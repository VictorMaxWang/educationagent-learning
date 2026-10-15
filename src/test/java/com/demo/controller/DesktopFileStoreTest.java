package com.demo.controller;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** 只验证进程内缓存边界，不启动应用、不访问磁盘或外部转换服务。 */
class DesktopFileStoreTest {
    @Test void getKeepsTheSameFileAvailableForMultipleConversions() {
        AtomicLong time = new AtomicLong();
        DesktopFileStore store = new DesktopFileStore(time::get);
        byte[] bytes = "课程资料".getBytes(StandardCharsets.UTF_8);
        String id = store.put(bytes, "课程.txt", "text/plain");

        assertTrue(id.matches("[0-9a-f]{32}"));
        assertArrayEquals(bytes, store.get(id).bytes());
        assertEquals("课程.txt", store.get(id).fileName());
        assertEquals("text/plain", store.get(id).mime());
        assertSame(store.get(id), store.get(id));
    }

    @Test void expiryUsesUploadTimeAndReleasesCapacityAtTheExactBoundary() {
        AtomicLong time = new AtomicLong(1000);
        DesktopFileStore store = new DesktopFileStore(time::get);
        List<String> ids = fillEntries(store);

        time.addAndGet(DesktopFileStore.TTL_MILLIS - 1);
        assertNotNull(store.get(ids.get(0)));
        assertStatus(429, () -> store.put(new byte[1], "full", null));
        time.incrementAndGet();
        assertNull(store.get(ids.get(0))); // 读取未延长其他条目的到期时间。
        String next = store.put(new byte[1], "after-expiry", null);
        assertNotNull(store.get(next));
        assertTrue(ids.stream().allMatch(id -> store.get(id) == null));
    }

    @Test void entryLimitRejectsWithoutDiscardingValidUploads() {
        DesktopFileStore store = new DesktopFileStore(() -> 0);
        List<String> ids = fillEntries(store);

        assertStatus(429, () -> store.put(new byte[1], "overflow", null));
        assertTrue(ids.stream().allMatch(id -> store.get(id) != null));
        store.remove(ids.get(0));
        store.remove(ids.get(0)); // 重复删除不能重复扣减计数。
        String replacement = store.put(new byte[1], "replacement", null);
        assertNotNull(store.get(replacement));
        assertStatus(429, () -> store.put(new byte[1], "overflow-again", null));
    }

    @Test void byteBudgetAllowsTheExactLimitAndTakeReturnsItsCapacity() {
        DesktopFileStore store = new DesktopFileStore(() -> 0);
        byte[] thirtyMiB = new byte[DesktopFileStore.MAX_FILE_BYTES];
        String first = store.put(thirtyMiB, "first", null);
        store.put(thirtyMiB, "second", null);
        store.put(new byte[4 * 1024 * 1024], "last-four", null);

        assertStatus(429, () -> store.put(new byte[1], "overflow", null));
        assertNotNull(store.take(first));
        assertNull(store.take(first));
        assertNull(store.get(first));
        assertNotNull(store.put(thirtyMiB, "replacement", null));
        assertStatus(429, () -> store.put(new byte[1], "overflow-again", null));
    }

    @Test void expiredByteBudgetIsReclaimedBeforeAnotherUpload() {
        AtomicLong time = new AtomicLong();
        DesktopFileStore store = new DesktopFileStore(time::get);
        byte[] large = new byte[DesktopFileStore.MAX_FILE_BYTES];
        String first = store.put(large, "first", null);
        String second = store.put(large, "second", null);
        time.set(DesktopFileStore.TTL_MILLIS);

        assertNotNull(store.put(large, "after-expiry", null));
        assertNull(store.get(first));
        assertNull(store.get(second));
    }

    @Test void emptyAndOversizedFilesHaveExplicitClientErrors() {
        DesktopFileStore store = new DesktopFileStore(() -> 0);
        assertStatus(400, () -> store.put(null, "none", null));
        assertStatus(400, () -> store.put(new byte[0], "empty", null));
        assertStatus(413, () -> store.put(new byte[DesktopFileStore.MAX_FILE_BYTES + 1], "too-large", null));
        assertNull(store.get(null));
        assertNull(store.take(null));
        store.remove(null);
        assertNotNull(store.put(new byte[1], "valid", null));
    }

    @Test void simultaneousUploadsCannotOverbookTheLastEntrySlot() throws Exception {
        DesktopFileStore store = new DesktopFileStore(() -> 0);
        for (int i = 0; i < DesktopFileStore.MAX_ENTRIES - 1; i++) store.put(new byte[1], "seed", null);
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(8)) {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) results.add(workers.submit(() -> {
                start.await();
                try { store.put(new byte[1], "racing", null); return 200; }
                catch (ResponseStatusException full) { return full.getStatusCode().value(); }
            }));
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) statuses.add(result.get());
            assertEquals(1, statuses.stream().filter(status -> status == 200).count());
            assertEquals(7, statuses.stream().filter(status -> status == 429).count());
        }
    }

    private static List<String> fillEntries(DesktopFileStore store) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < DesktopFileStore.MAX_ENTRIES; i++) ids.add(store.put(new byte[1], "file-" + i, null));
        return ids;
    }

    private static void assertStatus(int code, Runnable action) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, action::run);
        assertEquals(code, error.getStatusCode().value());
    }
}
