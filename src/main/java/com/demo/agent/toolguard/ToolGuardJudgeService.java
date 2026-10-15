package com.demo.agent.toolguard;

import com.demo.config.AiChatProperties;
import jakarta.annotation.Resource;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.demo.router.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具守卫的 LLM 裁判：只在规则裁决为 {@link AuditVerdict#AMBIGUOUS} 时被调用，
 * 判断"命中规则但没调工具"到底是真的漏调，还是模型用自身知识正确回答了。
 *
 * <p>为什么需要它：规则层只能看到"关键词命中 + 工具没调"，看不到语义。
 * 「今天天气怎么样」模型凭记忆答了 → 可能该拦（天气是实时数据，凭记忆答就是幻觉）；
 * 「算一下 1+1」模型直接答 2 → 拦它纯属浪费。这两者关键词层面完全一样。
 *
 * <p>容错：<b>任何失败都回退到规则结论</b>（{@link JudgeSource#RULE_FALLBACK}），
 * 绝不因为裁判不可用而改变阻断行为。裁判默认关闭（见 {@link ToolGuardProperties.Judge}）。
 */
@Slf4j
@Service
public class ToolGuardJudgeService {

    @Resource
    private AiChatProperties aiChat;

    private static final String SYSTEM_PROMPT = """
            你是工具调用审计员。用户问了一句话，助手回复了，但没有调用任何工具。
            请判断：助手是否应该调用工具，而不是凭自身知识回答？
            判断要点：
            - 需要实时数据（天气、股价、车票余票、新闻、订单状态）→ 应该调工具
            - 需要访问用户数据（档案、文件、日程、知识库）→ 应该调工具
            - 纯常识、数学计算、闲聊、解释概念 → 不需要调工具
            只输出 JSON，不要解释，格式：{"verdict":"PASS","reason":"简短理由"}
            verdict 取值：PASS（不调工具是对的）/ FAIL（应该调工具却没调）
            """;

    /** 从模型输出里抠出 JSON 对象。 */
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{.*}", Pattern.DOTALL);

    private final ToolGuardProperties properties;
    private final RestTemplate restTemplate;
    private final CircuitBreaker circuitBreaker;

    public ToolGuardJudgeService(ToolGuardProperties properties,
                                 @Qualifier("toolGuardRestTemplate") RestTemplate restTemplate,
                                 @Qualifier("toolGuardCircuitBreaker") CircuitBreaker circuitBreaker) {
        this.properties = properties;
        this.restTemplate = restTemplate;
        this.circuitBreaker = circuitBreaker;
    }

    /** 裁判是否可用（配置开启 + 有 key + 熔断未打开）。 */
    public boolean isAvailable() {
        return properties.isEnabled() && properties.getJudge().isEnabled()
                && aiChat != null && aiChat.hasApiKey();
    }

    /** 裁判当前状态，供健康面板展示。 */
    public String state() {
        if (!properties.getJudge().isEnabled()) return "DISABLED";
        if (aiChat == null || !aiChat.hasApiKey()) return "NO_API_KEY";
        return circuitBreaker.state().name();
    }

    /**
     * 请裁判判定。
     *
     * @return 覆盖了裁判结论的 {@link AuditResult}；裁判不可用/失败时返回
     *         {@link JudgeSource#RULE_FALLBACK} 的原结论
     */
    public AuditResult judge(String userMessage, String llmText, AuditResult ruleResult) {
        if (!isAvailable()) {
            return ruleResult.withJudge(ruleResult.verdict(), JudgeSource.RULE_FALLBACK, "judge-unavailable");
        }
        if (!circuitBreaker.allowRequest()) {
            return ruleResult.withJudge(ruleResult.verdict(), JudgeSource.RULE_FALLBACK, "circuit-open");
        }

        try {
            String raw = callDeepSeek(userMessage, llmText, ruleResult);
            circuitBreaker.recordSuccess();
            Verdict parsed = parse(raw);
            if (parsed == null) {
                return ruleResult.withJudge(ruleResult.verdict(), JudgeSource.RULE_FALLBACK, "unparsable");
            }
            AuditVerdict verdict = "FAIL".equals(parsed.verdict()) ? AuditVerdict.FAIL : AuditVerdict.PASS;
            return ruleResult.withJudge(verdict, JudgeSource.LLM, parsed.reason());
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            log.warn("工具守卫裁判调用失败 → 回退规则结论：{}", e.toString());
            return ruleResult.withJudge(ruleResult.verdict(), JudgeSource.RULE_FALLBACK, "judge-error");
        }
    }

    // ======================== 内部实现 ========================

    private record Verdict(String verdict, String reason) {}

    private String callDeepSeek(String userMessage, String llmText, AuditResult rule) {
        String payload = "用户： " + truncate(userMessage, 200) + "\n"
                + "助手回复： " + truncate(llmText, 400) + "\n"
                + "规则认为可能该调用的工具： " + rule.expectedTools();

        JSONArray messages = new JSONArray();
        JSONObject sys = new JSONObject();
        sys.put("role", "system");
        sys.put("content", SYSTEM_PROMPT);
        messages.add(sys);
        JSONObject user = new JSONObject();
        user.put("role", "user");
        user.put("content", payload);
        messages.add(user);

        JSONObject body = new JSONObject();
        aiChat.configureRequest(body);
        body.put("temperature", properties.getJudge().getTemperature());
        body.put("max_tokens", properties.getJudge().getMaxTokens());
        body.put("messages", messages);

        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", "Bearer " + aiChat.getApiKey());
        headers.add("Content-Type", "application/json");

        ResponseEntity<String> resp = restTemplate.postForEntity(
                aiChat.getCompletionsUrl(), new HttpEntity<>(body.toJSONString(), headers), String.class);
        JSONObject result = JSON.parseObject(resp.getBody());
        if (result == null) throw new IllegalStateException("空响应");
        return result.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
    }

    /** 宽松解析：模型偶尔会裹着 ``` 或多余说明，正则抠出第一个 JSON 对象。 */
    private Verdict parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        Matcher m = JSON_OBJECT.matcher(raw);
        if (!m.find()) return null;
        try {
            JSONObject o = JSON.parseObject(m.group());
            String v = o.getString("verdict");
            if (v == null) return null;
            v = v.trim().toUpperCase();
            if (!"PASS".equals(v) && !"FAIL".equals(v)) return null;
            return new Verdict(v, o.getString("reason"));
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
