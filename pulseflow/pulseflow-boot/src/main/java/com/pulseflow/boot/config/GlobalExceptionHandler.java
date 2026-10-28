package com.pulseflow.boot.config;

import cn.dev33.satoken.exception.NotLoginException;
import com.pulseflow.common.exception.DuplicateEventException;
import com.pulseflow.common.exception.PulseFlowException;
import com.pulseflow.common.model.ApiResponse;
import com.pulseflow.campaign.exception.CampaignConflictException;
import com.pulseflow.campaign.exception.CampaignForbiddenException;
import com.pulseflow.campaign.exception.CampaignResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 将业务、参数及基础设施异常转换为统一 API 响应。 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 将未登录错误转换为统一 API 响应，提示客户端重新认证。 */
    @ExceptionHandler(NotLoginException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<Void> handleNotLogin(NotLoginException e) {
        return ApiResponse.fail(401, "Unauthorized");
    }

    /** 保留应用业务错误码与消息，封装为统一失败响应。 */
    @ExceptionHandler(PulseFlowException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handlePulseFlowException(PulseFlowException e) {
        log.error("PulseFlow exception: code={}, message={}", e.getErrorCode(), e.getMessage());
        return ApiResponse.fail(500, e.getMessage());
    }

    /** 处理重复事件业务异常，以统一响应说明幂等冲突。 */
    @ExceptionHandler(DuplicateEventException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleDuplicateEvent(DuplicateEventException e) {
        return ApiResponse.fail(409, "Duplicate event: " + e.getEventId());
    }

    /** 将活动或草稿不存在映射为资源未找到响应。 */
    @ExceptionHandler(CampaignResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleCampaignNotFound(CampaignResourceNotFoundException e) {
        return ApiResponse.fail(404, e.getMessage());
    }

    /** 将活动或草稿状态不允许的操作映射为冲突响应。 */
    @ExceptionHandler(CampaignConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleCampaignConflict(CampaignConflictException e) {
        return ApiResponse.fail(409, e.getMessage());
    }

    /** 将操作员无权访问活动资源映射为禁止访问响应。 */
    @ExceptionHandler(CampaignForbiddenException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Void> handleCampaignForbidden(CampaignForbiddenException e) {
        return ApiResponse.fail(403, e.getMessage());
    }

    /** 汇总请求字段校验错误，供客户端定位无效参数。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b).orElse("Validation failed");
        return ApiResponse.fail(400, msg);
    }

    /** 处理参数格式与取值错误，统一返回客户端错误响应。 */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleBadRequest(IllegalArgumentException e) {
        return ApiResponse.fail(400, e.getMessage());
    }

    /** 记录未处理异常并返回通用失败响应，避免接口直接暴露堆栈。 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleGeneral(Exception e) {
        log.error("Unhandled exception", e);
        return ApiResponse.fail(500, "Internal server error");
    }
}
