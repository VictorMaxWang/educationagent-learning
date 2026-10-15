package com.demo.service.xhs;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.demo.agent.XhsPublishMcpTool;
import com.demo.entity.XhsHotScheduleEntity;
import com.demo.mapper.XhsHotScheduleMapper;
import com.demo.mcp.McpClientManager;
import com.demo.service.tools.ReportScheduleService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 小红书热门视频每周汇总发布。
 * <p>
 * 定时调度复用 {@link ReportScheduleService} 现有线程池（不新造线程池），
 * 通过 {@code scheduleRecurring("xhs-hot-video", ...)} 注册每周任务。
 * 内容：拉取首页 feeds → 筛视频类 → 按互动量排序 → AI 生成封面 → 汇总图文发布。
 * <p>
 * 合规：不截取、不使用任何他人图片，封面一律通义万相 AI 生成。
 */
@Slf4j
@Component
public class HotVideoReportService {

    private static final String TOOL_LIST_FEEDS = "xhs_list_feeds";
    private static final String JOB_ID = "xhs-hot-video";
    private static final String COVER_DIR = "data/xhs_publish";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Resource
    private McpClientManager mcpClientManager;
    @Resource
    private XhsPublishMcpTool xhsPublishMcpTool;
    @Resource
    private XhsHotScheduleMapper scheduleMapper;
    @Resource
    private ReportScheduleService reportScheduleService;

    /** 防并发：定时触发与手动发布互斥 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    @PostConstruct
    public void init() {
        XhsHotScheduleEntity cfg = getOrCreateConfig();
        log.info("📕 热门视频定时发布配置: enabled={} days={} time={} max_videos={}",
                cfg.getEnabled(), cfg.getDays(), cfg.getTime(), cfg.getMaxVideos());
        applySchedule(cfg);
    }

    // ======================== 配置 ========================

    public XhsHotScheduleEntity getOrCreateConfig() {
        List<XhsHotScheduleEntity> rows = scheduleMapper.selectList(null);
        if (!rows.isEmpty()) {
            XhsHotScheduleEntity c = rows.get(0);
            // 自愈：标签「本周热门」无法被 XHS 标签联想命中 → 发布失败，替换为稳定标签
            String tags = c.getTags();
            if (tags != null && tags.contains("本周热门")) {
                c.setTags(tags.replace("本周热门", "跟风"));
                scheduleMapper.updateById(c);
                log.info("📕 已自愈标签: {} → {}", tags, c.getTags());
            }
            return c;
        }
        XhsHotScheduleEntity c = new XhsHotScheduleEntity();
        c.setEnabled(0);
        c.setDays("3");
        c.setTime("14:00");
        c.setMaxVideos(5);
        c.setTags("#热门视频 #跟风");
        scheduleMapper.insert(c);
        log.info("📕 已初始化默认热门视频定时配置 (enabled=0, 周三14:00)");
        return c;
    }

    /** 周几+时分 → Spring 6 段 cron：0 <min> <hour> * * <days>（1=周一..7=周日） */
    private String toCron(String days, String time) {
        String t = (time == null || time.isBlank()) ? "14:00" : time.trim();
        String[] parts = t.split(":");
        int hour = Integer.parseInt(parts[0].trim());
        int minute = Integer.parseInt(parts[1].trim());
        String d = (days == null || days.isBlank()) ? "3" : days.trim();
        return String.format("0 %d %d * * %s", minute, hour, d);
    }

