package com.mall.agent.config;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class ModelPropertiesTest {

    private Properties base() {
        Properties p = new Properties();
        p.setProperty("model.baseUrl", "https://api.example.com/v1");
        p.setProperty("model.apiKey", "sk-test");
        p.setProperty("model.name", "some-model");
        p.setProperty("model.temperature", "0.0");
        return p;
    }

    @Test
    void shouldReadAllFields() {
        ModelProperties props = ModelProperties.from(base());

        assertEquals("https://api.example.com/v1", props.baseUrl());
        assertEquals("some-model", props.name());
        assertEquals(0.0, props.temperature());
    }

    @Test
    void temperatureShouldDefaultToZeroForReproducibility() {
        Properties p = base();
        p.remove("model.temperature");

        // 评测要有可复现性，默认必须是 0 而不是跟随厂商默认
        assertEquals(0.0, ModelProperties.from(p).temperature());
    }

    @Test
    void shouldRejectMissingApiKey() {
        Properties p = base();
        p.remove("model.apiKey");

        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class, () -> ModelProperties.from(p));
        assertTrue(e.getMessage().contains("model.apiKey"), e.getMessage());
    }

    @Test
    void shouldRejectMissingModelName() {
        Properties p = base();
        p.remove("model.name");

        assertThrows(IllegalArgumentException.class, () -> ModelProperties.from(p));
    }

    @Test
    void shouldRejectUnresolvedEnvironmentPlaceholder() {
        Properties p = base();
        p.setProperty("model.apiKey", "${MODEL_API_KEY}");

        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class, () -> ModelProperties.from(p));

        assertTrue(e.getMessage().contains("model.apiKey"), e.getMessage());
    }
}
