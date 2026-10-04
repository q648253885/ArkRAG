package com.arkrag.server.config;

import com.arkrag.core.config.ArkRagConfig;
import com.arkrag.core.exception.ArkRagException;
import com.arkrag.core.exception.ErrorCode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * arkrag.* 配置绑定与启动自检。
 * 安全红线：token 未设置或 <16 字符时拒绝启动（本机回环也留基本访问控制，
 * 防止同机其他用户进程静默读走知识库）。
 */
@ConfigurationProperties(prefix = "arkrag")
public class ArkRagServerProperties {

    private Server server = new Server();
    private String dataDir = System.getProperty("user.home") + "/.arkrag/data";
    private Embedding embedding = new Embedding();
    private Chunk chunk = new Chunk();
    private Ingest ingest = new Ingest();
    private Chat chat = new Chat();

    public static class Server {
        private int port = 8964;
        private String token;

        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
    }

    public static class Embedding {
        private String baseUrl;
        private String apiKey;
        private String model;
        private Integer dims;
        private int batchSize = 32;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public Integer getDims() { return dims; }
        public void setDims(Integer dims) { this.dims = dims; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    }

    public static class Chunk {
        private int size = 1000;
        private int overlap = 150;

        public int getSize() { return size; }
        public void setSize(int size) { this.size = size; }
        public int getOverlap() { return overlap; }
        public void setOverlap(int overlap) { this.overlap = overlap; }
    }

    public static class Ingest {
        private long maxFileSizeMb = 50;
        private int workers = 2;

        public long getMaxFileSizeMb() { return maxFileSizeMb; }
        public void setMaxFileSizeMb(long maxFileSizeMb) { this.maxFileSizeMb = maxFileSizeMb; }
        public int getWorkers() { return workers; }
        public void setWorkers(int workers) { this.workers = workers; }
    }

    /** Chat LLM 启动初值（v1.1；运行时配置优先）。 */
    public static class Chat {
        private String baseUrl;
        private String apiKey;
        private String model;
        private Double temperature;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public Double getTemperature() { return temperature; }
        public void setTemperature(Double temperature) { this.temperature = temperature; }
    }

    /** 启动期校验：失败直接终止进程（fail-fast，避免带病运行）。 */
    public void validate() {
        if (server.token == null || server.token.isBlank() || server.token.length() < 16) {
            throw new IllegalStateException(
                    "arkrag.server.token 未配置或长度 <16： refusing to start。"
                            + "请在 application.yml / 环境变量 ARKRAG_TOKEN 中设置 ≥16 位 token");
        }
    }

    /** 转引擎配置（core 不依赖 Spring）。dims=0 视为未显式配置（用首个真实向量回填）。 */
    public ArkRagConfig toCoreConfig() {
        var chatCfg = (chat.getBaseUrl() == null || chat.getBaseUrl().isBlank()
                || chat.getApiKey() == null || chat.getApiKey().isBlank()
                || chat.getModel() == null || chat.getModel().isBlank())
                ? null
                : new com.arkrag.core.config.ArkRagConfig.Chat(chat.getBaseUrl(), chat.getApiKey(),
                        chat.getModel(), chat.getTemperature());
        return new ArkRagConfig(
                Path.of(dataDir).toAbsolutePath().toString(),
                new ArkRagConfig.Embedding(embedding.baseUrl, embedding.apiKey, embedding.model,
                        embedding.dims != null && embedding.dims > 0 ? embedding.dims : null,
                        embedding.batchSize),
                new ArkRagConfig.Chunking(chunk.size, chunk.overlap),
                new ArkRagConfig.Ingest(ingest.maxFileSizeMb * 1024 * 1024, ingest.workers,
                        embedding.batchSize),
                chatCfg);
    }

    public Server getServer() { return server; }
    public void setServer(Server server) { this.server = server; }
    public String getDataDir() { return dataDir; }
    public void setDataDir(String dataDir) { this.dataDir = dataDir; }
    public Embedding getEmbedding() { return embedding; }
    public void setEmbedding(Embedding embedding) { this.embedding = embedding; }
    public Chunk getChunk() { return chunk; }
    public void setChunk(Chunk chunk) { this.chunk = chunk; }
    public Ingest getIngest() { return ingest; }
    public void setIngest(Ingest ingest) { this.ingest = ingest; }
    public Chat getChat() { return chat; }
    public void setChat(Chat chat) { this.chat = chat; }

    /** 供错误映射使用：业务异常统一走 core 错误码。 */
    public static ArkRagException badRequest(String msg) {
        return new ArkRagException(ErrorCode.VALIDATION, msg);
    }
}
