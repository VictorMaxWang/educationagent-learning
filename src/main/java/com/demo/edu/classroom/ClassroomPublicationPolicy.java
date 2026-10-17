package com.demo.edu.classroom;

import com.demo.edu.classroom.study.ClassroomStudyContext;
import com.demo.edu.classroom.study.ClassroomStudyStore;
import com.demo.edu.mapper.ClassroomCourseMapper;
import com.demo.entity.QuizPaperEntity;
import com.demo.mapper.QuizPaperMapper;
import com.demo.study.quiz.QuizMutationGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.text.Normalizer;
import java.time.Instant;
import java.util.*;

/**
 * 公开课程入口与已认可题目替换的增量规则，不修改课程原文、题库或历史答卷。
 * 维护操作只经内部端点进入；公开调用不能根据客户端标记决定题目是否已经有效。
 */
@Service
public class ClassroomPublicationPolicy {
    static final String PUBLICATION = "course_publication", PAPER = "course_paper_visibility";
    static final String CORRECTION = "quiz_content_replacement", RECEIPT = "course_publication_request";
    private final ClassroomCourseMapper courses;
    private final QuizPaperMapper papers;
    private final ClassroomStudyStore store;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    public ClassroomPublicationPolicy(ClassroomCourseMapper courses, QuizPaperMapper papers,
            ClassroomStudyStore store, ObjectMapper json, PlatformTransactionManager manager) {
        this.courses = courses; this.papers = papers; this.store = store; this.json = json;
        this.transactions = new TransactionTemplate(manager);
    }

    public record Change(String courseId, String expectedTitle, String action, String targetCourseId, String displayTitle) {}
    public record Correction(String oldCourseId, String oldSceneId, String oldQuestionId,
                             String newCourseId, String newSceneId, String newQuestionId) {}
    public record Batch(String requestId, List<Change> changes, List<Correction> corrections) {}
    public record Entry(String courseId, ObjectNode sceneMap) {}

    /** GET和导出可解析历史入口；写操作必须仍指向当前课程，避免把旧页面编辑误写到新内容。 */
    public Entry resolve(String requested) {
        String current = id(requested, 64); Set<String> seen = new HashSet<>();
        ObjectNode mapping = json.createObjectNode();
        for (int depth = 0; depth < 16; depth++) {
            if (!seen.add(current)) throw new ClassroomException(500, "课程入口配置异常");
            ObjectNode rule = store.find(PUBLICATION, current).orElse(null);
            if (rule == null || "publish".equals(rule.path("action").asText())) return new Entry(current, mapping);
            if ("archive".equals(rule.path("action").asText())) throw unavailable();
            if (!"alias".equals(rule.path("action").asText()) || !rule.path("sceneMap").isObject())
                throw new ClassroomException(500, "课程入口配置异常");
            ObjectNode nextMap = (ObjectNode)rule.path("sceneMap");
            if (mapping.isEmpty()) mapping.setAll(nextMap);
            else for (String key : fieldNames(mapping)) {
                JsonNode next = nextMap.get(mapping.path(key).asText());
                if (next == null) mapping.remove(key); else mapping.set(key, next.deepCopy());
            }
            current = id(rule.path("targetCourseId").asText(), 64);
        }
        throw new ClassroomException(500, "课程入口关联过长");
    }

    public void requireActive(String courseId) {
        ObjectNode rule = store.find(PUBLICATION, id(courseId, 64)).orElse(null);
        if (rule == null || "publish".equals(rule.path("action").asText())) return;
        if ("archive".equals(rule.path("action").asText())) throw unavailable();
        throw new ClassroomException(409, "课程内容已更新，请刷新后继续");
    }

    /** 列表一次读取所有发布规则，不能因过滤归档课程再引入逐课程查询。 */
    public List<ObjectNode> publicSummaries(List<ObjectNode> summaries) {
        Map<String, ObjectNode> rules = store.findMany(PUBLICATION,
                summaries.stream().map(c -> c.path("id").asText()).toList());
        List<ObjectNode> visible = new ArrayList<>();
        for (ObjectNode source : summaries) {
            ObjectNode rule = rules.get(source.path("id").asText());
            if (rule != null && !"publish".equals(rule.path("action").asText())) continue;
            ObjectNode copy = source.deepCopy(); applyTitle(copy, rule); visible.add(copy);
        }
        return visible;
    }

