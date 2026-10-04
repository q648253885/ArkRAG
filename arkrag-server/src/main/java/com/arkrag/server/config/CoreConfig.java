package com.arkrag.server.config;

import com.arkrag.core.config.ArkRagConfig;
import com.arkrag.core.embedding.EmbeddingService;
import com.arkrag.core.pipeline.IngestService;
import com.arkrag.core.search.SearchService;
import com.arkrag.core.service.KnowledgeBaseService;
import com.arkrag.core.store.IndexRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * core 引擎的 Spring 装配。启动顺序：校验配置 → 建引擎 → 加载索引快照；
 * 停机钩子：给在途摄入最多 10s 收尾，然后强制落盘全部索引再退出。
 */
@Configuration
@EnableConfigurationProperties(ArkRagServerProperties.class)
public class CoreConfig {

    private static final Logger LOG = LoggerFactory.getLogger(CoreConfig.class);

    private IngestService ingestService;
    private IndexRegistry registry;

    @Bean
    public ArkRagConfig arkRagConfig(ArkRagServerProperties props) {
        props.validate();
        return props.toCoreConfig();
    }

    @Bean
    public IndexRegistry indexRegistry(ArkRagConfig config) {
        registry = new IndexRegistry(config.dataDir());
        long t0 = System.currentTimeMillis();
        registry.loadAll();
        LOG.info("索引加载完成：耗时 {} ms", System.currentTimeMillis() - t0);
        return registry;
    }

    @Bean
    public com.arkrag.core.settings.SettingsService settingsService(ArkRagConfig config) {
        return new com.arkrag.core.settings.SettingsService(config);
    }

    @Bean
    public EmbeddingService embeddingService(com.arkrag.core.settings.SettingsService settings) {
        return new EmbeddingService(settings);
    }

    @Bean
    public com.arkrag.core.chat.ChatService chatService(com.arkrag.core.settings.SettingsService settings) {
        return new com.arkrag.core.chat.ChatService(settings);
    }

    @Bean
    public KnowledgeBaseService knowledgeBaseService(ArkRagConfig config, IndexRegistry registry) {
        return new KnowledgeBaseService(config, registry);
    }

    @Bean
    public IngestService ingestService(ArkRagConfig config, KnowledgeBaseService kbService,
                                       IndexRegistry registry, EmbeddingService embedding) {
        ingestService = new IngestService(config, kbService, registry, embedding);
        return ingestService;
    }

    @Bean
    public SearchService searchService(KnowledgeBaseService kbService, IndexRegistry registry,
                                       EmbeddingService embedding) {
        return new SearchService(kbService, registry, embedding);
    }

    @Bean
    public com.arkrag.core.ask.AskService askService(SearchService searchService,
                                                     com.arkrag.core.chat.ChatService chatService) {
        return new com.arkrag.core.ask.AskService(searchService, chatService);
    }

    @PreDestroy
    public void shutdown() {
        try {
            if (ingestService != null) {
                // 在途任务最多等 10s：摄入是外部 API 密集型，卡死时优先保证已写数据落盘
                ingestService.beginShutdown();
                if (!ingestService.awaitTermination(10, TimeUnit.SECONDS)) {
                    LOG.warn("摄入任务 10s 内未全部结束，强制进入落盘流程");
                }
            }
            if (registry != null) {
                registry.close();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
