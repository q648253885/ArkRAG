package com.arkrag.core.model;

/** 文档摄入状态机：PENDING → PARSING → EMBEDDING → READY；PARSING/EMBEDDING 任一步失败 → FAILED。 */
public enum DocStatus {
    PENDING, PARSING, EMBEDDING, READY, FAILED
}
