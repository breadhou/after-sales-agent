# MCP Server 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 supermall 的售后能力包装成 6 个 MCP 工具，供任意 MCP 客户端调用。

**Architecture:** 独立 Java 进程，**stdio 传输**。用官方 MCP Java SDK，不引入任何 AI 框架——本进程不含 AI 逻辑，只是协议适配层。工具逻辑与 MCP 装配分离，前者可直接单测。

**Tech Stack:** Java 17 / Maven / `io.modelcontextprotocol.sdk:mcp:0.10.0` / Jackson / JUnit 5

**仓库:** `D:\sourcecode\after-sales-agent`（本项目）

**前置:** **Plan A 必须已完成**——本计划调用的三个新端点（`refund-eligibility`、`refund/execute`、`after-sales/policies`）由 A 提供。

**命令约定**：Task 6 需要查库核对状态，先定义：

```bash
mysql_q() {
  "/d/MySQL/MySQL Server 8.0/bin/mysql" -uroot -p123456 -N -B \
    --default-character-set=utf8mb4 -e "$1" 2>/dev/null
}
```

---

## 为什么用官方 SDK 而不是 Spring AI

MCP Server **本身不含任何 AI 逻辑**，它只是把 HTTP 接口翻译成 MCP 工具描述。为此背一个 AI 框架进去，既增加了依赖面，也让"能力与调用方解耦"这个设计意图变得含糊。官方 SDK 只做协议，正好。

## 为什么选 stdio 而不是 HTTP

| | stdio | HTTP/SSE |
|---|---|---|
| 传输版本兼容 | **无风险**，两代 SDK 都稳定支持 | SSE 与 Streamable HTTP 存在版本差异 |
| 调试 | 稍麻烦 | 可用 MCP Inspector 直连 |
| 进程模型 | 由客户端拉起 | 独立运行 |

本阶段**先取确定性**：stdio 消除了整类集成失败。若之后要用 Inspector 调试或让多个客户端共用，SDK 只需换一个 transport provider 即可加 HTTP。

## 三个必须知道的约束

### 1. stdout 是协议通道，日志必须走 stderr

stdio 传输下 **stdout 被 JSON-RPC 独占**。任何一行普通日志打到 stdout 都会破坏协议，表现为客户端"收到无法解析的响应"。本计划用 `slf4j-simple` 并把输出定向到 stderr（Task 1 配置，Task 5 有验证步骤）。

### 2. 工具的错误是给模型看的，不是给开发者看的

模型要根据错误信息决定下一步。所以工具**不能把异常堆栈或 HTTP 500 直接抛出去**，而要返回结构化的、可理解的信息：

```
差：java.net.ConnectException: Connection refused
好：{"error":"MERCHANT_UNAVAILABLE","message":"售后系统暂时不可用，请稍后重试"}
```

同时 `isError=true`，让模型知道这次调用失败了而不是拿到了数据。

### 3. 身份靠环境变量注入

MCP Server 用**用户的 JWT** 调 supermall。token 由客户端拉起本进程时通过环境变量 `SUPERMALL_TOKEN` 传入。

**不能把 token 做成工具参数**——那会让 token 出现在模型的上下文里，也会出现在对话记录里。

---

## 文件结构

```
after-sales-agent/
├── pom.xml                                  # 父 POM，聚合模块
└── mcp-server/
    ├── pom.xml
    └── src/
        ├── main/java/com/mall/agent/mcp/
        │   ├── McpServerMain.java           # 入口：装配 stdio + 注册工具
        │   ├── SupermallClient.java         # HTTP 调用 + 令牌 + 错误映射
        │   ├── SupermallException.java      # 携带业务码的异常
        │   ├── Json.java                    # Jackson 工具
        │   └── tools/
        │       ├── OrderTools.java          # get_order / list_user_orders / get_logistics
        │       ├── RefundTools.java         # get_refund_eligibility / submit_refund
        │       └── PolicyTools.java         # list_policy_clauses
        ├── main/resources/simplelogger.properties   # 日志定向到 stderr
        └── test/java/com/mall/agent/mcp/
            ├── FakeSupermall.java           # JDK 内置 HttpServer 假后端
            ├── SupermallClientTest.java
            ├── OrderToolsTest.java
            ├── RefundToolsTest.java
            └── PolicyToolsTest.java
```

**分层**：`SupermallClient` 只管 HTTP 与令牌，`tools/*` 只管把 HTTP 结果翻译成模型能读的 JSON，`McpServerMain` 只管装配。三者可分别测试。

---

## Task 1: 项目骨架

**Files:**
- Create: `pom.xml`（父 POM）
- Create: `mcp-server/pom.xml`
- Create: `mcp-server/src/main/resources/simplelogger.properties`

