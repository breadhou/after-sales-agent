package com.mall.agent.config;

import java.util.Properties;

/**
 * 模型接入配置。
 *
 * <p>统一走 OpenAI 兼容接口，因此换厂商只改 baseUrl 与 name。三个字段缺一不可，
 * 缺失时直接报错而不是回落到某个默认模型——评测结果的归属必须是明确的。</p>
 *
 * <p>{@code temperature} 默认 0：评测要可复现，不能跟随厂商默认值。</p>
 */
public record ModelProperties(String baseUrl, String apiKey, String name, double temperature) {

    public static ModelProperties from(Properties properties) {
        String baseUrl = require(properties, "model.baseUrl");
        String apiKey = require(properties, "model.apiKey");
        String name = require(properties, "model.name");
        double temperature = Double.parseDouble(
                properties.getProperty("model.temperature", "0.0"));
        return new ModelProperties(baseUrl, apiKey, name, temperature);
    }

    private static String require(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "缺少配置 " + key + "。模型必须显式配置，不回落到默认值。");
        }
        return value.trim();
    }
}
