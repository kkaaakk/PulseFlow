-- XXL-JOB Admin 2.4.1 database. Select its schema explicitly, pause the admin
-- scheduler nodes first, and keep the job definition/history for audit.
SET @pulseflow_executor_app = COALESCE(@pulseflow_executor_app, 'pulseflow-executor');
START TRANSACTION;
UPDATE xxl_job_info job
JOIN xxl_job_group executor ON executor.id = job.job_group
SET job.trigger_status = 0, job.trigger_last_time = 0, job.trigger_next_time = 0, job.update_time = NOW()
WHERE executor.app_name = @pulseflow_executor_app
  AND job.glue_type = 'BEAN'
  AND job.executor_handler = 'tagRecalcJob'
  AND (job.trigger_status <> 0 OR job.trigger_last_time <> 0 OR job.trigger_next_time <> 0);
COMMIT;

-- Inventory this executor's actual schedules and confirm tagRecalcJob is stopped.
SELECT job.id, job.executor_handler, job.schedule_type, job.schedule_conf, job.trigger_status
FROM xxl_job_info job
JOIN xxl_job_group executor ON executor.id = job.job_group
WHERE executor.app_name = @pulseflow_executor_app
ORDER BY job.executor_handler, job.id;

-- Review and remove any scheduled parent references through Admin before resuming.
SELECT parent.id AS parent_id, retired.id AS retired_child_id
FROM xxl_job_info retired
JOIN xxl_job_group executor ON executor.id = retired.job_group
JOIN xxl_job_info parent ON FIND_IN_SET(retired.id, REPLACE(parent.child_jobid, ' ', '')) > 0
WHERE executor.app_name = @pulseflow_executor_app
  AND retired.glue_type = 'BEAN' AND retired.executor_handler = 'tagRecalcJob';
