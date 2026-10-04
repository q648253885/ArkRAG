package com.arkrag.core.chat;

import com.arkrag.core.exception.ArkRagException;
import com.arkrag.core.exception.ErrorCode;
import com.arkrag.core.settings.ModelSettings;
import com.arkrag.core.settings.SettingsService;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Chat LLM 服务（v1.1，独立 RAG 答案生成用）。
 * 与 EmbeddingService 同一套"配置指纹缓存"热生效模式；
 * 未配置时 isConfigured()=false，chat() 抛 CHAT_NOT_CONFIGURED（控制台/MCP 给配置指引）。
 * 副作用：调用外部 HTTP API。
 */
public class ChatService {

    private static final int MAX_RETRIES = 2; // 答案生成可重试一次，避免用户等待过久

    private final SettingsService settings;
    private record Cached(String fingerprint, OpenAiChatModel model) {
    }
    private final AtomicReference<Cached> cache = new AtomicReference<>();

    public ChatService(SettingsService settings) {
        this.settings = settings;
    }

    /** Chat 模型是否已配置。 */
    public boolean isConfigured() {
        ModelSettings.Chat cfg = settings.effectiveChat();
        return cfg != null && cfg.configured();
    }

    /**
     * 发送一条用户消息并返回助手文本。
     *
     * @throws ArkRagException CHAT_NOT_CONFIGURED / SERVER_ERROR（重试耗尽）
     */
    public String chat(String userMessage) {
        ModelSettings.Chat cfg = settings.effectiveChat();
        if (cfg == null || !cfg.configured()) {
            throw new ArkRagException(ErrorCode.CHAT_NOT_CONFIGURED,
                    "Chat 模型未配置：请在控制台「模型配置」页配置 OpenAI 兼容的 chat base-url、api-key、model");
        }
        String fingerprint = cfg.baseUrl() + "|" + cfg.apiKey() + "|" + cfg.model() + "|" + cfg.temperature();
        Cached cached = cache.get();
        if (cached == null || !cached.fingerprint().equals(fingerprint)) {
            var builder = OpenAiChatModel.builder()
                    .baseUrl(cfg.baseUrl())
                    .apiKey(cfg.apiKey())
                    .modelName(cfg.model())
                    .timeout(Duration.ofSeconds(120));
            if (cfg.temperature() != null) {
                builder.temperature(cfg.temperature());
            }
            cached = new Cached(fingerprint, builder.build());
            cache.set(cached);
        }
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                String answer = cached.model().chat(userMessage);
                if (answer == null || answer.isBlank()) {
                    throw new ArkRagException(ErrorCode.SERVER_ERROR, "Chat 模型返回了空答案");
                }
                return answer;
            } catch (ArkRagException e) {
                throw e;
            } catch (RuntimeException ex) {
                last = ex;
                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(800);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new ArkRagException(ErrorCode.SERVER_ERROR, "Chat 请求被中断", null, ie);
                    }
                }
            }
        }
        throw new ArkRagException(ErrorCode.SERVER_ERROR,
                "Chat 模型调用失败：" + rootMessage(last), null, last);
    }

    private static String rootMessage(Throwable t) {
        while (t != null && t.getCause() != null) {
            t = t.getCause();
        }
        return t == null ? "unknown" : String.valueOf(t.getMessage());
    }
}
