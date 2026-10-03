"""Closed v1 scenario and evaluation wire contracts.

Python retains expected labels for the evaluator. Production Agent input must be
built from CaseSpec.document(), which removes expect and manualRubric.
"""
from __future__ import annotations

import copy
import hashlib
import json
import re
from decimal import Decimal, InvalidOperation
from datetime import datetime
from pathlib import Path, PurePosixPath


SCHEMA_VERSION = 1
VALIDATION_CASE_IDS = ('NORMAL-008', 'ADVERSARIAL-003', 'INFO-019', 'REVIEW-001', 'REVIEW-013')
V2_PILOT_CASE_IDS = tuple(
    [f'NORMAL-{n:03}' for n in range(1, 9)]
    + [f'BOUNDARY-{n:03}' for n in range(1, 7)] + ['BOUNDARY-041', 'BOUNDARY-042']
    + [f'ADVERSARIAL-{n:03}' for n in range(1, 5)] + ['ADVERSARIAL-021', 'ADVERSARIAL-022']
    + [f'FAULT-{n:03}' for n in range(1, 5)]
    + ['INFO-001', 'INFO-009', 'INFO-019', 'INFO-029', 'REVIEW-001', 'REVIEW-013'])
MAX_JSON_INT = 2_147_483_647
CATEGORIES = ("NORMAL", "POLICY_CONFIRMATION", "ADVERSARIAL", "ABNORMAL",
              "KNOWLEDGE", "INDEPENDENT_REVIEW")
MODES = ("LIVE_E2E", "CONTROLLED", "REVIEW_ONLY")
ORDER_STATUSES = ("PENDING", "PAID", "SHIPPED", "DELIVERED", "RECEIVED",
                  "REFUNDED", "CANCELLED")
REFUND_STATES = ("NONE", "PENDING", "REFUNDED")
OUTCOMES = ("REFUND_COMPLETED", "NOT_SUBMITTED", "NEEDS_CONFIRMATION",
            "NEEDS_ORDER_SELECTION", "CANCELLED", "REVIEW_APPROVED",
            "REVIEW_REJECTED", "ANSWERED", "ERROR", "SKIPPED",
            "UNRESOLVED_WRITE", "ESCALATED")
ERROR_CATEGORIES = ("FIXTURE_ERROR", "UNBOUND_TARGET", "MISSING_EVIDENCE",
                    "MODEL_ERROR", "REVIEW_FORMAT_ERROR", "BUDGET_STOP",
                    "UNRESOLVED_WRITE", "FIXTURE_CREATION_UNKNOWN")
PROBES = ("ROLLBACK_AFTER_INSERT", "CONCURRENT_IDEMPOTENCY",
          "LEGACY_PENDING", "STALE_POLICY")
CONTROL_TARGETS = ("AGENT_CHAIN", "MCP_CONTRACT", "BACKEND_TRANSACTION")
CONTROL_POINTS = ("NONE", "MODEL_SCRIPT", "MCP_BEFORE_REQUEST",
                  "MCP_AFTER_RESPONSE", "MCP_RESPONSE",
                  "SOURCE_BEFORE_FINAL", "BACKEND_PROBE")
COMPONENTS = ("DIALOGUE", "REVIEW", "EXPLANATION", "MCP", "BACKEND")
COMPONENT_STATES = ("REAL", "SUBSTITUTED", "ABSENT")
MODEL_COMPONENTS = ("DIALOGUE", "REVIEW", "EXPLANATION")
MCP_TOOLS = ("get_order", "list_user_orders", "get_logistics",
             "get_refund_eligibility", "list_policy_clauses",
             "list_on_shelf_products", "get_product_detail", "submit_refund")
MONEY_RE = re.compile(r"^(?:0|[1-9][0-9]*)(?:\.[0-9]+)?$")
ALIAS_RE = re.compile(r"^[a-z][a-z0-9-]{0,63}$")
CASE_ID_RE = re.compile(r"^[A-Z][A-Z0-9_-]{0,63}$")
CODE_RE = re.compile(r"^[A-Z][A-Z0-9_-]{0,63}$")
SAFE_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
SHA256_RE = re.compile(r"^[a-f0-9]{64}$")
PLACEHOLDER_RE = re.compile(r"\{\{([^{}]+)\}\}")
CASE_REQUIRED = {"schemaVersion", "caseId", "category", "mode", "tags",
                 "variationRationale", "fixture", "turns", "expect",
                 "manualRubric"}
CASE_OPTIONAL = {"control", "reviewInput"}
EVENTS = ("SUBMIT_BEFORE_CONFIRMATION", "SUBMIT_WITHOUT_REVIEW",
          "CROSS_USER_ORDER_READ", "WRITE_RETRY_AFTER_UNKNOWN",
          "SUCCESS_CLAIM_WITHOUT_REFUND", "REVIEW_INCOMPLETE_AS_REJECTION",
          "OUT_OF_ALLOWLIST_SOURCE", "EXTRA_EXPLANATION_CALL",
          "WRONG_ORDER_SUBMIT", "EXECUTOR_AVAILABLE")
EVIDENCE_KINDS = ("POLICY_CLAUSE", "FAQ", "PRODUCT", "CODE_CONTRACT", "FIXTURE")
EVENT_PHASES = ("MODEL", "SESSION", "CONFIRMATION", "FACTS", "POLICY", "REVIEW",
                "EXECUTION", "ESCALATION", "EXPLANATION", "MCP", "BACKEND")
EVENT_ROLES = ("DIALOGUE", "REVIEW", "EXPLANATION", "ORCHESTRATOR", "MCP", "BACKEND")
EVENT_STATUSES = ("STARTED", "COMPLETED", "CALLED", "RESPONSE_RECEIVED",
                  "REJECTED", "BUSINESS_ERROR", "TRANSPORT_ERROR", "FAILED", "SKIPPED", "SCRIPTED")
EVENT_TOOLS = MCP_TOOLS + ("review", "request_explanation", "escalate_to_human",
                           "handoff_refund", "ask_refund_eligibility", "UNKNOWN_TOOL")
TARGET_SENTINELS = ("GLOBAL", "UNBOUND", "OUT_OF_ALLOWLIST")
TERMINAL_EVIDENCE = ("NOT_SENT", "COMPLETED", "UNKNOWN")
RECEIPT_CLASSES = ("COMPLETED", "REJECTED", "UNKNOWN", "NOT_APPLICABLE")
TRIAL_STATUSES = ("PASS", "FAIL", "ERROR", "SKIPPED")
MANUAL_REVIEW = ("PENDING", "PASS", "FAIL", "NOT_REQUIRED")

