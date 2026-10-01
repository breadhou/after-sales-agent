package com.mall.agent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Retains only ID-to-alias projections, never actor tokens or evaluator expectations. */
public final class BindingIndex {
    private final Map<Long, String> orders;
    private final Map<Long, String> products;

    private BindingIndex(Map<Long, String> orders, Map<Long, String> products) {
        this.orders = Map.copyOf(orders);
        this.products = Map.copyOf(products);
    }

    public static BindingIndex fromPrivateJson(JsonNode document) {
        if (document == null || !document.isObject() || document.path("schemaVersion").asInt() != 1
                || !document.path("orders").isObject() || !document.path("products").isObject()) {
            throw new IllegalArgumentException("Invalid private bindings");
        }
        Set<String> fields = Set.of("schemaVersion", "runId", "caseId", "trialId", "activeActor", "actors", "orders", "products");
        document.fieldNames().forEachRemaining(name -> {
            if (!fields.contains(name)) throw new IllegalArgumentException("Unknown private binding field");
        });
        return new BindingIndex(project(document.path("orders"), "orderId"),
                project(document.path("products"), "productId"));
    }

    private static Map<Long, String> project(JsonNode rows, String idField) {
        Map<Long, String> result = new HashMap<>();
        rows.fields().forEachRemaining(row -> {
            String alias = row.getKey();
            JsonNode value = row.getValue().path(idField);
            if (!alias.matches("[a-z][a-z0-9-]{0,63}") || !value.isTextual()
                    || !value.textValue().matches("[1-9][0-9]*")) {
                throw new IllegalArgumentException("Invalid private target binding");
            }
            final long id;
            try { id = Long.parseLong(value.textValue()); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Target ID outside Java Long range"); }
            if (result.putIfAbsent(id, alias) != null) throw new IllegalArgumentException("Ambiguous target binding");
        });
        return result;
    }

    public String orderAlias(Long id) { return id == null ? "GLOBAL" : orders.getOrDefault(id, "UNBOUND"); }
    public String productAlias(Long id) { return id == null ? "GLOBAL" : products.getOrDefault(id, "OUT_OF_ALLOWLIST"); }

    String sourceKey(String raw) {
        if (raw != null && raw.startsWith("PRODUCT-")) {
            try {
                String alias = productAlias(Long.valueOf(raw.substring("PRODUCT-".length())));
                return alias.equals("OUT_OF_ALLOWLIST") ? null : "PRODUCT:" + alias;
            } catch (NumberFormatException ignored) { return null; }
        }
        return raw != null && raw.matches("(?:FAQ-[0-9]{3}|[A-Z][A-Z0-9_-]{0,63})") ? raw : null;
    }
}
