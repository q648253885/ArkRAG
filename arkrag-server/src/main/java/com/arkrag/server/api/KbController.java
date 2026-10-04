package com.arkrag.server.api;

import com.arkrag.core.model.KnowledgeBase;
import com.arkrag.core.service.KnowledgeBaseService;
import com.arkrag.server.api.dto.Dto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库管理接口（契约 §5.1）：
 * POST /api/v1/kb 建库 ｜ GET /api/v1/kb 列表 ｜ GET /api/v1/kb/{id} 详情+文档
 * DELETE /api/v1/kb/{id} 级联删除。
 */
@RestController
@RequestMapping("/api/v1/kb")
public class KbController {

    private final KnowledgeBaseService kbService;
    private final com.arkrag.core.pipeline.IngestService ingestService;

    public KbController(KnowledgeBaseService kbService, com.arkrag.core.pipeline.IngestService ingestService) {
        this.kbService = kbService;
        this.ingestService = ingestService;
    }

    /** 建库；校验失败 400 VALIDATION，Embedding 未配置 409。 */
    @PostMapping
    public Dto.KbView create(@RequestBody Dto.CreateKbRequest req) {
        KnowledgeBase kb = kbService.create(req.name(), req.description(), req.chunkSize(), req.chunkOverlap());
        return Dto.KbView.of(kb, kbService.stats(kb.getId()));
    }

    /** 列表（含统计）。 */
    @GetMapping
    public List<Dto.KbView> list() {
        List<KnowledgeBase> kbs = kbService.listAll();
        List<Dto.KbView> out = new ArrayList<>(kbs.size());
        for (KnowledgeBase kb : kbs) {
            out.add(Dto.KbView.of(kb, kbService.stats(kb.getId())));
        }
        return out;
    }

    /** 详情 + 全部文档（含摄入状态）。404 KB_NOT_FOUND。 */
    @GetMapping("/{kbId}")
    public MapWithDocs detail(@PathVariable String kbId) {
        KnowledgeBase kb = kbService.get(kbId);
        List<Dto.DocView> docs = kbService.listDocs(kbId).stream().map(Dto.DocView::of).toList();
        return new MapWithDocs(Dto.KbView.of(kb, kbService.stats(kbId)), docs);
    }

    /** 级联删除。204。 */
    @DeleteMapping("/{kbId}")
    public ResponseEntity<Void> delete(@PathVariable String kbId) {
        kbService.delete(kbId);
        return ResponseEntity.noContent().build();
    }

    /** 重建索引（换 Embedding 模型后）：用已存原始文件重新切块-嵌入。202 + 重新排队数量。 */
    @PostMapping("/{kbId}/rebuild")
    public ResponseEntity<Object> rebuild(@PathVariable String kbId) {
        com.arkrag.core.model.KnowledgeBase kb = kbService.get(kbId);
        int queued = ingestService.rebuild(kb);
        return ResponseEntity.accepted().body(java.util.Map.of("queued", queued));
    }

    /** 详情响应体（KB + 文档列表）。 */
    public record MapWithDocs(Dto.KbView kb, List<Dto.DocView> documents) {
    }
}
