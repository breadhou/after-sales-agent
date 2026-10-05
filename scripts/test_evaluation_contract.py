import copy
import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from scripts import evaluation_contract as contract


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
SHARED_CASES = REPOSITORY_ROOT / "eval" / "fixtures" / "case-contract.json"


def _apply_patches(value, patches):
    result = copy.deepcopy(value)
    for patch in patches:
        path = patch["path"]
        parent = result
        for component in path[:-1]:
            parent = parent[int(component)] if isinstance(parent, list) else parent[component]
        key = path[-1]
        if patch["op"] == "set":
            if isinstance(parent, list):
                parent[int(key)] = copy.deepcopy(patch["value"])
            else:
                parent[key] = copy.deepcopy(patch["value"])
        elif patch["op"] == "add":
            if isinstance(parent, list):
                parent.insert(int(key), copy.deepcopy(patch["value"]))
            else:
                parent[key] = copy.deepcopy(patch["value"])
        elif patch["op"] == "remove":
            if isinstance(parent, list):
                del parent[int(key)]
            else:
                del parent[key]
        else:
            raise AssertionError(f"unsupported shared fixture patch: {patch['op']}")
    return result


class EvaluationContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.shared = json.loads(SHARED_CASES.read_text(encoding="utf-8-sig"))

    def test_shared_case_fixtures_have_expected_accept_reject_decisions(self):
        for fixture in self.shared["cases"]:
            template = self.shared["templates"][fixture["template"]]
            case = _apply_patches(template, fixture.get("patches", []))
            with self.subTest(fixture=fixture["id"]):
                if fixture["accepted"]:
                    self.assertEqual(case, contract.validate_case(case))
                else:
                    with self.assertRaises(ValueError):
                        contract.validate_case(case)

    def test_fixed_time_probe_uses_the_closed_backend_boundary(self):
        case = json.loads((REPOSITORY_ROOT / 'eval/scenarios/v2/boundary.jsonl').read_text(encoding='utf-8').splitlines()[-1])
        case['control']['probe'] = 'POLICY_WINDOW_FIXED_TIME'
        self.assertEqual('POLICY_WINDOW_FIXED_TIME', contract.validate_case(case)['control']['probe'])
        case['control']['probe'] = 'POLICY_WINDOW_FIXED_TIME#arbitrary'
        with self.assertRaises(ValueError):
            contract.validate_case(case)

    def test_mcp_contract_direct_call_requires_none_and_real_backend_components(self):
        direct = copy.deepcopy(self.shared["templates"]["mcpContract"])
        self.assertEqual(direct, contract.validate_case(direct))

        wrong_point = copy.deepcopy(direct)
        wrong_point["control"]["point"] = "MCP_BEFORE_REQUEST"
        with self.assertRaises(ValueError):
            contract.validate_case(wrong_point)

        substituted_model = copy.deepcopy(direct)
        substituted_model["control"]["components"]["DIALOGUE"] = "SUBSTITUTED"
        with self.assertRaises(ValueError):
            contract.validate_case(substituted_model)

    def test_decimal_money_strings_preserve_scale_and_compare_numerically(self):
        template = self.shared["templates"]["live"]
        case = _apply_patches(template, [{
            "op": "set",
            "path": ["fixture", "orders", "order-a", "expectedPaidAmount"],
            "value": "39.8",
        }])
        self.assertEqual(case, contract.validate_case(case))

    def test_binding_is_exact_and_never_evaluated(self):
        self.assertEqual(
            "订单 9007199254740993",
            contract.render_input("订单 {{order-a}}", {"order-a": "9007199254740993"}),
        )
        with self.assertRaises(ValueError):
            contract.render_input("{{__import__('os')}}", {})
        with self.assertRaises(ValueError):
            contract.render_input("{{order-b}}", {"order-a": "9007199254740993"})
        with self.assertRaises(ValueError):
            contract.render_input("订单 {{order-a", {"order-a": "42"})

    def test_suite_loads_exact_pilot_and_full_quotas(self):
        pilot = _build_suite_cases(self.shared, "pilot")
        full = _build_suite_cases(self.shared, "full")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            pilot_manifest = _write_suite(root / "pilot", "pilot", pilot)
            full_manifest = _write_suite(root / "full", "full", full)
            self.assertEqual(32, len(contract.load_suite(pilot_manifest)))
            self.assertEqual(240, len(contract.load_suite(full_manifest)))

            malformed_repeats = json.loads(full_manifest.read_text(encoding="utf-8"))
            malformed_repeats["repeatIds"] = [case["caseId"] for case in full if case["category"] == "NORMAL"][:12]
            malformed_path = full_manifest.parent / "wrong-repeat-categories.json"
            malformed_path.write_text(json.dumps(malformed_repeats), encoding="utf-8")
            with self.assertRaises(ValueError):
                contract.load_suite(malformed_path)

    def test_fix5_explicit_v2_versions_keep_exact_phase_quotas(self):
        with tempfile.TemporaryDirectory() as temporary:
            for phase, count in (("pilot", 32), ("full", 240)):
                path = _write_suite(Path(temporary) / phase, phase, _build_suite_cases(self.shared, phase))
                manifest = json.loads(path.read_text())
                manifest["suiteVersion"] = "v2-" + phase
                path.write_text(json.dumps(manifest), encoding="utf-8")
                try:
                    loaded = contract.load_suite(path)
                except ValueError as failure:
                    self.fail('Explicit v2 phase should load: ' + str(failure))
                self.assertEqual(count, len(loaded))
                for version in ("v3-" + phase, "v2-full" if phase == "pilot" else "v2-pilot"):
                    with self.subTest(version=version):
                        bad = {**manifest, "suiteVersion": version}
                        with self.assertRaises(ValueError): contract.validate_wire("Manifest", bad)
                manifest["caseIds"] = manifest["caseIds"][:-1]
                path.write_text(json.dumps(manifest), encoding="utf-8")
                with self.assertRaises(ValueError): contract.load_suite(path)

    def test_suite_rejects_duplicate_ids_and_invalid_file_references(self):
        cases = _build_suite_cases(self.shared, "pilot")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest_path = _write_suite(root, "pilot", cases)
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))

            duplicate = copy.deepcopy(manifest)
            duplicate["caseIds"][1] = duplicate["caseIds"][0]
            duplicate_path = root / "duplicate.json"
            duplicate_path.write_text(json.dumps(duplicate), encoding="utf-8")
            with self.assertRaises(ValueError):
                contract.load_suite(duplicate_path)

            traversal = copy.deepcopy(manifest)
            traversal["files"][0]["path"] = "../outside.jsonl"
            traversal_path = root / "traversal.json"
            traversal_path.write_text(json.dumps(traversal), encoding="utf-8")
            with self.assertRaises(ValueError):
                contract.load_suite(traversal_path)

            missing = copy.deepcopy(manifest)
            missing["files"][0]["path"] = "missing.jsonl"
            missing_path = root / "missing.json"
            missing_path.write_text(json.dumps(missing), encoding="utf-8")
            with self.assertRaises(ValueError):
                contract.load_suite(missing_path)

    def test_suite_rejects_hash_mismatch_and_case_id_mismatch(self):
        cases = _build_suite_cases(self.shared, "pilot")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest_path = _write_suite(root, "pilot", cases)
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))

            wrong_hash = copy.deepcopy(manifest)
            wrong_hash["files"][0]["sha256"] = "0" * 64
            wrong_hash_path = root / "wrong-hash.json"
            wrong_hash_path.write_text(json.dumps(wrong_hash), encoding="utf-8")
            with self.assertRaises(ValueError):
                contract.load_suite(wrong_hash_path)

            wrong_ids = copy.deepcopy(manifest)
            wrong_ids["caseIds"][0] = "NORMAL-999"
            wrong_ids_path = root / "wrong-ids.json"
            wrong_ids_path.write_text(json.dumps(wrong_ids), encoding="utf-8")
            with self.assertRaises(ValueError):
                contract.load_suite(wrong_ids_path)

    def test_pilot_manifest_cannot_claim_full_phase_or_repeat_quota(self):
        cases = _build_suite_cases(self.shared, "pilot")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest_path = _write_suite(root, "pilot", cases)
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["phase"] = "full"
            manifest["suiteVersion"] = "v1-full"
            path = root / "mislabelled.json"
            path.write_text(json.dumps(manifest), encoding="utf-8")
            with self.assertRaises(ValueError):
                contract.load_suite(path)

    def test_wire_contract_rejects_unknown_fields_and_wrong_mode_payloads(self):
        request = {
            "schemaVersion": 1,
            "op": "prepare",
            "runId": "run-001",
            "caseId": "NORMAL-001",
            "trialId": "trial-001",
            "fixture": self.shared["templates"]["live"]["fixture"],
        }
        self.assertEqual(request, contract.validate_wire("FixtureRequest", request))
        with self.assertRaises(ValueError):
            contract.validate_wire("FixtureRequest", {**request, "password": "must-not-cross-wire"})
        with self.assertRaises(ValueError):
            contract.validate_wire("FixtureRequest", {**request, "ledgerPath": "other-op-only"})

    def test_wire_contract_preserves_unknown_token_usage_as_null(self):
        result = {
            "schemaVersion": 1,
            "runId": "run-001",
            "caseId": "NORMAL-001",
            "trialId": "trial-001",
            "eventsFile": "events.ndjson",
            "privateEvidenceFile": "evidence.json",
            "metering": {"logicalModelRequests": 1, "promptTokens": None,
                         "completionTokens": None, "totalTokens": None, "usageComplete": False, "unknownUsageRequests": 1},
            "terminalEvidence": "NOT_SENT",
            "errorCategory": None,
        }
        validated = contract.validate_wire("WorkerResult", result)
        self.assertEqual(result, validated)
        self.assertIsNone(validated["metering"]["totalTokens"])

    def test_eval_runs_is_ignored_before_runtime_evidence_is_created(self):
        import subprocess

        result = subprocess.run(
            ["git", "check-ignore", "eval/runs/example/private.json"],
            cwd=REPOSITORY_ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)


