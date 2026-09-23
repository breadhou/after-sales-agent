package com.mall.agent.mcp;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/** A real HTTP test double for supermall's Result envelope. */
public final class FakeSupermall implements AutoCloseable {

    private final HttpServer server;
    private final AtomicReference<Response> responseOverride = new AtomicReference<>();
    public final List<String> receivedAuthHeaders = new CopyOnWriteArrayList<>();
    public final List<String> receivedPaths = new CopyOnWriteArrayList<>();
    public final List<String> receivedMethods = new CopyOnWriteArrayList<>();
    public volatile String lastRequestBody;
    public volatile String lastContentType;

    public FakeSupermall() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            receivedAuthHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            receivedPaths.add(exchange.getRequestURI().getPath());
            receivedMethods.add(exchange.getRequestMethod());
            lastContentType = exchange.getRequestHeaders().getFirst("Content-Type");
            lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Response response = responseOverride.get();
            if (response == null) {
                response = defaultResponse(exchange.getRequestURI().getPath());
            }
            byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(response.status(), body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
    }

    public void respondWith(int status, String body) {
        responseOverride.set(new Response(status, body));
    }

    private Response defaultResponse(String path) {
        if (path.endsWith("/refund-eligibility")) {
            return success("{\"orderId\":9001,\"eligible\":true,\"reason\":null,"
                    + "\"policyCode\":\"SEVEN_DAY_NO_REASON\",\"policyTitle\":\"签收 7 天内整单退款\","
                    + "\"refundableAmount\":199.99,\"refundExists\":false}");
        }
        if (path.endsWith("/refund/execute")) {
            return success("{\"orderId\":9001,\"eligible\":true,\"reason\":null,"
                    + "\"policyCode\":\"SEVEN_DAY_NO_REASON\",\"policyTitle\":\"签收 7 天内整单退款\","
                    + "\"refundableAmount\":199.99,\"refundExists\":false}");
        }
        if (path.endsWith("/logistics")) {
            return success("{\"id\":30001,\"orderId\":9001,\"company\":\"SF Express\","
                    + "\"trackingNo\":\"SF1234567890\",\"status\":\"DELIVERED\","
                    + "\"createdAt\":\"2026-09-20T10:00:00\"}");
        }
        if ("/api/orders".equals(path)) {
            return success("{\"records\":[{\"id\":9001,\"orderNo\":\"SN9001\","
                    + "\"totalAmount\":199.99,\"status\":\"RECEIVED\","
                    + "\"createdAt\":\"2026-09-20T10:00:00\",\"itemCount\":1}],"
                    + "\"total\":1,\"size\":20,\"current\":1}");
        }
        if ("/api/after-sales/policies".equals(path)) {
            return success("{\"fingerprint\":\"fake-policy-catalog-v1\",\"clauses\":["
                    + "{\"code\":\"SEVEN_DAY_NO_REASON\",\"title\":\"已签收（完整天数不超过 7）整单退款\","
                    + "\"clauseText\":\"订单状态为已签收时，系统从订单创建时间起每满 24 小时计 1 天，不足 24 小时的余数不计；计数不超过 7 天可申请整单退款，退款执行后立即完成。\"},"
                    + "{\"code\":\"SHIPPED_NOT_RECEIVED\",\"title\":\"已发货或已送达整单退款\","
                    + "\"clauseText\":\"订单状态为已发货或已送达时，可申请整单退款；退款执行后立即完成。\"},"
                    + "{\"code\":\"QUALITY_ISSUE\",\"title\":\"已签收（完整天数超过 7）整单退款\","
                    + "\"clauseText\":\"订单状态为已签收时，系统从订单创建时间起每满 24 小时计 1 天，不足 24 小时的余数不计；计数超过 7 天仍可申请整单退款，退款执行后立即完成。\"}]}");
        }
        if ("/api/orders/9001".equals(path)) {
            return success("{\"id\":9001,\"orderNo\":\"SN9001\",\"userId\":10001,"
                    + "\"addressId\":20001,\"couponId\":null,\"totalAmount\":199.99,"
                    + "\"status\":\"RECEIVED\",\"createdAt\":\"2026-09-20T10:00:00\",\"items\":[]}");
        }
        return new Response(200, "{\"code\":50000,\"message\":\"订单不存在\",\"data\":null}");
    }

    private Response success(String data) {
        return new Response(200, "{\"code\":0,\"message\":\"成功\",\"data\":" + data + "}");
    }

    private record Response(int status, String body) {
    }

    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
