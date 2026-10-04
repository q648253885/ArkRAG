package com.arkrag.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 文档元数据。同 KB 内 name 唯一（同名再次上传 = 覆盖旧版本，docId 变化）。
 * contentHash 为文件内容 sha256，供重复上传提示与重建索引。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DocInfo {
    private String id;
    private String kbId;
    private String name;
    private String ext;
    private long size;
    private String contentHash;
    private int chunkCount;
    private DocStatus status = DocStatus.PENDING;
    private String error;
    private long createdAt;
    private long updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getKbId() { return kbId; }
    public void setKbId(String kbId) { this.kbId = kbId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getExt() { return ext; }
    public void setExt(String ext) { this.ext = ext; }
    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public int getChunkCount() { return chunkCount; }
    public void setChunkCount(int chunkCount) { this.chunkCount = chunkCount; }
    public DocStatus getStatus() { return status; }
    public void setStatus(DocStatus status) { this.status = status; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
