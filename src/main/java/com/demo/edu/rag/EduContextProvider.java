package com.demo.edu.rag;

import com.demo.chroma.base.ChromaClient;
import com.demo.chroma.doc.DocKnowledgeAssistant;
import com.demo.chroma.doc.DocKnowledgeRegistry;
import com.demo.edu.mapper.EduUserClassMapper;
import com.demo.edu.teacher.EduTopicRecorder;
import com.demo.util.VoiceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 把生命安全与健康教育 / 青春期性教育知识库接进主对话（{@code AgentExecutor.buildMessages}）。
 *
 * <h3>为什么需要专门的门控</h3>
 * 这两个库是<b>独立 REST</b>（{@code /api/desktop/lifesafety/search}）也能用的，
 * 但那样学生只能在"知识中心"页面里问，产品故事就变成了"又一个文档检索站"。
 * 接进对话链路后学生可以直接问，代价是每条消息都会多打几次 Chroma + 嵌入接口——
 * 对"今天天气怎么样"做这件事纯属浪费，还会把无关材料塞进上下文污染回答。
 *
 * <h3>门控规则（跳过词优先于强制词）</h3>
 * 这个顺序是有意的，抄 {@code LawContextProvider} 踩过的坑：若按"强制词 → 跳过词"，
 * 跳过词永远轮不到（强制词命中就返回了），等于死代码。
 *
 * <h3>词表为什么刻意收紧</h3>
 * {@code 难过} / {@code 悲伤} / {@code 怀念} / {@code 发育} 这些词都<b>没有</b>进表：
 * <ul>
 *   <li>"今天考试没考好，很难过"会因此被塞进三段哀伤陪伴材料——把一次普通的情绪低落
 *       当成丧亲来回应，比不回应更糟；</li>
 *   <li>"我家狗狗发育不良"会命中"发育"——宠物域的问题被塞进青春期性教育材料。</li>
 * </ul>
 * 宁可漏（学生显式问时仍能从知识中心页面问到），也不要错——错一次就是在未成年人面前
 * 把话题引偏。
 */
@Slf4j
@Component
public class EduContextProvider {

