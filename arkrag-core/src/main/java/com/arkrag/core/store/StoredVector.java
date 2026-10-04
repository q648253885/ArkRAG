package com.arkrag.core.store;

import com.arkrag.core.model.ChunkMeta;

/**
 * 一条已入库向量（自研索引的存储单元）。
 * id 即向量条目 id（摄入时生成 UUID）；vec 为原始向量；meta 为引用溯源负载。
 */
public record StoredVector(String id, float[] vec, ChunkMeta meta) {
}
