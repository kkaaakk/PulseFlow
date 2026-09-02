#!/usr/bin/env python3
"""Offline regression tests for the Functional state ownership boundary."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path


FUNCTIONAL_DIR = Path(__file__).resolve().parent
if str(FUNCTIONAL_DIR) not in sys.path:
    sys.path.insert(0, str(FUNCTIONAL_DIR))

import state  # noqa: E402


class FunctionalOwnershipTests(unittest.TestCase):
    def setUp(self) -> None:
        self.scope = state.load_scope()

    def test_generator_namespaces_and_reserved_user_ranges_are_owned(self) -> None:
        self.assertTrue(self.scope.owns_event_id("pf-normal-20260827-00000000"))
        self.assertTrue(self.scope.owns_event_id("pf-fixture-smoke-v1-0001"))
        self.assertTrue(self.scope.owns_user_id(1_000_999))
        self.assertTrue(self.scope.owns_user_id(9_000_002))
        self.assertFalse(self.scope.owns_event_id("business-order-1"))
        self.assertFalse(self.scope.owns_user_id(42))

    def test_redis_cleanup_never_selects_unowned_production_shaped_keys(self) -> None:
        task_ids = {9203}
        owned = [
            "event:processed:pf-normal-20260827-00000000",
            "user:rt:1000001",
            "user:daily:7000001:20260827",
            "freq:campaign:9202:7000001",
            "freq:reserved:9203",
        ]
        unowned = [
            "event:processed:business-order-1",
            "user:rt:1001",
            "freq:campaign:42:1000001",
            "freq:reserved:12",
        ]
        for key in owned:
            self.assertTrue(state.key_is_owned(key, self.scope, task_ids), key)
        for key in unowned:
            self.assertFalse(state.key_is_owned(key, self.scope, task_ids), key)

    def test_mysql_cleanup_is_namespace_scoped_and_not_destructive(self) -> None:
        sql = state.build_mysql_cleanup_sql(self.scope).upper()
        self.assertIn("EVENT_ID LIKE 'PF-%'", sql)
        self.assertIn("START TRANSACTION", sql)
        self.assertIn("COMMIT", sql)
        self.assertNotIn("FLUSHDB", sql)
        self.assertNotIn("DROP DATABASE", sql)
        self.assertNotIn("TRUNCATE", sql)

    def test_current_manifest_scope_does_not_expand_to_other_scenarios(self) -> None:
        manifest = {
            "scenario": "normal",
            "sampleEventIds": ["pf-normal-20260827-00000000"],
            "userIdRange": {"min": 1000000, "max": 1000999},
            "ownership": {
                "eventIdPrefix": "pf-normal-20260827-",
                "userIdRange": {"min": 1000000, "max": 1000999},
            },
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "normal.manifest.json"
            path.write_text(json.dumps(manifest), encoding="utf-8")
            current = state.load_scope([path], include_catalog=False)

        self.assertEqual(current.event_patterns, ("pf-normal-20260827-%",))
        self.assertTrue(current.owns_event_id("pf-normal-20260827-00000001"))
        self.assertFalse(current.owns_event_id("pf-campaign-20260827-00000001"))
        self.assertIsNone(current.campaign_pattern)


if __name__ == "__main__":
    unittest.main()
