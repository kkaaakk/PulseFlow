package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.sql.*;

/**
 * Offline cutover only: stop all application instances and jobs before running.
 * MySQL DDL is non-transactional. Validate every ambiguity before the first DDL,
 * commit and verify backfill before dropping the old table as the final operation.
 */
public class V9__consolidate_delivery_task extends BaseJavaMigration {
    @Override public boolean canExecuteInTransaction() { return false; }
    @Override public Integer getChecksum() { return 9001; }

    @Override public void migrate(Context context) throws Exception {
        Connection c = context.getConnection();
        reject(c, "orphan or mismatched send record", """
                SELECT r.id FROM delivery_record r LEFT JOIN delivery_task t ON t.id=r.task_id
                WHERE t.id IS NULL OR r.user_id<>t.user_id OR r.campaign_id<>t.campaign_id
                OR r.channel<>t.channel
                """);
        reject(c, "unknown task state/channel or invalid retry count", """
                SELECT id FROM delivery_task WHERE status IS NULL OR channel NOT IN ('IN_APP','PUSH','EMAIL')
                OR retry_count IS NULL OR retry_count<0
                """);
        reject(c, "missing record time (success or legacy metric cohort cannot be inferred)", """
                SELECT id FROM delivery_record WHERE sent_at IS NULL OR status IS NULL
                """);
        reject(c, "cancelled task has successful send record", """
                SELECT r.id FROM delivery_record r JOIN delivery_task t ON t.id=r.task_id
                WHERE r.status='SENT' AND t.status='CANCELLED'
                """);
        reject(c, "channel receipt ownership/channel conflict", """
                SELECT t.id FROM delivery_task t JOIN in_app_message m ON m.business_key=t.id
                WHERE t.channel<>'IN_APP' OR t.user_id<>m.user_id OR t.campaign_id<>m.campaign_id
                UNION ALL
                SELECT t.id FROM delivery_task t JOIN push_record p ON p.business_key=t.id
                WHERE t.channel<>'PUSH' OR t.user_id<>p.user_id OR t.campaign_id<>p.campaign_id
                """);
        reject(c, "failed record conflicts with success evidence", """
                SELECT r.id FROM delivery_record r JOIN delivery_task t ON t.id=r.task_id
                LEFT JOIN in_app_message m ON m.business_key=t.id
                LEFT JOIN push_record p ON p.business_key=t.id
                WHERE r.status='FAILED' AND (t.status='SENT' OR m.id IS NOT NULL OR p.id IS NOT NULL)
                """);
        reject(c, "success record precedes actual channel receipt", """
                SELECT r.id FROM delivery_record r
                LEFT JOIN in_app_message m ON m.business_key=r.task_id AND r.channel='IN_APP'
                LEFT JOIN push_record p ON p.business_key=r.task_id AND r.channel='PUSH'
                WHERE r.status='SENT' AND (m.created_at>r.sent_at OR p.pushed_at>r.sent_at)
                """);
        reject(c, "cancelled channel send or SENT task without verifiable success", """
                SELECT t.id FROM delivery_task t LEFT JOIN delivery_record r ON r.task_id=t.id
                LEFT JOIN in_app_message m ON m.business_key=t.id
                LEFT JOIN push_record p ON p.business_key=t.id
                WHERE (t.status='CANCELLED' AND (m.id IS NOT NULL OR p.id IS NOT NULL))
                OR (t.status='SENT' AND r.id IS NULL AND m.id IS NULL AND p.id IS NULL)
                """);

        reject(c, "EMAIL success outcome is ambiguous", """
                SELECT r.id FROM delivery_record r JOIN delivery_task t ON t.id=r.task_id
                WHERE r.channel='EMAIL' AND r.status='SENT' AND t.status<>'SENT'
                """);

        long oldTotal = scalar(c, "SELECT COUNT(*) FROM delivery_record");
        long oldSuccess = scalar(c, "SELECT COUNT(*) FROM delivery_record WHERE status='SENT'");
        long recovered = scalar(c, """
                SELECT COUNT(*) FROM delivery_task t LEFT JOIN delivery_record r ON r.task_id=t.id
                LEFT JOIN in_app_message m ON m.business_key=t.id
                LEFT JOIN push_record p ON p.business_key=t.id
                WHERE r.id IS NULL AND (m.id IS NOT NULL OR p.id IS NOT NULL)
                """);
        // IF guards allow an operator to rerun after a MySQL DDL failure and Flyway repair.
        addColumn(c, "sent_at", "DATETIME(3) NULL COMMENT 'Actual successful channel send time'");
        addColumn(c, "first_send_attempt_at", "DATETIME(3) NULL COMMENT 'First send fact / legacy metric cohort'");
        execute(c, "ALTER TABLE delivery_task MODIFY processing_at DATETIME(3) NULL");
        execute(c, "ALTER TABLE in_app_message MODIFY created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)");
        execute(c, "ALTER TABLE push_record MODIFY pushed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)");
        c.setAutoCommit(false);
        try {
            execute(c, """
                    UPDATE delivery_task t JOIN delivery_record r ON r.task_id=t.id
                    LEFT JOIN in_app_message m ON m.business_key=t.id AND t.channel='IN_APP'
                    LEFT JOIN push_record p ON p.business_key=t.id AND t.channel='PUSH'
                    SET t.first_send_attempt_at=r.sent_at,
                        t.sent_at=CASE WHEN r.status='SENT'
                            THEN COALESCE(m.created_at,p.pushed_at,r.sent_at) ELSE NULL END,
                        t.status=CASE WHEN r.status='SENT' THEN 'SENT' ELSE t.status END,
                        t.last_error=COALESCE(NULLIF(t.last_error,''),r.error_msg),
                        t.next_retry_at=CASE WHEN r.status='SENT' THEN NULL ELSE t.next_retry_at END,
                        t.updated_at=t.updated_at
                    """);
            execute(c, """
                    UPDATE delivery_task t LEFT JOIN delivery_record r ON r.task_id=t.id
                    LEFT JOIN in_app_message m ON m.business_key=t.id AND t.channel='IN_APP'
                    LEFT JOIN push_record p ON p.business_key=t.id AND t.channel='PUSH'
                    SET t.first_send_attempt_at=COALESCE(m.created_at,p.pushed_at),
                        t.sent_at=COALESCE(m.created_at,p.pushed_at), t.status='SENT', t.next_retry_at=NULL,
                        t.updated_at=t.updated_at
                    WHERE r.id IS NULL AND (m.id IS NOT NULL OR p.id IS NOT NULL)
                    """);
            if (scalar(c, "SELECT COUNT(*) FROM delivery_task WHERE first_send_attempt_at IS NOT NULL") != oldTotal+recovered
                    || scalar(c, "SELECT COUNT(*) FROM delivery_task WHERE status='SENT' AND sent_at IS NOT NULL") != oldSuccess+recovered)
                throw new SQLException("V9 metric reconciliation failed; delivery_record retained");
            reject(c, "backfill count/cohort mismatch", """
                    SELECT r.id FROM delivery_record r JOIN delivery_task t ON t.id=r.task_id
                    WHERE t.first_send_attempt_at<>r.sent_at
                    OR (r.status='SENT' AND (t.status<>'SENT' OR t.sent_at IS NULL))
                    OR (r.status='FAILED' AND t.sent_at IS NOT NULL)
                    """);
            c.commit();
        } catch (Exception e) {
            c.rollback();
            throw e;
        } finally { c.setAutoCommit(true); }
        addIndex(c, "idx_campaign_attempt", "(campaign_id, first_send_attempt_at)");
        addIndex(c, "idx_campaign_sent", "(campaign_id, status, sent_at)");
        System.out.printf("V9 delivery cutover: legacy attempted=%d, legacy successful=%d, recovered channel-only=%d%n",
                oldTotal, oldSuccess, recovered);
        execute(c, "DROP TABLE delivery_record");
    }

