package com.arkrag.core.settings;

import com.arkrag.core.config.ArkRagConfig;
import com.arkrag.core.exception.ArkRagException;
import com.arkrag.core.exception.ErrorCode;
import com.arkrag.core.store.MetaFiles;
import com.fasterxml.jackson.core.type.TypeReference;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 运行时模型配置中心（v1.1）。
 *
 * 配置合成顺序：settings.json（控制台在线修改）> application.yml（启动初值）。
 * 保存即写盘（临时文件 + 原子 move）并广播变更——EmbeddingService/ChatService
 * 监听后清空缓存模型实例，下一次调用用新配置重建，实现"热生效、无需重启"。
 *
 * 为什么不直接改 yml：yml 属于部署物，运行时写回会造成"配置来源不可考"；
 * settings.json 是用户数据（data-dir 内），升级部署不丢配置。
 */
public class SettingsService {

    private static final TypeReference<ModelSettings> TYPE = new TypeReference<>() {
    };

    private final Path file;
    private final ArkRagConfig yamlConfig;
    private final CopyOnWriteArrayList<Consumer<ModelSettings>> listeners = new CopyOnWriteArrayList<>();

    public SettingsService(ArkRagConfig yamlConfig) {
        this.yamlConfig = yamlConfig;
        this.file = Path.of(yamlConfig.dataDir()).resolve("settings.json");
    }

    /** 读取运行时配置（文件不存在/损坏 = 空），不合并 yml。 */
    public ModelSettings runtime() {
        if (!Files.exists(file)) {
            return new ModelSettings();
        }
        try {
            ModelSettings s = MetaFiles.mapper().readValue(file.toFile(), TYPE);
            return s == null ? new ModelSettings() : s;
        } catch (Exception e) {
            throw new ArkRagException(ErrorCode.SERVER_ERROR, "模型配置文件损坏：" + file, null, e);
        }
    }

    /**
     * 当前生效的 Embedding 配置：runtime 覆盖 yml。
     */
    public ArkRagConfig.Embedding effectiveEmbedding() {
        ModelSettings.Embedding rt = runtime().getEmbedding();
        if (rt != null && rt.configured()) {
            return new ArkRagConfig.Embedding(rt.baseUrl(), rt.apiKey(), rt.model(), rt.dims(), 32);
        }
        return yamlConfig.embedding();
    }

    /** 当前生效的 Chat 配置；未配置返回 null。 */
    public ModelSettings.Chat effectiveChat() {
        ModelSettings.Chat rt = runtime().getChat();
        if (rt != null && rt.configured()) {
            return rt;
        }
        var fromYaml = yamlConfig.chat();
        return yamlChatConfigured(fromYaml)
                ? new ModelSettings.Chat(fromYaml.baseUrl(), fromYaml.apiKey(), fromYaml.model(), fromYaml.temperature())
                : null;
    }

    private static boolean yamlChatConfigured(ArkRagConfig.Chat c) {
        return c != null && c.baseUrl() != null && !c.baseUrl().isBlank()
                && c.apiKey() != null && !c.apiKey().isBlank()
                && c.model() != null && !c.model().isBlank();
    }

    /**
     * 保存运行时配置并广播。任一组传 null = 清除该组（回落 yml）。
     * apiKey 传空串/掩码 = 保留原值（控制台把脱敏值原样带回时不致清空）。
     */
    public ModelSettings save(ModelSettings next) {
        ModelSettings current = runtime();
        ModelSettings toWrite = new ModelSettings();
        toWrite.setEmbedding(mergeEmbedding(current.getEmbedding(), next == null ? null : next.getEmbedding()));
        toWrite.setChat(mergeChat(current.getChat(), next == null ? null : next.getChat()));
        toWrite.setUpdatedAt(System.currentTimeMillis());
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, MetaFiles.mapper().writeValueAsString(toWrite));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            throw new ArkRagException(ErrorCode.SERVER_ERROR, "模型配置写入失败：" + file, null, e);
        }
        for (Consumer<ModelSettings> l : listeners) {
            l.accept(toWrite);
        }
        return toWrite;
    }

    private static ModelSettings.Embedding mergeEmbedding(ModelSettings.Embedding old, ModelSettings.Embedding in) {
        if (in == null) {
            return null;
        }
        String key = preserveKey(in.apiKey(), old == null ? null : old.apiKey());
        return new ModelSettings.Embedding(in.baseUrl(), key, in.model(), in.dims());
    }

    private static ModelSettings.Chat mergeChat(ModelSettings.Chat old, ModelSettings.Chat in) {
        if (in == null) {
            return null;
        }
        String key = preserveKey(in.apiKey(), old == null ? null : old.apiKey());
        return new ModelSettings.Chat(in.baseUrl(), key, in.model(), in.temperature());
    }

    /** 空/掩码 apiKey 视为"未修改"：保留旧值，避免控制台回显脱敏串时误清空。 */
    private static String preserveKey(String incoming, String old) {
        if (incoming == null || incoming.isBlank() || incoming.contains("*")) {
            return old;
        }
        return incoming.trim();
    }

    /** 订阅配置变更（热生效钩子）。 */
    public void onChange(Consumer<ModelSettings> listener) {
        listeners.add(listener);
    }

    /** apiKey 脱敏：仅保留末 4 位。 */
    public static String mask(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        return apiKey.length() <= 4 ? "****" : "****" + apiKey.substring(apiKey.length() - 4);
    }

    /** 供配置测试直读（绕过缓存）。 */
    public List<String> describeSources() {
        ModelSettings rt = runtime();
        return List.of(
                "embedding=" + (rt.getEmbedding() != null ? "runtime" : yamlConfig.embedding().configured() ? "yaml" : "unset"),
                "chat=" + (rt.getChat() != null ? "runtime" : effectiveChat() != null ? "yaml" : "unset"));
    }
}
