package com.crm.foundation.Controller;

import com.crm.foundation.DTO.CommonResponse;
import com.crm.foundation.Exception.BadRequestException;
import com.crm.foundation.Exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.annotation.ResponseStatus;

@RestControllerAdvice
public class ErrorHandler {
    private final Logger log = LoggerFactory.getLogger(getClass());

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(IllegalArgumentException.class)
    public CommonResponse<Void> processValidationError(IllegalArgumentException ex) {
        log.debug("Returning HTTP 400 for IllegalArgumentException", ex);
        return CommonResponse.from(null, ex.getMessage());
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(BadRequestException.class)
    public CommonResponse<Void> processBadRequest(BadRequestException ex) {
        log.debug("Returning HTTP 400 for BadRequestException", ex);
        return CommonResponse.from(null, ex.getMessage());
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ExceptionHandler(NotFoundException.class)
    public CommonResponse<Void> processNotFound(NotFoundException ex) {
        log.debug("Returning HTTP 404 for NotFoundException", ex);
        return CommonResponse.from(null, ex.getMessage());
    }
}
