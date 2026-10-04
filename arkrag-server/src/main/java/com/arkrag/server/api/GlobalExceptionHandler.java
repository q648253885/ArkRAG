package com.arkrag.server.api;

import com.arkrag.core.exception.ArkRagException;
import com.arkrag.server.api.dto.Dto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 统一异常 → HTTP 映射（契约 §5.4 错误码总表）。
 * 原则：业务异常（ArkRagException）按 code 映射；未知异常一律 500 + 通用文案
 * （细节进日志，不透给客户端）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ArkRagException.class)
    public ResponseEntity<Dto.ErrorResponse> business(ArkRagException e) {
        return ResponseEntity.status(e.code().httpStatus())
                .body(new Dto.ErrorResponse(new Dto.ErrorBody(e.code().name(), e.getMessage(), e.details())));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Dto.ErrorResponse> invalidBody(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().isEmpty()
                ? "请求体校验失败"
                : e.getBindingResult().getFieldErrors().get(0).getField() + " "
                        + e.getBindingResult().getFieldErrors().get(0).getDefaultMessage();
        return ResponseEntity.status(400)
                .body(new Dto.ErrorResponse(new Dto.ErrorBody("VALIDATION", msg, null)));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Dto.ErrorResponse> tooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(413)
                .body(new Dto.ErrorResponse(new Dto.ErrorBody("FILE_TOO_LARGE", "上传文件超过大小上限", null)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Dto.ErrorResponse> unknown(Exception e) {
        LOG.error("未处理异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new Dto.ErrorResponse(new Dto.ErrorBody("SERVER_ERROR", "服务内部错误，请查看服务日志", null)));
    }
}
