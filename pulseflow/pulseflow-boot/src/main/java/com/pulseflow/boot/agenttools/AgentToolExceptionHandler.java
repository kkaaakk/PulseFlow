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
@RestControllerAdvice(assignableTypes = {AgentToolController.class, AgentProposalGateway.class,
        CampaignProposalDraftController.class})
public class AgentToolExceptionHandler {
    /**
     * 保留网关异常指定的 HTTP 状态，仅向调用方输出稳定错误码。
     */
    @ExceptionHandler(AgentGatewayException.class)
    public ResponseEntity<Map<String, String>> gateway(AgentGatewayException error) {
        return ResponseEntity.status(error.status).body(Map.of("error", error.code));
    }
    /**
     * 将未登录异常转换为 401，不输出会话或鉴权内部信息。
     */
    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<Map<String, String>> unauthorized(NotLoginException ignored) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
    }
    /**
     * 将资源归属异常转换为 403，并隐藏具体操作员与资源信息。
     */
    @ExceptionHandler(CampaignForbiddenException.class)
    public ResponseEntity<Map<String, String>> forbidden(CampaignForbiddenException ignored) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
    }
    /**
     * 将草稿生命周期冲突转换为 409，返回统一 draft_conflict 错误码。
     */
    @ExceptionHandler(CampaignConflictException.class)
    public ResponseEntity<Map<String, String>> conflict(CampaignConflictException ignored) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "draft_conflict"));
    }
    /**
     * 将参数或 JSON 解析异常转换为 400，避免回显原始请求内容。
     */
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, String>> invalid(Exception ignored) {
        return ResponseEntity.badRequest().body(Map.of("error", "invalid_tool_request"));
    }

    /**
     * 将业务资源不存在异常转换为 404，统一隐藏底层资源查询细节。
     */
    @ExceptionHandler(CampaignResourceNotFoundException.class)
    public ResponseEntity<Map<String, String>> missing(CampaignResourceNotFoundException ignored) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
    }

    /**
     * 将数据库访问故障转换为 503，告知工具调用方当前依赖不可用。
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> unavailable(DataAccessException ignored) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "tool_unavailable"));
    }

    /**
     * 兜底处理未分类异常并返回 500，不将堆栈或异常消息发送给 Agent。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> failed(Exception ignored) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "tool_failed"));
    }
}
