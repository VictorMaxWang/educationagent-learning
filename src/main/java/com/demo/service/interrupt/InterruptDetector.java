package com.demo.service.interrupt;

import com.demo.config.AiChatProperties;
import jakarta.annotation.Resource;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 三层智能中断意图识别器。
 * <p>
 * 替代原来单一的 {@code isInterruptCommand()} 关键词匹配，采用三级漏斗：
 * <pre>
 *   ① 快速规则层（关键词精确匹配，0ms）     → 拦截高频短句 "别说了"
 *   ② 轻量语义层（正则/相似度，<0.1ms）    → 覆盖同义打断句式 "能不能别说了"
 *   ③ LLM 上下文校验（DeepSeek 分类，~1s）  → 处理长句/隐性切换意图 "这个话题先放一放"
 * </pre>
 * 三级逐级降级：上一层命中直接返回，不进入下一层。
 * 第③层仅在用户有正在执行的任务时才触发，避免浪费 token。
 */
@Slf4j
@Component
public class InterruptDetector {

    @Resource
    private AiChatProperties aiChat;

    private final InterruptManager interruptManager;
    private final RestTemplate restTemplate;

    /** 是否启用 LLM 语义分类层（默认关闭，需显式开启） */
    @Value("${interrupt.llm-detection.enabled:false}")
    private boolean llmDetectionEnabled;

    // ======================== 第 1 层：关键词 ========================

    private static final Set<String> KEYWORDS = Set.of(
            "别说了", "别讲了", "打断", "停", "停下", "暂停", "闭嘴", "住口",
            "stop", "Stop", "STOP",
            "好了好了", "行了", "够了", "别");

    // ======================== 第 2 层：正则模式 ========================

    private static final List<Pattern> PATTERNS = List.of(
            // 直接打断：能不能别说了、可以停了、先别讲、先停一下
            Pattern.compile("(能不能|可以|先).{0,4}(别|不要|不用).{0,4}(说|讲|问|提|聊)"),
            // 停止指令：停一下、暂停掉
            Pattern.compile("(先|请|给我)?(停|暂停)(下|止|掉)?"),
            // 厌烦/够了：好了好了、行了行、够了够了、知道了知道了
            Pattern.compile("(好|行|够|知道).{0,2}(了|啦).{0,3}(好|行|够|知道).{0,2}(了|啦)?"),
            // 算了：算了、算了吧、拉倒吧
            Pattern.compile("算(了|啦|了吧)|拉倒"),
            // 话题切换：换个话题、换个问题、不说这个了
            Pattern.compile("(换|改|重新).{0,4}(话题|问题|个|一个|方向)"),
            Pattern.compile("不.{0,3}(说|讲|问|提|聊)(这个|那个|了|啦)"),
            // 软停止：等一下、稍等、先这样吧、就到这
            Pattern.compile("(等|慢).{0,2}(一下|一会|会儿)"),
            Pattern.compile("先这样(吧|子)?|就到这|先到这"),
            // 下一题/换一个
            Pattern.compile("(下|换).{0,2}(一个|个|题|问题|话题)"),
            // 不问了/不说了/不谈了
            Pattern.compile("不.{0,3}(问|说|谈|讲|聊)(了|啦)"),
            // 英文
            Pattern.compile("\\b(?i)(shut\\s*up|stop|enough|cancel|nevermind|forget\\s*it)\\b")
    );

    // ======================== 第 3 层：LLM 缓存 ========================

    /** 短时缓存 LLM 分类结果，避免重复调用 */
    private final Map<String, Boolean> llmCache = new ConcurrentHashMap<>();

    // ======================== 构造 ========================

    public InterruptDetector(InterruptManager interruptManager, RestTemplate restTemplate) {
        this.interruptManager = interruptManager;
        this.restTemplate = restTemplate;
    }

    // ======================== 唯一入口 ========================

