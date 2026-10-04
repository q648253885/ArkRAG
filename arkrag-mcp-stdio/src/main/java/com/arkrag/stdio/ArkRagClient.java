package com.arkrag.stdio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * ArkRAG HTTP 客户端（stdio 桥专用，零 Spring 依赖）。
 * 配置来源（04-system-design.md §5.3）：
 *   ARKRAG_URL（默认 http://127.0.0.1:8964）、
 *   ARKRAG_TOKEN 或 ARKRAG_TOKEN_FILE（二选一，后者配合 ArkWork secret: env 惯例）。
 */
final class ArkRagClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final String baseUrl;
    private final String token;

    ArkRagClient() {
        this.baseUrl = trimSlash(env("ARKRAG_URL", "http://127.0.0.1:8964"));
        this.token = resolveToken();
    }

    String baseUrl() {
        return baseUrl;
    }

    /** GET /api/v1/health → 200 返回 JSON，其他抛 BridgeException。 */
    JsonNode health() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/health"))
                .timeout(Duration.ofSeconds(10))
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new BridgeException("健康检查失败：HTTP " + resp.statusCode());
        }
        return JSON.readTree(resp.body());
    }

    /** GET /api/v1/kb。 */
    JsonNode listKbs() throws Exception {
        return getForJson("/api/v1/kb");
    }

    /** POST /api/v1/search。body 会被原样转发（由 BridgeMain 组装）。 */
    JsonNode search(String bodyJson) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/search"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("X-Api-Key", token)
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return parse(resp, "检索失败");
    }

    /** POST /api/v1/ask（问答，Chat 生成可能较慢：120s 超时）。 */
    JsonNode ask(String bodyJson) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/ask"))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("X-Api-Key", token)
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return parse(resp, "问答失败");
    }

    JsonNode getForJson(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(15))
                .header("X-Api-Key", token)
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return parse(resp, "请求失败");
    }

    private JsonNode parse(HttpResponse<String> resp, String what) throws Exception {
        JsonNode body;
        try {
            body = JSON.readTree(resp.body());
        } catch (Exception e) {
            body = JSON.nullNode();
        }
        if (resp.statusCode() == 401) {
            throw new BridgeException("鉴权失败（401）：ARKRAG_TOKEN 与服务端 arkrag.server.token 不一致");
        }
        if (resp.statusCode() >= 400) {
            String msg = body != null && body.has("error")
                    ? body.path("error").path("message").asText("")
                    : "HTTP " + resp.statusCode();
            throw new BridgeException(what + "：" + msg);
        }
        return body;
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : v.trim();
    }

    private static String resolveToken() {
        String direct = System.getenv("ARKRAG_TOKEN");
        if (direct != null && !direct.isBlank()) {
            return direct.trim();
        }
        String file = System.getenv("ARKRAG_TOKEN_FILE");
        if (file != null && !file.isBlank()) {
            try {
                return Files.readString(Path.of(file.trim()), StandardCharsets.UTF_8).trim();
            } catch (Exception e) {
                throw new IllegalStateException("读取 ARKRAG_TOKEN_FILE 失败：" + file, e);
            }
        }
        return "";
    }

    /** 连接层失败（服务未启动等）。 */
    static BridgeException unreachable(Exception cause) {
        return new BridgeException("无法连接 ArkRAG 服务（" + cause.getMessage() + "）。"
                + "请先启动服务：java -jar arkrag-server.jar（默认 http://127.0.0.1:8964），"
                + "并确认 ARKRAG_URL / ARKRAG_TOKEN 配置正确");
    }

    /** 桥业务异常 → MCP isError 文本。 */
    static final class BridgeException extends RuntimeException {
        BridgeException(String message) {
            super(message);
        }
    }
}
