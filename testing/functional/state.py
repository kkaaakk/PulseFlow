#!/usr/bin/env python3
"""Reset and verify the state owned by PulseFlow Functional tests.

The application deliberately uses production-shaped Redis keys, so this tool
uses an explicit test ownership catalog instead of a destructive FLUSHDB.  The
catalog is derived from ``generate.py`` namespaces and ``campaign-fixture.sql``
IDs.  MySQL remains guarded by the same test-environment and loopback checks as
the validator.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable


FUNCTIONAL_DIR = Path(__file__).resolve().parent
OWNERSHIP_PATH = FUNCTIONAL_DIR / "ownership.json"
DEFAULT_DATABASE = "pulseflow_test"
DEFAULT_MYSQL_HOST = "127.0.0.1"
DEFAULT_MYSQL_PORT = 13306
DEFAULT_MYSQL_USER = "test"
DEFAULT_MYSQL_PASSWORD = "test"
DEFAULT_REDIS_HOST = "127.0.0.1"
DEFAULT_REDIS_PORT = 16379
DEFAULT_REDIS_DATABASE = 0
DEFAULT_BATCH_SIZE = 100
DEFAULT_RESET_ATTEMPTS = 5
DEFAULT_STABILIZE_SECONDS = 1.0


class StateCommandError(RuntimeError):
    """Raised when a guarded MySQL/Redis command cannot complete."""


@dataclass
class OwnershipScope:
    event_patterns: tuple[str, ...]
    event_prefixes: tuple[str, ...]
    campaign_pattern: str | None
    user_ranges: tuple[tuple[int, int], ...]
    user_ids: set[int] = field(default_factory=set)
    campaign_ids: set[int] = field(default_factory=set)
    delivery_task_ids: set[int] = field(default_factory=set)
    fixed_delivery_task_ids: set[int] = field(default_factory=set)

    def owns_user_id(self, user_id: int) -> bool:
        return user_id in self.user_ids or any(
            lower <= user_id <= upper for lower, upper in self.user_ranges
        )

    def owns_event_id(self, event_id: str) -> bool:
        return any(event_id.startswith(prefix) for prefix in self.event_prefixes)


def load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"Expected JSON object: {path}")
    return value


def load_scope(manifest_paths: Iterable[Path] = (), include_catalog: bool = True) -> OwnershipScope:
    catalog = load_json(OWNERSHIP_PATH)
    ranges: set[tuple[int, int]] = set()
    if include_catalog:
        for item in catalog.get("userIdRanges", []):
            ranges.add((int(item["min"]), int(item["max"])))

    scope = OwnershipScope(
        event_patterns=(str(catalog["eventIdLikePattern"]),) if include_catalog else (),
        event_prefixes=tuple(str(item) for item in catalog["eventIdPrefixes"]) if include_catalog else (),
        campaign_pattern=str(catalog["campaignNameLikePattern"]) if include_catalog else None,
        user_ranges=tuple(sorted(ranges)),
        user_ids={int(item) for item in catalog.get("fixedUserIds", [])} if include_catalog else set(),
        campaign_ids={int(item) for item in catalog.get("fixedCampaignIds", [])} if include_catalog else set(),
        delivery_task_ids={int(item) for item in catalog.get("fixedDeliveryTaskIds", [])} if include_catalog else set(),
        fixed_delivery_task_ids={int(item) for item in catalog.get("fixedDeliveryTaskIds", [])}
        if include_catalog else set(),
    )

    for manifest_path in manifest_paths:
        manifest = load_json(manifest_path)
        ownership = manifest.get("ownership") or {}
        prefix = ownership.get("eventIdPrefix")
        if not prefix:
            samples = manifest.get("sampleEventIds") or []
            if samples:
                prefix = str(samples[0]).rsplit("-", 1)[0] + "-"
        if prefix:
            scope.event_patterns = tuple(dict.fromkeys((*scope.event_patterns, f"{prefix}%")))
            scope.event_prefixes = tuple(dict.fromkeys((*scope.event_prefixes, str(prefix))))
        user_range = ownership.get("userIdRange") or manifest.get("userIdRange") or {}
        if not include_catalog:
            for item in catalog.get("userIdRanges", []):
                if item.get("name") == manifest.get("scenario"):
                    scope.user_ranges = tuple(dict.fromkeys((*scope.user_ranges, (
                        int(item["min"]), int(item["max"])
                    ))))
        if user_range.get("min") is not None and user_range.get("max") is not None:
            scope.user_ranges = tuple(dict.fromkeys((*scope.user_ranges, (
                int(user_range["min"]), int(user_range["max"])
            ))))
        for user_id in manifest.get("sampleUserIds") or []:
            scope.user_ids.add(int(user_id))
        details = manifest.get("scenarioDetails") or {}
        if manifest.get("scenario") == "campaign" or details.get("campaignFixtureName"):
            scope.campaign_pattern = str(catalog["campaignNameLikePattern"])
            if not include_catalog:
                scope.campaign_ids.update(int(item) for item in catalog.get("fixedCampaignIds", []))
                scope.delivery_task_ids.update(int(item) for item in catalog.get("fixedDeliveryTaskIds", []))
                scope.fixed_delivery_task_ids.update(int(item) for item in catalog.get("fixedDeliveryTaskIds", []))
        for campaign_key in ("frequencyCampaignId", "attributionCampaignId"):
            campaign_id = details.get(campaign_key)
            if campaign_id is not None:
                scope.campaign_ids.add(int(campaign_id))
        attribution = details.get("attribution") or {}
        target_id = attribution.get("targetEventId")
        if target_id:
            # Target IDs are event IDs, and this keeps the manifest useful for
            # diagnostics without changing the namespace-wide reset query.
            prefix = str(target_id).rsplit("-", 1)[0] + "-"
            scope.event_prefixes = tuple(dict.fromkeys((*scope.event_prefixes, prefix)))
    return scope


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("reset", "verify"), default="reset")
    parser.add_argument(
        "--scope", choices=("all", "current"), default="all",
        help="all uses the complete historical catalog; current uses only the supplied Manifest",
    )
    parser.add_argument(
        "--max-reset-attempts", type=int, default=DEFAULT_RESET_ATTEMPTS,
        help="bounded cleanup retries used when an active consumer writes during reset",
    )
    parser.add_argument(
        "--stabilize-seconds", type=float, default=DEFAULT_STABILIZE_SECONDS,
        help="seconds to wait before confirming that the cleaned state stays stable",
    )
    parser.add_argument("--manifest", action="append", type=Path, default=[])
    parser.add_argument("--report-path", type=Path)
    parser.add_argument("--mysql-bin", default=os.environ.get("PULSEFLOW_TEST_MYSQL_BIN", "mysql"))
    parser.add_argument("--mysql-host", default=os.environ.get("PULSEFLOW_TEST_MYSQL_HOST", DEFAULT_MYSQL_HOST))
    parser.add_argument("--mysql-port", type=int, default=int(os.environ.get("PULSEFLOW_TEST_MYSQL_PORT", DEFAULT_MYSQL_PORT)))
    parser.add_argument("--mysql-user", default=os.environ.get("PULSEFLOW_TEST_MYSQL_USER", DEFAULT_MYSQL_USER))
    parser.add_argument("--mysql-password", default=os.environ.get("PULSEFLOW_TEST_MYSQL_PASSWORD", DEFAULT_MYSQL_PASSWORD))
    parser.add_argument("--mysql-database", default=os.environ.get("PULSEFLOW_TEST_MYSQL_DATABASE", DEFAULT_DATABASE))
    parser.add_argument("--redis-bin", default=os.environ.get("PULSEFLOW_TEST_REDIS_BIN", "redis-cli"))
    parser.add_argument("--redis-host", default=os.environ.get("PULSEFLOW_TEST_REDIS_HOST", DEFAULT_REDIS_HOST))
    parser.add_argument("--redis-port", type=int, default=int(os.environ.get("PULSEFLOW_TEST_REDIS_PORT", DEFAULT_REDIS_PORT)))
    parser.add_argument("--redis-password", default=os.environ.get("PULSEFLOW_TEST_REDIS_PASSWORD", ""))
    parser.add_argument("--redis-database", type=int, default=int(os.environ.get("PULSEFLOW_TEST_REDIS_DATABASE", DEFAULT_REDIS_DATABASE)))
    return parser.parse_args()


def command_exists(command: str) -> bool:
    return Path(command).exists() if Path(command).is_absolute() else shutil.which(command) is not None


def assert_safe_store_target(args: argparse.Namespace) -> None:
    test_env = os.environ.get("PULSEFLOW_TEST_ENV", "").lower() == "test"
    loopback_hosts = {"127.0.0.1", "localhost", "::1"}
    database_is_test = "test" in args.mysql_database.lower() or args.mysql_database.lower().endswith("_test")
    if not test_env or not database_is_test:
        raise ValueError(
            "Refusing Functional state mutation: require PULSEFLOW_TEST_ENV=test "
            "and a test database name."
        )
    nonlocal_allowed = os.environ.get("PULSEFLOW_TEST_ALLOW_NONLOCAL", "false").lower() == "true"
    if (args.mysql_host.lower() not in loopback_hosts or args.redis_host.lower() not in loopback_hosts) and not nonlocal_allowed:
        raise ValueError(
            "Refusing non-loopback MySQL/Redis target without "
            "PULSEFLOW_TEST_ALLOW_NONLOCAL=true."
        )


def run_mysql(args: argparse.Namespace, query: str) -> list[list[str]]:
    if not command_exists(args.mysql_bin):
        raise StateCommandError(f"MySQL client not found: {args.mysql_bin}")
    command = [
        args.mysql_bin, "--batch", "--raw", "--skip-column-names", "--protocol=tcp",
        "-h", args.mysql_host, "-P", str(args.mysql_port), "-u", args.mysql_user,
        args.mysql_database, "-e", query,
    ]
    environment = os.environ.copy()
    environment["MYSQL_PWD"] = args.mysql_password
    try:
        result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8",
                                errors="replace", env=environment, timeout=60)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise StateCommandError(
            f"MySQL command failed (queryLength={len(query)}): {error}"
        ) from error
    if result.returncode != 0:
        raise StateCommandError(result.stderr.strip() or f"MySQL exited with {result.returncode}")
    return [line.split("\t") for line in result.stdout.splitlines() if line != ""]


def run_redis(args: argparse.Namespace, command_args: list[str], timeout: int = 60) -> list[str]:
    if not command_exists(args.redis_bin):
        raise StateCommandError(f"Redis CLI not found: {args.redis_bin}")
    command = [
        args.redis_bin, "-h", args.redis_host, "-p", str(args.redis_port),
        "-n", str(args.redis_database), "--raw", *command_args,
    ]
    environment = os.environ.copy()
    if args.redis_password:
        environment["REDISCLI_AUTH"] = args.redis_password
    try:
        result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8",
                                errors="replace", env=environment, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise StateCommandError(
            f"Redis command failed (argumentCount={len(command_args)}): {error}"
        ) from error
    if result.returncode != 0:
        raise StateCommandError(result.stderr.strip() or f"Redis exited with {result.returncode}")
    return result.stdout.splitlines()


def sql_user_condition(scope: OwnershipScope, column: str = "user_id") -> str:
    parts = [f"{column} BETWEEN {lower} AND {upper}" for lower, upper in scope.user_ranges]
    parts.extend(f"{column} = {user_id}" for user_id in sorted(scope.user_ids))
    return "(" + " OR ".join(parts) + ")" if parts else "(1 = 0)"


def sql_like_condition(column: str, patterns: Iterable[str]) -> str:
    clauses = [f"{column} LIKE '{pattern.replace(chr(39), chr(39) * 2)}'" for pattern in patterns]
    return "(" + " OR ".join(clauses) + ")" if clauses else "(1 = 0)"


def sql_id_condition(column: str, values: Iterable[int]) -> str:
    ids = sorted({int(value) for value in values})
    return f"({column} IN ({', '.join(str(value) for value in ids)}))" if ids else "(1 = 0)"


def sql_campaign_condition(scope: OwnershipScope, column: str = "campaign_id") -> str:
    if not scope.campaign_pattern:
        return "(1 = 0)"
    pattern = scope.campaign_pattern.replace("'", "''")
    return f"({column} IN (SELECT id FROM campaign WHERE name LIKE '{pattern}'))"


def build_mysql_cleanup_sql(scope: OwnershipScope) -> str:
    event_condition = sql_like_condition("event_id", scope.event_patterns)
    target_event_condition = sql_like_condition("target_event_id", scope.event_patterns)
    trigger_event_condition = sql_like_condition("trigger_event_id", scope.event_patterns)
    campaign_condition = sql_campaign_condition(scope)
    user_condition = sql_user_condition(scope)
    campaign_insert = ""
    if scope.campaign_pattern:
        campaign_pattern = scope.campaign_pattern.replace("'", "''")
        campaign_insert = (
            "INSERT IGNORE INTO pf_functional_campaign_ids (id)\n"
            f"    SELECT id FROM campaign WHERE name LIKE '{campaign_pattern}';"
        )
    fixed_task_ids = ", ".join(str(item) for item in sorted(scope.fixed_delivery_task_ids)) or "-1"
    return f"""
