package com.mall.agent.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * HTTP client for supermall's {@code {code, message, data}} response envelope.
 *
 * <p>The caller supplies the JWT. This client sends it only as an Authorization
 * header and returns the unwrapped {@code data} node on successful requests.</p>
 */
public class SupermallClient {

    private static final int CONNECTION_FAILURE_CODE = -2;
    private static final int HTTP_FAILURE_CODE = -3;
    private static final String UNAVAILABLE_MESSAGE = "售后系统暂时不可用，请稍后重试";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String token;
    private final HttpClient http;

    public SupermallClient(String baseUrl, String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("SUPERMALL_TOKEN 未设置。MCP server 需要用户令牌才能调用 supermall");
        }
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public JsonNode get(String path) {
        return exchange(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build());
    }

    public JsonNode post(String path, String jsonBody) {
        return exchange(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json; charset=UTF-8")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build());
    }

    private JsonNode exchange(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new SupermallException(CONNECTION_FAILURE_CODE, UNAVAILABLE_MESSAGE);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SupermallException(CONNECTION_FAILURE_CODE, UNAVAILABLE_MESSAGE);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new SupermallException(HTTP_FAILURE_CODE, UNAVAILABLE_MESSAGE);
        }

        JsonNode envelope;
        try {
            envelope = MAPPER.readTree(response.body());
        } catch (JsonProcessingException exception) {
            throw new SupermallException(HTTP_FAILURE_CODE, UNAVAILABLE_MESSAGE);
        }

        int code = envelope.path("code").asInt(-1);
        if (code != 0) {
            JsonNode message = envelope.get("message");
            throw new SupermallException(code,
                    message != null && message.isTextual() ? message.textValue() : "未知错误");
        }
        return envelope.path("data");
    }
}
