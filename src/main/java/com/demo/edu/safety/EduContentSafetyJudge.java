package com.demo.edu.safety;

import com.demo.config.AiChatProperties;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.demo.router.CircuitBreaker;
import com.demo.edu.classroom.ai.ClassroomModelAdmission;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.time.Duration;
import java.util.concurrent.CancellationException;

/**
 * L4 后置内容校验：回答生成后，用<b>独立裁判模型</b>核查内容是否适合中小学教育场景。
 *
 * <h3>基础设施是复用的，不是新建的</h3>
 * 温度 0、JSON 输出、<b>熔断器 + 超时降级</b>、"任何失败都保留原结论"这套骨架，
 * 与本项目 {@code ToolGuardJudgeService}（那套判的是"该调工具没调"）完全一致。
 * 这里只是把判据从"工具调用合规"换成"内容适龄合规"。
 *
 * <h3>一个必须承认的事实：流式回答撤回不了</h3>
 * 流式链路里 token 是边生成边推给用户的，等裁判出结果时用户已经看到了。
 * 所以两条链路的处置方式<b>必然不同</b>（这里没有优雅解法，只有如实处理）：
 * <ul>
 *   <li><b>非流式 / 知识库 REST</b>：返回前替换成标准提示（能真正拦下）；</li>
 *   <li><b>流式</b>：只能<b>追加一条更正提示</b>（{@code streamCorrection}）+ 记 WARN，
 *       并在文档里写明"流式路径的 L4 是事后更正，不是事后拦截"。</li>
 * </ul>
 *
 * <h3>失败方向</h3>
 * 裁判超时、熔断打开、JSON 解析失败 → 一律视为"无法判定"，<b>不阻断</b>。
 * 理由：内容裁判是"再多一道保险"，不是安全前提（前提是 L1 的精确关键词闸门）。
 * 让它因为自己挂了就把所有回答替换掉，是把保险做成了单点故障。
 */
@Slf4j
@Service
public class EduContentSafetyJudge {

    @Resource
    private AiChatProperties aiChat;

    /** 裁判调用的抽象（测试可替换，避免单测真的去打 LLM）。 */
    @FunctionalInterface
    interface LlmCaller {
        /** @return 模型返回的原始 JSON 文本；失败抛异常 */
        String call(String prompt) throws Exception;
    }

    private final EduContentSafetyProperties properties;
    private final CircuitBreaker circuitBreaker;
    private final ClassroomModelAdmission admission;

    /** 长文本生成的路径可能不注入 llmRestTemplate（单测），缺失时按"无法判定"跳过。 */
    @Resource(name = "llmRestTemplate")
    private RestTemplate llmRestTemplate;

    private volatile LlmCaller llmCaller;

    public EduContentSafetyJudge(EduContentSafetyProperties properties) {
        this(properties,null);
    }
    @Autowired public EduContentSafetyJudge(EduContentSafetyProperties properties,ClassroomModelAdmission admission) {
        this.properties = properties;
        this.admission=admission;
        // 复用路由那套熔断器（同一份实现：连续失败即开路，冷却后放行一次探测）
        this.circuitBreaker = new CircuitBreaker("edu-content-judge", true,
                properties.getCircuitFailThreshold(), properties.getCircuitOpenMs());
    }

    /** 供测试注入（生产代码不应调用）。 */
    void setLlmCallerForTest(LlmCaller caller) {
        this.llmCaller = caller;
    }

    /**
     * 判定结果。
     *
     * @param evaluated  是否真的跑过裁判（false = 开关关闭/熔断打开/超时/解析失败）
     * @param safe       {@code evaluated=false} 时恒为 true（即不阻断）
     * @param category   命中的类别（safe 时为 null）
     * @param reason     裁判给的理由
     */
    public record Verdict(boolean evaluated, boolean safe, String category, String reason) {
        static Verdict unknown(String reason) {
            return new Verdict(false, true, null, reason);
        }
    }

    /** 校验一段文本（回答正文）。 */
    public Verdict judge(String text) {
        return judge(text, null);
    }

