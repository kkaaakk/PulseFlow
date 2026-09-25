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

    @ExceptionHandler(NotLoginException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<Void> handleNotLogin(NotLoginException e) {
        return ApiResponse.fail(401, "Unauthorized");
    }

    @ExceptionHandler(PulseFlowException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handlePulseFlowException(PulseFlowException e) {
        log.error("PulseFlow exception: code={}, message={}", e.getErrorCode(), e.getMessage());
        return ApiResponse.fail(500, e.getMessage());
    }

    @ExceptionHandler(DuplicateEventException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleDuplicateEvent(DuplicateEventException e) {
        return ApiResponse.fail(409, "Duplicate event: " + e.getEventId());
    }

    @ExceptionHandler(CampaignResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleCampaignNotFound(CampaignResourceNotFoundException e) {
        return ApiResponse.fail(404, e.getMessage());
    }

    @ExceptionHandler(CampaignConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleCampaignConflict(CampaignConflictException e) {
        return ApiResponse.fail(409, e.getMessage());
    }

    @ExceptionHandler(CampaignForbiddenException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Void> handleCampaignForbidden(CampaignForbiddenException e) {
        return ApiResponse.fail(403, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b).orElse("Validation failed");
        return ApiResponse.fail(400, msg);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleBadRequest(IllegalArgumentException e) {
        return ApiResponse.fail(400, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleGeneral(Exception e) {
        log.error("Unhandled exception", e);
        return ApiResponse.fail(500, "Internal server error");
    }
}
