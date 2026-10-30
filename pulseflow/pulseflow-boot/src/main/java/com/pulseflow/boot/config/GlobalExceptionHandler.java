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

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 将未登录请求转换为 HTTP 401 和统一 API 失败响应。
     */
    @ExceptionHandler(NotLoginException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<Void> handleNotLogin(NotLoginException e) {
        return ApiResponse.fail(401, "Unauthorized");
    }

    /**
     * 记录业务错误码与消息，并以 HTTP 500 返回该业务异常的公开消息。
     */
    @ExceptionHandler(PulseFlowException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handlePulseFlowException(PulseFlowException e) {
        log.error("PulseFlow exception: code={}, message={}", e.getErrorCode(), e.getMessage());
        return ApiResponse.fail(500, e.getMessage());
    }

    /**
     * 将重复事件异常转换为 HTTP 409，响应中携带重复的事件 ID。
     */
    @ExceptionHandler(DuplicateEventException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleDuplicateEvent(DuplicateEventException e) {
        return ApiResponse.fail(409, "Duplicate event: " + e.getEventId());
    }

    /**
     * 将 Campaign 资源缺失转换为 HTTP 404，保留业务层提供的说明。
     */
    @ExceptionHandler(CampaignResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleCampaignNotFound(CampaignResourceNotFoundException e) {
        return ApiResponse.fail(404, e.getMessage());
    }

    /**
     * 将草稿状态等业务冲突转换为 HTTP 409。
     */
    @ExceptionHandler(CampaignConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleCampaignConflict(CampaignConflictException e) {
        return ApiResponse.fail(409, e.getMessage());
    }

    /**
     * 将 Campaign 归属或权限校验失败转换为 HTTP 403。
     */
    @ExceptionHandler(CampaignForbiddenException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Void> handleCampaignForbidden(CampaignForbiddenException e) {
        return ApiResponse.fail(403, e.getMessage());
    }

    /**
     * 汇总 Bean Validation 字段错误为可读消息，使用 HTTP 400 返回；无字段错误时使用默认提示。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b).orElse("Validation failed");
        return ApiResponse.fail(400, msg);
    }

    /**
     * 将非法参数异常转换为 HTTP 400，并返回参数校验说明。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleBadRequest(IllegalArgumentException e) {
        return ApiResponse.fail(400, e.getMessage());
    }

    /**
     * 记录未处理异常的完整堆栈，对外统一返回 HTTP 500 和通用错误消息。
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleGeneral(Exception e) {
        log.error("Unhandled exception", e);
        return ApiResponse.fail(500, "Internal server error");
    }
}