START TRANSACTION;
CREATE TEMPORARY TABLE pf_functional_campaign_ids (id BIGINT PRIMARY KEY);
{campaign_insert}
CREATE TEMPORARY TABLE pf_functional_task_ids (id BIGINT PRIMARY KEY);
INSERT IGNORE INTO pf_functional_task_ids (id)
    SELECT id FROM delivery_task
    WHERE {trigger_event_condition}
       OR {campaign_condition};
INSERT IGNORE INTO pf_functional_task_ids (id)
    SELECT id FROM delivery_task WHERE id IN ({fixed_task_ids});
CREATE TEMPORARY TABLE pf_functional_click_ids (id BIGINT PRIMARY KEY);
INSERT IGNORE INTO pf_functional_click_ids (id)
    SELECT id FROM click_event
    WHERE click_source LIKE 'PF_TEST_%'
       OR task_id IN (SELECT id FROM pf_functional_task_ids);

DELETE FROM attribution_record
 WHERE {target_event_condition}
    OR click_event_id IN (SELECT id FROM pf_functional_click_ids);
DELETE FROM attribution_task WHERE {target_event_condition};
DELETE FROM click_event
 WHERE id IN (SELECT id FROM pf_functional_click_ids)
    OR click_source LIKE 'PF_TEST_%';
