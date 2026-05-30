package com.corecc.config;

import io.github.cdimascio.dotenv.Dotenv;

/**
 * 应用配置，从环境变量加载，支持 CLI 参数覆盖。
 *
 * 对应 Python 版的 corecc/config.py。
 *
 * 配置优先级（从高到低）：
 * 1. CLI 参数（-m, --base-url, --api-key）
 * 2. 环境变量（CORECC_*, OPENAI_*, DEEPSEEK_*）
 * 3. .env 文件
 * 4. 默认值
 */
public class Config {
    private String model;
    private String apiKey;
    private String baseUrl;
    private int maxTokens;
    private double temperature;
    private int maxContextTokens;

    public Config() {
        this.model = "gpt-4o";
        this.apiKey = "";
        this.baseUrl = null;
        this.maxTokens = 4096;
        this.temperature = 0.0;
        this.maxContextTokens = 128000;
    }

    public Config(String model, String apiKey, String baseUrl, int maxTokens,
                  double temperature, int maxContextTokens) {
        this.model = model;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
        this.maxContextTokens = maxContextTokens;
    }

    /**
     * 从环境变量构建配置实例。
     *
     * 支持的环境变量：
     * - CORECC_MODEL / CORECC_API_KEY / CORECC_BASE_URL
     * - OPENAI_API_KEY / OPENAI_BASE_URL / DEEPSEEK_API_KEY
     */
    public static Config fromEnv() {
        // Load .env file (does not override existing env vars)
        Dotenv dotenv;
        try {
            dotenv = Dotenv.configure()
                .ignoreIfMissing()
                .load();
        } catch (Exception e) {
            dotenv = null;
        }

        // Auto-detect API key from common env vars
        String apiKey = getConfigValue(dotenv, "CORECC_API_KEY",
            getConfigValue(dotenv, "OPENAI_API_KEY",
                getConfigValue(dotenv, "DEEPSEEK_API_KEY", "")));

        String baseUrl = getConfigValue(dotenv, "OPENAI_BASE_URL",
            getConfigValue(dotenv, "CORECC_BASE_URL", null));

        return new Config(
            getConfigValue(dotenv, "CORECC_MODEL", "gpt-4o"),
            apiKey,
            baseUrl,
            Integer.parseInt(getConfigValue(dotenv, "CORECC_MAX_TOKENS", "4096")),
            Double.parseDouble(getConfigValue(dotenv, "CORECC_TEMPERATURE", "0")),
            Integer.parseInt(getConfigValue(dotenv, "CORECC_MAX_CONTEXT", "128000"))
        );
    }

    private static String getConfigValue(Dotenv dotenv, String key, String defaultValue) {
        // Try system env first, then .env
        String value = System.getenv(key);
        if (value != null && !value.isEmpty()) {
            return value;
        }
        if (dotenv != null) {
            value = dotenv.get(key);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return defaultValue;
    }

    // Getters and setters
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }

    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }

    public int getMaxContextTokens() { return maxContextTokens; }
    public void setMaxContextTokens(int maxContextTokens) { this.maxContextTokens = maxContextTokens; }

    @Override
    public String toString() {
        return String.format("Config{model='%s', baseUrl='%s', maxTokens=%d, temperature=%.1f, maxContext=%d}",
            model, baseUrl, maxTokens, temperature, maxContextTokens);
    }
}
