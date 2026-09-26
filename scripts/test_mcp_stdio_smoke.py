import io
import json
import os
import sys
import unittest
from contextlib import redirect_stdout
from unittest.mock import patch

from scripts import mcp_stdio_smoke


class CaptureStdin:
    def __init__(self):
        self.chunks = []
        self.closed = False

    def write(self, chunk):
        self.chunks.append(chunk)
        return len(chunk)

    def flush(self):
        pass

    def close(self):
        self.closed = True

    def json_messages(self):
        return [json.loads(chunk) for chunk in b"".join(self.chunks).splitlines()]


class FakeProcess:
    def __init__(self, responses):
        self.stdin = CaptureStdin()
        self.stdout = io.BytesIO(b"".join(
            json.dumps(response).encode("utf-8") + b"\n" for response in responses))
        self.stderr = io.BytesIO()

    def wait(self, timeout=None):
        return 0

    def terminate(self):
        raise AssertionError("the smoke process should exit after stdin closes")


def initialize_response():
    return {"jsonrpc": "2.0", "id": 1,
            "result": {"protocolVersion": "2024-11-05"}}


def tool_list_response():
    names = ["get_order", "list_user_orders", "get_logistics", "get_refund_eligibility",
             "list_policy_clauses", "submit_refund"]
    properties = {name: {"type": "string"} for name in
                  ("reason", "expectedCatalogFingerprint", "expectedPolicyCode")}
    properties["orderId"] = {"type": "integer"}
    return {"jsonrpc": "2.0", "id": 2,
            "result": {"tools": [
                {"name": name,
                 "inputSchema": ({
                     "properties": properties,
                     "required": ["orderId", "reason", "expectedCatalogFingerprint", "expectedPolicyCode"],
                     "additionalProperties": False,
                 } if name == "submit_refund" else {"properties": {}})}
                for name in names
            ]}}


def call_response():
    return {"jsonrpc": "2.0", "id": 2,
            "result": {"isError": False, "content": [{"type": "text", "text": "{}"}]}}


class McpStdioSmokeTest(unittest.TestCase):
    def run_smoke(self, args, responses):
        process = FakeProcess(responses)
        output = io.StringIO()
        with patch.object(sys, "argv", ["mcp_stdio_smoke.py", *args]), \
                patch.dict(os.environ, {
                    "SUPERMALL_TOKEN": "synthetic-smoke-token",
                    "SUPERMALL_BASE_URL": "http://127.0.0.1:8081",
                }), \
                patch.object(mcp_stdio_smoke.subprocess, "Popen", return_value=process), \
                redirect_stdout(output):
            mcp_stdio_smoke.main()
        return process, output.getvalue()

    def test_list_accepts_exact_reviewed_submit_schema(self):
        process, _ = self.run_smoke([], [initialize_response(), tool_list_response()])

        sent = process.stdin.json_messages()
        self.assertEqual("tools/list", sent[2]["method"])

    def test_submit_cli_sends_reviewed_pair(self):
        process, _ = self.run_smoke([
            "--tool", "submit_refund", "--order-id", "9001", "--reason", "changed my mind",
            "--expected-fingerprint", "catalog-v1", "--expected-policy-code", "SHIPPED_NOT_RECEIVED",
        ], [initialize_response(), call_response()])

        call = process.stdin.json_messages()[2]
        self.assertEqual("tools/call", call["method"])
        self.assertEqual({
            "orderId": 9001,
            "reason": "changed my mind",
            "expectedCatalogFingerprint": "catalog-v1",
            "expectedPolicyCode": "SHIPPED_NOT_RECEIVED",
        }, call["params"]["arguments"])


if __name__ == "__main__":
    unittest.main()
