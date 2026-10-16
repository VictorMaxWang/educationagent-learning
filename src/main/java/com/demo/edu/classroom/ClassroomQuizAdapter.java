package com.demo.edu.classroom;

import com.demo.edu.mapper.ClassroomCourseMapper;
import com.demo.entity.QuizPaperEntity;
import com.demo.entity.QuizQuestionEntity;
import com.demo.study.quiz.QuizService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import java.util.*;

/** educationagent 课堂题目映射到既有题库，判分仍交给 QuizService。 */
@Component
public class ClassroomQuizAdapter {
    private final ClassroomCourseMapper mapper;
    private final QuizService quizService;
    private final ObjectMapper json;
    public ClassroomQuizAdapter(ClassroomCourseMapper mapper, QuizService quizService, ObjectMapper json) {
        this.mapper = mapper; this.quizService = quizService; this.json = json;
    }

    /** 调用者必须与课程保存使用同一事务；已关联题库的页面不会重复建卷。 */
    void attach(ObjectNode course, ObjectNode scene) {
        if (!"quiz".equals(scene.path("type").asText()) || scene.has("quizId")) return;
        JsonNode questions = scene.path("content").path("questions");
        ClassroomDocumentValidator.questions(questions);
        QuizPaperEntity paper = new QuizPaperEntity();
        paper.setUserId(ClassroomCourseService.LEARNER_ID);
        paper.setTitle(course.path("title").asText() + " · " + scene.path("title").asText());
        if (paper.getTitle().length() > 255) paper.setTitle(paper.getTitle().substring(0, 255));
        paper.setSourceFileName("classroom:" + course.path("id").asText() + ":" + scene.path("id").asText());
        paper.setQuestionCount(questions.size());
        mapper.insertPaper(paper);
        if (paper.getId() == null) throw new IllegalStateException("题库未返回试卷 ID");
        int no = 0;
        for (JsonNode raw : questions) {
            ObjectNode question = (ObjectNode) raw;
            String type = question.path("type").asText();
            boolean choice = type.equals("single") || type.equals("multiple");
            ArrayNode normalizedOptions = json.createArrayNode();
            ArrayNode labels = json.createArrayNode();
            Map<String, String> mapping = new LinkedHashMap<>();
            int optionIndex = 0;
            for (JsonNode option : question.path("options")) {
                String letter = String.valueOf((char) ('A' + optionIndex++));
                mapping.put(option.path("value").asText(), letter);
                String label = option.path("label").asText();
                normalizedOptions.addObject().put("label", label).put("value", letter);
                labels.add(letter + ". " + label);
            }
            String answer = choice ? normalizeAnswerValues(question.path("answer"), mapping)
                    : type.equals("judge") && question.path("answer").isBoolean() ? (question.path("answer").asBoolean() ? "对" : "错")
                    : question.path("answer").asText("");
            QuizQuestionEntity q = new QuizQuestionEntity();
            q.setPaperId(paper.getId()); q.setQuestionNo(++no);
            q.setQType(switch (type) { case "multiple" -> "multi"; case "short_answer" -> "short"; default -> type; });
            q.setStem(question.path("question").asText()); q.setOptions(labels.toString());
            q.setAnswer(answer);
            String explanation = question.path("analysis").asText("");
            if (question.path("gradingPoints").isArray() && !question.path("gradingPoints").isEmpty()) {
                List<String> points = new ArrayList<>(); question.path("gradingPoints").forEach(point -> points.add(point.asText()));
                explanation += "\n评分要点：" + String.join("；", points);
            }
            q.setExplanation(explanation);
            q.setKnowledgePoint(course.path("topic").asText().substring(0, Math.min(128, course.path("topic").asText().length())));
            q.setHasAnswer(answer.isBlank() ? 0 : 1); mapper.insertQuestion(q);
            question.put("no", no); question.set("options", normalizedOptions); question.put("hasAnswer", !answer.isBlank());
            if (choice) {
                ArrayNode normalizedAnswer = json.createArrayNode();
                for (char letter : answer.toCharArray()) normalizedAnswer.add(String.valueOf(letter));
                question.set("answer", normalizedAnswer);
            } else if (type.equals("judge")) question.put("answer", answer);
            // DB 统一存文本，课件仍保留填空数值/简答文本原类型，避免数字零在导出时丢失。
        }
        scene.put("quizId", paper.getId());
    }

