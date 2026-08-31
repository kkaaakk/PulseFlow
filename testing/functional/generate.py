#!/usr/bin/env python3
"""Deterministic PulseFlow dataset generator.

The generator intentionally uses only the Python standard library.  It emits
request-shaped JSONL records plus a manifest containing the expected canonical
event and hourly-metric totals.  No generated data is committed to Git.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import random
from collections import Counter, defaultdict
from datetime import datetime, timedelta
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any, Iterable


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUTPUT_DIR = ROOT / "testing" / "data" / "generated"

SCALES: dict[str, dict[str, int]] = {
    "SMALL": {"users": 1_000, "events": 10_000},
    "MEDIUM": {"users": 10_000, "events": 100_000},
    "LARGE": {"users": 50_000, "events": 1_000_000},
}

# This list is copied from pulseflow-common/EventType.java. Keep generated
# request data aligned with the application event model; runtime behavior is
# verified by Maven/HTTP tests rather than source-text scanning.
EVENT_TYPES = [
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

EVENT_TYPE_WEIGHTS = [
    ("LOGIN", 2),
    ("CONTENT_VIEW", 36),
    ("SEARCH", 12),
    ("LIKE", 12),
    ("FAVORITE", 8),
    ("ADD_CART", 10),
    ("REMOVE_CART", 4),
    ("ORDER_CREATE", 5),
    ("ORDER_PAID", 5),
    ("SHARE", 4),
    ("CLICK", 2),
]

# Separate user-id namespaces make SQL validation safe when several datasets
# are replayed into the same test database. Replaying a dataset remains
# idempotent because its event ids do not change.
USER_BASES = {
    "normal": 1_000_000,
    "duplicate": 2_000_000,
    "out-of-order": 3_000_000,
    "late": 4_000_000,
    "invalid": 5_000_000,
    "hot-user": 6_000_000,
    "campaign": 7_000_000,
    "concurrency": 8_500_000,
}

BASE_TIME = datetime(2026, 8, 27, 12, 0, 0)


def stable_json(value: Any) -> str:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )


def iso_time(value: datetime, milliseconds: bool = False) -> str:
    if milliseconds:
        return value.isoformat(timespec="milliseconds")
    return value.isoformat(timespec="seconds")


def slug(value: str) -> str:
    return value.strip().lower().replace("_", "-")


def dataset_id_for(scenario: str, scale: str) -> str:
    if scenario == "normal":
        return f"normal-events-{scale.lower()}-v1"
    if scenario == "hot-user":
        return f"hot-user-events-{scale.lower()}-v1"
    return {
        "duplicate": "duplicate-events-v1",
        "out-of-order": "out-of-order-events-v1",
        "late": "late-events-v1",
        "invalid": "invalid-payload-v1",
        "campaign": "campaign-frequency-attribution-v1",
        "concurrency": "concurrency-events-v1",
    }[scenario]


def event_id(scenario: str, seed: int, index: int) -> str:
    return f"pf-{slug(scenario)}-{seed}-{index:08d}"


def choose_event_type(rng: random.Random) -> str:
    total = sum(weight for _, weight in EVENT_TYPE_WEIGHTS)
    selected = rng.randrange(total)
    for event_type, weight in EVENT_TYPE_WEIGHTS:
        if selected < weight:
            return event_type
        selected -= weight
    return EVENT_TYPES[-1]


def amount_for(rng: random.Random, minimum: int = 99, maximum: int = 2_000) -> float:
    cents = rng.randint(minimum, maximum)
    return float(f"{cents / 100:.2f}")


def properties_for(event_type: str, rng: random.Random, index: int) -> dict[str, Any]:
    props: dict[str, Any] = {"category": f"category-{index % 8}"}
    if event_type in {"CONTENT_VIEW", "SEARCH", "LIKE", "FAVORITE", "SHARE"}:
        props["duration"] = rng.randint(500, 120_000)
    if event_type in {"ADD_CART", "REMOVE_CART", "ORDER_CREATE", "ORDER_PAID"}:
        props["cartItemId"] = f"SKU-{index % 500:04d}"
        props["price"] = amount_for(rng)
    if event_type in {"ORDER_CREATE", "ORDER_PAID"}:
        props["orderId"] = f"order-{index:08d}"
        props["currency"] = "CNY"
    if event_type == "CLICK":
        props["taskId"] = 10_000 + (index % 2_000)
        props["campaignId"] = 20_000 + (index % 50)
    if event_type == "SEARCH":
        props["query"] = f"query-{index % 2_000}"
    return props


def generated_event(
    scenario: str,
    seed: int,
    index: int,
    user_id: int,
    event_type: str,
    event_time: datetime,
    rng: random.Random,
    *,
    milliseconds: bool = False,
) -> dict[str, Any]:
    target_id: int | None = None
    if event_type not in {"LOGIN", "SEARCH"}:
        target_id = 5_000 + (index % 20_000)
    return {
        "eventId": event_id(scenario, seed, index),
        "userId": user_id,
        "eventType": event_type,
        "targetId": target_id,
        "eventTime": iso_time(event_time, milliseconds=milliseconds),
        "properties": properties_for(event_type, rng, index),
    }


def normal_events(scale: str, seed: int, scenario: str = "normal") -> list[dict[str, Any]]:
    config = SCALES[scale]
    rng = random.Random(seed)
    rows: list[dict[str, Any]] = []
    for index in range(config["events"]):
        bucket = index % 4
        if bucket == 0:
            offset = timedelta(minutes=rng.randint(0, 59))
        elif bucket == 1:
            offset = timedelta(days=1, hours=rng.randint(0, 23))
        elif bucket == 2:
            offset = timedelta(days=rng.randint(2, 6), hours=rng.randint(0, 23))
        else:
            offset = timedelta(days=rng.randint(8, 29), hours=rng.randint(0, 23))
        user_id = USER_BASES[scenario] + (index * 37) % config["users"]
        event_type = choose_event_type(rng)
        rows.append(
            generated_event(
                scenario,
                seed,
                index,
                user_id,
                event_type,
                BASE_TIME - offset,
                rng,
            )
        )
    return rows


def duplicate_events(seed: int) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    rng = random.Random(seed + 101)
    base: list[dict[str, Any]] = []
    for index in range(100):
        base.append(
            generated_event(
                "duplicate",
                seed,
                index,
                USER_BASES["duplicate"] + (index % 10),
                "CONTENT_VIEW" if index % 3 else "ORDER_PAID",
                BASE_TIME - timedelta(minutes=index),
                rng,
            )
        )

    rows = [dict(row) for row in base]
    for index in range(10):
        rows.append(json.loads(stable_json(base[index])))

    conflicting_ids: list[str] = []
    conflicting_expected: dict[str, dict[str, Any]] = {}
    for index in range(10, 20):
        conflicting = json.loads(stable_json(base[index]))
        conflicting["properties"]["price"] = amount_for(rng, 3_000, 4_000)
        conflicting["properties"]["duration"] = 999_999
        rows.append(conflicting)
        conflicting_ids.append(base[index]["eventId"])
        conflicting_expected[base[index]["eventId"]] = {
            "eventType": base[index]["eventType"],
            "userId": base[index]["userId"],
            "targetId": base[index]["targetId"],
            "properties": base[index]["properties"],
        }

    replay_id = base[20]["eventId"]
    for _ in range(9):
        rows.append(json.loads(stable_json(base[20])))

    details = {
        "exactDuplicateEventIds": [row["eventId"] for row in base[:10]],
        "conflictingPayloadEventIds": conflicting_ids,
        "conflictingPayloadExpected": conflicting_expected,
        "replayedTenTimesEventId": replay_id,
        "replayedTenTimesTotalOccurrences": 10,
    }
    return rows, details


def out_of_order_events(seed: int) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    rng = random.Random(seed + 202)
    user_id = USER_BASES["out-of-order"] + 1
    rows: list[dict[str, Any]] = []
    first_three: list[dict[str, Any]] = []
    for index, minute in enumerate((0, 1, 2)):
        first_three.append(
            generated_event(
                "out-of-order",
                seed,
                index,
                user_id,
                "CONTENT_VIEW",
                BASE_TIME.replace(hour=10, minute=minute),
                rng,
            )
        )
    # Arrival: 10:02, 10:00, 10:01.
    rows.extend([first_three[2], first_three[0], first_three[1]])

    click_a = generated_event(
        "out-of-order",
        seed,
        3,
        user_id,
        "CLICK",
        BASE_TIME.replace(hour=10, minute=3),
        rng,
    )
    click_a["properties"].update({"taskId": 9101, "campaignId": 9101, "touch": "A"})
    click_b = generated_event(
        "out-of-order",
        seed,
        4,
        user_id,
        "CLICK",
        BASE_TIME.replace(hour=10, minute=4),
        rng,
    )
    click_b["properties"].update({"taskId": 9102, "campaignId": 9102, "touch": "B"})
    conversion = generated_event(
        "out-of-order",
        seed,
        5,
        user_id,
        "ORDER_PAID",
        BASE_TIME.replace(hour=10, minute=5),
        rng,
    )
    conversion["properties"].update({"orderId": "late-conversion-1", "price": 328.0})
    # Conversion arrives before both clicks, while event time says it is last.
    rows.extend([conversion, click_b, click_a])

    return rows, {
        "baseTime": iso_time(BASE_TIME),
        "eventTimeOrder": [row["eventId"] for row in first_three]
        + [click_a["eventId"], click_b["eventId"], conversion["eventId"]],
        "arrivalOrder": [row["eventId"] for row in rows],
        "attributionExpectedIfClickEventsAreMaterialized": {
            "model": "CLICK_LAST_TOUCH",
            "campaignId": 9102,
            "taskId": 9102,
        },
        "sourceLimitation": (
            "The current HTTP event consumer persists CLICK as user_event but does not "
            "call ClickEventService; attribution requires click_event rows."
        ),
    }


def late_events(seed: int) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    rng = random.Random(seed + 303)
    cases = [
        ("inside-4m59s", -299.999, False),
        ("exactly-5m", -300.0, False),
        # EventService uses Duration.toMinutes() > 5, so +1ms is still 5 minutes.
        ("outside-by-1ms", -300.001, False),
        ("outside-by-1s", -301.0, True),
        ("severely-old-30d", -30 * 24 * 60 * 60, True),
        ("future-60s", 60.0, False),
    ]
    rows: list[dict[str, Any]] = []
    manifest_cases: list[dict[str, Any]] = []
    for index, (case_id, offset_seconds, expected_clock_skew) in enumerate(cases):
        timestamp = BASE_TIME + timedelta(seconds=offset_seconds)
        row = generated_event(
            "late",
            seed,
            index,
            USER_BASES["late"] + index,
            "CONTENT_VIEW",
            timestamp,
            rng,
            milliseconds=True,
        )
        rows.append(row)
        manifest_cases.append(
            {
                "caseId": case_id,
                "eventId": row["eventId"],
                "offsetSecondsFromBaseTime": offset_seconds,
                "expectedClockSkewAtBaseTime": expected_clock_skew,
                "expectedEffectiveTime": "server receive time when skew is true",
            }
        )
    return rows, {
        "baseTime": iso_time(BASE_TIME),
        "rule": "abs(eventTime - receiveTime).toMinutes() > 5",
        "cases": manifest_cases,
        "sourceLimitation": (
            "The API accepts late/future events and marks large skew; it does not reject "
            "them with a 4xx response."
        ),
    }


def invalid_payload_events(seed: int) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    base_user = USER_BASES["invalid"]
    valid = {
        "eventId": event_id("invalid", seed, 99),
        "userId": base_user,
        "eventType": "CONTENT_VIEW",
        "targetId": 5001,
        "eventTime": iso_time(BASE_TIME),
        "properties": {"duration": 1000},
    }

    cases: list[dict[str, Any]] = [
        {
            "caseId": "missing-event-id",
            "body": {key: value for key, value in valid.items() if key != "eventId"},
            "expectedStatus": 400,
            "expectedOutcome": "rejected-by-bean-validation",
        },
        {
            "caseId": "blank-event-id",
            "body": {**valid, "eventId": ""},
            "expectedStatus": 400,
            "expectedOutcome": "rejected-by-bean-validation",
        },
        {
            "caseId": "missing-user-id",
            "body": {key: value for key, value in valid.items() if key != "userId"},
            "expectedStatus": 400,
            "expectedOutcome": "rejected-by-bean-validation",
        },
        {
            "caseId": "wrong-user-id-type",
            "body": {**valid, "userId": "not-a-number"},
            "expectedStatus": 500,
            "expectedOutcome": "current-global-handler-maps-message-conversion-to-500",
        },
        {
            "caseId": "missing-event-time",
            "body": {key: value for key, value in valid.items() if key != "eventTime"},
            "expectedStatus": 400,
            "expectedOutcome": "rejected-by-bean-validation",
        },
        {
            "caseId": "blank-event-type",
            "body": {**valid, "eventType": " "},
            "expectedStatus": 400,
            "expectedOutcome": "rejected-by-bean-validation",
        },
        {
            "caseId": "malformed-json",
            "body": '{"eventId":"broken",',
            "expectedStatus": 500,
            "expectedOutcome": "current-global-handler-maps-message-conversion-to-500",
        },
        {
            "caseId": "unknown-event-type",
            "body": {**valid, "eventId": event_id("invalid", seed, 1), "eventType": "NOT_IN_EVENT_TYPE"},
            "expectedStatus": 200,
            "expectedOutcome": "accepted-at-http-layer; downstream-contract-gap-to-observe",
        },
        {
            "caseId": "negative-price",
            "body": {
                **valid,
                "eventId": event_id("invalid", seed, 2),
                "eventType": "ORDER_PAID",
                "properties": {"price": -1.0, "orderId": "negative-price"},
            },
            "expectedStatus": 200,
            "expectedOutcome": "accepted-at-http-layer; data-quality-gap-to-observe",
        },
        {
            "caseId": "missing-properties",
            "body": {key: value for key, value in valid.items() if key != "properties"},
            "expectedStatus": 200,
            "expectedOutcome": "accepted-properties-optional",
        },
        {
            "caseId": "non-uuid-event-id",
            "body": {**valid, "eventId": "not-a-uuid", "userId": base_user + 3},
            "expectedStatus": 200,
            "expectedOutcome": "accepted-string-id; UUID-format-not-required-by-source",
        },
        {
            "caseId": "oversized-event-id",
            "body": {**valid, "eventId": "x" * 65, "userId": base_user + 4},
            "expectedStatus": 200,
            "expectedOutcome": "accepted-at-http-layer; DB-column-limit-to-observe",
        },
        {
            "caseId": "extreme-price",
            "body": {
                **valid,
                "eventId": event_id("invalid", seed, 5),
                "eventType": "ORDER_PAID",
                "userId": base_user + 5,
                "properties": {"price": 999999999999.99, "orderId": "extreme-price"},
            },
            "expectedStatus": 200,
            "expectedOutcome": "accepted-at-http-layer; DECIMAL-range-to-observe",
        },
    ]
    return cases, {
        "statusContract": "ExpectedStatus reflects current controller/Bean Validation behavior, not desired future behavior.",
        "cases": [
            {key: value for key, value in case.items() if key != "body"}
            for case in cases
        ],
    }


def hot_user_events(scale: str, seed: int) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    count = 1_000 if scale == "SMALL" else 10_000
    rng = random.Random(seed + 404)
    hot_user = USER_BASES["hot-user"] + 1
    rows: list[dict[str, Any]] = []
    for index in range(count):
        rows.append(
            generated_event(
                "hot-user",
                seed,
                index,
                hot_user,
                "CONTENT_VIEW" if index % 5 else "SEARCH",
                BASE_TIME - timedelta(minutes=index % (30 * 24 * 60)),
                rng,
            )
        )
    return rows, {
        "hotUserId": hot_user,
        "hotUserEventCount": count,
        "reasonableBoundary": True,
        "notDoS": True,
    }


def concurrency_events(scale: str, seed: int) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    """Create deterministic input for concurrent correctness, not throughput.

    Every canonical event targets one user and one hourly bucket so an atomic
    metric upsert is exercised under contention. The first event is repeated
    enough times to make same-event-id races observable while the Manifest
    still expects one canonical row and one metric contribution.
    """
    unique_count = {"SMALL": 1_000, "MEDIUM": 10_000, "LARGE": 100_000}[scale]
    duplicate_count = max(20, unique_count // 100)
    rng = random.Random(seed + 606)
    user_id = USER_BASES["concurrency"] + 1
    rows: list[dict[str, Any]] = []
    for index in range(unique_count):
        row = generated_event(
            "concurrency",
            seed,
            index,
            user_id,
            "CONTENT_VIEW",
            BASE_TIME,
            rng,
        )
        row["properties"].update({"scenario": "concurrency-v1"})
        rows.append(row)

    duplicate = json.loads(stable_json(rows[0]))
    rows.extend(json.loads(stable_json(duplicate)) for _ in range(duplicate_count))
    same_event_id = duplicate["eventId"]
    rng.shuffle(rows)
    return rows, {
        "concurrencyUserId": user_id,
        "uniqueEventCount": unique_count,
        "duplicateInputCount": duplicate_count,
        "sameEventId": same_event_id,
        "expectedCanonicalMetricCount": unique_count,
        "requiresConcurrency": True,
    }


def campaign_events(seed: int) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    rng = random.Random(seed + 505)
    user_id = USER_BASES["campaign"] + 1
    rows: list[dict[str, Any]] = []
    for index in range(4):
        row = generated_event(
            "campaign",
            seed,
            index,
            user_id,
            "CONTENT_VIEW",
            BASE_TIME + timedelta(seconds=index),
            rng,
        )
        row["properties"].update({"scenario": "frequency-v1", "campaignFixture": "PF_TEST_FREQUENCY_V1"})
        rows.append(row)

    click_a = generated_event(
        "campaign",
        seed,
        4,
        user_id,
        "CLICK",
        BASE_TIME + timedelta(minutes=1),
        rng,
    )
    click_a["properties"].update({"taskId": 9201, "campaignId": 9201, "touch": "A"})
    click_b = generated_event(
        "campaign",
        seed,
        5,
        user_id,
        "CLICK",
        BASE_TIME + timedelta(minutes=2),
        rng,
    )
    click_b["properties"].update({"taskId": 9202, "campaignId": 9202, "touch": "B"})
    conversion = generated_event(
        "campaign",
        seed,
        6,
        user_id,
        "ORDER_PAID",
        BASE_TIME + timedelta(minutes=3),
        rng,
    )
    conversion["properties"].update({"orderId": "campaign-conversion-1", "price": 328.0})
    # Deliberately arrive conversion first to exercise the late-click path.
    rows.extend([conversion, click_b, click_a])
    return rows, {
        "baseTime": iso_time(BASE_TIME),
        "campaignFixtureName": "PF_TEST_FREQUENCY_V1",
        "frequencyCampaignId": 9202,
        "attributionCampaignId": 9203,
        "campaignUserId": user_id,
        "frequency": {
            "inputEventCount": 4,
            "expectedDeliveryTaskCount": 4,
            "expectedAllowedByFrequency": 2,
            "expectedCancelledByFrequency": 2,
            "expectedUserDailyFrequencyCount": 3,
        },
        "attribution": {
            "targetEventId": conversion["eventId"],
            "expectedModel": "CLICK_LAST_TOUCH",
            "expectedCampaignId": 9203,
            "expectedTaskId": 9203,
            "requiresClickEventMaterialization": True,
        },
        "downstreamStages": {
            "campaignExecution": "requires-scheduled-campaign-fixture-and-campaignSelectionJob",
            "performanceSummary": "requires-campaignReviewJob",
            "aiReview": "requires-campaignReviewJob-and-AI-enabled",
        },
        "sourceLimitation": (
            "No HTTP endpoint currently writes click_event; the SQL fixture documents the "
            "test-only setup needed for attribution validation."
        ),
    }


def metric_totals(rows: Iterable[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    totals: dict[str, dict[str, Any]] = defaultdict(
        lambda: {"eventCount": 0, "durationSum": 0, "amountSum": Decimal("0.00")}
    )
    for row in rows:
        event_type = str(row.get("eventType"))
        values = totals[event_type]
        values["eventCount"] += 1
        props = row.get("properties") or {}
        duration = props.get("duration", 0)
        if isinstance(duration, bool):
            duration = 0
        try:
            values["durationSum"] += int(duration)
        except (TypeError, ValueError):
            pass
        try:
            values["amountSum"] += Decimal(str(props.get("price", 0)))
        except (InvalidOperation, TypeError, ValueError):
            pass
    return {
        event_type: {
            "eventCount": values["eventCount"],
            "durationSum": values["durationSum"],
            "amountSum": format(values["amountSum"].quantize(Decimal("0.01")), "f"),
        }
        for event_type, values in sorted(totals.items())
    }


def canonical_rows(rows: Iterable[dict[str, Any]]) -> list[dict[str, Any]]:
    """Return the first occurrence of each eventId, matching DB canonical data."""
    seen: set[str] = set()
    result: list[dict[str, Any]] = []
    for row in rows:
        event_id_value = row.get("eventId")
        if not event_id_value or event_id_value in seen:
            continue
        seen.add(str(event_id_value))
        result.append(row)
    return result


def manifest_for(
    dataset_id: str,
    scenario: str,
    scale: str,
    seed: int,
    rows: list[dict[str, Any]],
    data_file: Path,
    details: dict[str, Any] | None = None,
) -> dict[str, Any]:
    unique_ids = list(dict.fromkeys(row["eventId"] for row in rows if "eventId" in row))
    event_type_counts = Counter(row.get("eventType") for row in rows if row.get("eventType"))
    user_ids = [int(row["userId"]) for row in rows if isinstance(row.get("userId"), int)]
    canonical = canonical_rows(row for row in rows if row.get("eventId"))
    canonical_metrics = metric_totals(canonical)
    manifest: dict[str, Any] = {
        "datasetId": dataset_id,
        "version": 1,
        "seed": seed,
        "scenario": scenario,
        "scale": scale,
        "dataFile": data_file.name,
        "reproducible": True,
        "sourceContract": {
            "endpoint": "POST /api/events",
            "topic": "pulseflow.raw.events",
            "eventTypes": EVENT_TYPES,
            "maxClockSkewMinutes": 5,
            "clockSkewComparison": "strictly greater than 5 whole minutes",
        },
        "events": len(rows),
        "uniqueEventIds": len(unique_ids),
        "duplicateInputEvents": len(rows) - len(unique_ids),
        "eventTypeCounts": dict(sorted(event_type_counts.items())),
        "metricTotalsByEventType": canonical_metrics,
        "userIdRange": {
            "min": min(user_ids) if user_ids else None,
            "max": max(user_ids) if user_ids else None,
        },
        "sampleEventIds": unique_ids[:5],
        "sampleUserIds": list(dict.fromkeys(user_ids))[:5],
        "expected": {
            "mysql": {
                "uniqueUserEventCount": len(unique_ids),
                "duplicateRows": 0,
                "metricTotalsByEventType": canonical_metrics,
                "canonicalSamples": [
                    {
                        "eventId": row.get("eventId"),
                        "userId": row.get("userId"),
                        "eventType": row.get("eventType"),
                        "targetId": row.get("targetId"),
                        "properties": row.get("properties") or {},
                    }
                    for row in canonical[:5]
                ],
                "compensationByStatus": {},
            },
            "redis": {
                "processedFlagTtlSecondsAtLeast": 1,
                "sampleProcessedFlagCount": min(5, len(unique_ids)),
            },
            "scheduledOutputs": {
                "dailyMetrics": "requires-dailyMetricJob",
                "windowMetrics": "requires-windowMetricJob",
                "userTags": "requires-tagRecalcJob",
            },
        },
    }
    if scenario == "invalid":
        status_counts = Counter(row.get("expectedStatus") for row in rows)
        manifest["expected"] = {
            "httpStatusCounts": {str(key): value for key, value in sorted(status_counts.items())},
            "cases": details.get("cases", []) if details else [],
        }
        manifest["duplicateInputEvents"] = 0
        manifest["userIdRange"] = {"min": None, "max": None}
        manifest["metricTotalsByEventType"] = {}
        manifest["expected"].pop("scheduledOutputs", None)
    elif scenario not in {"normal", "hot-user", "concurrency"}:
        manifest["expected"].pop("scheduledOutputs", None)
    if details:
        manifest["scenarioDetails"] = details
    return manifest


def write_dataset(output_dir: Path, manifest: dict[str, Any], rows: list[dict[str, Any]]) -> dict[str, Any]:
    output_dir.mkdir(parents=True, exist_ok=True)
    data_path = output_dir / manifest["dataFile"]
    manifest_path = output_dir / f"{manifest['datasetId']}.manifest.json"

    with data_path.open("w", encoding="utf-8", newline="\n") as handle:
        for row in rows:
            handle.write(stable_json(row))
            handle.write("\n")

    digest = hashlib.sha256()
    with data_path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    manifest["sha256"] = digest.hexdigest()
    manifest_path.write_text(stable_json(manifest) + "\n", encoding="utf-8")
    return manifest


def generate_one(output_dir: Path, scenario: str, scale: str, seed: int) -> dict[str, Any]:
    if scenario == "normal":
        rows = normal_events(scale, seed)
        details: dict[str, Any] = {
            "timeCoverageWindows": ["1h", "1d", "7d", "30d"],
            "timeBase": iso_time(BASE_TIME),
        }
    elif scenario == "duplicate":
        rows, details = duplicate_events(seed)
    elif scenario == "out-of-order":
        rows, details = out_of_order_events(seed)
    elif scenario == "late":
        rows, details = late_events(seed)
    elif scenario == "invalid":
        rows, details = invalid_payload_events(seed)
    elif scenario == "hot-user":
        rows, details = hot_user_events(scale, seed)
    elif scenario == "concurrency":
        rows, details = concurrency_events(scale, seed)
    elif scenario == "campaign":
        rows, details = campaign_events(seed)
    else:
        raise ValueError(f"Unsupported scenario: {scenario}")

    dataset_id = dataset_id_for(scenario, scale)
    data_file = Path(f"{dataset_id}.jsonl")
    manifest = manifest_for(dataset_id, scenario, scale, seed, rows, data_file, details)
    manifest["events"] = len(rows)
    manifest["users"] = len(set(row.get("userId") for row in rows if row.get("userId") is not None))
    return write_dataset(output_dir, manifest, rows)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scale", choices=sorted(SCALES), default="SMALL")
    parser.add_argument(
        "--scenario",
        choices=[
            "all", "normal", "duplicate", "out-of-order", "late", "invalid",
            "hot-user", "concurrency", "campaign",
        ],
        default="all",
    )
    parser.add_argument("--seed", type=int, default=20260827)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    scenarios = [
        "normal",
        "duplicate",
        "out-of-order",
        "late",
        "invalid",
        "hot-user",
        "concurrency",
        "campaign",
    ] if args.scenario == "all" else [args.scenario]
    for scenario in scenarios:
        manifest = generate_one(args.output_dir, scenario, args.scale, args.seed)
        print(
            f"generated {manifest['datasetId']}: events={manifest['events']} "
            f"unique={manifest['uniqueEventIds']} sha256={manifest['sha256']}"
        )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except BrokenPipeError:
        raise SystemExit(1)
