#!/usr/bin/env python3
"""Offline contract/coverage check for the AI evaluation dataset."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
REQUIRED_CATEGORIES = {
    "normal-operations",
    "vague-expression",
    "unknown-field",
    "illegal-business-field",
    "pii-phone",
    "prompt-injection",
    "overlong-input",
    "empty-input",
    "extreme-number",
    "contradictory-conditions",
    "chinese-colloquial",
    "typo",
    "pii-email-address",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=ROOT / "testing/data/ai/campaign-intent-eval.jsonl")
    parser.add_argument("--output", type=Path, default=ROOT / "testing/reports/ai-dataset-check.json")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    errors: list[str] = []
    records: list[dict] = []
    seen: set[str] = set()
    try:
        lines = args.dataset.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        print(f"AI dataset read failed: {error}", file=sys.stderr)
        return 2
    manifest_path = args.dataset.with_name(args.dataset.stem + ".manifest.json")
    if not manifest_path.exists():
        errors.append(f"manifest not found: {manifest_path}")
    else:
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            actual_sha256 = hashlib.sha256(args.dataset.read_bytes()).hexdigest()
            if manifest.get("sha256") != actual_sha256:
                errors.append(
                    f"manifest checksum mismatch: expected {manifest.get('sha256')}, actual {actual_sha256}"
                )
            if int(manifest.get("cases", -1)) != len([line for line in lines if line.strip()]):
                errors.append("manifest case count does not match JSONL")
        except (OSError, ValueError, json.JSONDecodeError) as error:
            errors.append(f"invalid AI manifest: {error}")
    for line_number, line in enumerate(lines, start=1):
        if not line.strip():
            continue
        try:
            record = json.loads(line)
        except json.JSONDecodeError as error:
            errors.append(f"line {line_number}: invalid JSON: {error}")
            continue
        case_id = record.get("id")
        category = record.get("category")
        if not case_id or case_id in seen:
            errors.append(f"line {line_number}: missing or duplicate id")
        if case_id:
            seen.add(case_id)
        if category not in REQUIRED_CATEGORIES:
            errors.append(f"line {line_number}: unsupported category {category!r}")
        has_input = isinstance(record.get("input"), str)
        has_repeat = isinstance(record.get("inputPrefix"), str) and isinstance(record.get("inputRepeat"), int)
        if not has_input and not has_repeat:
            errors.append(f"line {line_number}: input or inputPrefix/inputRepeat is required")
        if has_repeat and record["inputRepeat"] <= 0:
            errors.append(f"line {line_number}: inputRepeat must be positive")
        if category == "overlong-input" and has_repeat:
            materialized_length = len(record["inputPrefix"]) * record["inputRepeat"]
            if materialized_length <= 4096:
                errors.append(f"line {line_number}: overlong-input must materialize to >4096 characters")
        expected = record.get("expected")
        if not isinstance(expected, dict) or not expected.get("pipeline") or not expected.get("sensitiveData"):
            errors.append(f"line {line_number}: expected.pipeline and expected.sensitiveData are required")
        records.append(record)
    categories = {record.get("category") for record in records}
    missing = sorted(REQUIRED_CATEGORIES - categories)
    errors.extend(f"missing required category: {category}" for category in missing)
    result = {
        "status": "PASS" if not errors else "FAIL",
        "checkedAt": datetime.now(timezone.utc).isoformat(),
        "dataset": str(args.dataset.resolve()),
        "cases": len(records),
        "categories": sorted(categories),
        "requiredCategories": sorted(REQUIRED_CATEGORIES),
        "errors": errors,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"AI dataset check {result['status']}: cases={len(records)} output={args.output}")
    return 0 if not errors else 1


if __name__ == "__main__":
    raise SystemExit(main())
