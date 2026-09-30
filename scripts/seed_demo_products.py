"""Create fictional demo products through supermall's merchant business API.

An in-flight marker deliberately blocks retries after an uncertain POST. Reconcile
the product through the merchant API/UI, then record its ID in the local manifest
before rerunning; never delete an unresolved marker to retry a creation.
"""

import argparse
import json
import os
import tempfile
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Callable, Mapping
from urllib.parse import urlsplit
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "data" / "demo-products.json"
MANIFEST = ROOT / "data" / "demo-products.local.json"
JOURNAL = ROOT / "data" / "demo-products.seed-journal.local.json"


def _atomic_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w", encoding="utf-8", dir=path.parent, prefix=f".{path.name}.",
            suffix=".tmp", delete=False,
        ) as stream:
            temporary = Path(stream.name)
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def _catalog(path: Path) -> list[dict]:
    products = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(products, list) or not 15 <= len(products) <= 35:
        raise ValueError("demo catalog must contain 15–35 products")
    keys = set()
    for item in products:
        if not isinstance(item, dict) or not isinstance(item.get("logicalKey"), str):
            raise ValueError("demo catalog has an invalid logical key")
        key = item["logicalKey"]
        if not key or key in keys:
            raise ValueError("demo catalog has a missing or duplicate logical key")
        keys.add(key)
        if not all(isinstance(item.get(field), str) and item[field].strip()
                   for field in ("name", "description")):
            raise ValueError(f"demo product {key} lacks name or description")
        if "演示" not in item["description"]:
            raise ValueError(f"demo product {key} lacks demo labeling")
        skus = item.get("skus")
        if not isinstance(skus, list) or len(skus) != 1:
            raise ValueError(f"demo product {key} needs exactly one SKU")
        sku = skus[0]
        try:
            price = Decimal(str(sku["price"]))
        except (KeyError, InvalidOperation, TypeError):
            raise ValueError(f"demo product {key} has invalid SKU price") from None
        stock = sku.get("stock")
        if (not price.is_finite() or price < Decimal("0.01")
                or type(stock) is not int or stock < 0
                or not isinstance(sku.get("specs"), str) or not sku["specs"].strip()):
            raise ValueError(f"demo product {key} has invalid SKU")
    return products


def _manifest(path: Path, catalog_keys: set[str]) -> dict[str, int]:
    if not path.exists():
        return {}
    entries = json.loads(path.read_text(encoding="utf-8"))
    if (not isinstance(entries, dict) or not set(entries).issubset(catalog_keys)
            or any(type(value) is not int or value <= 0 for value in entries.values())
            or len(set(entries.values())) != len(entries)):
        raise ValueError("local manifest is invalid; reconcile it before seeding")
    return entries


def _base_url(environment: Mapping[str, str]) -> str:
    value = environment.get("SUPERMALL_BASE_URL", "").rstrip("/")
    parsed = urlsplit(value)
    if (parsed.scheme not in ("http", "https") or not parsed.netloc
            or parsed.username or parsed.password or parsed.query or parsed.fragment):
        raise ValueError("SUPERMALL_BASE_URL must be a plain HTTP(S) server URL")
    return value


def seed_products(catalog_path: Path, manifest_path: Path, journal_path: Path,
                  category_id: int, environment: Mapping[str, str],
                  opener: Callable = urlopen) -> dict[str, int]:
    """Seed once per logical key; a recorded uncertain write requires reconciliation."""
    if type(category_id) is not int or category_id <= 0:
        raise ValueError("category-id must be a positive existing category ID")
    token = environment.get("DEMO_MERCHANT_TOKEN", "")
    if not token.strip() or token.strip().startswith("${"):
        raise ValueError("DEMO_MERCHANT_TOKEN is required for merchant writes")
    base_url = _base_url(environment)
    products = _catalog(catalog_path)
    manifest = _manifest(manifest_path, {item["logicalKey"] for item in products})

    if journal_path.exists():
        marker = json.loads(journal_path.read_text(encoding="utf-8"))
        key = marker.get("logicalKey") if isinstance(marker, dict) else None
        if key not in manifest:
            raise RuntimeError(
                "Unresolved merchant create; manual reconciliation through merchant API/UI "
                "then record its logical key and ID in the local manifest before retrying")
        journal_path.unlink()

    for product in products:
        key = product["logicalKey"]
        if key in manifest:
            continue
        payload = {field: product[field] for field in ("name", "description", "skus")}
        payload["categoryId"] = category_id
        payload["status"] = "ON_SHELF"
        _atomic_json(journal_path, {"logicalKey": key, "name": product["name"],
                                    "categoryId": category_id, "baseUrl": base_url})
        request = Request(
            f"{base_url}/api/merchant/products",
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            headers={"Authorization": f"Bearer {token}",
                     "Content-Type": "application/json; charset=utf-8"},
            method="POST",
        )
        try:
            with opener(request, timeout=15) as response:
                if response.status != 200:
                    raise ValueError("unexpected HTTP status")
                result = json.load(response)
            data = result.get("data") if isinstance(result, dict) else None
            product_id = data.get("id") if isinstance(data, dict) else None
            if (result.get("status") != "SUCCESS" or result.get("code") != 0
                    or type(product_id) is not int or product_id <= 0
                    or data.get("status") != "ON_SHELF"):
                raise ValueError("merchant response did not confirm an on-shelf product ID")
        except Exception:
            raise RuntimeError(
                "Merchant create outcome is uncertain; manual reconciliation through merchant API/UI "
                "and record its logical key and ID in the local manifest before retrying") from None
        manifest[key] = product_id
        _atomic_json(manifest_path, manifest)
        journal_path.unlink()
    return manifest


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Seed fictional demo products through merchant API")
    parser.add_argument("--category-id", required=True, type=int)
    args = parser.parse_args(argv)
    try:
        manifest = seed_products(CATALOG, MANIFEST, JOURNAL, args.category_id, os.environ)
    except (OSError, ValueError, RuntimeError, json.JSONDecodeError) as error:
        print(f"Seeding stopped: {error}")
        return 1
    print(f"Confirmed demo products in local manifest: {len(manifest)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
