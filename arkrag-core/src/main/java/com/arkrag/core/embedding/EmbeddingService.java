package com.arkrag.core.embedding;

import com.arkrag.core.config.ArkRagConfig;
import com.arkrag.core.exception.ArkRagException;
import com.arkrag.core.exception.ErrorCode;
import com.arkrag.core.settings.SettingsService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Embedding 服务：把 OpenAI 兼容端点包装成可注入的嵌入能力。
 * v1.1：配置源 = SettingsService（runtime > yml 合成）；模型实例按配置指纹缓存，
 * 配置变更（控制台保存）后下一次调用自动用新配置重建 —— 热生效、无需重启。
 * 请求失败退避重试（外部 API 抖动是常态，不能一次失败就污染摄入状态机）。
 * 副作用：调用外部 HTTP API。
 */
public class EmbeddingService {

    private static final int MAX_RETRIES = 3;

    private final SettingsService settings;
    private record Cached(String fingerprint, OpenAiEmbeddingModel model) {
    }
    private final AtomicReference<Cached> cache = new AtomicReference<>();

    public EmbeddingService(SettingsService settings) {
        this.settings = settings;
    }

    /** 服务端 /health 与控制台/插件"测试连接"用：模型是否已配置。 */
    public boolean isConfigured() {
        return settings.effectiveEmbedding().configured();
    }

    /** 已配置的模型名（未配置时返回 null）。 */
    public String modelName() {
        ArkRagConfig.Embedding cfg = settings.effectiveEmbedding();
        return cfg.configured() ? cfg.model() : null;
    }

    /** 显式配置的维度（未显式配置返回 null，用首个真实向量回填）。 */
    public Integer configuredDims() {
        return settings.effectiveEmbedding().dims();
    }

    /**
     * 批量嵌入文本。批大小固定 32（与摄入配置一致）；任一批最终失败抛 ArkRagException。
     *
     * @param texts 非空文本列表
     * @return 与输入等长且顺序一致的向量列表
     * @throws ArkRagException EMBEDDING_NOT_CONFIGURED（未配置）或 INGEST_FAILED（重试耗尽）
     */
    public List<Embedding> embedAll(List<String> texts) {
        OpenAiEmbeddingModel model = model();
        List<TextSegment> segments = texts.stream().map(TextSegment::from).toList();
        Response<List<Embedding>> resp = withRetry(model, segments);
        List<Embedding> embeddings = resp.content();
        if (embeddings == null || embeddings.size() != texts.size()) {
            throw new ArkRagException(ErrorCode.INGEST_FAILED,
                    "Embedding 返回数量与输入不一致（" + (embeddings == null ? 0 : embeddings.size())
                            + "/" + texts.size() + "）");
        }
        return embeddings;
    }

    /** 单条嵌入（查询用）。 */
    public Embedding embedOne(String text) {
        return embedAll(List.of(text)).get(0);
    }

    /** 查询向量的实际维度（调用方用于与 KB.dims 做一致性校验）。 */
    public int dimsOf(Embedding e) {
        return e.vector().length;
    }

    private Response<List<Embedding>> withRetry(OpenAiEmbeddingModel model, List<TextSegment> segments) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return model.embedAll(segments);
            } catch (RuntimeException ex) {
                last = ex;
                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(500L * attempt * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new ArkRagException(ErrorCode.INGEST_FAILED, "嵌入请求被中断", null, ie);
                    }
                }
            }
        }
        throw new ArkRagException(ErrorCode.INGEST_FAILED, "Embedding API 调用失败（已重试 "
                + MAX_RETRIES + " 次）：" + rootMessage(last), null, last);
    }

    /** 按配置指纹取模型：配置变化 → 重建实例（热生效核心）。 */
    private OpenAiEmbeddingModel model() {
        ArkRagConfig.Embedding cfg = settings.effectiveEmbedding();
        if (!cfg.configured()) {
            throw new ArkRagException(ErrorCode.EMBEDDING_NOT_CONFIGURED,
                    "Embedding 模型未配置：请在控制台「模型配置」页（或 application.yml）配置 OpenAI 兼容的 base-url、api-key、model");
        }
        String fingerprint = cfg.baseUrl() + "|" + cfg.apiKey() + "|" + cfg.model() + "|" + cfg.dims();
        Cached cached = cache.get();
        if (cached == null || !cached.fingerprint().equals(fingerprint)) {
            var builder = OpenAiEmbeddingModel.builder()
                    .baseUrl(cfg.baseUrl())
                    .apiKey(cfg.apiKey())
                    .modelName(cfg.model())
                    .timeout(Duration.ofSeconds(60));
            if (cfg.dims() != null) {
                builder.dimensions(cfg.dims());
            }
            cached = new Cached(fingerprint, builder.build());
            cache.set(cached);
        }
        return cached.model();
    }

    private static String rootMessage(Throwable t) {
        while (t != null && t.getCause() != null) {
            t = t.getCause();
        }
        return t == null ? "unknown" : String.valueOf(t.getMessage());
    }
}
