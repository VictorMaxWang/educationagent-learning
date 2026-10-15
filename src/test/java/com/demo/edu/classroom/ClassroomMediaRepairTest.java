package com.demo.edu.classroom;

import com.demo.config.AiChatProperties;
import com.demo.edu.classroom.media.ClassroomImageTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 使用真实媒体修复、校验与保存逻辑，只替换供应商HTTP和数据库边界。
 * 组合现有fixture而不继承其测试，避免重复执行整套课堂状态机用例。
 */
class ClassroomMediaRepairTest {
    private static final String PRIVATE_PROMPT = "repair-private-prompt-marker-42 植物叶片教学插图";
    private final ClassroomCourseServiceTest fixture = new ClassroomCourseServiceTest();
    @TempDir Path directory;
    private RepairTransport transport;
    private ClassroomMediaService media;

    @BeforeEach void setupRepair() {
        fixture.setup();
        transport = new RepairTransport();
        MockEnvironment environment = new MockEnvironment()
                .withProperty("classroom.data-dir", directory.toString())
                .withProperty("AI_CHAT_BASE_URL", "https://provider.example.test/v1")
                .withProperty("AI_CHAT_API_KEY", "test-chat-key")
                .withProperty("classroom.image.protocol", "openai")
                .withProperty("classroom.image.model", "gpt-image-1");
        media = new ClassroomMediaService(new AiChatProperties(environment), environment, fixture.json, transport);
    }

    @Test void failedRetryLeavesOriginalCourseAndPrivateCandidateUnchanged() throws Exception {
        String id = pendingCourse();
        String before = fixture.documents.get(id);
        ObjectNode original = internal(id);
        assertTrue(original.at("/scenes/0/_pendingMediaRepair/request/prompt").asText().contains(PRIVATE_PROMPT));
        assertEquals("原课件正文", original.at("/scenes/0/content/canvas/elements/0/content").asText());
        clearInvocations(fixture.engine, fixture.quizzes);

        ClassroomException error = assertThrows(ClassroomException.class,
                () -> fixture.service.retryMedia(id, "step-1", original.path("revision").asLong()));

        assertEquals(502, error.status());
        assertEquals(before, fixture.documents.get(id), "失败重试不得重写正文、进度或待修复候选");
        assertFalse(error.getMessage().contains("provider-private-error"));
        verifyNoInteractions(fixture.engine, fixture.quizzes);
    }

    @Test void successfulRepairOnlyAddsImageAndPreservesProgressQuizActionsAndBoards() throws Exception {
        String id = pendingCourse();
        ObjectNode course = internal(id);
        course.putObject("progress").put("sceneIndex", 2).put("actionIndex", 1);
        course.putObject("submissions").putObject("answered-before-repair").put("result", "preserved");
        course.withArray("messages").addObject().put("speaker", "user").put("sceneId", "step-1").put("text", "已有提问");
        course.withArray("history").addObject().put("role", "user").put("content", "已有历史");
        ObjectNode runtime = ((ObjectNode) course.path("runtime")).putObject("step-1");
        runtime.putObject("whiteboard").put("id", "existing-board").put("isOpen", true)
                .set("canvas", fixture.textContent("人工板书").path("canvas"));
        runtime.putArray("acknowledgedActionIds").add("speech-1");
        // 修复目标是图文页；旁边的已关联题库必须连同历史ID原样保留。
        ((ObjectNode) course.path("scenes").get(3)).put("quizId", 731);
        fixture.documents.put(id, course.toString());
        ObjectNode before = internal(id);
        transport.fail = false;
        clearInvocations(fixture.engine, fixture.quizzes);

        ObjectNode visible = fixture.service.retryMedia(id, "step-1", before.path("revision").asLong());
        ObjectNode after = internal(id);
        ObjectNode repaired = (ObjectNode) after.path("scenes").get(0);

        assertEquals(id, visible.path("id").asText());
        assertEquals(1, fixture.documents.size(), "修复不得创建替代课程");
        assertEquals(before.path("revision").asLong() + 1, after.path("revision").asLong());
        for (String field : List.of("progress", "submissions", "messages", "history", "runtime", "outlines"))
            assertEquals(before.path(field), after.path(field), field);
        for (int index = 1; index < 4; index++)
            assertEquals(before.path("scenes").get(index), after.path("scenes").get(index));
        assertEquals(before.at("/scenes/0/actions"), repaired.path("actions"));
        assertEquals(before.at("/scenes/0/whiteboards"), repaired.path("whiteboards"));
        assertEquals(before.at("/scenes/0/content/canvas/elements/0"), repaired.at("/content/canvas/elements/0"));
        assertEquals(2, repaired.at("/content/canvas/elements").size());
        assertEquals("pending-image", repaired.at("/content/canvas/elements/1/id").asText());
        assertTrue(repaired.at("/content/canvas/elements/1/src").asText().matches("/api/classroom/media/[0-9a-f]{64}\\.jpg"));
        assertFalse(repaired.has("_pendingMediaRepair"));
        assertFalse(repaired.has("mediaWarnings"));
        assertFalse(visible.at("/scenes/0/mediaRetryAvailable").asBoolean());
        assertEquals("media-repair", repaired.at("/validation/origin").asText());
        assertEquals("NOT_RUN", repaired.at("/validation/checks/browserRuntime").asText());
        assertTrue(ClassroomValidationEvidence.matches(repaired));
        verifyNoInteractions(fixture.engine, fixture.quizzes);
    }