    public ObjectNode decorate(ObjectNode course) {
        applyTitle(course, store.find(PUBLICATION, course.path("id").asText()).orElse(null)); return course;
    }
    /** 导入和导出持有同一发布边界直到完成，避免认可规则刚提交后旧课包又被建成计分题。 */
    public synchronized <T> T withCurrentRules(java.util.function.Supplier<T> operation) { return operation.get(); }
    private static void applyTitle(ObjectNode course, ObjectNode rule) {
        if (rule != null && rule.path("displayTitle").isTextual()) course.put("title", rule.path("displayTitle").asText());
    }
    public boolean isPaperPublic(Long paperId) {
        if (paperId == null || paperId <= 0) return false;
        return store.find(PAPER, paperId.toString()).map(p -> p.path("public").asBoolean(false)).orElse(true);
    }
    public void requirePaperPublic(Long paperId) {
        if (!isPaperPublic(paperId)) throw new ClassroomException(409, "练习内容已更新，请从当前课堂继续");
    }
    public String publicPaperTitle(Long paperId, String fallback) {
        if (paperId == null) return fallback;
        return store.find(PAPER, paperId.toString()).map(p -> p.path("title").asText(fallback)).orElse(fallback);
    }

    /** 题面识别忽略UUID、答案及解析；删除来源字段或改变选项字母不能让已确认错题重新生效。 */
    public ObjectNode applyCorrections(ObjectNode original) {
        if (!original.path("scenes").isArray() || original.path("scenes").size() > 64) throw bad("课件页面数量无效");
        ObjectNode course = original.deepCopy();
        // 对已知来源沿用服务端的正常显示名；不对用户自定义标题做全局字符串替换。
        String sourceId = course.path("id").asText();
        if (sourceId.matches("[A-Za-z0-9_-]{1,64}")) applyTitle(course, store.find(PUBLICATION, sourceId).orElse(null));
        record Position(ArrayNode questions, int index, String fingerprint) {}
        List<Position> positions = new ArrayList<>(); Set<String> keys = new LinkedHashSet<>();
        for (JsonNode scene : course.path("scenes")) {
            if (!"quiz".equals(scene.path("type").asText()) || !scene.path("content").path("questions").isArray()) continue;
            ArrayNode questions = (ArrayNode)scene.path("content").path("questions");
            if (questions.size() > 10) throw bad("单页练习题目过多");
            for (int index = 0; index < questions.size(); index++) {
                JsonNode question = questions.get(index);
                if (!question.isObject()) throw bad("练习题目格式无效");
                String key = questionFingerprint(question); keys.add(key); positions.add(new Position(questions, index, key));
            }
        }
        Map<String, ObjectNode> rules = new HashMap<>(); List<String> ordered = new ArrayList<>(keys);
        for (int index = 0; index < ordered.size(); index += 200)
            rules.putAll(store.findMany(CORRECTION, ordered.subList(index, Math.min(index + 200, ordered.size()))));
        for (Position position : positions) {
                JsonNode question = position.questions().get(position.index());
                ObjectNode rule = rules.get(position.fingerprint());
                if (rule == null) continue;
                if (!rule.path("replacement").isObject()) throw new ClassroomException(500, "题目内容配置异常");
                ObjectNode replacement = ((ObjectNode)rule.path("replacement")).deepCopy();
                // 保留待导入文件的关联标识；后续正常导入会创建自己的新ID及试卷，绝不关联历史答卷。
                if (question.has("id")) replacement.set("id", question.path("id").deepCopy());
                if (question.has("no")) replacement.set("no", question.path("no").deepCopy());
                replacement.remove(List.of("review", "correctAnswer", "explanation"));
                position.questions().set(position.index(), replacement);
        }
        return course;
    }

