package com.demo.edu.classroom;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.demo.edu.classroom.study.ClassroomStudyStore;
import com.demo.edu.mapper.ClassroomCourseMapper;
import com.demo.entity.QuizPaperEntity;
import com.demo.entity.QuizQuestionEntity;
import com.demo.mapper.QuizPaperMapper;
import com.demo.mapper.QuizQuestionMapper;
import com.demo.study.quiz.QuizMutationGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 已有作答的课堂保留原题与历史成绩，通过不可覆盖的指针指向新练习。
 * 注册的锁顺序固定为 registry -> paper -> transaction，直到提交完成才释放 paper 锁；
 * 查询和判分检查不获取 registry 监视器，避免 paper -> registry 的逆序死锁。
 */
@Service
public class ClassroomQuizRevisionRegistry {
    static final String KIND = "quiz_supersession";
    static final String REQUEST_KIND = "quiz_revision_request";
    private static final int MAX_CHAIN = 64;
    private final ClassroomCourseMapper courses;
    private final QuizPaperMapper papers;
    private final QuizQuestionMapper questions;
    private final ClassroomStudyStore store;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    public ClassroomQuizRevisionRegistry(ClassroomCourseMapper courses, QuizPaperMapper papers,
            QuizQuestionMapper questions, ClassroomStudyStore store, ObjectMapper json,
            PlatformTransactionManager transactionManager) {
        this.courses = courses;
        this.papers = papers;
        this.questions = questions;
        this.store = store;
        this.json = json;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public record RegisterRequest(String oldCourseId, String oldSceneId, String newCourseId,
                                  String newSceneId, String reason, String requestId) {}

    /** 同一请求载荷重放返回原记录；不同载荷、覆盖已有指针与循环均拒绝。仅写增量存储。 */
    public synchronized ObjectNode register(RegisterRequest input) {
        RegisterRequest request = checked(input);
        ObjectNode replay = replay(request);
        if (replay != null) return replay;
        Endpoint oldEndpoint = endpoint(request.oldCourseId(), request.oldSceneId());
        Endpoint newEndpoint = endpoint(request.newCourseId(), request.newSceneId());
        if (oldEndpoint.paperId() == newEndpoint.paperId()) throw conflict("练习修订不能指向自身试卷");
        return QuizMutationGuard.withPapers(List.of(oldEndpoint.paperId(), newEndpoint.paperId()), () ->
                transactions.execute(status -> {
                    ObjectNode committedReplay = replay(request);
                    if (committedReplay != null) return committedReplay;
                    // 预读只用于确定锁；获得锁之后重新确认页面与题库关联。
                    if (!oldEndpoint.equals(endpoint(request.oldCourseId(), request.oldSceneId()))
                            || !newEndpoint.equals(endpoint(request.newCourseId(), request.newSceneId())))
                        throw conflict("课程练习关联已变化，请重新核对后登记修订");
                    validatePaper(oldEndpoint);
                    validatePaper(newEndpoint);
                    if (store.find(KIND, Long.toString(oldEndpoint.paperId())).isPresent())
                        throw conflict("原练习已登记修订，不能覆盖已有修订指针");
                    // 新边也占一层；共享写监视器使当前单实例内的链检查与登记不可交错。
                    Endpoint finalTarget = finalTarget(newEndpoint, Set.of(oldEndpoint.paperId()), MAX_CHAIN - 1);
                    if (!finalTarget.equals(newEndpoint)) {
                        Endpoint currentFinal = endpoint(finalTarget.courseId(), finalTarget.sceneId());
                        if (currentFinal.paperId() != finalTarget.paperId()) throw conflict("最终修订页面的题库关联已变化");
                        validatePaper(currentFinal);
                    }
                    ObjectNode document = json.createObjectNode()
                            .put("oldCourseId", request.oldCourseId()).put("oldSceneId", request.oldSceneId())
                            .put("oldPaperId", oldEndpoint.paperId()).put("newCourseId", request.newCourseId())
                            .put("newSceneId", request.newSceneId()).put("newPaperId", newEndpoint.paperId())
                            .put("reason", request.reason()).put("createdAt", Instant.now().toString())
                            .put("requestId", request.requestId());
                    if (!store.create(KIND, Long.toString(oldEndpoint.paperId()), request.oldCourseId(), document))
                        throw conflict("原练习修订已被其他请求登记，请重新查询");
                    ObjectNode requestIndex = document.deepCopy();
                    if (!store.create(REQUEST_KIND, request.requestId(), request.oldCourseId(), requestIndex))
                        // INSERT IGNORE 不抛异常；显式失败才能回滚前面已写入的主指针。
                        throw conflict("修订请求标识已使用，未登记任何新指针");
                    return document.deepCopy();
                }));
    }

    /** 公开投影只给跳转与说明，沿继承链解析最终页面，不输出试卷号或幂等请求信息。 */
    public ObjectNode publicRevisions(String courseId) {
        String id = identifier(courseId, 64, "课程标识");
        ObjectNode course = course(id);
        ObjectNode response = json.createObjectNode().put("courseId", id);
        var revisions = response.putArray("revisions");
        Set<String> seen = new HashSet<>();
        for (JsonNode scene : course.path("scenes")) {
            if (!"quiz".equals(scene.path("type").asText()) || !scene.path("quizId").isIntegralNumber()) continue;
            long paperId = positiveId(scene, "quizId");
            String sceneId = scene.path("id").asText();
            if (!seen.add(sceneId)) throw conflict("课程包含重复练习页面，无法读取修订关系");
            ObjectNode pointer = store.find(KIND, Long.toString(paperId)).orElse(null);
            if (pointer == null) continue;
            Endpoint source = new Endpoint(id, sceneId, paperId, scene.path("content").path("questions").size());
            revisions.add(publicProjection(pointer, source).put("sceneId", sceneId));
        }
        return response;
    }

    /** 错题本使用的只读投影：没有修订返回 null，有修订只给最终跳转目标及原登记说明。 */
    public ObjectNode revisionForPaper(Long paperId) {
        if (paperId == null || paperId <= 0) throw new ClassroomException(400, "试卷标识无效");
        ObjectNode pointer = store.find(KIND, paperId.toString()).orElse(null);
        if (pointer == null) return null;
        Endpoint source = new Endpoint(pointer.path("oldCourseId").asText(), pointer.path("oldSceneId").asText(), paperId, 0);
        return publicProjection(pointer, source);
    }

    private ObjectNode publicProjection(ObjectNode pointer, Endpoint source) {
        verifyPointer(pointer, source);
        Endpoint target = finalTarget(source, Set.of(), MAX_CHAIN);
        // 创建新对象而非删改持久化记录，避免将来增加私有字段时意外经错题本公开。
        return json.createObjectNode().put("newCourseId", target.courseId()).put("newSceneId", target.sceneId())
                .put("reason", pointer.path("reason").asText()).put("createdAt", pointer.path("createdAt").asText());
    }

    /** 判分入口在持有 paper 锁时调用；已修订的历史练习可阅读，但不能再提交或重做计分。 */
    public void assertScorable(Long paperId) {
        if (paperId == null || paperId <= 0) throw new ClassroomException(400, "试卷标识无效");
        ObjectNode pointer = store.find(KIND, paperId.toString()).orElse(null);
        if (pointer == null) return;
        Endpoint source = new Endpoint(pointer.path("oldCourseId").asText(), pointer.path("oldSceneId").asText(), paperId, 0);
        Endpoint target = finalTarget(source, Set.of(), MAX_CHAIN);
        throw conflict("本练习已有修订版，请前往课程 " + target.courseId() + " 的页面 " + target.sceneId() + " 作答");
    }

    private ObjectNode replay(RegisterRequest request) {
        ObjectNode index = store.find(REQUEST_KIND, request.requestId()).orElse(null);
        if (index == null) return null;
        if (!samePayload(index, request)) throw conflict("该修订请求标识已用于其他载荷");
        long oldPaperId = positiveId(index, "oldPaperId");
        ObjectNode record = store.find(KIND, Long.toString(oldPaperId))
                .orElseThrow(() -> new ClassroomException(500, "修订登记记录不完整，请核对原记录"));
        if (!record.equals(index)) throw new ClassroomException(500, "修订登记记录不一致，请核对原记录");
        return record.deepCopy();
    }

    private Endpoint finalTarget(Endpoint start, Set<Long> forbidden, int maxLinks) {
        Set<Long> visited = new HashSet<>(forbidden);
        Endpoint current = start;
        int links = 0;
        while (true) {
            if (!visited.add(current.paperId())) throw conflict("练习修订关系不能形成循环");
            ObjectNode pointer = store.find(KIND, Long.toString(current.paperId())).orElse(null);
            if (pointer == null) return current;
            verifyPointer(pointer, current);
            if (links++ >= maxLinks) throw conflict("练习修订链超过64层，请核对修订关系");
            current = new Endpoint(pointer.path("newCourseId").asText(), pointer.path("newSceneId").asText(),
                    positiveId(pointer, "newPaperId"), 0);
        }
    }

    private static void verifyPointer(ObjectNode pointer, Endpoint source) {
        if (positiveId(pointer, "oldPaperId") != source.paperId()
                || !pointer.path("oldCourseId").isTextual() || pointer.path("oldCourseId").asText().isBlank()
                || !pointer.path("oldSceneId").isTextual() || pointer.path("oldSceneId").asText().isBlank()
                || !source.courseId().equals(pointer.path("oldCourseId").asText())
                || !source.sceneId().equals(pointer.path("oldSceneId").asText())
                || !pointer.path("newCourseId").isTextual() || pointer.path("newCourseId").asText().isBlank()
                || !pointer.path("newSceneId").isTextual() || pointer.path("newSceneId").asText().isBlank())
            throw new ClassroomException(500, "修订登记归属异常，请核对原记录");
    }

    private Endpoint endpoint(String courseId, String sceneId) {
        ObjectNode course = course(courseId);
        JsonNode match = null;
        for (JsonNode candidate : course.path("scenes")) if (sceneId.equals(candidate.path("id").asText())) {
            if (match != null) throw conflict("课程包含重复页面标识");
            match = candidate;
        }
        if (match == null) throw new ClassroomException(404, "指定课程中没有该练习页面");
        JsonNode quiz = match.path("content").path("questions");
        if (!"quiz".equals(match.path("type").asText()) || !quiz.isArray() || quiz.isEmpty())
            throw new ClassroomException(422, "修订来源与目标都必须是已有计分题目的练习页");
        return new Endpoint(courseId, sceneId, positiveId(match, "quizId"), quiz.size());
    }

    private ObjectNode course(String id) {
        String document = courses.find(id);
        if (document == null) throw new ClassroomException(404, "课程不存在");
        try {
            JsonNode parsed = json.readTree(document);
            if (parsed instanceof ObjectNode object && id.equals(object.path("id").asText()) && object.path("scenes").isArray()) return object;
        } catch (Exception ignored) { /* 不把原始课程或解析异常内容放进公开错误。 */ }
        throw new ClassroomException(500, "课程记录格式或归属异常");
    }

    private void validatePaper(Endpoint endpoint) {
        QuizPaperEntity paper = papers.selectById(endpoint.paperId());
        if (paper == null || !Objects.equals(paper.getId(), endpoint.paperId())
                || !ClassroomCourseService.LEARNER_ID.equals(paper.getUserId())
                || !"classroom".equals(paper.getFileType()) || !Objects.equals(paper.getDeleted(), 0)
                || !("classroom:" + endpoint.courseId() + ":" + endpoint.sceneId()).equals(paper.getSourceFileName())
                || !Objects.equals(paper.getQuestionCount(), endpoint.questionCount()))
            throw conflict("练习题库不存在、归属不符或题数不一致");
        List<QuizQuestionEntity> rows = questions.selectList(new QueryWrapper<QuizQuestionEntity>().eq("paper_id", endpoint.paperId()));
        if (rows == null || rows.size() != endpoint.questionCount()) throw conflict("练习题库实际题数与课程不一致");
        Set<Long> ids = new HashSet<>();
        Set<Integer> numbers = new HashSet<>();
        for (QuizQuestionEntity row : rows) {
            if (row == null || row.getId() == null || row.getId() <= 0 || !ids.add(row.getId())
                    || !Objects.equals(row.getPaperId(), endpoint.paperId()) || row.getQuestionNo() == null
                    || row.getQuestionNo() < 1 || row.getQuestionNo() > endpoint.questionCount()
                    || !numbers.add(row.getQuestionNo())) throw conflict("练习题库题号或归属异常");
        }
    }

    private static RegisterRequest checked(RegisterRequest value) {
        if (value == null) throw new ClassroomException(400, "缺少修订登记内容");
        RegisterRequest request = new RegisterRequest(identifier(value.oldCourseId(), 64, "原课程标识"),
                identifier(value.oldSceneId(), 80, "原页面标识"), identifier(value.newCourseId(), 64, "新课程标识"),
                identifier(value.newSceneId(), 80, "新页面标识"), identifier(value.reason(), 600, "修订原因"),
                identifier(value.requestId(), 160, "请求标识"));
        if (request.oldCourseId().equals(request.newCourseId()) && request.oldSceneId().equals(request.newSceneId()))
            throw conflict("练习修订不能指向同一课程的同一页面");
        return request;
    }

    private static String identifier(String value, int max, String label) {
        if (value == null || value.isBlank() || value.length() > max || value.chars().anyMatch(Character::isISOControl))
            throw new ClassroomException(400, label + "无效");
        return value.trim();
    }

    private static boolean samePayload(ObjectNode record, RegisterRequest request) {
        return request.oldCourseId().equals(record.path("oldCourseId").asText())
                && request.oldSceneId().equals(record.path("oldSceneId").asText())
                && request.newCourseId().equals(record.path("newCourseId").asText())
                && request.newSceneId().equals(record.path("newSceneId").asText())
                && request.reason().equals(record.path("reason").asText())
                && request.requestId().equals(record.path("requestId").asText());
    }

    private static long positiveId(JsonNode value, String field) {
        JsonNode id = value.path(field);
        if (!id.isIntegralNumber() || !id.canConvertToLong() || id.asLong() <= 0)
            throw new ClassroomException(422, "练习题库标识无效");
        return id.asLong();
    }

    private record Endpoint(String courseId, String sceneId, long paperId, int questionCount) {}
    private static ClassroomException conflict(String message) { return new ClassroomException(409, message); }
}
