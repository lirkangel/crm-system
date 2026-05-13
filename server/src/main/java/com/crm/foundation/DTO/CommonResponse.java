package com.crm.foundation.DTO;


public record CommonResponse<T>(
    T data,
    String message
) {
    public static <T> CommonResponse<T> from(T data, String message) {
        return new CommonResponse<>(data, message);
    }

    public static <T> CommonResponse<T> from(T data) {
        return new CommonResponse<>(data, "success");
    }
}

