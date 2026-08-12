package com.pulseflow.boot.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> {
                    SaRouter.match("/api/**", r -> StpUtil.checkLogin());
                    SaRouter.match("/api/events", r -> {});
                }))
                .addPathPatterns("/**")
                // /api/events : public event ingestion endpoint
                // /api/auth/dev-login : opt-in demo login (DevLoginController, off by default)
                .excludePathPatterns("/api/events", "/api/auth/dev-login");
    }
}
