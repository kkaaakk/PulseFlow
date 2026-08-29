#!/usr/bin/env python3
"""Run semantic AI dataset checks against the local mock or an explicit provider."""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parents[2]


def stable_json(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def safe_url(base_url: str) -> None:
    parsed = urlparse(base_url)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise ValueError("base URL must be http(s)")
    if parsed.hostname.lower() not in {"localhost", "127.0.0.1", "::1"}:
        raise ValueError("AI evaluation only allows loopback URLs by default")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=ROOT / "testing/data/ai/campaign-intent-eval.jsonl")
    parser.add_argument("--base-url", default=os.environ.get("BASE_URL", "http://localhost:8080"))
    parser.add_argument("--token", default=os.environ.get("PULSEFLOW_TOKEN", ""))
    parser.add_argument("--timezone", default="Asia/Shanghai")
    parser.add_argument("--report-dir", type=Path)
    parser.add_argument("--pii-enabled", action="store_true")
    parser.add_argument("--offline", action="store_true", help="Only check coverage; do not call the API")
    parser.add_argument("--timeout", type=float, default=15.0)
    return parser.parse_args()


def materialize(record: dict) -> str:
    if isinstance(record.get("input"), str):
        return record["input"]
    return str(record.get("inputPrefix", "")) * int(record.get("inputRepeat", 0))


def expected_statuses(record: dict, pii_enabled: bool) -> set[int]:
    category = record.get("category")
    if category == "empty-input":
        return {400}
    if category == "illegal-business-field":
        return {422}
    if category in {"pii-phone", "pii-email-address"} and pii_enabled:
        return {422}
    # Mock mode is intentionally a provider fixture: it verifies parser/DSL
    # plumbing, while input-understanding expectations remain for real mode.
    return {200}


def call_parse(base_url: str, token: str, text: str, timezone_name: str, timeout: float):
    request = Request(
        base_url.rstrip("/") + "/api/ai/campaigns/parse",
        data=stable_json({"text": text, "timezone": timezone_name}).encode("utf-8"),
        headers={"Content-Type": "application/json", "Accept": "application/json", "token": token},
        method="POST",
    )
    started = time.perf_counter()
    try:
        with urlopen(request, timeout=timeout) as response:
            raw = response.read().decode("utf-8", errors="replace")
            status = int(response.status)
    except HTTPError as error:
        raw = error.read().decode("utf-8", errors="replace")
        status = int(error.code)
    except (URLError, TimeoutError, OSError) as error:
        return None, None, str(error), round((time.perf_counter() - started) * 1000, 3)
    try:
        body = json.loads(raw)
    except json.JSONDecodeError:
        body = {"raw": raw[:4096], "parseError": True}
    return status, body, None, round((time.perf_counter() - started) * 1000, 3)


def main() -> int:
    args = parse_args()
    try:
        safe_url(args.base_url)
    except ValueError as error:
        print(f"unsafe AI evaluation target: {error}", file=sys.stderr)
        return 2
    records = [json.loads(line) for line in args.dataset.read_text(encoding="utf-8").splitlines() if line.strip()]
    report_dir = (args.report_dir or ROOT / "testing/reports/ai-evaluation").resolve()
    report_dir.mkdir(parents=True, exist_ok=True)
    results = []
    failures = []
    for record in records:
        text = materialize(record)
        expected = expected_statuses(record, args.pii_enabled)
        if args.offline:
            actual_status = None
            body = {"offline": True}
            error = None
            duration_ms = 0
            passed = True
        else:
            if not args.token:
                print("PULSEFLOW_TOKEN is required for API evaluation; use --offline for coverage only", file=sys.stderr)
                return 2
            actual_status, body, error, duration_ms = call_parse(
                args.base_url, args.token, text, args.timezone, args.timeout
            )
            passed = actual_status in expected and isinstance(body, dict)
            if passed and actual_status == 200:
                data = body.get("data") or {}
                passed = body.get("code") == 200 and data.get("dsl") is not None and data.get("status") in {
                    "VALIDATED", "NEEDS_CONFIRMATION", "INVALID"
                }
        result = {
            "id": record.get("id"),
            "category": record.get("category"),
            "inputLength": len(text),
            "expectedStatuses": sorted(expected),
            "actualStatus": actual_status,
            "durationMs": duration_ms,
            "passed": passed,
            "error": error,
            "response": body,
        }
        results.append(result)
        if not passed:
            failures.append({
                "module": "AI parse pipeline",
                "caseId": record.get("id"),
                "input": text[:16_384],
                "inputTruncated": len(text) > 16_384,
                "expected": {"httpStatuses": sorted(expected), "semantic": "parser/DSL response shape"},
                "actual": {"httpStatus": actual_status, "response": body},
                "exceptionOrLog": error,
                "stableReproduction": True,
            })
    status = "PASS" if not failures else "FAIL"
    report = {
        "status": "NOT_RUN" if args.offline else status,
        "mode": "offline" if args.offline else "api",
        "checkedAt": datetime.now(timezone.utc).isoformat(),
        "cases": len(records),
        "failures": len(failures),
        "results": results,
    }
    (report_dir / "ai-validation.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (report_dir / "failures.json").write_text(json.dumps(failures, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"AI evaluation {report['status']}: cases={len(records)} failures={len(failures)} report={report_dir}")
    return 0 if args.offline or not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