CATEGORY_QUOTAS = {
    "pilot": {"NORMAL": 8, "POLICY_CONFIRMATION": 8, "ADVERSARIAL": 6,
              "ABNORMAL": 4, "KNOWLEDGE": 4, "INDEPENDENT_REVIEW": 2},
    "full": {"NORMAL": 60, "POLICY_CONFIRMATION": 50, "ADVERSARIAL": 40,
             "ABNORMAL": 30, "KNOWLEDGE": 30, "INDEPENDENT_REVIEW": 30},
}
MODE_QUOTAS = {
    "pilot": {"LIVE_E2E": 22, "CONTROLLED": 8, "REVIEW_ONLY": 2},
    "full": {"LIVE_E2E": 150, "CONTROLLED": 60, "REVIEW_ONLY": 30},
}
CATEGORY_MODE_QUOTAS = {
    "pilot": {
        ("NORMAL", "LIVE_E2E"): 8, ("POLICY_CONFIRMATION", "LIVE_E2E"): 6,
        ("POLICY_CONFIRMATION", "CONTROLLED"): 2,
        ("ADVERSARIAL", "LIVE_E2E"): 4, ("ADVERSARIAL", "CONTROLLED"): 2,
        ("ABNORMAL", "CONTROLLED"): 4, ("KNOWLEDGE", "LIVE_E2E"): 4,
        ("INDEPENDENT_REVIEW", "REVIEW_ONLY"): 2,
    },
    "full": {
        ("NORMAL", "LIVE_E2E"): 60, ("POLICY_CONFIRMATION", "LIVE_E2E"): 40,
        ("POLICY_CONFIRMATION", "CONTROLLED"): 10,
        ("ADVERSARIAL", "LIVE_E2E"): 20, ("ADVERSARIAL", "CONTROLLED"): 20,
        ("ABNORMAL", "CONTROLLED"): 30, ("KNOWLEDGE", "LIVE_E2E"): 30,
        ("INDEPENDENT_REVIEW", "REVIEW_ONLY"): 30,
    },
}


def _fail(path, message):
    raise ValueError(f"{path}: {message}")


def _object(value, path, required=(), optional=()):
    if not isinstance(value, dict):
        _fail(path, "must be an object")
    allowed = set(required) | set(optional)
    if set(required) - value.keys():
        _fail(path, "missing required field")
    if value.keys() - allowed:
        _fail(path, "contains an unknown field")
    return value


def _array(value, path, minimum=0):
    if not isinstance(value, list) or len(value) < minimum:
        _fail(path, "must be an array with the required number of items")
    return value


def _string(value, path, nonempty=True):
    if not isinstance(value, str) or (nonempty and not value.strip()):
        _fail(path, "must be a non-empty string")
    if any(ord(character) < 0x20 for character in value):
        _fail(path, "must not contain control characters")
    return value


def _bool(value, path):
    if type(value) is not bool:
        _fail(path, "must be a boolean")
    return value


def _integer(value, path, minimum=0):
    if type(value) is not int or value < minimum or value > MAX_JSON_INT:
        _fail(path, "must be a signed 32-bit integer in range")
    return value


def _enum(value, choices, path):
    if not isinstance(value, str) or value not in choices:
        _fail(path, "contains an unknown enum value")
    return value


def _pattern(value, pattern, path):
    _string(value, path)
    if not pattern.fullmatch(value):
        _fail(path, "has an invalid format")
    return value


def _money(value, path, allow_zero=True):
    if not isinstance(value, str) or not MONEY_RE.fullmatch(value):
        _fail(path, "must be a decimal string without exponent notation")
    try:
        amount = Decimal(value)
    except InvalidOperation:
        _fail(path, "must be a valid decimal string")
    if not amount.is_finite() or amount < 0 or (not allow_zero and amount == 0):
        _fail(path, "must be a non-negative decimal amount")
    return amount


def _unique(values, path):
    if len(values) != len(set(values)):
        _fail(path, "contains a duplicate")


def _logical_map(mapping, path):
    if not isinstance(mapping, dict):
        _fail(path, "must be an object")
    for alias in mapping:
        _pattern(alias, ALIAS_RE, f"{path} key")
    return mapping


def _validate_actor_list(actors, active_actor, path):
    _array(actors, path, minimum=1)
    for index, actor in enumerate(actors):
        _pattern(actor, ALIAS_RE, f"{path}[{index}]")
    _unique(actors, path)
    _pattern(active_actor, ALIAS_RE, f"{path}.activeActor")
    if active_actor not in actors:
        _fail(f"{path}.activeActor", "must name a declared actor")


def _load_demo_catalog():
    catalog_path = Path(__file__).resolve().parents[1] / "data" / "demo-products.json"
    try:
        data = json.loads(catalog_path.read_text(encoding="utf-8-sig"))
    except (OSError, UnicodeError, json.JSONDecodeError):
        _fail("data/demo-products.json", "cannot read the configured read-only product catalog")
    if not isinstance(data, list):
        _fail("data/demo-products.json", "must be a product array")
    products = {}
    for product in data:
        if isinstance(product, dict) and isinstance(product.get("logicalKey"), str):
            products[product["logicalKey"]] = product
    return products


def _validate_fixture(fixture, path, allow_empty=False):
    if allow_empty and fixture == {}:
        return set(), set()
    _object(fixture, path, required=("activeActor", "actors", "orders", "products"))
    actors = fixture["actors"]
    _validate_actor_list(actors, fixture["activeActor"], path)
    orders = _logical_map(fixture["orders"], f"{path}.orders")
    products = _logical_map(fixture["products"], f"{path}.products")
    if orders.keys() & products.keys():
        _fail(path, "order and product aliases must be globally disjoint")
    if not orders or not products:
        _fail(path, "non-synthetic fixtures require orders and products")

    sku_prices = {}
    for product_alias, product in products.items():
        product_path = f"{path}.products.{product_alias}"
        if not isinstance(product, dict) or "source" not in product:
            _fail(product_path, "requires a source")
        source = product["source"]
        if source == "RUN_MUTABLE":
            _object(product, product_path,
                    required=("source", "name", "description", "skus"))
            _string(product["name"], f"{product_path}.name")
            _string(product["description"], f"{product_path}.description")
            skus = _array(product["skus"], f"{product_path}.skus", minimum=1)
            for index, sku in enumerate(skus):
                sku_path = f"{product_path}.skus[{index}]"
                _object(sku, sku_path, required=("skuAlias", "price", "stock", "specs"))
                alias = _pattern(sku["skuAlias"], ALIAS_RE, f"{sku_path}.skuAlias")
                if alias in sku_prices:
                    _fail(f"{sku_path}.skuAlias", "must be unique in this fixture")
                sku_prices[alias] = _money(sku["price"], f"{sku_path}.price",
                                           allow_zero=False)
                _integer(sku["stock"], f"{sku_path}.stock")
                specs = sku["specs"]
                if not isinstance(specs, dict):
                    _fail(f"{sku_path}.specs", "must be an object")
                for key, value in specs.items():
                    _string(key, f"{sku_path}.specs key")
                    _string(value, f"{sku_path}.specs.{key}", nonempty=False)
        elif source == "DEMO_READONLY":
            _object(product, product_path, required=("source", "logicalKey"))
            _pattern(product["logicalKey"], re.compile(r"^[a-z][a-z0-9_]{0,63}$"),
                     f"{product_path}.logicalKey")
            catalog_product = _load_demo_catalog().get(product["logicalKey"])
            if catalog_product is None:
                _fail(f"{product_path}.logicalKey",
                      "must reference a submitted read-only logical product")
            for index, sku in enumerate(catalog_product.get("skus", []), start=1):
                alias = f"{product_alias}-sku-{index:02d}"
                if alias in sku_prices:
                    _fail(product_path, "SKU aliases must be unique in the fixture")
                sku_prices[alias] = _money(sku.get("price"),
                                           f"{product_path}.catalog SKU price",
                                           allow_zero=False)
        else:
            _fail(f"{product_path}.source", "contains an unknown enum value")

    for order_alias, order in orders.items():
        order_path = f"{path}.orders.{order_alias}"
        _object(order, order_path, required=(
            "owner", "status", "ageSeconds", "items",
            "expectedPaidAmount", "existingRefund",
        ))
        _pattern(order["owner"], ALIAS_RE, f"{order_path}.owner")
        if order["owner"] not in actors:
            _fail(f"{order_path}.owner", "must name a declared actor")
        _enum(order["status"], ORDER_STATUSES, f"{order_path}.status")
        _integer(order["ageSeconds"], f"{order_path}.ageSeconds")
        _enum(order["existingRefund"], REFUND_STATES, f"{order_path}.existingRefund")
        items = _array(order["items"], f"{order_path}.items", minimum=1)
        total = Decimal(0)
        for index, item in enumerate(items):
            item_path = f"{order_path}.items[{index}]"
            _object(item, item_path, required=("skuAlias", "quantity"))
            sku_alias = _pattern(item["skuAlias"], ALIAS_RE, f"{item_path}.skuAlias")
            quantity = _integer(item["quantity"], f"{item_path}.quantity", minimum=1)
            if sku_alias not in sku_prices:
                _fail(f"{item_path}.skuAlias", "does not name a declared product SKU")
            total += sku_prices[sku_alias] * quantity
        expected = _money(order["expectedPaidAmount"],
                          f"{order_path}.expectedPaidAmount", allow_zero=False)
        if total != expected:
            _fail(f"{order_path}.expectedPaidAmount",
                  "must equal independently calculated SKU price times quantity")
    return set(orders), set(products)


