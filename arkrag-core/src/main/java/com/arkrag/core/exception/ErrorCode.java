package com.arkrag.core.exception;

/**
 * 统一错误码（契约见 docs/v1.0/04-system-design.md §5.4）。
 * httpStatus 供 server 模块映射 HTTP 状态；stdio 桥只使用 code + message。
 */
public enum ErrorCode {
    UNAUTHORIZED(401),
    VALIDATION(400),
    KB_NOT_FOUND(404),
    DOC_NOT_FOUND(404),
    UNSUPPORTED_FILE_TYPE(415),
    FILE_TOO_LARGE(413),
    EMBEDDING_NOT_CONFIGURED(409),
    EMBEDDING_DIMENSION_MISMATCH(409),
    CHAT_NOT_CONFIGURED(409),
    INGEST_FAILED(500),
    SERVER_ERROR(500);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    /** 该错误对应的 HTTP 状态码。 */
    public int httpStatus() {
        return httpStatus;
    }
}
