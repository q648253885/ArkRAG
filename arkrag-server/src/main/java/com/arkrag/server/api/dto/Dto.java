package com.arkrag.server.api.dto;

import com.arkrag.core.model.DocInfo;
import com.arkrag.core.model.KnowledgeBase;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** 响应视图 DTO（契约：docs/v1.0/04-system-design.md §5.1）。 */
public final class Dto {

    private Dto() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record KbView(String id, String name, String description, String embeddingModel, Integer dims,
                         Integer chunkSize, Integer chunkOverlap, Long createdAt, Long updatedAt,
                         Long documentCount, Long chunkCount) {
        public static KbView of(KnowledgeBase kb, long[] stats) {
            return new KbView(kb.getId(), kb.getName(), kb.getDescription(), kb.getEmbeddingModel(),
                    kb.getDims(), kb.getChunkSize(), kb.getChunkOverlap(),
                    kb.getCreatedAt(), kb.getUpdatedAt(), stats[0], stats[1]);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DocView(String id, String name, String ext, long size, int chunkCount,
                          String status, String error, long createdAt, long updatedAt) {
        public static DocView of(DocInfo d) {
            return new DocView(d.getId(), d.getName(), d.getExt(), d.getSize(), d.getChunkCount(),
                    d.getStatus().name(), d.getError(), d.getCreatedAt(), d.getUpdatedAt());
        }
    }

    public record CreateKbRequest(String name, String description, Integer chunkSize, Integer chunkOverlap) {
    }

    public record IngestTextRequest(String title, String text, String base64) {
    }

    public record SearchRequest(String query, List<String> kbIds, Integer topK, Double minScore) {
    }

    public record Hit(String kbId, String kbName, String docId, String docName,
                      int chunkIndex, String text, double score) {
    }

    public record SearchResponse(List<Hit> hits, long tookMs) {
    }

    public record ErrorBody(String code, String message, Object details) {
    }

    public record ErrorResponse(ErrorBody error) {
    }
}
