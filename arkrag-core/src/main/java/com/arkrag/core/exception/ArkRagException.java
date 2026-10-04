package com.arkrag.core.exception;

import java.util.Map;

/**
 * ArkRAG 业务异常：携带统一错误码与人话消息；details 可选携带结构化补充信息
 * （如 INGEST_FAILED 的阶段与底层原因）。所有对外可见的失败都应抛本类型，
 * 禁止把底层异常消息直接透给客户端（避免泄露路径/堆栈）。
 */
public class ArkRagException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> details;

    public ArkRagException(ErrorCode code, String message) {
        this(code, message, null, null);
    }

    public ArkRagException(ErrorCode code, String message, Map<String, Object> details) {
        this(code, message, details, null);
    }

    public ArkRagException(ErrorCode code, String message, Map<String, Object> details, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.details = details;
    }

    /** 统一错误码（非空）。 */
    public ErrorCode code() {
        return code;
    }

    /** 结构化补充信息，可为 null；序列化进错误响应的 details 字段。 */
    public Map<String, Object> details() {
        return details;
    }
}