DELETE FROM in_app_message WHERE business_key IN (SELECT id FROM pf_functional_task_ids);
DELETE FROM push_record WHERE business_key IN (SELECT id FROM pf_functional_task_ids);
DELETE FROM delivery_record WHERE task_id IN (SELECT id FROM pf_functional_task_ids);
DELETE FROM delivery_task WHERE id IN (SELECT id FROM pf_functional_task_ids);

DELETE FROM campaign_ai_review
 WHERE campaign_id IN (SELECT id FROM pf_functional_campaign_ids);
DELETE FROM campaign_performance_summary
 WHERE campaign_id IN (SELECT id FROM pf_functional_campaign_ids);
DELETE FROM ai_generation_record
 WHERE campaign_id IN (SELECT id FROM pf_functional_campaign_ids);
DELETE FROM campaign_ai_draft
 WHERE confirmed_campaign_id IN (SELECT id FROM pf_functional_campaign_ids);
DELETE FROM campaign_execution
 WHERE campaign_id IN (SELECT id FROM pf_functional_campaign_ids);
DELETE FROM campaign_rule
 WHERE campaign_id IN (SELECT id FROM pf_functional_campaign_ids);
DELETE FROM campaign WHERE id IN (SELECT id FROM pf_functional_campaign_ids);