def _build_suite_cases(shared, phase):
    templates = shared["templates"]
    if phase == "pilot":
        distribution = [
            ("NORMAL", "LIVE_E2E", 8),
            ("POLICY_CONFIRMATION", "LIVE_E2E", 6),
            ("POLICY_CONFIRMATION", "CONTROLLED", 2),
            ("ADVERSARIAL", "LIVE_E2E", 4),
            ("ADVERSARIAL", "CONTROLLED", 2),
            ("ABNORMAL", "CONTROLLED", 4),
            ("KNOWLEDGE", "LIVE_E2E", 4),
            ("INDEPENDENT_REVIEW", "REVIEW_ONLY", 2),
        ]
    else:
        distribution = [
            ("NORMAL", "LIVE_E2E", 60),
            ("POLICY_CONFIRMATION", "LIVE_E2E", 40),
            ("POLICY_CONFIRMATION", "CONTROLLED", 10),
            ("ADVERSARIAL", "LIVE_E2E", 20),
            ("ADVERSARIAL", "CONTROLLED", 20),
            ("ABNORMAL", "CONTROLLED", 30),
            ("KNOWLEDGE", "LIVE_E2E", 30),
            ("INDEPENDENT_REVIEW", "REVIEW_ONLY", 30),
        ]

    result = []
    sequence = 1
    for category, mode, count in distribution:
        template_name = "review" if mode == "REVIEW_ONLY" else (
            "controlled" if mode == "CONTROLLED" else "live")
        for _ in range(count):
            case = copy.deepcopy(templates[template_name])
            case["caseId"] = f"{category}-{sequence:03d}"
            case["category"] = category
            case["mode"] = mode
            case["variationRationale"] = f"Generated quota fixture {sequence}."
            result.append(case)
            sequence += 1
    return result


