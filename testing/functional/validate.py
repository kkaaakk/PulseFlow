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
from collections import defaultdict
from datetime import datetime, timedelta, timezone
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

STATUS_LABELS = {
    "PASS": "✅ PASS（通过）",
    "FAIL": "❌ FAIL（失败）",
    "NOT_RUN": "⚠️ NOT_RUN（未执行）",
}

MODULE_NAMES = {
    "MySQL": "MySQL（数据库）",
    "Redis": "Redis（缓存）",
    "Profile": "Profile（用户画像）",
    "Campaign": "Campaign（营销活动）",
    "Attribution": "Attribution（归因）",
    "Compensation": "Compensation（补偿）",
    "Replay": "Replay（业务重放）",
    "AI": "AI（智能审核）",
}

CHECK_NAMES = {
    "mysql-not-applicable": "MySQL 校验适用性",
    "mysql-connection": "MySQL 测试库连接",
    "mysql-unique-events": "事件落库数量",
    "mysql-duplicate-rows": "eventId 唯一性",
    "mysql-conflicting-canonical": "冲突重复事件保留首条规范记录",
    "mysql-event-type-counts": "事件类型计数",
    "mysql-hourly-metrics": "小时指标聚合",
    "mysql-daily-metrics": "日指标聚合",
    "mysql-canonical-samples": "规范事件样本",
    "mysql-schema": "数据库表结构",
    "mysql-compensation": "数据补偿任务",
    "mysql-explicit-skip": "MySQL 校验主动跳过",
    "scheduled-outputs": "定时任务输出范围",
    "profile-window-metrics": "用户窗口指标",
    "profile-user-tags": "用户标签计算",
    "redis-not-applicable": "Redis 校验适用性",
    "redis-connection": "Redis 测试库连接",
    "redis-processed-flags": "Redis 幂等处理标记",
    "redis-processed-ttl": "Redis 幂等标记 TTL",
    "redis-realtime-profile": "Redis 实时画像键",
    "redis-business-values": "Redis 业务画像值",
    "redis-realtime-values": "Redis 实时画像值",
    "redis-daily-values": "Redis 日实时指标",
    "redis-cart-values": "Redis 购物车状态",
    "redis-explicit-skip": "Redis 校验主动跳过",
    "campaign-fixture": "Campaign 测试预置数据",
    "campaign-execution": "营销活动执行记录",
    "campaign-delivery-tasks": "营销投放任务",
    "campaign-delivery-records": "营销投放记录",
    "campaign-channel-records": "渠道发送记录",
    "campaign-frequency-redis": "营销频控计数",
    "campaign-frequency-reservations": "营销频控配额预留",
    "campaign-performance-summary": "营销活动效果汇总",
    "campaign-ai-review": "营销活动 AI 审核",
    "attribution-click-event": "归因点击事件",
    "attribution-last-touch": "末次点击归因",
    "attribution-task-state": "归因任务状态",
    "replay-request": "业务重放请求",
}

STATUS_SORT_ORDER = {"FAIL": 0, "NOT_RUN": 1, "PASS": 2}


def stable_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def pretty_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2)


def status_label(status: Any) -> str:
    value = str(status)
    return STATUS_LABELS.get(value, value)


def module_label(module: Any) -> str:
    value = str(module or "未知模块")
    return MODULE_NAMES.get(value, value)


def check_label(check: dict[str, Any]) -> str:
    check_id = str(check.get("checkId") or "")
    return CHECK_NAMES.get(check_id, str(check.get("description") or check_id or "未命名校验"))


def markdown_value(value: Any, limit: int = 180) -> str:
    if value is None or value == "":
        return "—"
    if isinstance(value, (dict, list, tuple, set)):
        return "详见 `summary.json`"
    text = str(value).replace("\r", " ").replace("\n", " ").replace("|", "\\|")
    if len(text) > limit:
        return text[: max(1, limit - 1)] + "…"
    return text


def markdown_reason(value: Any) -> str:
    return markdown_value(value, limit=240)


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
    parser.add_argument(
        "--http-only", action="store_true",
        help="Only aggregate Replay's ingress result; storage checks are not applicable",
    )
    parser.add_argument(
        "--jobs-triggered", action="store_true",
        help="Treat missing scheduled-job output as FAIL instead of NOT_RUN",
    )
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
        result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8",
                                errors="replace", env=environment, timeout=30)
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
        result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8",
                                errors="replace", env=environment, timeout=30)
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


def parse_json_value(value: Any) -> Any:
    if not isinstance(value, str):
        return value
    try:
        return json.loads(value)
    except json.JSONDecodeError:
        return value


def nullable_int(value: Any) -> int | None:
    if value is None or str(value).strip().upper() in {"", "NULL", "\\N"}:
        return None
    return int(value)


def validate_canonical_samples(args: argparse.Namespace, manifest: dict[str, Any],
                               checks: list[dict[str, Any]]) -> None:
    expected = manifest.get("expected", {}).get("mysql", {}).get("canonicalSamples", [])
    if not expected:
        return
    ids = [str(item.get("eventId")) for item in expected if item.get("eventId")]
    if not ids:
        return
    id_sql = ", ".join(sql_literal(event_id) for event_id in ids)
    query = (
        "SELECT event_id, user_id, event_type, COALESCE(target_id, ''), properties "
        f"FROM user_event WHERE event_id IN ({id_sql}) ORDER BY event_id"
    )
    rows, error = run_mysql(args, query)
    if error:
        add_check(checks, "mysql-canonical-samples", "MySQL", "Canonical event payload samples match the input", "NOT_RUN",
                  expected=expected, actual=None, query=query, reason=error)
        return
    actual = []
    for row in rows or []:
        actual.append({
            "eventId": row[0],
            "userId": int(row[1]),
            "eventType": row[2],
            "targetId": nullable_int(row[3]),
            "properties": parse_json_value(row[4]) or {},
        })
    expected_sorted = sorted(expected, key=lambda item: str(item.get("eventId")))
    add_check(
        checks,
        "mysql-canonical-samples",
        "MySQL",
        "Canonical event payload samples match the input",
        "PASS" if actual == expected_sorted else "FAIL",
        expected=expected_sorted,
        actual=actual,
        query=query,
    )

    conflict_expected = manifest.get("scenarioDetails", {}).get("conflictingPayloadExpected", {})
    if not conflict_expected:
        return
    conflict_ids = sorted(str(event_id) for event_id in conflict_expected)
    conflict_sql = ", ".join(sql_literal(event_id) for event_id in conflict_ids)
    conflict_query = (
        "SELECT event_id, user_id, event_type, COALESCE(target_id, ''), properties "
        f"FROM user_event WHERE event_id IN ({conflict_sql}) ORDER BY event_id"
    )
    conflict_rows, conflict_error = run_mysql(args, conflict_query)
    expected_conflicts = []
    for event_id in conflict_ids:
        expected_value = conflict_expected[event_id]
        expected_conflicts.append({
            "eventId": event_id,
            "userId": int(expected_value["userId"]),
            "eventType": expected_value["eventType"],
            "targetId": expected_value.get("targetId"),
            "properties": expected_value.get("properties") or {},
        })
    actual_conflicts = []
    for row in conflict_rows or []:
        actual_conflicts.append({
            "eventId": row[0],
            "userId": int(row[1]),
            "eventType": row[2],
            "targetId": nullable_int(row[3]),
            "properties": parse_json_value(row[4]) or {},
        })
    add_check(
        checks,
        "mysql-conflicting-canonical",
        "MySQL",
        "Conflicting duplicate payloads retain the first canonical event",
        "NOT_RUN" if conflict_error else ("PASS" if actual_conflicts == expected_conflicts else "FAIL"),
        expected=expected_conflicts,
        actual=None if conflict_error else actual_conflicts,
        query=conflict_query,
        reason=conflict_error,
    )