DELETE FROM data_compensation_task WHERE {event_condition};
DELETE FROM user_metric_hourly WHERE {user_condition};
DELETE FROM user_metric_daily WHERE {user_condition};
DELETE FROM user_behavior_summary WHERE {user_condition};
DELETE FROM user_tag WHERE {user_condition};
DELETE FROM user_event WHERE {event_condition};
DELETE FROM user_profile WHERE {user_condition};
COMMIT;
""".strip()


def query_owned_ids(args: argparse.Namespace, scope: OwnershipScope) -> tuple[set[int], set[int], set[int]]:
    event_condition = sql_like_condition("event_id", scope.event_patterns)
    target_event_condition = sql_like_condition("target_event_id", scope.event_patterns)
    trigger_event_condition = sql_like_condition("trigger_event_id", scope.event_patterns)
    campaign_condition = sql_campaign_condition(scope)
    user_rows = run_mysql(args, f"""
SELECT DISTINCT user_id FROM user_event WHERE {event_condition}
UNION SELECT DISTINCT user_id FROM attribution_task WHERE {target_event_condition}
UNION SELECT DISTINCT user_id FROM delivery_task
    WHERE {trigger_event_condition}
       OR {campaign_condition}
UNION SELECT DISTINCT user_id FROM click_event WHERE click_source LIKE 'PF_TEST_%'
ORDER BY user_id
""")
    task_rows = run_mysql(args, f"""
