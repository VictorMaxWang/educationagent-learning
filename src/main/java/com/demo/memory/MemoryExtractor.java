package com.demo.memory;

import com.demo.config.AiChatProperties;
import jakarta.annotation.Resource;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从用户消息里抽取值得长期记住的事实。
 *
 * <h3>两层：规则优先，LLM 兜底</h3>
 * 规则层覆盖高频的自我陈述：姓名 / 身份 / 年龄 / 联系方式 / 居住地 / 工作地 / 宠物 /
 * 喜好与厌恶 / 日常习惯 / 计划意愿。零成本、零延迟、零幻觉。
 * LLM 层默认<b>关</b>——它要为每一轮对话增加一次模型调用，而绝大多数轮次其实没东西可记，
 * 这类额外请求与主对话共用 {@code ai.chat.model}，因此必须控制调用频次与 token 成本。
 * 需要更高召回时<b>优先加正则</b>，不要打开 LLM。
 *
 * <h3>与方案书正则有意的两处差异</h3>
 * <ol>
 *   <li><b>去掉了裸的「我在」。</b>方案书写的是 {@code (?:我住在|我在)(...)}，
 *       但「我在想一个问题」「我在开会」都会被它抓成"用户住想一个问题"。
 *       改成只认「我住在 / 我家在 / 我来自 / 我老家在」这类明确的居住表述——
 *       少抽一条，好过把错误信息写进长期记忆（写进去之后每次对话都会被注入，
 *       而且要等 90 天才自动清除）。</li>
 *   <li>抽取出的文字做了长度与标点清洗，避免把「的用户」这类噪声带进记忆。</li>
 * </ol>
 */
@Slf4j
@Service
public class MemoryExtractor {

    @Resource
    private AiChatProperties aiChat;

    /** 姓名：我叫张三 / 我叫欧阳娜娜。 */
    private static final Pattern P_NAME =
            Pattern.compile("我叫([\\u4e00-\\u9fa5]{2,4})");

    /**
     * 居住地：我住在杭州 / 我家在四川 / 我来自东北。
     * <p>刻意<b>不</b>收裸的「我在」——见类注释。
     */
    private static final Pattern P_LOCATION =
            Pattern.compile("我(?:住在|家在|来自|老家在)([\\u4e00-\\u9fa5]{2,10})");

    /** 喜欢：我喜欢跑步 / 我爱吃辣。放在"讨厌"之后匹配，避免"我不喜欢"被它先吃掉。 */
    private static final Pattern P_LIKE =
            Pattern.compile("我(?:喜欢|爱)([^，。！？,.!?\\s]{2,20})");

    /** 讨厌：我讨厌加班 / 我不喜欢吃辣 / 我不爱运动。 */
    private static final Pattern P_DISLIKE =
            Pattern.compile("我(?:讨厌|不喜欢|不爱)([^，。！？,.!?\\s]{2,20})");

    // ======================== 扩展规则（2026-10 补） ========================
    // 起因：只有上面 4 条时，"我是女生""我 30 岁""我养了只猫"这类最常见的自我陈述
    // 一条都抽不到，用户会以为记忆功能坏了。
    //
    // 选型原则仍是"宁可漏、不可错"——每一条都额外收紧了边界：
    // 要么要求数字/显式词（年龄、联系方式），要么要求后接标点（身份），
    // 要么用排除表挡住歧义（"我是说""我想问一下"）。

    /**
     * 身份：我是女生 / 我是老师 / 我是软件工程师。
     *
     * <p>必须紧跟标点或句尾。否则「我是女生我喜欢咖啡」会被贪婪匹配成"女生我喜欢"——
     * 用后视边界挡掉，代价是这种没说标点的句子抽不到，但比写错强。
     */
    private static final Pattern P_IDENTITY =
            Pattern.compile("我是([\\u4e00-\\u9fa5A-Za-z]{2,6})(?=[，。！？,.!?\\s]|$)");

    /**
     * 「我是」后面接这些词就不是身份陈述，而是别的意思——
     * 我是<u>说</u> / 我是<u>来</u>问的 / 我是<u>为了</u>…。命中即丢弃。
     */
    private static final java.util.Set<String> IDENTITY_STOP_PREFIX =
            java.util.Set.of("说", "来", "问", "想", "要", "为了", "因为", "真的", "不", "在", "有", "被", "从");

    /** 年龄：我 30 岁 / 我今年 30 岁。数字 + "岁" 几乎没有歧义。 */
    private static final Pattern P_AGE =
            Pattern.compile("我(?:今年)?\\s*(\\d{1,3})\\s*岁");

