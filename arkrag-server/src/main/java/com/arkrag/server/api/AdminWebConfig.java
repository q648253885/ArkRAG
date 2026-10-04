package com.arkrag.server.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * /admin 管理控制台静态资源（v1.1）。
 * 静态 HTML/JS 免鉴权（无敏感数据，数据全走鉴权 API，见 AuthFilter）；
 * classpath:/admin/ 由 spring-boot repackage 打进 fat-jar，单 jar 交付。
 */
@Configuration
public class AdminWebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/admin", "/admin/index.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/admin/**")
                .addResourceLocations("classpath:/admin/");
    }
}
