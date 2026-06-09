package com.crm.foundation.Controller;

import com.crm.foundation.DTO.CommonResponse;
import com.crm.foundation.Exception.AuthException;
import com.crm.foundation.Exception.BadRequestException;
import com.crm.foundation.Exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class ErrorHandler {
    private final Logger log = LoggerFactory.getLogger(getClass());

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<CommonResponse<Void>> handleAuthException(AuthException ex) {
        log.debug("Returning HTTP 401 for AuthException: {}", ex.getCode());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(CommonResponse.failure(ex.getCode(), ex.getMessage()));
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public CommonResponse<Void> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .collect(Collectors.joining(", "));
        log.debug("Returning HTTP 400 for validation failure: {}", message);
        return CommonResponse.failure("VALIDATION_ERROR", message);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(IllegalArgumentException.class)
    public CommonResponse<Void> processValidationError(IllegalArgumentException ex) {
        log.debug("Returning HTTP 400 for IllegalArgumentException", ex);
        return CommonResponse.failure("BAD_REQUEST", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(BadRequestException.class)
    public CommonResponse<Void> processBadRequest(BadRequestException ex) {
        log.debug("Returning HTTP 400 for BadRequestException", ex);
        return CommonResponse.failure("BAD_REQUEST", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ExceptionHandler(NotFoundException.class)
    public CommonResponse<Void> processNotFound(NotFoundException ex) {
        log.debug("Returning HTTP 404 for NotFoundException", ex);
        return CommonResponse.failure("NOT_FOUND", ex.getMessage());
    }
}