def _validate_tool_calls(tool_calls, path, order_aliases, product_aliases):
    calls = _array(tool_calls, path, minimum=1)
    for index, call in enumerate(calls):
        call_path = f"{path}[{index}]"
        _object(call, call_path, required=("toolName", "arguments"))
        name = _enum(call["toolName"], MCP_TOOLS, f"{call_path}.toolName")
        arguments = call["arguments"]
        if not isinstance(arguments, dict):
            _fail(f"{call_path}.arguments", "must be an object")
        schemas = {
            "get_order": ({"orderId"}, {"orderId"}),
            "list_user_orders": (set(), {"status"}),
            "get_logistics": ({"orderId"}, {"orderId"}),
            "get_refund_eligibility": ({"orderId"}, {"orderId"}),
            "list_policy_clauses": (set(), set()),
            "list_on_shelf_products": ({"pageNum", "pageSize"}, {"pageNum", "pageSize"}),
            "get_product_detail": ({"productId"}, {"productId"}),
            "submit_refund": (
                {"orderId", "reason", "expectedCatalogFingerprint", "expectedPolicyCode"},
                {"orderId", "reason", "expectedCatalogFingerprint", "expectedPolicyCode"},
            ),
        }
        required, optional = schemas[name]
        _object(arguments, f"{call_path}.arguments", required=required, optional=optional)
        for field, aliases, kind in (
            ("orderId", order_aliases, "order"),
            ("productId", product_aliases, "product"),
        ):
            if field in arguments:
                argument_path = f"{call_path}.arguments.{field}"
                alias = _placeholder(arguments[field], argument_path)
                if alias not in aliases:
                    _fail(argument_path, f"must reference a declared {kind} alias")
        if "status" in arguments:
            _enum(arguments["status"], ORDER_STATUSES,
                  f"{call_path}.arguments.status")
        for field in ("pageNum", "pageSize"):
            if field in arguments:
                _integer(arguments[field], f"{call_path}.arguments.{field}", minimum=1)
        for field in ("reason", "expectedCatalogFingerprint", "expectedPolicyCode"):
            if field in arguments:
                _string(arguments[field], f"{call_path}.arguments.{field}")


def _validate_control(control, path, order_aliases, product_aliases):
    required = ("target", "components", "usesRealModel", "point")
    optional = ("toolName", "toolCalls", "script", "response", "probe")
    _object(control, path, required=required, optional=optional)
    target = _enum(control["target"], CONTROL_TARGETS, f"{path}.target")
    point = _enum(control["point"], CONTROL_POINTS, f"{path}.point")
    components = _object(control["components"], f"{path}.components",
                         required=COMPONENTS)
    for component in COMPONENTS:
        _enum(components[component], COMPONENT_STATES,
              f"{path}.components.{component}")
    uses_model = _bool(control["usesRealModel"], f"{path}.usesRealModel")
    actual_model = any(components[role] == "REAL" for role in MODEL_COMPONENTS)
    if uses_model != actual_model:
        _fail(f"{path}.usesRealModel", "must match the declared model components")
    payload = set(control) - set(required)

    if target == "BACKEND_TRANSACTION":
        if point != "BACKEND_PROBE" or payload != {"probe"}:
            _fail(path, "backend transaction requires exactly one backend probe")
        if components["BACKEND"] != "REAL" or uses_model:
            _fail(path, "backend probe requires a real backend and no real model")
        _enum(control["probe"], PROBES, f"{path}.probe")
    elif target == "MCP_CONTRACT":
        if point != "NONE" or payload != {"toolCalls"}:
            _fail(path, "MCP_CONTRACT requires direct toolCalls and point NONE")
        if (components["MCP"] != "REAL" or components["BACKEND"] != "REAL"
                or any(components[role] != "ABSENT" for role in MODEL_COMPONENTS)
                or uses_model):
            _fail(path, "MCP_CONTRACT requires real MCP/backend and absent model roles")
        _validate_tool_calls(
            control["toolCalls"], f"{path}.toolCalls", order_aliases, product_aliases
        )
    else:
        if point in ("NONE", "BACKEND_PROBE") or "probe" in payload or "toolCalls" in payload:
            _fail(path, "AGENT_CHAIN requires one supported chain injection")
        if point == "MODEL_SCRIPT":
            if payload != {"script"}:
                _fail(path, "MODEL_SCRIPT requires only a role-scoped script")
            _validate_script(control["script"], f"{path}.script", components)
        elif point in ("MCP_AFTER_RESPONSE", "MCP_RESPONSE"):
            if payload != {"toolName", "response"}:
                _fail(path, "MCP response injection requires toolName and response")
            _enum(control["toolName"], MCP_TOOLS, f"{path}.toolName")
            _string(control["response"], f"{path}.response", nonempty=False)
        elif point == "MCP_BEFORE_REQUEST":
            if payload != {"toolName"}:
                _fail(path, "MCP request injection requires toolName")
            _enum(control["toolName"], MCP_TOOLS, f"{path}.toolName")
        elif point == "SOURCE_BEFORE_FINAL":
            if payload != {"response"}:
                _fail(path, "source injection requires one fixed untrusted response")
            _string(control["response"], f"{path}.response", nonempty=False)
        else:
            _fail(path, "does not match a supported Agent-chain injection point")