def _write_suite(directory, phase, cases):
    directory.mkdir(parents=True, exist_ok=True)
    case_file = directory / "cases.jsonl"
    lines = [json.dumps(case, ensure_ascii=False, separators=(",", ":")) for case in cases]
    contents = chr(10).join(lines) + chr(10)
    case_file.write_text(contents, encoding="utf-8")
    version = "v1-pilot" if phase == "pilot" else "v1-full"
    manifest = {
        "schemaVersion": 1,
        "suiteVersion": version,
        "phase": phase,
        "files": [{"path": "cases.jsonl",
                   "sha256": hashlib.sha256(case_file.read_bytes()).hexdigest()}],
        "caseIds": [case["caseId"] for case in cases],
        "repeatIds": [] if phase == "pilot" else [case["caseId"] for case in cases if case["category"] == "NORMAL"][:4] + [case["caseId"] for case in cases if case["category"] == "ADVERSARIAL" and case["mode"] == "LIVE_E2E"][:4] + [case["caseId"] for case in cases if case["category"] == "INDEPENDENT_REVIEW" and case["mode"] == "REVIEW_ONLY"][:4],
    }
    manifest_path = directory / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False), encoding="utf-8")
    return manifest_path







class BindingContractTest(unittest.TestCase):
    def test_worker_bindings_do_not_expose_foreign_actor_tokens(self):
        bindings = {
            "schemaVersion": 1, "runId": "run-001", "caseId": "NORMAL-001", "trialId": "trial-001",
            "activeActor": "actor-a",
            "actors": {"actor-a": {"userId": "9007199254740993", "userToken": "private-active-token"},
                       "actor-b": {"userId": "9007199254740994"}},
            "orders": {"order-a": {"orderId": "9007199254741000", "orderNo": "NO-1"}},
            "products": {"product-a": {"productId": "9007199254741001",
                "skus": {"sku-a": {"skuId": "9007199254741002", "price": "19.90"}}}},
        }
        self.assertEqual(bindings, contract.validate_wire("Bindings", bindings))
        leaked = copy.deepcopy(bindings)
        leaked["actors"]["actor-b"]["userToken"] = "must-stay-in-helper-ledger"
        with self.assertRaises(ValueError):
            contract.validate_wire("Bindings", leaked)

    def test_synthetic_review_bindings_have_no_actor_or_token(self):
        bindings = {
            "schemaVersion": 1, "runId": "run-001", "caseId": "INDEPENDENT_REVIEW-001",
            "trialId": "trial-001", "activeActor": None, "actors": {},
            "orders": {"order-a": {"orderId": "9007199254740993", "orderNo": "SYNTHETIC"}},
            "products": {},
        }
        self.assertEqual(bindings, contract.validate_wire("Bindings", bindings))