def validate_compensation(args: argparse.Namespace, manifest: dict[str, Any],
                          checks: list[dict[str, Any]]) -> None:
    prefix = event_prefix(manifest)
    if not prefix:
        return
    query = (
        "SELECT task_type, status, COUNT(*) FROM data_compensation_task "
        f"WHERE event_id LIKE {sql_literal(prefix + '%')} "
        "GROUP BY task_type, status ORDER BY task_type, status"
    )
    rows, error = run_mysql(args, query)
    expected = manifest.get("expected", {}).get("mysql", {}).get("compensationByStatus", {})
    if error:
        add_check(checks, "mysql-compensation", "Compensation", "Compensation task contents match the Manifest", "NOT_RUN",
                  expected=expected, actual=None, query=query, reason=error)
        return
    actual: dict[str, dict[str, int]] = defaultdict(dict)
    for row in rows or []:
        if len(row) >= 3:
            actual[row[0]][row[1]] = int(row[2])
    actual_dict = {key: dict(value) for key, value in actual.items()}
    add_check(
        checks,
        "mysql-compensation",
        "Compensation",
        "Compensation task contents match the Manifest",
        "PASS" if actual_dict == expected else "FAIL",
        expected=expected,
        actual=actual_dict,
        query=query,
    )


def validate_daily_metrics(args: argparse.Namespace, user_min: int, user_max: int,
                           checks: list[dict[str, Any]]) -> None:
    expected_query = (
        "SELECT DATE(metric_hour), event_type, COALESCE(SUM(event_count),0), "
        "COALESCE(SUM(duration_sum),0), CAST(COALESCE(SUM(amount_sum),0) AS DECIMAL(20,2)) "
        "FROM user_metric_hourly "
        f"WHERE user_id BETWEEN {user_min} AND {user_max} "
        "AND metric_hour >= DATE_SUB(CURDATE(), INTERVAL 1 DAY) "
        "AND metric_hour < DATE_FORMAT(NOW(), '%Y-%m-%d %H:00:00') "
        "GROUP BY DATE(metric_hour), event_type ORDER BY DATE(metric_hour), event_type"
    )
    actual_query = (
        "SELECT metric_date, event_type, COALESCE(SUM(event_count),0), "
        "COALESCE(SUM(duration_sum),0), CAST(COALESCE(SUM(amount_sum),0) AS DECIMAL(20,2)) "
        "FROM user_metric_daily "
        f"WHERE user_id BETWEEN {user_min} AND {user_max} "
        "AND metric_date >= DATE_SUB(CURDATE(), INTERVAL 1 DAY) "
        "AND metric_date <= CURDATE() "
        "GROUP BY metric_date, event_type ORDER BY metric_date, event_type"
    )
    expected_rows, expected_error = run_mysql(args, expected_query)
    actual_rows, actual_error = run_mysql(args, actual_query)
    if expected_error or actual_error:
        add_check(checks, "mysql-daily-metrics", "MySQL", "Daily metric contents match hourly source buckets", "NOT_RUN",
                  expected="dailyMetricJob output", actual=None, query=actual_query,
                  reason=expected_error or actual_error)
        return

    def rows_to_map(rows: list[list[str]] | None) -> dict[str, dict[str, Any]]:
        result: dict[str, dict[str, Any]] = {}
        for row in rows or []:
            if len(row) >= 5:
                result[f"{row[0]}|{row[1]}"] = {
                    "metricDate": row[0],
                    "eventType": row[1],
                    "eventCount": int(row[2]),
                    "durationSum": int(row[3]),
                    "amountSum": decimal_text(row[4]),
                }
        return result

    expected = rows_to_map(expected_rows)
    actual = rows_to_map(actual_rows)
    if not actual:
        status = "FAIL" if args.jobs_triggered and expected else ("PASS" if args.jobs_triggered else "NOT_RUN")
        reason = (
            "user_metric_daily has no rows after an explicit dailyMetricJob trigger"
            if args.jobs_triggered and expected
            else "dailyMetricJob is not auto-triggered by Functional Replay"
        )
    else:
        status = "PASS" if expected == actual else "FAIL"
        reason = None
    add_check(checks, "mysql-daily-metrics", "MySQL", "Daily metric contents match hourly source buckets", status,
              expected=expected, actual=actual, query=actual_query, reason=reason)


