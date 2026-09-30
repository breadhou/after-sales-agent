package com.mall.agent.policy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Retrieves complete policy catalog generations and resolves only exact policy codes. */
public final class PolicyCatalogConsumer {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final Function<ToolExecutionRequest, ToolExecutionResult> toolCaller;
    private volatile CatalogSnapshot currentSnapshot;

    public PolicyCatalogConsumer(McpClient mcp) {
        this(mcp::executeTool);
    }

    /** Test seam for the MCP invocation; production callers use {@link McpClient}. */
    PolicyCatalogConsumer(Function<ToolExecutionRequest, ToolExecutionResult> toolCaller) {
        this.toolCaller = Objects.requireNonNull(toolCaller, "toolCaller");
    }

    /**
     * Fetches and validates one complete MCP catalog response, then atomically installs it.
     * A failed refresh returns no generation and never substitutes an earlier one.
     */
    public synchronized CatalogSnapshot refresh() {
        CatalogSnapshot refreshed = parseCatalog(callCatalog());
        currentSnapshot = refreshed;
        return refreshed;
    }

    /**
     * Resolves an eligibility response against the exact catalog generation supplied by this request.
     */
    public PolicyEvidence requireMatching(CatalogSnapshot snapshot,
                                          String eligibilityFingerprint,
                                          String policyCode) {
        if (snapshot == null || !snapshot.fingerprint().equals(eligibilityFingerprint)) {
            throw new PolicyCatalogException("资格目录指纹与当前复核目录不一致");
        }
        if (policyCode == null || policyCode.isBlank()) {
            throw new PolicyCatalogException("资格结果缺少政策 code");
        }
        for (PolicyEvidence evidence : snapshot.clauses()) {
            if (evidence.code().equals(policyCode)) {
                return evidence;
            }
        }
        throw new PolicyCatalogException("目录中找不到资格结果指定的政策 code");
    }

    private ToolExecutionResult callCatalog() {
        try {
            ToolExecutionResult result = toolCaller.apply(ToolExecutionRequest.builder()
                    .name("list_policy_clauses")
                    .arguments("{}")
                    .build());
            if (result == null || result.isError()) {
                throw new PolicyCatalogException("无法读取政策目录");
            }
            return result;
        } catch (PolicyCatalogException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new PolicyCatalogException("无法读取政策目录", e);
        }
    }

    private static CatalogSnapshot parseCatalog(ToolExecutionResult result) {
        String resultText;
        try {
            resultText = result.resultText();
        } catch (RuntimeException e) {
            throw new PolicyCatalogException("无法读取政策目录", e);
        }
        if (resultText == null || resultText.isBlank()) {
            throw new PolicyCatalogException("政策目录响应为空");
        }

        final JsonNode catalog;
        try {
            catalog = MAPPER.readTree(resultText);
        } catch (JsonProcessingException e) {
            throw new PolicyCatalogException("政策目录不是有效 JSON", e);
        }
        if (catalog == null || !catalog.isObject()) {
            throw new PolicyCatalogException("政策目录不是对象");
        }
        String fingerprint = requiredText(catalog, "fingerprint", "政策目录缺少指纹");
        JsonNode clausesNode = catalog.get("clauses");
        if (clausesNode == null || !clausesNode.isArray() || clausesNode.isEmpty()) {
            throw new PolicyCatalogException("政策目录缺少条款");
        }

        List<PolicyEvidence> clauses = new ArrayList<>();
        Set<String> seenCodes = new HashSet<>();
        for (JsonNode clause : clausesNode) {
            if (!clause.isObject()) {
                throw new PolicyCatalogException("政策目录含有无效条款");
            }
            String code = requiredText(clause, "code", "政策条款缺少 code");
            if (!seenCodes.add(code)) {
                throw new PolicyCatalogException("政策目录含有重复 code");
            }
            String title = requiredText(clause, "title", "政策条款缺少标题");
            String clauseText = requiredText(clause, "clauseText", "政策条款缺少正文");
            clauses.add(new PolicyEvidence(fingerprint, code, title, clauseText));
        }
        return new CatalogSnapshot(fingerprint, clauses);
    }

    private static String requiredText(JsonNode object, String field, String message) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new PolicyCatalogException(message);
        }
        return value.textValue();
    }

    public static final class PolicyCatalogException extends RuntimeException {

        public PolicyCatalogException(String message) {
            super(message);
        }

        public PolicyCatalogException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
