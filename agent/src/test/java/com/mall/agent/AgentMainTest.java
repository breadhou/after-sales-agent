package com.mall.agent;

import com.mall.agent.config.ModelProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentMainTest {

    @Test
    void unresolvedModelPlaceholdersMustStopStartup() {
        Properties properties = modelProperties();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AgentMain.modelProperties(properties, Map.of()));

        assertTrue(e.getMessage().contains("model.baseUrl"), e.getMessage());
    }

    @Test
    void environmentModelValuesOverridePlaceholders() {
        ModelProperties model = AgentMain.modelProperties(modelProperties(), Map.of(
                "MODEL_BASE_URL", "https://models.example/v1",
                "MODEL_API_KEY", "test-key",
                "MODEL_NAME", "test-model"));

        assertEquals("https://models.example/v1", model.baseUrl());
        assertEquals("test-model", model.name());
    }

    @Test
    void missingMcpJarMustStopStartupBeforeClientCreation() throws Exception {
        Path emptyRepository = Files.createTempDirectory("after-sales-agent-test");
        try {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> AgentMain.mcpServerJar(emptyRepository));
            assertTrue(e.getMessage().contains("mcp-server"), e.getMessage());
        } finally {
            Files.deleteIfExists(emptyRepository);
        }
    }

    @Test
    void backendCredentialsMustStopAgentStartupWithoutEchoingValues() {
        for (String key : new String[]{"MERCHANT_JWT_SECRET",
                "SPRING_DATASOURCE_PASSWORD", "SPRING_RABBITMQ_PASSWORD"}) {
            String secret = "synthetic-secret-should-not-be-printed";
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> AgentMain.rejectBackendCredentials(Map.of(key, secret)));
            assertTrue(e.getMessage().contains(key), e.getMessage());
            assertTrue(!e.getMessage().contains(secret), e.getMessage());
        }
        AgentMain.rejectBackendCredentials(Map.of("MERCHANT_JWT_SECRET", " "));
    }

    private static Properties modelProperties() {
        Properties properties = new Properties();
        properties.setProperty("model.baseUrl", "${MODEL_BASE_URL}");
        properties.setProperty("model.apiKey", "${MODEL_API_KEY}");
        properties.setProperty("model.name", "${MODEL_NAME}");
        properties.setProperty("model.temperature", "0.0");
        return properties;
    }
}