def validate_window_metrics(args: argparse.Namespace, user_min: int, user_max: int,
                            checks: list[dict[str, Any]]) -> None:
    daily_query = (
        "SELECT user_id, metric_date, event_type, COALESCE(SUM(event_count),0), "
        "CAST(COALESCE(SUM(amount_sum),0) AS DECIMAL(20,2)) "
        "FROM user_metric_daily "
        f"WHERE user_id BETWEEN {user_min} AND {user_max} "
        "AND metric_date >= DATE_SUB(CURDATE(), INTERVAL 30 DAY) "
        "GROUP BY user_id, metric_date, event_type ORDER BY user_id, metric_date, event_type"
    )
    hourly_query = (
        "SELECT user_id, COALESCE(SUM(event_count),0) "
        "FROM user_metric_hourly "
        f"WHERE user_id BETWEEN {user_min} AND {user_max} "
        "AND event_type = 'SEARCH' "
        "AND metric_hour = DATE_FORMAT(NOW(), '%Y-%m-%d %H:00:00') "
        "GROUP BY user_id ORDER BY user_id"
    )
    daily_rows, daily_error = run_mysql(args, daily_query)
    hourly_rows, hourly_error = run_mysql(args, hourly_query)
    if daily_error or hourly_error:
        add_check(checks, "profile-window-metrics", "Profile", "Window metric contents match daily/hourly buckets", "NOT_RUN",
                  expected="search_1h/active_7d/spend_30d/fav_7d", actual=None,
                  query=daily_query, reason=daily_error or hourly_error)
        return

    expected: dict[str, dict[str, Any]] = {}
    daily_by_user: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in daily_rows or []:
        if len(row) < 5:
            continue
        daily_by_user[row[0]].append({
            "metricDate": row[1],
            "eventType": row[2],
            "eventCount": int(row[3]),
            "amountSum": Decimal(str(row[4] or "0")),
        })
    search_by_user = {row[0]: int(row[1]) for row in hourly_rows or [] if len(row) >= 2}
    since_7d = (datetime.now().date() - timedelta(days=7)).isoformat()
    for user_id, metrics in daily_by_user.items():
        recent_metrics = [item for item in metrics if item["metricDate"] >= since_7d]
        content_7d = sum(item["eventCount"] for item in recent_metrics if item["eventType"] == "CONTENT_VIEW")
        paid_30d = sum((item["amountSum"] for item in metrics if item["eventType"] == "ORDER_PAID"), Decimal("0"))
        favorite_7d = sum(item["eventCount"] for item in recent_metrics if item["eventType"] == "FAVORITE")
        expected[f"{user_id}|search_1h"] = {"userId": int(user_id), "metricType": "search_1h",
                                              "metricValue": decimal_text(search_by_user.get(user_id, 0))}
        expected[f"{user_id}|active_7d"] = {"userId": int(user_id), "metricType": "active_7d",
                                              "metricValue": decimal_text(content_7d)}
        expected[f"{user_id}|spend_30d"] = {"userId": int(user_id), "metricType": "spend_30d",
                                              "metricValue": decimal_text(paid_30d)}
        expected[f"{user_id}|fav_7d"] = {"userId": int(user_id), "metricType": "fav_7d",
                                          "metricValue": decimal_text(favorite_7d)}

    actual_query = (
        "SELECT user_id, metric_type, metric_value FROM ("
        "SELECT user_id, metric_type, metric_value, "
        "ROW_NUMBER() OVER (PARTITION BY user_id, metric_type ORDER BY calculated_at DESC) AS rn "
        "FROM user_behavior_summary "
        f"WHERE user_id BETWEEN {user_min} AND {user_max}"
        ") latest WHERE rn = 1 ORDER BY user_id, metric_type"
    )
    actual_rows, actual_error = run_mysql(args, actual_query)
    if actual_error:
        add_check(checks, "profile-window-metrics", "Profile", "Window metric contents match daily/hourly buckets", "NOT_RUN",
                  expected=expected, actual=None, query=actual_query, reason=actual_error)
        return
    actual = {
        f"{row[0]}|{row[1]}": {"userId": int(row[0]), "metricType": row[1], "metricValue": decimal_text(row[2])}
        for row in actual_rows or [] if len(row) >= 3
    }
    if not actual:
        status = "FAIL" if args.jobs_triggered and expected else ("PASS" if args.jobs_triggered else "NOT_RUN")
        reason = (
            "user_behavior_summary has no rows after an explicit windowMetricJob trigger"
            if args.jobs_triggered and expected
            else "windowMetricJob is not auto-triggered by Functional Replay"
        )
    else:
        status = "PASS" if actual == expected else "FAIL"
        reason = None
    add_check(checks, "profile-window-metrics", "Profile", "Window metric contents match daily/hourly buckets", status,
              expected=expected, actual=actual, query=actual_query, reason=reason)


def expected_tag_values(metrics: list[dict[str, Any]]) -> dict[str, str]:
    total = sum(item["eventCount"] for item in metrics)
    search = sum(item["eventCount"] for item in metrics if item["eventType"] == "SEARCH")
    add_cart = sum(item["eventCount"] for item in metrics if item["eventType"] == "ADD_CART")
    paid = sum(item["eventCount"] for item in metrics if item["eventType"] == "ORDER_PAID")
    paid_amount = sum((item["amountSum"] for item in metrics if item["eventType"] == "ORDER_PAID"), Decimal("0"))
    login_days = sum(1 for item in metrics if item["eventType"] == "LOGIN" and item["eventCount"] > 0)
    has_activity = any(item["eventType"] in {"LOGIN", "CONTENT_VIEW"} for item in metrics)
    average_order = paid_amount / paid if paid else Decimal("0")
    return {
        "AI_PREF": "1" if total and search / total > 0.3 else "0",
        "HIGH_VALUE": "1" if paid_amount >= Decimal("500") else "0",
        "CHURN_RISK": "0" if has_activity else "1",
        "PRICE_SEN": "1" if paid and average_order < Decimal("50") else "0",
        "ACTIVE_USER": "1" if total >= 50 else "0",
        "NEW_USER": "1" if total < 10 else "0",
        "BARGAIN_HUNTER": "1" if (add_cart > 5 if paid == 0 else add_cart / paid > 3.0) else "0",
        "LOYAL_CUSTOMER": "1" if login_days >= 5 else "0",
    }


def validate_user_tags(args: argparse.Namespace, user_min: int, user_max: int,
                       checks: list[dict[str, Any]]) -> None:
    daily_query = (
        "SELECT user_id, event_type, event_count, amount_sum "
        "FROM user_metric_daily "
        f"WHERE user_id BETWEEN {user_min} AND {user_max} "
        "AND metric_date >= DATE_SUB(CURDATE(), INTERVAL 7 DAY) "
        "ORDER BY user_id, event_type"
    )
    daily_rows, daily_error = run_mysql(args, daily_query)
    if daily_error:
        add_check(checks, "profile-user-tags", "Profile", "Latest user tag values match daily metric contents", "NOT_RUN",
                  expected="eight current tag values", actual=None, query=daily_query, reason=daily_error)
        return
    metrics_by_user: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in daily_rows or []:
        if len(row) >= 4:
            metrics_by_user[row[0]].append({
                "eventType": row[1],
                "eventCount": int(row[2]),
                "amountSum": Decimal(str(row[3] or "0")),
            })
    expected: dict[str, dict[str, Any]] = {}
    for user_id, metrics in metrics_by_user.items():
        for tag_name, tag_value in expected_tag_values(metrics).items():
            expected[f"{user_id}|{tag_name}"] = {
                "userId": int(user_id), "tagName": tag_name, "tagValue": tag_value,
            }

    actual_query = (
        "SELECT user_id, tag_name, tag_value FROM ("
        "SELECT user_id, tag_name, tag_value, "
        "ROW_NUMBER() OVER (PARTITION BY user_id, tag_name ORDER BY calculated_at DESC) AS rn "
        "FROM user_tag "
        f"WHERE user_id BETWEEN {user_min} AND {user_max}"
        ") latest WHERE rn = 1 ORDER BY user_id, tag_name"
    )
    actual_rows, actual_error = run_mysql(args, actual_query)
    if actual_error:
        add_check(checks, "profile-user-tags", "Profile", "Latest user tag values match daily metric contents", "NOT_RUN",
                  expected=expected, actual=None, query=actual_query, reason=actual_error)
        return
    actual = {
        f"{row[0]}|{row[1]}": {"userId": int(row[0]), "tagName": row[1], "tagValue": row[2]}
        for row in actual_rows or [] if len(row) >= 3
    }
    if not actual:
        status = "FAIL" if args.jobs_triggered and expected else ("PASS" if args.jobs_triggered else "NOT_RUN")
        reason = (
            "user_tag has no rows after an explicit tagRecalcJob trigger"
            if args.jobs_triggered and expected
            else "tagRecalcJob is not auto-triggered by Functional Replay"
        )
    else:
        status = "PASS" if actual == expected else "FAIL"
        reason = None
    add_check(checks, "profile-user-tags", "Profile", "Latest user tag values match daily metric contents", status,
              expected=expected, actual=actual, query=actual_query, reason=reason)


