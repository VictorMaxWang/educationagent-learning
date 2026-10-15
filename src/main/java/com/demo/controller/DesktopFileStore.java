package com.demo.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 桌面端上传文件/图片的临时缓存。
 * <p>
 * 前端把文件上传后拿到 fileId，再在聊天请求中携带 fileId；
 * 本组件在 fileId 与文件字节之间建立映射，供聊天接口取用（图片或普通文件）。
 * 本地单用户应用，缓存不设 TTL，消息处理后由调用方主动移除。
 */
@Slf4j
@Component
public class DesktopFileStore {

    /** 一次上传的文件条目 */
    public record FileEntry(byte[] bytes, String fileName, String mime) {}

    /** fileId → 文件条目 */
    private final Map<String, FileEntry> store = new ConcurrentHashMap<>();

    /** 存入文件，返回新生成的 fileId */
    public String put(byte[] bytes, String fileName, String mime) {
        String fileId = UUID.randomUUID().toString().replace("-", "");
        store.put(fileId, new FileEntry(bytes, fileName, mime));
        log.info("📄 桌面端文件已缓存：{}KB, fileId={}, name={}, mime={}",
                bytes.length / 1024, fileId, fileName, mime);
        return fileId;
    }

    /** 取出并删除（一次消费，避免残留） */
    public FileEntry take(String fileId) {
        if (fileId == null) return null;
        return store.remove(fileId);
    }

    /** 只读不删（供刷题解析等可能重试的场景使用，成功后由调用方 remove） */
    public FileEntry get(String fileId) {
        if (fileId == null) return null;
        return store.get(fileId);
    }

    /** 按 fileId 删除（刷题解析成功后清理） */
    public void remove(String fileId) {
        if (fileId != null) store.remove(fileId);
    }
}
