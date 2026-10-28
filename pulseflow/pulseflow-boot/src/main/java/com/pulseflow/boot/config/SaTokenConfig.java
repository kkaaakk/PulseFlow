package com.pulseflow.boot.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 定义需要认证的业务路由及公共路径例外。 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    /** 为需要登录的业务路由注册 Sa-Token 校验，并应用公开路径排除规则。 */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> {
                    SaRouter.match("/api/**", r -> StpUtil.checkLogin());
                    SaRouter.match("/api/events", r -> {});
                }))
                .addPathPatterns("/**")
                // /api/events : public event ingestion endpoint
                // /api/auth/login and /api/auth/dev-login are public login entry points.
                // The latter remains opt-in and is only intended for the existing demo script.
                .excludePathPatterns("/api/events", "/api/auth/login", "/api/auth/dev-login");
    }
}
