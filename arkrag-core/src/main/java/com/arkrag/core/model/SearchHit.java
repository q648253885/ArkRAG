package com.arkrag.core.model;

/**
 * 单条检索命中。score 为余弦相似度（LangChain4j 归一化后），越高越相关。
 */
public record SearchHit(String kbId, String kbName, String docId, String docName,
                        int chunkIndex, String text, double score) {
}
