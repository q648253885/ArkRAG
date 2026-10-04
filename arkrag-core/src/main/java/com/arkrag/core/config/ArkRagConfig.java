package com.arkrag.core.config;

/**
 * 引擎配置（由 server 的 application.yml 绑定后构造，stdio 桥不使用）。
 * embedding 为空（baseUrl/apiKey/model 任一缺失）时服务可启动，
 * 但摄入与检索返回 EMBEDDING_NOT_CONFIGURED——便于先跑通 KB 管理再补模型。
 */
public class ArkRagConfig {

    private final String dataDir;
    private final Embedding embedding;
    private final Chunking chunking;
    private final Ingest ingest;
    private final Chat chat;

    public ArkRagConfig(String dataDir, Embedding embedding, Chunking chunking, Ingest ingest) {
        this(dataDir, embedding, chunking, ingest, null);
    }

    public ArkRagConfig(String dataDir, Embedding embedding, Chunking chunking, Ingest ingest, Chat chat) {
        this.dataDir = dataDir;
        this.embedding = embedding;
        this.chunking = chunking;
        this.ingest = ingest;
        this.chat = chat;
    }

    public String dataDir() {
        return dataDir;
    }

    public Embedding embedding() {
        return embedding;
    }

    public Chunking chunking() {
        return chunking;
    }

    public Ingest ingest() {
        return ingest;
    }

    /** Chat LLM 启动初值（可为 null；v1.1 起运行时配置优先）。 */
    public Chat chat() {
        return chat;
    }

    /** Embedding 模型连接配置（OpenAI 兼容 /v1/embeddings）。 */
    public record Embedding(String baseUrl, String apiKey, String model, Integer dims, int batchSize) {
        public boolean configured() {
            return notBlank(baseUrl) && notBlank(apiKey) && notBlank(model);
        }
    }

    /** 切块参数（字符口径，段落边界优先的递归切块）。 */
    public record Chunking(int size, int overlap) {
    }

    /** 摄入参数：单文件上限与全局并发 worker 数。 */
    public record Ingest(long maxFileSizeBytes, int workers, int batchSize) {
    }

    /** OpenAI 兼容 Chat 配置（启动初值；可为 null）。 */
    public record Chat(String baseUrl, String apiKey, String model, Double temperature) {
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
