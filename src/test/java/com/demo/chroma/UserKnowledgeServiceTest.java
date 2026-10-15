package com.demo.chroma;

import com.demo.chroma.base.ChromaClient;
import com.demo.chroma.base.EmbeddingService;
import com.demo.service.preprocessing.FileService;
import com.demo.service.tools.holder.UserFileHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 验证公开上传文件名不能变成服务器路径；仅写入 JUnit 的临时目录，不连接向量库。 */
class UserKnowledgeServiceTest {
    @TempDir Path temporary;
    private Path root;
    private UserKnowledgeService service;
    private UserFileHolder holder;
    private EmbeddingService embedding;
    private ChromaClient chroma;

    @BeforeEach void setup() {
        root = temporary.resolve("knowledge");
        service = new UserKnowledgeService();
        holder = mock(UserFileHolder.class);
        embedding = mock(EmbeddingService.class);
        chroma = mock(ChromaClient.class);
        ReflectionTestUtils.setField(service, "storageDirectory", root);
        ReflectionTestUtils.setField(service, "userFileHolder", holder);
        ReflectionTestUtils.setField(service, "embeddingService", embedding);
        ReflectionTestUtils.setField(service, "chromaClient", chroma);
        ReflectionTestUtils.setField(service, "fileService", mock(FileService.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../escape.txt", "..\\escape.txt", "/tmp/escape.txt", "C:\\temp\\escape.txt", "C:escape.txt",
            "file.txt:stream", "CON.txt", "NUL", "file.txt.", "file.txt ", "bad\u0000.txt"})
    void maliciousNamesAreRejectedBeforeWritingAnyUserDirectory(String name) throws Exception {
        Files.writeString(temporary.resolve("escape.txt"), "original");
        upload(name, "untrusted");
        assertTrue(service.saveAndIndex("student").startsWith("❌"));
        assertFalse(Files.exists(root));
        assertEquals("original", Files.readString(temporary.resolve("escape.txt")));
        verifyNoInteractions(embedding, chroma);
    }

    @Test void normalChineseNameAndDuplicateUploadRemainInsideUserDirectoryWithoutOverwrite() throws Exception {
        upload("第一课资料.txt", "first");
        assertTrue(service.saveAndIndex("student").contains("已保存"));
        upload("第一课资料.txt", "second");
        assertTrue(service.saveAndIndex("student").contains("已保存"));
        assertEquals("first", Files.readString(root.resolve("student/第一课资料.txt")));
        try (var files = Files.list(root.resolve("student"))) {
            List<Path> saved = files.toList();
            assertEquals(2, saved.size());
            assertTrue(saved.stream().allMatch(path -> path.normalize().startsWith(root)));
            assertTrue(saved.stream().anyMatch(path -> {
                try { return "second".equals(Files.readString(path)); }
                catch (Exception error) { return false; }
            }));
        }
        assertEquals(2, service.listFiles("student").size());
        assertEquals(2, service.listDetail("student").size());
        verifyNoInteractions(embedding, chroma);
    }

    @Test void readAndDeleteAlsoRejectPathsOutsideTheUserDirectory() throws Exception {
        Files.createDirectories(root.resolve("student"));
        Path outside = root.resolve("outside.txt");
        Files.writeString(outside, "keep");
        assertNull(service.viewContent("student", "../outside.txt"));
        assertFalse(service.delete("student", "../outside.txt"));
        assertEquals("keep", Files.readString(outside));
        verifyNoInteractions(chroma);
    }

    @Test void existingSymbolicLinkDoesNotAllowUploadReadOrDeleteOfItsTarget() throws Exception {
        Files.createDirectories(root.resolve("student"));
        Path outside = temporary.resolve("outside.txt");
        Files.writeString(outside, "original");
        try { Files.createSymbolicLink(root.resolve("student/link.txt"), outside); }
        catch (UnsupportedOperationException | java.io.IOException unavailable) {
            org.junit.jupiter.api.Assumptions.abort("当前主机不允许创建测试符号链接");
        }
        upload("link.txt", "untrusted");
        assertTrue(service.saveAndIndex("student").startsWith("❌"));
        assertNull(service.viewContent("student", "link.txt"));
        assertFalse(service.delete("student", "link.txt"));
        assertEquals("original", Files.readString(outside));
    }

    private void upload(String name, String content) {
        when(holder.get(any())).thenReturn(new UserFileHolder.FileEntry(content.getBytes(StandardCharsets.UTF_8), name,
                "", System.currentTimeMillis(), null));
    }
}