    /**
     * 校验回答正文，<b>并同时校验检索片段</b>（片段与正文一起判、只花一次裁判调用）。
     *
     * <p>为什么要连片段一起判：片段是拼进提示词的原材料，若片段本身不适龄，
     * 回答迟早会带出来；等它出现在输出里才拦，已经晚了一步。
     *
     * <p>为什么合成一次调用而不是两次：两次调用 = 双倍延迟与成本，而判据是同一套；
     * 合在一起让裁判"任一处不合适即判不合适"更省也更一致。
     *
     * @param snippets 检索片段（可为 null/空，此时只判正文）
     */
    public Verdict judge(String text, java.util.List<String> snippets) {
        if (!properties.isEnabled()) return Verdict.unknown("content-check-disabled");
        if ((text == null || text.isBlank()) && (snippets == null || snippets.isEmpty())) {
            return Verdict.unknown("empty-text");
        }
        if (!circuitBreaker.allowRequest()) {
            return Verdict.unknown("circuit-open");
        }
        var attempted=new java.util.concurrent.atomic.AtomicBoolean();
        try {
            // 开关、空输入与熔断已在前面短路；只有实际语义调用占用共享模型额度。
            LlmCaller target=caller();String prompt=buildPrompt(text,snippets);
            String raw;
            if(admission==null){attempted.set(true);raw=target.call(prompt);}
            else raw=admission.execute(Duration.ofMillis(Math.max(1,Math.min(180_000,properties.getTimeoutMs()))),null,remaining->{
                attempted.set(true);try{return target.call(prompt);}catch(RuntimeException known){throw known;}catch(Exception failure){throw new IllegalStateException("内容裁判请求未完成",failure);}
            });
            Verdict verdict = parseVerdict(raw);
            circuitBreaker.recordSuccess();
            return verdict;
        } catch (ClassroomModelAdmission.Rejected busy) {
            // 资源排队不代表供应商失败，不能把本机配额拥挤写进熔断计数。
            if(attempted.get())circuitBreaker.recordFailure();else circuitBreaker.recordNotAttempted();
            return Verdict.unknown(attempted.get()?"llm-timeout":"model-capacity-"+busy.reason());
        } catch (CancellationException cancelled) {
            circuitBreaker.recordNotAttempted();
            return Verdict.unknown("content-check-cancelled");
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            log.warn("内容裁判调用失败（按无法判定处理，不阻断）：{}", e.getClass().getSimpleName());
            return Verdict.unknown("llm-failed");
        }
    }

    /** 便捷方法：不适合（且已判定）时返回替换话术。 */
    public Optional<String> replacementIfUnsafe(String text) {
        return replacementIfUnsafe(text, null);
    }

    /** 便捷方法（含片段）：不适合时返回替换话术。 */
    public Optional<String> replacementIfUnsafe(String text, java.util.List<String> snippets) {
        Verdict v = judge(text, snippets);
        if (v.evaluated() && !v.safe()) {
            log.warn("🚫 L4 内容校验判定不合适 | 类别={} | 理由={} | mode={} | 含片段={}",
                    v.category(), v.reason(), properties.getMode(),
                    snippets != null && !snippets.isEmpty());
            if (properties.isEnforce()) {
                return Optional.of(properties.getReplacement());
            }
            log.warn("⚠️ L4 处于 log 模式：只记录不替换。改 edu.safety.content.mode=enforce 即生效");
        }
        return Optional.empty();
    }

    /** 流式路径用：需要追加更正提示时才返回提示文本。 */
    public Optional<String> streamCorrectionIfUnsafe(String text) {
        Verdict v = judge(text, null);
        if (!v.evaluated() || v.safe()) return Optional.empty();
        log.warn("🚫 L4 流式路径判定不合适（已输出，只能追加更正）| 类别={} | 理由={}",
                v.category(), v.reason());
        return properties.isEnforce() ? Optional.of(properties.getStreamCorrection()) : Optional.empty();
    }

    /** 当前是否处于熔断打开状态（供健康面板展示；用 state 而不是 allowRequest，避免有副作用）。 */
    public boolean circuitOpen() {
        return circuitBreaker.state() != CircuitBreaker.State.CLOSED;
    }

    // ======================== 内部 ========================

    private LlmCaller caller() {
        if (llmCaller != null) return llmCaller;
        if (llmRestTemplate == null || aiChat == null || !aiChat.hasApiKey()) {
            throw new IllegalStateException("内容裁判未就绪（缺少 llmRestTemplate 或 AI_CHAT_API_KEY）");
        }
        return this::callDeepSeek;
    }