    /** 联系方式/生日/地址：我的手机是… / 我的生日是… 。要求显式"我的X是"。 */
    private static final Pattern P_CONTACT =
            Pattern.compile("我的(电话|手机|手机号|邮箱|微信|生日|地址|名字)(?:是|：|:)\\s*([^\\s，。！？,.!?]{2,30})");

    /** 工作 / 就读地：我在杭州工作 / 我在北大读书。 */
    private static final Pattern P_WORKPLACE =
            Pattern.compile("我在([\\u4e00-\\u9fa5]{2,10})(?:工作|上班|读书|上学|念书|读大学|读研)");

    /** 养的宠物：我养了一只猫 / 我养了条狗。 */
    private static final Pattern P_PET =
            Pattern.compile("我养了?(?:一|两|三|四|五|\\d)?\\s*(?:只|条)?([\\u4e00-\\u9fa5]{1,4})");

    /** 日常习惯：我每天跑步 / 我经常加班。 */
    private static final Pattern P_HABIT =
            Pattern.compile("我(?:每天|每周|每月|经常|通常|一般)([^，。！？,.!?\\s]{2,15})");

    /**
     * 计划意愿：我想学 Rust / 我打算考研 / 我准备换工作。
     * <p>排除表挡掉「我想问一下」「我准备说的是」这类非计划表述。
     */
    private static final Pattern P_PLAN =
            Pattern.compile("我(想|打算|准备)([^，。！？,.!?\\s]{2,15})");

    /** 「想/打算/准备」后面接这些词，说明不是在讲计划。 */
    private static final java.util.Set<String> PLAN_STOP_PREFIX =
            java.util.Set.of("问", "说", "知道", "确认", "了解", "试试", "考虑", "请教", "麻烦", "再想");

    private static final String EXTRACT_PROMPT = """
            你是对话记忆提取器。从用户的话里提取值得长期记住的稳定事实。
            只输出 JSON 数组，不要解释。每项格式：
            {"content":"记忆正文（不超过40字，第三人称陈述，如：用户叫张三）",
             "type":"BASIC|EPISODIC|SEMANTIC|WORKING","importance":1到10的整数}
             type 取值：BASIC=姓名住址职业等稳定属性；EPISODIC=某次具体事件；
             SEMANTIC=偏好习惯观点；WORKING=当前会话临时状态。
             importance：越稳定越重要（姓名9~10，住址7，偏好5~6，一次性事件2~4）。
             ｜没有值得长期记住的内容就输出 []
            用户的话：
            """;

    private final MemoryProperties properties;
    private final RestTemplate restTemplate;

    public MemoryExtractor(MemoryProperties properties,
                           @Qualifier("llmRestTemplate") RestTemplate restTemplate) {
        this.properties = properties;
        this.restTemplate = restTemplate;
    }

    /**
     * 抽取候选事实：规则 → 可选 LLM → 埋点全量捕获，已做条数上限截断。
     *
     * <p>三层是<b>互斥递进</b>的：上一层抽到东西，下一层就不跑。
     * 所以常见的自我陈述永远走规则（干净、零成本），
     * 只有"规则也认不出、LLM 也没开"的句子才会落到原句捕获。
     */
    public List<MemoryRecord> extract(String userId, String userMessage) {
        List<MemoryRecord> out = new ArrayList<>();
        if (userMessage == null || userMessage.isBlank()) return out;

        if (properties.getExtractor().isRuleEnabled()) {
            out.addAll(extractByRules(userId, userMessage));
        }
        if (properties.getExtractor().isLlmEnabled() && out.isEmpty()) {
            // 规则已经抽到了就不再调 LLM：省一次调用，也避免两路结果互相打架
            out.addAll(extractByLlm(userId, userMessage));
        }
        if (out.isEmpty() && properties.getExtractor().isCaptureAll()) {
            // 埋点式全量捕获：抽不出结构化事实，就把原句本身记下来
            MemoryRecord captured = captureRaw(userId, userMessage);
            if (captured != null) {
                out.add(captured);
            }
        }

        int max = Math.max(1, properties.getExtractor().getMaxFacts());
        if (out.size() > max) {
            return new ArrayList<>(out.subList(0, max));
        }
        return out;
    }

    // ======================== 埋点全量捕获 ========================

    /** 原句入库的正文上限，防止用户粘一大段文字把记忆库撑爆。 */
    private static final int MAX_CAPTURE_CHARS = 200;

