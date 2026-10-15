package com.demo.memory;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 用户记忆库配置，键前缀 {@code memory.*}。
 *
 * <p>刻意<b>不</b>包含 {@code memory.embedding.*} 与 {@code memory.chroma.*} 两个子树——
 * 它们分别由 {@code EmbeddingService} 和 {@code ChromaClient} 自己用 {@code @Value} 读取
 * （云端嵌入 provider 选择、Chroma 地址），属于既有公共知识库链路的配置，
 * 本模块只借用、不接管。默认 {@code ignoreUnknownFields=true}，所以互不干扰。
 *
 * <p>所有中文关键词一律硬编码在 Java 源码里，不放配置——本项目 properties 按 ISO-8859-1 读取，
 * 写中文必须转义，容易埋坑。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "memory")
public class MemoryProperties {

    private final Store store = new Store();
    private final Extractor extractor = new Extractor();
    private final Rag rag = new Rag();
    private final Hybrid hybrid = new Hybrid();
    private final Decay decay = new Decay();
    private final Feedback feedback = new Feedback();
    private final Working working = new Working();
    private final Graph graph = new Graph();
    private final Integration integration = new Integration();

    @Getter
    @Setter
    public static class Store {
        /** Lucene 记忆库总开关。false → 记忆相关能力全部跳过。 */
        private boolean enabled = true;

        /**
         * 索引目录。遵循本项目 {@code data/<domain>} 惯例。
         * <p>方案书要求"不要放 C 盘"——本项目的 data 目录是<b>相对于工作目录</b>的，
         * 仓库在工作目录下时就在 C 盘。真要挪走请在这里配绝对路径（如 D:/data/memory-index），
         * 并同步把该路径加进 .gitignore。
         */
        private String indexPath = "data/memory-index";

        /** 分析器：{@code cjk}（中文）或 {@code keyword}（不分词，仅精确匹配）。 */
        private String analyzer = "cjk";

        /** 单用户记忆上限，超出按 {@code importance/(1+ageDays/30)} 逐出。 */
        private int maxMemoryPerUser = 1000;

        /**
         * 向量维度。<b>必须与向量化 provider 一致</b>，且索引一旦建立就不能改
         * （Lucene 的 KnnFloatVectorField 维度固定），改了必须删索引目录重建。
         */
        private int dimension = 1024;
    }

    @Getter
    @Setter
    public static class Extractor {
        /** 规则层是否启用（10 条正则，零成本）。 */
        private boolean ruleEnabled = true;

        /** LLM 兜底抽取开关。默认关：会为每轮对话增加一次 LLM 调用。 */
        private boolean llmEnabled = false;

        /**
         * 单轮消息最多抽几条事实。
         *
         * <p>规则层一共 10 类（姓名/身份/年龄/联系方式/居住地/工作地/宠物/喜好/习惯/计划），
         * 用户完全可能把好几件事塞进一条消息里说（实测有人一次发 12 句自我陈述）。
         * 上限设 5 会把后半截直接丢掉——用户看到的就是"我明明说了，它没记住"。
         */
        private int maxFacts = 10;

        /**
         * 埋点式全量捕获：10 条规则都没命中时，把<b>原句</b>也作为一条记忆存下来。
         *
         * <p>这是"说什么都能加进去"的实现方式。原句以 {@code EPISODIC} + 重要度
         * {@value #CAPTURE_IMPORTANCE} 入库，因此：
         * <ul>
         *   <li>脑图上完整可见（用户能看到自己说过的每句话）</li>
         *   <li>但<b>不会被注入提示词</b>——由 {@code memory.integration.min-importance} 拦下</li>
         *   <li>并被按类型分档的衰减优先回收（EPISODIC 活得比 BASIC 短）</li>
         * </ul>
         */
        private boolean captureAll = true;

        /** 短于该长度的消息不捕获——挡掉"嗯""好的""谢谢"这类纯应答。 */
        private int captureMinLength = 4;

        /** 每条记忆抽取的关键词个数（无词典本地切分，零 token）。 */
        private int keywordsMax = 5;

        /** 全量捕获时给的固定重要度：低于提示词门槛，所以在图上可见、不进上下文。 */
        public static final int CAPTURE_IMPORTANCE = 2;
    }

    @Getter
    @Setter
    public static class Rag {
        /** 记忆 RAG 开关。 */
        private boolean enabled = true;

        /** 检索返回条数。 */
        private int topK = 5;

        /** 最低余弦相似度，低于此值不注入。 */
        private double minScore = 0.35;

        /** 命中后是否异步回写访问计数（reinforce）。 */
        private boolean asyncReinforce = true;
    }

    @Getter
    @Setter
    public static class Hybrid {
        /** 双路 RAG 融合开关。 */
        private boolean enabled = true;

        private int memoryTopK = 5;
        private int lawTopK = 5;

        /**
         * 融合<b>前</b>的原始相关度下限（记忆侧余弦 / 法律侧相似度，均为 0~1）。
         *
         * <p><b>不要按融合分理解。</b>RRF 融合分的量级只有 0.005~0.016（k=60 时），
         * 方案书给的 0.6 若作用在融合分上会把结果全部截断、让混合 RAG 静默失效。
         * 详细推导见 {@link RrfFusion}。
         *
         * <p>默认 0 = 不额外过滤：记忆侧已经有 {@code memory.rag.min-score} 把关，
         * 这里再卡一道只会让"记忆本来能答"的场景变成"什么都不记得"。
         */
        private double minScore = 0.0;

