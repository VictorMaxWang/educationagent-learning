package com.demo.edu.safety;

import com.demo.router.SafetyKeywordRules;
import com.demo.util.VoiceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 安全闸门：命中自伤风险或违规内容请求时，<b>不让这句话进入 LLM</b>，直接给学生一段固定话术。
 *
 * <p>判定源是 {@link SafetyKeywordRules}——与路由 L1 的安全规则<b>同一份</b>，
 * 所以不存在"路由拦了但知识库链路漏了"这类不一致。本类负责的是<b>处置</b>：
 * 取自伤话术 + 脱敏计数，或取违规话术且不计数。
 *
 * <h3>两条触发路径</h3>
 * <ol>
 *   <li><b>主路径</b>：路由 L1 的安全规则判出 {@code BLOCKED}，由 {@code AgentExecutor} 短路调用本类；</li>
 *   <li><b>兜底</b>：路由未启用/未就绪（{@code route == null}）、或走的是知识库 REST
 *       （{@code BaseDocKnowledgeController.search}，那条链路根本不经过路由）时由本类直接判定。
 *       安全不能挂在功能开关上，所以这条兜底是必需的。</li>
 * </ol>
 *
 * <h3>为什么不交给模型</h3>
 * 让模型"自由发挥"回答"我不想活了"是这类系统最不该做的事：它可能生成说教、
 * 可能生成文学化的死亡描写、也可能只是礼貌地转开话题。固定话术的每一句都是可以逐字审的，
 * 而它要传达的东西就三样——<b>被听见、可求助的电话、身边可信任的人</b>。
 *
 * <h3>接入点（3 处，覆盖全部入口）</h3>
 * <ol>
 *   <li>{@code AgentExecutor.chat} 首行 —— 微信链路的最终汇聚点（CmdService 的快路径最终都走到这里）</li>
 *   <li>{@code AgentExecutor.chatStream} 首行 —— 桌面链路；3 参重载委托 4 参，守一处即可</li>
 *   <li>{@code BaseDocKnowledgeController.search} —— 知识库 REST（通识/心理/生死/性教育/校本共用基类），
 *       这条链路不经过 AgentExecutor</li>
 * </ol>
 * 图片/语音/文件在两端预处理后都已拼成同一个字符串（agentInput），
 * 所以"图片描述里藏着危机信号"同样被覆盖，不需要在预处理层再加一道。
 */
@Slf4j
@Component
public class CrisisGuard {

    /** 固定话术：不含说教、不含死亡描写、可逐字审核。 */
    public static final String SCRIPT = """
            我很担心你现在的状态。你愿意说出来，已经很不容易了。

            你并不孤单，现在这种感觉是可以被帮到的：
            · 全国心理援助热线 12356（24 小时免费）
            · 共青团青少年服务热线 12355

            如果情况紧急，请立刻联系你信任的成年人——父母、老师或学校心理老师；必要时拨打 120。

            我会在这里陪着你。你愿意的话，可以告诉我现在发生了什么。""";

    /**
     * 违规内容请求的固定话术（索取色情资源 / 性交易 / 违法实施方法）。
     *
     * <p>为什么不复用 {@link #SCRIPT}：两者要说的事完全不同——自伤话术是"你并不孤单 + 热线"，
     * 这里要的是"我不提供这类内容"并且**把正常提问的路留出来**（很多孩子其实是在问健康问题，
     * 只是措辞不当；一句生硬的拒绝会把人挡回去）。
     */
    public static final String EXPLICIT_SCRIPT = """
            这个我没法帮你。色情资源、性交易、伤害他人的方法，我不会提供，也不会去查。

            如果你其实是在问青春期的健康问题，换个说法，我会认真回答你。

            如果有人正在向你索取或传播这类内容，请告诉可信任的成年人；必要时拨打 110。""";

    /**
     * 判断一段回复是不是本类的固定话术。
     *
     * <p>用途：L4 后置内容校验要跳过它们——固定话术是逐字审过的，
     * 再去花一次 LLM 裁判既浪费又可能被误判成"不合适"而把热线话术替换掉（那会很糟）。
     */
    public static boolean isSafetyScript(String reply) {
        if (reply == null) return false;
        return reply.startsWith(SCRIPT.substring(0, 16))
                || reply.startsWith(EXPLICIT_SCRIPT.substring(0, 16));
    }