SELECT id FROM delivery_task
 WHERE {trigger_event_condition}
    OR {campaign_condition}
""")
    campaign_rows = run_mysql(args, f"""
SELECT id FROM campaign WHERE {sql_like_condition('name', (scope.campaign_pattern,)) if scope.campaign_pattern else '1 = 0'}
""")
    users = {int(row[0]) for row in user_rows if row and row[0]}
    tasks = {int(row[0]) for row in task_rows if row and row[0]}
    campaigns = {int(row[0]) for row in campaign_rows if row and row[0]}
    return users, tasks, campaigns


def chunks(values: list[str], size: int = DEFAULT_BATCH_SIZE) -> Iterable[list[str]]:
    for start in range(0, len(values), size):
        yield values[start:start + size]


def scan_keys(args: argparse.Namespace, pattern: str) -> list[str]:
    # redis-cli 5.0 on Windows does not reliably pass --scan --pattern to a
    # Redis 7 server. Use the Redis SCAN command directly and filter only the
    # returned members; deletion is still restricted by key_is_owned().
    cursor = "0"
    keys: list[str] = []
    while True:
        output = run_redis(args, ["SCAN", cursor, "MATCH", pattern, "COUNT", "10000"], timeout=120)
        if not output:
            raise StateCommandError("Redis SCAN returned an empty response")
        cursor = output[0]
        keys.extend(output[1:])
        if cursor == "0":
            return keys


def parse_user_from_key(key: str) -> int | None:
    match = re.match(r"^(?:user:(?:rt|cart|window):|user:daily:|freq:user:)(\d+)(?::|$)", key)
    return int(match.group(1)) if match else None


def key_is_owned(key: str, scope: OwnershipScope, task_ids: set[int]) -> bool:
    if key.startswith("event:processed:"):
        return scope.owns_event_id(key[len("event:processed:"):])
    user_id = parse_user_from_key(key)
    if user_id is not None:
        return scope.owns_user_id(user_id)
    campaign_match = re.match(r"^freq:campaign:(\d+):(\d+)$", key)
    if campaign_match:
        return int(campaign_match.group(1)) in scope.campaign_ids and scope.owns_user_id(int(campaign_match.group(2)))
    reserved_match = re.match(r"^freq:reserved:(\d+)$", key)
    return bool(reserved_match and int(reserved_match.group(1)) in task_ids)


def delete_redis_keys(args: argparse.Namespace, keys: Iterable[str]) -> int:
    key_list = sorted(set(keys))
    deleted = 0
    for batch in chunks(key_list):
        output = run_redis(args, ["DEL", *batch]) if batch else []
        if output and output[0].strip().isdigit():
            deleted += int(output[0])
    return deleted


def remove_owned_zset_members(args: argparse.Namespace, scope: OwnershipScope, key: str) -> int:
    members = run_redis(args, ["ZRANGE", key, "0", "-1"], timeout=120)
    if key in {"delay:attribution", "delay:attribution:processing"}:
        owned = [member for member in members if scope.owns_event_id(member)]
    else:
        # Delayed task IDs encode the originating add-cart event as their last
        # component. Restrict the match to the Functional event namespace.
        owned = [member for member in members if any(prefix in member for prefix in scope.event_prefixes)]
    removed = 0
    for batch in chunks(owned):
        output = run_redis(args, ["ZREM", key, *batch]) if batch else []
        if output and output[0].strip().isdigit():
            removed += int(output[0])
    return removed


def verify_redis(args: argparse.Namespace, scope: OwnershipScope, task_ids: set[int]) -> dict[str, Any]:
    remaining_keys = {
        key for key in scan_keys(args, "*") if key_is_owned(key, scope, task_ids)
    }
    zsets = [
        "delay:attribution", "delay:attribution:processing",
        "delay:pending:DELAYED_CAMPAIGN", "delay:processing:DELAYED_CAMPAIGN",
    ]
    remaining_members: dict[str, int] = {}
    for key in zsets:
        members = run_redis(args, ["ZRANGE", key, "0", "-1"], timeout=120)
        if key in {"delay:attribution", "delay:attribution:processing"}:
            owned = [member for member in members if scope.owns_event_id(member)]
        else:
            owned = [member for member in members if any(prefix in member for prefix in scope.event_prefixes)]
        if owned:
            remaining_members[key] = len(owned)
    return {
        "remainingKeyCount": len(remaining_keys),
        "remainingKeys": sorted(remaining_keys),
        "remainingZsetMembers": remaining_members,
    }


def verify_mysql(args: argparse.Namespace, scope: OwnershipScope) -> dict[str, Any]:
    event_condition = sql_like_condition("event_id", scope.event_patterns)
    target_event_condition = sql_like_condition("target_event_id", scope.event_patterns)
    trigger_event_condition = sql_like_condition("trigger_event_id", scope.event_patterns)
    campaign_condition = sql_campaign_condition(scope)
    campaign_id_condition = sql_id_condition("campaign_id", scope.campaign_ids)
    campaign_name_condition = (
        sql_like_condition("name", (scope.campaign_pattern,))
        if scope.campaign_pattern else "(1 = 0)"
    )
    user_condition = sql_user_condition(scope)
    query = f"""
