package com.demo.router;

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
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * L3 层：LLM 兜底意图分类。只在 L1、L2 都没把握时才调用，是最后一道保险。
 *
 * <p>与主对话共用文本模型连接配置，保留路由自己的低 token 限额、超时与熔断策略。
 * 思考默认关闭，确保很短的输出额度也能返回分类正文。
 *
 * <p>成本控制（对应方案书的"LLM 路由耗 token → prompt &lt; 100 token"风险）：
 * <ul>
 *   <li>prompt 只有一行标签清单，不解释、不给例子；</li>
 *   <li>{@code max_tokens=10}、{@code temperature=0}；</li>
 *   <li>用户消息截断到 200 字；</li>
 *   <li>默认<b>关闭</b>（{@code router.llm.enabled=false}），需要时再开。</li>
 * </ul>
 *
 * <p>容错：熔断打开 / 超时 / HTTP 异常 / 返回不可解析 → 一律
 * {@link RouteResult#fallback}，绝不抛异常，绝不让路由拖垮主对话。
 */
@Slf4j
@Component
public class LlmRouter implements PreRouter {

    @Resource
    private AiChatProperties aiChat;

    /** 标签清单由枚举生成，避免手写漏项。 */
    private static final String LABEL_LIST;

    static {
        StringBuilder sb = new StringBuilder();
        for (IntentLabel v : IntentLabel.values()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(v.name());
        }
        LABEL_LIST = sb.toString();
    }

    private static final String SYSTEM_PROMPT =
            "你是意图分类器。只输出一个英文标签，不要解释、不要标点。可选标签：" + LABEL_LIST;

    /** 从模型输出里抠出第一个全大写标签。 */
    private static final Pattern LABEL_PATTERN = Pattern.compile("[A-Z][A-Z_]{1,15}");

    /** 用户消息截断长度。 */
    private static final int MAX_MSG_CHARS = 200;

    private final RouterProperties properties;
    private final RestTemplate routerRestTemplate;
    private final CircuitBreaker circuitBreaker;

    public LlmRouter(RouterProperties properties,
                     @Qualifier("routerRestTemplate") RestTemplate routerRestTemplate,
                     @Qualifier("llmRouterCircuitBreaker") CircuitBreaker circuitBreaker) {
        this.properties = properties;
        this.routerRestTemplate = routerRestTemplate;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public boolean isEnabled() {
        return properties.isEnabled() && properties.getLlm().isEnabled()
                && aiChat != null && aiChat.hasApiKey();
    }

    @Override
    public String layerName() {
        return "L3";
    }

    @Override
    public RouteResult route(String userId, String message, MessageContext ctx) {
        long start = System.nanoTime();

        if (message == null || message.isBlank()) {
            return RouteResult.fallback("L3:blank", elapsedMs(start));
        }
        if (!circuitBreaker.allowRequest()) {
            return RouteResult.fallback("L3:circuit-open", elapsedMs(start));
        }

        String raw;
        int tokens;
        try {
            JSONObject usage = new JSONObject();
            raw = callDeepSeek(message, usage);
            tokens = usage.getIntValue("total_tokens", 0);
            circuitBreaker.recordSuccess();
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            log.warn("[{}] L3 意图判定失败 → FALLBACK：{}", userId, e.toString());
            return RouteResult.fallback("L3:error", elapsedMs(start));
        }

        IntentLabel label = parseLabel(raw);
        if (label == null) {
            // 模型输出了预期之外的标签——按方案书要求走 FALLBACK，不猜
            log.info("[{}] L3 返回非预期标签 [{}] → FALLBACK", userId, safe(raw));
            return RouteResult.fallback("L3:unexpected=" + safe(raw), elapsedMs(start));
        }

        RouteDecision decision = label == IntentLabel.CHAT ? RouteDecision.CHAT : RouteDecision.TOOL;
        return RouteResult.builder()
                .decision(decision)
                .intent(label)
                .matchedRule("L3:llm:" + label)
                .confidence(0.6)
                .costMs(elapsedMs(start))
                .tools(label.tools())
                .llmIntent(label.name())
                .tokenUsed(tokens)
                .layer(layerName())
                .features(Map.of("rawOutput", safe(raw)))
                .build();
    }

    // ======================== 内部实现 ========================

    private String callDeepSeek(String message, JSONObject usageOut) {
        RouterProperties.Llm cfg = properties.getLlm();

        JSONArray messages = new JSONArray();
        JSONObject sys = new JSONObject();
        sys.put("role", "system");
        sys.put("content", SYSTEM_PROMPT);
        messages.add(sys);

        JSONObject user = new JSONObject();
        user.put("role", "user");
        user.put("content", truncate(message, MAX_MSG_CHARS));
        messages.add(user);

        JSONObject body = new JSONObject();
        aiChat.configureRequest(body);
        body.put("temperature", cfg.getTemperature());
        body.put("max_tokens", 10);
        body.put("messages", messages);

        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", "Bearer " + aiChat.getApiKey());
        headers.add("Content-Type", "application/json");

        ResponseEntity<String> resp = routerRestTemplate.postForEntity(
                aiChat.getCompletionsUrl(), new HttpEntity<>(body.toJSONString(), headers), String.class);

        JSONObject result = JSON.parseObject(resp.getBody());
        if (result == null) {
            throw new IllegalStateException("空响应");
        }
        JSONObject usage = result.getJSONObject("usage");
        if (usage != null) {
            usageOut.putAll(usage);
        }
        return result.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content");
    }

    /** 宽松解析模型输出；无法映射到已知标签返回 null。 */
    private IntentLabel parseLabel(String raw) {
        if (raw == null || raw.isBlank()) return null;
        Matcher m = LABEL_PATTERN.matcher(raw);
        while (m.find()) {
            IntentLabel label = IntentLabel.parse(m.group());
            if (label != null) return label;
        }
        return null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String safe(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() > 40 ? t.substring(0, 40) + "…" : t;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