    private final CrisisGuardProperties properties;
    private final CrisisCountRecorder recorder;

    public CrisisGuard(CrisisGuardProperties properties, CrisisCountRecorder recorder) {
        this.properties = properties;
        this.recorder = recorder;
    }

    /**
     * 检查并处置。
     *
     * @param memoryId    会话标识（微信为 userId，桌面为 session_xxx）
     * @param userMessage 本条消息（可能是媒体预处理后的拼装文本）
     * @return 需要短路时返回固定话术；否则 empty（照常进入 LLM）
     */
    public Optional<String> check(String memoryId, String userMessage) {
        return check(memoryId, userMessage, null);
    }

    /**
     * 显式指定用户标识的重载（<b>知识库 REST 路径必须用这个</b>）。
     *
     * <p>为什么：{@code resolveUserId} 会优先取 {@code VoiceContext}（聊天链路的 ThreadLocal），
     * 而知识库 REST 请求**没有设置过它**——Tomcat 复用线程，于是可能读到**上一个请求残留的用户 id**，
     * 把这次危机信号记到**另一个学生的班级**里。这是隐私敏感路径上的错误归属，必须避免。
     *
     * @param explicitUserId 明确的用户标识；传入时不再看 VoiceContext
     */
    public Optional<String> checkForUser(String explicitUserId, String userMessage) {
        return check(null, userMessage, explicitUserId);
    }

    private Optional<String> check(String memoryId, String userMessage, String explicitUserId) {
        if (!properties.isEnabled()) return Optional.empty();

        SafetyKeywordRules.Hit hit;
        try {
            // 判定源统一在 SafetyKeywordRules（它内部复用 CrisisSignalDetector 的自伤词表）：
            // 路由 L1 的安全规则与本方法用的是同一份判定，不会出现"路由拦了、这里没拦"的不一致
            hit = SafetyKeywordRules.classify(userMessage, properties.getExtraWords());
        } catch (Exception e) {
            log.warn("安全关键词判定异常（按未命中处理）：{}", e.toString());
            return Optional.empty();
        }
        if (!hit.isHit()) return Optional.empty();

        // 显式给定的优先（知识库 REST 路径）；否则按聊天链路的口径解析（VoiceContext → memoryId）
        String userId = (explicitUserId != null && !explicitUserId.isBlank())
                ? explicitUserId : resolveUserId(memoryId);
        log.warn("🚨 安全闸门命中 {} | keyword={} | 风险等级={} | memoryId={} | 用户哈希={} | mode={}",
                hit.verdict(), hit.keyword(), hit.level(), memoryId, shortHash(userId), properties.getMode());

        if (!properties.isEnforce()) {
            // log 模式：只记录、照常走 LLM。用于量误报率 —— 此时不计数，
            // 因为计数语义是"已向学生发出危机引导"，log 模式下并没有发。
            log.warn("⚠️ 安全闸门处于 log 模式：本条仍会进入 LLM，话术不会被替换。"
                    + "演示/上线前必须确认 edu.safety.mode=enforce");
            return Optional.empty();
        }

        if (hit.verdict() == SafetyKeywordRules.Verdict.SELF_HARM) {
            recorder.record(userId, hit.level());
            return Optional.of(SCRIPT);
        }
        // 违规内容请求：拒绝并留出正常提问的路，但**不写入危机计数**——
        // 危机计数的口径是"自伤风险"，把色情/违法请求混进去会让教师端那个数字失去意义。
        log.warn("🚫 违规内容请求已拒绝（keyword={}），按设计不计入危机计数", hit.keyword());
        return Optional.of(EXPLICIT_SCRIPT);
    }

    /**
     * 取"真实用户标识"：桌面端 memoryId 是 {@code session_xxx}，不是用户；
     * 与 AgentExecutor.buildMessages 取 settingsUserId 的口径保持一致（优先 VoiceContext）。
     */
    private static String resolveUserId(String memoryId) {
        String fromCtx = VoiceContext.getUserId();
        return (fromCtx != null && !fromCtx.isBlank()) ? fromCtx : memoryId;
    }

    /** 日志里只留用户哈希前缀，避免把学生标识写进日志。 */
    private static String shortHash(String raw) {
        if (raw == null) return "null";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8))).substring(0, 8);
        } catch (Exception e) {
            return "?";
        }
    }
}
