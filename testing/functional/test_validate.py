#!/usr/bin/env python3
"""Focused unit tests for Functional Validator nullable MySQL values."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch


FUNCTIONAL_DIR = Path(__file__).resolve().parent
if str(FUNCTIONAL_DIR) not in sys.path:
    sys.path.insert(0, str(FUNCTIONAL_DIR))

import validate  # noqa: E402  pylint: disable=wrong-import-position


# 验证测试数据解析或所有权边界，避免空值误判及清理范围扩张。
class NullableIntTests(unittest.TestCase):
    # 验证 MySQL NULL 文本标记被解析为 None。
    def test_mysql_null_tokens_map_to_none(self) -> None:
        for value in (None, "", "NULL", "\\N"):
            with self.subTest(value=value):
                self.assertIsNone(validate.nullable_int(value))

    # 验证数值零不会被误认为 SQL NULL。
    def test_zero_is_not_treated_as_null(self) -> None:
        self.assertEqual(validate.nullable_int(0), 0)
        self.assertEqual(validate.nullable_int("0"), 0)

    # 验证合法数值保持整数类型与原值。
    def test_numeric_values_are_preserved_as_integers(self) -> None:
        self.assertEqual(validate.nullable_int(123), 123)
        self.assertEqual(validate.nullable_int("123"), 123)


# 验证测试数据解析或所有权边界，避免空值误判及清理范围扩张。
class MysqlRunnerTests(unittest.TestCase):
    # 验证 CLI 输出保留 NULL 标记，使可空整数解析正确。
    def test_run_mysql_keeps_cli_null_token_for_nullable_parser(self) -> None:
        args = SimpleNamespace(
            mysql_bin="mysql",
            mysql_host="127.0.0.1",
            mysql_port=13306,
            mysql_user="test",
            mysql_database="pulseflow_test",
            mysql_password="test",
        )
        result = SimpleNamespace(
            returncode=0,
            stdout="event-null\t1\tLOGIN\tNULL\t{}\n",
            stderr="",
        )

        with patch.object(validate, "command_exists", return_value=True), patch.object(
            validate.subprocess, "run", return_value=result
        ) as run:
            rows, error = validate.run_mysql(args, "SELECT target_id FROM user_event")

        self.assertIsNone(error)
        self.assertEqual(rows, [["event-null", "1", "LOGIN", "NULL", "{}"]])
        command = run.call_args.args[0]
        self.assertIn("--batch", command)
        self.assertIn("--raw", command)
        self.assertIn("--skip-column-names", command)


# 验证测试数据解析或所有权边界，避免空值误判及清理范围扩张。
class CanonicalSampleTests(unittest.TestCase):
    # 验证规范事件抽样查询能正确保留并解析 null 目标标识。
    def test_canonical_query_preserves_and_parses_mysql_null(self) -> None:
        manifest = {
            "expected": {
                "mysql": {
                    "canonicalSamples": [
                        {
                            "eventId": "event-null",
                            "userId": 1,
                            "eventType": "LOGIN",
                            "targetId": None,
                            "properties": {},
                        },
                        {
                            "eventId": "event-zero",
                            "userId": 1,
                            "eventType": "CLICK",
                            "targetId": 0,
                            "properties": {},
                        },
                    ]
                }
            }
        }
        mysql_rows = [
            ["event-null", "1", "LOGIN", "NULL", "{}"],
            ["event-zero", "1", "CLICK", "0", "{}"],
        ]
        calls: list[str] = []
        checks: list[dict[str, object]] = []

        # 模拟包含 NULL 与零目标标识的 MySQL 行，验证抽样解析区别。
        def fake_run_mysql(_args: object, query: str) -> tuple[list[list[str]], None]:
            calls.append(query)
            return mysql_rows, None

        with patch.object(validate, "run_mysql", side_effect=fake_run_mysql):
            validate.validate_canonical_samples(SimpleNamespace(), manifest, checks)

        self.assertEqual(len(calls), 1)
        self.assertNotIn("COALESCE(target_id", calls[0])
        self.assertIn("target_id", calls[0])
        self.assertEqual(checks[0]["status"], "PASS")
        self.assertEqual(checks[0]["actual"][0]["targetId"], None)  # type: ignore[index]
        self.assertEqual(checks[0]["actual"][1]["targetId"], 0)  # type: ignore[index]


if __name__ == "__main__":
    unittest.main()
