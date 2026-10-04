package com.arkrag.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 知识库（一等实体）。每个 KB 独立持有文档与向量索引；
 * embeddingModel/dims 为建库时快照——检索时与查询向量做维度一致性校验，
 * 不匹配必须 rebuild（禁止新旧模型向量混用）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class KnowledgeBase {
    private String id;
    private String name;
    private String description;
    private String embeddingModel;
    private int dims;
    private int chunkSize;
    private int chunkOverlap;
    private long createdAt;
    private long updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
    public int getDims() { return dims; }
    public void setDims(int dims) { this.dims = dims; }
    public int getChunkSize() { return chunkSize; }
    public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }
    public int getChunkOverlap() { return chunkOverlap; }
    public void setChunkOverlap(int chunkOverlap) { this.chunkOverlap = chunkOverlap; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