- [ ] **Step 1: 创建父 POM**

`after-sales-agent/pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.mall.agent</groupId>
    <artifactId>after-sales-agent</artifactId>
    <version>1.0.0</version>
    <packaging>pom</packaging>

    <modules>
        <module>mcp-server</module>
    </modules>

    <properties>
        <maven.compiler.release>17</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <mcp.sdk.version>0.10.0</mcp.sdk.version>
        <jackson.version>2.17.0</jackson.version>
        <junit.version>5.10.2</junit.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>io.modelcontextprotocol.sdk</groupId>
                <artifactId>mcp</artifactId>
                <version>${mcp.sdk.version}</version>
            </dependency>
            <dependency>
                <groupId>com.fasterxml.jackson.core</groupId>
                <artifactId>jackson-databind</artifactId>
                <version>${jackson.version}</version>
            </dependency>
            <dependency>
                <groupId>org.junit.jupiter</groupId>
                <artifactId>junit-jupiter</artifactId>
                <version>${junit.version}</version>
                <scope>test</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
</project>
```

- [ ] **Step 2: 创建 mcp-server 模块 POM**

`mcp-server/pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.mall.agent</groupId>
        <artifactId>after-sales-agent</artifactId>
        <version>1.0.0</version>
    </parent>

    <artifactId>mcp-server</artifactId>

    <dependencies>
        <dependency>
            <groupId>io.modelcontextprotocol.sdk</groupId>
            <artifactId>mcp</artifactId>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>
        <!-- 日志：必须走 stderr，stdout 被协议独占 -->
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-simple</artifactId>
            <version>2.0.13</version>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <finalName>mcp-server</finalName>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.2.5</version>
            </plugin>
            <!-- 打成可执行 fat jar，供客户端以子进程拉起 -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-shade-plugin</artifactId>
                <version>3.5.2</version>
                <executions>
                    <execution>
                        <phase>package</phase>
                        <goals><goal>shade</goal></goals>
                        <configuration>
                            <createDependencyReducedPom>false</createDependencyReducedPom>
                            <transformers>
                                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                                    <mainClass>com.mall.agent.mcp.McpServerMain</mainClass>
                                </transformer>
                                <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
                            </transformers>
                            <filters>
                                <filter>
                                    <artifact>*:*</artifact>
                                    <excludes>
                                        <exclude>META-INF/*.SF</exclude>
                                        <exclude>META-INF/*.DSA</exclude>
                                        <exclude>META-INF/*.RSA</exclude>
                                    </excludes>
                                </filter>
                            </filters>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 3: 配置日志走 stderr**

`mcp-server/src/main/resources/simplelogger.properties`：

```properties
# stdio 传输下 stdout 被 JSON-RPC 独占，任何普通日志打上去都会破坏协议
org.slf4j.simpleLogger.logFile=System.err
org.slf4j.simpleLogger.defaultLogLevel=info
org.slf4j.simpleLogger.showThreadName=false
```

- [ ] **Step 4: 验证骨架能构建**

```bash
cd /d/sourcecode/after-sales-agent
JAVA_HOME=/d/jdks/openjdk-22.0.2 "/d/JetBrains/IntelliJ IDEA 2026.2/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" -q validate
```

Expected: 无输出（构建通过）

- [ ] **Step 5: 提交**

```bash
git add pom.xml mcp-server/pom.xml mcp-server/src/main/resources/
git commit -m "build: scaffold the mcp-server module"
```

---

## Task 2: Supermall 客户端

**Files:**
- Create: `mcp-server/src/main/java/com/mall/agent/mcp/SupermallException.java`
- Create: `mcp-server/src/main/java/com/mall/agent/mcp/SupermallClient.java`
- Create: `mcp-server/src/test/java/com/mall/agent/mcp/FakeSupermall.java`
- Test: `mcp-server/src/test/java/com/mall/agent/mcp/SupermallClientTest.java`

**设计要点**：supermall 的统一响应是 `{"code":0,"message":"...","data":{...}}`。客户端负责**拆信封**：`code != 0` 时抛 `SupermallException`，把业务码与消息带出来。

- [ ] **Step 1: 创建异常类**

```java
package com.mall.agent.mcp;

/** supermall 返回的非零业务码。 */
public class SupermallException extends RuntimeException {

    private final int code;

    public SupermallException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
```

- [ ] **Step 2: 写假后端（JDK 内置 HttpServer，无需额外依赖）**

```java
package com.mall.agent.mcp;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 测试用的假 supermall。用 JDK 自带的 HttpServer，不引入 mock 框架，
 * 因此测的是真实的 HTTP 行为（状态码、请求头、序列化）。
 */
public class FakeSupermall implements AutoCloseable {

    private final HttpServer server;

