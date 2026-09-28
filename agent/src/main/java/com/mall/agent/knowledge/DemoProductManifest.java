package com.mall.agent.knowledge;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Loads the ignored logical-key to product-ID manifest; invalid input disables product answers. */
public final class DemoProductManifest {

    private static final JsonMapper MAPPER = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private DemoProductManifest() {
    }

    /**
     * Returns a validated immutable allowlist. Missing or invalid manifests become an empty
     * allowlist so optional product answers can be disabled without preventing refund startup.
     */
    public static Set<Long> load(Path path) {
        try {
            if (path == null || !Files.isRegularFile(path)) {
                return Set.of();
            }
            JsonNode root = MAPPER.readTree(Files.readString(path, StandardCharsets.UTF_8));
            if (root == null || !root.isObject() || root.isEmpty()) {
                return Set.of();
            }

            Set<Long> ids = new LinkedHashSet<>();
            for (var entry : root.properties()) {
                JsonNode value = entry.getValue();
                if (entry.getKey().isBlank() || !value.isIntegralNumber()
                        || !value.canConvertToLong()) {
                    return Set.of();
                }
                long id = value.longValue();
                if (id <= 0 || !ids.add(id)) {
                    return Set.of();
                }
            }
            return Collections.unmodifiableSet(ids);
        } catch (IOException | RuntimeException e) {
            return Set.of();
        }
    }
}
