#!/bin/sh
set -eu

# Hash the local administrator password without putting its plaintext into SQL.
password_hash=$(printf '%s' "$XXL_JOB_ADMIN_PASSWORD" | md5sum | cut -d ' ' -f 1)
mysql --protocol=TCP -hxxl-job-mysql -uxxl_job -Dxxl_job \
  --execute="UPDATE xxl_job_user SET password='$password_hash' WHERE username='admin';"
mysql --protocol=TCP -hxxl-job-mysql -uxxl_job -Dxxl_job < /bootstrap/jobs.sql
printf '%s\n' 'PulseFlow schedules registered; existing schedules and pause states retained.'
