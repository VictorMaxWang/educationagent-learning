package com.demo.router;

import com.demo.edu.safety.CrisisSignalDetector;

import java.util.List;

/**
 * 安全规则：路由 L1 的第一条规则所依据的词表与判定。
 *
 * <h3>为什么放在 router 包，却复用 edu.safety 的检测器</h3>
 * 位置放在 {@code router} 是因为它的消费者是 L1 规则引擎；但自伤/自杀词表<b>复用</b>
 * {@link CrisisSignalDetector} 而不是另抄一份——<b>两套风险词表一定会漂移</b>，
 * 而漂移的后果是"某个入口漏检"。宁可这里有跨包依赖，也不要两个真相源。
 *
 * <h3>两类风险，处置不同</h3>
 * <ul>
 *   <li>{@link Verdict#SELF_HARM} 自伤/自杀风险 → 固定引导话术（含全国心理援助热线）+ <b>脱敏计数</b>；</li>
 *   <li>{@link Verdict#EXPLICIT_OR_ILLEGAL} 索取色情资源 / 性交易 / 违法实施方法 → 固定拒绝引导话术，
 *       <b>不计入危机计数</b>（那是"自伤风险"的口径，混进去会让教师端的数字失去意义）。</li>
 * </ul>
 *
 * <h3>白名单为什么是"构建期校验"而不是"运行期放行"</h3>
 * 这个项目有一个具体而危险的边界：<b>青春期性教育知识库的正常提问本身就在用解剖学词汇</b>
 * （月经、遗精、避孕、生殖健康）。粗暴的敏感词表会把这个库存在的意义一并拦掉。
 *
 * <p>常见做法是"运行时先查白名单、命中就放行"。这里不做，因为那样会留一个洞：
 * "月经期间能看黄片吗" 会因为含"月经"而被整体放行。改用<b>冲突校验</b>：
 * {@link #assertNoConflict()} 检查黑名单里的词是否与健康白名单语义重叠（互为子串），
 * 一旦有人把"性行为""发育"这类词加进黑名单，<b>启动即报错、测试即失败</b>。
 * 与其在运行期事后放行，不如在构建期不让它进来。
 */
public final class SafetyKeywordRules {

    /** 判定结果。 */
    public enum Verdict {
        /** 未命中。 */
        NONE,
        /** 自伤 / 自杀风险。 */
        SELF_HARM,
        /** 索取色情资源 / 性交易 / 违法实施方法。 */
        EXPLICIT_OR_ILLEGAL
    }

    /** 命中详情。 */
    public record Hit(Verdict verdict, String keyword) {
        public boolean isHit() {
            return verdict != Verdict.NONE;
        }

        /**
         * 风险等级：自伤类按词表分档（1 无望 / 2 直接意念 / 3 方法性表述）；
         * 违规内容类不参与危机等级（返回 0）——它和"自伤风险"是两回事，混在一起会让教师端那个数字失去意义。
         */
        public int level() {
            return verdict == Verdict.SELF_HARM ? CrisisSignalDetector.levelOf(keyword) : 0;
        }

        static Hit none() {
            return new Hit(Verdict.NONE, null);
        }
    }

    /**
     * 违规内容请求词表。
     *
     * <p>刻意<b>只收"索取资源 / 性交易 / 明确的违法实施方法"</b>这三类模式，
     * 不收任何解剖学、健康、法律话题词。反例说明：
     * <ul>
     *   <li>不收 {@code 做爱} / {@code 性行为}：这是性教育里必然会出现的提问用词
     *       （"青春期对性行为好奇正常吗"该答，不该拦）；</li>
     *   <li>不收 {@code 杀人} / {@code 盗窃}：法律知识库的正常提问
     *       （"故意杀人怎么判刑"是法律咨询，不是犯罪意图）；</li>
     *   <li>不收 {@code 毒品} / {@code 吸毒}：生命安全与健康教育里
     *       "吸毒有什么危害"是正经科普题。</li>
     * </ul>
     * 只拦"哪里能买/怎么搞到/教我制作"这一类无法辩解的实施请求。
     */
    private static final List<String> EXPLICIT_OR_ILLEGAL = List.of(
            // 索取色情资源
            "黄色视频", "黄片", "黄图", "色情视频", "色情网站", "色情小说", "色情动漫",
            "成人视频", "裸照", "偷拍视频", "三级片",
            // 性交易
            "约炮", "找小姐", "招嫖", "嫖娼", "卖淫", "援交", "一夜情", "上门服务",
            // 违法实施方法。注意这里收的是"动词短语"而不是光杆名词：
            // 「怎么盗号」是实施请求，而「我的号被盗了怎么办」是被害人求助——后者必须能答。
            // 光杆名词（盗号/下毒/骗钱）单独出现时两种语义都有，靠关键词层分不开，
            // 所以只把明确的"求方法"组合写进词表。
            "制作炸药", "自制炸弹", "制作毒药", "下毒的配方", "买枪", "搞到枪", "迷药",
            "迷奸药", "黑客教程", "盗号教程", "洗钱方法", "诈骗话术",
            "怎么盗号", "教我盗号", "如何盗号", "怎么骗钱", "教我骗钱", "如何骗钱",
            "怎么下毒", "教我下毒", "如何下毒", "怎么弄到枪"
    );

