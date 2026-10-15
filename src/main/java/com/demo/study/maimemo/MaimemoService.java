package com.demo.study.maimemo;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.demo.entity.EnglishWordEntity;
import com.demo.mapper.EnglishWordMapper;
import com.demo.service.TtsService;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 英语学习服务（墨墨背单词联动）。
 * <p>
 * 词库：AI 按考纲生成 / 有道查词（LLM 兜底）/ 文本批量提取 / 本地 MySQL 存储 / 复习状态。
 * 听力：TTS 单词听写包 + AI 短文听力（DeepSeek 生成短文与理解题 + TTS 合成）。
 * 墨墨同步：配置了墨墨 API 才真正推送，否则仅本地词库。
 */
@Slf4j
@Service
public class MaimemoService {

    /** 文本提取时的停用词（跳过常见虚词） */
    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "the", "and", "or", "but", "of", "to", "in", "on", "at", "for", "with",
            "is", "are", "was", "were", "be", "been", "being", "it", "this", "that", "these",
            "those", "i", "you", "he", "she", "we", "they", "my", "your", "his", "her", "our",
            "their", "me", "him", "us", "them", "as", "by", "from", "so", "if", "then", "than",
            "which", "who", "what", "when", "where", "why", "how", "all", "any", "some", "not");

    /** 艾宾浩斯复习间隔（天），下标 = review_level，封顶 7 */
    private static final int[] EBINGHAUS = {1, 2, 4, 7, 15, 30, 60, 90};
    /** 一天的毫秒数 */
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    @Resource
    private EnglishWordMapper wordMapper;

    @Resource
    private OpenAiChatModel deepseekChatModel;

    @Resource
    private TtsService ttsService;

    @Resource
    private MaimemoConfigProperties config;

    @Resource
    private RestTemplate restTemplate;

    // ======================== 词库管理 ========================

    /** AI 按考纲生成词库并入库，自动同步开关开启则推墨墨 */
    public Map<String, Object> generate(String bookType, int count, String difficulty, boolean withExamples) {
        // 单次生成太多会让 LLM 输出超时/截断（50 个词+例句实测常失败），上限 100，内部再分批
        count = Math.max(1, Math.min(count, 100));
        System.out.println("[Maimemo] ===== AI 生成词库开始 ===== bookType=" + bookType + " count=" + count
                + " difficulty=" + difficulty + " withExamples=" + withExamples);
        log.info("📚 [Maimemo] 生成词库: bookType={}, count={}, difficulty={}", bookType, count, difficulty);
        List<Map<String, Object>> words = llmGenerateWords(bookType, count, difficulty, withExamples);
        System.out.println("[Maimemo] LLM 返回单词数: " + words.size());
        if (words.isEmpty()) {
            System.out.println("[Maimemo] ❌ LLM 返回空数组，生成失败");
            return Map.of("code", 500, "msg", "AI 生成失败，请重试");
        }
        int added = 0;
        for (Map<String, Object> w : words) {
            String word = str(w.get("word"));
            if (word == null) continue;
            if (findWord(word) != null) continue;
            EnglishWordEntity e = new EnglishWordEntity();
            e.setWord(word);
            e.setPhonetic(str(w.get("phonetic")));
            e.setMeaning(str(w.get("meaning")));
            e.setPartOfSpeech(str(w.get("partOfSpeech")));
            e.setExample(str(w.get("example")));
            e.setStatus("new");
            e.setBookType(bookType);
            e.setSource("ai");
            wordMapper.insert(e);
            added++;
        }
        System.out.println("[Maimemo] 入库完成: 生成 " + words.size() + " 个，新增 " + added + " 个");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("code", 200);
        r.put("msg", "✅ 已生成 " + words.size() + " 个单词（新增入库 " + added + "）");
        r.put("words", words);
        r.put("bookType", bookType);
        return r;
    }

    /** 词库列表（支持按 词库类型 / 状态 / 生词本 过滤） */
    public Map<String, Object> getWords(String bookType, String status, Integer notebook) {
        LambdaQueryWrapper<EnglishWordEntity> qw = new LambdaQueryWrapper<>();
        if (bookType != null && !bookType.isBlank()) qw.eq(EnglishWordEntity::getBookType, bookType);
        if (status != null && !status.isBlank()) qw.eq(EnglishWordEntity::getStatus, status);
        if (notebook != null) qw.eq(EnglishWordEntity::getInNotebook, notebook == 1 ? 1 : 0);
        qw.orderByDesc(EnglishWordEntity::getId);
        qw.last("LIMIT 500");
        List<EnglishWordEntity> all = wordMapper.selectList(qw);
        List<Map<String, Object>> words = new ArrayList<>();
        for (EnglishWordEntity e : all) words.add(toVo(e));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("code", 200);
        r.put("words", words);
        r.put("total", words.size());
        return r;
    }

    /** 文本导入词库：每行一个单词，或 "word|释义" / "word|释义|音标" */
    public Map<String, Object> importWords(String bookType, String content) {
        if (content == null || content.isBlank()) {
            return Map.of("code", 400, "msg", "导入内容不能为空");
        }
        int added = 0;
        int skipped = 0;
        String[] lines = content.split("\\r?\\n");
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            String[] parts = t.split("[|\\t]", 3);
            String word = parts[0].trim().toLowerCase();
            if (word.isEmpty()) continue;
            if (findWord(word) != null) {
                skipped++;
                continue;
            }
            EnglishWordEntity e = new EnglishWordEntity();
            e.setWord(word);
            e.setStatus("new");
            e.setBookType(bookType);
            e.setSource("import");
            if (parts.length > 1 && !parts[1].trim().isEmpty()) e.setMeaning(parts[1].trim());
            if (parts.length > 2 && !parts[2].trim().isEmpty()) e.setPhonetic(parts[2].trim());
            wordMapper.insert(e);
            added++;
        }
        return Map.of("code", 200,
                "msg", "✅ 导入完成：新增 " + added + " 个，跳过重复 " + skipped + " 个",
                "added", added, "skipped", skipped);
    }

    /** 加入/移出生词本（词不存在则自动创建） */
    public Map<String, Object> setNotebook(String word, boolean inNotebook) {
        if (word == null || word.isBlank()) return Map.of("code", 400, "msg", "请输入单词");
        EnglishWordEntity e = findWord(word.trim().toLowerCase());
        if (e == null) {
            e = new EnglishWordEntity();
            e.setWord(word.trim().toLowerCase());
            e.setStatus("new");
            e.setSource("local");
            e.setInNotebook(inNotebook ? 1 : 0);
            wordMapper.insert(e);
        } else {
            e.setInNotebook(inNotebook ? 1 : 0);
            wordMapper.updateById(e);
        }
        return Map.of("code", 200, "inNotebook", inNotebook);
    }

    /** 更新复习状态（熟练/模糊/遗忘） */
    public Map<String, Object> updateStatus(Long id, String status) {
        EnglishWordEntity e = wordMapper.selectById(id);
        if (e != null) {
            e.setStatus(status);
            wordMapper.updateById(e);
        }
        return Map.of("code", 200);
    }

    /** 批量加入词库（查词后一键入库），可选同步墨墨 */
    public Map<String, Object> addWords(List<String> words, boolean syncToMaimemo, String source) {
        int added = 0;
        for (String word : words) {
            if (word == null || word.isBlank()) continue;
            String w = word.trim().toLowerCase();
            if (findWord(w) != null) continue;
            EnglishWordEntity e = new EnglishWordEntity();
            e.setWord(w);
            e.setStatus("new");
            e.setSource(source == null ? "local" : source);
            wordMapper.insert(e);
            added++;
        }
        String msg = "✅ 已加入词库 " + added + " 个";
        if (syncToMaimemo && !words.isEmpty()) {
            Map<String, Object> sr = sync(null, words);
            msg += "（墨墨同步 " + sr.get("found") + "/" + sr.get("total") + "）";
        }
        return Map.of("code", 200, "msg", msg);
    }

    /** 今日待复习：艾宾浩斯到期词（next_review_at=0 或 <= now，且未熟练）；focus=weak 只拉难词 */
    public Map<String, Object> reviewToday(String focus) {
        long now = System.currentTimeMillis();
        LambdaQueryWrapper<EnglishWordEntity> qw = new LambdaQueryWrapper<>();
        qw.ne(EnglishWordEntity::getStatus, "mastered");
        if ("weak".equals(focus)) {
            // 难词强化：只刷 weak/forgotten
            qw.in(EnglishWordEntity::getStatus, "weak", "forgotten");
        } else {
            // 到期词：从未复习(next_review_at=0) 或 已到期
            qw.and(w -> w.eq(EnglishWordEntity::getNextReviewAt, 0)
                    .or().le(EnglishWordEntity::getNextReviewAt, now));
        }
        qw.last("LIMIT 30");
        List<EnglishWordEntity> list = wordMapper.selectList(qw);
        List<Map<String, Object>> words = new ArrayList<>();
        for (EnglishWordEntity e : list) {
            Map<String, Object> vo = toVo(e);
            vo.put("options", buildMcqOptions(e)); // 选择题 4 选 1 数据
            words.add(vo);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("code", 200);
        r.put("words", words);
        r.put("source", "local");
        r.put("focus", focus == null ? "" : focus);
        r.put("msg", words.isEmpty()
                ? "今天没有待复习的词，去「词库管理」生成/导入一批吧"
                : "今日待复习 " + words.size() + " 个（本地词库）");
        return r;
    }

    /** 复习判分：按艾宾浩斯间隔推进 / 归零，更新复习等级、下次复习时间与状态 */
    public Map<String, Object> reviewAnswer(Long wordId, boolean correct, boolean revisit) {
        EnglishWordEntity e = wordMapper.selectById(wordId);
        if (e == null) return Map.of("code", 400, "msg", "单词不存在");
        long now = System.currentTimeMillis();
        int level = e.getReviewLevel() == null ? 0 : e.getReviewLevel();
        e.setLastReviewedAt(now);
        if (!correct) {
            // 答错：等级归零，明天再复习
            e.setReviewLevel(0);
            e.setNextReviewAt(now + DAY_MS);
            e.setStatus("forgotten");
        } else if (revisit) {
            // 模糊：等级不变，明天再刷
            e.setNextReviewAt(now + DAY_MS);
            if (e.getStatus() == null || "new".equals(e.getStatus()) || "forgotten".equals(e.getStatus())) {
                e.setStatus("weak");
            }
        } else {
            // 答对：等级 +1，按艾宾浩斯间隔推进
            int nextLevel = Math.min(level + 1, EBINGHAUS.length - 1);
            e.setReviewLevel(nextLevel);
            e.setNextReviewAt(now + EBINGHAUS[nextLevel] * DAY_MS);
            if (nextLevel >= 5) {
                e.setStatus("mastered");
            } else if (e.getStatus() == null || "new".equals(e.getStatus()) || "forgotten".equals(e.getStatus())) {
                e.setStatus("weak");
            }
        }
        wordMapper.updateById(e);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("code", 200);
        r.put("wordId", wordId);
        r.put("reviewLevel", e.getReviewLevel());
        r.put("nextReviewAt", e.getNextReviewAt());
        r.put("status", e.getStatus());
        return r;
    }

    /** 选择题数据：正确释义 + 3 个随机其它词释义（打乱顺序），返回 options + answerIndex */
    private Map<String, Object> buildMcqOptions(EnglishWordEntity target) {
        List<String> meanings = new ArrayList<>();
        String correct = (target.getMeaning() == null || target.getMeaning().isBlank())
                ? target.getWord() : target.getMeaning();
        meanings.add(correct);
        List<EnglishWordEntity> others = wordMapper.selectList(
                new LambdaQueryWrapper<EnglishWordEntity>()
                        .ne(EnglishWordEntity::getWord, target.getWord())
                        .last("ORDER BY RAND() LIMIT 6"));
        for (EnglishWordEntity o : others) {
            if (o.getMeaning() == null || o.getMeaning().isBlank()) continue;
            if (o.getMeaning().equals(correct)) continue;
            meanings.add(o.getMeaning());
            if (meanings.size() >= 4) break;
        }
        Collections.shuffle(meanings);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("options", meanings);
        r.put("answerIndex", meanings.indexOf(correct));
        return r;
    }

    /** 复习统计：词库规模 + 等级分布 + 各时间窗到期数（供前端展示遗忘曲线排期） */
    public Map<String, Object> reviewStats() {
        List<EnglishWordEntity> all = wordMapper.selectList(null);
        long now = System.currentTimeMillis();
        long day = 86400000L;
        int total = all.size();
        int mastered = 0;
        int[] byLevel = new int[8];
        int[] due = new int[8];
        for (EnglishWordEntity e : all) {
            if ("mastered".equals(e.getStatus())) mastered++;
            int lv = e.getReviewLevel() == null ? 0 : Math.min(e.getReviewLevel(), 7);
            byLevel[lv]++;
            long nra = e.getNextReviewAt() == null ? 0 : e.getNextReviewAt();
            long diffDays = (nra - now) / day;
            int bucket;
            if (diffDays <= 0) bucket = 0;       // 今天（含从未复习）
            else if (diffDays <= 1) bucket = 1;  // 明天
            else if (diffDays <= 3) bucket = 2;  // 3 天内
            else if (diffDays <= 7) bucket = 3;  // 1 周内
            else if (diffDays <= 15) bucket = 4;
            else if (diffDays <= 30) bucket = 5;
            else if (diffDays <= 60) bucket = 6;
            else bucket = 7;                     // 更久
            due[bucket]++;
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("code", 200);
        r.put("total", total);
        r.put("mastered", mastered);
        r.put("byLevel", byLevel);
        r.put("due", due);
        r.put("dueLabels", List.of("今天", "明天", "3天内", "1周内", "15天内", "30天内", "60天内", "更久"));
        return r;
    }

    // ======================== 查词（有道 + LLM 兜底） ========================

    /** 单词查释义：LLM 生成（墨墨英语），含英/美音标 + 生词本标记 */
    public Map<String, Object> lookupWord(String word) {
        Map<String, Object> def = llmLookup(word);
        EnglishWordEntity exist = findWord(word);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("code", 200);
        r.put("word", word);
        r.put("phonetic", def == null ? "" : def.get("phonetic"));
        r.put("ukPhonetic", def == null ? "" : def.get("ukPhonetic"));
        r.put("usPhonetic", def == null ? "" : def.get("usPhonetic"));
        r.put("meaning", def == null ? "" : def.get("meaning"));
        r.put("partOfSpeech", def == null ? "" : def.get("partOfSpeech"));
        r.put("example", def == null ? "" : def.get("example"));
        r.put("inLibrary", exist != null);
        r.put("inNotebook", exist != null && exist.getInNotebook() != null && exist.getInNotebook() == 1);
        r.put("source", def == null ? "local" : def.get("source"));
        return r;
    }

    /** 文本批量提取生词 + 查释义 */
    public Map<String, Object> lookupText(String text, int limit) {
        List<String> tokens = extractWords(text, limit);
        List<Map<String, Object>> defs = llmBatchLookup(tokens);
        List<Map<String, Object>> words = new ArrayList<>();
        for (Map<String, Object> d : defs) {
            Map<String, Object> vo = new LinkedHashMap<>(d);
            String w = str(d.get("word"));
            EnglishWordEntity ex = w == null ? null : findWord(w);
            vo.put("inLibrary", ex != null);
            vo.put("inNotebook", ex != null && ex.getInNotebook() != null && ex.getInNotebook() == 1);
            words.add(vo);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("code", 200);
        r.put("words", words);
        r.put("msg", "提取到 " + words.size() + " 个生词");
        return r;
    }

    // ======================== 听力（TTS） ========================

    /** 单词听写包：每个词 发音 + 逐字母拼写 → 合成一段 MP3 */
    public Map<String, Object> listenDictation(int count) {
        List<EnglishWordEntity> words = wordMapper.selectList(
                new LambdaQueryWrapper<EnglishWordEntity>()
                        .ne(EnglishWordEntity::getStatus, "mastered")
                        .last("LIMIT " + Math.min(count, 20)));
        if (words.isEmpty()) {
            return Map.of("code", 400, "msg", "词库为空，先去「词库生成」生成一批词吧");
        }
        StringBuilder ttsText = new StringBuilder();
        for (EnglishWordEntity w : words) {
            ttsText.append(w.getWord()).append(". ");
            for (char c : w.getWord().toCharArray()) {
                if (Character.isLetter(c)) ttsText.append(Character.toUpperCase(c)).append(". ");
            }
            ttsText.append(" ");
        }
        byte[] mp3 = ttsService.synthesizeLong(ttsText.toString());
        if (mp3 == null) {
            return Map.of("code", 500, "msg", "TTS 合成失败（请检查腾讯云 TTS 配置）");
        }
        String audioUrl = saveAudio(mp3);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("code", 200);
        r.put("audioUrl", audioUrl);
        r.put("words", words.stream().map(EnglishWordEntity::getWord).toList());
        r.put("msg", "✅ 已生成 " + words.size() + " 词听写包");
        return r;
    }

    /** AI 短文听力：用复习词生成短文 + 理解题，TTS 合成音频 */
    public Map<String, Object> listenPassage(int count, boolean withQuestions) {
        List<EnglishWordEntity> words = wordMapper.selectList(
                new LambdaQueryWrapper<EnglishWordEntity>()
                        .ne(EnglishWordEntity::getStatus, "mastered")
                        .last("LIMIT " + Math.min(count, 15)));
        if (words.isEmpty()) {
            return Map.of("code", 400, "msg", "词库为空，先去「词库生成」生成一批词吧");
        }
        String wordStr = words.stream().map(EnglishWordEntity::getWord).toList().toString();
        String prompt = "请用这些英语单词编一段约 150 词的听力短文（对话或独白），并出 3 道听力理解单选题。"
                + "严格只输出 JSON 对象：{passage: 短文正文, questions: [{question, options: [\"A....\",\"B....\",\"C....\",\"D....\"], answer_index: 正确答案下标}]}。不要输出其他文字。\n单词：" + wordStr;
        JSONObject obj = llmJson(prompt);
        if (obj == null) {
            return Map.of("code", 500, "msg", "AI 短文生成失败");
        }
        String passage = obj.getString("passage");
        JSONArray qArr = obj.getJSONArray("questions");
        List<Map<String, Object>> questions = new ArrayList<>();
        if (qArr != null) {
            for (Object o : qArr) {
                JSONObject qo = (JSONObject) o;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("question", qo.getString("question"));
                m.put("options", qo.getJSONArray("options") == null ? List.of()
                        : qo.getJSONArray("options").toJavaList(String.class));
                m.put("answer_index", qo.getIntValue("answer_index"));
                questions.add(m);
            }
        }
        byte[] mp3 = ttsService.synthesizeLong(passage);
        if (mp3 == null) {
            return Map.of("code", 500, "msg", "TTS 合成失败（请检查腾讯云 TTS 配置）");
        }
        String audioUrl = saveAudio(mp3);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("code", 200);
        r.put("audioUrl", audioUrl);
        r.put("words", words.stream().map(EnglishWordEntity::getWord).toList());
        r.put("passage", passage);
        r.put("questions", questions);
        r.put("msg", "✅ 已生成短文听力（" + questions.size() + " 题）");
        return r;
    }

    // ======================== 语法专项（规则讲解 + 练习解析） ========================

    /** 语法点练习：LLM 生成规则讲解 + 5 道单选题（带答案解析） */
    public Map<String, Object> grammarQuiz(String topic) {
        String prompt = "你是资深英语语法老师。请针对语法点「" + topic + "」输出："
                + "1. rules：一段精炼的中文语法规则讲解，含要点和例句，可用 Markdown 小标题/列表；"
                + "2. questions：5 道单选题（由易到难），每题字段：question（题干，含填空）、options（4 个选项字符串数组）、"
                + "answer_index（正确选项下标）、explanation（中文解析，说明为什么选这个）。"
                + "严格只输出 JSON 对象：{rules: string, questions: [{question, options: [...], answer_index: number, explanation}]}。不要输出任何其他文字。";
        JSONObject obj = llmJson(prompt);
        if (obj == null) {
            return Map.of("code", 500, "msg", "语法讲解生成失败，请重试");
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("code", 200);
        r.put("topic", topic);
        r.put("rules", obj.getString("rules"));
        JSONArray qArr = obj.getJSONArray("questions");
        List<Map<String, Object>> questions = new ArrayList<>();
        if (qArr != null) {
            for (Object o : qArr) {
                JSONObject qo = (JSONObject) o;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("question", qo.getString("question"));
                m.put("options", qo.getJSONArray("options") == null ? List.of()
                        : qo.getJSONArray("options").toJavaList(String.class));
                m.put("answerIndex", qo.getIntValue("answer_index"));
                m.put("explanation", qo.getString("explanation"));
                questions.add(m);
            }
        }
        r.put("questions", questions);
        return r;
    }

    // ======================== 墨墨同步 / 配置 ========================

    /** 墨墨云词本：把单词加入云词本（墨墨开放 API 仅提供此接口，后续在墨墨 App 内手动加入学习） */
    public Map<String, Object> sync(String bookType, List<String> words) {
        System.out.println("[Maimemo] ===== 加入墨墨云词本 ===== words=" + words.size() + " bookType=" + bookType);
        if (config.getMaimemoApiUrl() == null || config.getMaimemoApiUrl().isBlank()
                || config.getMaimemoApiKey() == null || config.getMaimemoApiKey().isBlank()) {
            System.out.println("[Maimemo] ⚠️ 墨墨未配置（maimemo-api-url/key 为空），跳过同步");
            return Map.of("found", 0, "total", words.size(), "notFound", words.size());
        }
        if (words.isEmpty()) {
            return Map.of("found", 0, "total", 0, "notFound", 0);
        }
        try {
            String url = config.getMaimemoApiUrl() + "/word/add";
            JSONObject body = new JSONObject();
            body.put("apiKey", config.getMaimemoApiKey());
            body.put("bookType", bookType);
            body.put("words", words);
            System.out.println("[Maimemo] POST " + url);
            System.out.println("[Maimemo] 请求体: " + body.toJSONString());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(body.toJSONString(), headers);
            ResponseEntity<String> resp = restTemplate.postForEntity(url, entity, String.class);
            String respBody = resp.getBody();
            System.out.println("[Maimemo] 墨墨响应 HTTP " + resp.getStatusCodeValue() + ": "
                    + (respBody == null ? "空" : respBody.length() > 500 ? respBody.substring(0, 500) + "..." : respBody));

            JSONObject json = respBody == null ? null : JSON.parseObject(respBody);
            if (json != null && json.containsKey("found")) {
                return Map.of("found", json.getIntValue("found"), "total", json.getIntValue("total"),
                        "notFound", json.getIntValue("notFound"));
            }
            return Map.of("found", words.size(), "total", words.size(), "notFound", 0);
        } catch (Exception e) {
            System.out.println("[Maimemo] ❌ 墨墨同步异常: " + e.getMessage());
            log.error("墨墨同步失败", e);
            return Map.of("found", 0, "total", words.size(), "notFound", words.size(), "error", e.getMessage());
        }
    }

    public Map<String, Object> getConfig() {
        return Map.of("code", 200);
    }

    // ======================== 私有工具 ========================

    /** 单次 LLM 请求生成的单词数上限：超过易输出超时/截断，返回非法 JSON */
    private static final int GENERATE_CHUNK = 20;

    /** LLM 生成单词 JSON 数组：大数量拆成多批小请求再合并，单批失败重试一次 */
    private List<Map<String, Object>> llmGenerateWords(String bookType, int count, String difficulty, boolean withExamples) {
        String bookName = switch (bookType) {
            case "cet6" -> "六级";
            case "kaoyan" -> "考研英语一";
            case "ielts" -> "雅思";
            default -> "四级";
        };
        List<Map<String, Object>> all = new ArrayList<>();
        for (int done = 0; done < count; done += GENERATE_CHUNK) {
            int n = Math.min(GENERATE_CHUNK, count - done);
            List<Map<String, Object>> part = llmGenerateWordsChunk(bookName, n, difficulty, withExamples);
            if (part.isEmpty()) {
                part = llmGenerateWordsChunk(bookName, n, difficulty, withExamples); // 失败重试一次
            }
            if (!part.isEmpty()) all.addAll(part);
            System.out.println("[Maimemo] 分批进度: " + (done + n) + "/" + count + "，本批 " + part.size() + " 个");
        }
        return all;
    }

    /** 单批 LLM 生成（≤ GENERATE_CHUNK 个） */
    private List<Map<String, Object>> llmGenerateWordsChunk(String bookName, int count, String difficulty, boolean withExamples) {
        String prompt = "你是英语词汇专家。请生成 " + count + " 个" + bookName + "考纲范围内、" + difficulty + "难度的英语单词。"
                + "严格只输出 JSON 数组，每个元素字段：word（单词）、phonetic（音标）、partOfSpeech（词性）、meaning（中文释义）"
                + (withExamples ? "、example（含该词的英文例句）" : "") + "。不要输出任何其他文字。";
        System.out.println("[Maimemo] LLM prompt 前200字: " + (prompt.length() > 200 ? prompt.substring(0, 200) + "..." : prompt));
        try {
            ChatResponse resp = deepseekChatModel.chat(ChatRequest.builder()
                    .messages(List.of(SystemMessage.from("你是英语词典助手，严格按 JSON 数组输出。"), UserMessage.from(prompt)))
                    .build());
            String rawText = resp.aiMessage().text();
            System.out.println("[Maimemo] LLM 原始返回前300字: "
                    + (rawText.length() > 300 ? rawText.substring(0, 300) + "..." : rawText));
            JSONArray arr = JSON.parseArray(extractJsonArray(rawText));
            System.out.println("[Maimemo] LLM 解析出的 JSON 元素数: " + (arr == null ? 0 : arr.size()));
            List<Map<String, Object>> out = new ArrayList<>();
            if (arr != null) {
                for (Object o : arr) {
                    JSONObject obj = (JSONObject) o;
                    if (obj == null) continue;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("word", obj.getString("word"));
                    m.put("phonetic", obj.getString("phonetic"));
                    m.put("partOfSpeech", obj.getString("partOfSpeech"));
                    m.put("meaning", obj.getString("meaning"));
                    m.put("example", obj.getString("example"));
                    out.add(m);
                }
            }
            return out;
        } catch (Exception e) {
            log.error("LLM 生成单词失败", e);
            return List.of();
        }
    }

    /** LLM 单词查释义（含英/美音标） */
    private Map<String, Object> llmLookup(String word) {
        String prompt = "请给出单词 " + word + " 的释义。只输出 JSON 对象：{phonetic, ukPhonetic, usPhonetic, partOfSpeech, meaning, example}，"
                + "phonetic 为常见音标、ukPhonetic 为英式音标、usPhonetic 为美式音标，不要输出其他文字。";
        JSONObject obj = llmJson(prompt);
        if (obj == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("phonetic", obj.getString("phonetic"));
        m.put("ukPhonetic", obj.getString("ukPhonetic"));
        m.put("usPhonetic", obj.getString("usPhonetic"));
        m.put("partOfSpeech", obj.getString("partOfSpeech"));
        m.put("meaning", obj.getString("meaning"));
        m.put("example", obj.getString("example"));
        m.put("source", "ai");
        return m;
    }

    /** LLM 批量查词释义（含英/美音标） */
    private List<Map<String, Object>> llmBatchLookup(List<String> words) {
        if (words.isEmpty()) return List.of();
        String prompt = "请给下列英语单词逐个给出释义。严格只输出 JSON 数组，每个元素字段：word（原词）、phonetic（音标）、ukPhonetic（英式音标）、usPhonetic（美式音标）、partOfSpeech（词性）、meaning（中文释义）、example（含该词的英文例句）。顺序与输入一致，不要输出其他文字。\n单词列表：" + String.join(", ", words);
        try {
            ChatResponse resp = deepseekChatModel.chat(ChatRequest.builder()
                    .messages(List.of(SystemMessage.from("你是英语词典助手，严格按 JSON 数组输出。"), UserMessage.from(prompt)))
                    .build());
            JSONArray arr = JSON.parseArray(extractJsonArray(resp.aiMessage().text()));
            List<Map<String, Object>> out = new ArrayList<>();
            if (arr != null) {
                for (Object o : arr) {
                    JSONObject obj = (JSONObject) o;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("word", obj.getString("word"));
                    m.put("phonetic", obj.getString("phonetic"));
                    m.put("ukPhonetic", obj.getString("ukPhonetic"));
                    m.put("usPhonetic", obj.getString("usPhonetic"));
                    m.put("partOfSpeech", obj.getString("partOfSpeech"));
                    m.put("meaning", obj.getString("meaning"));
                    m.put("example", obj.getString("example"));
                    m.put("source", "ai");
                    out.add(m);
                }
            }
            return out;
        } catch (Exception e) {
            log.error("LLM 批量查词失败", e);
            return List.of();
        }
    }

    /** 通用 LLM 单对象输出 */
    private JSONObject llmJson(String prompt) {
        try {
            ChatResponse resp = deepseekChatModel.chat(ChatRequest.builder()
                    .messages(List.of(SystemMessage.from("你是英语助手，严格按 JSON 输出。"), UserMessage.from(prompt)))
                    .build());
            return JSON.parseObject(extractJsonObject(resp.aiMessage().text()));
        } catch (Exception e) {
            log.error("LLM JSON 调用失败", e);
            return null;
        }
    }

    /** 从文本提取英文单词（去停用词、去重） */
    private List<String> extractWords(String text, int limit) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (text == null) return out;
        Matcher m = Pattern.compile("[A-Za-z]+(?:['-][A-Za-z]+)*").matcher(text.toLowerCase());
        while (m.find() && seen.size() < limit) {
            String w = m.group();
            if (w.length() < 3 || STOPWORDS.contains(w)) continue;
            seen.add(w);
        }
        return new ArrayList<>(seen);
    }

    private EnglishWordEntity findWord(String word) {
        if (word == null) return null;
        List<EnglishWordEntity> list = wordMapper.selectList(
                new LambdaQueryWrapper<EnglishWordEntity>().eq(EnglishWordEntity::getWord, word.trim().toLowerCase()));
        return list.isEmpty() ? null : list.get(0);
    }

    private Map<String, Object> toVo(EnglishWordEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("word", e.getWord());
        m.put("phonetic", e.getPhonetic());
        m.put("meaning", e.getMeaning());
        m.put("partOfSpeech", e.getPartOfSpeech());
        m.put("example", e.getExample());
        m.put("status", e.getStatus());
        m.put("bookType", e.getBookType());
        m.put("source", e.getSource());
        m.put("inNotebook", e.getInNotebook());
        m.put("reviewLevel", e.getReviewLevel());
        m.put("nextReviewAt", e.getNextReviewAt());
        m.put("lastReviewedAt", e.getLastReviewedAt());
        return m;
    }

    /** 保存听力音频到磁盘，返回下载路径 */
    private String saveAudio(byte[] mp3) {
        try {
            Path dir = Paths.get(config.getAudioDir());
            Files.createDirectories(dir);
            String name = UUID.randomUUID().toString().replace("-", "") + ".mp3";
            Files.write(dir.resolve(name), mp3);
            return "/api/maimemo/audio/" + name;
        } catch (IOException e) {
            log.error("保存听力音频失败", e);
            return null;
        }
    }

    private String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private String extractJsonArray(String text) {
        if (text == null) return null;
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        return (start >= 0 && end > start) ? text.substring(start, end + 1) : text;
    }

    private String extractJsonObject(String text) {
        if (text == null) return null;
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return (start >= 0 && end > start) ? text.substring(start, end + 1) : text;
    }
}