    /** 内部维护使用同一请求号幂等重放；课程锁→试卷锁→短事务，全部校验完成后才提交规则。 */
    public synchronized ObjectNode apply(Batch batch) {
        if (batch == null || batch.changes() == null || batch.changes().isEmpty() || batch.changes().size() > 20
                || batch.corrections() != null && batch.corrections().size() > 100) throw bad("发布调整内容无效");
        String requestId = id(batch.requestId(), 160), signature = ClassroomStudyContext.hash(json.valueToTree(batch));
        ObjectNode replay = receipt(requestId, signature); if (replay != null) return replay;
        Set<String> identities = new HashSet<>(), changedIds = new HashSet<>();
        for (Change change : batch.changes()) {
            if (change == null || !changedIds.add(id(change.courseId(), 64))) throw bad("发布调整包含重复课程");
            identities.add(change.courseId());
            if (change.targetCourseId() != null) identities.add(id(change.targetCourseId(), 64));
        }
        for (Correction correction : Optional.ofNullable(batch.corrections()).orElse(List.of())) {
            if (correction == null) throw bad("题目替换内容无效");
            identities.add(id(correction.oldCourseId(), 64)); identities.add(id(correction.newCourseId(), 64));
        }
        return ClassroomCourseLocks.withCourses(identities, () -> {
            Map<String, ObjectNode> documents = new HashMap<>(); identities.forEach(k -> documents.put(k, rawCourse(k)));
            List<Long> paperIds = documents.values().stream().flatMap(c -> paperIds(c).stream()).distinct().toList();
            return QuizMutationGuard.withPapers(paperIds, () -> transactions.execute(tx -> {
                ObjectNode saved = receipt(requestId, signature); if (saved != null) return saved;
                Map<String, ObjectNode> planned = new HashMap<>();
                for (Change change : batch.changes()) {
                    ObjectNode document = documents.get(change.courseId());
                    if (!Objects.equals(change.expectedTitle(), document.path("title").asText())) throw conflict("课程名称已变化，请重新核对");
                    if (Set.of("outlining", "generating").contains(document.path("status").asText())) throw conflict("请等待课程生成结束再调整发布状态");
                    if (!Set.of("archive", "alias", "publish").contains(change.action() == null ? "" : change.action())) throw bad("发布动作无效");
                    ObjectNode rule = json.createObjectNode().put("courseId", change.courseId()).put("action", change.action())
                            .put("requestId", requestId).put("updatedAt", Instant.now().toString());
                    if (change.displayTitle() != null) rule.put("displayTitle", title(change.displayTitle()));
                    if ("alias".equals(change.action())) {
                        if (change.courseId().equals(change.targetCourseId()) || !documents.containsKey(change.targetCourseId())) throw bad("课程入口目标无效");
                        rule.put("targetCourseId", change.targetCourseId());
                        rule.set("sceneMap", sceneMapping(document, documents.get(change.targetCourseId())));
                    } else if (change.targetCourseId() != null) throw bad("该发布动作不接受目标课程");
                    planned.put(change.courseId(), rule);
                }
                for (String start : planned.keySet()) validateDestination(start, planned);
                Map<String, ObjectNode> corrections = prepareCorrections(batch.corrections(), documents, requestId);
                for (var entry : planned.entrySet()) {
                    ObjectNode document = documents.get(entry.getKey()); ObjectNode rule = entry.getValue();
                    store.save(PUBLICATION, entry.getKey(), entry.getKey(), rule);
                    for (JsonNode scene : document.path("scenes")) if (scene.path("quizId").isIntegralNumber()) {
                        long paperId = scene.path("quizId").asLong(); validatePaper(document, scene, paperId);
                        ObjectNode visibility = json.createObjectNode().put("courseId", entry.getKey())
                                .put("public", "publish".equals(rule.path("action").asText())).put("requestId", requestId);
                        if (rule.has("displayTitle")) visibility.put("title", rule.path("displayTitle").asText() + " · " + scene.path("title").asText());
                        store.save(PAPER, Long.toString(paperId), entry.getKey(), visibility);
                    }
                }
                corrections.forEach((key, value) -> store.save(CORRECTION, key, value.path("newCourseId").asText(), value));
                ObjectNode result = json.createObjectNode().put("requestId", requestId).put("signature", signature)
                        .put("applied", true).put("courseChanges", planned.size()).put("questionReplacements", corrections.size())
                        .put("createdAt", Instant.now().toString());
                if (!store.create(RECEIPT, requestId, batch.changes().getFirst().courseId(), result)) throw conflict("发布请求已存在，请重新查询");
                return result.deepCopy();
            }));
        });
    }

