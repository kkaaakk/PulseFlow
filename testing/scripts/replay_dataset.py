#!/usr/bin/env python3
"""Replay a PulseFlow JSONL dataset through POST /api/events.

The replay is sequential by design.  Arrival order is part of the test input,
so parallel workers would make duplicate and out-of-order scenarios harder to
reproduce.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parents[2]


def stable_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def assert_safe_base_url(base_url: str) -> None:
    parsed = urlparse(base_url)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise ValueError(f"BASE_URL must be an http(s) URL: {base_url}")
    host = parsed.hostname.lower()
    loopback = host in {"localhost", "127.0.0.1", "::1"}
    explicitly_allowed = (
        os.environ.get("PULSEFLOW_TEST_ALLOW_NONLOCAL", "false").lower() == "true"
        and os.environ.get("PULSEFLOW_TEST_ENV", "").lower() == "test"
    )
    if not loopback and not explicitly_allowed:
        raise ValueError(
            "Refusing non-loopback replay target. Use localhost, or explicitly set "
            "PULSEFLOW_TEST_ENV=test and PULSEFLOW_TEST_ALLOW_NONLOCAL=true."
        )


def percentile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = (len(ordered) - 1) * fraction
    lower = int(index)
    upper = min(lower + 1, len(ordered) - 1)
    if lower == upper:
        return round(ordered[lower], 3)
    weight = index - lower
    return round(ordered[lower] * (1 - weight) + ordered[upper] * weight, 3)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", required=True, type=Path)
    parser.add_argument("--base-url", default=os.environ.get("BASE_URL", "http://localhost:8080"))
    parser.add_argument("--report-dir", type=Path)
    parser.add_argument("--run-id")
    parser.add_argument("--timeout", type=float, default=10.0)
    parser.add_argument("--pacing-ms", type=int, default=1)
    parser.add_argument("--max-events", type=int, default=0, help="0 means no limit")
    parser.add_argument("--stop-on-error", action="store_true")
    parser.add_argument("--dry-run", action="store_true", help="Read/validate input without making HTTP calls")
    parser.add_argument("--rebase-event-time", action="store_true",
                        help="Preserve fixture-relative time offsets while sending eventTime near now")
    return parser.parse_args()


def load_manifest(dataset: Path) -> dict[str, Any] | None:
    manifest = dataset.with_name(dataset.stem + ".manifest.json")
    if not manifest.exists():
        # Generated files use <dataset-id>.jsonl and <dataset-id>.manifest.json;
        # this branch also supports a caller passing a fixture with a different
        # basename by looking beside it for the only matching manifest.
        candidates = sorted(dataset.parent.glob("*.manifest.json"))
        candidates = [candidate for candidate in candidates if candidate.stem.startswith(dataset.stem)]
        if len(candidates) == 1:
            manifest = candidates[0]
    if not manifest.exists():
        return None
    return json.loads(manifest.read_text(encoding="utf-8"))


def rebase_event_time(body: Any, manifest: dict[str, Any] | None) -> Any:
    """Preserve relative event time while making boundary fixtures runnable today."""
    if not isinstance(body, dict) or not isinstance(body.get("eventTime"), str) or not manifest:
        return body
    details = manifest.get("scenarioDetails") or {}
    base_text = details.get("baseTime") or details.get("timeBase")
    if not base_text:
        return body
    try:
        source_time = datetime.fromisoformat(body["eventTime"])
        base_time = datetime.fromisoformat(base_text)
    except ValueError:
        return body
    rebased = datetime.now().replace(microsecond=0) + (source_time - base_time)
    result = dict(body)
    result["eventTime"] = rebased.isoformat(
        timespec="milliseconds" if source_time.microsecond else "seconds"
    )
    return result


def request_body(record: dict[str, Any], manifest: dict[str, Any] | None,
                 rebase: bool) -> tuple[Any, int, str | None]:
    if "body" in record:
        body = rebase_event_time(record["body"], manifest) if rebase else record["body"]
        return body, int(record.get("expectedStatus", 200)), record.get("caseId")
    body = rebase_event_time(record, manifest) if rebase else record
    return body, 200, None


def send_json(base_url: str, body: Any, timeout: float) -> tuple[int | None, Any, str | None, float]:
    payload = body if isinstance(body, str) else stable_json(body)
    request = Request(
        base_url.rstrip("/") + "/api/events",
        data=payload.encode("utf-8"),
        headers={"Content-Type": "application/json", "Accept": "application/json"},
        method="POST",
    )
    started = time.perf_counter()
    try:
        with urlopen(request, timeout=timeout) as response:
            raw = response.read().decode("utf-8", errors="replace")
            status = int(response.status)
            return status, parse_response_body(raw), None, (time.perf_counter() - started) * 1000
    except HTTPError as error:
        raw = error.read().decode("utf-8", errors="replace")
        return int(error.code), parse_response_body(raw), str(error), (time.perf_counter() - started) * 1000
    except (URLError, TimeoutError, OSError) as error:
        return None, None, str(error), (time.perf_counter() - started) * 1000


def parse_response_body(raw: str) -> Any:
    if not raw:
        return None
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return {"raw": raw[:4_096], "parseError": True}


def compact_input(body: Any) -> tuple[Any, bool]:
    text = body if isinstance(body, str) else stable_json(body)
    if len(text) <= 16_384:
        return body, False
    return text[:16_384], True


def main() -> int:
    args = parse_args()
    dataset = args.dataset.resolve()
    if not dataset.exists() or not dataset.is_file():
        print(f"dataset not found: {dataset}", file=sys.stderr)
        return 2
    try:
        assert_safe_base_url(args.base_url)
    except ValueError as error:
        print(f"unsafe replay target: {error}", file=sys.stderr)
        return 2

    run_id = args.run_id or datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    report_dir = (args.report_dir or ROOT / "testing" / "reports" / run_id).resolve()
    report_dir.mkdir(parents=True, exist_ok=True)
    manifest = load_manifest(dataset)
    dataset_id = manifest.get("datasetId") if manifest else dataset.stem
    results_path = report_dir / "replay-results.jsonl"
    failures: list[dict[str, Any]] = []
    durations: list[float] = []
    status_counts: dict[str, int] = {}
    processed = 0
    expected_failures = 0
    started_at = datetime.now(timezone.utc).isoformat()

    with dataset.open("r", encoding="utf-8") as source, results_path.open(
        "w", encoding="utf-8", newline="\n"
    ) as output:
        for line_number, line in enumerate(source, start=1):
            if not line.strip():
                continue
            if args.max_events and processed >= args.max_events:
                break
            try:
                record = json.loads(line)
            except json.JSONDecodeError as error:
                failure = {
                    "datasetId": dataset_id,
                    "line": line_number,
                    "module": "dataset-reader",
                    "input": line.rstrip("\n")[:16_384],
                    "expected": {"validJsonLine": True},
                    "actual": {"validJsonLine": False},
                    "exception": str(error),
                    "stableReproduction": True,
                }
                failures.append(failure)
                output.write(stable_json({"line": line_number, "ok": False, "failure": failure}) + "\n")
                expected_failures += 1
                if args.stop_on_error:
                    break
                continue

            body, expected_status, case_id = request_body(record, manifest, args.rebase_event_time)
            if args.dry_run:
                status = None
                response_body = {"dryRun": True}
                error_text = None
                duration_ms = 0.0
            else:
                status, response_body, error_text, duration_ms = send_json(
                    args.base_url, body, args.timeout
                )
            durations.append(duration_ms)
            actual_code = response_body.get("code") if isinstance(response_body, dict) else None
            passed = args.dry_run or (status == expected_status and actual_code == expected_status)
            status_key = str(status) if status is not None else "NOT_RUN"
            status_counts[status_key] = status_counts.get(status_key, 0) + 1
            compacted_body, truncated = compact_input(body)
            result = {
                "datasetId": dataset_id,
                "line": line_number,
                "caseId": case_id,
                "ok": passed,
                "expectedStatus": expected_status,
                "actualStatus": status,
                "actualApiCode": actual_code,
                "durationMs": round(duration_ms, 3),
                "error": error_text,
                "input": compacted_body,
                "inputTruncated": truncated,
                "response": response_body,
            }
            output.write(stable_json(result) + "\n")
            if not passed:
                failure = {
                    "datasetId": dataset_id,
                    "line": line_number,
                    "caseId": case_id,
                    "module": "HTTP ingress /api/events",
                    "input": compacted_body,
                    "inputTruncated": truncated,
                    "expected": {"httpStatus": expected_status},
                    "actual": {
                        "httpStatus": status,
                        "apiCode": actual_code,
                        "response": response_body,
                    },
                    "exceptionOrLog": error_text,
                    "stableReproduction": True,
                    "replayCommand": f"python testing/scripts/replay_dataset.py --dataset {dataset}",
                }
                failures.append(failure)
                expected_failures += 1
                if args.stop_on_error:
                    processed += 1
                    break
            processed += 1
            if processed % 1_000 == 0:
                print(f"replayed {processed} events; failures={len(failures)}")
            if args.pacing_ms > 0:
                time.sleep(args.pacing_ms / 1000)

    summary = {
        "status": "NOT_RUN" if args.dry_run else ("FAIL" if failures else "PASS"),
        "datasetId": dataset_id,
        "dataset": str(dataset),
        "runId": run_id,
        "baseUrl": args.base_url,
        "startedAt": started_at,
        "finishedAt": datetime.now(timezone.utc).isoformat(),
        "preservesFileOrder": True,
        "rebasesEventTime": args.rebase_event_time,
        "eventsRead": processed,
        "failures": len(failures),
        "expectedFailures": expected_failures,
        "statusCounts": status_counts,
        "httpFailureRate": round(len(failures) / processed, 6) if processed else None,
        "latencyMs": {
            "p50": percentile(durations, 0.50),
            "p95": percentile(durations, 0.95),
            "p99": percentile(durations, 0.99),
            "max": round(max(durations), 3) if durations else None,
        },
        "manifest": manifest,
    }
    (report_dir / "replay-summary.json").write_text(stable_json(summary) + "\n", encoding="utf-8")
    (report_dir / "failures.json").write_text(stable_json(failures) + "\n", encoding="utf-8")
    print(
        f"replay {summary['status']}: dataset={dataset_id} events={processed} "
        f"failures={len(failures)} report={report_dir}"
    )
    if args.dry_run:
        return 0
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
