package com.demo.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * 桌面端上传文件/图片的临时缓存。
 * <p>
 * 前端把文件上传后拿到 fileId，再在聊天请求中携带 fileId；
 * 本组件在 fileId 与文件字节之间建立映射，供聊天接口取用（图片或普通文件）。
 * 公共上传的缓存必须有界；读取不消费，兼容同一文件尝试多个转换格式。
 * 到期时间从上传时计算，不因读取续期。每次访问都清理过期项，无需后台线程。
 */
@Slf4j
@Component
public class DesktopFileStore {

    static final int MAX_FILE_BYTES = 30 * 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;
    static final int MAX_ENTRIES = 64;
    static final long TTL_MILLIS = 15L * 60 * 1000;

    /** 一次上传的文件条目 */
    public record FileEntry(byte[] bytes, String fileName, String mime) {}

    private record StoredFile(FileEntry entry, long uploadedAt) {}

    /** 所有访问共用监视器，使过期清理、容量检查、插入和字节计数成为同一原子操作。 */
    private final Map<String, StoredFile> store = new HashMap<>();
    private final LongSupplier clock;
    private long totalBytes;

    public DesktopFileStore() {
        this(System::currentTimeMillis);
    }

    /** 测试使用可控时钟验证到期边界，不等待真实十五分钟。 */
    DesktopFileStore(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * 存入文件并返回新 fileId；空文件返回400，超过单项限制返回413，缓存容量不足返回429。
     * 容量不足时不挤掉其他尚有效的上传，避免转换中的文件被无提示丢弃。
     */
    public synchronized String put(byte[] bytes, String fileName, String mime) {
        long now = clock.getAsLong();
        removeExpired(now);
        if (bytes == null || bytes.length == 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件内容不能为空");
        if (bytes.length > MAX_FILE_BYTES)
            throw new ResponseStatusException(HttpStatusCode.valueOf(413), "单个上传文件不能超过30 MiB");
        if (store.size() >= MAX_ENTRIES || bytes.length > MAX_TOTAL_BYTES - totalBytes)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "上传缓存暂满，请稍后重试");
        String fileId;
        do { fileId = UUID.randomUUID().toString().replace("-", ""); }
        while (store.containsKey(fileId));
        store.put(fileId, new StoredFile(new FileEntry(bytes, fileName, mime), now));
        totalBytes += bytes.length;
        log.info("📄 桌面端文件已缓存：{}KB, fileId={}, name={}, mime={}",
                bytes.length / 1024, fileId, fileName, mime);
        return fileId;
    }

    /** 取出并删除（一次消费，避免残留） */
    public synchronized FileEntry take(String fileId) {
        removeExpired(clock.getAsLong());
        return removeEntry(fileId);
    }

    /** 只读不删（供刷题解析等可能重试的场景使用，成功后由调用方 remove） */
    public synchronized FileEntry get(String fileId) {
        removeExpired(clock.getAsLong());
        StoredFile stored = store.get(fileId);
        return stored == null ? null : stored.entry();
    }

    /** 按 fileId 删除（刷题解析成功后清理） */
    public synchronized void remove(String fileId) {
        removeExpired(clock.getAsLong());
        removeEntry(fileId);
    }

    private FileEntry removeEntry(String fileId) {
        StoredFile stored = store.remove(fileId);
        if (stored == null) return null;
        totalBytes -= stored.entry().bytes().length;
        return stored.entry();
    }

    private void removeExpired(long now) {
        Iterator<StoredFile> entries = store.values().iterator();
        while (entries.hasNext()) {
            StoredFile stored = entries.next();
            if (now - stored.uploadedAt() >= TTL_MILLIS) {
                totalBytes -= stored.entry().bytes().length;
                entries.remove();
            }
        }
    }
}