    private void reject(Connection c, String reason, String query) throws SQLException {
        try (Statement s=c.createStatement(); ResultSet rs=s.executeQuery(query+" LIMIT 20")) {
            StringBuilder ids=new StringBuilder();
            while(rs.next()) { if (!ids.isEmpty()) ids.append(','); ids.append(rs.getLong(1)); }
            if (!ids.isEmpty()) throw new SQLException("V9 blocked: "+reason+"; sample IDs="+ids
                    + ". Resolve against channel evidence while old writers are stopped. No old table was dropped.");
        }
    }
    private long scalar(Connection c, String q) throws SQLException {
        try (Statement s=c.createStatement(); ResultSet rs=s.executeQuery(q)) { rs.next(); return rs.getLong(1); }
    }
    private void execute(Connection c, String q) throws SQLException {
        try (Statement s=c.createStatement()) { s.execute(q); }
    }
    private void addColumn(Connection c, String name, String definition) throws SQLException {
        if (scalar(c,"SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() "
                +"AND table_name='delivery_task' AND column_name='"+name+"'")==0)
            execute(c,"ALTER TABLE delivery_task ADD COLUMN "+name+" "+definition);
    }
    private void addIndex(Connection c, String name, String definition) throws SQLException {
        if (scalar(c,"SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() "
                +"AND table_name='delivery_task' AND index_name='"+name+"'")==0)
            execute(c,"ALTER TABLE delivery_task ADD INDEX "+name+" "+definition);
    }
}
