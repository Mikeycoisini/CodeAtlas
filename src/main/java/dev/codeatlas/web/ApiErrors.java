package dev.codeatlas.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import java.util.*;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ApiException.class) ResponseEntity<?> api(ApiException error) { return response(error.status, error.code, error.getMessage()); }
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> invalid(Exception error) { return response(400, "INVALID_REQUEST", "请求参数无效"); }
    @ExceptionHandler(Exception.class) ResponseEntity<?> unexpected(Exception error) { return response(500, "SYSTEM_ERROR", "系统处理失败，请检查服务状态后重试"); }
    private ResponseEntity<?> response(int status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message, "details", Map.of(), "requestId", UUID.randomUUID().toString()));
    }
}
