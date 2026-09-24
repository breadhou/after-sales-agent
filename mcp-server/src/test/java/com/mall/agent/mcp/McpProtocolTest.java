package com.mall.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the registered tools through the SDK's actual stdio JSON-RPC transport. */
class McpProtocolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private FakeSupermall fake;
    private ProtocolClient protocol;

    @BeforeEach
    void setUp() throws Exception {
        fake = new FakeSupermall();
        protocol = new ProtocolClient(new SupermallClient(fake.baseUrl(), "test-token"));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (protocol != null) {
            protocol.close();
        }
        if (fake != null) {
            fake.close();
        }
    }

    @Test
    void toolsListAdvertisesTheSixRegisteredTools() throws Exception {
        JsonNode response = protocol.request("tools/list", "{}");
        JsonNode tools = response.path("result").path("tools");

        assertTrue(tools.isArray());
        assertEquals(6, tools.size());
        Set<String> names = new HashSet<>();
        for (JsonNode tool : tools) {
            names.add(tool.path("name").asText());
            assertEquals("object", tool.path("inputSchema").path("type").asText());
        }
        assertEquals(Set.of("get_order", "list_user_orders", "get_logistics",
                "get_refund_eligibility", "list_policy_clauses", "submit_refund"), names);
        assertTrue(fake.receivedPaths.isEmpty());
    }

    @Test
    void allRegisteredHandlersReachTheirIntendedBackendRoutes() throws Exception {
        List<JsonNode> results = new ArrayList<>();
        results.add(protocol.call("get_order", "{\"orderId\":9001}"));
        results.add(protocol.call("list_user_orders", "{\"status\":\"RECEIVED\"}"));
        results.add(protocol.call("get_logistics", "{\"orderId\":9001}"));
        results.add(protocol.call("get_refund_eligibility", "{\"orderId\":9001}"));
        results.add(protocol.call("list_policy_clauses", "{}"));
        results.add(protocol.call("submit_refund", "{\"orderId\":9001,\"reason\":\"changed my mind\"}"));

        for (JsonNode result : results) {
            assertFalse(result.path("isError").asBoolean(true), result.toString());
            assertTrue(MAPPER.readTree(result.path("content").get(0).path("text").asText()).isObject());
        }
        assertEquals(List.of("/api/orders/9001", "/api/orders", "/api/orders/9001/logistics",
                "/api/orders/9001/refund-eligibility", "/api/after-sales/policies",
                "/api/orders/9001/refund/execute"), fake.receivedPaths);
        assertEquals("/api/orders?pageNum=1&pageSize=20&status=RECEIVED", fake.receivedRequestTargets.get(1));
        assertEquals("POST", fake.receivedMethods.get(5));
        assertEquals("changed my mind", MAPPER.readTree(fake.lastRequestBody).path("reason").asText());
        assertEquals(1, MAPPER.readTree(fake.lastRequestBody).size());
    }

    @Test
    void invalidOrderIdsReturnLocalToolErrorsWithoutAnyBackendRequest() throws Exception {
        for (String arguments : List.of("null", "{}", "{\"orderId\":null}", "{\"orderId\":0}",
                "{\"orderId\":-1}", "{\"orderId\":1.0}", "{\"orderId\":1.5}",
                "{\"orderId\":9223372036854775808}", "{\"orderId\":\"secret-9001\"}",
                "{\"orderId\":true}", "{\"orderId\":{}}")) {
            JsonNode result = protocol.call("get_order", arguments);
            assertLocalArgumentError(result);
            assertFalse(result.toString().contains("secret-9001"));
        }
        assertTrue(fake.receivedPaths.isEmpty());
    }

    @Test
    void maxLongOrderIdIsPreservedExactlyAtTheBackendBoundary() throws Exception {
        JsonNode result = protocol.call("get_order", "{\"orderId\":9223372036854775807}");

        assertTrue(result.path("isError").asBoolean());
        assertEquals(50000, resultCode(result));
        assertEquals(List.of("/api/orders/9223372036854775807"), fake.receivedPaths);
    }

    @Test
    void invalidReasonsAndExtraAmountNeverSubmitARefund() throws Exception {
        for (String arguments : List.of("{\"orderId\":9001}",
                "{\"orderId\":9001,\"reason\":null}",
                "{\"orderId\":9001,\"reason\":\"\"}",
                "{\"orderId\":9001,\"reason\":\"   \"}",
                "{\"orderId\":9001,\"reason\":42}",
                "{\"orderId\":9001,\"reason\":true}",
                "{\"orderId\":9001,\"reason\":" + MAPPER.writeValueAsString("x".repeat(513)) + "}",
                "{\"orderId\":9001,\"reason\":\"ok\",\"amount\":999}")) {
            assertLocalArgumentError(protocol.call("submit_refund", arguments));
        }
        assertTrue(fake.receivedPaths.isEmpty());
    }

    @Test
    void optionalStatusMustBeAStringAndUnknownFieldsAreRejected() throws Exception {
        assertLocalArgumentError(protocol.call("list_user_orders", "{\"status\":17}"));
        assertLocalArgumentError(protocol.call("list_user_orders", "{\"status\":true}"));
        assertLocalArgumentError(protocol.call("list_policy_clauses", "{\"unexpected\":1}"));
        assertTrue(fake.receivedPaths.isEmpty());
    }

    @Test
    void explicitNullStatusIsRejectedBeforeListingOrders() throws Exception {
        assertLocalArgumentError(protocol.call("list_user_orders", "{\"status\":null}"));
        assertTrue(fake.receivedPaths.isEmpty());
    }

    @Test
    void nonObjectToolArgumentsReturnLocalErrorsWithoutLeakingConversionExceptions() throws Exception {
        for (String arguments : List.of("[]", "[1]", "42", "\"secret-value\"")) {
            JsonNode result = protocol.call("get_order", arguments);
            assertLocalArgumentError(result);
            assertFalse(result.toString().contains("secret-value"));
        }
        assertTrue(fake.receivedPaths.isEmpty());
    }

    @Test
    void backendBusinessFailureBecomesAnErrorToolResult() throws Exception {
        fake.respondWith(200, "{\"code\":80001,\"message\":\"订单不满足退款条件\",\"data\":null}");

        JsonNode result = protocol.call("submit_refund", "{\"orderId\":9001,\"reason\":\"changed my mind\"}");

        assertTrue(result.path("isError").asBoolean());
        assertEquals(80001, resultCode(result));
        assertEquals("订单不满足退款条件", resultText(result).path("message").asText());
        assertEquals(List.of("/api/orders/9001/refund/execute"), fake.receivedPaths);
    }

    private static void assertLocalArgumentError(JsonNode result) throws Exception {
        assertTrue(result.path("isError").asBoolean(), result.toString());
        assertEquals(10000, resultCode(result));
        assertTrue(resultText(result).path("message").asText().startsWith("参数不合法"));
        assertFalse(result.toString().contains("Exception"));
    }

    private static int resultCode(JsonNode result) throws Exception {
        return resultText(result).path("code").asInt();
    }

    private static JsonNode resultText(JsonNode result) throws Exception {
        return MAPPER.readTree(result.path("content").get(0).path("text").asText());
    }

    private static final class ProtocolClient implements AutoCloseable {
        private final PipedInputStream serverInput = new PipedInputStream();
        private final PipedOutputStream clientOutput;
        private final PipedOutputStream serverOutput = new PipedOutputStream();
        private final PipedInputStream clientInput;
        private final BufferedWriter writer;
        private final BufferedReader reader;
        private final ExecutorService readerThread = Executors.newSingleThreadExecutor();
        private final McpSyncServer server;
        private int nextId = 1;

        private ProtocolClient(SupermallClient backend) throws Exception {
            clientOutput = new PipedOutputStream(serverInput);
            clientInput = new PipedInputStream(serverOutput);
            writer = new BufferedWriter(new OutputStreamWriter(clientOutput, StandardCharsets.UTF_8));
            reader = new BufferedReader(new InputStreamReader(clientInput, StandardCharsets.UTF_8));
            server = McpServerMain.startServer(
                    new StdioServerTransportProvider(MAPPER, serverInput, serverOutput), backend);
            JsonNode initialized = request("initialize", "{\"protocolVersion\":\"2024-11-05\","
                    + "\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}");
            assertEquals("2024-11-05", initialized.path("result").path("protocolVersion").asText());
            send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        }

        private JsonNode call(String name, String arguments) throws Exception {
            JsonNode response = request("tools/call", "{\"name\":" + MAPPER.writeValueAsString(name)
                    + ",\"arguments\":" + arguments + "}");
            assertFalse(response.has("error"), response.toString());
            return response.path("result");
        }

        private JsonNode request(String method, String params) throws Exception {
            int id = nextId++;
            send("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":" + MAPPER.writeValueAsString(method)
                    + ",\"params\":" + params + "}");
            String line = CompletableFuture.supplyAsync(() -> {
                try {
                    return reader.readLine();
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            }, readerThread).get(10, TimeUnit.SECONDS);
            assertNotNull(line);
            JsonNode response = MAPPER.readTree(line);
            assertEquals(id, response.path("id").asInt(), line);
            return response;
        }

        private void send(String message) throws IOException {
            writer.write(message);
            writer.newLine();
            writer.flush();
        }

        @Override
        public void close() throws Exception {
            writer.close();
            server.close();
            reader.close();
            readerThread.shutdownNow();
        }
    }
}
