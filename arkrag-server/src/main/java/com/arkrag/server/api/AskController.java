package com.arkrag.server.api;

import com.arkrag.core.ask.AskService;
import com.arkrag.server.api.dto.Dto;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * POST /api/v1/ask — 独立 RAG 问答（v1.1，契约见 docs/v1.1/04-system-design-delta.md §2）。
 * 检索 topK → Chat 生成带 [n] 引用的答案。Chat 未配置 → 409 CHAT_NOT_CONFIGURED。
 */
@RestController
@RequestMapping("/api/v1")
public class AskController {

    private final AskService askService;

    public AskController(AskService askService) {
        this.askService = askService;
    }

    /** 问答：query 必填；kbIds 缺省全部；topK 1..50 缺省 5。 */
    @PostMapping("/ask")
    public AskResponse ask(@RequestBody Dto.SearchRequest req) {
        long t0 = System.currentTimeMillis();
        var result = askService.ask(req.query(), req.kbIds(), req.topK());
        List<Object> citations = result.citations().stream().map(c -> {
            Map<String, Object> o = new LinkedHashMap<String, Object>();
            o.put("index", c.index());
            o.put("kbId", c.kbId());
            o.put("kbName", c.kbName());
            o.put("docId", c.docId());
            o.put("docName", c.docName());
            o.put("chunkIndex", c.chunkIndex());
            o.put("score", Math.round(c.score() * 1000.0) / 1000.0);
            return (Object) o;
        }).toList();
        return new AskResponse(result.answer(), citations, System.currentTimeMillis() - t0);
    }

    /** 响应体：answer 内 [n] 对应 citations[].index。 */
    public record AskResponse(String answer, Object citations, long tookMs) {
    }
}
