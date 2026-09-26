package com.pulseflow.boot.agenttools;

import com.pulseflow.campaign.exception.CampaignResourceNotFoundException;
import com.pulseflow.campaign.exception.CampaignForbiddenException;
import com.pulseflow.campaign.exception.CampaignConflictException;
import cn.dev33.satoken.exception.NotLoginException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** Error codes only; never echo request bodies, SQL, credentials or raw exceptions. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {AgentToolController.class, AgentDraftController.class,
        AgentProposalGateway.class})
public class AgentToolExceptionHandler {
    @ExceptionHandler(AgentGatewayException.class)
    public ResponseEntity<Map<String, String>> gateway(AgentGatewayException error) {
        return ResponseEntity.status(error.status).body(Map.of("error", error.code));
    }
    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<Map<String, String>> unauthorized(NotLoginException ignored) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
    }
    @ExceptionHandler(CampaignForbiddenException.class)
    public ResponseEntity<Map<String, String>> forbidden(CampaignForbiddenException ignored) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "draft_grant_denied"));
    }
    @ExceptionHandler(CampaignConflictException.class)
    public ResponseEntity<Map<String, String>> conflict(CampaignConflictException ignored) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "draft_conflict"));
    }
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, String>> invalid(Exception ignored) {
        return ResponseEntity.badRequest().body(Map.of("error", "invalid_tool_request"));
    }

    @ExceptionHandler(CampaignResourceNotFoundException.class)
    public ResponseEntity<Map<String, String>> missing(CampaignResourceNotFoundException ignored) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> unavailable(DataAccessException ignored) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "tool_unavailable"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> failed(Exception ignored) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "tool_failed"));
    }
}
