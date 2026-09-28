import io
import json
import tempfile
import unittest
from pathlib import Path
from urllib.parse import urlparse

from scripts import seed_demo_products


CATALOG = Path(__file__).resolve().parents[1] / "data" / "demo-products.json"


class FakeResponse(io.BytesIO):
    def __init__(self, product_id, payload):
        envelope = {"status": "SUCCESS", "code": 0, "message": "成功",
                    "data": {"id": product_id, "merchantId": 55,
                             "name": payload["name"], "description": payload["description"],
                             "categoryId": payload["categoryId"],
                             "status": "ON_SHELF", "createdAt": "2026-09-28T12:00:00",
                             "skus": [{"id": product_id + 10000,
                                       **payload["skus"][0], "image": None}]},
                    "count": 0}
        super().__init__(json.dumps(envelope).encode("utf-8"))
        self.status = 200


class MerchantApiFake:
    def __init__(self, journal, manifest, timeout_after_create=False):
        self.journal = journal
        self.manifest = manifest
        self.initial_confirmed = len(json.loads(manifest.read_text(encoding="utf-8"))) if manifest.exists() else 0
        self.timeout_after_create = timeout_after_create
        self.requests = []
        self.created = []

    def __call__(self, request, timeout):
        self.requests.append(request)
        payload = json.loads(request.data)
        in_flight = json.loads(self.journal.read_text(encoding="utf-8"))
        assert in_flight["logicalKey"]
        assert in_flight["name"] == payload["name"]
        confirmed = json.loads(self.manifest.read_text(encoding="utf-8")) if self.manifest.exists() else {}
        assert len(confirmed) == self.initial_confirmed + len(self.created)
        product_id = 9000 + len(self.created)
        self.created.append((in_flight["logicalKey"], product_id))
        if self.timeout_after_create:
            raise TimeoutError("response lost after server creation: merchant-fake-token")
        return FakeResponse(product_id, payload)


class SeedDemoProductsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.manifest = self.root / "demo-products.local.json"
        self.journal = self.root / "demo-products.seed-journal.local.json"
        self.environment = {"DEMO_MERCHANT_TOKEN": "merchant-fake-token",
                            "SUPERMALL_TOKEN": "user-token-must-not-be-used",
                            "SUPERMALL_BASE_URL": "http://localhost:8080"}

    def seed(self, fake, category_id=7, environment=None):
        return seed_demo_products.seed_products(
            CATALOG, self.manifest, self.journal, category_id,
            self.environment if environment is None else environment, fake)

    def test_seedsOnlyThroughMerchantApi(self):
        fake = MerchantApiFake(self.journal, self.manifest)
        self.seed(fake)

        products = json.loads(CATALOG.read_text(encoding="utf-8"))
        self.assertEqual(21, len(products))
        self.assertEqual(21, len(fake.requests))
        self.assertEqual(21, len(json.loads(self.manifest.read_text(encoding="utf-8"))))
        self.assertFalse(self.journal.exists())
        self.assertEqual(21, len({item["logicalKey"] for item in products}))
        for request, item in zip(fake.requests, products):
            self.assertEqual("POST", request.get_method())
            self.assertEqual("/api/merchant/products", urlparse(request.full_url).path)
            self.assertEqual("Bearer merchant-fake-token", request.get_header("Authorization"))
            self.assertNotIn("user-token-must-not-be-used", str(request.headers))
            body = json.loads(request.data)
            self.assertEqual(7, body["categoryId"])
            self.assertEqual("ON_SHELF", body["status"])
            self.assertEqual(item["name"], body["name"])
            self.assertEqual(item["description"], body["description"])
            self.assertEqual(item["skus"], body["skus"])
            self.assertGreaterEqual(float(body["skus"][0]["price"]), 0.01)
            self.assertGreaterEqual(body["skus"][0]["stock"], 0)
            self.assertIn("演示", body["description"])

    def test_refusesMissingMerchantTokenOrCategory(self):
        fake = MerchantApiFake(self.journal, self.manifest)
        with self.assertRaisesRegex(ValueError, "DEMO_MERCHANT_TOKEN"):
            self.seed(fake, environment={"SUPERMALL_TOKEN": "only-user-token",
                                         "SUPERMALL_BASE_URL": "http://localhost:8080"})
        with self.assertRaisesRegex(ValueError, "category"):
            self.seed(fake, category_id=0)
        self.assertEqual([], fake.requests)
        self.assertFalse(self.manifest.exists())

    def test_manifestResumeSkipsCreatedLogicalKeys(self):
        already_created = {"paper_grid_a5": 8000, "paper_plain_a5": 8001,
                           "paper_dot_a5": 8002}
        self.manifest.write_text(json.dumps(already_created), encoding="utf-8")
        first = MerchantApiFake(self.journal, self.manifest)
        self.seed(first)
        second = MerchantApiFake(self.journal, self.manifest)
        self.seed(second)

        self.assertEqual(18, len(first.requests))
        self.assertEqual([], second.requests)
        manifest = json.loads(self.manifest.read_text(encoding="utf-8"))
        self.assertEqual(21, len(manifest))
        self.assertEqual(8000, manifest["paper_grid_a5"])

    def test_lostCreateResponseBlocksAutomaticRetry(self):
        first = MerchantApiFake(self.journal, self.manifest, timeout_after_create=True)
        with self.assertRaisesRegex(RuntimeError, "manual|人工") as stopped:
            self.seed(first)
        self.assertNotIn("merchant-fake-token", str(stopped.exception))
        self.assertEqual(1, len(first.created))
        self.assertTrue(self.journal.exists())
        self.assertFalse(self.manifest.exists())

        retry = MerchantApiFake(self.journal, self.manifest)
        with self.assertRaisesRegex(RuntimeError, "manual|人工"):
            self.seed(retry)
        self.assertEqual([], retry.requests)


if __name__ == "__main__":
    unittest.main()