def validate_scheduled_outputs(args: argparse.Namespace, manifest: dict[str, Any],
                               checks: list[dict[str, Any]]) -> None:
    scheduled = manifest.get("expected", {}).get("scheduledOutputs", {})
    if not scheduled:
        return
    user_range = manifest.get("userIdRange", {})
    user_min = user_range.get("min")
    user_max = user_range.get("max")
    if user_min is None or user_max is None:
        add_check(checks, "scheduled-outputs", "Profile", "Scheduled business outputs have a usable user range", "NOT_RUN",
                  expected="userIdRange", actual=None, reason="manifest has no user range")
        return
    if "dailyMetrics" in scheduled:
        validate_daily_metrics(args, int(user_min), int(user_max), checks)
    if "windowMetrics" in scheduled:
        validate_window_metrics(args, int(user_min), int(user_max), checks)
    if "userTags" in scheduled:
        validate_user_tags(args, int(user_min), int(user_max), checks)


def validate_campaign_derived_outputs(args: argparse.Namespace, campaign_details: dict[str, Any],
                                      campaign_ids: list[int], checks: list[dict[str, Any]]) -> None:
    stages = campaign_details.get("downstreamStages", {})
    if not campaign_ids or not stages:
        return
    campaign_sql = ", ".join(str(int(campaign_id)) for campaign_id in sorted(set(campaign_ids)))

    if "campaignExecution" in stages:
        query = (
            "SELECT campaign_id, status, COUNT(*) FROM campaign_execution "
            f"WHERE campaign_id IN ({campaign_sql}) GROUP BY campaign_id, status ORDER BY campaign_id, status"
        )
        rows, error = run_mysql(args, query)
        actual: dict[str, dict[str, int]] = defaultdict(dict)
        for row in rows or []:
            if len(row) >= 3:
                actual[str(row[0])][row[1]] = int(row[2])
        actual_dict = {key: dict(value) for key, value in actual.items()}
        if error:
            status = "NOT_RUN"
            reason = error
        elif not actual_dict:
            status = "NOT_RUN"
            reason = "campaign_execution has no rows; no scheduled Campaign fixture was triggered"
        else:
            valid_states = {state for values in actual_dict.values() for state in values}
            status = "PASS" if valid_states <= {"PENDING", "RUNNING", "DONE", "FAILED"} else "FAIL"
            reason = None
        add_check(checks, "campaign-execution", "Campaign", "Scheduled campaign execution contents are observable", status,
                  expected="PENDING/RUNNING/DONE/FAILED rows", actual=actual_dict, query=query, reason=reason)

    if "performanceSummary" in stages:
        expected_query = (
            "SELECT c.id, "
            "(SELECT COUNT(DISTINCT user_id) FROM delivery_task t WHERE t.campaign_id = c.id), "
            "(SELECT COUNT(*) FROM delivery_record d WHERE d.campaign_id = c.id), "
            "(SELECT COUNT(*) FROM delivery_record d WHERE d.campaign_id = c.id "
            "AND UPPER(d.status) IN ('SENT','DELIVERED')), "
            "(SELECT COUNT(DISTINCT ce.user_id) FROM click_event ce "
            "JOIN delivery_task t ON t.id = ce.task_id WHERE t.campaign_id = c.id), "
            "(SELECT COUNT(DISTINCT ar.user_id) FROM attribution_record ar WHERE ar.campaign_id = c.id) "
            f"FROM campaign c WHERE c.id IN ({campaign_sql}) ORDER BY c.id"
        )
        summary_query = (
            "SELECT campaign_id, target_audience_count, sent_count, delivered_count, clicked_count, converted_count "
            f"FROM campaign_performance_summary WHERE campaign_id IN ({campaign_sql}) ORDER BY campaign_id"
        )
        expected_rows, expected_error = run_mysql(args, expected_query)
        actual_rows, actual_error = run_mysql(args, summary_query)
        if expected_error or actual_error:
            add_check(checks, "campaign-performance-summary", "Campaign", "Performance summary contents match delivery facts",
                      "NOT_RUN", expected="summary for fixture campaigns", actual=None,
                      query=summary_query, reason=expected_error or actual_error)
        else:
            expected = {
                str(row[0]): {
                    "campaignId": int(row[0]), "targetAudienceCount": int(row[1]), "sentCount": int(row[2]),
                    "deliveredCount": int(row[3]), "clickedCount": int(row[4]), "convertedCount": int(row[5]),
                }
                for row in expected_rows or [] if len(row) >= 6
            }
            actual = {
                str(row[0]): {
                    "campaignId": int(row[0]), "targetAudienceCount": int(row[1]), "sentCount": int(row[2]),
                    "deliveredCount": int(row[3]), "clickedCount": int(row[4]), "convertedCount": int(row[5]),
                }
                for row in actual_rows or [] if len(row) >= 6
            }
            if not actual:
                status = "NOT_RUN"
                reason = "campaign_performance_summary has no rows; campaignReviewJob was not observed"
            else:
                status = "PASS" if actual == expected else "FAIL"
                reason = None
            add_check(checks, "campaign-performance-summary", "Campaign", "Performance summary contents match delivery facts",
                      status, expected=expected, actual=actual, query=summary_query, reason=reason)

    if "aiReview" in stages:
        query = (
            "SELECT campaign_id, status, review_json FROM campaign_ai_review "
            f"WHERE campaign_id IN ({campaign_sql}) ORDER BY campaign_id"
        )
        rows, error = run_mysql(args, query)
        if error:
            add_check(checks, "campaign-ai-review", "AI", "AI review rows are terminal and contain output", "NOT_RUN",
                      expected="terminal review rows", actual=None, query=query, reason=error)
        else:
            actual = {
                str(row[0]): {"campaignId": int(row[0]), "status": row[1], "hasReviewJson": bool(row[2])}
                for row in rows or [] if len(row) >= 3
            }
            valid = all(item["status"] in {"SUCCESS", "RETRYABLE_FAILED", "SKIPPED_INSUFFICIENT_DATA", "PERMANENT_FAILED"}
                        and (item["status"] != "SUCCESS" or item["hasReviewJson"])
                        for item in actual.values())
            if not actual:
                status = "NOT_RUN"
                reason = "campaign_ai_review has no rows; campaignReviewJob/AI was not observed"
            else:
                status = "PASS" if valid and len(actual) == len(set(campaign_ids)) else "FAIL"
                reason = None
            add_check(checks, "campaign-ai-review", "AI", "AI review rows are terminal and contain output", status,
                      expected="one terminal row per fixture campaign", actual=actual, query=query, reason=reason)