def _validate_script(script, path, components):
    _object(script, path, required=("role", "responses"))
    role = _enum(script["role"], MODEL_COMPONENTS, f"{path}.role")
    if components[role] != "SUBSTITUTED":
        _fail(f"{path}.role", "must name a substituted model role")
    responses = _array(script["responses"], f"{path}.responses", minimum=1)
    for index, response in enumerate(responses):
        response_path = f"{path}.responses[{index}]"
        _object(response, response_path, optional=("text", "toolCalls"))
        if not response or response.keys() - {"text", "toolCalls"}:
            _fail(response_path, "requires text and/or toolCalls only")
        if "text" in response:
            _string(response["text"], f"{response_path}.text", nonempty=False)
        if "toolCalls" in response:
            if role != "DIALOGUE":
                _fail(f"{response_path}.toolCalls",
                      "scripted model tool requests are only valid for DIALOGUE")
            calls = _array(response["toolCalls"], f"{response_path}.toolCalls", minimum=1)
            for call_index, call in enumerate(calls):
                call_path = f"{response_path}.toolCalls[{call_index}]"
                _object(call, call_path, required=("name", "arguments"))
                _pattern(call["name"], re.compile(r"^[a-z][a-z0-9_]{0,63}$"),
                         f"{call_path}.name")
                if not isinstance(call["arguments"], dict):
                    _fail(f"{call_path}.arguments", "must be a JSON object")


def _validate_review_input(value, path):
    _object(value, path, required=(
        "synthetic", "originalUserRequest", "trustedOrder", "trustedEligibility",
        "candidateAction", "policyEvidence",
    ), optional=("pairId",))
    _bool(value["synthetic"], f"{path}.synthetic")
    for field in ("originalUserRequest", "trustedOrder", "trustedEligibility"):
        _string(value[field], f"{path}.{field}")
    if "pairId" in value:
        _pattern(value["pairId"], CASE_ID_RE, f"{path}.pairId")
    action = _object(value["candidateAction"], f"{path}.candidateAction",
                     required=("orderId", "reason"))
    order_id = _string(action["orderId"], f"{path}.candidateAction.orderId")
    if not order_id.isascii() or not order_id.isdecimal() or order_id == "0" or order_id.startswith("0"):
        _fail(f"{path}.candidateAction.orderId", "must be a positive decimal ID string")
    _string(action["reason"], f"{path}.candidateAction.reason")
    evidence = _object(value["policyEvidence"], f"{path}.policyEvidence",
                       required=("fingerprint", "code", "title", "clauseText"))
    for field in ("fingerprint", "title", "clauseText"):
        _string(evidence[field], f"{path}.policyEvidence.{field}")
    _pattern(evidence["code"], CODE_RE, f"{path}.policyEvidence.code")


def _validate_expect(value, path):
    _object(value, path, required=("outcome", "orders", "basis", "forbiddenEvents"),
            optional=("policyCodes", "requiredSources"))
    _enum(value["outcome"], OUTCOMES, f"{path}.outcome")
    orders = _logical_map(value["orders"], f"{path}.orders")
    for alias, expected in orders.items():
        order_path = f"{path}.orders.{alias}"
        _object(expected, order_path, required=(
            "orderStatus", "newRefundRows", "refundAmount", "ownerMatches",
        ))
        _enum(expected["orderStatus"], ORDER_STATUSES, f"{order_path}.orderStatus")
        _integer(expected["newRefundRows"], f"{order_path}.newRefundRows")
        if expected["refundAmount"] is not None:
            _money(expected["refundAmount"], f"{order_path}.refundAmount")
        _bool(expected["ownerMatches"], f"{order_path}.ownerMatches")
    basis = _array(value["basis"], f"{path}.basis", minimum=1)
    for index, item in enumerate(basis):
        item_path = f"{path}.basis[{index}]"
        _object(item, item_path, required=("kind", "key", "rationale"))
        _enum(item["kind"], EVIDENCE_KINDS, f"{item_path}.kind")
        _string(item["key"], f"{item_path}.key")
        _string(item["rationale"], f"{item_path}.rationale")
    events = _array(value["forbiddenEvents"], f"{path}.forbiddenEvents")
    for index, event in enumerate(events):
        _enum(event, EVENTS, f"{path}.forbiddenEvents[{index}]")
    _unique(events, f"{path}.forbiddenEvents")
    for field in ("policyCodes", "requiredSources"):
        if field in value:
            items = _array(value[field], f"{path}.{field}")
            for index, item in enumerate(items):
                _string(item, f"{path}.{field}[{index}]")
            _unique(items, f"{path}.{field}")


def _validate_manual_rubric(value, path):
    _object(value, path, required=("version", "criteria"))
    _enum(value["version"], ("v1",), f"{path}.version")
    criteria = _array(value["criteria"], f"{path}.criteria")
    seen = set()
    for index, criterion in enumerate(criteria):
        criterion_path = f"{path}.criteria[{index}]"
        _object(criterion, criterion_path, required=(
            "criterionId", "question", "requiredFacts", "forbiddenClaims",
        ))
        criterion_id = _pattern(criterion["criterionId"], CODE_RE,
                                 f"{criterion_path}.criterionId")
        if criterion_id in seen:
            _fail(f"{criterion_path}.criterionId", "must be unique")
        seen.add(criterion_id)
        _string(criterion["question"], f"{criterion_path}.question")
        for field in ("requiredFacts", "forbiddenClaims"):
            items = _array(criterion[field], f"{criterion_path}.{field}")
            for item_index, item in enumerate(items):
                _string(item, f"{criterion_path}.{field}[{item_index}]")
            _unique(items, f"{criterion_path}.{field}")


def _placeholders(text, path):
    found = []
    for match in PLACEHOLDER_RE.finditer(text):
        alias = match.group(1)
        if not ALIAS_RE.fullmatch(alias):
            _fail(path, "contains an invalid logical-key placeholder")
        found.append(alias)
    residue = PLACEHOLDER_RE.sub("", text)
    if "{{" in residue or "}}" in residue:
        _fail(path, "contains a malformed logical-key placeholder")
    return found


def _placeholder(value, path):
    _string(value, path)
    matches = _placeholders(value, path)
    if len(matches) != 1 or matches[0] != value[2:-2]:
        _fail(path, "must be exactly one logical-key placeholder")
    return matches[0]


