package com.mall.agent.knowledge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Loads and validates the versioned FAQ documents shipped with the agent. */
public final class FaqCorpus {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final int FAQ_COUNT = 32;

    private FaqCorpus() { }

    public record FaqDocument(String id, String title, String body, String basis, String reviewedAt) { }

    public static List<FaqDocument> load() {
        try (InputStream input = FaqCorpus.class.getResourceAsStream("/corpus/faq.json")) {
            if (input == null) throw new IllegalStateException("FAQ corpus resource is missing");
            return parse(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("FAQ corpus could not be read", e);
        }
    }

    static List<FaqDocument> parse(String json) {
        final JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("FAQ corpus is not valid JSON", e);
        }
        if (root == null || !root.isArray() || root.size() != FAQ_COUNT) {
            throw new IllegalArgumentException("FAQ corpus must contain exactly 32 entries");
        }

        List<FaqDocument> documents = new ArrayList<>(FAQ_COUNT);
        for (int index = 0; index < root.size(); index++) {
            JsonNode item = root.get(index);
            if (!item.isObject()) throw new IllegalArgumentException("FAQ entry must be an object");

            String expectedId = "FAQ-%03d".formatted(index + 1);
            String id = requiredText(item, "id");
            if (!expectedId.equals(id)) {
                throw new IllegalArgumentException("FAQ IDs must be continuous from FAQ-001 to FAQ-032");
            }
            String title = requiredText(item, "title");
            String body = requiredText(item, "body");
            String basis = requiredText(item, "basis");
            String reviewedAt = requiredText(item, "reviewedAt");
            try {
                LocalDate.parse(reviewedAt);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("FAQ review date must use ISO local-date format", e);
            }
            documents.add(new FaqDocument(id, title, body, basis, reviewedAt));
        }
        return List.copyOf(documents);
    }

    private static String requiredText(JsonNode item, String field) {
        JsonNode value = item.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("FAQ entry is missing nonblank field: " + field);
        }
        return value.textValue().strip();
    }
}
