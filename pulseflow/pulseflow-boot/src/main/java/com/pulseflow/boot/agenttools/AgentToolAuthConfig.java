package com.pulseflow.boot.agenttools;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class AgentToolAuthConfig {
    @Bean
    FilterRegistrationBean<AgentToolAuthFilter> agentToolAuthFilter(
            @Value("${pulseflow.agent.internal-token:}") String token) {
        FilterRegistrationBean<AgentToolAuthFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new AgentToolAuthFilter(token));
        registration.addUrlPatterns("/internal/v1/agent-tools/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
