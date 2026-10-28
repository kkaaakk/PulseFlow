package com.pulseflow.boot.agenttools;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** 内部工具机器鉴权过滤器配置。 */
@Configuration
public class AgentToolAuthConfig {
    /** 为内部工具路由注册机器令牌过滤器，限制其生效路径和执行顺序。 */
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