SELECT 'user_event', COUNT(*) FROM user_event WHERE {event_condition}
UNION ALL SELECT 'data_compensation_task', COUNT(*) FROM data_compensation_task WHERE {event_condition}
UNION ALL SELECT 'attribution_task', COUNT(*) FROM attribution_task WHERE {target_event_condition}
UNION ALL SELECT 'attribution_record', COUNT(*) FROM attribution_record WHERE {target_event_condition}
UNION ALL SELECT 'delivery_task', COUNT(*) FROM delivery_task
    WHERE {trigger_event_condition}
       OR {campaign_condition}
UNION ALL SELECT 'delivery_record', COUNT(*) FROM delivery_record
    WHERE {campaign_id_condition}
UNION ALL SELECT 'in_app_message', COUNT(*) FROM in_app_message
    WHERE {campaign_id_condition}
UNION ALL SELECT 'push_record', COUNT(*) FROM push_record
    WHERE {campaign_id_condition}
UNION ALL SELECT 'click_event', COUNT(*) FROM click_event WHERE click_source LIKE 'PF_TEST_%'
UNION ALL SELECT 'campaign', COUNT(*) FROM campaign WHERE {campaign_name_condition}
UNION ALL SELECT 'campaign_rule', COUNT(*) FROM campaign_rule WHERE {campaign_id_condition}
UNION ALL SELECT 'campaign_execution', COUNT(*) FROM campaign_execution WHERE {campaign_id_condition}
UNION ALL SELECT 'campaign_performance_summary', COUNT(*) FROM campaign_performance_summary WHERE {campaign_id_condition}
UNION ALL SELECT 'campaign_ai_review', COUNT(*) FROM campaign_ai_review WHERE {campaign_id_condition}
UNION ALL SELECT 'ai_generation_record', COUNT(*) FROM ai_generation_record WHERE {campaign_id_condition}
UNION ALL SELECT 'campaign_ai_draft', COUNT(*) FROM campaign_ai_draft
    WHERE confirmed_campaign_id IN ({', '.join(str(value) for value in sorted(scope.campaign_ids)) or '-1'})
