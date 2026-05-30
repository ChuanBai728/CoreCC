package com.corecc.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 基于 OkHttp 的 OpenAI API 封装类。
 *
 * 对应 Python 版的 corecc/llm.py。
 * 支持：
 * - 流式接收响应（stream=True）
 * - 工具调用（function calling）
 * - 指数退避重试（RateLimitError、APITimeoutError、APIConnectionError）
 * - 5xx 服务端错误自动重试，4xx 客户端错误不重试
 */
public class LLM {
    private final String model;
    private final String apiKey;
    private final String baseUrl;
    private final OkHttpClient client;
    private final ObjectMapper mapper;
    private final Map<String, Object> extra;

    public long totalPromptTokens = 0;
    public long totalCompletionTokens = 0;

    public LLM(String model, String apiKey, String baseUrl, Map<String, Object> extra) {
        this.model = model;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl != null ? baseUrl : "https://api.openai.com/v1";
        this.extra = extra != null ? extra : new HashMap<>();
        this.mapper = new ObjectMapper();

        // Configure HTTP client with timeouts
        this.client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();
    }

    /**
     * 发送消息，流式接收响应，处理工具调用。
     *
     * @param messages 消息列表
     * @param tools 工具 schema 列表
     * @param onToken 流式 token 回调
     * @return LLM 响应
     */
    public LLMResponse chat(List<Map<String, Object>> messages,
                            List<Map<String, Object>> tools,
                            Consumer<String> onToken) {
        // Build request body
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", true);
        body.putAll(extra);

        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
        }

        // Try with stream_options first, then without
        for (int streamAttempt = 0; streamAttempt < 3; streamAttempt++) {
            try {
                body.put("stream_options", Map.of("include_usage", true));
                return streamRequest(body, onToken);
            } catch (Exception e) {
                body.remove("stream_options");
                try {
                    return streamRequest(body, onToken);
                } catch (Exception e2) {
                    if (streamAttempt < 2 && isRetryableStreamError(e2)) {
                        sleep(streamAttempt);
                        continue;
                    }
                    throw new RuntimeException(e2);
                }
            }
        }

