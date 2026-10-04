package com.arkrag.server.api;

import com.arkrag.core.config.ArkRagConfig;
import com.arkrag.core.exception.ArkRagException;
import com.arkrag.core.exception.ErrorCode;
import com.arkrag.core.settings.ModelSettings;
import com.arkrag.core.settings.SettingsService;
import com.arkrag.server.api.dto.Dto;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行时模型配置接口（v1.1，契约见 docs/v1.1/04-system-design-delta.md §2）。
 * GET 读脱敏视图；PUT 保存并热生效（空/掩码 apiKey = 保留原值，见 SettingsService）；
 * POST test 分路实测（embedding 发 1 条真实嵌入 / chat 发 1 条对话），不落盘。
 */
@RestController
@RequestMapping("/api/v1/settings/model")
public class SettingsController {

    private final SettingsService settingsService;
    private final ArkRagConfig yamlConfig;

    public SettingsController(SettingsService settingsService, ArkRagConfig yamlConfig) {
        this.settingsService = settingsService;
        this.yamlConfig = yamlConfig;
    }

    /** 当前生效配置（脱敏）+ 来源标注。 */
    @GetMapping
    public Map<String, Object> get() {
        ModelSettings runtime = settingsService.runtime();
        ArkRagConfig.Embedding effEmb = settingsService.effectiveEmbedding();
        ModelSettings.Chat effChat = settingsService.effectiveChat();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("embedding", Map.of(
                "configured", effEmb.configured(),
                "baseUrl", nz(effEmb.baseUrl()),
                "apiKeyMasked", nz(SettingsService.mask(effEmb.apiKey())),
                "model", nz(effEmb.model()),
                "dims", effEmb.dims() == null ? 0 : effEmb.dims(),
                "source", runtime.getEmbedding() != null ? "runtime" : effEmb.configured() ? "yaml" : "unset"));
        out.put("chat", effChat == null
                ? Map.of("configured", false, "baseUrl", "", "apiKeyMasked", "", "model", "", "source", "unset")
                : Map.of("configured", true,
                        "baseUrl", nz(effChat.baseUrl()),
                        "apiKeyMasked", nz(SettingsService.mask(effChat.apiKey())),
                        "model", nz(effChat.model()),
                        "temperature", effChat.temperature() == null ? 0.7 : effChat.temperature(),
                        "source", runtime.getChat() != null ? "runtime" : "yaml"));
        return out;
    }

    /**
     * 保存运行时模型配置（热生效）。任一组传 null = 清除并回落 yml。
     * 校验：提供的组必须 baseUrl/model 非空且 URL 以 http(s) 开头。
     */
    @PutMapping
    public Map<String, Object> put(@RequestBody ModelSettings next) {
        validateGroup(next.getEmbedding() == null ? null
                : new String[]{next.getEmbedding().baseUrl(), next.getEmbedding().model()});
        validateGroup(next.getChat() == null ? null
                : new String[]{next.getChat().baseUrl(), next.getChat().model()});
        settingsService.save(next);
        return get();
    }

    /** 配置连通性测试（不落盘）。target = embedding | chat。 */
    @PostMapping("/test")
    public Map<String, Object> test(@RequestBody TestRequest req) {
        if (req.target() == null || req.target().isBlank()) {
            throw new ArkRagException(ErrorCode.VALIDATION, "target 必填（embedding 或 chat）");
        }
        try {
            if ("embedding".equals(req.target())) {
                require(req.baseUrl(), req.model());
                var builder = OpenAiEmbeddingModel.builder()
                        .baseUrl(req.baseUrl()).apiKey(orDash(req.apiKey())).modelName(req.model())
                        .timeout(Duration.ofSeconds(30));
                if (req.dims() != null && req.dims() > 0) {
                    builder.dimensions(req.dims());
                }
                var vec = builder.build().embedAll(List.of(dev.langchain4j.data.segment.TextSegment.from("连通性测试"))).content();
                return Map.of("ok", true, "message", "连接成功", "dims", vec.get(0).vector().length);
            }
            if ("chat".equals(req.target())) {
                require(req.baseUrl(), req.model());
                var builder = OpenAiChatModel.builder()
                        .baseUrl(req.baseUrl()).apiKey(orDash(req.apiKey())).modelName(req.model())
                        .maxTokens(16).timeout(Duration.ofSeconds(30));
                if (req.temperature() != null) {
                    builder.temperature(req.temperature());
                }
                String reply = builder.build().chat("回复 ok");
                return Map.of("ok", true, "message", "连接成功", "reply", String.valueOf(reply).trim());
            }
            throw new ArkRagException(ErrorCode.VALIDATION, "target 仅支持 embedding 或 chat");
        } catch (ArkRagException e) {
            throw e;
        } catch (Exception e) {
            return Map.of("ok", false, "message", "连接失败：" + rootMessage(e));
        }
    }

    private static void require(String... fields) {
        for (String f : fields) {
            if (f == null || f.isBlank()) {
                throw new ArkRagException(ErrorCode.VALIDATION, "baseUrl 与 model 必填");
            }
        }
    }

    private static void validateGroup(String[] pair) {
        if (pair == null) {
            return;
        }
        if (pair[0] != null && !pair[0].isBlank() && !pair[0].matches("^https?://.*")) {
            throw new ArkRagException(ErrorCode.VALIDATION, "baseUrl 必须以 http/https 开头");
        }
        require(pair[0], pair[1]);
    }

    private static String orDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String rootMessage(Throwable t) {
        while (t != null && t.getCause() != null) {
            t = t.getCause();
        }
        return String.valueOf(t.getMessage());
    }

    /** 测试请求体。 */
    public record TestRequest(String target, String baseUrl, String apiKey, String model,
                              Integer dims, Double temperature) {
    }
}