def validate_campaign_frequency_redis(args: argparse.Namespace, campaign_details: dict[str, Any],
                                      campaign_id: int, checks: list[dict[str, Any]]) -> None:
    user_id = campaign_details.get("campaignUserId")
    if user_id is None:
        add_check(checks, "campaign-frequency-redis", "Redis", "Frequency counters match sent campaign tasks", "NOT_RUN",
                  expected="campaignUserId", actual=None, reason="Manifest has no campaignUserId")
        return
    date_text = datetime.now().strftime("%Y%m%d")
    frequency = campaign_details.get("frequency", {})
    expected_count = int(frequency.get("expectedAllowedByFrequency", 0))
    expected_user_count = int(frequency.get("expectedUserDailyFrequencyCount", expected_count))
    keys = {
        f"freq:user:{int(user_id)}:{date_text}": expected_user_count,
        f"freq:campaign:{int(campaign_id)}:{int(user_id)}": expected_count,
    }
    actual: dict[str, Any] = {}
    errors: list[str] = []
    command_failed = False
    for key, expected in keys.items():
        output, error = run_redis(args, ["GET", key])
        if error:
            command_failed = True
            errors.append(error)
            continue
        actual[key] = int(output[0]) if output and output[0] else None
        if actual[key] != expected:
            errors.append(f"{key}: expected {expected}, actual {actual[key]}")
    if errors:
        status = "NOT_RUN" if command_failed else "FAIL"
        add_check(checks, "campaign-frequency-redis", "Redis", "Frequency counters match sent campaign tasks", status,
                  expected=keys, actual=actual, reason="; ".join(errors))
        return

    task_query = (
        "SELECT id FROM delivery_task "
        f"WHERE campaign_id = {int(campaign_id)} AND status = 'SENT' ORDER BY id"
    )
    task_rows, task_error = run_mysql(args, task_query)
    sent_task_ids = [row[0] for row in task_rows or [] if row]
    reserved: dict[str, int | None] = {}
    reserved_errors: list[str] = []
    for task_id in sent_task_ids:
        output, error = run_redis(args, ["EXISTS", f"freq:reserved:{task_id}"])
        if error:
            reserved_errors.append(error)
        else:
            reserved[str(task_id)] = int(output[0]) if output else 0
    add_check(checks, "campaign-frequency-reservations", "Redis", "Sent campaign tasks hold idempotent quota reservations",
              "NOT_RUN" if task_error or reserved_errors else ("PASS" if all(value == 1 for value in reserved.values()) else "FAIL"),
              expected={str(task_id): 1 for task_id in sent_task_ids}, actual=reserved,
              query=task_query, reason=task_error or (reserved_errors[0] if reserved_errors else None))


def redis_hash(args: argparse.Namespace, key: str) -> tuple[dict[str, str] | None, str | None]:
    output, error = run_redis(args, ["HGETALL", key])
    if error:
        return None, error
    values = output or []
    return {values[index]: values[index + 1] for index in range(0, len(values) - 1, 2)}, None