    static String normalizeAnswerValues(JsonNode answers, Map<String, String> mapping) {
        SortedSet<String> letters = new TreeSet<>();
        for (JsonNode answer : answers) {
            String letter = mapping.get(answer.asText());
            if (letter == null) throw new ClassroomException(422, "答案引用了无效选项");
            letters.add(letter);
        }
        return String.join("", letters);
    }

    QuizService.SubmitResult submit(ObjectNode scene, List<QuizService.AnswerSubmission> answers) {
        JsonNode questions = scene.path("content").path("questions");
        if (answers == null || answers.size() != questions.size()) throw new ClassroomException(400, "请提交本页所有题目，每题提交一次");
        Map<Integer, JsonNode> byNo = new HashMap<>();
        for (JsonNode q : questions) byNo.put(q.path("no").asInt(), q);
        Set<Integer> seen = new HashSet<>();
        List<QuizService.AnswerSubmission> normalized = new ArrayList<>();
        for (QuizService.AnswerSubmission answer : answers) {
            if (answer == null || !seen.add(answer.no()) || !byNo.containsKey(answer.no())) throw new ClassroomException(400, "题号重复或不属于本页");
            JsonNode q = byNo.get(answer.no());
            String type = q.path("type").asText();
            String value = answer.answer() == null ? "" : answer.answer().trim();
            if (value.isBlank()) throw new ClassroomException(400, "请完成每一道题后提交");
            if (type.equals("judge")) {
                if (!Set.of("对", "错").contains(value)) throw new ClassroomException(400, "判断题请选择正确或错误");
                normalized.add(new QuizService.AnswerSubmission(answer.no(), value)); continue;
            }
            if (type.equals("fill") || type.equals("short_answer")) {
                if (value.length() > (type.equals("fill") ? 2000 : 4000)) throw new ClassroomException(400, "答案过长，请精简后提交");
                normalized.add(new QuizService.AnswerSubmission(answer.no(), value)); continue;
            }
            value = value.toUpperCase(Locale.ROOT);
            if (!value.matches("[A-H,，、\\s]*")) throw new ClassroomException(400, "请选择题目提供的选项");
            String letters = value.replaceAll("[^A-H]", "");
            SortedSet<Character> unique = new TreeSet<>();
            for (char c : letters.toCharArray()) {
                if (c - 'A' >= q.path("options").size()) throw new ClassroomException(400, "答案包含不存在的选项");
                unique.add(c);
            }
            if ("single".equals(q.path("type").asText()) && unique.size() > 1) throw new ClassroomException(400, "单选题只能选择一个选项");
            StringBuilder canonical = new StringBuilder(); unique.forEach(canonical::append);
            normalized.add(new QuizService.AnswerSubmission(answer.no(), canonical.toString()));
        }
        QuizService.SubmitResult result = quizService.submit(ClassroomCourseService.LEARNER_ID, scene.path("quizId").asLong(), normalized);
        // 外层课程事务同时覆盖题库写入。未确定的评阅整组回滚，前端可使用原提交 ID 重试。
        if (result == null || result.results() == null || result.results().size() != questions.size()
                || result.results().stream().anyMatch(item -> item == null || item.correct() == null)
                || !result.results().stream().map(QuizService.ResultItem::no).collect(java.util.stream.Collectors.toSet()).equals(seen))
            throw new ClassroomException(502, "本组练习暂未完成评阅，答案已保留，请稍后重试");
        return result;
    }
}
