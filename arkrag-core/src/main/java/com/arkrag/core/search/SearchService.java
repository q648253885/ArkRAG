package com.arkrag.core.search;

import com.arkrag.core.embedding.EmbeddingService;
import com.arkrag.core.exception.ArkRagException;
import com.arkrag.core.exception.ErrorCode;
import com.arkrag.core.model.KnowledgeBase;
import com.arkrag.core.model.SearchHit;
import com.arkrag.core.service.KnowledgeBaseService;
import com.arkrag.core.store.IndexRegistry;
import com.arkrag.core.store.VectorIndex;
import dev.langchain4j.data.embedding.Embedding;

import java.util.ArrayList;
import java.util.List;

/**
 * 语义检索。热路径全程无锁：只读 IndexRegistry 的 volatile 快照，
 * 余弦相似度由 VectorIndex 暴力计算（04 §4）。
 * 维度护栏（04 §6 流程 2）：查询向量维度 ≠ KB.dims 时拒绝并提示 rebuild，
 * 绝不混用新旧模型的向量（否则相似度无意义且结果不可复现）。
 */
public class SearchService {

    private final KnowledgeBaseService kbService;
    private final IndexRegistry registry;
    private final EmbeddingService embeddingService;

    public SearchService(KnowledgeBaseService kbService, IndexRegistry registry,
                         EmbeddingService embeddingService) {
        this.kbService = kbService;
        this.registry = registry;
        this.embeddingService = embeddingService;
    }

    /**
     * 语义检索。
     *
     * @param query    自然语言问题，1..2000 字符
     * @param kbIds    目标 KB；null/空 = 全部
     * @param topK     每库召回后全局合并取前 N；1..50，缺省 5
     * @param minScore 分数过滤 0..1，缺省 0（不过滤）
     * @return 全局按分数降序的命中列表
     * @throws ArkRagException VALIDATION / KB_NOT_FOUND / EMBEDDING_NOT_CONFIGURED /
     *                         EMBEDDING_DIMENSION_MISMATCH
     */
    public List<SearchHit> search(String query, List<String> kbIds, Integer topK, Double minScore) {
        if (query == null || query.isBlank()) {
            throw new ArkRagException(ErrorCode.VALIDATION, "query 不能为空");
        }
        if (query.length() > 2000) {
            throw new ArkRagException(ErrorCode.VALIDATION, "query 不能超过 2000 字符");
        }
        int k = topK == null ? 5 : topK;
        if (k < 1 || k > 50) {
            throw new ArkRagException(ErrorCode.VALIDATION, "topK 取值范围 1..50");
        }
        double min = minScore == null ? 0 : minScore;
        if (min < 0 || min > 1) {
            throw new ArkRagException(ErrorCode.VALIDATION, "minScore 取值范围 0..1");
        }

        List<KnowledgeBase> targets = resolveTargets(kbIds);
        if (targets.isEmpty()) {
            throw new ArkRagException(ErrorCode.VALIDATION, "没有可检索的知识库（kbIds 不存在或库列表为空）");
        }

        Embedding queryVec = embeddingService.embedOne(query);
        int queryDims = embeddingService.dimsOf(queryVec);

        List<SearchHit> hits = new ArrayList<>();
        for (KnowledgeBase kb : targets) {
            if (kb.getDims() != 0 && kb.getDims() != queryDims) {
                throw new ArkRagException(ErrorCode.EMBEDDING_DIMENSION_MISMATCH,
                        "知识库「" + kb.getName() + "」的向量维度(" + kb.getDims()
                                + ")与当前查询维度(" + queryDims + ")不一致——"
                                + "Embedding 模型已变更，请对该库执行 POST /api/v1/kb/" + kb.getId() + "/rebuild");
            }
            hits.addAll(VectorIndex.search(registry.of(kb.getId()).snapshot(),
                    kb.getName(), queryVec.vector(), k, min));
        }
        hits.sort((a, b) -> Double.compare(b.score(), a.score()));
        return hits;
    }

    private List<KnowledgeBase> resolveTargets(List<String> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) {
            return kbService.listAll();
        }
        return kbIds.stream().map(kbService::get).toList();
    }
}
