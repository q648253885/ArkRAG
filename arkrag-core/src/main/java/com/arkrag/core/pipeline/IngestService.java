package com.arkrag.core.pipeline;

import com.arkrag.core.config.ArkRagConfig;
import com.arkrag.core.embedding.EmbeddingService;
import com.arkrag.core.exception.ArkRagException;
import com.arkrag.core.exception.ErrorCode;
import com.arkrag.core.model.ChunkMeta;
import com.arkrag.core.model.DocInfo;
import com.arkrag.core.model.DocStatus;
import com.arkrag.core.model.KnowledgeBase;
import com.arkrag.core.service.KnowledgeBaseService;
import com.arkrag.core.store.IndexRegistry;
import com.arkrag.core.store.MetaFiles;
import com.arkrag.core.store.StoredVector;
import com.arkrag.core.store.VectorIndex;
import com.fasterxml.jackson.core.type.TypeReference;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 摄入管线：文件/文本 → Tika 解析 → 递归切块 → 批量嵌入 → 写入 KB 快照。
 *
 * 并发与一致性（04-system-design.md §6 流程 1）：
 *  - 全局固定 worker 池，**同一 KB 内任务串行**（每 KB 单线程执行器），
 *    避免两个任务交错写同一快照；
 *  - 文档状态每次变迁立即写 docs.json；
 *  - 索引变更走"工作副本 → 换快照 → 防抖落盘"，搜索无锁；
 *  - 同 KB 同名文档 = 覆盖（先删旧 chunks 与旧文件再入库）。
 */
public class IngestService {

    private static final Logger LOG = LoggerFactory.getLogger(IngestService.class);
    private static final TypeReference<List<DocInfo>> DOC_LIST = new TypeReference<>() {
    };

    /** 允许上传的扩展名白名单（Tika 文本层可解析的类型）。 */
    public static final List<String> ALLOWED_EXT = List.of("pdf", "docx", "pptx", "html", "txt", "md");

    private final ArkRagConfig config;
    private final KnowledgeBaseService kbService;
    private final IndexRegistry registry;
    private final EmbeddingService embeddingService;
    private final ApacheTikaDocumentParser parser = new ApacheTikaDocumentParser();
    private final ExecutorService workers;
    private final ConcurrentHashMap<String, ExecutorService> kbQueues = new ConcurrentHashMap<>();

    public IngestService(ArkRagConfig config, KnowledgeBaseService kbService,
                         IndexRegistry registry, EmbeddingService embeddingService) {
        this.config = config;
        this.kbService = kbService;
        this.registry = registry;
        this.embeddingService = embeddingService;
        this.workers = Executors.newFixedThreadPool(
                Math.max(1, config.ingest().workers()),
                r -> {
                    Thread t = new Thread(r, "arkrag-ingest");
                    t.setDaemon(true);
                    return t;
                });
    }

