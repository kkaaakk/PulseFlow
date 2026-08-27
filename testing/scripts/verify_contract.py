#!/usr/bin/env python3
"""Verify that the replay harness still matches PulseFlow source contracts."""

from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]


def read(root: Path, relative: str) -> str:
    return (root / relative).read_text(encoding="utf-8")


def check_contains(source: str, patterns: list[str]) -> tuple[bool, list[str]]:
    missing = [pattern for pattern in patterns if pattern not in source]
    return not missing, missing


def extract_event_types(source: str) -> list[str]:
    match = re.search(r"enum\s+EventType\s*\{(?P<body>.*?)\}", source, flags=re.DOTALL)
    if not match:
        return []
    return re.findall(r"\b[A-Z][A-Z0-9_]*\b", match.group("body"))


def build_checks(root: Path) -> list[dict[str, Any]]:
    event_type_source = read(
        root, "pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/enums/EventType.java"
    )
    event_controller = read(
        root, "pulseflow/pulseflow-event/src/main/java/com/pulseflow/event/controller/EventController.java"
    )
    event_service = read(
        root, "pulseflow/pulseflow-event/src/main/java/com/pulseflow/event/service/EventService.java"
    )
    event_consumer = read(
        root, "pulseflow/pulseflow-event/src/main/java/com/pulseflow/event/consumer/EventConsumer.java"
    )
    persistence = read(
        root, "pulseflow/pulseflow-event/src/main/java/com/pulseflow/event/service/EventPersistenceService.java"
    )
    realtime = read(
        root,
        "pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/profile/RealtimeProfileUpdateService.java",
    )
    attribution = read(
        root,
        "pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/attribution/AttributionService.java",
    )
    migration = read(root, "pulseflow/pulseflow-boot/src/main/resources/db/migration/V1__init.sql")
    v2 = read(root, "pulseflow/pulseflow-boot/src/main/resources/db/migration/V2__channel_tables.sql")
    v3 = read(root, "pulseflow/pulseflow-boot/src/main/resources/db/migration/V3__ai_campaign_tables.sql")
    v5 = read(root, "pulseflow/pulseflow-boot/src/main/resources/db/migration/V5__review_status_split_and_ownership.sql")

    expected_event_types = [
        "LOGIN",
        "CONTENT_VIEW",
        "SEARCH",
        "LIKE",
        "FAVORITE",
        "ADD_CART",
        "REMOVE_CART",
        "ORDER_CREATE",
        "ORDER_PAID",
        "SHARE",
        "CLICK",
    ]
    checks: list[dict[str, Any]] = []

    def add(check_id: str, description: str, passed: bool, expected: Any, actual: Any, files: list[str]) -> None:
        checks.append(
            {
                "checkId": check_id,
                "description": description,
                "status": "PASS" if passed else "FAIL",
                "expected": expected,
                "actual": actual,
                "files": files,
            }
        )

    actual_types = extract_event_types(event_type_source)
    add("event-types", "EventType enum matches generator contract", actual_types == expected_event_types,
        expected_event_types, actual_types, ["pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/enums/EventType.java"])

    passed, missing = check_contains(event_controller, ['@RequestMapping("/api/events")', "@PostMapping", "@Valid"])
    add("event-ingress", "HTTP event endpoint and validation annotations exist", passed,
        ["/api/events", "POST", "@Valid"], missing or "present", ["pulseflow/pulseflow-event/src/main/java/com/pulseflow/event/controller/EventController.java"])

    passed, missing = check_contains(event_service, ['TOPIC = "pulseflow.raw.events"', "MAX_TIME_SKEW_MINUTES = 5", "skew.toMinutes() > MAX_TIME_SKEW_MINUTES"])
    add("event-service", "Kafka topic and clock-skew behavior match source", passed,
        ["pulseflow.raw.events", 5, "strict whole-minute comparison"], missing or "present", ["pulseflow/pulseflow-event/src/main/java/com/pulseflow/event/service/EventService.java"])

    passed, missing = check_contains(event_consumer, ['@KafkaListener(topics = "pulseflow.raw.events"', 'Set.of("ORDER_PAID")', "eventPersistenceService.persist"])
    add("event-consumer", "Consumer topic, canonical persistence, and attribution target match source", passed,
        ["raw event listener", "ORDER_PAID", "persist"], missing or "present", ["pulseflow/pulseflow-event/src/main/java/com/pulseflow/event/consumer/EventConsumer.java"])

    passed, missing = check_contains(persistence, ["@Transactional(rollbackFor = Exception.class)", "buildUserEvent", "upsertMetricHourlyAtomic"])
    add("persistence", "Phase 1 transaction and metric upsert path exist", passed,
        ["transaction", "event unique key", "hourly upsert"], missing or "present", ["pulseflow/pulseflow-event/src/main/java/com/pulseflow/event/service/EventPersistenceService.java"])

    redis_keys = ["event:processed:", "user:rt:", "user:daily:", "user:cart:"]
    passed, missing = check_contains(realtime, redis_keys + ["604800", "EXISTS"])
    add("redis-realtime", "Realtime Redis keys and processed TTL match source", passed,
        redis_keys + ["7-day TTL", "processed EXISTS guard"], missing or "present", ["pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/profile/RealtimeProfileUpdateService.java"])

    passed, missing = check_contains(attribution, ["GRACE_WINDOW_SECONDS = 300", "ATTRIBUTION_WINDOW_HOURS = 24", '"CLICK_LAST_TOUCH"', "clickEventMapper.selectList"])
    add("attribution", "Attribution window, grace period, and click lookup match source", passed,
        [300, 24, "CLICK_LAST_TOUCH", "click_event lookup"], missing or "present", ["pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/attribution/AttributionService.java"])

    required_tables = [
        "CREATE TABLE user_event",
        "CREATE TABLE user_metric_hourly",
        "CREATE TABLE user_metric_daily",
        "CREATE TABLE user_behavior_summary",
        "CREATE TABLE user_tag",
        "CREATE TABLE campaign",
        "CREATE TABLE campaign_rule",
        "CREATE TABLE campaign_execution",
        "CREATE TABLE delivery_task",
        "CREATE TABLE delivery_record",
        "CREATE TABLE click_event",
        "CREATE TABLE attribution_task",
        "CREATE TABLE attribution_record",
        "CREATE TABLE data_compensation_task",
    ]
    passed, missing = check_contains(migration, required_tables)
    add("v1-tables", "V1 migration contains the core physical tables", passed,
        required_tables, missing or "present", ["pulseflow/pulseflow-boot/src/main/resources/db/migration/V1__init.sql"])

    passed, missing = check_contains(migration, ["UNIQUE KEY uk_event_id (event_id)", "UNIQUE KEY uk_user_hour_type", "UNIQUE KEY uk_dedup (dedup_key)"])
    add("v1-uniques", "Core idempotency keys exist in MySQL schema", passed,
        ["event_id", "user/hour/type", "delivery dedup_key"], missing or "present", ["pulseflow/pulseflow-boot/src/main/resources/db/migration/V1__init.sql"])

    passed, missing = check_contains(v2, ["CREATE TABLE in_app_message", "CREATE TABLE push_record", "UNIQUE KEY uk_business_key"])
    add("v2-channel-tables", "Channel idempotency tables exist", passed,
        ["in_app_message", "push_record", "business key unique"], missing or "present", ["pulseflow/pulseflow-boot/src/main/resources/db/migration/V2__channel_tables.sql"])

    passed, missing = check_contains(v3, ["CREATE TABLE campaign_ai_draft", "CREATE TABLE ai_generation_record", "CREATE TABLE campaign_performance_summary", "CREATE TABLE campaign_ai_review"])
    add("v3-ai-tables", "AI persistence tables exist", passed,
        ["four AI tables"], missing or "present", ["pulseflow/pulseflow-boot/src/main/resources/db/migration/V3__ai_campaign_tables.sql"])

    passed, missing = check_contains(v5, ["ADD COLUMN created_by", "ADD COLUMN failure_code", "CREATE INDEX idx_ai_review_status_retry"])
    add("v5-ai-hardening", "Ownership/retry migration is present", passed,
        ["created_by", "failure_code", "retry index"], missing or "present", ["pulseflow/pulseflow-boot/src/main/resources/db/migration/V5__review_status_split_and_ownership.sql"])
    return checks


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=ROOT)
    parser.add_argument("--output", type=Path, default=ROOT / "testing" / "reports" / "contract-check.json")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        checks = build_checks(args.repo_root.resolve())
    except (OSError, ValueError) as error:
        print(f"contract check failed to read source: {error}", file=sys.stderr)
        return 2
    result = {
        "status": "PASS" if all(check["status"] == "PASS" for check in checks) else "FAIL",
        "checkedAt": datetime.now(timezone.utc).isoformat(),
        "checks": checks,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for check in checks:
        print(f"{check['status']}: {check['checkId']} — {check['description']}")
        if check["status"] == "FAIL":
            print(f"  expected={check['expected']}")
            print(f"  actual={check['actual']}")
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
