package com.arkrag.core.model;

/**
 * 向量条目负载：随 embedding 一起存入 InMemoryEmbeddingStore，
 * 检索命中后原样带回（引用溯源的最小完备集）。
 */
public record ChunkMeta(String kbId, String docId, String docName, int chunkIndex, String text) {
}