    /**
     * 关键词 → (领域, 子库) 的映射。
     *
     * <p>为什么按子库而不是按领域检索：{@code DocKnowledgeAssistant.searchKnowledge} 传空 libId
     * 只会查"第一个子库"，{@code support}（哀伤陪伴）的内容永远检索不到。
     * 按命中的子库分别查，既能查全，也避免"一句话查 5 个子库"。
     */
    private record Rule(String domainId, String libId, List<String> keywords) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule("lifesafety", "life", List.of(
                    "生死", "生命教育", "死亡教育", "死亡", "去世", "过世", "离世",
                    "生命的意义", "活着的意义", "脑死亡", "人为什么会死")),
            new Rule("lifesafety", "support", List.of(
                    "哀伤", "丧亲", "亲人去世", "亲人过世", "亲人离世", "哀悼", "葬礼", "临终")),
            new Rule("sexedu", "puberty", List.of(
                    "青春期", "月经", "生理期", "例假", "遗精", "第二性征", "变声", "身体变化")),
            new Rule("sexedu", "relationship", List.of(
                    "身体边界", "性教育", "早恋", "恋爱关系", "不舒服的触碰")),
            new Rule("sexedu", "protection", List.of(
                    "性骚扰", "性侵", "被摸", "生殖健康", "性健康", "艾滋病", "避孕", "保护自己")),
            // 校本资源：**按班隔离**——这个域检索时必须带 classId 过滤（见 COLUMN_FILTER_DOMAINS）
            new Rule("school", "school", List.of(
                    "校本", "本校", "我们学校", "学校资料", "乡土", "校内资料"))
    );

    /**
     * 需要按班过滤的领域：这些域里的 chunk 带 {@code classId} 元数据，
     * 检索时必须把学生的班级作为 {@code where} 条件下发，否则会看到别班的材料。
     *
     * <p>公共知识库（生命安全与健康教育、性教育等）不在其中——它们对所有学生开放，
     * 传过滤条件反而会一条都查不到。
     */
    private static final java.util.Set<String> CLASS_SCOPED_DOMAINS = java.util.Set.of("school");

    /** 命中即跳过：明确属于其它业务域的消息，不该被教育材料污染。 */
    private static final List<String> SKIP_KEYWORDS = List.of(
            "天气", "气温", "温度", "火车票", "高铁", "打车", "点餐", "麦当劳", "小红书",
            "邮件", "提醒", "截图", "翻译", "代码", "地图", "路线", "宠物", "打卡",
            "简历", "招聘", "订单");

    /** 单个片段注入时的截断长度。放宽到 600 是为了给模型足够材料组织答案——切太狠只能对着半句话编。 */
    private static final int MAX_CHUNK_CHARS = 600;

    /** 整个教育上下文块的长度上限，避免挤占对话预算。 */
    private static final int MAX_BLOCK_CHARS = 3000;

    /** 每个子库取几条。 */
    private static final int TOP_K = 3;

    private final DocKnowledgeRegistry registry;
    private final EduTopicRecorder topicRecorder;
    private final EduUserClassMapper userClassMapper;

    public EduContextProvider(DocKnowledgeRegistry registry, EduTopicRecorder topicRecorder,
                              EduUserClassMapper userClassMapper) {
        this.registry = registry;
        this.topicRecorder = topicRecorder;
        this.userClassMapper = userClassMapper;
    }

    /** 当前用户的班级（按班隔离域的过滤条件）；取不到返回 null。 */
    private String classIdOfCurrentUser() {
        try {
            String userId = VoiceContext.getUserId();
            if (userId == null || userId.isBlank()) return null;
            return userClassMapper.selectClassIdByUserId(userId);
        } catch (Exception e) {
            log.debug("查询用户班级失败：{}", e.toString());
            return null;
        }
    }

    /** 是否应当为本条消息检索教育知识库。 */
    public boolean shouldQuery(String message) {
        if (message == null || message.isBlank()) return false;
        String msg = message.toLowerCase(Locale.ROOT);
        if (containsAny(msg, SKIP_KEYWORDS)) return false;
        for (Rule r : RULES) {
            if (containsAny(msg, r.keywords())) return true;
        }
        return false;
    }

    /**
     * 构造注入提示词的教育上下文块。
     *
     * @return 可直接作为 {@code SystemMessage} 的文本；不需要检索或检索无结果时返回 {@code null}
     */
    public Block buildBlock(String message) {
        if (message == null || message.isBlank()) return null;
        String msg = message.toLowerCase(Locale.ROOT);
        if (containsAny(msg, SKIP_KEYWORDS)) return null;

        List<Rule> matched = new ArrayList<>();
        for (Rule r : RULES) {
            if (containsAny(msg, r.keywords())) matched.add(r);
        }
        if (matched.isEmpty()) return null;

        // ★ 专题提问计数：只要门控命中就记一次（**不要求检索有结果**）。
        //   统计语义是"学生问了什么专题"，不是"检索引擎答出了什么"——若放在检索之后，
        //   库里恰好没有资料时就不会计数，占比图会系统性偏低。
        //   计数只写 班级+日期+专题+计数，不含 user_id（见 EduTopicRecorder）。
        Set<String> domains = new LinkedHashSet<>();
        for (Rule r : matched) domains.add(r.domainId());
        for (String domain : domains) {
            topicRecorder.record(VoiceContext.getUserId(), domain);
        }

        // 注入块不只是"资料"，还带回答要求——因为主对话用的是全局人设（旅行规划师），
        // 教育知识库自己的 systemPrompt 在这条链路上并不生效。
        // 不把这些规则写进来，学生问"人为什么会死"得到的会是旅行规划师口吻的回答。
        StringBuilder sb = new StringBuilder(
                """
                【教育专题知识参考 · 回答要求】
                本轮用户问的是教育与健康话题，请按下面的要求作答：
                1. 本轮人设以「面向中小学生的生命安全与健康教育助手」为准（不要用旅行规划师的口吻）；
                2. 读懂下列资料后组织成完整解释：先给结论，再分点，最后一句可操作的建议；篇幅 200~400 字；
                   不要原句照抄，也不要罗列标题；
                3. 资料里有的要点标注【资料】，你自己补充的标注【补充】；不得编造数字、机构名、政策名称；
                4. 资料与问题无关时不要硬套：如实说明"知识库里没有直接对应的材料"，再用通俗语言给出你力所能及的解释；
                5. 若出现自伤、自杀等危险信号，停止科普，先表达关心，并引导联系可信任的成年人，
                   或拨打全国心理援助热线 12356、共青团 12355。

                资料：
                """);
        Set<String> usedLibs = new LinkedHashSet<>();
        int count = 0;
        try {
            outer:
            for (Rule r : matched) {
                DocKnowledgeAssistant assistant = registry.getAssistant(r.domainId());
                if (assistant == null) {
                    log.debug("🎓 教育RAG：领域 {} 未注册，跳过", r.domainId());
                    continue;
                }

                // 按班隔离域：过滤条件下随查询下发；拿不到班级就**不检索**（fail-closed）。
                // 方向是刻意的——学生端"查不到自己班的材料"是可接受的降级，
                // "看到别班的材料"不是。教师端那条路径相反（班级由角色门保证，缺了只是配置错误）。
                Map<String, Object> where = null;
                if (CLASS_SCOPED_DOMAINS.contains(r.domainId())) {
                    where = ChromaClient.eqWhere("classId", classIdOfCurrentUser());
                    if (where == null) {
                        log.debug("🎓 教育RAG：{} 是按班隔离域，但当前用户无班级归属，本次跳过", r.domainId());
                        continue;
                    }
                }

                List<String> chunks = assistant.searchKnowledge(message, r.libId(), TOP_K, where);
                for (String c : chunks) {
                    if (c == null || c.isBlank()) continue;
                    String text = c.length() > MAX_CHUNK_CHARS ? c.substring(0, MAX_CHUNK_CHARS) + "…" : c;
                    sb.append("- [").append(r.domainId()).append('/').append(r.libId()).append("] ")
                            .append(text).append('\n');
                    count++;
                    if (sb.length() > MAX_BLOCK_CHARS) {
                        sb.append("…（更多内容已省略）\n");
                        break outer;
                    }
                }
                usedLibs.add(r.domainId() + "/" + r.libId());
            }
        } catch (Exception e) {
            // 教育 RAG 失败绝不能让主对话失败
            log.warn("🎓 教育知识库检索异常，本轮不注入：{}", e.toString());
            return null;
        }

        if (count == 0) {
            log.debug("🎓 教育RAG 门控命中（{}）但未检索到内容，本轮不注入", usedLibs);
            return null;
        }
        log.info("🎓 教育RAG 注入上下文：{} 个片段 ← {}", count, usedLibs);
        return new Block(sb.toString(), null, false, count);
    }

    private static boolean containsAny(String msg, List<String> keywords) {
        for (String kw : keywords) {
            if (msg.contains(kw)) return true;
        }
        return false;
    }

    /**
     * @param text      注入用的完整文本
     * @param note      未检索到内容时的提示（null 表示无需提示）
     * @param degraded  是否发生软降级（本域暂不产生降级）
     * @param chunkCount 注入的片段数
     */
    public record Block(String text, String note, boolean degraded, int chunkCount) {
    }
}