    @Test void staleRevisionIsRejectedBeforeAnyNewProviderRequest() throws Exception {
        String id = pendingCourse();
        ObjectNode before = internal(id);
        int posts = transport.posts;
        transport.fail = false;
        assertEquals(409, assertThrows(ClassroomException.class,
                () -> fixture.service.retryMedia(id, "step-1", before.path("revision").asLong() - 1)).status());
        assertEquals(posts, transport.posts);
        assertEquals(before, internal(id));
    }

    @Test void editWhileProviderIsRunningWinsAndOldPendingCannotReappear() throws Exception {
        String id = pendingCourse();
        long revision = internal(id).path("revision").asLong();
        transport.fail = false;
        // 回调发生在真实供应商边界；同线程模拟并发提交，不使用时序不稳定的sleep。
        transport.onPost = () -> fixture.service.editScene(id, "step-1",
                new ClassroomCourseService.SceneEditRequest(revision, fixture.textContent("编辑后的新正文"), null));

        assertEquals(409, assertThrows(ClassroomException.class,
                () -> fixture.service.retryMedia(id, "step-1", revision)).status());

        ObjectNode after = internal(id);
        assertEquals("编辑后的新正文", after.at("/scenes/0/content/canvas/elements/0/content").asText());
        assertEquals(1, after.at("/scenes/0/content/canvas/elements").size());
        assertFalse(after.path("scenes").get(0).has("_pendingMediaRepair"));
        assertFalse(fixture.service.get(id).at("/scenes/0/mediaRetryAvailable").asBoolean());
        assertEquals("edit", after.at("/scenes/0/validation/origin").asText());
        assertTrue(ClassroomValidationEvidence.matches((ObjectNode) after.path("scenes").get(0)));
        int posts = transport.posts;
        assertEquals(409, assertThrows(ClassroomException.class,
                () -> fixture.service.retryMedia(id, "step-1", after.path("revision").asLong())).status());
        assertEquals(posts, transport.posts);
    }

    @Test void progressSavedWhileGeneratingImageSurvivesTheSuccessfulCommit() throws Exception {
        String id = pendingCourse();
        long revision = internal(id).path("revision").asLong();
        transport.fail = false;
        transport.onPost = () -> fixture.service.saveProgress(id, 2, 0);
        fixture.service.retryMedia(id, "step-1", revision);
        assertEquals(2, internal(id).at("/progress/sceneIndex").asInt());
        assertEquals(0, internal(id).at("/progress/actionIndex").asInt());
        assertFalse(internal(id).path("scenes").get(0).has("_pendingMediaRepair"));
    }

    @Test void privatePromptIsAbsentFromPublicAndPortableCopiesIncludingOutlineAlias() throws Exception {
        String id = pendingCourse();
        assertFalse(fixture.service.get(id).toString().contains(PRIVATE_PROMPT), "大纲中的同值副本也不得公开待重试提示");
        assertFalse(fixture.service.list().toString().contains(PRIVATE_PROMPT));
        assertFalse(fixture.service.portable(id).toString().contains(PRIVATE_PROMPT));
        assertTrue(internal(id).at("/scenes/0/_pendingMediaRepair/request/prompt").asText().contains(PRIVATE_PROMPT));
    }

