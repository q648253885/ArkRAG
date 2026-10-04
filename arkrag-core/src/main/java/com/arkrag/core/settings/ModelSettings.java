package com.arkrag.core.settings;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 运行时模型配置（dataDir/settings.json 持久化；null 组 = 回落 application.yml 初始值）。
 * local-first 安全口径：apiKey 明文落盘与 ArkWork models.json 同级；对外接口一律脱敏。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ModelSettings {

    private Embedding embedding;
    private Chat chat;
    private Long updatedAt;

    public Embedding getEmbedding() { return embedding; }
    public void setEmbedding(Embedding embedding) { this.embedding = embedding; }
    public Chat getChat() { return chat; }
    public void setChat(Chat chat) { this.chat = chat; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }

    /** OpenAI 兼容 Embedding 配置；dims 为空 = 用首个真实向量回填。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Embedding(String baseUrl, String apiKey, String model, Integer dims) {
        public boolean configured() {
            return nz(baseUrl) && nz(apiKey) && nz(model);
        }
    }

    /** OpenAI 兼容 Chat 配置；temperature 可空（用端点默认）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Chat(String baseUrl, String apiKey, String model, Double temperature) {
        public boolean configured() {
            return nz(baseUrl) && nz(apiKey) && nz(model);
        }
    }

    private static boolean nz(String s) {
        return s != null && !s.isBlank();
    }
}
