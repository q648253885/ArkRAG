package com.arkrag.core.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import static com.arkrag.core.exception.ErrorCode.SERVER_ERROR;

/**
 * 元数据 JSON 文件（kbs.json / docs.json）的读写：整文件加载、整体原子替换。
 * 数据量级 = 安装内 KB 数与每 KB 文档数（百/千级），整文件读写足够；
 * 不引入数据库以保持"单 jar + 一个数据目录"的交付形态。
 */
public final class MetaFiles {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MetaFiles() {
    }

    /** 读取列表文件；文件不存在返回空列表。 */
    public static <T> List<T> readList(Path file, TypeReference<List<T>> type) {
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            return MAPPER.readValue(file.toFile(), type);
        } catch (IOException e) {
            throw new com.arkrag.core.exception.ArkRagException(SERVER_ERROR,
                    "元数据文件读取失败：" + file, null, e);
        }
    }

    /** 整体写入列表（唯一临时文件 + 原子 move；唯一名防并发写互踩 tmp）。 */
    public static <T> void writeList(Path file, List<T> list) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Path tmp = file.resolveSibling(file.getFileName() + "." + java.util.UUID.randomUUID() + ".tmp");
            Files.writeString(tmp, MAPPER.writeValueAsString(list), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new com.arkrag.core.exception.ArkRagException(SERVER_ERROR,
                    "元数据文件写入失败：" + file, null, e);
        }
    }

    /** 静态 Jackson 供本包内复用。 */
    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