def redis_zset_contains(args: argparse.Namespace, key: str, member: str) -> tuple[bool | None, str | None]:
    output, error = run_redis(args, ["ZRANGE", key, "0", "-1"])
    if error:
        return None, error
    return any(member in str(item) for item in (output or [])), None


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

    validate_canonical_samples(args, manifest, checks)
    validate_compensation(args, manifest, checks)
    validate_scheduled_outputs(args, manifest, checks)

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
        campaign_ids: list[int] = []
        campaign_query = (
            "SELECT id, name, channel FROM campaign WHERE name = " + sql_literal(campaign_name)
            + " ORDER BY id DESC LIMIT 1"
        )
        campaign_rows, error = run_mysql(args, campaign_query)
        if error:
            add_check(checks, "campaign-fixture", "Campaign", "Campaign SQL fixture is present", "NOT_RUN",
                      expected=campaign_name, actual=None, query=campaign_query, reason=error)
        elif not campaign_rows:
            add_check(checks, "campaign-fixture", "Campaign", "Campaign SQL fixture is present", "NOT_RUN",
                      expected=campaign_name, actual=None, query=campaign_query,
                       reason="run the Campaign fixture step in testing/functional/run.ps1 first")
        else:
            campaign_id = campaign_rows[0][0]
            campaign_ids.append(int(campaign_id))
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
                      "PASS" if actual_task_count == expected_tasks and actual_tasks == {
                          key: value for key, value in {
                              "SENT": int(detail.get("expectedAllowedByFrequency", 0)),
                              "CANCELLED": int(detail.get("expectedCancelledByFrequency", 0)),
                          }.items() if value > 0
                      } else ("NOT_RUN" if task_error else "FAIL"),
                      expected={
                          "SENT": int(detail.get("expectedAllowedByFrequency", 0)),
                          "CANCELLED": int(detail.get("expectedCancelledByFrequency", 0)),
                      }, actual=actual_tasks, query=task_query, reason=task_error)

            allowed_tasks = int(detail.get("expectedAllowedByFrequency", 0))
            delivery_query = (
                "SELECT status, COUNT(*) FROM delivery_record "
                f"WHERE campaign_id = {int(campaign_id)} GROUP BY status ORDER BY status"
            )
            delivery_rows, delivery_error = run_mysql(args, delivery_query)
            actual_delivery = {row[0]: int(row[1]) for row in delivery_rows or []} if not delivery_error else None
            add_check(checks, "campaign-delivery-records", "Campaign", "Delivery records match frequency-allowed tasks",
                      "PASS" if actual_delivery == ({"SENT": allowed_tasks} if allowed_tasks else {})
                      else ("NOT_RUN" if delivery_error else "FAIL"),
                      expected={"SENT": allowed_tasks} if allowed_tasks else {}, actual=actual_delivery,
                      query=delivery_query, reason=delivery_error)

            channel = campaign_rows[0][2] if len(campaign_rows[0]) > 2 else None
            if channel == "IN_APP":
                channel_query = (
                    "SELECT COUNT(*) FROM in_app_message m "
                    "JOIN delivery_task t ON t.id = m.business_key "
                    f"WHERE t.campaign_id = {int(campaign_id)}"
                )
                channel_expected = allowed_tasks
                channel_name = "in_app_message"
            elif channel == "PUSH":
                channel_query = (
                    "SELECT COUNT(*) FROM push_record p "
                    "JOIN delivery_task t ON t.id = p.business_key "
                    f"WHERE t.campaign_id = {int(campaign_id)}"
                )
                channel_expected = allowed_tasks
                channel_name = "push_record"
            else:
                channel_query = (
                    "SELECT COUNT(*) FROM delivery_record "
                    f"WHERE campaign_id = {int(campaign_id)} AND channel = 'EMAIL' AND status = 'SENT'"
                )
                channel_expected = allowed_tasks
                channel_name = "email delivery_record"
            channel_rows, channel_error = run_mysql(args, channel_query)
            channel_actual = int(channel_rows[0][0]) if channel_rows and channel_rows[0] else None
            add_check(checks, "campaign-channel-records", "Campaign", f"{channel_name} contents match sent tasks",
                      "PASS" if channel_actual == channel_expected else ("NOT_RUN" if channel_error else "FAIL"),
                      expected=channel_expected, actual=channel_actual, query=channel_query, reason=channel_error)
            validate_campaign_frequency_redis(args, campaign_details, int(campaign_id), checks)

        attribution = campaign_details.get("attribution", {})
        target_event_id = attribution.get("targetEventId")
        if target_event_id:
            click_query = (
                "SELECT task_id, COUNT(*) FROM click_event "
                f"WHERE task_id = {int(attribution.get('expectedTaskId') or 0)} GROUP BY task_id"
            )
            click_rows, click_error = run_mysql(args, click_query)
            actual_clicks = int(click_rows[0][1]) if click_rows and len(click_rows[0]) > 1 else 0
            add_check(checks, "attribution-click-event", "Attribution", "Fixture click event is present for attribution",
                      "PASS" if actual_clicks >= 1 else ("NOT_RUN" if click_error else "FAIL"),
                      expected={"taskId": attribution.get("expectedTaskId"), "minCount": 1},
                      actual={"count": actual_clicks}, query=click_query, reason=click_error)

            expected_attr = {
                "targetEventId": target_event_id,
                "campaignId": attribution.get("expectedCampaignId"),
                "taskId": attribution.get("expectedTaskId"),
                "attributionModel": attribution.get("expectedModel", "CLICK_LAST_TOUCH"),
            }
            task_status_query = (
                "SELECT status, matched_task_id, grace_until FROM attribution_task "
                "WHERE target_event_id = " + sql_literal(target_event_id)
            )
            attribution_query = (
                "SELECT target_event_id, campaign_id, task_id, attribution_model "
                "FROM attribution_record WHERE target_event_id = " + sql_literal(target_event_id)
            )
            deadline = time.monotonic() + max(0, args.wait_seconds)
            attempts: list[dict[str, Any]] = []
            actual_attr = None
            actual_task_status = None
            task_status_error = None
            attribution_error = None
            post_grace_deadline: float | None = None
            while True:
                attribution_rows, attribution_error = run_mysql(args, attribution_query)
                task_status_rows, task_status_error = run_mysql(args, task_status_query)
                if attribution_error or task_status_error:
                    break
                if attribution_rows:
                    row = attribution_rows[0]
                    actual_attr = {
                        "targetEventId": row[0], "campaignId": nullable_int(row[1]),
                        "taskId": nullable_int(row[2]), "attributionModel": row[3],
                    }
                grace_until = None
                if task_status_rows:
                    row = task_status_rows[0]
                    actual_task_status = {
                        "status": row[0], "matchedTaskId": nullable_int(row[1]),
                    }
                    if len(row) > 2 and row[2] and str(row[2]).upper() not in {"NULL", "\\N"}:
                        try:
                            grace_until = datetime.fromisoformat(str(row[2]).replace(" ", "T"))
                        except ValueError:
                            grace_until = None
                attempts.append({
                    "at": datetime.now(timezone.utc).isoformat(),
                    "record": actual_attr is not None,
                    "task": actual_task_status,
                })
                if actual_attr == expected_attr or (actual_task_status and actual_task_status["status"] != "PENDING"):
                    break
                if grace_until and datetime.now() >= grace_until and post_grace_deadline is None:
                    # Give the fixed-delay consumer one polling interval plus a
                    # small cushion after the grace boundary before classifying
                    # an unprocessed task.
                    post_grace_deadline = time.monotonic() + 45.0
                if post_grace_deadline is not None and time.monotonic() >= post_grace_deadline:
                    break
                if time.monotonic() >= deadline:
                    break
                sleep_seconds = max(0.1, args.poll_seconds)
                if grace_until and datetime.now() < grace_until:
                    remaining_until_grace = (grace_until - datetime.now()).total_seconds()
                    if remaining_until_grace > 0:
                        sleep_seconds = min(max(0.1, remaining_until_grace), max(2.0, args.poll_seconds))
                time.sleep(sleep_seconds)

            attribution_status = "PASS" if actual_attr == expected_attr else None
            attribution_reason = None
            if attribution_status != "PASS":
                if attribution_error or task_status_error:
                    attribution_status = "NOT_RUN"
                    attribution_reason = attribution_error or task_status_error
                elif not actual_task_status:
                    attribution_status = "FAIL"
                    attribution_reason = "Attribution task was not created for the target event"
                elif actual_task_status["status"] == "PENDING":
                    grace_until_text = None
                    if task_status_rows and len(task_status_rows[0]) > 2:
                        grace_until_text = task_status_rows[0][2]
                    grace_until = None
                    if grace_until_text and str(grace_until_text).upper() not in {"NULL", "\\N"}:
                        try:
                            grace_until = datetime.fromisoformat(str(grace_until_text).replace(" ", "T"))
                        except ValueError:
                            pass
                    if grace_until and datetime.now() < grace_until:
                        attribution_status = "NOT_RUN"
                        attribution_reason = "Attribution grace window has not elapsed before timeout"
                    else:
                        pending_member, pending_error = redis_zset_contains(
                            args, "delay:attribution", str(target_event_id)
                        )
                        processing_member, processing_error = redis_zset_contains(
                            args, "delay:attribution:processing", str(target_event_id)
                        )
                        if pending_error or processing_error:
                            attribution_status = "NOT_RUN"
                            attribution_reason = pending_error or processing_error
                        elif pending_member:
                            attribution_status = "NOT_RUN"
                            attribution_reason = "AttributionTaskConsumer has not claimed the expired task"
                        elif processing_member:
                            attribution_status = "NOT_RUN"
                            attribution_reason = "Attribution task is still being processed"
                        else:
                            attribution_status = "FAIL"
                            attribution_reason = (
                                "Grace window elapsed; attribution task remains PENDING and is absent from "
                                "both pending and processing queues"
                            )
                elif actual_task_status["status"] == "EXPIRED":
                    attribution_status = "FAIL"
                    attribution_reason = "Attribution task expired without the expected click match"
                else:
                    attribution_status = "FAIL"
                    attribution_reason = "Attribution task state and record disagree"

            add_check(checks, "attribution-last-touch", "Attribution", "Last-touch attribution matches fixture",
                      attribution_status, expected=expected_attr, actual=actual_attr,
                      query=attribution_query, reason=attribution_reason)
            task_expected = {"status": "MATCHED", "matchedTaskId": attribution.get("expectedTaskId")}
            add_check(checks, "attribution-task-state", "Attribution", "Attribution task reaches the expected terminal state",
                      attribution_status, expected=task_expected,
                      actual={"state": actual_task_status, "pollAttempts": attempts},
                      query=task_status_query, reason=attribution_reason)
            if attribution.get("expectedCampaignId"):
                campaign_ids.append(int(attribution["expectedCampaignId"]))
        validate_campaign_derived_outputs(args, campaign_details, campaign_ids, checks)
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

    prefix = event_prefix(manifest)
    user_sql = ", ".join(str(int(user_id)) for user_id in sample_users)
    event_query = (
        "SELECT user_id, event_type, effective_event_time, properties FROM user_event "
        f"WHERE event_id LIKE {sql_literal(prefix + '%')} AND user_id IN ({user_sql}) "
        "ORDER BY id"
    ) if prefix and user_sql else None
    event_rows, event_error = run_mysql(args, event_query) if event_query else (None, "manifest has no sample users")
    if event_error:
        add_check(checks, "redis-business-values", "Redis", "Realtime, daily and cart values match canonical events", "NOT_RUN",
                  expected="Redis business values", actual=None, query=event_query, reason=event_error)
        return "NOT_RUN"

    expected_daily: dict[str, dict[str, str]] = defaultdict(dict)
    expected_cart: dict[str, dict[str, Any]] = defaultdict(dict)
    expects_login: set[str] = set()
    for row in event_rows or []:
        if len(row) < 4:
            continue
        user_id, event_type, effective_time = str(row[0]), row[1], row[2]
        props = parse_json_value(row[3]) or {}
        date_text = str(effective_time).replace("T", " ")[:10].replace("-", "")
        daily_key = f"{user_id}:{date_text}"
        if event_type == "CONTENT_VIEW":
            expected_daily[daily_key]["views"] = str(int(expected_daily[daily_key].get("views", "0")) + 1)
        elif event_type == "SEARCH":
            expected_daily[daily_key]["search_count"] = str(
                int(expected_daily[daily_key].get("search_count", "0")) + 1
            )
        if event_type == "LOGIN":
            expects_login.add(user_id)
        cart_item_id = str(props.get("cartItemId", "")) if isinstance(props, dict) else ""
        if cart_item_id:
            if event_type == "ADD_CART":
                expected_cart[user_id][cart_item_id] = props
            elif event_type in {"REMOVE_CART", "ORDER_PAID"}:
                expected_cart[user_id].pop(cart_item_id, None)

    actual_rt: dict[str, dict[str, str]] = {}
    rt_errors: list[str] = []
    for user_id in sample_users:
        key = str(int(user_id))
        value, error = redis_hash(args, f"user:rt:{key}")
        if error:
            rt_errors.append(error)
        else:
            actual_rt[key] = value or {}
    if rt_errors:
        add_check(checks, "redis-realtime-values", "Redis", "Realtime profile values contain required fields", "NOT_RUN",
                  expected="last_active_at and last_login_at when applicable", actual=None, reason=rt_errors[0])
    else:
        missing_fields = {}
        for user_id, value in actual_rt.items():
            required = {"last_active_at"}
            if user_id in expects_login:
                required.add("last_login_at")
            missing = sorted(field for field in required if field not in value)
            if missing:
                missing_fields[user_id] = missing
        add_check(checks, "redis-realtime-values", "Redis", "Realtime profile values contain required fields",
                  "PASS" if not missing_fields else "FAIL",
                  expected="last_active_at and last_login_at when applicable",
                  actual={user_id: sorted(value) for user_id, value in actual_rt.items()}
                  if not missing_fields else missing_fields)

    actual_daily: dict[str, dict[str, str]] = {}
    daily_errors: list[str] = []
    for composite_key, expected in expected_daily.items():
        user_id, date_text = composite_key.split(":", 1)
        value, error = redis_hash(args, f"user:daily:{user_id}:{date_text}")
        if error:
            daily_errors.append(error)
        else:
            actual_daily[composite_key] = value or {}
    if daily_errors:
        add_check(checks, "redis-daily-values", "Redis", "Daily realtime counters match canonical events", "NOT_RUN",
                  expected=dict(expected_daily), actual=None, reason=daily_errors[0])
    else:
        add_check(checks, "redis-daily-values", "Redis", "Daily realtime counters match canonical events",
                  "PASS" if actual_daily == dict(expected_daily) else "FAIL",
                  expected=dict(expected_daily), actual=actual_daily)

    actual_cart: dict[str, dict[str, Any]] = {}
    cart_errors: list[str] = []
    for user_id, expected in expected_cart.items():
        value, error = redis_hash(args, f"user:cart:{user_id}")
        if error:
            cart_errors.append(error)
        else:
            actual_cart[user_id] = {key: parse_json_value(item) for key, item in (value or {}).items()}
    if cart_errors:
        add_check(checks, "redis-cart-values", "Redis", "Cart contents match canonical events", "NOT_RUN",
                  expected=dict(expected_cart), actual=None, reason=cart_errors[0])
    else:
        expected_cart_dict = {key: dict(value) for key, value in expected_cart.items()}
        add_check(checks, "redis-cart-values", "Redis", "Cart contents match canonical events",
                  "PASS" if actual_cart == expected_cart_dict else "FAIL",
                  expected=expected_cart_dict, actual=actual_cart)
    return "FAIL" if any(check["status"] == "FAIL" for check in checks) else "PASS"


