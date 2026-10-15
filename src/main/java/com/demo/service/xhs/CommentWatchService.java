package com.demo.service.xhs;

import com.demo.config.AiChatProperties;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.demo.agent.XhsPublishMcpTool;
import com.demo.entity.XhsBlackCommentEntity;
import com.demo.mapper.XhsBlackCommentMapper;
import com.demo.mcp.McpClientManager;
import com.demo.service.tools.ReportScheduleService;
import com.demo.service.tools.SendEmailService;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 小红书评论区黑评监控。
 * <p>
 * 每天定时（cron 可配）扫描自己账号最近笔记的评论区：
 * 关键词初筛 → DeepSeek AI 确认黑评 → 去重（xhs_black_comments 表）→ 发邮件提醒。
 * 调度复用 {@link ReportScheduleService} 现有线程池。
 */
@Slf4j
@Component
public class CommentWatchService {

    @Resource
    private AiChatProperties aiChat;

    private static final String TOOL_MY_PROFILE = "xhs_get_my_profile";
    private static final String TOOL_FEED_DETAIL = "xhs_get_feed_detail";
    private static final String JOB_ID = "xhs-comment-watch";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 黑评关键词初筛（命中后交给 AI 确认） */
    private static final List<String> BLACK_KEYWORDS = List.of(
            "丑", "好丑", "太丑", "丑爆", "垃圾", "辣鸡", "恶心", "骗", "骗人",
            "差评", "太差", "质量差", "差劲", "劣质", "难用", "不好用", "坑",
            "烂", "滚", "傻", "蠢", "土", "难看", "难听", "退款", "退货",
            "拉黑", "举报", "什么玩意", "无语", "敷衍", "糊弄", "假", "浪费",
            "智商税", "割韭菜", "翻车", "暴雷", "避雷", "踩雷", "不值", "后悔",
            "sb", "SB", "脑残", "弱智", "傻逼", "智障", "差"
    );

    @Resource
    private McpClientManager mcpClientManager;
    @Resource
    private XhsPublishMcpTool xhsPublishMcpTool;
    @Resource
    private SendEmailService sendEmailService;
    @Resource
    private XhsBlackCommentMapper blackCommentMapper;
    @Resource
    private ReportScheduleService reportScheduleService;

    @Value("${life.xhs.comment-watch.enabled:false}")
    private boolean enabled;
    @Value("${life.xhs.comment-watch.cron:0 30 9 * * *}")
    private String cron;
    @Value("${life.xhs.comment-watch.email:}")
    private String notifyEmail;
    @Value("${life.xhs.comment-watch.max-notes:10}")
    private int maxNotes;

    private OpenAiChatModel aiModel;

    @PostConstruct
    public void init() {
        if (enabled && notifyEmail != null && !notifyEmail.isBlank()) {
            reportScheduleService.scheduleRecurring(JOB_ID, this::scanNow, cron);
            log.info("📬 黑评监控已开启: cron={} email={}", cron, notifyEmail);
        } else {
            log.info("📬 黑评监控未开启（enabled={} email={}）", enabled, notifyEmail);
        }
    }

