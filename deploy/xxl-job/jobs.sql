-- Idempotent registration for the XXL-JOB 2.4.1 schema.
-- New jobs start enabled. Restarts preserve existing cron and pause settings.
START TRANSACTION;
INSERT INTO xxl_job_group (app_name, title, address_type, address_list, update_time)
SELECT 'pulseflow-executor', 'PulseFlow', 1, 'http://java:9999/', NOW()
WHERE NOT EXISTS (SELECT 1 FROM xxl_job_group WHERE app_name = 'pulseflow-executor');
SET @pulseflow_group = (SELECT MIN(id) FROM xxl_job_group WHERE app_name = 'pulseflow-executor');

INSERT INTO xxl_job_info (
    job_group, job_desc, add_time, update_time, author, alarm_email,
    schedule_type, schedule_conf, misfire_strategy, executor_route_strategy,
    executor_handler, executor_param, executor_block_strategy, executor_timeout,
    executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime,
    child_jobid, trigger_status, trigger_last_time, trigger_next_time
)
SELECT @pulseflow_group, defaults.description, NOW(), NOW(), 'PulseFlow', '',
    'CRON', defaults.cron, 'DO_NOTHING', 'FIRST', defaults.handler, '',
    'SERIAL_EXECUTION', 0, 0, 'BEAN', '', 'PulseFlow default schedule', NOW(), '', 1, 0, 0
FROM (
    SELECT 'dailyMetricJob' AS handler, 'Daily metric aggregation' AS description, '0 0 * * * ?' AS cron
    UNION ALL SELECT 'windowMetricJob', 'Window profile aggregation', '0 0/5 * * * ?'
    UNION ALL SELECT 'tagRecalcJob', 'User tag recalculation', '0 2/5 * * * ?'
    UNION ALL SELECT 'campaignSelectionJob', 'Scheduled campaign selection', '0/10 * * * * ?'
    UNION ALL SELECT 'campaignPerformanceSummaryJob', 'Campaign performance summary', '0 1/5 * * * ?'
    UNION ALL SELECT 'dispatchRetryJob', 'Kafka dispatch retries', '0/15 * * * * ?'
    UNION ALL SELECT 'compensationJob', 'Event and delivery compensation', '5/30 * * * * ?'
    UNION ALL SELECT 'retryCompensationJob', 'Compensation retries', '10/30 * * * * ?'
    UNION ALL SELECT 'delayTaskRecoveryJob', 'Delayed task recovery', '20 * * * * ?'
    UNION ALL SELECT 'dataCleanupJob', 'Expired business data cleanup', '0 30 3 * * ?'
) AS defaults
WHERE NOT EXISTS (
    SELECT 1 FROM xxl_job_info existing
    WHERE existing.job_group = @pulseflow_group AND existing.executor_handler = defaults.handler
);
COMMIT;