UNION ALL SELECT 'user_metric_hourly', COUNT(*) FROM user_metric_hourly WHERE {user_condition}
UNION ALL SELECT 'user_metric_daily', COUNT(*) FROM user_metric_daily WHERE {user_condition}
UNION ALL SELECT 'user_behavior_summary', COUNT(*) FROM user_behavior_summary WHERE {user_condition}
UNION ALL SELECT 'user_tag', COUNT(*) FROM user_tag WHERE {user_condition}
UNION ALL SELECT 'user_profile', COUNT(*) FROM user_profile WHERE {user_condition}
"""
    rows = run_mysql(args, query)
    return {row[0]: int(row[1]) for row in rows if len(row) >= 2}


def reset_once(args: argparse.Namespace, scope: OwnershipScope) -> dict[str, Any]:
    users_before, tasks_before, campaigns_before = query_owned_ids(args, scope)
    # The catalog ranges already own generated users. Only retain an ID that
    # falls outside those declared ranges (for example a future fixed fixture)
    # so a large normal dataset cannot expand the SQL command line one ID at a
    # time.
    scope.user_ids.update(
        user_id for user_id in users_before
        if not any(lower <= user_id <= upper for lower, upper in scope.user_ranges)
    )
    scope.delivery_task_ids.update(tasks_before)
    scope.campaign_ids.update(campaigns_before)
    mysql_before = verify_mysql(args, scope)
    run_mysql(args, build_mysql_cleanup_sql(scope))

    redis_keys = {
        key for key in scan_keys(args, "*")
        if key_is_owned(key, scope, scope.delivery_task_ids)
    }
    redis_deleted = delete_redis_keys(args, redis_keys)
    redis_zset_deleted = 0
    for key in (
        "delay:attribution", "delay:attribution:processing",
        "delay:pending:DELAYED_CAMPAIGN", "delay:processing:DELAYED_CAMPAIGN",
    ):
        redis_zset_deleted += remove_owned_zset_members(args, scope, key)

    mysql_remaining = verify_mysql(args, scope)
    redis_remaining = verify_redis(args, scope, scope.delivery_task_ids)
    return {
        "status": "PASS" if not any(mysql_remaining.values())
        and redis_remaining["remainingKeyCount"] == 0
        and not redis_remaining["remainingZsetMembers"] else "FAIL",
        "mysql": {
            "rowsBefore": mysql_before,
            "remainingOwnedRows": mysql_remaining,
        },
        "redis": {
            "keysMatched": len(redis_keys),
            "keysDeleted": redis_deleted,
            "zsetMembersDeleted": redis_zset_deleted,
            **redis_remaining,
        },
        "ownership": {
            "eventPatterns": list(scope.event_patterns),
            "eventPrefixes": list(scope.event_prefixes),
            "userRanges": [{"min": lower, "max": upper} for lower, upper in scope.user_ranges],
            "campaignIds": sorted(scope.campaign_ids),
            "deliveryTaskIds": sorted(scope.delivery_task_ids),
        },
    }


def reset_result_is_clean(result: dict[str, Any]) -> bool:
    mysql = result.get("mysql", {}).get("remainingOwnedRows", {})
    redis = result.get("redis", {})
    return (
        result.get("status") == "PASS"
        and not any(mysql.values())
        and redis.get("remainingKeyCount") == 0
        and not redis.get("remainingZsetMembers")
    )


def reset(args: argparse.Namespace, scope: OwnershipScope) -> dict[str, Any]:
    """Run a bounded cleanup convergence loop.

    Functional's Kafka consumers may still be draining messages from an
    interrupted run while MySQL/Redis are being reset. A single snapshot can
    therefore be dirty again immediately after a successful DELETE/DEL. The
    loop repeats the same ownership-scoped cleanup and requires a clean
    verification after a short settling interval before returning PASS.
    """
    attempts: list[dict[str, Any]] = []
    last_result: dict[str, Any] | None = None
    max_attempts = max(1, int(args.max_reset_attempts))
    settle_seconds = max(0.0, float(args.stabilize_seconds))

    for attempt in range(1, max_attempts + 1):
        last_result = reset_once(args, scope)
        clean_now = reset_result_is_clean(last_result)
        attempts.append({
            "attempt": attempt,
            "cleanImmediately": clean_now,
            "remainingMysqlRows": sum(last_result.get("mysql", {}).get("remainingOwnedRows", {}).values()),
            "remainingRedisKeys": last_result.get("redis", {}).get("remainingKeyCount"),
            "remainingRedisZsetMembers": sum(last_result.get("redis", {}).get("remainingZsetMembers", {}).values()),
        })

        if clean_now and settle_seconds > 0:
            time.sleep(settle_seconds)
            stable_mysql = verify_mysql(args, scope)
            stable_redis = verify_redis(args, scope, scope.delivery_task_ids)
            stable = not any(stable_mysql.values()) \
                and stable_redis["remainingKeyCount"] == 0 \
                and not stable_redis["remainingZsetMembers"]
            last_result["mysql"]["remainingOwnedRows"] = stable_mysql
            last_result["redis"].update(stable_redis)
            attempts[-1]["stableAfterWait"] = stable
            if stable:
                break
        elif clean_now:
            attempts[-1]["stableAfterWait"] = True
            break

        if attempt < max_attempts and settle_seconds > 0:
            time.sleep(settle_seconds)

    if last_result is None:
        raise StateCommandError("Functional state reset did not run")
    last_result["status"] = "PASS" if reset_result_is_clean(last_result) else "FAIL"
    last_result["stabilization"] = {
        "maxAttempts": max_attempts,
        "settleSeconds": settle_seconds,
        "attempts": attempts,
        "stable": last_result["status"] == "PASS",
    }
    return last_result


def main() -> int:
    args = parse_args()
    report: dict[str, Any] = {"mode": args.mode, "scope": args.scope, "status": "NOT_RUN"}
    try:
        assert_safe_store_target(args)
        if args.scope == "current" and not args.manifest:
            raise ValueError("--scope current requires at least one --manifest")
        scope = load_scope(
            (path.resolve() for path in args.manifest),
            include_catalog=args.scope == "all",
        )
        if args.mode == "verify":
            _, tasks, campaigns = query_owned_ids(args, scope)
            scope.delivery_task_ids.update(tasks)
            scope.campaign_ids.update(campaigns)
            report["mysql"] = {"remainingOwnedRows": verify_mysql(args, scope)}
            report["redis"] = verify_redis(args, scope, tasks)
            report["status"] = "PASS" if not any(report["mysql"]["remainingOwnedRows"].values()) \
                and report["redis"]["remainingKeyCount"] == 0 \
                and not report["redis"]["remainingZsetMembers"] else "FAIL"
        else:
            report = {"mode": args.mode, "scope": args.scope, **reset(args, scope)}
    except (OSError, ValueError, StateCommandError, KeyError, json.JSONDecodeError) as error:
        report["status"] = "FAIL"
        report["error"] = str(error)

    if args.report_path:
        args.report_path.parent.mkdir(parents=True, exist_ok=True)
        args.report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"functional state {report['status']}: mode={args.mode}")
    if report["status"] == "PASS":
        return 0
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
