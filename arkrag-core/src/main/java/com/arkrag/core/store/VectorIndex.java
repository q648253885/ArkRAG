package com.arkrag.core.store;

import com.arkrag.core.model.ChunkMeta;
import com.arkrag.core.model.SearchHit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.arkrag.core.exception.ErrorCode.INGEST_FAILED;

/**
 * 自研向量索引：内存 + 文件持久化 + 暴力余弦检索（04 §1/§4，缺陷回溯后定稿）。
 *
 * 为什么自研而不用 LangChain4j InMemoryEmbeddingStore：
 * ① 其 Entry 字段包私有，外部无法枚举条目（删除文档/统计/重建都需要枚举）；
 * ② 自带 JSON 序列化对自定义负载类型的反序列化不可靠。
 * 暴力余弦在单库 ≤10 万块时 p95 < 200ms（04 §7 容量边界），完全够用且零依赖。
 *
 * 线程模型：全部方法静态纯函数；快照为不可变 List，由 IndexRegistry volatile 换引用。
 * 文件格式：[{"id","vector":[...],"meta":{kbId,docId,docName,chunkIndex,text}}]
 */
public final class VectorIndex {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private VectorIndex() {
    }

    /** 暴力余弦检索：返回按分数降序、最多 topK 条命中。 */
    public static List<SearchHit> search(List<StoredVector> snapshot, String kbName,
                                         float[] query, int topK, double minScore) {
        record Scored(StoredVector sv, double score) {
        }
        List<Scored> all = new ArrayList<>(snapshot.size());
        for (StoredVector sv : snapshot) {
            double s = cosine(query, sv.vec());
            if (s >= minScore) {
                all.add(new Scored(sv, s));
            }
        }
        all.sort(Comparator.comparingDouble(Scored::score).reversed());
        List<SearchHit> out = new ArrayList<>(Math.min(topK, all.size()));
        for (int i = 0; i < Math.min(topK, all.size()); i++) {
            Scored s = all.get(i);
            ChunkMeta m = s.sv().meta();
            out.add(new SearchHit(m.kbId(), kbName, m.docId(), m.docName(), m.chunkIndex(),
                    m.text(), s.score()));
        }
        return out;
    }

    /** 余弦相似度（向量未归一化时同样正确）。 */
    static double cosine(float[] a, float[] b) {
        int n = Math.min(a.length, b.length);
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < n; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** 过滤出指定文档的向量（删除/覆盖语义用）。 */
    public static List<StoredVector> removeDoc(List<StoredVector> snapshot, String docId) {
        List<StoredVector> next = new ArrayList<>(snapshot.size());
        for (StoredVector sv : snapshot) {
            if (!docId.equals(sv.meta().docId())) {
                next.add(sv);
            }
        }
        return next;
    }

    /** 统计某文档的 chunk 数。 */
    public static long countOfDoc(List<StoredVector> snapshot, String docId) {
        return snapshot.stream().filter(sv -> docId.equals(sv.meta().docId())).count();
    }

    /** 统计某库 chunk 总数（READY 语义 = 索引内全部）。 */
    public static long countAll(List<StoredVector> snapshot) {
        return snapshot.size();
    }

    /**
     * 整体落盘（临时文件 + 原子 move；进程崩溃不会留下半截索引）。
     */
    public static void save(List<StoredVector> snapshot, Path target) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (StoredVector sv : snapshot) {
            ObjectNode o = arr.addObject();
            o.put("id", sv.id());
            ArrayNode vec = o.putArray("vector");
            for (float v : sv.vec()) {
                vec.add(v);
            }
            ChunkMeta m = sv.meta();
            ObjectNode meta = o.putObject("meta");
            meta.put("kbId", m.kbId()).put("docId", m.docId())
                    .put("docName", m.docName()).put("chunkIndex", m.chunkIndex())
                    .put("text", m.text());
        }
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + "." + java.util.UUID.randomUUID() + ".tmp");
            Files.writeString(tmp, MAPPER.writeValueAsString(arr), StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new com.arkrag.core.exception.ArkRagException(INGEST_FAILED,
                    "向量索引写入失败：" + target, null, e);
        }
    }

    /** 从文件恢复；文件不存在返回空快照（新 KB 首启路径）。 */
    public static List<StoredVector> load(Path file) {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            JsonNode arr = MAPPER.readTree(file.toFile());
            if (!arr.isArray()) {
                return List.of();
            }
            List<StoredVector> out = new ArrayList<>(arr.size());
            for (JsonNode n : arr) {
                float[] vector = new float[n.path("vector").size()];
                var it = n.path("vector").elements();
                for (int i = 0; it.hasNext(); i++) {
                    vector[i] = (float) it.next().asDouble();
                }
                JsonNode m = n.path("meta");
                ChunkMeta meta = new ChunkMeta(
                        m.path("kbId").asText(), m.path("docId").asText(),
                        m.path("docName").asText(), m.path("chunkIndex").asInt(0),
                        m.path("text").asText());
                out.add(new StoredVector(n.path("id").asText(), vector, meta));
            }
            return List.copyOf(out);
        } catch (IOException e) {
            throw new com.arkrag.core.exception.ArkRagException(INGEST_FAILED,
                    "向量索引文件损坏，无法加载：" + file, null, e);
        }
    }
}
