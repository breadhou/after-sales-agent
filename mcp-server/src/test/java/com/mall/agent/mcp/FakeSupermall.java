package com.mall.agent.mcp;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** A real HTTP test double for supermall's Result envelope. */
public final class FakeSupermall implements AutoCloseable {

    private final HttpServer server;
    public final List<String> receivedAuthHeaders = new ArrayList<>();
    public final List<String> receivedPaths = new ArrayList<>();
    public final List<String> receivedMethods = new ArrayList<>();
    private int responseStatus = 200;
    private String responseBody = "{\"code\":0,\"message\":\"成功\",\"data\":{\"id\":9001,\"status\":\"RECEIVED\"}}";

    public FakeSupermall() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            receivedAuthHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            receivedPaths.add(exchange.getRequestURI().getPath());
            receivedMethods.add(exchange.getRequestMethod());
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(responseStatus, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
    }

    public void respondWith(int status, String body) {
        responseStatus = status;
        responseBody = body;
    }

    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
