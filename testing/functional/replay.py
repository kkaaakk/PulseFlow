#!/usr/bin/env python3
"""Replay a PulseFlow JSONL dataset through ``POST /api/events``.

Replay is the functional validation runner. It keeps deterministic input and
per-record evidence, while ``--concurrency`` adds a bounded concurrency mode
for race/idempotency scenarios. It is deliberately not a load generator:
there is no throughput target or latency threshold here.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import uuid
from concurrent.futures import FIRST_COMPLETED, Future, ThreadPoolExecutor, wait
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterator
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
    parser.add_argument(
        "--concurrency", type=int, default=1,
        help="Bounded functional replay workers; 1 preserves strict arrival order",
    )
    parser.add_argument("--max-events", type=int, default=0, help="0 means no limit")
    parser.add_argument("--stop-on-error", action="store_true")
    parser.add_argument("--dry-run", action="store_true", help="Read/validate input without making HTTP calls")
    parser.add_argument(
        "--rebase-event-time", action="store_true",
        help="Preserve fixture-relative time offsets while sending eventTime near now",
    )
    return parser.parse_args()


def load_manifest(dataset: Path) -> dict[str, Any] | None:
    manifest = dataset.with_name(dataset.stem + ".manifest.json")
    if not manifest.exists():
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


def response_passed(expected_status: int, status: int | None, response_body: Any) -> bool:
    """Apply the shared ingress contract used by functional and performance tests."""
    if status != expected_status or not isinstance(response_body, dict):
        return False
    if response_body.get("code") != expected_status:
        return False
    if expected_status == 200:
        data = response_body.get("data")
        return isinstance(data, dict) and data.get("accepted") is True
    return True


def parse_record(line_number: int, line: str, manifest: dict[str, Any] | None,
                 rebase: bool, dataset_id: str) -> tuple[dict[str, Any] | None, dict[str, Any] | None]:
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
        return None, failure

    if not isinstance(record, dict):
        failure = {
            "datasetId": dataset_id,
            "line": line_number,
            "module": "dataset-reader",
            "input": record,
            "expected": {"jsonObject": True},
            "actual": {"jsonObject": False},
            "stableReproduction": True,
        }
        return None, failure

    body, expected_status, case_id = request_body(record, manifest, rebase)
    return {
        "datasetId": dataset_id,
        "line": line_number,
        "caseId": case_id,
        "body": body,
        "expectedStatus": expected_status,
    }, None


def execute_record(prepared: dict[str, Any], args: argparse.Namespace,
                   dataset: Path) -> tuple[dict[str, Any], dict[str, Any] | None]:
    body = prepared["body"]
    expected_status = prepared["expectedStatus"]
    if args.dry_run:
        status = None
        response_body = {"dryRun": True}
        error_text = None
        duration_ms = 0.0
    else:
        status, response_body, error_text, duration_ms = send_json(
            args.base_url, body, args.timeout
        )

    actual_code = response_body.get("code") if isinstance(response_body, dict) else None
    accepted = (
        response_body.get("data", {}).get("accepted")
        if isinstance(response_body, dict) and isinstance(response_body.get("data"), dict)
        else None
    )
    passed = args.dry_run or response_passed(expected_status, status, response_body)
    compacted_body, truncated = compact_input(body)
    result = {
        "datasetId": prepared["datasetId"],
        "line": prepared["line"],
        "caseId": prepared["caseId"],
        "ok": passed,
        "expectedStatus": expected_status,
        "expectedAccepted": expected_status == 200,
        "actualStatus": status,
        "actualApiCode": actual_code,
        "actualAccepted": accepted,
        "durationMs": round(duration_ms, 3),
        "error": error_text,
        "input": compacted_body,
        "inputTruncated": truncated,
        "response": response_body,
    }
    if passed:
        failure = None
    else:
        failure = {
            "datasetId": prepared["datasetId"],
            "line": prepared["line"],
            "caseId": prepared["caseId"],
            "module": "HTTP ingress /api/events",
            "input": compacted_body,
            "inputTruncated": truncated,
            "expected": {"httpStatus": expected_status, "accepted": expected_status == 200},
            "actual": {
                "httpStatus": status,
                "apiCode": actual_code,
                "accepted": accepted,
                "response": response_body,
            },
            "exceptionOrLog": error_text,
            "stableReproduction": True,
            "replayCommand": f"python testing/functional/replay.py --dataset {dataset}",
        }
    if args.pacing_ms > 0:
        time.sleep(args.pacing_ms / 1000)
    return result, failure


def iter_lines(dataset: Path) -> Iterator[tuple[int, str]]:
    with dataset.open("r", encoding="utf-8") as source:
        for line_number, line in enumerate(source, start=1):
            if line.strip():
                yield line_number, line


def main() -> int:
    args = parse_args()
    if args.concurrency < 1:
        print("--concurrency must be at least 1", file=sys.stderr)
        return 2
    if args.pacing_ms < 0:
        print("--pacing-ms cannot be negative", file=sys.stderr)
        return 2

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
    lines_read = 0
    started_at = datetime.now(timezone.utc).isoformat()

    def record_result(output: Any, result: dict[str, Any], failure: dict[str, Any] | None) -> None:
        nonlocal processed
        output.write(stable_json(result) + "\n")
        processed += 1
        durations.append(float(result["durationMs"]))
        status_key = str(result["actualStatus"]) if result["actualStatus"] is not None else "NOT_RUN"
        status_counts[status_key] = status_counts.get(status_key, 0) + 1
        if failure:
            failures.append(failure)
        if processed % 1_000 == 0:
            print(f"replayed {processed} events; failures={len(failures)}")

    stop_requested = False
    with results_path.open("w", encoding="utf-8", newline="") as output:
        line_iterator = iter_lines(dataset)
        if args.concurrency == 1 or args.dry_run:
            for line_number, line in line_iterator:
                if args.max_events and processed >= args.max_events:
                    break
                lines_read += 1
                prepared, parse_failure = parse_record(line_number, line, manifest, args.rebase_event_time, dataset_id)
                if parse_failure:
                    failures.append(parse_failure)
                    output.write(stable_json({"line": line_number, "ok": False, "failure": parse_failure}) + "\n")
                    if args.stop_on_error:
                        break
                    continue
                result, failure = execute_record(prepared, args, dataset)
                record_result(output, result, failure)
                if failure and args.stop_on_error:
                    break
        else:
            pending: dict[Future[tuple[dict[str, Any], dict[str, Any] | None]], dict[str, Any]] = {}
            max_in_flight = max(args.concurrency * 2, args.concurrency)
            with ThreadPoolExecutor(max_workers=args.concurrency, thread_name_prefix="pulseflow-replay") as executor:
                exhausted = False
                while pending or not exhausted:
                    while not exhausted and not stop_requested and len(pending) < max_in_flight:
                        try:
                            line_number, line = next(line_iterator)
                        except StopIteration:
                            exhausted = True
                            break
                        if args.max_events and lines_read >= args.max_events:
                            exhausted = True
                            break
                        lines_read += 1
                        prepared, parse_failure = parse_record(
                            line_number, line, manifest, args.rebase_event_time, dataset_id
                        )
                        if parse_failure:
                            failures.append(parse_failure)
                            output.write(stable_json({"line": line_number, "ok": False, "failure": parse_failure}) + "\n")
                            if args.stop_on_error:
                                stop_requested = True
                            continue
                        future = executor.submit(execute_record, prepared, args, dataset)
                        pending[future] = prepared

                    if not pending:
                        continue
                    done, _ = wait(tuple(pending), return_when=FIRST_COMPLETED)
                    for future in done:
                        pending.pop(future, None)
                        result, failure = future.result()
                        record_result(output, result, failure)
                        if failure and args.stop_on_error:
                            stop_requested = True

    failures.sort(key=lambda item: (item.get("line", 0), item.get("caseId") or ""))
    effective_concurrency = 1 if args.dry_run else args.concurrency
    summary = {
        "status": "NOT_RUN" if args.dry_run else ("FAIL" if failures else "PASS"),
        "datasetId": dataset_id,
        "dataset": str(dataset),
        "runId": run_id,
        "baseUrl": args.base_url,
        "startedAt": started_at,
        "finishedAt": datetime.now(timezone.utc).isoformat(),
        "requestedConcurrency": args.concurrency,
        "concurrency": effective_concurrency,
        "preservesFileOrder": effective_concurrency == 1,
        "rebasesEventTime": args.rebase_event_time,
        "linesRead": lines_read,
        "eventsRead": processed,
        "failures": len(failures),
        "statusCounts": status_counts,
        "httpFailureRate": round(len(failures) / processed, 6) if processed else None,
        "latencyMs": {
            "p50": percentile(durations, 0.50),
            "p95": percentile(durations, 0.95),
            "p99": percentile(durations, 0.99),
            "max": round(max(durations), 3) if durations else None,
            "note": "Diagnostic request timings only; Replay has no performance threshold.",
        },
        "manifest": manifest,
    }
    (report_dir / "replay-summary.json").write_text(stable_json(summary) + "\n", encoding="utf-8")
    (report_dir / "replay-failures.json").write_text(stable_json(failures) + "\n", encoding="utf-8")
    (report_dir / "failures.json").write_text(stable_json(failures) + "\n", encoding="utf-8")
    print(
        f"replay {summary['status']}: dataset={dataset_id} events={processed} "
        f"concurrency={args.concurrency} failures={len(failures)} report={report_dir}"
    )
    if args.dry_run:
        return 0
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