    /**
     * 健康教育白名单：<b>不参与运行期放行，只用于校验黑名单没有越界</b>
     * （见 {@link #assertNoConflict()}）。它们出现时说明这是一条正常的教育类提问，
     * 因此黑名单里绝不允许出现与它们重叠的词。
     */
    private static final List<String> HEALTH_WHITELIST = List.of(
            "月经", "生理期", "例假", "遗精", "第二性征", "发育", "青春期", "避孕", "避孕套",
            "生殖健康", "性健康", "性教育", "艾滋病", "性病", "怀孕", "身体变化",
            "性骚扰", "性侵", "自慰", "生殖器", "激素", "身体边界"
    );

    private SafetyKeywordRules() {
    }

    /** 判定（自伤优先，其次违规内容）。 */
    public static Hit classify(String text) {
        return classify(text, null);
    }

    /**
     * @param text          待判定文本（用户原话，或媒体预处理后拼成的描述文本）
     * @param extraSelfHarm 运行时追加的自伤词（{@code edu.safety.extra-words}），可为 null
     */
    public static Hit classify(String text, List<String> extraSelfHarm) {
        if (text == null || text.isBlank()) return Hit.none();

        // ① 自伤 / 自杀：优先级最高，且不受任何白名单影响
        List<String> selfHarm = CrisisSignalDetector.hits(text, extraSelfHarm);
        if (!selfHarm.isEmpty()) {
            return new Hit(Verdict.SELF_HARM, selfHarm.get(0));
        }

        // ② 违规内容请求
        for (String kw : EXPLICIT_OR_ILLEGAL) {
            if (text.contains(kw)) {
                return new Hit(Verdict.EXPLICIT_OR_ILLEGAL, kw);
            }
        }
        return Hit.none();
    }

    /**
     * 冲突校验：黑名单不得与健康教育白名单重叠（互为子串）。
     *
     * <p>在 {@code RuleBasedRouter} 启动时调用一次，同时由单元测试钉住。
     * 触发即说明有人把正常健康教育词汇加进了黑名单——那会把这个系统最该答的问题拦掉。
     *
     * @throws IllegalStateException 存在冲突时
     */
    public static void assertNoConflict() {
        assertNoConflict(EXPLICIT_OR_ILLEGAL, HEALTH_WHITELIST);
    }

    /**
     * 可测的重载：校验逻辑本身必须能被验证，否则它就是个空方法。
     * （包可见，供 {@code SafetyKeywordRulesTest} 用一份"故意冲突"的词表验证它会抛。）
     */
    static void assertNoConflict(List<String> blockedWords, List<String> whitelist) {
        for (String blocked : blockedWords) {
            for (String health : whitelist) {
                if (blocked.contains(health) || health.contains(blocked)) {
                    throw new IllegalStateException(
                            "安全词表冲突：黑名单「" + blocked + "」与健康教育白名单「" + health
                                    + "」重叠。这会拦掉正常健康教育提问，请改词表");
                }
            }
        }
    }

    /** 词表规模（供健康面板展示，便于确认"词表不是空的"）。 */
    public static String summary() {
        return "自伤自杀 " + CrisisSignalDetector.signalCount() + " 词 + 违规内容 "
                + EXPLICIT_OR_ILLEGAL.size() + " 词；健康白名单 " + HEALTH_WHITELIST.size()
                + " 词（构建期校验，不参与运行期放行）";
    }

    /** 是否命中健康教育白名单词汇（供诊断与测试使用）。 */
    public static boolean looksLikeHealthQuestion(String text) {
        if (text == null) return false;
        for (String kw : HEALTH_WHITELIST) {
            if (text.contains(kw)) return true;
        }
        return false;
    }
}
