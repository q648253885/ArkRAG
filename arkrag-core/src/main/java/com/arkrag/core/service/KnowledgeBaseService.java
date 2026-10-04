package com.arkrag.core.service;

import com.arkrag.core.config.ArkRagConfig;
import com.arkrag.core.exception.ArkRagException;
import com.arkrag.core.exception.ErrorCode;
import com.arkrag.core.model.DocInfo;
import com.arkrag.core.model.DocStatus;
import com.arkrag.core.model.KnowledgeBase;
import com.arkrag.core.store.IndexRegistry;
import com.arkrag.core.store.MetaFiles;
import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 知识库 CRUD（HTTP 与 MCP 共用）。
 * 业务规则：name 安装内唯一、≤64 字符；删除 KB 级联删文档、向量与原始文件；
 * embeddingModel/dims 在建库时从当前 Embedding 配置快照（此后检索做维度护栏）。
 * 副作用：写 dataDir 元数据文件、删 KB 目录。
 */
public class KnowledgeBaseService {

    private static final TypeReference<List<KnowledgeBase>> KB_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<DocInfo>> DOC_LIST = new TypeReference<>() {
    };

    private final ArkRagConfig config;
    private final IndexRegistry registry;
    private final Path kbFile;
    private final Object writeLock = new Object();
    private final ConcurrentHashMap<String, Object> kbLocks = new ConcurrentHashMap<>();

    public KnowledgeBaseService(ArkRagConfig config, IndexRegistry registry) {
        this.config = config;
        this.registry = registry;
        this.kbFile = Path.of(config.dataDir()).resolve("kbs.json");
    }

    /**
     * 建库。
     *
     * @param name        必填 ≤64，安装内唯一
     * @param description 选填 ≤256
     * @param chunkSize   选填，缺省用全局配置；范围 200..4000
     * @param chunkOverlap 选填，缺省用全局配置；必须 < chunkSize
     * @throws ArkRagException VALIDATION（校验失败）/ EMBEDDING_NOT_CONFIGURED（无法快照模型）
     */
    public KnowledgeBase create(String name, String description, Integer chunkSize, Integer chunkOverlap) {
        if (name == null || name.isBlank()) {
            throw new ArkRagException(ErrorCode.VALIDATION, "知识库名称不能为空");
        }
        if (name.length() > 64) {
            throw new ArkRagException(ErrorCode.VALIDATION, "知识库名称不能超过 64 字符");
        }
        if (description != null && description.length() > 256) {
            throw new ArkRagException(ErrorCode.VALIDATION, "描述不能超过 256 字符");
        }
        int size = chunkSize != null ? chunkSize : config.chunking().size();
        int overlap = chunkOverlap != null ? chunkOverlap : config.chunking().overlap();
        if (size < 200 || size > 4000) {
            throw new ArkRagException(ErrorCode.VALIDATION, "chunkSize 取值范围 200..4000");
        }
        if (overlap < 0 || overlap >= size) {
            throw new ArkRagException(ErrorCode.VALIDATION, "chunkOverlap 必须在 0..chunkSize-1 之间");
        }
        synchronized (writeLock) {
            boolean exists = listAll().stream().anyMatch(k -> k.getName().equals(name));
            if (exists) {
                throw new ArkRagException(ErrorCode.VALIDATION, "同名知识库已存在：" + name);
            }
            KnowledgeBase kb = new KnowledgeBase();
            kb.setId(UUID.randomUUID().toString());
            kb.setName(name);
            kb.setDescription(description);
            kb.setChunkSize(size);
            kb.setChunkOverlap(overlap);
            kb.setCreatedAt(System.currentTimeMillis());
            kb.setUpdatedAt(kb.getCreatedAt());
            // 建库即快照当前 Embedding 配置：此后换模型需 rebuild，不静默混用
            var embedding = config.embedding();
            if (!embedding.configured()) {
                throw new ArkRagException(ErrorCode.EMBEDDING_NOT_CONFIGURED,
                        "Embedding 模型未配置，无法创建知识库（建库需快照向量模型与维度）");
            }
            kb.setEmbeddingModel(embedding.model());
            kb.setDims(embedding.dims() != null ? embedding.dims() : 0); // 0 = 待首次摄入回填实际维度
            List<KnowledgeBase> all = listAll();
            all.add(kb);
            MetaFiles.writeList(kbFile, all);
            registry.of(kb.getId()); // 注册空索引
            return kb;
        }
    }

    /** 列出全部知识库（按创建时间升序）。 */
    public List<KnowledgeBase> listAll() {
        return MetaFiles.readList(kbFile, KB_LIST);
    }

    /** 按 id 取库；不存在抛 KB_NOT_FOUND。 */
    public KnowledgeBase get(String kbId) {
        return listAll().stream().filter(k -> k.getId().equals(kbId)).findFirst()
                .orElseThrow(() -> new ArkRagException(ErrorCode.KB_NOT_FOUND, "知识库不存在：" + kbId));
    }

    /** 该 KB 的全部文档元数据（含摄入状态）。 */
    public List<DocInfo> listDocs(String kbId) {
        get(kbId); // 存在性校验
        return MetaFiles.readList(docsFile(kbId), DOC_LIST);
    }

    /** 统计信息：文档数 / READY chunk 总数（列表页与 MCP rag_list_kbs 用）。 */
    public long[] stats(String kbId) {
        List<DocInfo> docs = listDocs(kbId);
        long chunks = docs.stream().filter(d -> d.getStatus() == DocStatus.READY)
                .mapToLong(DocInfo::getChunkCount).sum();
        return new long[]{docs.size(), chunks};
    }

    /**
     * 删除 KB：级联删文档、向量索引、原始文件目录。
     * 先改元数据再删目录——中断后最坏情况是残留孤儿目录，不会出现"元数据在而内容没了"。
     */
    public void delete(String kbId) {
        synchronized (writeLock) {
            KnowledgeBase kb = get(kbId);
            List<KnowledgeBase> all = listAll();
            all.removeIf(k -> k.getId().equals(kbId));
            MetaFiles.writeList(kbFile, all);
            registry.evict(kbId);
            deleteRecursively(kbDir(kbId));
        }
    }

    /** 更新某库一个文档元数据（IngestService 回写状态用；调用方持有该 KB 锁）。 */
    public void updateDocs(String kbId, List<DocInfo> docs) {
        MetaFiles.writeList(docsFile(kbId), docs);
    }

    /** 重新读-改-写文档列表的互斥锁（同 KB 串行摄入的锁）。 */
    public Object kbLock(String kbId) {
        return kbLocks.computeIfAbsent(kbId, k -> new Object());
    }

    /** 文档文件目录（IngestService 存原始文件用）。 */
    public Path kbDir(String kbId) {
        return Path.of(config.dataDir()).resolve("kb").resolve(kbId);
    }

    private Path docsFile(String kbId) {
        return kbDir(kbId).resolve("docs.json");
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 同 delete()：磁盘清理失败不阻塞主流程
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }

    /** 供列表接口拼装统计（避免 n+1 读文件的简单缓存由调用方决定）。 */
    public Map<String, long[]> statsFor(List<KnowledgeBase> kbs) {
        var out = new java.util.LinkedHashMap<String, long[]>();
        for (KnowledgeBase kb : kbs) {
            out.put(kb.getId(), stats(kb.getId()));
        }
        return out;
    }
}
