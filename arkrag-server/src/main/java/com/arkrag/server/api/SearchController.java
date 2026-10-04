package com.arkrag.server.api;

import com.arkrag.core.search.SearchService;
import com.arkrag.server.api.dto.Dto;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * POST /api/v1/search — 语义检索（契约 §5.1）。
 * 错误：400 VALIDATION / 404 KB_NOT_FOUND / 409 EMBEDDING_NOT_CONFIGURED、EMBEDDING_DIMENSION_MISMATCH。
 */
@RestController
@RequestMapping("/api/v1")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    /** 检索：query 必填；kbIds 缺省全部；topK 1..50 缺省 5；minScore 0..1 缺省 0。 */
    @PostMapping("/search")
    public Dto.SearchResponse search(@RequestBody Dto.SearchRequest req) {
        long t0 = System.currentTimeMillis();
        List<com.arkrag.core.model.SearchHit> hits =
                searchService.search(req.query(), req.kbIds(), req.topK(), req.minScore());
        long took = System.currentTimeMillis() - t0;
        return new Dto.SearchResponse(
                hits.stream()
                        .map(h -> new Dto.Hit(h.kbId(), h.kbName(), h.docId(), h.docName(),
                                h.chunkIndex(), h.text(), h.score()))
                        .toList(),
                took);
    }
}
