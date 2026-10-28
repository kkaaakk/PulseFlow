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

/**
 * 内部接口安全错误映射，只返回固定代码。
 * Error codes only; never echo request bodies, SQL, credentials or raw exceptions. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {AgentToolController.class, AgentProposalGateway.class,
        CampaignProposalDraftController.class})
public class AgentToolExceptionHandler {
    /** 保留网关规定的状态与安全错误码，统一输出内部接口错误响应。 */
    @ExceptionHandler(AgentGatewayException.class)
    public ResponseEntity<Map<String, String>> gateway(AgentGatewayException error) {
        return ResponseEntity.status(error.status).body(Map.of("error", error.code));
    }
    /** 把未登录异常映射为未授权错误，不返回会话内部信息。 */
    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<Map<String, String>> unauthorized(NotLoginException ignored) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
    }
    /** 把归属不符映射为禁止访问响应，隐藏原始异常细节。 */
    @ExceptionHandler(CampaignForbiddenException.class)
    public ResponseEntity<Map<String, String>> forbidden(CampaignForbiddenException ignored) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
    }
    /** 把业务状态冲突映射为 409，提示调用方刷新状态后再操作。 */
    @ExceptionHandler(CampaignConflictException.class)
    public ResponseEntity<Map<String, String>> conflict(CampaignConflictException ignored) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "draft_conflict"));
    }
    /** 将输入校验失败转换为固定参数错误，避免回显请求正文。 */
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, String>> invalid(Exception ignored) {
        return ResponseEntity.badRequest().body(Map.of("error", "invalid_tool_request"));
    }

    /** 将业务资源不存在转换为统一的 404 响应。 */
    @ExceptionHandler(CampaignResourceNotFoundException.class)
    public ResponseEntity<Map<String, String>> missing(CampaignResourceNotFoundException ignored) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
    }

    /** 将数据库异常转换为服务不可用响应，避免泄露 SQL 与连接信息。 */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> unavailable(DataAccessException ignored) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "tool_unavailable"));
    }

    /** 兜底处理未分类异常，返回固定错误而不暴露堆栈或业务内容。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> failed(Exception ignored) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "tool_failed"));
    }
}