    @Test void importingPortableDoesNotTrustPendingRequestOrBrowserPassAndKeepsOldCourse() throws Exception {
        String id = pendingCourse();
        String original = fixture.documents.get(id);
        ObjectNode envelope = fixture.service.portable(id);
        ObjectNode first = (ObjectNode) envelope.at("/course/scenes/0");
        first.set("_pendingMediaRepair", internal(id).at("/scenes/0/_pendingMediaRepair").deepCopy());
        first.put("mediaRetryAvailable", true);
        first.putObject("validation").put("schema", "forged").putObject("checks").put("browserRuntime", "PASS");
        int posts = transport.posts;
        ObjectNode imported = fixture.service.importPortable(envelope);
        String newId = imported.path("id").asText();
        assertNotEquals(id, newId);
        assertEquals(original, fixture.documents.get(id));
        assertEquals(posts, transport.posts, "导入不得运行隐藏的图片请求");
        ObjectNode newScene = (ObjectNode) internal(newId).path("scenes").get(0);
        assertFalse(newScene.has("_pendingMediaRepair"));
        assertFalse(imported.at("/scenes/0/mediaRetryAvailable").asBoolean());
        assertEquals("import", newScene.at("/validation/origin").asText());
        assertEquals("NOT_RUN", newScene.at("/validation/checks/browserRuntime").asText());
        assertTrue(ClassroomValidationEvidence.matches(newScene));
    }

    private String pendingCourse() throws Exception {
        String id = fixture.readyCourse();
        ObjectNode course = internal(id);
        ObjectNode raw = (ObjectNode) course.path("scenes").get(0).deepCopy();
        raw.set("content", fixture.textContent("原课件正文"));
        ((ArrayNode) raw.at("/content/canvas/elements")).addObject()
                .put("id", "pending-image").put("type", "image").put("src", "gen_img_leaf")
                .put("left", 350).put("top", 10).put("width", 200).put("height", 160);
        raw.putArray("whiteboards").add(fixture.textContent("预设白板").path("canvas"));
        raw.withArray("actions").addObject().put("id", "point-text").put("type", "spotlight").put("elementId", "text-1");
        raw.withArray("actions").addObject().put("id", "point-image").put("type", "laser").put("elementId", "pending-image");
        ObjectNode outline = (ObjectNode) course.path("outlines").get(0);
        outline.putArray("mediaGenerations").addObject().put("type", "image").put("elementId", "gen_img_leaf")
                .put("prompt", PRIVATE_PROMPT).put("aspectRatio", "1:1");
        ObjectNode original = raw.deepCopy();
        ObjectNode failed = media.enrichSlide(raw, outline, new ClassroomCancellation());
        assertEquals(original, raw, "媒体服务必须在副本上移除失败占位");
        assertTrue(failed.has("_pendingMediaRepair"));
        assertEquals(2, failed.path("actions").size(), "仅移除失效图片动作，保留讲稿和文本指示");
        failed = ClassroomDocumentValidator.scene(failed, outline, id);
        ClassroomValidationEvidence.record(failed, course.path("revision").asLong(), "generate");
        ((ArrayNode) course.path("scenes")).set(0, failed);
        fixture.documents.put(id, course.toString());
        fixture.service.setMediaService(media);
        return id;
    }

    private ObjectNode internal(String id) throws IOException {
        return (ObjectNode) fixture.json.readTree(fixture.documents.get(id));
    }

    private final class RepairTransport implements ClassroomImageTransport {
        private boolean fail = true;
        private int posts;
        private Runnable onPost;
        @Override public byte[] postJson(URI endpoint, String key, byte[] body, ClassroomCancellation token) throws IOException {
            posts++;
            Runnable callback = onPost;
            onPost = null;
            if (callback != null) callback.run();
            if (fail) throw new IOException("provider-private-error");
            return fixture.json.writeValueAsBytes(Map.of("data", List.of(Map.of("b64_json",
                    Base64.getEncoder().encodeToString(ClassroomMediaServiceTest.png(64, 64))))));
        }
        @Override public byte[] download(URI image, ClassroomCancellation token) throws IOException {
            throw new IOException("本夹具只返回base64图像，不下载远端文件");
        }
    }
}