    private Map<String, ObjectNode> prepareCorrections(List<Correction> requested, Map<String, ObjectNode> documents, String requestId) {
        Map<String, ObjectNode> result = new LinkedHashMap<>();
        for (Correction change : Optional.ofNullable(requested).orElse(List.of())) {
            JsonNode old = question(documents.get(change.oldCourseId()), change.oldSceneId(), change.oldQuestionId());
            JsonNode next = question(documents.get(change.newCourseId()), change.newSceneId(), change.newQuestionId());
            if (!old.path("type").equals(next.path("type"))) throw bad("替换题须保留题型");
            ClassroomDocumentValidator.questions(json.createArrayNode().add(next.deepCopy()));
            String key = questionFingerprint(old);
            ObjectNode rule = json.valueToTree(change); rule.put("requestId", requestId); rule.set("replacement", next.deepCopy());
            ObjectNode existing = store.find(CORRECTION, key).orElse(null);
            if (existing != null && !existing.path("replacement").equals(next)) throw conflict("题面已有不同的认可内容，请先核对已有记录");
            if (result.putIfAbsent(key, rule) != null) throw bad("发布调整包含重复题面");
        }
        return result;
    }
    private void validateDestination(String start, Map<String, ObjectNode> planned) {
        Set<String> seen = new HashSet<>(); String current = start;
        for (int i = 0; i < 16; i++) {
            if (!seen.add(current)) throw conflict("课程入口不能形成循环");
            ObjectNode rule = planned.containsKey(current) ? planned.get(current) : store.find(PUBLICATION, current).orElse(null);
            if (rule == null || "publish".equals(rule.path("action").asText())) return;
            if ("archive".equals(rule.path("action").asText())) { if (!current.equals(start)) throw conflict("课程入口不能指向已撤下内容"); return; }
            current = id(rule.path("targetCourseId").asText(), 64);
        }
        throw conflict("课程入口关联过长");
    }
    private ObjectNode receipt(String key, String signature) {
        ObjectNode existing = store.find(RECEIPT, key).orElse(null);
        if (existing != null && !signature.equals(existing.path("signature").asText())) throw conflict("同一发布请求号不能用于不同内容");
        return existing;
    }
    private ObjectNode rawCourse(String courseId) {
        String value = courses.find(courseId); if (value == null) throw unavailable();
        try { JsonNode parsed = json.readTree(value); if (parsed instanceof ObjectNode object && courseId.equals(object.path("id").asText()) && object.path("scenes").isArray()) return object; }
        catch (Exception ignored) { /* 保留原文用于维护，不在错误响应中输出课程正文。 */ }
        throw new ClassroomException(500, "课程记录格式异常");
    }
    private void validatePaper(ObjectNode course, JsonNode scene, long paperId) {
        QuizPaperEntity paper = papers.selectById(paperId);
        if (paper == null || !"classroom".equals(paper.getFileType())
                || !("classroom:" + course.path("id").asText() + ":" + scene.path("id").asText()).equals(paper.getSourceFileName()))
            throw conflict("课程试卷归属已变化，请重新核对");
    }
    private static List<Long> paperIds(ObjectNode course) {
        List<Long> values = new ArrayList<>();
        for (JsonNode scene : course.path("scenes")) if (scene.path("quizId").isIntegralNumber() && scene.path("quizId").asLong() > 0) values.add(scene.path("quizId").asLong());
        return values;
    }
    private ObjectNode sceneMapping(ObjectNode old, ObjectNode target) {
        JsonNode before = old.path("scenes"), after = target.path("scenes");
        if (before.size() != after.size()) throw bad("课程页面数量不同，不能自动建立旧入口");
        ObjectNode mapping = json.createObjectNode(); Set<String> targets = new HashSet<>();
        for (int i = 0; i < before.size(); i++) {
            JsonNode a = before.get(i), b = after.get(i);
            if (!a.path("type").equals(b.path("type")) || mapping.has(a.path("id").asText()) || !targets.add(b.path("id").asText()))
                throw bad("课程页面无法逐一对应");
            mapping.put(id(a.path("id").asText(), 80), id(b.path("id").asText(), 80));
        }
        return mapping;
    }
    private JsonNode question(ObjectNode course, String sceneId, String questionId) {
        id(sceneId, 80); id(questionId, 160);
        for (JsonNode scene : course.path("scenes")) if (sceneId.equals(scene.path("id").asText()))
            for (JsonNode question : scene.path("content").path("questions")) if (questionId.equals(question.path("id").asText())) return question;
        throw bad("没有找到要核对的题目");
    }
    public String questionFingerprint(JsonNode question) {
        ObjectNode canonical = json.createObjectNode().put("type", question.path("type").asText()).put("question", normalize(question.path("question").asText()));
        List<String> labels = new ArrayList<>();
        for (JsonNode option : question.path("options")) labels.add(normalize(option.isObject() ? option.path("label").asText() : option.asText()));
        Collections.sort(labels); canonical.set("options", json.valueToTree(labels));
        return ClassroomStudyContext.hash(canonical);
    }
    private static String normalize(String value) { return Normalizer.normalize(value, Normalizer.Form.NFKC).replaceAll("\\s+", " ").trim(); }
    private static List<String> fieldNames(ObjectNode value) { List<String> result = new ArrayList<>(); value.fieldNames().forEachRemaining(result::add); return result; }
    private static String title(String value) { if (value.isBlank() || value.length() > 200 || value.chars().anyMatch(Character::isISOControl)) throw bad("显示名称无效"); return value.trim(); }
    private static String id(String value, int max) { if (value == null || value.isBlank() || value.length() > max || !value.matches("[A-Za-z0-9_-]+")) throw bad("标识无效"); return value; }
    private static ClassroomException bad(String message) { return new ClassroomException(400, message); }
    private static ClassroomException conflict(String message) { return new ClassroomException(409, message); }
    private static ClassroomException unavailable() { return new ClassroomException(404, "课程不存在或暂不可用"); }
}