        throw new RuntimeException("Failed after retries");
    }

    /**
     * 流式请求处理。
     */
    private LLMResponse streamRequest(Map<String, Object> body, Consumer<String> onToken) {
        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize request", e);
        }

        Request request = new Request.Builder()
            .url(baseUrl + "/chat/completions")
            .addHeader("Authorization", "Bearer " + apiKey)
            .addHeader("Content-Type", "application/json")
            .post(RequestBody.create(json, MediaType.parse("application/json")))
            .build();

        // Accumulators for streaming
        StringBuilder contentBuilder = new StringBuilder();
        StringBuilder reasoningBuilder = new StringBuilder();
        Map<Integer, Map<String, String>> tcMap = new TreeMap<>();
        AtomicReference<Long> promptTokens = new AtomicReference<>(0L);
        AtomicReference<Long> completionTokens = new AtomicReference<>(0L);

        // Create SSE event source
        EventSource.Factory factory = EventSources.createFactory(client);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Exception> errorRef = new AtomicReference<>();

        EventSource eventSource = factory.newEventSource(request, new EventSourceListener() {
            @Override
            public void onEvent(EventSource eventSource, String id, String type, String data) {
                if ("[DONE]".equals(data)) {
                    return;
                }

                try {
                    JsonNode node = mapper.readTree(data);

                    // Usage info
                    if (node.has("usage") && !node.get("usage").isNull()) {
                        JsonNode usage = node.get("usage");
                        if (usage.has("prompt_tokens")) {
                            promptTokens.set(usage.get("prompt_tokens").asLong());
                        }
                        if (usage.has("completion_tokens")) {
                            completionTokens.set(usage.get("completion_tokens").asLong());
                        }
                    }

                    if (!node.has("choices") || node.get("choices").isEmpty()) {
                        return;
                    }

                    JsonNode delta = node.get("choices").get(0).get("delta");

                    // Content
                    if (delta.has("content") && !delta.get("content").isNull()) {
                        String content = delta.get("content").asText();
                        contentBuilder.append(content);
                        if (onToken != null) {
                            onToken.accept(content);
                        }
                    }

                    // Reasoning content (DeepSeek)
                    if (delta.has("reasoning_content") && !delta.get("reasoning_content").isNull()) {
                        reasoningBuilder.append(delta.get("reasoning_content").asText());
                    }

                    // Tool calls
                    if (delta.has("tool_calls")) {
                        JsonNode toolCalls = delta.get("tool_calls");
                        for (JsonNode tcDelta : toolCalls) {
                            int idx = tcDelta.get("index").asInt();
                            tcMap.putIfAbsent(idx, new HashMap<>());
                            Map<String, String> tc = tcMap.get(idx);

                            if (tcDelta.has("id") && !tcDelta.get("id").isNull()) {
                                tc.put("id", tcDelta.get("id").asText());
                            }
                            if (tcDelta.has("function")) {
                                JsonNode func = tcDelta.get("function");
                                if (func.has("name") && !func.get("name").isNull()) {
                                    tc.put("name", func.get("name").asText());
                                }
                                if (func.has("arguments") && !func.get("arguments").isNull()) {
                                    tc.merge("args", func.get("arguments").asText(), (a, b) -> a + b);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    // Ignore parsing errors in stream
                }
            }

            @Override
            public void onFailure(EventSource eventSource, Throwable t, Response response) {
                if (t != null) {
                    errorRef.set(new RuntimeException(t));
                }
                latch.countDown();
            }

            @Override
            public void onClosed(EventSource eventSource) {
                latch.countDown();
            }
        });

        try {
            latch.await(120, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Request interrupted", e);
        } finally {
            eventSource.cancel();
        }

        if (errorRef.get() != null) {
            throw new RuntimeException(errorRef.get());
        }

        // Parse accumulated tool calls
        List<ToolCall> parsed = new ArrayList<>();
        for (Map.Entry<Integer, Map<String, String>> entry : tcMap.entrySet()) {
            Map<String, String> raw = entry.getValue();
            Map<String, Object> args;
            try {
                args = mapper.readValue(raw.getOrDefault("args", "{}"),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                args = Map.of();
            }
            parsed.add(new ToolCall(
                raw.getOrDefault("id", ""),
                raw.getOrDefault("name", ""),
                args
            ));
        }

        long pTokens = promptTokens.get();
        long cTokens = completionTokens.get();
        totalPromptTokens += pTokens;
        totalCompletionTokens += cTokens;

        return new LLMResponse(
            contentBuilder.toString(),
            parsed,
            reasoningBuilder.toString(),
            (int) pTokens,
            (int) cTokens
        );
    }

    /**
     * 带指数退避的重试机制。
     */
    public <T> T callWithRetry(java.util.function.Supplier<T> call, int maxRetries) {
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                return call.get();
            } catch (Exception e) {
                if (isRetryableError(e) && attempt < maxRetries - 1) {
                    sleep(attempt);
                    continue;
                }
                throw new RuntimeException(e);
            }
        }
        throw new RuntimeException("Failed after retries");
    }

    private boolean isRetryableError(Exception e) {
        String msg = e.getMessage().toLowerCase();
        return msg.contains("429") || msg.contains("timeout") || msg.contains("connection") ||
               msg.contains("500") || msg.contains("502") || msg.contains("503") || msg.contains("504");
    }

    private boolean isRetryableStreamError(Exception e) {
        String text = (e.getClass().getName() + ": " + e.getMessage()).toLowerCase();
        return text.contains("remoteprotocolerror") ||
               text.contains("incomplete chunked read") ||
               text.contains("peer closed connection") ||
               text.contains("connection reset") ||
               text.contains("readerror") ||
               text.contains("timeout");
    }

    private void sleep(int attempt) {
        try {
            Thread.sleep((long) Math.pow(2, attempt) * 1000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // Getters
    public String getModel() { return model; }
    public void setModel(String model) { /* Use constructor for immutability */ }
    public String getApiKey() { return apiKey; }
    public String getBaseUrl() { return baseUrl; }
}