    /**
     * 全量捕获：把原句本身存成一条记忆。
     *
     * <h3>为什么敢"什么都存"</h3>
     * 因为后面有两道闸门兜着，成本可控：
     * <ol>
     *   <li><b>不进提示词</b>——重要度固定 {@value MemoryProperties.Extractor#CAPTURE_IMPORTANCE}，
     *       低于 {@code memory.integration.min-importance}。所以它只在脑图上可见，
     *       不会稀释 LLM 每轮看到的上下文；</li>
     *   <li><b>优先被回收</b>——类型是 {@code EPISODIC}，走更短的分代衰减
     *       （{@code memory.decay.episodic-*}），比 BASIC/SEMANTIC 死得早。</li>
     * </ol>
     *
     * <p>两条过滤挡掉纯噪声：太短的（"嗯""好的"）和切不出任何内容词的。
     *
     * @return 捕获到的记忆；不满足条件时返回 null（表示这条消息不值得记）
     */
    private MemoryRecord captureRaw(String userId, String message) {
        String text = message.trim();
        if (text.length() < Math.max(1, properties.getExtractor().getCaptureMinLength())) {
            return null;
        }

        String keywords = KeywordExtractor.extractJoined(
                text, properties.getExtractor().getKeywordsMax());
        if (keywords.isEmpty()) {
            return null;   // 整句都是功能字，说明没有内容可记
        }

        long now = System.currentTimeMillis();
        return MemoryRecord.builder()
                .userId(userId)
                .content(text.length() > MAX_CAPTURE_CHARS ? text.substring(0, MAX_CAPTURE_CHARS) : text)
                .keywords(keywords)
                .type(MemoryType.EPISODIC)
                .importance(MemoryProperties.Extractor.CAPTURE_IMPORTANCE)
                .createdAt(now)
                .lastAccessedAt(now)
                .accessCount(0)
                .build();
    }

    // ======================== 规则层 ========================

    private List<MemoryRecord> extractByRules(String userId, String message) {
        List<MemoryRecord> out = new ArrayList<>();
        long now = System.currentTimeMillis();

        Matcher name = P_NAME.matcher(message);
        if (name.find()) {
            out.add(rule(userId, "用户叫" + clean(name.group(1)), MemoryType.BASIC, 9, now));
        }

        // 身份："我是女生" —— 最常见的自我陈述之一，且直接决定 ④ 段注入时的称呼/口吻
        Matcher identity = P_IDENTITY.matcher(message);
        if (identity.find()) {
            String v = clean(identity.group(1));
            if (!isStopPrefix(v, IDENTITY_STOP_PREFIX)) {
                out.add(rule(userId, "用户是" + v, MemoryType.BASIC, 8, now));
            }
        }

        Matcher age = P_AGE.matcher(message);
        if (age.find()) {
            out.add(rule(userId, "用户今年 " + age.group(1) + " 岁", MemoryType.BASIC, 8, now));
        }

        Matcher contact = P_CONTACT.matcher(message);
        if (contact.find()) {
            out.add(rule(userId, "用户的" + contact.group(1) + "是" + contact.group(2),
                    MemoryType.BASIC, 8, now));
        }

        Matcher loc = P_LOCATION.matcher(message);
        if (loc.find()) {
            out.add(rule(userId, "用户住在" + clean(loc.group(1)), MemoryType.BASIC, 7, now));
        }

        Matcher workplace = P_WORKPLACE.matcher(message);
        if (workplace.find()) {
            out.add(rule(userId, "用户在" + clean(workplace.group(1)) + "工作/学习",
                    MemoryType.BASIC, 7, now));
        }

        Matcher pet = P_PET.matcher(message);
        if (pet.find()) {
            String v = clean(pet.group(1));
            // 不设长度下限：宠物名/种类常常就是一个字（猫、狗、鸟、鱼）
            if (!v.isEmpty() && !isStopPrefix(v, java.util.Set.of("的", "不"))) {
                out.add(rule(userId, "用户养了" + v, MemoryType.SEMANTIC, 6, now));
            }
        }

        // 喜欢与不喜欢**各自独立判断，不是 if/else**。
        //
        // 原实现是 if(dislike) else if(like)，前提是"一条消息只说一个偏好"。
        // 但一条消息完全可以同时说两件（"我喜欢喝咖啡 我不喜欢吃香菜"），
        // 那样 dislike 一命中，like 就整个被跳过——实测漏掉了「用户喜欢喝咖啡」。
        // 两个正则本来就不会互相干扰：P_LIKE 要求"我"紧跟"喜欢/爱"，
        // 而"我不喜欢"的"我"后面是"不"，匹配不上。
        Matcher dislike = P_DISLIKE.matcher(message);
        if (dislike.find()) {
            out.add(rule(userId, "用户不喜欢" + clean(dislike.group(1)), MemoryType.SEMANTIC, 6, now));
        }
        {
            Matcher like = P_LIKE.matcher(message);
            if (like.find()) {
                out.add(rule(userId, "用户喜欢" + clean(like.group(1)), MemoryType.SEMANTIC, 6, now));
            }
        }

        Matcher habit = P_HABIT.matcher(message);
        if (habit.find()) {
            out.add(rule(userId, "用户经常" + clean(habit.group(1)), MemoryType.SEMANTIC, 5, now));
        }

        Matcher plan = P_PLAN.matcher(message);
        if (plan.find()) {
            String v = clean(plan.group(2));
            if (!isStopPrefix(v, PLAN_STOP_PREFIX)) {
                // 保留原动词：「打算考研」和「想考研」的确定程度不一样，不该被抹平
                out.add(rule(userId, "用户" + plan.group(1) + v, MemoryType.SEMANTIC, 5, now));
            }
        }

        return out;
    }