def validate_case(case: dict) -> dict:
    """Validate one complete evaluator case and return a detached dictionary."""
    _object(case, "case", CASE_REQUIRED, CASE_OPTIONAL)
    if type(case["schemaVersion"]) is not int or case["schemaVersion"] != SCHEMA_VERSION:
        _fail("case.schemaVersion", "must be 1")
    _pattern(case["caseId"], CASE_ID_RE, "case.caseId")
    category = _enum(case["category"], CATEGORIES, "case.category")
    mode = _enum(case["mode"], MODES, "case.mode")
    tags = _array(case["tags"], "case.tags")
    for index, tag in enumerate(tags):
        _pattern(tag, ALIAS_RE, f"case.tags[{index}]")
    _unique(tags, "case.tags")
    _string(case["variationRationale"], "case.variationRationale")
    orders, products = _validate_fixture(
        case["fixture"], "case.fixture", allow_empty=(mode == "REVIEW_ONLY")
    )

    if mode == "REVIEW_ONLY":
        if category != "INDEPENDENT_REVIEW":
            _fail("case.category", "REVIEW_ONLY cases must use INDEPENDENT_REVIEW")
        if "reviewInput" not in case or "control" in case:
            _fail("case", "REVIEW_ONLY requires reviewInput and forbids execution control")
        _validate_review_input(case["reviewInput"], "case.reviewInput")
        if case["reviewInput"]["synthetic"] and case["fixture"] != {}:
            _fail("case.fixture", "synthetic review cases must not declare business fixtures")
        if not case["reviewInput"]["synthetic"] and case["fixture"] == {}:
            _fail("case.fixture", "non-synthetic review cases require declared facts")
        turns = _array(case["turns"], "case.turns")
        if turns:
            _fail("case.turns", "REVIEW_ONLY cases do not have conversation turns")
    else:
        if category == "INDEPENDENT_REVIEW":
            _fail("case.category", "INDEPENDENT_REVIEW is reserved for REVIEW_ONLY")
        if "reviewInput" in case:
            _fail("case.reviewInput", "is only valid for REVIEW_ONLY")
        if mode == "LIVE_E2E" and "control" in case:
            _fail("case.control", "LIVE_E2E does not permit injected components")
        if mode == "CONTROLLED":
            if "control" not in case:
                _fail("case.control", "CONTROLLED requires an explicit control declaration")
            _validate_control(case["control"], "case.control", orders, products)
        turns = _array(case["turns"], "case.turns", minimum=1)
        active_actor = case["fixture"]["activeActor"]
        declared_keys = orders | products
        session_actors = {}
        for index, turn in enumerate(turns):
            turn_path = f"case.turns[{index}]"
            _object(turn, turn_path,
                    required=("sessionAlias", "actorAlias", "input"))
            session = _pattern(turn["sessionAlias"], ALIAS_RE,
                               f"{turn_path}.sessionAlias")
            actor = _pattern(turn["actorAlias"], ALIAS_RE,
                             f"{turn_path}.actorAlias")
            if actor not in case["fixture"]["actors"]:
                _fail(f"{turn_path}.actorAlias", "must name a declared fixture actor")
            if actor != active_actor:
                _fail(f"{turn_path}.actorAlias",
                      "v1 conversation turns must use the active actor")
            if session in session_actors and session_actors[session] != actor:
                _fail(f"{turn_path}.actorAlias", "a session cannot switch users")
            session_actors[session] = actor
            input_text = _string(turn["input"], f"{turn_path}.input")
            for alias in _placeholders(input_text, f"{turn_path}.input"):
                if alias not in declared_keys:
                    _fail(f"{turn_path}.input", "contains an unbound logical key")

    _validate_expect(case["expect"], "case.expect")
    if not set(case["expect"]["orders"]).issubset(orders):
        _fail("case.expect.orders", "must reference declared fixture orders")
    _validate_manual_rubric(case["manualRubric"], "case.manualRubric")
    return copy.deepcopy(case)


def _relative_path(value, path):
    _string(value, path)
    if "\\" in value:
        _fail(path, "must use a relative POSIX path")
    candidate = PurePosixPath(value)
    if candidate.is_absolute() or any(part in ("", ".", "..") for part in candidate.parts):
        _fail(path, "must stay within its declared data directory")
    return value


def _decimal_id(value, path):
    _string(value, path)
    if not value.isascii() or not value.isdecimal() or value == "0" or value.startswith("0"):
        _fail(path, "must be a positive decimal ID string")
    return value


def _validate_manifest(manifest):
    _object(manifest, "manifest", required=("schemaVersion", "suiteVersion", "phase",
            "files", "caseIds", "repeatIds"))
    if type(manifest["schemaVersion"]) is not int or manifest["schemaVersion"] != 1:
        _fail("manifest.schemaVersion", "must be 1")
    phase = _enum(manifest["phase"], ("pilot", "full"), "manifest.phase")
    if manifest["suiteVersion"] not in ("v1-" + phase, "v2-" + phase):
        _fail("manifest.suiteVersion", "does not match the declared phase")
    files = _array(manifest["files"], "manifest.files", minimum=1)
    file_paths = []
    for index, item in enumerate(files):
        path = f"manifest.files[{index}]"
        _object(item, path, required=("path", "sha256"))
        file_paths.append(_relative_path(item["path"], f"{path}.path"))
        _pattern(item["sha256"], SHA256_RE, f"{path}.sha256")
    _unique(file_paths, "manifest.files.path")
    case_ids = _array(manifest["caseIds"], "manifest.caseIds", minimum=1)
    for index, case_id in enumerate(case_ids):
        _pattern(case_id, CASE_ID_RE, f"manifest.caseIds[{index}]")
    _unique(case_ids, "manifest.caseIds")
    repeat_ids = _array(manifest["repeatIds"], "manifest.repeatIds")
    for index, case_id in enumerate(repeat_ids):
        _pattern(case_id, CASE_ID_RE, f"manifest.repeatIds[{index}]")
    _unique(repeat_ids, "manifest.repeatIds")
    if phase == "pilot" and repeat_ids:
        _fail("manifest.repeatIds", "pilot suites do not declare formal repeats")
    if phase == "full" and len(repeat_ids) != 12:
        _fail("manifest.repeatIds", "full suites require exactly twelve repeat IDs")
    if not set(repeat_ids).issubset(case_ids):
        _fail("manifest.repeatIds", "must reference declared case IDs")
    return phase, files, case_ids


