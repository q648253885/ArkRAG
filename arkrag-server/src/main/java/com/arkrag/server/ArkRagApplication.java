package com.arkrag.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * ArkRAG 服务入口。启动即自检 token（缺失/过短拒绝启动，见 ArkRagServerProperties#validate）。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ArkRagApplication {

    public static void main(String[] args) {
        SpringApplication.run(ArkRagApplication.class, args);
    }
}