    /** 捕获文本是否以排除词开头——用于挡掉"我是说""我想问一下"这类歧义表述。 */
    private static boolean isStopPrefix(String value, java.util.Set<String> stopPrefixes) {
        if (value == null || value.isEmpty()) return true;
        for (String p : stopPrefixes) {
            if (value.startsWith(p)) return true;
        }
        return false;
    }

    private MemoryRecord rule(String userId, String content, MemoryType type, int importance, long now) {
        return MemoryRecord.builder()
                .userId(userId)
                .content(content)
                .type(type)
                .importance(importance)
                .createdAt(now)
                .lastAccessedAt(now)
                .accessCount(0)
                .build();
    }

    /** 清洗捕获组：去掉尾部语气词与残留标点。 */
    private static String clean(String s) {
        if (s == null) return "";
        return s.replaceAll("[的呢了吧啊呀哦嘛\\s]+$", "").trim();
    }

    // ======================== LLM 兜底层 ========================

    private List<MemoryRecord> extractByLlm(String userId, String message) {
        if (aiChat == null || !aiChat.hasApiKey()) return List.of();
        try {
            JSONArray messages = new JSONArray();
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", EXTRACT_PROMPT);
            messages.add(sys);
            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", message);
            messages.add(user);

            JSONObject body = new JSONObject();
            aiChat.configureRequest(body);
            body.put("temperature", 0.0);
            body.put("max_tokens", 512);
            body.put("messages", messages);

            HttpHeaders headers = new HttpHeaders();
            headers.add("Authorization", "Bearer " + aiChat.getApiKey());
            headers.add("Content-Type", "application/json");

            ResponseEntity<String> resp = restTemplate.postForEntity(
                    aiChat.getCompletionsUrl(), new HttpEntity<>(body.toJSONString(), headers), String.class);
            JSONObject result = JSON.parseObject(resp.getBody());
            if (result == null) return List.of();
            String content = result.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content");
            return parseLlmFacts(userId, content);
        } catch (Exception e) {
            // 抽取失败只是少记一条，绝不能影响主对话
            log.warn("【记忆库-Extractor】LLM 抽取失败（不影响主流程）：{}", e.toString());
            return List.of();
        }
    }

    private List<MemoryRecord> parseLlmFacts(String userId, String content) {
        if (content == null || content.isBlank()) return List.of();
        JSONArray arr = null;
        try {
            arr = JSON.parseArray(content);
        } catch (Exception e) {
            // 模型偶尔会裹一层 ```json 或前后加解释，退化成"抠出第一个 [ ... ]"
            int s = content.indexOf('[');
            int t = content.lastIndexOf(']');
            if (s >= 0 && t > s) {
                try {
                    arr = JSON.parseArray(content.substring(s, t + 1));
                } catch (Exception ignored) {
                    // 忽略
                }
            }
        }
        if (arr == null) {
            log.debug("【记忆库-Extractor】LLM 返回无法解析：{}", content);
            return List.of();
        }

        List<MemoryRecord> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int i = 0; i < arr.size(); i++) {
            Object item = arr.get(i);
            String text = null;
            MemoryType type = MemoryType.BASIC;
            int importance = 5;
            if (item instanceof String s) {
                text = s;
            } else if (item instanceof JSONObject o) {
                text = o.getString("content");
                type = MemoryType.parse(o.getString("type"));
                Integer imp = o.getInteger("importance");
                if (imp != null) importance = Math.max(1, Math.min(10, imp));
            }
            if (text == null || text.isBlank()) continue;
            out.add(rule(userId, text.trim(), type, importance, now));
        }
        return out;
    }
}