def read_replay_failures(run_dir: Path | None) -> list[dict[str, Any]]:
    if not run_dir:
        return []
    path = run_dir / "replay-failures.json"
    if not path.exists():
        path = run_dir / "failures.json"
    if not path.exists():
        return []
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
        return value if isinstance(value, list) else []
    except json.JSONDecodeError:
        return [{"module": "replay-report", "exceptionOrLog": "invalid failures.json"}]


def read_replay_status(run_dir: Path | None) -> str | None:
    if not run_dir:
        return None
    path = run_dir / "replay-summary.json"
    if not path.exists():
        return None
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
        status = value.get("status")
        return status if status in {"PASS", "FAIL", "NOT_RUN"} else None
    except json.JSONDecodeError:
        return "NOT_RUN"


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
    checked_at = datetime.now(timezone.utc).isoformat()
    summary = {
        "status": status,
        "type": "functional",
        "datasetId": manifest.get("datasetId"),
        "seed": manifest.get("seed"),
        "scale": manifest.get("scale"),
        "checkedAt": checked_at,
        "checkCounts": {key: sum(1 for check in checks if check["status"] == key)
                        for key in ("PASS", "FAIL", "NOT_RUN")},
        "replayFailureCount": len(replay_failures),
        "failureCount": len(failures),
        "checks": checks,
    }
    (report_dir / "summary.json").write_text(pretty_json(summary) + "\n", encoding="utf-8")
    (report_dir / "failures.json").write_text(pretty_json(failures) + "\n", encoding="utf-8")

    check_counts = summary["checkCounts"]
    dataset_id = manifest.get("datasetId") or "未知数据集"
    scale = manifest.get("scale") or "—"
    seed = manifest.get("seed")
    lines = [
        "# PulseFlow 功能验收报告",
        "",
        "## 验收结论",
        "",
        f"总体结果：{status_label(status)}",
        "",
        f"数据集：`{dataset_id}`",
        f"数据规模：`{scale}`",
        f"随机种子：`{seed if seed is not None else '—'}`",
        f"校验时间：`{checked_at}`",
        "",
        "### 结果统计",
        "",
        f"- ✅ 通过：{check_counts['PASS']}",
        f"- ❌ 失败：{check_counts['FAIL']}",
        f"- ⚠️ 未执行：{check_counts['NOT_RUN']}",
        f"- 校验总数：{len(checks)}",
        "",
        "## ❌ 失败项",
        "",
    ]
    if failures:
        lines.extend([
            "| 校验内容 | 模块 | 预期 | 实际 | 原因 | 技术标识 |",
            "|---|---|---|---|---|---|",
        ])
        for failure in failures:
            check_id = str(failure.get("checkId") or "replay-request")
            check = {"checkId": check_id, "description": failure.get("exceptionOrLog")}
            lines.append(
                f"| {check_label(check)} | {module_label(failure.get('module'))} "
                f"| {markdown_value(failure.get('expected'))} "
                f"| {markdown_value(failure.get('actual'))} "
                f"| {markdown_reason(failure.get('exceptionOrLog') or '断言不匹配')} "
                f"| `{check_id}` |"
            )
    else:
        lines.append("无。")

    not_run_checks = [check for check in checks if check["status"] == "NOT_RUN"]
    lines.extend(["", "## ⚠️ 未执行项", ""])
    if not_run_checks:
        lines.extend([
            "| 校验内容 | 模块 | 原因 | 技术标识 |",
            "|---|---|---|---|",
        ])
        for check in not_run_checks:
            lines.append(
                f"| {check_label(check)} | {module_label(check.get('module'))} "
                f"| {markdown_reason(check.get('reason') or '未满足执行条件')} "
                f"| `{check.get('checkId')}` |"
            )
    else:
        lines.append("无。")

    module_counts: dict[str, dict[str, int]] = {}
    module_order: list[str] = []
    for check in checks:
        module = str(check.get("module") or "未知模块")
        if module not in module_counts:
            module_counts[module] = {"PASS": 0, "FAIL": 0, "NOT_RUN": 0}
            module_order.append(module)
        module_counts[module][check["status"]] = module_counts[module].get(check["status"], 0) + 1
    lines.extend([
        "",
        "## 核心校验结果",
        "",
        "| 模块 | 通过 | 失败 | 未执行 |",
        "|---|---:|---:|---:|",
    ])
    for module in module_order:
        counts = module_counts[module]
        lines.append(
            f"| {module_label(module)} | {counts['PASS']} | {counts['FAIL']} | {counts['NOT_RUN']} |"
        )

    ordered_checks = sorted(
        enumerate(checks),
        key=lambda item: (STATUS_SORT_ORDER.get(item[1].get("status"), 99), item[0]),
    )
    lines = [
        *lines,
        "",
        "## 完整校验结果",
        "",
        "| 校验内容 | 模块 | 结果 | 技术标识 |",
        "|---|---|---|---|",
    ]
    for _, check in ordered_checks:
        lines.append(
            f"| {check_label(check)} | {module_label(check.get('module'))} "
            f"| {status_label(check.get('status'))} | `{check.get('checkId')}` |"
        )
    lines.extend([
        "",
        "## 原始数据",
        "",
        "主报告只展示摘要；Expected、Actual、SQL 和完整调试信息见 `summary.json`。",
        "Replay 请求错误见 `replay-failures.json`，校验失败汇总见 `failures.json`。",
    ])
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

    if args.http_only:
        replay_failures = read_replay_failures(run_dir)
        replay_status = read_replay_status(run_dir)
        return write_summary(report_dir, manifest, checks, replay_failures,
                             replay_status == "NOT_RUN")

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

    for module, filename in (("Profile", "profile-validation.json"),
                             ("Campaign", "campaign-validation.json"),
                             ("Attribution", "attribution-validation.json"),
                             ("Compensation", "compensation-validation.json"),
                             ("AI", "ai-validation.json")):
        module_checks = [check for check in checks if check["module"] == module]
        if module_checks:
            module_status = "FAIL" if any(check["status"] == "FAIL" for check in module_checks) else (
                "NOT_RUN" if any(check["status"] == "NOT_RUN" for check in module_checks) else "PASS"
            )
            (report_dir / filename).write_text(
                pretty_json({"datasetId": manifest.get("datasetId"), "status": module_status,
                             "checks": module_checks}) + "\n",
                encoding="utf-8",
            )

    replay_failures = read_replay_failures(run_dir)
    replay_status = read_replay_status(run_dir)
    dependency_not_run = dependency_not_run or replay_status == "NOT_RUN"
    return write_summary(report_dir, manifest, checks, replay_failures, dependency_not_run)


if __name__ == "__main__":
    raise SystemExit(main())
