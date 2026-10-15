package com.demo.controller;

import com.demo.agent.XhsPublishMcpTool;
import com.demo.service.xhs.CommentWatchService;
import com.demo.service.xhs.HotVideoReportService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生活企划 → 小红书发布（桌面端「生活企划 → 小红书」直接调用）。
 * <p>
 * 复用 {@link XhsPublishMcpTool}：检查登录 → 收集图片（可显式传路径）→ 写临时文件 → 调 xhs_publish_content。
 */
@Slf4j
@RestController
@RequestMapping("/api/desktop/life")
public class LifeController {

    @Resource
    private XhsPublishMcpTool xhsPublishMcpTool;

    @Resource
    private HotVideoReportService hotVideoReportService;

    @Resource
    private CommentWatchService commentWatchService;

    /**
     * 发布小红书图文笔记。
     * body: {"title":"标题","desc":"正文","tags":"#旅行 #美食","images":["D:/a.jpg","D:/b.jpg"]}
     */
    @PostMapping("/xhs/publish")
    public Map<String, Object> publishXhs(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (body == null) {
            out.put("code", 400);
            out.put("msg", "参数为空");
            return out;
        }
        String title = str(body.get("title"));
        String desc = str(body.get("desc"));
        String tags = str(body.get("tags"));
        String images = collectImages(body.get("images"));

        log.info("📕 桌面端调小红书发布：title={}, tags={}, images={}", title, tags, images);
        String result = xhsPublishMcpTool.publishXhsNote(title, desc, tags, images);
        out.put("code", 200);
        out.put("result", result);
        return out;
    }

    /**
     * 查询小红书登录状态（桌面端「生活企划 → 小红书」发布前提示）。
     * 返回 {code, loggedIn, result}。
     */
    @PostMapping("/xhs/login-status")
    public Map<String, Object> loginStatus() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 200);
        out.putAll(xhsPublishMcpTool.loginStatus());
        return out;
    }

    /**
     * 清除 MCP 服务端缓存的登录 Cookie（换账号先清旧登录态）。
     * 返回 {code, result}。
     */
    @PostMapping("/xhs/logout")
    public Map<String, Object> logout() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 200);
        out.put("result", xhsPublishMcpTool.deleteCookies());
        return out;
    }

    /**
     * 获取小红书登录二维码（Base64 图片 + 超时），清 cookie 后扫码重新登录。
     * 返回 {code, result}。
     */
    @PostMapping("/xhs/qrcode")
    public Map<String, Object> qrcode() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 200);
        out.put("result", xhsPublishMcpTool.getLoginQrcode());
        return out;
    }

    // ======================== 热门视频定时发布 ========================

    /** 定时发布状态（enabled/days/time/cron/nextRun） */
    @PostMapping("/xhs/hot-trend/status")
    public Map<String, Object> hotTrendStatus() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 200);
        out.putAll(hotVideoReportService.status());
        return out;
    }

    /** 设置定时发布：body {days:[1,3], time:"14:00", enabled:true} */
    @PostMapping("/xhs/hot-trend/schedule")
    public Map<String, Object> hotTrendSchedule(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Integer> days = toIntList(body == null ? null : body.get("days"));
        String time = body == null ? null : str(body.get("time"));
        Boolean enabled = body == null ? null : toBoolean(body.get("enabled"));
        out.put("code", 200);
        out.putAll(hotVideoReportService.setSchedule(days, time, enabled));
        return out;
    }

    /** 预览首页热门视频（不发布） */
    @PostMapping("/xhs/hot-trend/preview")
    public Map<String, Object> hotTrendPreview() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 200);
        out.put("videos", hotVideoReportService.preview());
        return out;
    }

    /** 立即发布热门视频汇总笔记 */
    @PostMapping("/xhs/hot-trend/publish")
    public Map<String, Object> hotTrendPublish() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 200);
        out.put("result", hotVideoReportService.publishNow());
        return out;
    }

    // ======================== 黑评监控 ========================

    /** 黑评监控状态（enabled/cron/email/nextRun） */
    @PostMapping("/xhs/comment-watch/status")
    public Map<String, Object> commentWatchStatus() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 200);
        out.putAll(commentWatchService.status());
        return out;
    }

    /** 手动触发黑评扫描 */
    @PostMapping("/xhs/comment-watch/scan")
    public Map<String, Object> commentWatchScan() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 200);
        out.put("result", commentWatchService.scanNow());
        return out;
    }

    /** body 里 days 转 List<Integer>（兼容数组/逗号字符串） */
    private static List<Integer> toIntList(Object o) {
        List<Integer> out = new ArrayList<>();
        if (o == null) return out;
        if (o instanceof List<?> list) {
            for (Object it : list) {
                try {
                    out.add(Integer.parseInt(String.valueOf(it).trim()));
                } catch (Exception ignored) {
                }
            }
        } else if (o instanceof String s) {
            for (String part : s.split(",")) {
                try {
                    out.add(Integer.parseInt(part.trim()));
                } catch (Exception ignored) {
                }
            }
        }
        return out;
    }

    /** body 里布尔转 Boolean（兼容 true/false/"1"/"0"） */
    private static Boolean toBoolean(Object o) {
        if (o == null) return null;
        if (o instanceof Boolean b) return b;
        String s = String.valueOf(o).trim();
        if ("1".equals(s) || "true".equalsIgnoreCase(s) || "yes".equalsIgnoreCase(s)) return Boolean.TRUE;
        if ("0".equals(s) || "false".equalsIgnoreCase(s) || "no".equalsIgnoreCase(s)) return Boolean.FALSE;
        return null;
    }

    /**
     * 暂存桌面端上传的 base64 图片 → 返回本地路径（浏览器 dev 兜底：MCP 只认本地路径）。
     * body: {"images": ["<base64>", ...]} → {code, paths:[...]}
     */
    @PostMapping("/xhs/prepare-images")
    public Map<String, Object> prepareImages(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> base64List = collectBase64List(body == null ? null : body.get("images"));
        List<String> paths = xhsPublishMcpTool.saveBase64Images(base64List);
        if (paths.isEmpty()) {
            out.put("code", 500);
            out.put("msg", "没有可用的图片数据");
            return out;
        }
        out.put("code", 200);
        out.put("paths", paths);
        return out;
    }

    /** base64 列表：数组或逗号分隔字符串 → List<String> */
    private static List<String> collectBase64List(Object o) {
        List<String> out = new ArrayList<>();
        if (o == null) return out;
        if (o instanceof List<?> list) {
            for (Object it : list) {
                String s = String.valueOf(it).trim();
                if (!s.isEmpty()) out.add(s);
            }
        } else if (o instanceof String s && !s.isBlank()) {
            for (String part : s.split(",")) {
                if (!part.isBlank()) out.add(part.trim());
            }
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    /** images：数组或逗号分隔字符串 → 逗号分隔的路径串 */
    private static String collectImages(Object o) {
        if (o == null) return "";
        if (o instanceof List<?> list) {
            List<String> paths = new ArrayList<>();
            for (Object it : list) {
                String s = String.valueOf(it).trim();
                if (!s.isEmpty()) paths.add(s);
            }
            return String.join(",", paths);
        }
        return String.valueOf(o).trim();
    }
}