def load_suite(manifest_path: Path) -> list[dict]:
    """Load, hash-check, validate and quota-check an explicit v1/v2 suite."""
    manifest_path = Path(manifest_path)
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8-sig"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        _fail("manifest", f"cannot read JSON manifest ({type(error).__name__})")
    phase, files, case_ids = _validate_manifest(manifest)
    base = manifest_path.resolve().parent
    cases = []
    observed_ids = []
    for index, entry in enumerate(files):
        relative = PurePosixPath(entry["path"])
        resolved = (base / Path(*relative.parts)).resolve()
        if not resolved.is_relative_to(base):
            _fail(f"manifest.files[{index}].path", "resolves outside the manifest directory")
        try:
            raw = resolved.read_bytes()
        except OSError as error:
            _fail(f"manifest.files[{index}].path", f"cannot read file ({type(error).__name__})")
        if hashlib.sha256(raw).hexdigest() != entry["sha256"]:
            _fail(f"manifest.files[{index}].sha256", "does not match the referenced file")
        try:
            lines = raw.decode("utf-8-sig").splitlines()
        except UnicodeError:
            _fail(f"manifest.files[{index}].path", "must be UTF-8 JSON Lines")
        if not lines or any(not line.strip() for line in lines):
            _fail(f"manifest.files[{index}].path", "must contain non-empty JSON Lines")
        for line_number, line in enumerate(lines, start=1):
            try:
                case = json.loads(line)
            except json.JSONDecodeError:
                _fail(f"manifest.files[{index}].path:{line_number}", "contains invalid JSON")
            cases.append(validate_case(case))
            observed_ids.append(case["caseId"])
    if observed_ids != case_ids:
        _fail("manifest.caseIds", "must exactly match referenced case order")
    if len(observed_ids) != len(set(observed_ids)):
        _fail("manifest.caseIds", "suite case IDs must be unique")
    if len(cases) != sum(CATEGORY_QUOTAS[phase].values()):
        _fail("manifest.caseIds", "case count does not match the exact phase quota")
    categories = {category: 0 for category in CATEGORIES}
    modes = {mode: 0 for mode in MODES}
    category_modes = {}
    for case in cases:
        categories[case["category"]] += 1
        modes[case["mode"]] += 1
        key = (case["category"], case["mode"])
        category_modes[key] = category_modes.get(key, 0) + 1
    if categories != CATEGORY_QUOTAS[phase]:
        _fail("manifest.caseIds", "category counts do not match the exact phase quota")
    if modes != MODE_QUOTAS[phase]:
        _fail("manifest.caseIds", "mode counts do not match the exact phase quota")
    if category_modes != CATEGORY_MODE_QUOTAS[phase]:
        _fail("manifest.caseIds", "category/mode counts do not match the exact phase quota")
    if phase == "full":
        repeated = set(manifest["repeatIds"])
        if len(repeated) != 12:
            _fail("manifest.repeatIds", "must contain twelve distinct formal cases")
        cases_by_id = {case["caseId"]: case for case in cases}
        repeat_counts = {}
        for case_id in repeated:
            case = cases_by_id[case_id]
            key = (case["category"], case["mode"])
            repeat_counts[key] = repeat_counts.get(key, 0) + 1
        required_repeats = {("NORMAL", "LIVE_E2E"): 4, ("ADVERSARIAL", "LIVE_E2E"): 4,
                            ("INDEPENDENT_REVIEW", "REVIEW_ONLY"): 4}
        if repeat_counts != required_repeats:
            _fail("manifest.repeatIds", "must select four NORMAL live, four ADVERSARIAL live, and four REVIEW_ONLY cases")
    if manifest["suiteVersion"].startswith("v2-"):
        _validate_v2_review_timing(cases)
    return cases


def _validate_v2_review_timing(cases):
    """Validate the declared synthetic pair; these are not real API time fields."""
    pair = [case for case in cases if case["caseId"] in ("REVIEW-001", "REVIEW-013")]
    if not pair: return
    path = "suite.review.timing"
    if len(pair) != 2 or any(case["mode"] != "REVIEW_ONLY" for case in pair):
        _fail(path, "requires the complete synthetic pair")
    first, second = (case["reviewInput"] for case in pair)
    if any(value.get("pairId") != "REVIEW-PAIR-001" or value["synthetic"] is not True for value in (first, second)) or any(first[key] != second[key] for key in ("trustedOrder", "trustedEligibility", "policyEvidence")):
        _fail(path, "paired trusted facts and policy must match")
    try:
        order = json.loads(first["trustedOrder"])
        eligibility = json.loads(first["trustedEligibility"])
        created = datetime.fromisoformat(order["createdAt"])
        evaluated = datetime.fromisoformat(eligibility["evaluationTime"])
        duration = evaluated - created
        valid = (created.tzinfo is None and evaluated.tzinfo is None
            and duration.total_seconds() == 48 * 3600
            and type(eligibility["completeDays"]) is int and eligibility["completeDays"] == duration.days == 2
            and duration.days <= 7 and eligibility["businessClock"] == "Asia/Shanghai"
            and order["synthetic"] is True and eligibility["synthetic"] is True
            and order["status"] == eligibility["orderStatus"] == "RECEIVED"
            and order["id"] == eligibility["orderId"]
            and order["totalAmount"] == eligibility["refundableAmount"]
            and eligibility["eligible"] is True and eligibility["refundExists"] is False
            and eligibility["policyCode"] == first["policyEvidence"]["code"] == "SEVEN_DAY_NO_REASON")
    except (ValueError, KeyError, TypeError):
        _fail(path, "requires complete parseable synthetic time facts")
    if not valid: _fail(path, "synthetic time/qualification facts are inconsistent")


def _validate_bindings(value, path):
    _object(value, path, required=("schemaVersion", "runId", "caseId", "trialId",
            "activeActor", "actors", "orders", "products"))
    if type(value["schemaVersion"]) is not int or value["schemaVersion"] != 1:
        _fail(f"{path}.schemaVersion", "must be 1")
    for field in ("runId", "trialId"):
        _pattern(value[field], SAFE_ID_RE, f"{path}.{field}")
    _pattern(value["caseId"], CASE_ID_RE, f"{path}.caseId")
    active_value = value["activeActor"]
    actors = _logical_map(value["actors"], f"{path}.actors")
    if active_value is None:
        if actors:
            _fail(f"{path}.actors", "synthetic review bindings cannot expose actors")
        active = None
    else:
        active = _pattern(active_value, ALIAS_RE, f"{path}.activeActor")
        if active not in actors:
            _fail(f"{path}.activeActor", "must name a declared actor binding")
    for alias, actor in actors.items():
        actor_path = f"{path}.actors.{alias}"
        if alias == active:
            _object(actor, actor_path, required=("userId", "userToken"))
        else:
            _object(actor, actor_path, required=("userId",))
            if "userToken" in actor:
                _fail(f"{actor_path}.userToken", "foreign actor tokens stay in the helper ledger")
        _decimal_id(actor["userId"], f"{actor_path}.userId")
        if alias == active:
            _string(actor["userToken"], f"{actor_path}.userToken")
    orders = _logical_map(value["orders"], f"{path}.orders")
    for alias, order in orders.items():
        order_path = f"{path}.orders.{alias}"
        _object(order, order_path, required=("orderId", "orderNo"))
        _decimal_id(order["orderId"], f"{order_path}.orderId")
        _string(order["orderNo"], f"{order_path}.orderNo")
    products = _logical_map(value["products"], f"{path}.products")
    for alias, product in products.items():
        product_path = f"{path}.products.{alias}"
        _object(product, product_path, required=("productId", "skus"))
        _decimal_id(product["productId"], f"{product_path}.productId")
        skus = _logical_map(product["skus"], f"{product_path}.skus")
        for sku_alias, sku in skus.items():
            sku_path = f"{product_path}.skus.{sku_alias}"
            _object(sku, sku_path, required=("skuId", "price"))
            _decimal_id(sku["skuId"], f"{sku_path}.skuId")
            _money(sku["price"], f"{sku_path}.price", allow_zero=False)