    private String callDeepSeek(String prompt) {
        JSONObject body = new JSONObject();
        aiChat.configureRequest(body);
        body.put("temperature", 0.0);
        body.put("max_tokens", 80);
        JSONArray messages = new JSONArray();
        JSONObject sys = new JSONObject();
        sys.put("role", "system");
        sys.put("content", "你是内容安全裁判。只输出 JSON，不要解释、不要 markdown。");
        JSONObject usr = new JSONObject();
        usr.put("role", "user");
        usr.put("content", prompt);
        messages.add(sys);
        messages.add(usr);
        body.put("messages", messages);

        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", "Bearer " + aiChat.getApiKey());
        headers.add("Content-Type", "application/json");

        ResponseEntity<String> resp = llmRestTemplate.postForEntity(
                aiChat.getCompletionsUrl(), new HttpEntity<>(body.toJSONString(), headers), String.class);
        return resp.getBody();
    }

    /** 裁判提示词：判"是否适合中小学教育场景"，片段与正文一起判，强制 JSON 输出。 */
    private static String buildPrompt(String text, java.util.List<String> snippets) {
        String trimmed = text == null ? "" : (text.length() > 1200 ? text.substring(0, 1200) + "…" : text);

        StringBuilder snippetBlock = new StringBuilder();
        if (snippets != null && !snippets.isEmpty()) {
            int budget = 1200;
            for (String s : snippets) {
                if (s == null || s.isBlank()) continue;
                String piece = s.length() > 300 ? s.substring(0, 300) + "…" : s;
                if (budget - piece.length() < 0) break;
                snippetBlock.append("- ").append(piece).append('\n');
                budget -= piece.length();
            }
        }

        return """
                判断下面的内容是否适合直接展示给中小学生（教育场景）。
                不合适的类别仅限：sex_explicit（露骨性描述）、violence（暴力血腥细节）、
                discrimination（歧视侮辱）、horror（恐怖惊悚描写）、self_harm_method（自伤自杀方法细节）、
                illegal（违法实施方法）、other_unsuitable。
                注意：正常的青春期健康教育、生命教育科普**不算**不合适。
                任何一处（参考资料或回答）出现不合适内容，即判 false。
                只输出 JSON：{"safe":true/false,"category":"...","reason":"不超过30字"}

                """
                + (snippetBlock.length() == 0 ? "" : "【参考资料片段】\n" + snippetBlock + "\n")
                + "【待判断回答】\n" + trimmed;
    }

    /**
     * 解析裁判输出。包可见静态方法——解析逻辑必须可单测（裁判本身要真实调 LLM）。
     *
     * <p>解析不出来时返回"未判定"（= 不阻断），绝不因为裁判输出格式不对就把回答替换掉。
     */
    static Verdict parseVerdict(String raw) {
        if (raw == null || raw.isBlank()) return Verdict.unknown("empty-response");
        try {
            JSONObject root = JSON.parseObject(raw);
            String content = root.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content");
            if (content == null) return Verdict.unknown("no-content");

            // 容错：模型可能仍带 ```json 包裹
            String json = content.trim();
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start < 0 || end <= start) return Verdict.unknown("no-json-in-content");
            json = json.substring(start, end + 1);

            JSONObject verdict = JSON.parseObject(json);
            Boolean safe = verdict.getBoolean("safe");
            if (safe == null) return Verdict.unknown("no-safe-field");
            String category = verdict.getString("category");
            String reason = verdict.getString("reason");
            return new Verdict(true, safe, category, reason);
        } catch (Exception e) {
            return Verdict.unknown("parse-failed");
        }
    }

    /** 供健康面板展示配置。 */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", properties.isEnabled());
        out.put("mode", properties.getMode());
        out.put("enforce", properties.isEnforce());
        out.put("model", aiChat == null ? "unconfigured" : aiChat.getModel());
        out.put("timeoutMs", properties.getTimeoutMs());
        out.put("circuitOpen", circuitOpen());
        out.put("note", "流式路径只能追加更正提示（已输出的内容撤回不了）；非流式与知识库路径可替换");
        return out;
    }
}