    /**
     * 提交文件摄入（异步）。
     *
     * @param kb       目标知识库
     * @param fileName 原始文件名（决定扩展名校验与文档 name）
     * @param content  文件字节
     * @return 建立好的 DocInfo（status=PENDING）；完成情况经 GET documents 轮询
     * @throws ArkRagException UNSUPPORTED_FILE_TYPE / FILE_TOO_LARGE / VALIDATION（空文件）
     */
    public DocInfo submitFile(KnowledgeBase kb, String fileName, byte[] content) {
        String safeName = sanitizeName(fileName);
        String ext = extOf(safeName);
        if (!ALLOWED_EXT.contains(ext)) {
            throw new ArkRagException(ErrorCode.UNSUPPORTED_FILE_TYPE,
                    "不支持的文件类型 ." + ext + "（允许：" + ALLOWED_EXT + "）");
        }
        if (content.length == 0) {
            throw new ArkRagException(ErrorCode.VALIDATION, "文件为空：" + safeName);
        }
        if (content.length > config.ingest().maxFileSizeBytes()) {
            throw new ArkRagException(ErrorCode.FILE_TOO_LARGE,
                    "文件超过大小上限（" + (config.ingest().maxFileSizeBytes() / 1024 / 1024) + "MB）：" + safeName);
        }
        DocInfo doc = newDoc(kb, safeName, ext, content.length, sha256(content));
        Path target = fileStorePath(kb.getId(), doc.getId(), safeName);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw new ArkRagException(ErrorCode.SERVER_ERROR, "上传文件落盘失败", null, e);
        }
        // 提交登记与摄入管线共用 KB 锁：并发上传不丢文档登记（read-modify-write 必须串行）
        synchronized (kbService.kbLock(kb.getId())) {
            upsertDocMeta(doc);
        }
        enqueue(kb, doc, target, null);
        return doc;
    }

    /**
     * 提交纯文本摄入（HTTP JSON 路径；title 即文档名）。
     */
    public DocInfo submitText(KnowledgeBase kb, String title, String text) {
        if (text == null || text.isBlank()) {
            throw new ArkRagException(ErrorCode.VALIDATION, "文本内容不能为空");
        }
        String safeTitle = sanitizeName(title);
        DocInfo doc = newDoc(kb, safeTitle, "txt", text.getBytes().length, sha256(text.getBytes()));
        synchronized (kbService.kbLock(kb.getId())) {
            upsertDocMeta(doc);
        }
        enqueue(kb, doc, null, text);
        return doc;
    }

    /** 入队：同 KB 串行执行。 */
    private void enqueue(KnowledgeBase kb, DocInfo doc, Path filePath, String inlineText) {
        kbQueues.computeIfAbsent(kb.getId(),
                id -> Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "arkrag-ingest-" + id);
                    t.setDaemon(true);
                    return t;
                })).submit(() -> runPipeline(kb.getId(), doc, filePath, inlineText));
    }

    /** 摄入主流程（同 KB 串行；异常全部落 FAILED——状态在 docs.json 里可见）。 */
    private void runPipeline(String kbId, DocInfo doc, Path filePath, String inlineText) {
        KnowledgeBase kb = kbService.get(kbId);
        synchronized (kbService.kbLock(kbId)) {
            // 管线内持有工作列表：状态每次变迁都以该列表写盘（否则会写回磁盘旧状态）
            List<DocInfo> docs = readDocs(kbId);
            try {
                overwriteSameName(kb, doc, docs); // 覆盖语义：先删同名旧版本（原地修改 docs）
                setStatus(doc, DocStatus.PARSING, docs);

                String text = inlineText;
                if (text == null) {
                    try (InputStream in = Files.newInputStream(filePath)) {
                        Document parsed = parser.parse(in);
                        text = parsed.text();
                    }
                }
                if (text == null || text.isBlank()) {
                    throw new ArkRagException(ErrorCode.INGEST_FAILED, "解析得到的文本为空（扫描件或纯图片 PDF？）");
                }

                List<TextSegment> chunks = DocumentSplitters
                        .recursive(kb.getChunkSize(), kb.getChunkOverlap())
                        .split(Document.document(text));
                if (chunks.isEmpty()) {
                    throw new ArkRagException(ErrorCode.INGEST_FAILED, "切块结果为空");
                }

                setStatus(doc, DocStatus.EMBEDDING, docs);
                List<StoredVector> working = new ArrayList<>(registry.of(kbId).snapshot());
                int batchSize = Math.max(1, config.ingest().batchSize());
                for (int from = 0; from < chunks.size(); from += batchSize) {
                    List<TextSegment> batch = chunks.subList(from, Math.min(from + batchSize, chunks.size()));
                    List<String> texts = batch.stream().map(TextSegment::text).toList();
                    List<Embedding> vectors = embeddingService.embedAll(texts);
                    for (int i = 0; i < batch.size(); i++) {
                        ChunkMeta meta = new ChunkMeta(kbId, doc.getId(), doc.getName(),
                                from + i, texts.get(i));
                        working.add(new StoredVector(UUID.randomUUID().toString(),
                                vectors.get(i).vector(), meta));
                    }
                }

                // 维度快照回填：建库时未知 dims 的，用首个真实向量回填
                if (kb.getDims() == 0) {
                    kb.setDims(working.get(0).vec().length);
                    persistKb(kb);
                }

                IndexRegistry.LoadedIndex li = registry.of(kbId);
                li.replaceSnapshot(List.copyOf(working)); // 换快照：在途搜索继续用旧快照，无锁
                registry.requestPersist(kbId);

                doc.setChunkCount(chunks.size());
                setStatus(doc, DocStatus.READY, docs);
                LOG.info("摄入完成 kb={} doc={} chunks={}", kbId, doc.getName(), chunks.size());
            } catch (Exception e) {
                ArkRagException ex = e instanceof ArkRagException ae ? ae
                        : new ArkRagException(ErrorCode.INGEST_FAILED, "摄入失败：" + e.getMessage(), null, e);
                doc.setStatus(DocStatus.FAILED);
                doc.setError(ex.getMessage());
                doc.setUpdatedAt(System.currentTimeMillis());
                replaceInList(docs, doc);
                kbService.updateDocs(kbId, docs);
                LOG.warn("摄入失败 kb={} doc={} 原因={}", kbId, doc.getName(), ex.getMessage());
            }
        }
    }

    /**
     * 删除文档：从快照移除其全部 chunks、删原始文件与元数据。
     *
     * @throws ArkRagException DOC_NOT_FOUND
     */
    public void deleteDoc(String kbId, String docId) {
        KnowledgeBase kb = kbService.get(kbId);
        synchronized (kbService.kbLock(kbId)) {
            List<DocInfo> docs = readDocs(kbId);
            DocInfo doc = docs.stream().filter(d -> d.getId().equals(docId)).findFirst()
                    .orElseThrow(() -> new ArkRagException(ErrorCode.DOC_NOT_FOUND, "文档不存在：" + docId));
            IndexRegistry.LoadedIndex li = registry.of(kbId);
            li.replaceSnapshot(VectorIndex.removeDoc(li.snapshot(), docId));
            registry.requestPersist(kbId);
            docs.remove(doc);
            kbService.updateDocs(kbId, docs);
            try {
                Files.deleteIfExists(fileStorePath(kbId, docId, doc.getName()));
            } catch (IOException ignored) {
                // 文件缺失不阻塞元数据删除
            }
        }
    }

    /** 用已存原始文件重建整个 KB 的向量（换 Embedding 模型后调用）。异步提交，逐文档走管线。 */
    public int rebuild(KnowledgeBase kb) {
        List<DocInfo> docs = readDocs(kb.getId());
        int queued = 0;
        for (DocInfo doc : docs) {
            if (doc.getStatus() == DocStatus.PARSING || doc.getStatus() == DocStatus.EMBEDDING) {
                continue; // 在途任务不动
            }
            Path file = fileStorePath(kb.getId(), doc.getId(), doc.getName());
            if (!Files.exists(file)) {
                continue; // 纯文本摄入无原始文件，重建时跳过
            }
            doc.setStatus(DocStatus.PENDING);
            doc.setError(null);
            enqueue(kb, doc, file, null);
            queued++;
        }
        return queued;
    }

    /** 停机第一步：停止接收新任务（已入队任务继续执行）。 */
    public void beginShutdown() {
        workers.shutdown();
        kbQueues.values().forEach(ExecutorService::shutdown);
    }

    /** 等待在途任务结束。 */
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        boolean done = workers.awaitTermination(timeout, unit);
        for (ExecutorService q : kbQueues.values()) {
            q.awaitTermination(timeout, unit);
        }
        return done;
    }

    /* ---------------- 内部工具 ---------------- */

    private void overwriteSameName(KnowledgeBase kb, DocInfo incoming, List<DocInfo> docs) {        List<DocInfo> sameName = docs.stream()
                .filter(d -> d.getName().equals(incoming.getName()) && !d.getId().equals(incoming.getId()))
                .toList();
        if (sameName.isEmpty()) {
            return;
        }
        IndexRegistry.LoadedIndex li = registry.of(kb.getId());
        List<StoredVector> working = new ArrayList<>(li.snapshot());
        for (DocInfo old : sameName) {
            working = VectorIndex.removeDoc(working, old.getId());
            docs.remove(old);
            try {
                Files.deleteIfExists(fileStorePath(kb.getId(), old.getId(), old.getName()));
            } catch (IOException ignored) {
                // 覆盖场景旧文件缺失属正常
            }
        }
        li.replaceSnapshot(List.copyOf(working));
        kbService.updateDocs(kb.getId(), docs);
        registry.requestPersist(kb.getId());
    }

    private DocInfo newDoc(KnowledgeBase kb, String name, String ext, long size, String hash) {
        DocInfo doc = new DocInfo();
        doc.setId(UUID.randomUUID().toString());
        doc.setKbId(kb.getId());
        doc.setName(name);
        doc.setExt(ext);
        doc.setSize(size);
        doc.setContentHash(hash);
        doc.setStatus(DocStatus.PENDING);
        doc.setCreatedAt(System.currentTimeMillis());
        doc.setUpdatedAt(doc.getCreatedAt());
        return doc;
    }

    /** 同 KB 同名唯一约束在提交时即可判定：存在 READY/FAILED 同名 → 覆盖语义由管线处理，这里仅登记。 */
    private void upsertDocMeta(DocInfo doc) {
        List<DocInfo> docs = readDocs(doc.getKbId());
        docs.removeIf(d -> d.getId().equals(doc.getId()));
        docs.add(doc);
        kbService.updateDocs(doc.getKbId(), docs);
    }

    /** 状态变更：更新对象 + 替换工作列表条目 + 立即落盘。 */
    private void setStatus(DocInfo doc, DocStatus status, List<DocInfo> docs) {
        doc.setStatus(status);
        doc.setUpdatedAt(System.currentTimeMillis());
        replaceInList(docs, doc);
        kbService.updateDocs(doc.getKbId(), docs);
    }

    private void replaceInList(List<DocInfo> docs, DocInfo doc) {
        docs.removeIf(d -> d.getId().equals(doc.getId()));
        docs.add(doc);
    }

    private void persistKb(KnowledgeBase kb) {
        MetaFiles.writeList(Path.of(config.dataDir()).resolve("kbs.json"),
                kbService.listAll().stream()
                        .map(k -> k.getId().equals(kb.getId()) ? kb : k).toList());
    }

    private List<DocInfo> readDocs(String kbId) {
        return MetaFiles.readList(kbService.kbDir(kbId).resolve("docs.json"), DOC_LIST);
    }

    private Path fileStorePath(String kbId, String docId, String name) {
        return kbService.kbDir(kbId).resolve("files").resolve(docId + "_" + name);
    }

    /** 文件名净化：去路径成分与控制字符，防目录穿越（04 §7 安全）。 */
    static String sanitizeName(String name) {
        String n = name == null ? "untitled" : name;
        n = n.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1);
        n = n.replaceAll("[\\p{Cntrl}]", "").trim();
        if (n.isEmpty()) {
            n = "untitled";
        }
        if (n.length() > 180) {
            n = n.substring(n.length() - 180); // 保留扩展名在尾部
        }
        return n;
    }

    static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (Exception e) {
            return "";
        }
    }
}
