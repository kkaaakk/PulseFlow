package com.pulseflow.boot.agenttools;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** PROPOSE only. No confirm, activate, delivery or frequency-update mapping exists here. */
@RestController
@RequestMapping("/internal/v1/agent-tools/campaign-drafts")
@RequiredArgsConstructor
public class AgentDraftController {
    private final AgentDraftService service;
    @PostMapping
    public AgentDraftService.Response create(
            @RequestHeader(name="X-PulseFlow-Draft-Grant", required=false) String grant,
            @RequestBody AgentDraftService.Request request) {
        return service.create(grant, request);
    }
}