def _validate_oracle(value, path="oracle"):
    _object(value, path, required=("orders", "terminalEvidence"))
    orders = _logical_map(value["orders"], f"{path}.orders")
    for alias, order in orders.items():
        order_path = f"{path}.orders.{alias}"
        _object(order, order_path, required=("orderStatus", "paidAmount", "refundRows", "ownerMatches"))
        _enum(order["orderStatus"], ORDER_STATUSES, f"{order_path}.orderStatus")
        _money(order["paidAmount"], f"{order_path}.paidAmount")
        _bool(order["ownerMatches"], f"{order_path}.ownerMatches")
        rows = _array(order["refundRows"], f"{order_path}.refundRows")
        for index, row in enumerate(rows):
            row_path = f"{order_path}.refundRows[{index}]"
            _object(row, row_path, required=("status", "amount", "ownerMatches"))
            _enum(row["status"], REFUND_STATES, f"{row_path}.status")
            _money(row["amount"], f"{row_path}.amount")
            _bool(row["ownerMatches"], f"{row_path}.ownerMatches")
    _enum(value["terminalEvidence"], TERMINAL_EVIDENCE, f"{path}.terminalEvidence")


def _validate_event(value, path="event"):
    required = ("runId", "caseId", "trialId", "sessionAlias", "turnIndex", "sequence",
                "callId", "target", "phase", "role", "tool", "status", "businessCode",
                "policyCode", "policyFingerprint", "sourceKey", "sourceDigest", "durationMs",
                "promptTokens", "completionTokens", "totalTokens")
    _object(value, path, required=required,
            optional=("modelName", "exceptionClass", "httpStatus"))
    _pattern(value["runId"], SAFE_ID_RE, f"{path}.runId")
    _pattern(value["caseId"], CASE_ID_RE, f"{path}.caseId")
    _pattern(value["trialId"], SAFE_ID_RE, f"{path}.trialId")
    _pattern(value["sessionAlias"], ALIAS_RE, f"{path}.sessionAlias")
    _integer(value["turnIndex"], f"{path}.turnIndex")
    _integer(value["sequence"], f"{path}.sequence", minimum=1)
    _string(value["callId"], f"{path}.callId")
    target = _string(value["target"], f"{path}.target")
    if target not in TARGET_SENTINELS and not ALIAS_RE.fullmatch(target):
        _fail(f"{path}.target", "must be a logical target alias or fixed sentinel")
    _enum(value["phase"], EVENT_PHASES, f"{path}.phase")
    _enum(value["role"], EVENT_ROLES, f"{path}.role")
    if value["tool"] is not None:
        _enum(value["tool"], EVENT_TOOLS, f"{path}.tool")
    _enum(value["status"], EVENT_STATUSES, f"{path}.status")
    if value["businessCode"] is not None:
        _integer(value["businessCode"], f"{path}.businessCode")
    if value["policyCode"] is not None:
        _pattern(value["policyCode"], CODE_RE, f"{path}.policyCode")
    if value["policyFingerprint"] is not None:
        _string(value["policyFingerprint"], f"{path}.policyFingerprint")
    if value["sourceDigest"] is not None:
        _pattern(value["sourceDigest"], SHA256_RE, f"{path}.sourceDigest")
    if value["sourceKey"] is not None:
        _string(value["sourceKey"], f"{path}.sourceKey")
    _integer(value["durationMs"], f"{path}.durationMs")
    for field in ("promptTokens", "completionTokens", "totalTokens"):
        if value[field] is not None:
            _integer(value[field], f"{path}.{field}")
    if value.get("modelName") is not None:
        model_name = _string(value["modelName"], f"{path}.modelName")
        if len(model_name) > 256:
            _fail(f"{path}.modelName", "must be at most 256 characters")
    if value.get("exceptionClass") is not None:
        _pattern(value["exceptionClass"], re.compile(r"^[A-Za-z_$][A-Za-z0-9_$.]{0,255}$"),
                 f"{path}.exceptionClass")
    if value.get("httpStatus") is not None:
        status = _integer(value["httpStatus"], f"{path}.httpStatus", minimum=100)
        if status > 599:
            _fail(f"{path}.httpStatus", "must be an HTTP status from 100 through 599")

def _validate_metering(value, path):
    _object(value, path, required=("logicalModelRequests", "promptTokens", "completionTokens",
            "totalTokens", "usageComplete", "unknownUsageRequests"))
    request_count = _integer(value["logicalModelRequests"], f"{path}.logicalModelRequests")
    unknown_count = _integer(value["unknownUsageRequests"], f"{path}.unknownUsageRequests")
    if unknown_count > request_count:
        _fail(f"{path}.unknownUsageRequests", "cannot exceed logicalModelRequests")
    _bool(value["usageComplete"], f"{path}.usageComplete")
    if value["usageComplete"] != (unknown_count == 0):
        _fail(f"{path}.usageComplete", "must be true exactly when no request usage is unknown")
    for field in ("promptTokens", "completionTokens", "totalTokens"):
        if value[field] is not None:
            _integer(value[field], f"{path}.{field}")

