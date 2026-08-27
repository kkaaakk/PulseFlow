#!/usr/bin/env python3
"""Validate a replay run against the real PulseFlow MySQL and Redis stores."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1].parent
REQUIRED_TABLES = {
    "user_profile",
    "user_event",
    "user_metric_hourly",
    "user_metric_daily",
    "user_behavior_summary",
    "user_tag",
    "campaign",
    "campaign_rule",
    "campaign_execution",
    "delivery_task",
    "delivery_record",
    "in_app_message",
    "push_record",
    "click_event",
    "attribution_task",
    "attribution_record",
    "data_compensation_task",
    "campaign_ai_draft",
    "ai_generation_record",
    "campaign_performance_summary",
    "campaign_ai_review",
}


def stable_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def pretty_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--run-dir", type=Path)
    parser.add_argument("--report-dir", type=Path)
    parser.add_argument("--wait-seconds", type=int, default=120)
    parser.add_argument("--poll-seconds", type=float, default=2.0)
    parser.add_argument("--mysql-bin", default=os.environ.get("PULSEFLOW_TEST_MYSQL_BIN", "mysql"))
    parser.add_argument("--mysql-host", default=os.environ.get("PULSEFLOW_TEST_MYSQL_HOST", "127.0.0.1"))
    parser.add_argument("--mysql-port", type=int, default=int(os.environ.get("PULSEFLOW_TEST_MYSQL_PORT", "13306")))
    parser.add_argument("--mysql-user", default=os.environ.get("PULSEFLOW_TEST_MYSQL_USER", "test"))
    parser.add_argument("--mysql-password", default=os.environ.get("PULSEFLOW_TEST_MYSQL_PASSWORD", "test"))
    parser.add_argument("--mysql-database", default=os.environ.get("PULSEFLOW_TEST_MYSQL_DATABASE", "pulseflow_test"))
    parser.add_argument("--redis-bin", default=os.environ.get("PULSEFLOW_TEST_REDIS_BIN", "redis-cli"))
    parser.add_argument("--redis-host", default=os.environ.get("PULSEFLOW_TEST_REDIS_HOST", "127.0.0.1"))
    parser.add_argument("--redis-port", type=int, default=int(os.environ.get("PULSEFLOW_TEST_REDIS_PORT", "16379")))
    parser.add_argument("--redis-password", default=os.environ.get("PULSEFLOW_TEST_REDIS_PASSWORD", ""))
    parser.add_argument("--redis-database", type=int, default=int(os.environ.get("PULSEFLOW_TEST_REDIS_DATABASE", "0")))
    parser.add_argument("--skip-mysql", action="store_true")
    parser.add_argument("--skip-redis", action="store_true")
    return parser.parse_args()


def assert_safe_store_target(args: argparse.Namespace) -> None:
    test_env = os.environ.get("PULSEFLOW_TEST_ENV", "").lower() == "test"
    loopback_hosts = {"127.0.0.1", "localhost", "::1"}
    mysql_test_name = "test" in args.mysql_database.lower() or args.mysql_database.lower().endswith("_test")
    # Both gates are required. APP_ENV=test alone is not enough to protect a
    # caller that accidentally leaves the production database name in place.
    if not test_env or not mysql_test_name:
        raise ValueError(
            "Refusing MySQL validation: require PULSEFLOW_TEST_ENV=test and a test database name."
        )
    if args.mysql_host.lower() not in loopback_hosts or args.redis_host.lower() not in loopback_hosts:
        explicitly_allowed = os.environ.get("PULSEFLOW_TEST_ALLOW_NONLOCAL", "false").lower() == "true"
        if not (test_env and explicitly_allowed):
            raise ValueError("Refusing non-loopback MySQL/Redis target without explicit test opt-in.")


def command_exists(command: str) -> bool:
    return Path(command).exists() if Path(command).is_absolute() else shutil.which(command) is not None


def sql_literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def run_mysql(args: argparse.Namespace, query: str) -> tuple[list[list[str]] | None, str | None]:
    if not command_exists(args.mysql_bin):
        return None, f"MySQL client not found: {args.mysql_bin}"
    command = [
        args.mysql_bin,
        "--batch",
        "--raw",
        "--skip-column-names",
        "--protocol=tcp",
        "-h",
        args.mysql_host,
        "-P",
        str(args.mysql_port),
        "-u",
        args.mysql_user,
        args.mysql_database,
        "-e",
        query,
    ]
    environment = os.environ.copy()
    environment["MYSQL_PWD"] = args.mysql_password
    try:
        result = subprocess.run(command, capture_output=True, text=True, env=environment, timeout=30)
    except (OSError, subprocess.TimeoutExpired) as error:
        return None, f"MySQL command failed: {error}"
    if result.returncode != 0:
        return None, result.stderr.strip() or f"MySQL exited with {result.returncode}"
    rows = [line.split("\t") for line in result.stdout.splitlines() if line != ""]
    return rows, None


def run_redis(args: argparse.Namespace, command_args: list[str]) -> tuple[list[str] | None, str | None]:
    if not command_exists(args.redis_bin):
        return None, f"Redis CLI not found: {args.redis_bin}"
    command = [
        args.redis_bin,
        "-h",
        args.redis_host,
        "-p",
        str(args.redis_port),
        "-n",
        str(args.redis_database),
        "--raw",
        *command_args,
    ]
    environment = os.environ.copy()
    if args.redis_password:
        environment["REDISCLI_AUTH"] = args.redis_password
    try:
        result = subprocess.run(command, capture_output=True, text=True, env=environment, timeout=30)
    except (OSError, subprocess.TimeoutExpired) as error:
        return None, f"Redis command failed: {error}"
    if result.returncode != 0:
        return None, result.stderr.strip() or f"Redis exited with {result.returncode}"
    return result.stdout.splitlines(), None


def add_check(checks: list[dict[str, Any]], check_id: str, module: str, description: str,
              status: str, expected: Any = None, actual: Any = None, query: str | None = None,
              reason: str | None = None) -> None:
    check: dict[str, Any] = {
        "checkId": check_id,
        "module": module,
        "description": description,
        "status": status,
        "expected": expected,
        "actual": actual,
    }
    if query:
        check["query"] = query
    if reason:
        check["reason"] = reason
    checks.append(check)


def load_manifest(manifest_path: Path) -> tuple[dict[str, Any], Path]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    data_path = manifest_path.parent / manifest["dataFile"]
    if not data_path.exists():
        raise FileNotFoundError(f"dataset file referenced by manifest not found: {data_path}")
    digest = hashlib.sha256(data_path.read_bytes()).hexdigest()
    if manifest.get("sha256") and digest != manifest["sha256"]:
        raise ValueError(f"dataset checksum mismatch: expected {manifest['sha256']}, actual {digest}")
    return manifest, data_path


def event_prefix(manifest: dict[str, Any]) -> str | None:
    samples = manifest.get("sampleEventIds") or []
    if not samples:
        return None
    return str(samples[0]).rsplit("-", 1)[0] + "-"


def decimal_text(value: Any) -> str:
    try:
        return format(Decimal(str(value or "0")).quantize(Decimal("0.01")), "f")
    except (InvalidOperation, ValueError):
        return str(value)


def expected_metrics(manifest: dict[str, Any]) -> dict[str, dict[str, Any]]:
    return manifest.get("expected", {}).get("mysql", {}).get(
        "metricTotalsByEventType", manifest.get("metricTotalsByEventType", {})
    )


def wait_for_expected_events(args: argparse.Namespace, query: str, expected: int) -> tuple[int | None, list[dict[str, Any]], str | None]:
    attempts: list[dict[str, Any]] = []
    deadline = time.monotonic() + max(0, args.wait_seconds)
    while True:
        rows, error = run_mysql(args, query)
        if error:
            return None, attempts, error
        try:
            actual = int(rows[0][0]) if rows and rows[0] else 0
        except (ValueError, IndexError):
            actual = 0
        attempts.append({"at": datetime.now(timezone.utc).isoformat(), "actual": actual})
        if actual >= expected or time.monotonic() >= deadline:
            return actual, attempts, None
        time.sleep(max(0.1, args.poll_seconds))


def validate_mysql(args: argparse.Namespace, manifest: dict[str, Any], checks: list[dict[str, Any]]) -> str:
    expected_unique = int(manifest.get("expected", {}).get("mysql", {}).get(
        "uniqueUserEventCount", manifest.get("uniqueEventIds", 0)
    ) or 0)
    prefix = event_prefix(manifest)
    if expected_unique <= 0 or not prefix:
        add_check(checks, "mysql-not-applicable", "MySQL", "Dataset has no canonical event rows to validate", "NOT_RUN",
                  expected=expected_unique, actual=None, reason="dataset has no eventId samples")
        return "NOT_RUN"

    prefix_sql = sql_literal(prefix + "%")
    count_query = f"SELECT COUNT(*) FROM user_event WHERE event_id LIKE {prefix_sql}"
    actual_count, attempts, error = wait_for_expected_events(args, count_query, expected_unique)
    if error:
        add_check(checks, "mysql-connection", "MySQL", "Connect to test database", "NOT_RUN",
                  expected="reachable", actual=None, query=count_query, reason=error)
        return "NOT_RUN"
    add_check(checks, "mysql-unique-events", "MySQL", "Canonical user_event count equals manifest",
              "PASS" if actual_count == expected_unique else "FAIL", expected=expected_unique,
              actual={"count": actual_count, "pollAttempts": attempts}, query=count_query)

    duplicate_query = (
        f"SELECT event_id, COUNT(*) FROM user_event WHERE event_id LIKE {prefix_sql} "
        "GROUP BY event_id HAVING COUNT(*) > 1 ORDER BY event_id LIMIT 20"
    )
    duplicate_rows, error = run_mysql(args, duplicate_query)
    if error:
        add_check(checks, "mysql-duplicate-rows", "MySQL", "No duplicate event_id rows", "NOT_RUN",
                  expected=[], actual=None, query=duplicate_query, reason=error)
        return "NOT_RUN"
    duplicate_actual = [{"eventId": row[0], "count": int(row[1])} for row in duplicate_rows or []]
    add_check(checks, "mysql-duplicate-rows", "MySQL", "No duplicate event_id rows",
              "PASS" if not duplicate_actual else "FAIL", expected=[], actual=duplicate_actual, query=duplicate_query)

    event_type_query = (
        f"SELECT event_type, COUNT(*) FROM user_event WHERE event_id LIKE {prefix_sql} "
        "GROUP BY event_type ORDER BY event_type"
    )
    type_rows, error = run_mysql(args, event_type_query)
    if error:
        add_check(checks, "mysql-event-type-counts", "MySQL", "Canonical event type counts match manifest", "NOT_RUN",
                  expected=manifest.get("eventTypeCounts", {}), actual=None, query=event_type_query, reason=error)
    else:
        actual_types = {row[0]: int(row[1]) for row in type_rows or []}
        expected_types = {key: int(value.get("eventCount", 0)) for key, value in expected_metrics(manifest).items()}
        add_check(checks, "mysql-event-type-counts", "MySQL", "Canonical event type counts match manifest",
                  "PASS" if actual_types == expected_types else "FAIL", expected=expected_types,
                  actual=actual_types, query=event_type_query)

    user_range = manifest.get("userIdRange", {})
    user_min = user_range.get("min")
    user_max = user_range.get("max")
    metric_query = (
        "SELECT event_type, COALESCE(SUM(event_count),0), COALESCE(SUM(duration_sum),0), "
        "CAST(COALESCE(SUM(amount_sum),0) AS DECIMAL(20,2)) "
        f"FROM user_metric_hourly WHERE user_id BETWEEN {int(user_min)} AND {int(user_max)} "
        "GROUP BY event_type ORDER BY event_type"
    ) if user_min is not None and user_max is not None else None
    metric_rows, error = run_mysql(args, metric_query) if metric_query else (None, "manifest has no user range")
    if error:
        add_check(checks, "mysql-hourly-metrics", "MySQL", "Hourly metric totals match canonical events", "NOT_RUN",
                  expected=expected_metrics(manifest), actual=None, query=metric_query, reason=error)
    else:
        actual_metrics = {
            row[0]: {"eventCount": int(row[1]), "durationSum": int(row[2]), "amountSum": decimal_text(row[3])}
            for row in metric_rows or []
        }
        expected_metric_values = {
            key: {
                "eventCount": int(value.get("eventCount", 0)),
                "durationSum": int(value.get("durationSum", 0)),
                "amountSum": decimal_text(value.get("amountSum", "0.00")),
            }
            for key, value in expected_metrics(manifest).items()
        }
        add_check(checks, "mysql-hourly-metrics", "MySQL", "Hourly metric totals match canonical events",
                  "PASS" if actual_metrics == expected_metric_values else "FAIL",
                  expected=expected_metric_values, actual=actual_metrics, query=metric_query)

    schema_query = (
        "SELECT table_name FROM information_schema.tables "
        "WHERE table_schema = DATABASE() ORDER BY table_name"
    )
    schema_rows, error = run_mysql(args, schema_query)
    if error:
        add_check(checks, "mysql-schema", "MySQL", "Flyway schema exposes expected PulseFlow tables", "NOT_RUN",
                  expected=sorted(REQUIRED_TABLES), actual=None, query=schema_query, reason=error)
    else:
        actual_tables = sorted(row[0] for row in schema_rows or [])
        missing = sorted(REQUIRED_TABLES - set(actual_tables))
        add_check(checks, "mysql-schema", "MySQL", "Flyway schema exposes expected PulseFlow tables",
                  "PASS" if not missing else "FAIL", expected=sorted(REQUIRED_TABLES),
                  actual={"missing": missing, "tables": actual_tables}, query=schema_query)

    campaign_details = manifest.get("scenarioDetails", {})
    campaign_name = campaign_details.get("campaignFixtureName")
    if campaign_name:
        campaign_query = (
            "SELECT id, name FROM campaign WHERE name = " + sql_literal(campaign_name) + " ORDER BY id DESC LIMIT 1"
        )
        campaign_rows, error = run_mysql(args, campaign_query)
        if error:
            add_check(checks, "campaign-fixture", "Campaign", "Campaign SQL fixture is present", "NOT_RUN",
                      expected=campaign_name, actual=None, query=campaign_query, reason=error)
        elif not campaign_rows:
            add_check(checks, "campaign-fixture", "Campaign", "Campaign SQL fixture is present", "NOT_RUN",
                      expected=campaign_name, actual=None, query=campaign_query,
                      reason="run testing/scripts/prepare-campaign-fixture.ps1 first")
        else:
            campaign_id = campaign_rows[0][0]
            detail = campaign_details.get("frequency", {})
            task_query = (
                f"SELECT status, COUNT(*) FROM delivery_task WHERE campaign_id = {int(campaign_id)} "
                "GROUP BY status ORDER BY status"
            )
            task_rows, task_error = run_mysql(args, task_query)
            actual_tasks = {row[0]: int(row[1]) for row in task_rows or []} if not task_error else None
            expected_tasks = int(detail.get("expectedDeliveryTaskCount", 0))
            actual_task_count = sum(actual_tasks.values()) if actual_tasks is not None else None
            add_check(checks, "campaign-delivery-tasks", "Campaign", "Frequency fixture delivery task total",
                      "PASS" if actual_task_count == expected_tasks else ("NOT_RUN" if task_error else "FAIL"),
                      expected=expected_tasks, actual=actual_tasks, query=task_query, reason=task_error)

        attribution = campaign_details.get("attribution", {})
        target_event_id = attribution.get("targetEventId")
        if target_event_id:
            attribution_query = (
                "SELECT target_event_id, campaign_id, task_id, attribution_model "
                "FROM attribution_record WHERE target_event_id = " + sql_literal(target_event_id)
            )
            attribution_rows, error = run_mysql(args, attribution_query)
            expected_attr = {
                "targetEventId": target_event_id,
                "campaignId": attribution.get("expectedCampaignId"),
                "taskId": attribution.get("expectedTaskId"),
                "attributionModel": attribution.get("expectedModel", "CLICK_LAST_TOUCH"),
            }
            actual_attr = None
            if attribution_rows:
                row = attribution_rows[0]
                actual_attr = {"targetEventId": row[0], "campaignId": int(row[1]) if row[1] else None,
                               "taskId": int(row[2]) if row[2] else None, "attributionModel": row[3]}
            add_check(checks, "attribution-last-touch", "Attribution", "Last-touch attribution matches fixture",
                      "PASS" if actual_attr == expected_attr else ("NOT_RUN" if error else "FAIL"),
                      expected=expected_attr, actual=actual_attr, query=attribution_query, reason=error)
    return "FAIL" if any(check["status"] == "FAIL" for check in checks) else "PASS"


def validate_redis(args: argparse.Namespace, manifest: dict[str, Any], checks: list[dict[str, Any]]) -> str:
    sample_ids = manifest.get("sampleEventIds") or []
    sample_users = manifest.get("sampleUserIds") or []
    if not sample_ids:
        add_check(checks, "redis-not-applicable", "Redis", "Dataset has no sample event ids", "NOT_RUN",
                  expected=0, actual=None, reason="dataset has no eventId samples")
        return "NOT_RUN"
    processed: dict[str, Any] = {}
    for event_id in sample_ids:
        output, error = run_redis(args, ["EXISTS", "event:processed:" + str(event_id)])
        if error:
            add_check(checks, "redis-connection", "Redis", "Connect to test Redis", "NOT_RUN",
                      expected="reachable", actual=None, reason=error)
            return "NOT_RUN"
        exists = int(output[0]) if output else 0
        ttl_output, ttl_error = run_redis(args, ["TTL", "event:processed:" + str(event_id)])
        ttl = int(ttl_output[0]) if ttl_output and ttl_output[0] else -2
        processed[str(event_id)] = {"exists": exists, "ttlSeconds": ttl, "ttlError": ttl_error}
    expected_count = int(manifest.get("expected", {}).get("redis", {}).get(
        "sampleProcessedFlagCount", len(sample_ids)
    ))
    actual_count = sum(1 for item in processed.values() if item["exists"] == 1)
    add_check(checks, "redis-processed-flags", "Redis", "Sample event processed flags exist",
              "PASS" if actual_count == expected_count else "FAIL", expected=expected_count,
              actual={"count": actual_count, "details": processed})

    ttl_min = int(manifest.get("expected", {}).get("redis", {}).get("processedFlagTtlSecondsAtLeast", 1))
    ttl_bad = {event_id: item["ttlSeconds"] for event_id, item in processed.items() if item["ttlSeconds"] < ttl_min}
    add_check(checks, "redis-processed-ttl", "Redis", "Processed flag TTL remains positive",
              "PASS" if not ttl_bad else "FAIL", expected=f">={ttl_min}", actual=ttl_bad or processed)

    realtime: dict[str, Any] = {}
    for user_id in sample_users:
        output, error = run_redis(args, ["EXISTS", f"user:rt:{int(user_id)}"])
        if error:
            add_check(checks, "redis-realtime-profile", "Redis", "Sample realtime profile keys exist", "NOT_RUN",
                      expected=True, actual=None, reason=error)
            return "NOT_RUN"
        realtime[str(user_id)] = int(output[0]) if output else 0
    add_check(checks, "redis-realtime-profile", "Redis", "Sample realtime profile keys exist",
              "PASS" if all(value == 1 for value in realtime.values()) else "FAIL",
              expected={str(user_id): 1 for user_id in sample_users}, actual=realtime)
    return "FAIL" if any(check["status"] == "FAIL" for check in checks) else "PASS"


def read_replay_failures(run_dir: Path | None) -> list[dict[str, Any]]:
    if not run_dir:
        return []
    path = run_dir / "failures.json"
    if not path.exists():
        return []
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
        return value if isinstance(value, list) else []
    except json.JSONDecodeError:
        return [{"module": "replay-report", "exceptionOrLog": "invalid failures.json"}]


def write_summary(report_dir: Path, manifest: dict[str, Any], checks: list[dict[str, Any]],
                  replay_failures: list[dict[str, Any]], dependency_not_run: bool) -> int:
    failures = list(replay_failures)
    for check in checks:
        if check["status"] == "FAIL":
            failures.append({
                "module": check["module"],
                "checkId": check["checkId"],
                "input": {"query": check.get("query")},
                "expected": check.get("expected"),
                "actual": check.get("actual"),
                "exceptionOrLog": check.get("reason"),
                "stableReproduction": True,
            })
    statuses = {check["status"] for check in checks}
    if "FAIL" in statuses or replay_failures:
        status = "FAIL"
    elif dependency_not_run or "NOT_RUN" in statuses:
        status = "NOT_RUN"
    else:
        status = "PASS"
    summary = {
        "status": status,
        "datasetId": manifest.get("datasetId"),
        "seed": manifest.get("seed"),
        "scale": manifest.get("scale"),
        "checkedAt": datetime.now(timezone.utc).isoformat(),
        "checkCounts": {key: sum(1 for check in checks if check["status"] == key)
                        for key in ("PASS", "FAIL", "NOT_RUN")},
        "replayFailureCount": len(replay_failures),
        "failureCount": len(failures),
        "checks": checks,
    }
    (report_dir / "summary.json").write_text(pretty_json(summary) + "\n", encoding="utf-8")
    (report_dir / "failures.json").write_text(pretty_json(failures) + "\n", encoding="utf-8")
    k6_path = report_dir / "k6-summary.json"
    if not k6_path.exists():
        k6_path.write_text(pretty_json({"status": "NOT_RUN", "reason": "No k6 summary supplied"}) + "\n", encoding="utf-8")

    lines = [
        f"# PulseFlow validation report — {manifest.get('datasetId')}",
        "",
        f"Status: **{status}**",
        f"Seed: `{manifest.get('seed')}`  Scale: `{manifest.get('scale')}`",
        "",
        "| Check | Module | Status |",
        "|---|---|---|",
    ]
    for check in checks:
        lines.append(f"| `{check['checkId']}` | {check['module']} | **{check['status']}** |")
    lines.extend(["", "## Failures", ""])
    if failures:
        for failure in failures:
            lines.append(f"- `{failure.get('module')}` `{failure.get('checkId', '')}`: {failure.get('exceptionOrLog') or 'assertion mismatch'}")
    else:
        lines.append("None.")
    (report_dir / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"validation {status}: checks={len(checks)} failures={len(failures)} report={report_dir}")
    return 0 if status == "PASS" else (2 if status == "NOT_RUN" else 1)


def main() -> int:
    args = parse_args()
    try:
        assert_safe_store_target(args)
        manifest, data_path = load_manifest(args.manifest.resolve())
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as error:
        print(f"validation setup failed: {error}", file=sys.stderr)
        return 2

    report_dir = (args.report_dir or args.run_dir or ROOT / "testing" / "reports" / "validation").resolve()
    report_dir.mkdir(parents=True, exist_ok=True)
    run_dir = args.run_dir.resolve() if args.run_dir else None
    checks: list[dict[str, Any]] = []
    dependency_not_run = False

    if args.skip_mysql:
        add_check(checks, "mysql-explicit-skip", "MySQL", "MySQL validation was explicitly skipped", "NOT_RUN",
                  reason="--skip-mysql")
        dependency_not_run = True
    else:
        before = len(checks)
        validate_mysql(args, manifest, checks)
        dependency_not_run = dependency_not_run or any(
            check["status"] == "NOT_RUN" and check["module"] == "MySQL" for check in checks[before:]
        )

    if args.skip_redis:
        add_check(checks, "redis-explicit-skip", "Redis", "Redis validation was explicitly skipped", "NOT_RUN",
                  reason="--skip-redis")
        dependency_not_run = True
    else:
        before = len(checks)
        validate_redis(args, manifest, checks)
        dependency_not_run = dependency_not_run or any(
            check["status"] == "NOT_RUN" and check["module"] == "Redis" for check in checks[before:]
        )

    (report_dir / "mysql-validation.json").write_text(
        pretty_json({
            "datasetId": manifest.get("datasetId"),
            "status": "FAIL" if any(c["status"] == "FAIL" for c in checks if c["module"] == "MySQL")
            else ("NOT_RUN" if any(c["status"] == "NOT_RUN" for c in checks if c["module"] == "MySQL") else "PASS"),
            "checks": [check for check in checks if check["module"] == "MySQL"],
        }) + "\n",
        encoding="utf-8",
    )
    (report_dir / "redis-validation.json").write_text(
        pretty_json({
            "datasetId": manifest.get("datasetId"),
            "status": "FAIL" if any(c["status"] == "FAIL" for c in checks if c["module"] == "Redis")
            else ("NOT_RUN" if any(c["status"] == "NOT_RUN" for c in checks if c["module"] == "Redis") else "PASS"),
            "checks": [check for check in checks if check["module"] == "Redis"],
        }) + "\n",
        encoding="utf-8",
    )

    replay_failures = read_replay_failures(run_dir)
    return write_summary(report_dir, manifest, checks, replay_failures, dependency_not_run)


if __name__ == "__main__":
    raise SystemExit(main())