class DownstreamWireContractTest(unittest.TestCase):
    def test_fixture_lifecycle_operations_have_closed_payloads(self):
        base = {"schemaVersion": 1, "op": "preflight", "runId": "run-001",
                "caseId": "NORMAL-001", "trialId": "trial-001"}
        self.assertEqual(base, contract.validate_wire("FixtureRequest", base))
        self.assertEqual({"schemaVersion": 1, "op": "shutdown", "runId": "run-001",
                          "caseId": "NORMAL-001", "trialId": "trial-001"},
                         contract.validate_wire("FixtureRequest", {**base, "op": "shutdown"}))
        with self.assertRaises(ValueError):
            contract.validate_wire("FixtureRequest", {**base, "fixture": {}})
        reply = {"schemaVersion": 1, "op": "preflight", "status": "COMPLETED"}
        self.assertEqual(reply, contract.validate_wire("FixtureReply", reply))
        with self.assertRaises(ValueError):
            contract.validate_wire("FixtureReply", {**reply, "oracle": {}})

    def test_event_supports_non_tool_model_events_and_unknown_tool_sentinel(self):
        event = {"runId": "run-001", "caseId": "NORMAL-001", "trialId": "trial-001",
                 "sessionAlias": "session-a", "turnIndex": 0, "sequence": 1, "callId": "call-1",
                 "target": "GLOBAL", "phase": "MODEL", "role": "DIALOGUE", "tool": None,
                 "status": "SCRIPTED", "modelName": "configured-model", "exceptionClass": None, "httpStatus": None,
                 "businessCode": None, "policyCode": None,
                 "policyFingerprint": None, "sourceKey": None, "sourceDigest": None,
                 "durationMs": 0, "promptTokens": None, "completionTokens": None, "totalTokens": None}
        self.assertEqual(event, contract.validate_wire("Event", event))
        rejected = {**event, "sequence": 2, "callId": "call-2", "tool": "UNKNOWN_TOOL",
                    "status": "REJECTED"}
        self.assertEqual(rejected, contract.validate_wire("Event", rejected))
        with self.assertRaises(ValueError):
            contract.validate_wire("Event", {**event, "httpStatus": 99})

    def test_partial_metering_retains_known_totals_and_unknown_request_count(self):
        result = {"schemaVersion": 1, "runId": "run-001", "caseId": "NORMAL-001",
                  "trialId": "trial-001", "eventsFile": "events.ndjson",
                  "privateEvidenceFile": "evidence.json",
                  "metering": {"logicalModelRequests": 2, "promptTokens": 120,
                                "completionTokens": 21, "totalTokens": 141,
                                "usageComplete": False, "unknownUsageRequests": 1},
                  "terminalEvidence": "NOT_SENT", "errorCategory": None}
        self.assertEqual(result, contract.validate_wire("WorkerResult", result))
        inconsistent = {**result, "metering": {**result["metering"], "usageComplete": True}}
        with self.assertRaises(ValueError):
            contract.validate_wire("WorkerResult", inconsistent)



class FixtureReplyShapeTest(unittest.TestCase):
    def test_prepare_reply_status_controls_exact_payload_shape(self):
        prepared = {"schemaVersion": 1, "op": "prepare", "status": "PREPARED",
                    "ledgerPath": "runs/trial/ledger.json"}
        self.assertEqual(prepared, contract.validate_wire("FixtureReply", prepared))
        with self.assertRaises(ValueError):
            contract.validate_wire("FixtureReply", {**prepared,
                "bindingFile": "runs/trial/bindings.json"})
        completed = {"schemaVersion": 1, "op": "prepare", "status": "COMPLETED",
                     "ledgerPath": "runs/trial/ledger.json",
                     "bindingFile": "runs/trial/bindings.json"}
        self.assertEqual(completed, contract.validate_wire("FixtureReply", completed))


class RenderInputTypeTest(unittest.TestCase):
    def test_binding_values_must_be_strings(self):
        with self.assertRaises(ValueError):
            contract.render_input("{{order-a}}", {"order-a": 42})




if __name__ == "__main__":
    unittest.main()