    /**
     * 三层智能中断检测。
     * 仅在用户有正在执行的任务时才进行检测（无事可打断时跳过）。
     *
     * @param text   用户消息原文
     * @param userId 用户 ID
     * @return true=用户想打断当前输出
     */
    public boolean detect(String text, String userId) {
        if (text == null || text.isBlank()) return false;
        String content = text.trim();

        // 第 1 层：关键词精确匹配（0ms，优先拦截高频短句）
        if (matchKeyword(content)) {
            log.debug("🔍 [{}] 第1层(关键词)命中: {}", userId, content);
            return true;
        }

        // 第 2 层：正则语义匹配（<0.1ms，覆盖同义变体）
        if (matchPattern(content)) {
            log.debug("🔍 [{}] 第2层(正则)命中: {}", userId, content);
            return true;
        }

        // 第 3 层仅在用户有任务执行时触发（避免浪费 token）
        if (!llmDetectionEnabled || !interruptManager.isProcessing(userId)) {
            return false;
        }

        // 第 3 层：LLM 上下文语义判断（~1s，处理长句隐性切换）
        if (matchLlm(content, userId)) {
            log.info("🔍 [{}] 第3层(LLM)命中: {}", userId, content);
            return true;
        }

        return false;
    }

    // ======================== 第 1 层：关键词 ========================

    private boolean matchKeyword(String content) {
        // 完全匹配
        if (KEYWORDS.contains(content)) return true;
        // 长度 ≤6 且包含关键词
        if (content.length() <= 6) {
            for (String word : KEYWORDS) {
                if (content.contains(word)) return true;
            }
        }
        return false;
    }

    // ======================== 第 2 层：正则 ========================

    private boolean matchPattern(String content) {
        for (Pattern pattern : PATTERNS) {
            if (pattern.matcher(content).find()) {
                return true;
            }
        }
        return false;
    }

    // ======================== 第 3 层：LLM ========================

    /** 直接调用 DeepSeek API 做二分类，不经过 Agent 上下文 */
    private boolean matchLlm(String content, String userId) {
        // 缓存命中检查
        Boolean cached = llmCache.get(content);
        if (cached != null) return cached;

        try {
            JSONObject request = new JSONObject();
            aiChat.configureRequest(request);
            request.put("messages", List.of(
                    Map.of("role", "system", "content",
                            "你是一个意图分类器，只做一件事：判断用户消息是否意图中断/停止 AI 的当前输出。\n" +
                                    "中断意图包括：不想听了、切换话题、取消当前内容、觉得够了等。\n" +
                                    "普通提问、追问、补充信息不算中断。\n" +
                                    "只需回复 true 或 false，不要任何其他内容。"),
                    Map.of("role", "user", "content", content)
            ));
            request.put("max_tokens", 5);
            request.put("temperature", 0);
            request.put("stream", false);

            String url = aiChat.getCompletionsUrl();
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setBearerAuth(aiChat.getApiKey());
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

            org.springframework.http.HttpEntity<String> entity =
                    new org.springframework.http.HttpEntity<>(request.toJSONString(), headers);

            long start = System.currentTimeMillis();
            String resp = restTemplate.postForObject(url, entity, String.class);
            long elapsed = System.currentTimeMillis() - start;

            if (resp != null) {
                JSONObject json = JSONObject.parseObject(resp);
                String answer = json.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                        .trim().toLowerCase();
                boolean result = answer.equals("true") || answer.startsWith("true");
                llmCache.put(content, result);
                log.debug("🔍 [{}] 第3层(LLM) → {} ({}ms): {}", userId, result, elapsed, content);
                return result;
            }
        } catch (Exception e) {
            log.warn("🔍 [{}] 第3层(LLM)调用失败: {}", userId, e.getMessage());
        }
        return false;
    }

    /** 清理 LLM 缓存（可在会话结束时调用） */
    public void clearCache() {
        llmCache.clear();
    }
}
