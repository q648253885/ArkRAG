package com.arkrag.server.api;

import com.arkrag.core.model.DocInfo;
import com.arkrag.core.model.KnowledgeBase;
import com.arkrag.core.pipeline.IngestService;
import com.arkrag.core.service.KnowledgeBaseService;
import com.arkrag.server.api.dto.Dto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档摄入与文档管理（契约 §5.1）：
 * POST /api/v1/kb/{id}/documents  — multipart(files 多文件) 或 JSON {title,text}，异步 202
 * GET  /api/v1/kb/{id}/documents  — 状态轮询
 * DELETE /api/v1/kb/{id}/documents/{docId}
 * POST /api/v1/kb/{id}/rebuild    — 用原始文件重建向量（换 Embedding 模型后）
 */
@RestController
@RequestMapping("/api/v1/kb/{kbId}/documents")
public class DocumentController {

    private final KnowledgeBaseService kbService;
    private final IngestService ingestService;

    public DocumentController(KnowledgeBaseService kbService, IngestService ingestService) {
        this.kbService = kbService;
        this.ingestService = ingestService;
    }

    /** 多文件上传（异步摄入）。 */
    @PostMapping(consumes = "multipart/form-data")
    public List<Dto.DocView> uploadFiles(@PathVariable String kbId,
                                         @RequestParam("files") List<MultipartFile> files) throws IOException {
        KnowledgeBase kb = kbService.get(kbId);
        List<Dto.DocView> out = new ArrayList<>(files.size());
        for (MultipartFile f : files) {
            String original = f.getOriginalFilename() == null ? "untitled" : f.getOriginalFilename();
            DocInfo doc = ingestService.submitFile(kb, original, f.getBytes());
            out.add(Dto.DocView.of(doc));
        }
        return out;
    }

    /** 纯文本或 base64 二进制摄入（异步；base64 供插件桥路径上传 PDF/DOCX 等二进制）。 */
    @PostMapping(consumes = "application/json")
    public Dto.DocView uploadText(@PathVariable String kbId, @RequestBody Dto.IngestTextRequest req) {
        KnowledgeBase kb = kbService.get(kbId);
        String title = req.title() == null || req.title().isBlank() ? "inline-text.md" : req.title();
        if (req.base64() != null && !req.base64().isBlank()) {
            byte[] bytes = java.util.Base64.getDecoder().decode(
                    req.base64().contains(",") ? req.base64().substring(req.base64().indexOf(',') + 1) : req.base64());
            return Dto.DocView.of(ingestService.submitFile(kb, title, bytes));
        }
        return Dto.DocView.of(ingestService.submitText(kb, title, req.text()));
    }

    /** 文档列表（含状态/错误/chunkCount）。 */
    @GetMapping
    public List<Dto.DocView> list(@PathVariable String kbId) {
        return kbService.listDocs(kbId).stream().map(Dto.DocView::of).toList();
    }

    /** 删文档及其 chunks。204。 */
    @DeleteMapping("/{docId}")
    public ResponseEntity<Void> delete(@PathVariable String kbId, @PathVariable String docId) {
        ingestService.deleteDoc(kbId, docId);
        return ResponseEntity.noContent().build();
    }
}
