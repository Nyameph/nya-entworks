package io.github.Nyameph.nyaentworks.common;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 统一响应体。
 * <p>单人自用系统不做错误码体系：{@code success} 判成败，{@code message} 直接给人看。
 * 页面上「因为什么所以做不了」比一个数字码有用 —— 环境缺东西是这个项目最常见的失败原因。
 */
@Schema(name = "ApiResult", description = "统一响应体")
public record ApiResult<T>(boolean success, String message, T data) {

    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>(true, null, data);
    }

    public static <T> ApiResult<T> ok() {
        return new ApiResult<>(true, null, null);
    }

    public static <T> ApiResult<T> fail(String message) {
        return new ApiResult<>(false, message, null);
    }
}