    /** 当前状态（含下次执行时间） */
    public Map<String, Object> status() {
        XhsHotScheduleEntity c = getOrCreateConfig();
        String cron = toCron(c.getDays(), c.getTime());
        String nextRun = "";
        try {
            // 用 ZonedDateTime：带星期字段的 cron 对 Instant 会抛 Unsupported field: DayOfWeek
            ZonedDateTime next = CronExpression.parse(cron).next(ZonedDateTime.now(ZONE));
            if (next != null) {
                nextRun = next.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            }
        } catch (Exception e) {
            log.warn("📕 计算下次执行时间失败: {}", e.getMessage());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", c.getEnabled() != null && c.getEnabled() == 1);
        m.put("days", parseDays(c.getDays()));
        m.put("time", c.getTime() != null ? c.getTime() : "14:00");
        m.put("cron", cron);
        m.put("nextRun", nextRun);
        return m;
    }

    /** 设置调度（周几+时分+开关），持久化并重排 */
    public Map<String, Object> setSchedule(List<Integer> days, String time, Boolean enabled) {
        XhsHotScheduleEntity c = getOrCreateConfig();
        if (days != null && !days.isEmpty()) {
            c.setDays(days.stream().sorted().map(String::valueOf).collect(Collectors.joining(",")));
        }
        if (time != null && !time.isBlank()) c.setTime(time.trim());
        if (enabled != null) c.setEnabled(enabled ? 1 : 0);
        scheduleMapper.updateById(c);
        applySchedule(c);
        log.info("📕 热门视频调度已更新: days={} time={} enabled={}", c.getDays(), c.getTime(), c.getEnabled());
        return status();
    }

    /** 按配置注册/取消定时任务（复用 ReportScheduleService 线程池） */
    private void applySchedule(XhsHotScheduleEntity c) {
        reportScheduleService.cancelRecurring(JOB_ID);
        if (c.getEnabled() != null && c.getEnabled() == 1) {
            reportScheduleService.scheduleRecurring(JOB_ID, () -> publishNow(), toCron(c.getDays(), c.getTime()));
        }
    }

    private List<Integer> parseDays(String days) {
        List<Integer> out = new ArrayList<>();
        if (days == null || days.isBlank()) return out;
        for (String s : days.split(",")) {
            try {
                out.add(Integer.parseInt(s.trim()));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    // ======================== 内容 ========================

    /** 预览：解析首页热门视频（不发布），只取最热门 10 条 */
    public List<Map<String, Object>> preview() {
        List<Map<String, Object>> videos = fetchHotVideos();
        return videos.size() > 10 ? videos.subList(0, 10) : videos;
    }

    /** 立即执行完整发布（定时任务与手动共用） */
    public String publishNow() {
        if (!running.compareAndSet(false, true)) {
            return "⏳ 已有发布任务执行中，跳过本次";
        }
        try {
            // 1. 登录检查（改走 get_my_profile 探测，check_login_status 服务端固定超时）
            if (xhsPublishMcpTool.probeLogin().state() == XhsPublishMcpTool.LoginProbe.NOT_LOGGED_IN) {
                return "❌ 小红书未登录，跳过自动发布";
            }

            // 2. 拉取首页热门视频，跟风 TOP1
            List<Map<String, Object>> videos = fetchHotVideos();
            if (videos.isEmpty()) {
                log.warn("📕 定时跟风发布跳过：拉取首页视频失败或暂无视频（已重试 3 次）");
                return "⚠️ 拉取首页视频失败或暂无视频，跳过本次发布";
            }
            Map<String, Object> top = videos.get(0);
            String topic = extractTopic(String.valueOf(top.get("title")), 24);    // 封面/正文用完整主题
            String titleTopic = extractTopic(String.valueOf(top.get("title")), 10); // 标题用短主题（小红书标题限长）

            // 3. 生成纯文字讨论卡片封面（Java2D 渲染，无 AI 成本、观感稳定，不截取他人图片）
            String coverPath = generateTextCover(topic);
            if (coverPath == null) {
                return "❌ 文字封面生成失败，跳过发布（避免无图发布）";
            }
            List<String> coverPaths = List.of(coverPath);

            // 4. 组跟风文案
            XhsHotScheduleEntity cfg = getOrCreateConfig();
            String title = "🔥跟风 · " + titleTopic;
            String desc = buildFollowDesc(top);
            String tags = cfg.getTags() != null && !cfg.getTags().isBlank() ? cfg.getTags() : "#热门视频 #跟风";

            // 5. 发布
            log.info("📕 跟风热门视频发布: topic={} 封面 {} 张", topic, coverPaths.size());
            String result = xhsPublishMcpTool.publishXhsNote(title, desc, tags, String.join(",", coverPaths));
            log.info("📕 跟风热门视频发布结果: {}", result);
            return result;
        } finally {
            running.set(false);
        }
    }

    /** 拉取首页 feeds（带重试：浏览器自动化瞬时失败常见，重试 3 次） */
    private List<Map<String, Object>> fetchHotVideos() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            List<Map<String, Object>> videos = tryFetchHotVideos();
            if (!videos.isEmpty()) return videos;
            log.warn("📕 首页视频获取失败（第 {}/3 次），2 秒后重试...", attempt);
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return List.of();
    }

    /** 单次拉取首页 feeds，筛视频类并按互动量排序 */
    private List<Map<String, Object>> tryFetchHotVideos() {
        String raw = mcpClientManager.callTool(TOOL_LIST_FEEDS, new HashMap<>());
        if (raw == null || raw.isBlank()) {
            log.warn("📕 xhs_list_feeds 返回为空");
            return List.of();
        }
        // MCP 返回错误文本（如"获取Feeds列表失败..."）而非 JSON → 直接判失败重试
        if (!raw.trim().startsWith("{")) {
            log.warn("📕 xhs_list_feeds 返回非 JSON: {}", raw.length() > 120 ? raw.substring(0, 120) : raw);
            return List.of();
        }
        try {
            JSONObject root = JSON.parseObject(raw);
            JSONArray feeds = root.getJSONArray("feeds");
            if (feeds == null || feeds.isEmpty()) return List.of();

            List<Map<String, Object>> videos = new ArrayList<>();
            for (int i = 0; i < feeds.size(); i++) {
                JSONObject nc = feeds.getJSONObject(i).getJSONObject("noteCard");
                if (nc == null) continue;
                if (!"video".equals(nc.getString("type"))) continue;

                JSONObject user = nc.getJSONObject("user");
                JSONObject inter = nc.getJSONObject("interactInfo");
                String title = nc.getString("displayTitle");
                String author = user != null && user.getString("nickname") != null ? user.getString("nickname") : "";
                String liked = inter != null && inter.getString("likedCount") != null ? inter.getString("likedCount") : "";
                String collected = inter != null && inter.getString("collectedCount") != null ? inter.getString("collectedCount") : "";
                long score = parseCount(liked) + parseCount(collected) * 2;

                Map<String, Object> v = new LinkedHashMap<>();
                v.put("title", title);
                v.put("author", author);
                v.put("likedCount", liked);
                v.put("collectedCount", collected);
                v.put("score", score);
                videos.add(v);
            }
            // 按互动量降序
            videos.sort((a, b) -> Long.compare((Long) b.get("score"), (Long) a.get("score")));
            log.info("📕 首页视频 {} 条", videos.size());
            return videos;
        } catch (Exception e) {
            log.error("📕 解析首页 feeds 失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** "10万+" / "1.2万" / "" → 数值 */
    private long parseCount(String s) {
        if (s == null || s.isBlank()) return 0;
        String t = s.replace("+", "").trim();
        try {
            if (t.contains("万")) {
                return (long) (Double.parseDouble(t.replace("万", "")) * 10000);
            }
            return (long) Double.parseDouble(t);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 从视频标题提取跟风主题（去括号、压缩空白、按 maxLen 截断） */
    private String extractTopic(String title, int maxLen) {
        if (title == null || title.isBlank() || "null".equalsIgnoreCase(title)) return "热门";
        String t = title.replaceAll("[【】\\[\\]（）()「」《》]+", "").replaceAll("\\s+", " ").trim();
        if (t.length() > maxLen) t = t.substring(0, maxLen) + "…";
        return t.isEmpty() ? "热门" : t;
    }

    /**
     * 生成纯文字讨论卡片封面（Java2D 渲染）。
     * 暖渐变底 + 大号话题字 + 评论区引导语，用于引发讨论，观感稳定不丑。
     */
    private String generateTextCover(String topic) {
        try {
            Path dir = Path.of(COVER_DIR);
            Files.createDirectories(dir);
            int w = 720, h = 1280;
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // 背景暖渐变
            g.setPaint(new GradientPaint(0, 0, new Color(255, 245, 232), 0, h, new Color(255, 210, 160)));
            g.fillRect(0, 0, w, h);

            Font font = loadChineseFont();

            // 顶部"跟风话题"标签
            g.setFont(font.deriveFont(Font.BOLD, 40f));
            String tag = "跟风话题";
            FontMetrics fm = g.getFontMetrics();
            int tagW = fm.stringWidth(tag);
            g.setColor(new Color(235, 118, 50));
            g.fillRoundRect((w - tagW) / 2 - 44, 96, tagW + 88, 84, 42, 42);
            g.setColor(Color.WHITE);
            g.drawString(tag, (w - tagW) / 2, 96 + 55);

            // 主体话题大字（居中换行）
            drawCenteredWrap(g, font.deriveFont(Font.BOLD, 76f), topic, w - 120, 480, 150, w);

            // 底部引导语
            g.setFont(font.deriveFont(Font.BOLD, 46f));
            g.setColor(new Color(235, 118, 50));
            String prompt = "你怎么看？评论区聊聊！";
            fm = g.getFontMetrics();
            g.drawString(prompt, (w - fm.stringWidth(prompt)) / 2, h - 220);

            g.dispose();
            Path f = dir.resolve("xhs_follow_cover_" + System.currentTimeMillis() + ".png");
            ImageIO.write(img, "png", f.toFile());
            log.info("📕 文字封面已生成: {}", f.toAbsolutePath());
            return f.toAbsolutePath().toString();
        } catch (Exception e) {
            log.error("📕 生成文字封面失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /** 选择可显示中文的字体（Windows 微软雅黑/黑体，兜底 SansSerif） */
    private Font loadChineseFont() {
        String[] names = {"Microsoft YaHei", "微软雅黑", "SimHei", "黑体", "Dialog"};
        for (String n : names) {
            Font f = new Font(n, Font.PLAIN, 20);
            if (f.canDisplay('中')) return f;
        }
        return new Font(Font.SANS_SERIF, Font.PLAIN, 20);
    }

    /** 按字符宽度换行并逐行水平居中绘制（过滤字体无法显示的 emoji/特殊字符） */
    private void drawCenteredWrap(Graphics2D g, Font font, String text, int maxWidth,
                                  int startY, int lineHeight, int canvasWidth) {
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        StringBuilder line = new StringBuilder();
        int y = startY;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!font.canDisplay(c)) continue;
            if (c == '\n') {
                flushLine(g, fm, line, y, canvasWidth);
                y += lineHeight;
                line.setLength(0);
                continue;
            }
            if (line.length() > 0 && fm.stringWidth(line.toString() + c) > maxWidth) {
                flushLine(g, fm, line, y, canvasWidth);
                y += lineHeight;
                line.setLength(0);
            }
            line.append(c);
        }
        flushLine(g, fm, line, y, canvasWidth);
    }

    private void flushLine(Graphics2D g, FontMetrics fm, StringBuilder line, int y, int canvasWidth) {
        if (line.length() == 0) return;
        int lw = fm.stringWidth(line.toString());
        g.drawString(line.toString(), (canvasWidth - lw) / 2, y);
    }

    /** 跟风文案（围绕该视频主题的原创编辑） */
    private String buildFollowDesc(Map<String, Object> top) {
        String title = String.valueOf(top.get("title"));
        String author = String.valueOf(top.get("author"));
        StringBuilder sb = new StringBuilder();
        sb.append("🔥 最近刷到大家都在看：「").append(title).append("」\n");
        sb.append("今天这个话题也太对味了，跟风来整一个！\n\n");
        if (author != null && !author.isBlank() && !"null".equalsIgnoreCase(author)) {
            sb.append("灵感来源：@").append(author).append(" 的热门视频\n");
        }
        return sb.toString();
    }
}
