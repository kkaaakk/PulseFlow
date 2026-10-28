"""Verify the running full stack; paid Agent calls require --real-agent."""

import argparse
from datetime import datetime, timedelta, timezone
import http.cookiejar
import json
import shlex
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
EXPECTED_HANDLERS = {
    "dailyMetricJob", "windowMetricJob", "tagRecalcJob", "campaignSelectionJob",
    "campaignPerformanceSummaryJob", "dispatchRetryJob", "compensationJob",
    "retryCompensationJob", "delayTaskRecoveryJob", "dataCleanupJob",
}


def settings() -> dict[str, str]:
    values = {}
    for line in (ROOT / ".env").read_text(encoding="utf-8-sig").splitlines():
        if not line.strip() or line.lstrip().startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1].replace("\\'", "'")
        values[key.strip()] = value
    return values


def docker(*arguments: str) -> str:
    result = subprocess.run(
        ["docker", "compose", "--project-directory", str(ROOT), *arguments],
        cwd=ROOT, capture_output=True, text=True, encoding="utf-8",
    )
    if result.returncode:
        raise RuntimeError("Docker verification command failed; inspect service health/logs")
    return result.stdout.strip()


def query(sql: str) -> list[list[str]]:
    command = ('MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --protocol=TCP '
               '-h127.0.0.1 -uxxl_job -Dxxl_job --batch --skip-column-names -e ' + shlex.quote(sql))
    output = docker("exec", "-T", "xxl-job-mysql", "sh", "-c", command)
    return [row.split("\t") for row in output.splitlines() if row]


def request(opener, url: str, *, body=None, form=None, token=None, expect_json=True):
    headers = {}
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    elif form is not None:
        data = urllib.parse.urlencode(form).encode()
    if token:
        headers["token"] = token
    try:
        with opener.open(urllib.request.Request(url, data=data, headers=headers), timeout=20) as response:
            payload = response.read()
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"HTTP verification failed: {error.code}") from None
    except urllib.error.URLError:
        raise RuntimeError("HTTP verification could not connect to a service") from None
    return json.loads(payload) if expect_json else payload


def business(opener, url: str, **arguments):
    envelope = request(opener, url, **arguments)
    if envelope.get("code") != 200:
        raise RuntimeError(f"Business API failed: code={envelope.get('code')}")
    return envelope.get("data")


def verify(trigger_jobs: bool, real_agent: bool) -> None:
    config = settings()
    web = "http://127.0.0.1:" + config.get("PULSEFLOW_WEB_PORT", "5173")
    admin = "http://127.0.0.1:" + config.get("XXL_JOB_ADMIN_PORT", "8081") + "/xxl-job-admin"
    jaeger = "http://127.0.0.1:" + config.get("JAEGER_UI_PORT", "16687")
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    assert b"<html" in request(opener, web + "/investigations", expect_json=False).lower()
    login = business(opener, web + "/api/auth/login", body={
        "operatorId": 1024, "password": config.get("PULSEFLOW_OPERATOR_PASSWORD", "pulseflow-local"),
    })
    token = login["tokenValue"]
    assert business(opener, web + "/api/auth/me", token=token)["operatorId"] == 1024
    statuses = business(opener, web + "/api/system/status", token=token)
    assert all(statuses.get(name) == "UP" for name in ("backend", "mysql", "redis", "kafka")), statuses
    sessions = docker("exec", "-T", "redis", "redis-cli", "--scan", "--pattern", "*:login:token:*")
    assert sessions, "Login did not create a Redis session"
    assert business(opener, web + "/api/simulator/bulk?count=0", token=token)["total"] == 0
    print("PASS Web routing, real API proxy, login, Redis session, MySQL/Redis/Kafka health")

    rows = query("SELECT i.id, i.executor_handler, i.trigger_status FROM xxl_job_info i "
                 "JOIN xxl_job_group g ON g.id=i.job_group WHERE g.app_name='pulseflow-executor'")
    assert {row[1] for row in rows} == EXPECTED_HANDLERS and len(rows) == 10
    assert all(row[2] == "1" for row in rows), "One or more business schedules are paused"
    print("PASS All 10 XXL-JOB business schedules registered and enabled")

    if trigger_jobs:
        logged_in = request(opener, admin + "/login", form={
            "userName": "admin", "password": config["XXL_JOB_ADMIN_PASSWORD"], "ifRemember": "off",
        })
        assert logged_in.get("code") == 200, "XXL-JOB login failed"
        for row in rows:
            previous = query("SELECT COALESCE(MAX(id),0) FROM xxl_job_log WHERE job_id=" + row[0])[0][0]
            triggered = request(opener, admin + "/jobinfo/trigger", form={"id": row[0], "executorParam": ""})
            assert triggered.get("code") == 200, "XXL-JOB trigger failed"
            deadline = time.monotonic() + 45
            while True:
                completed = query("SELECT COUNT(*) FROM xxl_job_log WHERE job_id=" + row[0]
                                  + " AND id>" + previous + " AND handle_code=200")[0][0]
                if int(completed):
                    print("PASS Executed " + row[1])
                    break
                if time.monotonic() > deadline:
                    raise RuntimeError("XXL-JOB did not complete successfully: " + row[1])
                time.sleep(2)

    if real_agent:
        to_time = datetime.now(timezone.utc).replace(microsecond=0)
        from_time = to_time - timedelta(days=7)
        question = (f"调用 query_metric 查询 SENT 触达发送量，时间范围为 "
                    f"{from_time.isoformat()} 到 {to_time.isoformat()}。引用查询结果，"
                    "没有数据则说明证据不足。")
        investigation = business(opener, web + "/api/investigations", token=token,
                                 body={"question": question})
        investigation_id = investigation["id"]
        deadline = time.monotonic() + 120
        while investigation.get("status") == "RUNNING":
            if time.monotonic() > deadline:
                business(opener, web + "/api/investigations/" + investigation_id + "/cancel",
                         token=token, body={})
                raise RuntimeError("Real Agent investigation timed out")
            time.sleep(2)
            investigation = business(opener, web + "/api/investigations/" + investigation_id, token=token)
        print("Real Agent terminal status: " + str(investigation.get("status")))
        assert investigation.get("status") in {"COMPLETED", "INSUFFICIENT_EVIDENCE"}, (
            "Real Agent investigation did not complete"
        )
        assert investigation.get("evidence"), "Real Agent did not collect Java evidence"
        print("PASS Real model, Azure PII, Java tools and investigation persistence")

    deadline = time.monotonic() + 25
    while True:
        services = request(opener, jaeger + "/api/services").get("data", [])
        if "pulseflow-java" in services and (not real_agent or "pulseflow-agent" in services):
            print("PASS Trace export visible in Jaeger")
            break
        if time.monotonic() > deadline:
            raise RuntimeError("Expected Java/Agent traces are not visible in Jaeger")
        time.sleep(2)
    business(opener, web + "/api/auth/logout", token=token, body={})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--trigger-jobs", action="store_true", help="Run all 10 jobs once, including retention cleanup")
    parser.add_argument("--real-agent", action="store_true", help="Run one bounded paid model/Azure investigation")
    arguments = parser.parse_args()
    verify(arguments.trigger_jobs, arguments.real_agent)