        /** RRF 权重：用户记忆。 */
        private double memoryWeight = 0.3;

        /** RRF 权重：法律/公共知识。 */
        private double lawWeight = 0.5;

        /** RRF 平滑常数 k。 */
        private int rrfK = 60;

        /** 融合前按 content 去重的相似度阈值（字符 bigram Jaccard）。 */
        private double dedupeThreshold = 0.95;

        /** 单路检索超时（秒），超时用降级结果。 */
        private int timeoutSeconds = 30;
    }

    @Getter
    @Setter
    public static class Decay {
        private boolean enabled = true;

        /** 每日凌晨 3 点执行。 */
        private String cron = "0 0 3 * * ?";

        /** 单批处理用户数。 */
        private int batchSize = 100;

        /** 超过该天数的记忆直接删除（除 EPISODIC 外，见下）。 */
        private int maxAgeDays = 90;

        /** 超过该天数开始软衰减（除 EPISODIC 外，见下）。 */
        private int softDecayDays = 30;

        /** 软衰减系数。 */
        private double softDecayFactor = 0.9;

        /**
         * EPISODIC（一次性事件 / 埋点捕获的原句）的删除天数——等同于 JVM 的<b>年轻人代回收更频繁</b>。
         *
         * <p><b>必须与脑图的分代阈值对齐</b>：{@code MemoryGraphService.generation()} 判定
         * EPISODIC 超过 60 天进 {@code PENDING_GC}。若这里设成比 60 小，记忆会在
         * <b>图上变红之前就被删掉</b>，"即将被回收"这个状态永远看不到，图就失去说服力了。
         */
        private int episodicMaxAgeDays = 60;

        /** EPISODIC 的软衰减起点。 */
        private int episodicSoftDecayDays = 14;
    }

    @Getter
    @Setter
    public static class Feedback {
        private boolean enabled = true;

        /** 显式正向关键词。 */
        private List<String> positiveKeywords = List.of("对", "记住了", "记住这个", "没错", "就是这样");

        /** 显式负向关键词。 */
        private List<String> negativeKeywords = List.of("不对", "错了", "记错了", "不是这样");

        /** 显式清除关键词。 */
        private List<String> clearKeywords = List.of("忘掉", "删除记忆", "忘了吧", "别记这个");

        /** 隐式反馈：重复问同一件事 → 认为这条记忆有用，加强。 */
        private boolean implicitEnabled = true;

        private double implicitSimilarityThreshold = 0.9;
    }

    @Getter
    @Setter
    public static class Working {
        private boolean enabled = true;

        /** 会话闲置多久后把工作记忆落成长期记忆。 */
        private int sessionTimeoutMinutes = 30;

        private long checkIntervalMs = 300_000L;
    }

    @Getter
    @Setter
    public static class Graph {
        private boolean enabled = true;

        /** 最多返回的节点数（含 ROOT）。 */
        private int maxNodes = 100;

        /** 只展示访问次数不低于该值的记忆。 */
        private int minAccessCount = 1;

        /** 节点之间建立连边的向量余弦阈值。 */
        private double similarityThreshold = 0.7;

        /** 脑图在独立线程池里构建的最长等待时间；超时降级为"仅根节点"空图。 */
        private int timeoutSeconds = 5;

        /**
         * 构建结果的 TTL 缓存秒数。
         *
         * <p>脑图是 O(N²) 的余弦计算，而前端每 15 秒轮询一次——没有缓存的话，
         * 一个几百条记忆的用户会每 15 秒把 CPU 打满一次。10 秒足够把
         * "同一次刷新里的重复请求"和"连续两次轮询"都吃到缓存。
         */
        private int cacheTtlSeconds = 10;
    }

    @Getter
    @Setter
    public static class Integration {
        /**
         * 记忆集成总开关。默认 <b>false</b>，这是刻意的。
         *
         * <p>本项目原先已有一条用户长期记忆链路（{@code UserMemoryService} + Chroma + 云端 embedding），
         * 本模块是它的<b>本地替代</b>，两者职责相同、只能二选一。打开这个开关会：
         * <ul>
         *   <li>用本地 Lucene 记忆库注入上下文（云端 embedding 欠费也能用）；</li>
         *   <li><b>停用</b>原来那条 Chroma 记忆的注入——已经积累在 Chroma 里的旧记忆不再被注入。</li>
         * </ul>
         * 这是一次实质性的行为切换，所以默认关闭：新能力就绪，但由使用者决定何时切。
         * 置 true 即完成切换，无需改任何代码。
         */
        private boolean enabled = false;

        /** 注入上下文时取几条记忆。 */
        private int retrieveTopK = 5;

        /** 注入块的标题前缀。 */
        private String contextPrefix = "【记忆上下文】";

        /** 写入是否异步。 */
        private boolean asyncWrite = true;

        /**
         * 注入提示词的最低重要度门槛。
         *
         * <p>埋点全量捕获的原句重要度是 {@value Extractor#CAPTURE_IMPORTANCE}，
         * 规则抽出的干净事实是 5~9。门槛设 3 的效果是：
         * <b>原句只在脑图上可见，不进提示词</b>——否则每轮上下文会被
         * "今天天气怎么样""谢谢"这类内容稀释，既费 token 又干扰回答。
         *
         * <p>想让模型也"记得你说过的每句话"，把它调成 2 即可，不用改代码。
         */
        private int minImportance = 3;
    }
}
