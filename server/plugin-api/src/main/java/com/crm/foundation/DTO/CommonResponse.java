package com.crm.foundation.DTO;

public record CommonResponse<T>(
    boolean success,
    String code,
    String message,
    T data
) {
    public static final String SUCCESS_CODE = "SUCCESS";

    public static <T> CommonResponse<T> success(T data) {
        return success(SUCCESS_CODE, "success", data);
    }

    public static <T> CommonResponse<T> success(String code, String message, T data) {
        return new CommonResponse<>(true, code, message, data);
    }

    public static <T> CommonResponse<T> failure(String code, String message) {
        return failure(code, message, null);
    }

    public static <T> CommonResponse<T> failure(String code, String message, T data) {
        return new CommonResponse<>(false, code, message, data);
    }
}