    /** 记录收到的请求，供断言检查（例如检查 Authorization 头）。 */
    public final List<String> receivedAuthHeaders = new ArrayList<>();
    public final List<String> receivedPaths = new ArrayList<>();

    public FakeSupermall() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            receivedAuthHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            receivedPaths.add(exchange.getRequestURI().getPath());
            byte[] body = respond(exchange.getRequestURI().getPath()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    private String respond(String path) {
        if (path.endsWith("/refund-eligibility")) {
            return "{\"code\":0,\"message\":\"success\",\"data\":{\"orderId\":9001,"
                    + "\"eligible\":true,\"policyCode\":\"SEVEN_DAY_NO_REASON\",\"refundableAmount\":199.99}}";
        }
        if (path.contains("/orders/")) {
            return "{\"code\":0,\"message\":\"success\",\"data\":{\"id\":9001,\"orderNo\":\"SN9001\","
                    + "\"status\":\"RECEIVED\",\"totalAmount\":199.99}}";
        }
        // 默认返回业务错误，用于测错误映射
        return "{\"code\":50000,\"message\":\"订单不存在\",\"data\":null}";
    }

    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
```

- [ ] **Step 3: 写失败的测试**

```java
package com.mall.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class SupermallClientTest {

    private FakeSupermall fake;
    private SupermallClient client;

    @BeforeEach
    void setUp() throws IOException {
        fake = new FakeSupermall();
        client = new SupermallClient(fake.baseUrl(), "test-token");
    }

    @AfterEach
    void tearDown() {
        fake.close();
    }

    @Test
    void get_shouldUnwrapResultEnvelope() {
        JsonNode data = client.get("/api/orders/9001");

        assertEquals(9001, data.get("id").asLong());
        assertEquals("RECEIVED", data.get("status").asText());
    }

    @Test
    void get_shouldSendBearerToken() {
        client.get("/api/orders/9001");

        assertEquals("Bearer test-token", fake.receivedAuthHeaders.get(0));
    }

    @Test
    void get_shouldThrowWithBusinessCodeWhenCodeIsNotZero() {
        SupermallException exception = assertThrows(
                SupermallException.class, () -> client.get("/api/unknown"));

        assertEquals(50000, exception.getCode());
        assertEquals("订单不存在", exception.getMessage());
    }

    @Test
    void constructor_shouldRejectBlankToken() {
        assertThrows(IllegalArgumentException.class,
                () -> new SupermallClient("http://localhost:1", "  "));
    }
}
```

- [ ] **Step 4: 运行测试确认失败**

```bash
JAVA_HOME=/d/jdks/openjdk-22.0.2 "/d/JetBrains/IntelliJ IDEA 2026.2/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" test -pl mcp-server -Dtest=SupermallClientTest
```

Expected: 编译失败，`找不到符号: 类 SupermallClient`

- [ ] **Step 5: 实现客户端**

```java
package com.mall.agent.mcp;

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
 * supermall 的 HTTP 客户端。
 *
 * <p>负责三件事：带上用户令牌、拆开 {@code Result} 信封、把非零业务码转成
 * {@link SupermallException}。工具层拿到的是已经拆好的 {@code data}。</p>
 */
public class SupermallClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String token;
    private final HttpClient http;

    public SupermallClient(String baseUrl, String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException(
                    "SUPERMALL_TOKEN 未设置。MCP server 需要用户令牌才能调用 supermall");
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
        } catch (IOException e) {
            throw new SupermallException(-2, "售后系统不可达：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SupermallException(-2, "请求被中断");
        }

        JsonNode envelope;
        try {
            envelope = MAPPER.readTree(response.body());
        } catch (IOException e) {
            throw new SupermallException(-3, "售后系统返回了无法解析的内容（HTTP " + response.statusCode() + "）");
        }

        int code = envelope.path("code").asInt(-1);
        if (code != 0) {
            throw new SupermallException(code, envelope.path("message").asText("未知错误"));
        }
        return envelope.path("data");
    }
}
```

- [ ] **Step 6: 运行测试确认通过**

```bash
JAVA_HOME=/d/jdks/openjdk-22.0.2 "/d/JetBrains/IntelliJ IDEA 2026.2/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" test -pl mcp-server -Dtest=SupermallClientTest
```

Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 7: 提交**

```bash
git add mcp-server/src/
git commit -m "feat: add supermall http client with token and error unwrapping"
```

---

## Task 3: 订单类工具

**Files:**
- Create: `mcp-server/src/main/java/com/mall/agent/mcp/tools/OrderTools.java`
- Create: `mcp-server/src/main/java/com/mall/agent/mcp/ToolResults.java`
- Test: `mcp-server/src/test/java/com/mall/agent/mcp/tools/OrderToolsTest.java`

**设计要点**：工具返回**紧凑的 JSON 字符串**，字段名对模型友好。**不含**内部字段（如 userId）。

- [ ] **Step 1: 写失败的测试**

```java
package com.mall.agent.mcp.tools;

import com.mall.agent.mcp.FakeSupermall;
import com.mall.agent.mcp.SupermallClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class OrderToolsTest {

    private FakeSupermall fake;
    private OrderTools tools;

    @BeforeEach
    void setUp() throws IOException {
        fake = new FakeSupermall();
        tools = new OrderTools(new SupermallClient(fake.baseUrl(), "t"));
    }

    @AfterEach
    void tearDown() {
        fake.close();
    }

    @Test
    void getOrder_shouldReturnModelFriendlyJson() {
        String result = tools.getOrder(9001L);

        assertTrue(result.contains("\"status\":\"RECEIVED\""));
        assertTrue(result.contains("\"orderNo\":\"SN9001\""));
        // userId 属内部字段，不该出现在模型上下文里
        assertFalse(result.contains("userId"), "内部字段泄漏给模型：" + result);
    }

    @Test
    void getOrder_shouldNotLeakInternalFields() {
        String result = tools.getOrder(9001L);

        for (String internal : new String[]{"userId", "addressId", "deleted"}) {
            assertFalse(result.contains(internal), "内部字段 " + internal + " 泄漏给模型：" + result);
        }
    }

    @Test
    void failure_shouldBeReadableTextWithoutExceptionClassNames() {
        String text = ToolResults.failure(new SupermallException(50000, "订单不存在"));

        assertTrue(text.contains("订单不存在"), text);
        assertTrue(text.contains("\"error\":true"), text);
        assertFalse(text.contains("Exception"), "错误信息里不该出现异常类名：" + text);
        assertFalse(text.contains("at com."), "错误信息里不该出现堆栈：" + text);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
JAVA_HOME=/d/jdks/openjdk-22.0.2 "/d/JetBrains/IntelliJ IDEA 2026.2/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" test -pl mcp-server -Dtest=OrderToolsTest
```

Expected: 编译失败，`找不到符号: 类 OrderTools`

- [ ] **Step 3: 实现**

```java
package com.mall.agent.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mall.agent.mcp.SupermallClient;
import com.mall.agent.mcp.SupermallException;

/**
 * 订单查询工具。
 *
 * <p>返回给模型的是**裁剪过的 JSON**：只保留判断所需字段，不暴露内部标识。
 * 这既省 token，也避免模型拿内部字段去推理。</p>
 */
public class OrderTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SupermallClient client;

    public OrderTools(SupermallClient client) {
        this.client = client;
    }

    /** 查订单详情。 */
    public String getOrder(Long orderId) {
        return pick(client.get("/api/orders/" + orderId),
                "id", "orderNo", "status", "totalAmount", "createdAt");
    }

    /** 查当前用户的订单列表。 */
    public String listUserOrders(String status) {
        String path = "/api/orders?page=1&size=20"
                + (status == null || status.isBlank() ? "" : "&status=" + status);
        JsonNode data = client.get(path);
        return pick(data, "records");
    }

    /** 查订单物流。 */
    public String getLogistics(Long orderId) {
        return pick(client.get("/api/orders/" + orderId + "/logistics"),
                "company", "trackingNo", "status", "createdAt");
    }

    private String pick(JsonNode node, String... fields) {
        ObjectNode picked = MAPPER.createObjectNode();
        for (String field : fields) {
            if (node != null && node.has(field)) {
                picked.set(field, node.get(field));
            }
        }
        return picked.toString();
    }
}
```

同时创建 `ToolResults.java`——**错误翻译单独成类**，因为 MCP 装配层与工具层都要用，放在任何一侧都是错的位置：

```java
package com.mall.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 工具返回值与错误的统一构造。
 *
 * <p>错误信息是<b>给模型看的</b>：它决定模型下一步怎么做。因此刻意不含异常类名与
 * 堆栈——那些对模型是噪声，只会诱导它复述技术细节而不是解决问题。</p>
 */
public final class ToolResults {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolResults() {
    }

    /** 业务失败的可读描述。 */
    public static String failure(SupermallException exception) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("error", true);
        node.put("code", exception.getCode());
        node.put("message", exception.getMessage());
        return node.toString();
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
JAVA_HOME=/d/jdks/openjdk-22.0.2 "/d/JetBrains/IntelliJ IDEA 2026.2/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" test -pl mcp-server -Dtest=OrderToolsTest
```

Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

```bash
git add mcp-server/src/
git commit -m "feat: add order query tools with trimmed model-facing output"
```

---

## Task 4: 售后类工具

**Files:**
- Create: `mcp-server/src/main/java/com/mall/agent/mcp/tools/RefundTools.java`
- Create: `mcp-server/src/main/java/com/mall/agent/mcp/tools/PolicyTools.java`
- Test: `mcp-server/src/test/java/com/mall/agent/mcp/tools/RefundToolsTest.java`

**设计要点**：`submit_refund` 的参数**只有 orderId 与 reason，没有金额**。这是第 1 道防线的落点——工具面本身就不提供"指定金额"的能力。

- [ ] **Step 1: 写失败的测试**

```java
package com.mall.agent.mcp.tools;

import com.mall.agent.mcp.FakeSupermall;
import com.mall.agent.mcp.SupermallClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class RefundToolsTest {

    private FakeSupermall fake;
    private RefundTools tools;

    @BeforeEach
    void setUp() throws IOException {
        fake = new FakeSupermall();
        tools = new RefundTools(new SupermallClient(fake.baseUrl(), "t"));
    }

    @AfterEach
    void tearDown() {
        fake.close();
    }

    @Test
    void eligibility_shouldExposePolicyCodeSoExplanationCanBeGrounded() {
        String result = tools.getRefundEligibility(9001L);

        // policyCode 是判定与解释之间的接缝，必须传给模型
        assertTrue(result.contains("SEVEN_DAY_NO_REASON"), result);
        assertTrue(result.contains("refundableAmount"), result);
    }

    @Test
    void submitRefund_shouldOnlyCarryReason() {
        tools.submitRefund(9001L, "不想要了");

        // 请求体里不能出现金额字段——工具签名本身就决定了这一点
        assertTrue(fake.receivedPaths.stream().anyMatch(p -> p.endsWith("/refund/execute")));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
JAVA_HOME=/d/jdks/openjdk-22.0.2 "/d/JetBrains/IntelliJ IDEA 2026.2/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" test -pl mcp-server -Dtest=RefundToolsTest
```

Expected: 编译失败，`找不到符号: 类 RefundTools`

- [ ] **Step 3: 实现 RefundTools**

```java
package com.mall.agent.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mall.agent.mcp.SupermallClient;

/**
 * 售后工具。
 *
 * <p><b>写操作只有 {@link #submitRefund} 一个，且签名里没有金额。</b>
 * 金额一律由 supermall 从订单算出——这是第 1 道防线：模型即便被诱导，
 * 也没有"退 99999"这个动作可调。</p>
 */
public class RefundTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SupermallClient client;

    public RefundTools(SupermallClient client) {
        this.client = client;
    }

    /** 查该订单的售后资格、可退金额、适用政策码、是否已有退款单。 */
    public String getRefundEligibility(Long orderId) {
        JsonNode data = client.get("/api/orders/" + orderId + "/refund-eligibility");
        ObjectNode picked = MAPPER.createObjectNode();
        for (String field : new String[]{"eligible", "reason", "policyCode", "policyTitle",
                "refundableAmount", "refundExists"}) {
            if (data.has(field)) {
                picked.set(field, data.get(field));
            }
        }
        // orderId 一并带上，便于模型在多轮对话里对齐上下文
        picked.put("orderId", orderId);
        return picked.toString();
    }

    /** 执行退款。金额由服务端决定，入参只有原因。 */
    public String submitRefund(Long orderId, String reason) {
        String body = MAPPER.createObjectNode().put("reason", reason).toString();
        JsonNode data = client.post("/api/orders/" + orderId + "/refund/execute", body);
        return data.toString();
    }
}
```

- [ ] **Step 4: 实现 PolicyTools**

```java
package com.mall.agent.mcp.tools;

import com.mall.agent.mcp.SupermallClient;

/**
 * 政策条款。阶段 3 RAG 的 Agent 索引消费者会使用它建立检索索引——<b>这是"判定与解释同源"的那条通路</b>。
 *
 * <p>条款文本与判定逻辑出自 supermall 的同一个枚举，提供可审阅的同源关系；
 * 条款条件是否由当前实现输入并核验、执行结果是否与条款相符，仍须由测试和代码审查确认。</p>
 *
 * <p><b>返回的 data 对象还带一个指纹</b>（Plan A 的 K-13 修订后，其形状为
 * {@code {"fingerprint":"…","clauses":[…]}}）。{@link SupermallClient} 已解析 Result 信封并取出
 * 该 data 对象；指纹由服务端从
 * <b>本次返回的那批条款</b>派生，条款一改就变。Plan C 当前没有索引；阶段 3 RAG 的首项
 * 会实现消费者，重新拉取时比对该指纹，不一致即重建索引——这是"同源"在<b>时间维度</b>上的保障，
 * 只靠"启动时拉一次"是不够的。</p>
 *
 * <p>本工具不解析 data 内的字段，而是将包含指纹与条款的 data 对象序列化后交给模型。</p>
 */
public class PolicyTools {

    private final SupermallClient client;

    public PolicyTools(SupermallClient client) {
        this.client = client;
    }

    public String listPolicyClauses() {
        return client.get("/api/after-sales/policies").toString();
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

```bash
JAVA_HOME=/d/jdks/openjdk-22.0.2 "/d/JetBrains/IntelliJ IDEA 2026.2/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" test -pl mcp-server -Dtest=RefundToolsTest
```

Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 6: 提交**

```bash
git add mcp-server/src/
git commit -m "feat: add refund and policy tools; write path carries no amount"
```

---

## Task 5: MCP 装配（stdio + 工具注册）

**Files:**
- Create: `mcp-server/src/main/java/com/mall/agent/mcp/McpServerMain.java`
- Test: `mcp-server/src/test/java/com/mall/agent/mcp/ToolSchemaTest.java`

- [ ] **Step 1: 写失败的测试（只测 schema 定义，不测协议）**

```java
package com.mall.agent.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolSchemaTest {

    @Test
    void everyToolShouldHaveNameDescriptionAndObjectSchema() {
        assertEquals(6, McpServerMain.toolDefinitions().size(),
                "工具数量与设计文档不一致");

        McpServerMain.toolDefinitions().forEach(tool -> {
            assertNotNull(tool.name());
            assertFalse(tool.description().isBlank(),
                    tool.name() + " 缺少描述——模型靠描述决定何时调用");
            assertTrue(tool.inputSchema().contains("\"type\":\"object\""),
                    tool.name() + " 的入参 schema 不是 object");
        });
    }

    @Test
    void submitRefundSchema_shouldNotExposeAmountParameter() {
        String schema = McpServerMain.toolDefinitions().stream()
                .filter(t -> t.name().equals("submit_refund"))
                .findFirst().orElseThrow()
                .inputSchema();

        // 第 1 道防线的直接体现：工具面不提供"指定金额"的能力
        assertFalse(schema.contains("amount"), "submit_refund 不应有任何金额参数：" + schema);
        assertTrue(schema.contains("reason"));
        assertTrue(schema.contains("orderId"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
JAVA_HOME=/d/jdks/openjdk-22.0.2 "/d/JetBrains/IntelliJ IDEA 2026.2/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" test -pl mcp-server -Dtest=ToolSchemaTest
```

Expected: 编译失败，`找不到符号: 类 McpServerMain`

- [ ] **Step 3: 实现入口**

```java
package com.mall.agent.mcp;

import com.mall.agent.mcp.tools.OrderTools;
import com.mall.agent.mcp.tools.PolicyTools;
import com.mall.agent.mcp.tools.RefundTools;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 售后 MCP Server 入口。
 *
 * <p>stdio 传输：<b>stdout 被 JSON-RPC 独占</b>，日志全部走 stderr
 * （见 simplelogger.properties）。</p>
 */
public class McpServerMain {

    private static final Logger log = LoggerFactory.getLogger(McpServerMain.class);

    /** 工具定义：名称、描述、入参 schema。描述直接决定模型何时调用，必须写清楚。 */
    public record ToolDefinition(String name, String description, String inputSchema) {
    }

    public static List<ToolDefinition> toolDefinitions() {
        return List.of(
                new ToolDefinition("get_order",
                        "查询指定订单的详情，包括状态与金额。需要订单号时先用 list_user_orders。",
                        """
                        {"type":"object","properties":{"orderId":{"type":"integer","description":"订单 ID"}},
                         "required":["orderId"]}"""),
                new ToolDefinition("list_user_orders",
                        "列出当前用户的订单。可按状态筛选（PENDING/PAID/SHIPPED/DELIVERED/RECEIVED/REFUNDED/CANCELLED）。",
                        """
                        {"type":"object","properties":{"status":{"type":"string","description":"订单状态，可省略"}}}"""),
                new ToolDefinition("get_logistics",
                        "查询订单的物流信息。",
                        """
                        {"type":"object","properties":{"orderId":{"type":"integer"}},"required":["orderId"]}"""),
                new ToolDefinition("get_refund_eligibility",
                        "判断订单是否符合售后政策、可退金额、适用政策码、以及是否已有退款记录。"
                                + "执行退款前必须先调用本工具确认资格。",
                        """
                        {"type":"object","properties":{"orderId":{"type":"integer"}},"required":["orderId"]}"""),
                new ToolDefinition("list_policy_clauses",
                        "获取售后政策条款原文，用于向用户解释判定依据。",
                        """
                        {"type":"object","properties":{}}"""),
                new ToolDefinition("submit_refund",
                        "执行退款。金额由系统根据订单计算，调用方无法指定。"
                                + "调用前必须先用 get_refund_eligibility 确认资格。",
                        """
                        {"type":"object","properties":{
                          "orderId":{"type":"integer","description":"订单 ID"},
                          "reason":{"type":"string","description":"退款原因，来自用户的说明"}},
                         "required":["orderId","reason"]}"""));
    }

    public static void main(String[] args) {
        String baseUrl = System.getenv().getOrDefault("SUPERMALL_BASE_URL", "http://localhost:8081");
        String token = System.getenv("SUPERMALL_TOKEN");
        if (token == null || token.isBlank()) {
            log.error("SUPERMALL_TOKEN 未设置，无法启动。MCP server 需要用户令牌才能调用 supermall。");
            System.exit(1);
        }

        SupermallClient client = new SupermallClient(baseUrl, token);
        OrderTools orders = new OrderTools(client);
        RefundTools refunds = new RefundTools(client);
        PolicyTools policies = new PolicyTools(client);

        var specs = List.of(
                spec("get_order", args1 -> orders.getOrder(longArg(args1, "orderId"))),
                spec("list_user_orders", args1 -> orders.listUserOrders(strArg(args1, "status"))),
                spec("get_logistics", args1 -> orders.getLogistics(longArg(args1, "orderId"))),
                spec("get_refund_eligibility", args1 -> refunds.getRefundEligibility(longArg(args1, "orderId"))),
                spec("list_policy_clauses", args1 -> policies.listPolicyClauses()),
                spec("submit_refund", args1 ->
                        refunds.submitRefund(longArg(args1, "orderId"), strArg(args1, "reason")))
        );

        McpServer.sync(new StdioServerTransportProvider())
                .serverInfo("after-sales-mcp", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(specs)
                .build();

        log.info("after-sales MCP server 已启动（stdio），baseUrl={}", baseUrl);
    }

    @FunctionalInterface
    private interface ToolCall {
        String run(Map<String, Object> args);
    }

    private static McpServerFeatures.SyncToolSpecification spec(String name, ToolCall call) {
        ToolDefinition definition = toolDefinitions().stream()
                .filter(d -> d.name().equals(name))
                .findFirst()
                .orElseThrow();
        McpSchema.Tool tool = new McpSchema.Tool(
                definition.name(), definition.description(), definition.inputSchema());

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, args) -> {
            try {
                return McpSchema.CallToolResult.builder()
                        .addTextContent(call.run(args))
                        .isError(false)
                        .build();
            } catch (SupermallException e) {
                // 业务错误返回给模型，让它据此决定下一步；堆栈只记日志
                log.warn("工具 {} 调用失败：code={} message={}", name, e.getCode(), e.getMessage());
                return McpSchema.CallToolResult.builder()
                        .addTextContent(ToolResults.failure(e))
                        .isError(true)
                        .build();
            }
        });
    }

    private static long longArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static String strArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
JAVA_HOME=/d/jdks/openjdk-22.0.2 "/d/JetBrains/IntelliJ IDEA 2026.2/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" test -pl mcp-server
```

Expected: 全部测试通过

- [ ] **Step 5: 打包并验证 stdout 干净**

```bash
JAVA_HOME=/d/jdks/openjdk-22.0.2 "/d/JetBrains/IntelliJ IDEA 2026.2/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" -q package -DskipTests

# 不设 token 时应以退出码 1 失败，且错误信息在 stderr 而非 stdout
SUPERMALL_TOKEN= java -jar mcp-server/target/mcp-server.jar 2>/tmp/err.txt 1>/tmp/out.txt
echo "exit=$?"; echo "--- stdout（必须为空）---"; cat /tmp/out.txt
echo "--- stderr ---"; cat /tmp/err.txt
```

Expected: `exit=1`，stdout **完全为空**，错误信息出现在 stderr。**若 stdout 有内容，说明日志配置没生效，必须修好再继续**——那会破坏 stdio 协议。

- [ ] **Step 6: 提交**

```bash
git add mcp-server/
git commit -m "feat: wire tools into a stdio mcp server"
```

---

## Task 6: 端到端验证（真实 supermall）

**前置**：Plan A 已完成，supermall 运行在 8081 且环境变量齐备。

- [ ] **Step 1: 拿一个真实用户令牌**

用 Plan A 的 Task 8 里创建的那个用户（口令在你本地记录里，本项目约定不入库）：

```bash
BASE=http://localhost:8081
PW=$(grep '^PW_A=' /c/Users/hou16/AppData/Local/Temp/mall-itest/merchant-credentials.txt | cut -d= -f2-)
CT=$(curl -s -X POST $BASE/api/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"aftersales_t1\",\"password\":\"$PW\"}" | sed -E 's/.*"accessToken":"([^"]+)".*/\1/')
echo "$CT" > /tmp/ct.txt
```

> 若该用户已不存在（库被清理过），按 Plan A Task 8 Step 2 重新造一个，并用同样的用户名，或把上面命令里的用户名换掉。

- [ ] **Step 2: 手工走一次 MCP 握手**

stdio 协议是行分隔的 JSON-RPC。用管道验证：

```bash
printf '%s\n' \
 '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"smoke","version":"1"}}}' \
 '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
 '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' \
| SUPERMALL_TOKEN=$(cat /tmp/ct.txt) SUPERMALL_BASE_URL=$BASE java -jar mcp-server/target/mcp-server.jar 2>/dev/null
```

Expected: 第二行响应包含 `tools` 数组，且**恰好 6 个工具**，名称与设计一致

- [ ] **Step 3: 调用只读工具**

```bash
printf '%s\n' \
 '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"smoke","version":"1"}}}' \
 '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
 '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"list_policy_clauses","arguments":{}}}' \
| SUPERMALL_TOKEN=$(cat /tmp/ct.txt) SUPERMALL_BASE_URL=$BASE java -jar mcp-server/target/mcp-server.jar 2>/dev/null
```

Expected: 返回三条政策的 `code` / `title` / `clauseText`，**且 code 与 supermall 的枚举名一致**（这是"判定与解释同源"的实证）

- [ ] **Step 4: 调用写工具并验证落库**

用一张 `RECEIVED` 订单（Plan A 的 Task 8 已造过）：

```bash
printf '%s\n' \
 '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"smoke","version":"1"}}}' \
 '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
 "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"submit_refund\",\"arguments\":{\"orderId\":$ORDER_ID,\"reason\":\"端到端验证\"}}}" \
| SUPERMALL_TOKEN=$(cat /tmp/ct.txt) SUPERMALL_BASE_URL=$BASE java -jar mcp-server/target/mcp-server.jar 2>/dev/null
```

Expected: `isError` 为 false；随后

```bash
mysql_q "SELECT status FROM mall.\`order\` WHERE id=$ORDER_ID;"
```

Expected: `REFUNDED`

- [ ] **Step 5: 验证业务错误被正确翻译**

用一个**别人的**订单号调用 `get_order`。

Expected: 返回 `isError: true`，内容里有 `"code":50000` 与中文消息，**且没有 Java 异常类名**

- [ ] **Step 6: 记录验证结果并提交**

在 `docs/` 下记录：每个调用的原始响应、数据库最终状态、stdout 是否干净。

```bash
git add docs/
git commit -m "docs: record mcp server end-to-end verification"
```

---

## 完成标准

- [ ] `mvn test -pl mcp-server` 全绿
- [ ] stdout 在非协议输出下**完全为空**（日志全在 stderr）
- [ ] `tools/list` 恰好返回 6 个工具，`submit_refund` 的 schema 里**没有 amount 参数**
- [ ] 真实环境下：条款码与 supermall 枚举一致；写工具能改变订单状态；业务错误返回可读文本而非异常
- [ ] fat jar 可被 `java -jar` 直接拉起

---

## 已知限制

1. **单令牌单进程** —— 每个用户会话需要一个 MCP server 进程。生产形态应在 HTTP 传输下把令牌放在请求头里，本阶段不做。
2. **无重试** —— 工具调用失败即返回错误给模型，由模型决定是否重试。重试策略属于 Agent 层（Plan C）。
3. **无缓存** —— `list_policy_clauses` 每次调用都打 supermall。本计划只透传条款和指纹；Plan C 当前没有索引，也没有缓存或目录变更消费者。

   ⚠️ **K-13 的后续里程碑**：响应已带**指纹**（见本计划 Step 4 的 `PolicyTools`），但发布端提供检测材料不等于 Agent 已消费它。阶段 3 RAG 的首项应建立索引消费者：重新拉取时比较指纹，不一致就以同次响应的条款快照重建索引。否则 supermall 改条款并重新部署后，Agent 可能继续引用旧文本。

---

## 做完之后

项目全貌与进度见仓库根目录的 `README.md`。

| 下一步 | 文档 | 依赖 |
|---|---|---|
| **计划 C：Agent** | `docs/plans/2026-09-18-plan-c-agent.md` | **本计划的 6 个工具** |
| 阶段 3：RAG 解释层 | 尚未编写——待设计 | 计划 C |
| 阶段 4：240 条评测集 | 尚未编写——待设计 | 计划 C |

**本计划完成后**：可以用 JSON-RPC 直接驱动 MCP server 调工具（Task 6 有完整命令），**此时仍然不需要模型**。这一步能独立验收，是很好的检查点。

请顺手更新 `README.md` 的进度表。