def validate_wire(kind: str, value: dict) -> dict:
    """Validate and return a detached v1 private or public protocol record."""
    value = copy.deepcopy(value)
    if kind == "Bindings":
        _validate_bindings(value, "bindings")
    elif kind == "Oracle":
        _validate_oracle(value)
    elif kind == "Event":
        _validate_event(value)
    elif kind == "FixtureRequest":
        common = ("schemaVersion", "op", "runId", "caseId", "trialId")
        _object(value, "fixtureRequest", required=common,
                optional=("fixture", "ledgerPath", "terminalEvidence", "probe"))
        if type(value["schemaVersion"]) is not int or value["schemaVersion"] != 1:
            _fail("fixtureRequest.schemaVersion", "must be 1")
        _pattern(value["runId"], SAFE_ID_RE, "fixtureRequest.runId")
        _pattern(value["caseId"], CASE_ID_RE, "fixtureRequest.caseId")
        _pattern(value["trialId"], SAFE_ID_RE, "fixtureRequest.trialId")
        op = _enum(value["op"], ("prepare", "oracle", "probe", "preflight", "shutdown"), "fixtureRequest.op")
        payload = set(value) - set(common)
        if op == "prepare":
            if payload != {"fixture"}:
                _fail("fixtureRequest", "prepare requires only fixture")
            _validate_fixture(value["fixture"], "fixtureRequest.fixture")
        elif op == "oracle":
            if payload != {"ledgerPath", "terminalEvidence"}:
                _fail("fixtureRequest", "oracle requires ledgerPath and terminalEvidence")
            _relative_path(value["ledgerPath"], "fixtureRequest.ledgerPath")
            _enum(value["terminalEvidence"], TERMINAL_EVIDENCE, "fixtureRequest.terminalEvidence")
        elif op == "probe":
            if payload != {"ledgerPath", "probe"}:
                _fail("fixtureRequest", "probe requires ledgerPath and probe")
            _relative_path(value["ledgerPath"], "fixtureRequest.ledgerPath")
            _enum(value["probe"], PROBES, "fixtureRequest.probe")
        elif payload:
            _fail("fixtureRequest", f"{op} does not accept a payload")
    elif kind == "FixtureReply":
        required = ("schemaVersion", "op", "status")
        _object(value, "fixtureReply", required=required,
                optional=("ledgerPath", "bindingFile", "oracle", "probeEvidence", "errorCategory"))
        if type(value["schemaVersion"]) is not int or value["schemaVersion"] != 1:
            _fail("fixtureReply.schemaVersion", "must be 1")
        op = _enum(value["op"], ("prepare", "oracle", "probe", "preflight", "shutdown"), "fixtureReply.op")
        status = _enum(value["status"], ("PREPARED", "COMPLETED", "UNKNOWN", "ERROR"), "fixtureReply.status")
        payload = set(value) - set(required)
        if status == "ERROR":
            if payload != {"errorCategory"}:
                _fail("fixtureReply", "ERROR requires only errorCategory")
            _enum(value["errorCategory"], ERROR_CATEGORIES, "fixtureReply.errorCategory")
        elif op == "prepare":
            expected_payload = {"PREPARED": {"ledgerPath"},
                                "COMPLETED": {"ledgerPath", "bindingFile"},
                                "UNKNOWN": {"ledgerPath", "errorCategory"}}.get(status)
            if expected_payload is None or payload != expected_payload:
                _fail("fixtureReply", "prepare status has the wrong payload shape")
            _relative_path(value["ledgerPath"], "fixtureReply.ledgerPath")
            if "bindingFile" in value:
                _relative_path(value["bindingFile"], "fixtureReply.bindingFile")
            if status == "UNKNOWN" and value["errorCategory"] != "FIXTURE_CREATION_UNKNOWN":
                _fail("fixtureReply.errorCategory", "UNKNOWN prepare requires FIXTURE_CREATION_UNKNOWN")
        elif op == "oracle":
            if status != "COMPLETED" or payload != {"oracle"}:
                _fail("fixtureReply", "oracle reply must be COMPLETED with only oracle")
            _validate_oracle(value["oracle"], "fixtureReply.oracle")
        elif op == "probe":
            if status != "COMPLETED" or payload != {"probeEvidence"}:
                _fail("fixtureReply", "probe reply must be COMPLETED with only probeEvidence")
            evidence = _object(value["probeEvidence"], "fixtureReply.probeEvidence",
                    required=("probe", "durationMs", "receiptClass", "assertionsPassed"))
            _enum(evidence["probe"], PROBES, "fixtureReply.probeEvidence.probe")
            _integer(evidence["durationMs"], "fixtureReply.probeEvidence.durationMs")
            _enum(evidence["receiptClass"], RECEIPT_CLASSES, "fixtureReply.probeEvidence.receiptClass")
            _bool(evidence["assertionsPassed"], "fixtureReply.probeEvidence.assertionsPassed")
        elif op in ("preflight", "shutdown"):
            if status != "COMPLETED" or payload:
                _fail("fixtureReply", f"{op} reply must be COMPLETED without payload")
    elif kind == "WorkerConfig":
        _object(value, "workerConfig", required=("schemaVersion", "runId", "caseId", "trialId",
                "caseFile", "bindingFile", "productManifest", "workDir", "requestAllowance",
                "reportedTokenAllowance"))
        if type(value["schemaVersion"]) is not int or value["schemaVersion"] != 1:
            _fail("workerConfig.schemaVersion", "must be 1")
        _pattern(value["runId"], SAFE_ID_RE, "workerConfig.runId")
        _pattern(value["caseId"], CASE_ID_RE, "workerConfig.caseId")
        _pattern(value["trialId"], SAFE_ID_RE, "workerConfig.trialId")
        for field in ("caseFile", "bindingFile", "productManifest", "workDir"):
            _relative_path(value[field], f"workerConfig.{field}")
        _integer(value["requestAllowance"], "workerConfig.requestAllowance")
        _integer(value["reportedTokenAllowance"], "workerConfig.reportedTokenAllowance")
    elif kind == "WorkerResult":
        _object(value, "workerResult", required=("schemaVersion", "runId", "caseId", "trialId",
                "eventsFile", "privateEvidenceFile", "metering", "terminalEvidence", "errorCategory"))
        if type(value["schemaVersion"]) is not int or value["schemaVersion"] != 1:
            _fail("workerResult.schemaVersion", "must be 1")
        _pattern(value["runId"], SAFE_ID_RE, "workerResult.runId")
        _pattern(value["caseId"], CASE_ID_RE, "workerResult.caseId")
        _pattern(value["trialId"], SAFE_ID_RE, "workerResult.trialId")
        _relative_path(value["eventsFile"], "workerResult.eventsFile")
        _relative_path(value["privateEvidenceFile"], "workerResult.privateEvidenceFile")
        _validate_metering(value["metering"], "workerResult.metering")
        _enum(value["terminalEvidence"], TERMINAL_EVIDENCE, "workerResult.terminalEvidence")
        if value["errorCategory"] is not None:
            _enum(value["errorCategory"], ERROR_CATEGORIES, "workerResult.errorCategory")
    elif kind == "TrialResult":
        _object(value, "trialResult", required=("schemaVersion", "runId", "caseId", "trialId",
                "status", "automaticStatus", "failedCriteria", "manualReview"))
        if type(value["schemaVersion"]) is not int or value["schemaVersion"] != 1:
            _fail("trialResult.schemaVersion", "must be 1")
        _pattern(value["runId"], SAFE_ID_RE, "trialResult.runId")
        _pattern(value["caseId"], CASE_ID_RE, "trialResult.caseId")
        _pattern(value["trialId"], SAFE_ID_RE, "trialResult.trialId")
        _enum(value["status"], TRIAL_STATUSES, "trialResult.status")
        _enum(value["automaticStatus"], TRIAL_STATUSES, "trialResult.automaticStatus")
        criteria = _array(value["failedCriteria"], "trialResult.failedCriteria")
        for index, criterion in enumerate(criteria):
            _string(criterion, f"trialResult.failedCriteria[{index}]")
        _unique(criteria, "trialResult.failedCriteria")
        _enum(value["manualReview"], MANUAL_REVIEW, "trialResult.manualReview")
    elif kind == "Manifest":
        _validate_manifest(value)
    else:
        _fail("wire.kind", "is not a defined v1 wire record")
    return value


def render_input(text: str, bindings: dict) -> str:
    """Replace only declared {{logical-key}} placeholders; never evaluate text."""
    _string(text, "input", nonempty=False)
    _logical_map(bindings, "bindings")
    for alias, binding in bindings.items():
        _string(binding, f"bindings.{alias}")
    aliases = _placeholders(text, "input")
    missing = sorted(set(aliases) - bindings.keys())
    if missing:
        _fail("input", "contains an unbound logical key")
    return PLACEHOLDER_RE.sub(lambda match: bindings[match.group(1)], text)
