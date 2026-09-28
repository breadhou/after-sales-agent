"""Drive one MCP stdio request while keeping stdin open for its response.

SUPERMALL_TOKEN and SUPERMALL_BASE_URL are passed explicitly to the Java child.
No credential is written to disk or printed by this script.
"""

import argparse
import json
import os
from pathlib import Path
from queue import Empty, Queue
import subprocess
import sys
from threading import Thread


ROOT = Path(__file__).resolve().parents[1]
CHILD_OS_KEYS = ("PATH", "PATHEXT", "SystemRoot", "WINDIR", "ComSpec",
                 "TEMP", "TMP", "JAVA_HOME")


def child_environment(token, base_url):
    environment = {key: os.environ[key] for key in CHILD_OS_KEYS if key in os.environ}
    environment["SUPERMALL_TOKEN"] = token
    environment["SUPERMALL_BASE_URL"] = base_url
    return environment


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "mcp-server/target/mcp-server.jar")
    parser.add_argument("--tool", help="MCP tool name; omit to request tools/list")
    parser.add_argument("--order-id", type=int)
    parser.add_argument("--product-id", type=int)
    parser.add_argument("--page-num", type=int)
    parser.add_argument("--page-size", type=int)
    parser.add_argument("--reason")
    parser.add_argument("--expected-fingerprint")
    parser.add_argument("--expected-policy-code")
    parser.add_argument("--expect-error", action="store_true")
    parser.add_argument("--expect-code", type=int)
    return parser.parse_args()


def main():
    args = parse_args()
    token = os.environ.get("SUPERMALL_TOKEN")
    if not token or not os.environ.get("SUPERMALL_BASE_URL"):
        raise RuntimeError("SUPERMALL_TOKEN and SUPERMALL_BASE_URL are required")

    arguments = {}
    if args.order_id is not None:
        arguments["orderId"] = args.order_id
    if args.product_id is not None:
        arguments["productId"] = args.product_id
    if args.page_num is not None:
        arguments["pageNum"] = args.page_num
    if args.page_size is not None:
        arguments["pageSize"] = args.page_size
    if args.reason is not None:
        arguments["reason"] = args.reason
    if args.expected_fingerprint is not None:
        arguments["expectedCatalogFingerprint"] = args.expected_fingerprint
    if args.expected_policy_code is not None:
        arguments["expectedPolicyCode"] = args.expected_policy_code
    params = {"name": args.tool, "arguments": arguments} if args.tool else None
    request = {"jsonrpc": "2.0", "id": 2,
               "method": "tools/call" if args.tool else "tools/list"}
    if params is not None:
        request["params"] = params

    process = subprocess.Popen(
        [os.environ.get("JAVA_BIN", "java"), "-jar", str(args.jar)],
        cwd=ROOT, env=child_environment(token, os.environ["SUPERMALL_BASE_URL"]), stdin=subprocess.PIPE,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    )
    lines = Queue()
    errors = []
    stdout_bytes = []

    def read_stdout():
        for line in process.stdout:
            stdout_bytes.append(line)
            try:
                lines.put(line.decode("utf-8"))
            except UnicodeDecodeError as error:
                lines.put(error)
        lines.put(None)

    def read_stderr():
        for line in process.stderr:
            errors.append(line)

    stdout_thread = Thread(target=read_stdout, daemon=True)
    stderr_thread = Thread(target=read_stderr, daemon=True)
    stdout_thread.start()
    stderr_thread.start()

    def send(message):
        process.stdin.write((json.dumps(message, ensure_ascii=False) + "\n").encode("utf-8"))
        process.stdin.flush()

    def receive(request_id):
        while True:
            try:
                line = lines.get(timeout=30)
            except Empty as error:
                raise RuntimeError(f"timeout waiting for response {request_id}") from error
            if line is None:
                raise RuntimeError(f"server closed before response {request_id}")
            if isinstance(line, UnicodeDecodeError):
                raise RuntimeError("MCP stdout is not UTF-8") from line
            try:
                response = json.loads(line)
            except json.JSONDecodeError as error:
                raise RuntimeError("non-protocol text on MCP stdout") from error
            if response.get("id") == request_id:
                return response

    try:
        send({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
            "protocolVersion": "2024-11-05", "capabilities": {},
            "clientInfo": {"name": "plan-b-task6-smoke", "version": "1"}}})
        initialized = receive(1)
        if "error" in initialized:
            raise RuntimeError("MCP initialize failed")
        send({"jsonrpc": "2.0", "method": "notifications/initialized"})
        send(request)
        response = receive(2)  # stdin remains open through the requested response
        if "error" in response:
            raise RuntimeError("MCP returned a JSON-RPC error")
        result = response.get("result", {})
        if args.tool:
            if result.get("isError", False) != args.expect_error:
                raise RuntimeError("unexpected MCP tool error flag")
            if args.expect_code is not None:
                content = result.get("content", [])
                if not content or json.loads(content[0]["text"])["code"] != args.expect_code:
                    raise RuntimeError("unexpected business error code")
        else:
            tools = result.get("tools", [])
            expected_names = {"get_order", "list_user_orders", "get_logistics",
                              "get_refund_eligibility", "list_policy_clauses", "submit_refund",
                              "list_on_shelf_products", "get_product_detail"}
            if {tool["name"] for tool in tools} != expected_names or len(tools) != len(expected_names):
                raise RuntimeError("unexpected MCP tool names or count")
            submit = next(tool for tool in tools if tool["name"] == "submit_refund")
            schema = submit["inputSchema"]
            reviewed_fields = {
                    "orderId", "reason", "expectedCatalogFingerprint", "expectedPolicyCode"}
            if set(schema["properties"]) != reviewed_fields \
                    or set(schema.get("required", [])) != reviewed_fields \
                    or schema.get("additionalProperties") is not False:
                raise RuntimeError("submit_refund schema widened")
            read_fields = {"list_on_shelf_products": {"pageNum", "pageSize"},
                           "get_product_detail": {"productId"}}
            for name, expected_fields in read_fields.items():
                read_schema = next(tool for tool in tools if tool["name"] == name)["inputSchema"]
                if set(read_schema["properties"]) != expected_fields \
                        or set(read_schema.get("required", [])) != expected_fields \
                        or read_schema.get("additionalProperties") is not False:
                    raise RuntimeError(f"{name} schema widened")
        if "\ufffd" in json.dumps(response, ensure_ascii=False):
            raise RuntimeError("replacement character in MCP response")
        # ASCII escaping survives Windows shell pipelines with a mismatched code page.
        output = json.dumps(response, ensure_ascii=True, separators=(",", ":"))
    finally:
        if process.stdin and not process.stdin.closed:
            process.stdin.close()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.terminate()
            process.wait(timeout=5)
        stdout_thread.join(timeout=2)
        stderr_thread.join(timeout=2)
        while True:
            try:
                extra = lines.get(timeout=2)
            except Empty as error:
                raise RuntimeError("MCP stdout did not close") from error
            if extra is None:
                break
            if isinstance(extra, UnicodeDecodeError):
                raise RuntimeError("MCP stdout is not UTF-8") from extra
            try:
                json.loads(extra)
            except json.JSONDecodeError as error:
                raise RuntimeError("non-protocol text on MCP stdout") from error
        if token.encode("utf-8") in b"".join(errors):
            raise RuntimeError("credential found on MCP stderr")
        if token.encode("utf-8") in b"".join(stdout_bytes):
            raise RuntimeError("credential found on MCP stdout")
    print(output)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, KeyError, ValueError) as error:
        print(f"smoke failed: {error}", file=sys.stderr)
        sys.exit(1)
