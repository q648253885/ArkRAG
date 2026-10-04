package com.arkrag.core.ask;

import com.arkrag.core.chat.ChatService;
import com.arkrag.core.exception.ArkRagException;
import com.arkrag.core.exception.ErrorCode;
import com.arkrag.core.model.SearchHit;
import com.arkrag.core.search.SearchService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 独立 RAG 问答（v1.1）：检索 topK → 组装中文提示词 → Chat 生成带 [n] 引用的答案。
 *
 * 提示词设计（为什么这么做）：
 *  - 强约束"仅依据资料回答 + [n] 标注"——把幻觉控制在可溯源范围；
 *  - 无命中/资料不足时用固定拒答文案——宁可拒答不给用户编造的答案；
 *  - 每条资料带来源元信息——模型可直接把 [n] 与出处对应，citations 由服务端
 *    （而非模型）生成，保证"答案里的 n"与"citations 里的 n"严格一致。
 */
public class AskService {

    static final String NO_HIT_ANSWER = "知识库中没有找到相关内容。请先在知识库中上传与问题相关的文档，或换一种问法。";

    private final SearchService searchService;
    private final ChatService chatService;

    public AskService(SearchService searchService, ChatService chatService) {
        this.searchService = searchService;
        this.chatService = chatService;
    }

    /** 问答结果：answer 内的 [n] 与 citations 的 index 一一对应。 */
    public record AskResult(String answer, List<Citation> citations, long tookMs) {
    }

    public record Citation(int index, String kbId, String kbName, String docId, String docName,
                           int chunkIndex, double score) {
    }

    /**
     * 知识库问答。
     *
     * @param query    自然语言问题，1..2000 字符
     * @param kbIds    目标 KB；null/空 = 全部
     * @param topK     召回条数 1..50，缺省 5
     * @throws ArkRagException VALIDATION / CHAT_NOT_CONFIGURED / EMBEDDING_*（复用检索错误语义）
     */
    public AskResult ask(String query, List<String> kbIds, Integer topK) {
        if (query == null || query.isBlank()) {
            throw new ArkRagException(ErrorCode.VALIDATION, "query 不能为空");
        }
        if (query.length() > 2000) {
            throw new ArkRagException(ErrorCode.VALIDATION, "query 不能超过 2000 字符");
        }
        if (!chatService.isConfigured()) {
            throw new ArkRagException(ErrorCode.CHAT_NOT_CONFIGURED,
                    "Chat 模型未配置：请在控制台「模型配置」页配置 chat（base-url / api-key / model）后重试");
        }
        long t0 = System.currentTimeMillis();
        List<SearchHit> hits = searchService.search(query, kbIds, topK, 0.0);
        if (hits.isEmpty()) {
            return new AskResult(NO_HIT_ANSWER, List.of(), System.currentTimeMillis() - t0);
        }
        String prompt = buildPrompt(query, hits);
        String answer = chatService.chat(prompt);
        return new AskResult(answer, citations(hits), System.currentTimeMillis() - t0);
    }

    /** 供 MCP/HTTP 复用的引用组装。 */
    public static List<Map<String, Object>> citationsMap(List<Citation> citations) {
        List<Map<String, Object>> out = new ArrayList<>(citations.size());
        for (Citation c : citations) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("index", c.index());
            o.put("kb_id", c.kbId());
            o.put("kb_name", c.kbName());
            o.put("doc_id", c.docId());
            o.put("doc_name", c.docName());
            o.put("chunk_index", c.chunkIndex());
            o.put("score", Math.round(c.score() * 1000.0) / 1000.0);
            out.add(o);
        }
        return out;
    }

    static String buildPrompt(String query, List<SearchHit> hits) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是知识库问答助手。请仅依据下面的资料回答用户问题，并在答案中用 [n] 标注所引用资料的编号；")
          .append("如果资料不足以回答问题，只回复：").append(NO_HIT_ANSWER)
          .append("。不要编造资料中没有的信息。\n\n");
        sb.append("资料：\n");
        for (int i = 0; i < hits.size(); i++) {
            SearchHit h = hits.get(i);
            sb.append("[").append(i + 1).append("] （来源：").append(h.docName())
              .append(" · 知识库「").append(h.kbName()).append("」第 ").append(h.chunkIndex() + 1).append(" 块）\n")
              .append(h.text()).append("\n\n");
        }
        sb.append("用户问题：").append(query);
        return sb.toString();
    }

    private static List<Citation> citations(List<SearchHit> hits) {
        List<Citation> out = new ArrayList<>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            SearchHit h = hits.get(i);
            out.add(new Citation(i + 1, h.kbId(), h.kbName(), h.docId(), h.docName(),
                    h.chunkIndex(), h.score()));
        }
        return out;
    }
}
