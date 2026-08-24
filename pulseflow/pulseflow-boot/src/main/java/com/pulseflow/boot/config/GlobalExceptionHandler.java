package com.pulseflow.boot.config;

import com.pulseflow.common.exception.DuplicateEventException;
import com.pulseflow.common.exception.PulseFlowException;
import com.pulseflow.common.model.ApiResponse;
import com.pulseflow.ai.support.AiConflictException;
import com.pulseflow.ai.support.AiDisabledException;
import com.pulseflow.ai.support.AiForbiddenException;
import com.pulseflow.ai.support.AiOutputInvalidException;
import com.pulseflow.ai.support.AiPiiGuardrailUnavailableException;
import com.pulseflow.ai.support.AiProviderException;
import com.pulseflow.ai.support.AiResourceNotFoundException;
import com.pulseflow.ai.support.AiSensitiveDataDetectedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(PulseFlowException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handlePulseFlowException(PulseFlowException e) {
        log.error("PulseFlow exception: code={}, message={}", e.getErrorCode(), e.getMessage());
        return ApiResponse.fail(500, e.getMessage());
    }

    @ExceptionHandler(AiDisabledException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ApiResponse<Void> handleAiDisabled(AiDisabledException e) {
        return ApiResponse.fail(503, "AI disabled: " + e.getMessage());
    }

    @ExceptionHandler(AiProviderException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ApiResponse<Void> handleAiProvider(AiProviderException e) {
        log.error("AI provider failure: code={}, message={}", e.getErrorCode(), e.getMessage());
        return ApiResponse.fail(503, "AI provider error: " + e.getMessage());
    }

    @ExceptionHandler(AiSensitiveDataDetectedException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ApiResponse<Void> handleAiSensitiveData(AiSensitiveDataDetectedException e) {
        // Categories are safe diagnostics; the exception never carries the
        // original entity text in its message or response.
        log.warn("AI input blocked by guardrail: code={}, categories={}",
                e.getErrorCode(), e.getCategories());
        return ApiResponse.fail(422, e.getErrorCode() + ": " + e.getMessage());
    }

    @ExceptionHandler(AiPiiGuardrailUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ApiResponse<Void> handleAiPiiGuardrailUnavailable(AiPiiGuardrailUnavailableException e) {
        log.warn("AI PII guardrail unavailable: code={}", e.getErrorCode());
        return ApiResponse.fail(503, e.getErrorCode() + ": PII guardrail temporarily unavailable");
    }

    @ExceptionHandler(AiOutputInvalidException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ApiResponse<Void> handleAiOutputInvalid(AiOutputInvalidException e) {
        log.warn("AI output invalid: code={}, message={}", e.getErrorCode(), e.getMessage());
        return ApiResponse.fail(422, "AI output rejected: " + e.getMessage());
    }

    @ExceptionHandler(DuplicateEventException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleDuplicateEvent(DuplicateEventException e) {
        return ApiResponse.fail(409, "Duplicate event: " + e.getEventId());
    }

    @ExceptionHandler(AiResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleAiNotFound(AiResourceNotFoundException e) {
        return ApiResponse.fail(404, e.getMessage());
    }

    @ExceptionHandler(AiConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleAiConflict(AiConflictException e) {
        return ApiResponse.fail(409, e.getMessage());
    }

    @ExceptionHandler(AiForbiddenException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Void> handleAiForbidden(AiForbiddenException e) {
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

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleGeneral(Exception e) {
        log.error("Unhandled exception", e);
        return ApiResponse.fail(500, "Internal server error");
    }
}
