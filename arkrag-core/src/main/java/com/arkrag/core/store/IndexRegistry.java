package com.arkrag.core.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.arkrag.core.exception.ArkRagException;
import com.arkrag.core.exception.ErrorCode;

/**
 * 索引注册表：kbId → 内存向量快照的运行时持有者（04 §4/§6）。
 *
 * 并发模型：写路径在 KB 锁内对工作副本变更后，构建**不可变快照**换入 volatile 引用；
 * 读路径（搜索）只读快照，全程无锁。持久化由单线程调度器防抖（500ms）执行——
 * 摄入批内多次变更只落一次盘。
 */
public class IndexRegistry {

    /** 单个 KB 的运行时索引。 */
    public static final class LoadedIndex {
        private final String kbId;
        private final Path vectorFile;
        private volatile List<StoredVector> snapshot;

        LoadedIndex(String kbId, Path vectorFile, List<StoredVector> initial) {
            this.kbId = kbId;
            this.vectorFile = vectorFile;
            this.snapshot = initial;
        }

        /** 当前不可变快照（搜索热路径读取，禁止修改）。 */
        public List<StoredVector> snapshot() {
            return snapshot;
        }

        public String kbId() {
            return kbId;
        }

        Path vectorFile() {
            return vectorFile;
        }

        /** 原子换入新快照（写路径专用，调用方持有 KB 锁）。 */
        public void replaceSnapshot(List<StoredVector> next) {
            this.snapshot = next;
        }
    }

    private final Path dataDir;
    private final ConcurrentHashMap<String, LoadedIndex> indexes = new ConcurrentHashMap<>();
    private final ScheduledExecutorService persister =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "arkrag-index-persister");
                t.setDaemon(true);
                return t;
            });
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public IndexRegistry(String dataDir) {
        this.dataDir = Path.of(dataDir);
    }

    /**
     * 启动加载：扫描数据目录下各 KB 的 vectors.json 全部载入内存。
     * 数据量级 ≤ 数十万块时启动秒级；超出属于容量边界（见 04 §7），交付说明已注明。
     */
    public void loadAll() {
        Path kbRoot = dataDir.resolve("kb");
        if (!Files.isDirectory(kbRoot)) {
            return;
        }
        try (var stream = Files.list(kbRoot)) {
            stream.filter(Files::isDirectory).forEach(dir -> {
                String kbId = dir.getFileName().toString();
                Path vectorFile = dir.resolve("vectors.json");
                indexes.put(kbId, new LoadedIndex(kbId, vectorFile, VectorIndex.load(vectorFile)));
            });
        } catch (Exception e) {
            throw new ArkRagException(ErrorCode.SERVER_ERROR, "启动加载索引失败：" + kbRoot, null, e);
        }
    }

    /** 取 KB 的运行时索引；未加载（未建库/刚创建）返回空快照并注册。 */
    public LoadedIndex of(String kbId) {
        return indexes.computeIfAbsent(kbId, id ->
                new LoadedIndex(id, kbDir(id).resolve("vectors.json"),
                        VectorIndex.load(kbDir(id).resolve("vectors.json"))));
    }

    /** 请求把 KB 当前快照落盘（防抖 500ms；幂等）。 */
    public void requestPersist(String kbId) {
        if (closed.get()) {
            return;
        }
        persister.schedule(() -> {
            LoadedIndex li = indexes.get(kbId);
            if (li != null) {
                VectorIndex.save(li.snapshot(), li.vectorFile());
            }
        }, 500, TimeUnit.MILLISECONDS);
    }

    /** 立即落盘指定 KB（关闭时/测试用）。 */
    public void persistNow(String kbId) {
        LoadedIndex li = indexes.get(kbId);
        if (li != null) {
            VectorIndex.save(li.snapshot(), li.vectorFile());
        }
    }

    /** 全部落盘（优雅停机）。 */
    public void persistAll() {
        indexes.keySet().forEach(this::persistNow);
    }

    /** 删除 KB 的内存索引与磁盘索引文件（目录由上层连文件一起删）。 */
    public void evict(String kbId) {
        LoadedIndex li = indexes.remove(kbId);
        if (li != null) {
            try {
                Files.deleteIfExists(li.vectorFile());
            } catch (Exception ignored) {
                // 磁盘清理失败不阻塞元数据删除；残留会在下次重建索引时被覆盖
            }
        }
    }

    /** 停机：落盘全部并关闭调度线程。 */
    public void close() {
        if (closed.compareAndSet(false, true)) {
            persister.shutdown();
            persistAll();
        }
    }

    private Path kbDir(String kbId) {
        return dataDir.resolve("kb").resolve(kbId);
    }
}