    /** 监控状态 */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled && notifyEmail != null && !notifyEmail.isBlank());
        m.put("cron", cron);
        m.put("email", notifyEmail);
        m.put("maxNotes", maxNotes);
        String nextRun = "";
        try {
            ZonedDateTime next = CronExpression.parse(cron).next(ZonedDateTime.now(ZONE));
            if (next != null) nextRun = next.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        } catch (Exception e) {
            log.warn("📬 计算下次扫描时间失败: {}", e.getMessage());
        }
        m.put("nextRun", nextRun);
        return m;
    }

    /** 手动触发扫描：关键词初筛 → AI 确认 → 去重 → 发邮件 */
    public String scanNow() {
        // 登录检查（改走 get_my_profile 探测，check_login_status 服务端固定超时）
        if (xhsPublishMcpTool.probeLogin().state() == XhsPublishMcpTool.LoginProbe.NOT_LOGGED_IN) {
            return "❌ 小红书未登录，跳过黑评扫描";
        }

        List<Map<String, String>> notes = fetchMyNotes();
        if (notes.isEmpty()) {
            return "⚠️ 账号暂无笔记，跳过扫描";
        }

        // 关键词初筛
        List<Map<String, String>> candidates = new ArrayList<>();
        for (Map<String, String> note : notes) {
            List<Map<String, String>> comments = fetchNoteComments(note.get("id"), note.get("xsec"));
            for (Map<String, String> c : comments) {
                String content = c.get("content");
                if (content == null || content.isBlank()) continue;
                if (containsBlackKeyword(content)) {
                    Map<String, String> cand = new LinkedHashMap<>();
                    cand.put("noteId", note.get("id"));
                    cand.put("noteTitle", note.get("title"));
                    cand.put("content", content);
                    cand.put("nickname", c.get("nickname"));
                    cand.put("commentId", c.get("commentId"));
                    candidates.add(cand);
                }
            }
        }
        if (candidates.isEmpty()) {
            log.info("📬 本次扫描未发现疑似黑评（{} 篇笔记）", notes.size());
            return "✅ 扫描完成，未发现黑评（共 " + notes.size() + " 篇笔记）";
        }

        // AI 确认
        List<Map<String, String>> confirmed = aiConfirmBlack(candidates);

        // 去重 + 入库
        List<Map<String, String>> newBlack = new ArrayList<>();
        for (Map<String, String> c : confirmed) {
            String commentId = c.get("commentId");
            if (commentId == null || commentId.isBlank()) continue;
            if (blackCommentMapper.findByCommentId(commentId) != null) continue;
            blackCommentMapper.insert(new XhsBlackCommentEntity(c.get("noteId"), commentId, c.get("content")));
            newBlack.add(c);
        }
        if (newBlack.isEmpty()) {
            log.info("📬 疑似黑评 {} 条均已提醒过，跳过邮件", confirmed.size());
            return "✅ 扫描完成，无新增黑评（疑似 " + candidates.size() + " 条，均已知）";
        }

        // 发邮件
        String subject = "📢 小红书黑评提醒：" + newBlack.size() + " 条新黑评";
        String body = buildMailBody(newBlack);
        String sendResult = sendEmailService.sendEmail(notifyEmail, subject, body);
        log.info("📬 黑评邮件发送结果: {}", sendResult);
        return "📬 发现 " + newBlack.size() + " 条新黑评，邮件已发送\n\n" + body;
    }

    // ======================== 内部方法 ========================

    /** 拉取自己账号最近笔记（id/xsec/title） */
    private List<Map<String, String>> fetchMyNotes() {
        List<Map<String, String>> notes = new ArrayList<>();
        String raw = mcpClientManager.callTool(TOOL_MY_PROFILE, new HashMap<>());
        if (raw == null || raw.isBlank()) return notes;
        try {
            JSONObject root = JSON.parseObject(raw);
            JSONArray feeds = root.getJSONArray("feeds");
            if (feeds == null || feeds.isEmpty()) return notes;
            int limit = Math.min(maxNotes > 0 ? maxNotes : 10, feeds.size());
            for (int i = 0; i < limit; i++) {
                JSONObject f = feeds.getJSONObject(i);
                JSONObject nc = f.getJSONObject("noteCard");
                Map<String, String> note = new LinkedHashMap<>();
                note.put("id", f.getString("id"));
                note.put("xsec", f.getString("xsecToken"));
                note.put("title", nc != null && nc.getString("displayTitle") != null ? nc.getString("displayTitle") : "");
                notes.add(note);
            }
            log.info("📬 账号笔记 {} 篇（扫描前 {}）", feeds.size(), limit);
        } catch (Exception e) {
            log.error("📬 解析 my_profile 失败: {}", e.getMessage(), e);
        }
        return notes;
    }

    /** 拉取单篇笔记评论（data.comments.list） */
    private List<Map<String, String>> fetchNoteComments(String noteId, String xsec) {
        List<Map<String, String>> comments = new ArrayList<>();
        if (noteId == null || noteId.isBlank() || xsec == null || xsec.isBlank()) return comments;
        try {
            Map<String, Object> args = new HashMap<>();
            args.put("feed_id", noteId);
            args.put("xsec_token", xsec);
            String raw = mcpClientManager.callTool(TOOL_FEED_DETAIL, args);
            if (raw == null || raw.isBlank()) return comments;
            JSONObject root = JSON.parseObject(raw);
            JSONObject data = root.getJSONObject("data");
            JSONObject commentsObj = data != null ? data.getJSONObject("comments") : null;
            JSONArray list = commentsObj != null ? commentsObj.getJSONArray("list") : null;
            if (list == null) return comments;
            for (int i = 0; i < list.size(); i++) {
                JSONObject c = list.getJSONObject(i);
                Map<String, String> cm = new LinkedHashMap<>();
                String content = c.getString("content");
                cm.put("content", content != null ? content : c.getString("text"));
                cm.put("commentId", c.getString("comment_id") != null ? c.getString("comment_id") : c.getString("id"));
                JSONObject user = c.getJSONObject("user");
                cm.put("nickname", user != null && user.getString("nickname") != null ? user.getString("nickname") : "");
                comments.add(cm);
            }
            log.debug("📬 笔记 {} 评论 {} 条", noteId, comments.size());
        } catch (Exception e) {
            log.warn("📬 拉取笔记 {} 评论失败: {}", noteId, e.getMessage());
        }
        return comments;
    }

    private boolean containsBlackKeyword(String content) {
        String c = content.toLowerCase();
        for (String kw : BLACK_KEYWORDS) {
            if (c.contains(kw.toLowerCase())) return true;
        }
        return false;
    }

    /** AI 确认哪些候选是真正恶意黑评（失败则退回全部候选） */
    private List<Map<String, String>> aiConfirmBlack(List<Map<String, String>> candidates) {
        if (aiChat == null || !aiChat.hasApiKey()) {
            log.warn("📬 未配置文本模型 key，跳过 AI 确认，按关键词直接视为黑评");
            return candidates;
        }
        try {
            OpenAiChatModel model = getAiModel();
            StringBuilder sb = new StringBuilder();
            sb.append("你是小红书博主助手。以下是一组用户评论，请判断哪些属于「负面评论」（对内容/质量明显不满、差评、恶意贬低、攻击性语言，值得博主关注处理）。")
              .append("正常好评、中性、与内容无关的灌水不算。\n\n评论列表：\n");
            for (int i = 0; i < candidates.size(); i++) {
                sb.append(i).append(". ").append(candidates.get(i).get("content")).append("\n");
            }
            sb.append("\n只返回恶意黑评的下标数组，如 [0,3]；没有则返回 []。不要输出任何其它内容。");
            var response = model.chat(ChatRequest.builder()
                    .messages(List.of(UserMessage.from(sb.toString())))
                    .build());
            String text = response.aiMessage().text() != null ? response.aiMessage().text().trim() : "";
            List<Integer> idxs = parseIndexList(text);
            List<Map<String, String>> out = new ArrayList<>();
            for (Integer i : idxs) {
                if (i >= 0 && i < candidates.size()) out.add(candidates.get(i));
            }
            log.info("📬 AI 确认黑评 {} / {} 条", out.size(), candidates.size());
            return out;
        } catch (Exception e) {
            log.error("📬 AI 确认失败，按关键词直接视为黑评: {}", e.getMessage());
            return candidates;
        }
    }

    private OpenAiChatModel getAiModel() {
        if (aiModel == null) {
            aiModel = aiChat.chatModelBuilder()
                    .temperature(0.1)
                    .maxRetries(1)
                    .build();
        }
        return aiModel;
    }

    /** 从 AI 返回文本提取下标数字（[0,3] → [0,3]） */
    private List<Integer> parseIndexList(String text) {
        List<Integer> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = Pattern.compile("\\d+").matcher(text);
        while (m.find()) out.add(Integer.parseInt(m.group()));
        return out;
    }

    private String buildMailBody(List<Map<String, String>> blackList) {
        StringBuilder sb = new StringBuilder();
        sb.append("你发布的小红书笔记收到了恶意黑评，请及时处理：\n\n");
        Map<String, List<Map<String, String>>> byNote = new LinkedHashMap<>();
        for (Map<String, String> c : blackList) {
            byNote.computeIfAbsent(String.valueOf(c.get("noteTitle")), k -> new ArrayList<>()).add(c);
        }
        for (Map.Entry<String, List<Map<String, String>>> e : byNote.entrySet()) {
            sb.append("【").append(e.getKey()).append("】\n");
            int i = 1;
            for (Map<String, String> c : e.getValue()) {
                sb.append("  ").append(i++).append(". ");
                if (c.get("nickname") != null && !c.get("nickname").isBlank()) {
                    sb.append("@").append(c.get("nickname")).append("：");
                }
                sb.append(c.get("content")).append("\n");
            }
            sb.append("\n");
        }
        sb.append("可登录小红书创作者中心删除评论或拉黑该用户。");
        return sb.toString();
    }
}
