package com.demo.travel;

import com.demo.config.AiChatProperties;
import com.alibaba.fastjson2.JSONObject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 出行穿搭生成服务 — 仿 WordLookupService 的 DeepSeek 原生 REST 调用。
 * <p>
 * 输入行程信息 + 用户衣橱（可选）→ 生成 2-3 套景点专属穿搭（含每日成套、打包清单、出片技巧、用到的衣橱单品）。
 */
@Slf4j
@Service
public class OutfitService {

    @Resource
    private AiChatProperties aiChat;

    @Resource
    @Qualifier("llmRestTemplate")
    private RestTemplate restTemplate;

    @Resource
    private ObjectMapper objectMapper;

    /** 出行信息（前端表单） */
    @Data
    public static class TravelInfo {
        private String city;
        private String spots;
        private Integer days = 1;
        private String temp;
        private Boolean rain;
        private String style;
        private Boolean useWardrobe;
    }

    /** 单天行程（多景点）：穿搭/试穿按天一套 */
    @Data
    public static class TravelDay {
        private String dayId;
        private Integer day = 1;
        private String city;
        private List<String> spots = new ArrayList<>();
        private String temp;
        private Boolean rain;
        private String style;
    }

    /** 单套推荐（一套 = 一件上衣 + 一件下装） */
    @Data
    public static class OutfitRec {
        private String label; // 方案一 / 方案二 ...
        private String topItemId;
        private String bottomItemId;
    }

    /** 单天方案结果（含多套推荐衣橱 itemId） */
    @Data
    public static class DayPlan {
        private String dayId;
        private String dayLabel;
        private String plan;
        private String recommendedTopItemId; // 兼容保留 = 方案一
        private String recommendedBottomItemId;
        private List<OutfitRec> outfits = new ArrayList<>(); // 每套独立可试穿
    }

    /**
     * 生成出行穿搭方案（markdown 文本）。
     *
     * @param info     行程信息
     * @param wardrobe 用户衣橱单品（useWardrobe=true 时带入），可为 null
     * @return AI 生成的穿搭方案；失败返回错误提示
     */
    public String generateOutfit(TravelInfo info, List<Map<String, Object>> wardrobe) {
        if (info == null || info.getCity() == null || info.getCity().isBlank()) {
            return "❌ 请先填写目的地城市";
        }
        return callDeepSeek(buildPrompt(info, wardrobe));
    }

    /**
     * 行程生成：按城市生成每日景点列表（旅行规划师，严格 JSON）。
     *
     * @return 每项 {day, city, spots[]}；解析失败返回 null（前端降级生成空白天数卡片）
     */
    public List<Map<String, Object>> generatePlan(String city, Integer days, String style, String extra) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是资深旅行规划师。请为【").append(nullSafe(city)).append("】制定 ")
          .append(days == null ? 1 : days).append(" 天的旅行计划，每天列出 3-5 个值得一去的景点（热门与小众兼顾，适合游玩/拍照）。");
        if (!isBlank(style)) {
            sb.append(" 出行风格偏好：").append(style).append("。");
        }
        if (!isBlank(extra)) {
            sb.append(" 特别要求：").append(extra).append("。");
        }
        sb.append("\n【关键】每天景点要按地理位置「顺路」排列：从第一站到最后一站是一条连贯、尽量不走回头路的路线；相邻两站地理上相近、交通方便，避免同一天在城市两端来回折返；相距近的景点尽量排在同一天。spots 数组的顺序就是当天的游览顺序。");
        sb.append("\n每天还要给出景点之间的交通方式建议（地铁/公交/打车/步行 + 大致耗时；跨城市时含高铁/火车大致时长），route 用单行文本、各段用；分隔、不要换行符。");
        sb.append("\n只输出一个 JSON 对象，不要输出任何解释、注释、Markdown 代码块或其他文字。格式：")
          .append("{\"itinerary\":[{\"day\":1,\"spots\":[\"景点名1\",\"景点名2\"],\"route\":\"景点名1→景点名2：地铁X号线约30分钟；景点名2→景点名3：打车约15分钟\"}]}");
        String raw = callDeepSeek(sb.toString());
        if (raw == null || raw.isBlank() || raw.startsWith("❌")) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(cleanJson(raw));
            JsonNode arr = root.path("itinerary");
            if (!arr.isArray() || arr.isEmpty()) return null;
            List<Map<String, Object>> itinerary = new ArrayList<>();
            for (JsonNode node : arr) {
                Map<String, Object> day = new LinkedHashMap<>();
                day.put("day", node.path("day").asInt(1));
                day.put("city", city);
                List<String> spots = new ArrayList<>();
                JsonNode sa = node.path("spots");
                if (sa.isArray()) {
                    for (JsonNode s : sa) {
                        String name = s.asText().trim();
                        if (!name.isBlank()) spots.add(name);
                    }
                }
                day.put("spots", spots);
                day.put("route", node.path("route").asText("")); // AI 交通建议（可能为空）
                itinerary.add(day);
            }
            return itinerary.isEmpty() ? null : itinerary;
        } catch (Exception e) {
            log.warn("👗 行程生成解析失败: {}", truncate(raw, 200));
            return null;
        }
    }

    /**
     * 按天批量生成穿搭方案（每天独立一次 LLM 调用）。
     * <p>单天失败不阻断其余天：该天返回空 plan + 空推荐 ID，由前端提示单独重试。
     *
     * @param days     天列表（含 dayId）
     * @param wardrobe 用户衣橱（可为 null/空）
     * @return 每天一个 DayPlan，顺序与 days 一致
     */
    public List<DayPlan> generateMultiOutfit(List<TravelDay> days, List<Map<String, Object>> wardrobe) {
        List<DayPlan> plans = new ArrayList<>();
        if (days == null || days.isEmpty()) {
            return plans;
        }
        for (int i = 0; i < days.size(); i++) {
            if (i > 0) {
                sleep(500); // 相邻天之间留间隔，降低连续请求触发限流概率
            }
            plans.add(generateOneDay(days.get(i), wardrobe));
        }
        return plans;
    }

    /** 单天方案：LLM 失败隔离 + 多套推荐编号解析降级 */
    private DayPlan generateOneDay(TravelDay day, List<Map<String, Object>> wardrobe) {
        DayPlan p = new DayPlan();
        p.setDayId(day.getDayId());
        p.setDayLabel(buildDayLabel(day));
        p.setRecommendedTopItemId("");
        p.setRecommendedBottomItemId("");
        try {
            String raw = callDeepSeek(buildDayPrompt(day, wardrobe));
            if (raw == null || raw.isBlank() || raw.startsWith("❌")) {
                log.warn("👗 第{}天 [{}] 方案生成失败，原因: {}", day.getDay() == null ? 1 : day.getDay(), day.getCity(), raw);
                p.setPlan("");
                return p;
            }
            List<OutfitRec> outfits = parseOutfits(raw, wardrobe);
            p.setOutfits(outfits);
            if (!outfits.isEmpty()) {
                p.setRecommendedTopItemId(outfits.get(0).getTopItemId());
                p.setRecommendedBottomItemId(outfits.get(0).getBottomItemId());
            }
            // 方案行已解析（或解析失败仅清空推荐），plan 保留纯行程文案
            p.setPlan(stripRecommendationLine(raw));
        } catch (Exception e) {
            log.error("👗 第{}天方案生成异常", day.getDay() == null ? 1 : day.getDay(), e);
            p.setPlan("");
        }
        return p;
    }

    /**
     * 汇总生成完整旅游穿搭手册。
     *
     * @param days    全部天
     * @param outfits 每天实际选用衣物 [{dayId, outfit}]，可为 null
     * @return 完整手册 Markdown
     */
    public String generateManual(List<TravelDay> days, List<Map<String, String>> outfits) {
        if (days == null || days.isEmpty()) {
            return "❌ 暂无已确认的行程";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("你是专业旅行穿搭造型师。用户已完成一次多天旅行的分天穿搭与试穿，请汇总输出一份完整的《旅游穿搭手册》。\n\n");
        sb.append("【每天行程与已选穿搭】\n");
        for (int i = 0; i < days.size(); i++) {
            TravelDay d = days.get(i);
            sb.append("第").append(d.getDay() == null ? i + 1 : d.getDay()).append("天 · ")
              .append(nullSafe(d.getCity())).append("：").append(joinSpots(d.getSpots()))
              .append("，气温：").append(nullSafe(d.getTemp())).append("℃，")
              .append(Boolean.TRUE.equals(d.getRain()) ? "有雨" : "无雨")
              .append("，风格：").append(nullSafe(d.getStyle())).append("\n");
            String outfit = outfitBySpotId(outfits, d.getDayId());
            if (!isBlank(outfit)) {
                sb.append("   实际选用穿搭：").append(outfit).append("\n");
            }
        }
        sb.append("\n【要求】\n");
        sb.append("1. 按天顺序输出完整每日穿搭手册，每天 1-2 套主打穿搭（覆盖当天所有景点场景）\n");
        sb.append("2. 汇总打包清单（跨天精简，一箱搞定）\n");
        sb.append("3. 给出天与天之间的穿搭衔接建议（温差 / 风格过渡）\n");
        sb.append("4. 用 Markdown 输出，中文回答。");
        return callDeepSeek(sb.toString());
    }

    /** 解析多套方案行 `方案1：上衣[3] 下装[5]`（可多行）；解析失败返回空列表（不丢弃方案文本） */
    private static List<OutfitRec> parseOutfits(String text, List<Map<String, Object>> wardrobe) {
        List<OutfitRec> list = new ArrayList<>();
        if (text == null) return list;
        try {
            Pattern p = Pattern.compile("方案\\s*(\\d+)\\s*[:：]\\s*上衣\\s*[\\[（(]?\\s*(\\d+)\\s*[\\]）)]?\\s*下装\\s*[\\[（(]?\\s*(\\d+)\\s*[\\]）)]?");
            Matcher m = p.matcher(text);
            while (m.find()) {
                OutfitRec rec = new OutfitRec();
                rec.setLabel("方案" + m.group(1));
                rec.setTopItemId(itemIdAt(wardrobe, Integer.parseInt(m.group(2))));
                rec.setBottomItemId(itemIdAt(wardrobe, Integer.parseInt(m.group(3))));
                if (!rec.getTopItemId().isBlank() || !rec.getBottomItemId().isBlank()) {
                    list.add(rec);
                }
            }
        } catch (NumberFormatException e) {
            // 保留已解析到的方案
        }
        return list;
    }

    /**
     * 从 plan 文本中剥离末尾推荐行（`推荐：…` 与 `方案N：上衣[..] 下装[..]`），
     * 保留方案正文的「方案N：标题」标题行，供前端按方案分段。
     */
    private static String stripRecommendationLine(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            // 只剥推荐行（含「上衣/下装」）；正文标题如「方案1：舒适通勤」不含上衣/下装，保留
            if (line.matches("^\\s*(推荐\\s*[:：].*|方案\\s*\\d+\\s*[:：]\\s*上衣\\s*.*下装\\s*.*)")) {
                continue;
            }
            sb.append(line).append("\n");
        }
        String out = sb.toString().replaceAll("\n{3,}", "\n\n").trim();
        return out.isBlank() ? text.trim() : out;
    }

    private static String itemIdAt(List<Map<String, Object>> wardrobe, int oneBasedIndex) {
        if (wardrobe == null || oneBasedIndex < 1 || oneBasedIndex > wardrobe.size()) return "";
        Object id = wardrobe.get(oneBasedIndex - 1).get("itemId");
        return id == null ? "" : String.valueOf(id);
    }

    private static String outfitBySpotId(List<Map<String, String>> outfits, String spotId) {
        if (outfits == null || spotId == null) return "";
        for (Map<String, String> m : outfits) {
            if (spotId.equals(m.get("spotId"))) {
                String o = m.get("outfit");
                return o == null ? "" : o;
            }
        }
        return "";
    }

    /** 单天方案提示词：含当天全部景点 + 衣橱编号 + 末尾推荐行 */
    private String buildDayPrompt(TravelDay day, List<Map<String, Object>> wardrobe) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是专业旅行穿搭造型师。请为以下第")
          .append(day.getDay() == null ? 1 : day.getDay())
          .append("天的出行生成一套穿搭方案（覆盖当天所有景点场景）。\n\n");
        sb.append("【出行信息】\n");
        sb.append("目的地：").append(nullSafe(day.getCity())).append("\n");
        String spotsStr = joinSpots(day.getSpots());
        if (!isBlank(spotsStr)) {
            sb.append("当天景点：").append(spotsStr).append("\n");
        }
        if (!isBlank(day.getTemp())) {
            sb.append("气温：").append(day.getTemp()).append("℃\n");
        }
        sb.append("天气：").append(Boolean.TRUE.equals(day.getRain()) ? "有降雨" : "无雨").append("\n");
        sb.append("出行风格：").append(isBlank(day.getStyle()) ? "舒适" : day.getStyle()).append("\n");

        boolean useWardrobe = wardrobe != null && !wardrobe.isEmpty();
        if (useWardrobe) {
            sb.append("\n【用户衣橱】仅使用下列自有衣物搭配，编号从 [1] 开始：\n");
            int idx = 1;
            for (Map<String, Object> item : wardrobe) {
                sb.append("[").append(idx).append("] ")
                  .append(item.getOrDefault("category", ""))
                  .append(" ").append(item.getOrDefault("subCategory", ""))
                  .append(" ").append(item.getOrDefault("color", ""))
                  .append(" ").append(item.getOrDefault("fabric", ""))
                  .append(" 季节:").append(item.getOrDefault("season", ""))
                  .append(" 风格:").append(item.getOrDefault("style", "")).append("\n");
                idx++;
            }
        }

        sb.append("\n【要求】\n");
        sb.append("1. 生成 2-3 套完整穿搭，适配该天所有景点（拍照 + 长时间步行舒适），并给出场景间切换建议；每套用一行加粗标题开头，格式严格为：**方案1：<简短标题>**、**方案2：<简短标题>**、**方案3：<简短标题>**（从 1 连续编号）\n");
        sb.append("2. 结合景点风景色彩给出配色逻辑，兼顾防晒、防风、昼夜温差\n");
        sb.append("3. 每套在标题下列出：每日成套（上衣/下装或连衣裙 + 外套 + 鞋 + 配饰，标注面料与温度适配）+ 拍照出片技巧\n");
        if (useWardrobe) {
            sb.append("4. 每套明确列出用到了衣橱里的哪些单品（用编号或名称均可）；缺少适配单品时给补充购买建议\n");
        }
        sb.append("5. 最后附上打包精简清单\n");
        sb.append("6. 用 Markdown 输出，结构清晰，中文回答。\n");
        if (useWardrobe) {
            sb.append("7. 全部内容写完后，最后【单独输出】推荐方案，与方案数量一致，每行一套，格式严格为（编号必须来自上面的衣橱列表）：\n")
              .append("方案1：上衣[编号] 下装[编号]\n")
              .append("方案2：上衣[编号] 下装[编号]\n")
              .append("方案3：上衣[编号] 下装[编号]\n")
              .append("（推荐方案的方案N 必须与正文方案N 是同一套衣服，即正文方案N 里描述的那件上衣和那条下装；推荐不同的编号组合让每套可独立试穿；不要输出这些行之外的任何附加内容）");
        } else {
            sb.append("7. 衣橱为空，不要输出方案行。");
        }
        return sb.toString();
    }

    /** 天 Tab 标签：第N天 · 景点1/景点2（无景点回退城市） */
    private static String buildDayLabel(TravelDay day) {
        String city = nullSafe(day.getCity());
        String spotsStr = joinSpots(day.getSpots());
        StringBuilder sb = new StringBuilder("第").append(day.getDay() == null ? 1 : day.getDay()).append("天");
        if (!isBlank(spotsStr)) {
            sb.append(" · ").append(spotsStr.length() > 12 ? spotsStr.substring(0, 12) + "…" : spotsStr);
        } else if (!city.isBlank()) {
            sb.append(" · ").append(city);
        }
        return sb.toString();
    }

    /** 剥离 LLM 夹带的前后自然文本/代码块，只留 JSON */
    private static String cleanJson(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.startsWith("```")) {
            s = s.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("```\\s*$", "");
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) {
            s = s.substring(start, end + 1);
        }
        return s;
    }

    private static String joinSpots(List<String> spots) {
        if (spots == null || spots.isEmpty()) return "";
        return String.join("、", spots);
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * 追问调整穿搭（带对话历史）。
     *
     * @param info     行程信息
     * @param wardrobe 用户衣橱（可为 null）
     * @param history  对话历史 [{role:"user|assistant", text:"..."}]（最近若干轮）
     * @param question 用户最新调整要求
     * @return 调整后的穿搭建议
     */
    public String adjustOutfit(TravelInfo info, List<Map<String, Object>> wardrobe,
                               List<Map<String, String>> history, String question) {
        if (question == null || question.isBlank()) {
            return "请输入你想调整的内容（如：加一件薄外套、换亮色、精简行李）";
        }
        return callDeepSeek(buildAdjustPrompt(info, wardrobe, history, question));
    }

    /**
     * 试穿解释：穿搭理由 / 景点适配 / 天气适配。
     *
     * @param info      行程信息（可为 null）
     * @param outfit    衣物描述
     * @return 简短 Markdown 解释
     */
    public String explainTryOn(TravelInfo info, String outfit) {
        if (outfit == null || outfit.isBlank()) {
            return "请选择一件衣物";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("你是旅行穿搭顾问。用户试穿一件衣物【").append(outfit).append("】，")
          .append("目的地：").append(info == null || info.getCity() == null ? "" : info.getCity())
          .append("，景点：").append(info == null || info.getSpots() == null ? "" : info.getSpots())
          .append("，气温：").append(info == null || info.getTemp() == null ? "" : info.getTemp())
          .append("℃，").append(info != null && Boolean.TRUE.equals(info.getRain()) ? "有雨" : "无雨")
          .append("。请用简短的 Markdown 输出以下三段：\n")
          .append("**穿搭理由**：这件衣物为什么适合（配色、版型、风格）\n")
          .append("**景点适配**：与目的地风景/景点的搭配建议\n")
          .append("**天气适配**：防晒/保暖/防水等实用性建议\n")
          .append("每段 1-2 句，简洁口语化。");
        return callDeepSeek(sb.toString());
    }

    /** DeepSeek 原生 REST 调用（非流式，返回全文）；瞬态失败（Connection reset/限流/超时）自动重试 2 次 */
    private String callDeepSeek(String prompt) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                JSONObject reqBody = new JSONObject();
                aiChat.configureRequest(reqBody);
                reqBody.put("temperature", 0.6);
                reqBody.put("max_tokens", 8192); // 防止长生成超时/被截断
                JSONObject msg = new JSONObject();
                msg.put("role", "user");
                msg.put("content", prompt);
                reqBody.put("messages", new Object[]{msg});

                HttpHeaders headers = new HttpHeaders();
                headers.add("Authorization", "Bearer " + aiChat.getApiKey());
                headers.add("Content-Type", "application/json");
                HttpEntity<String> entity = new HttpEntity<>(reqBody.toJSONString(), headers);

                ResponseEntity<String> resp = restTemplate.postForEntity(aiChat.getCompletionsUrl(), entity, String.class);
                if (!resp.getStatusCode().is2xxSuccessful()) {
                    log.warn("👗 DeepSeek 非 2xx(第{}次): HTTP {} body={}", attempt, resp.getStatusCodeValue(), truncate(resp.getBody(), 200));
                    if (attempt < 3) { sleep(1000); continue; }
                    return "❌ AI 服务异常（HTTP " + resp.getStatusCodeValue() + "），请稍后重试";
                }
                // 用 Jackson 解析（fastjson2 解析长字符串曾抛 ArrayIndexOutOfBoundsException）
                JsonNode root = objectMapper.readTree(resp.getBody());
                String content = root.path("choices").path(0).path("message").path("content").asText();
                if (content == null || content.isBlank()) {
                    if (attempt < 3) { sleep(1000); continue; }
                    return "❌ AI 生成失败，请重试";
                }
                return content.trim();
            } catch (Exception e) {
                log.warn("👗 DeepSeek 调用失败(第{}次): {}", attempt, e.getMessage());
                if (attempt < 3) { sleep(1000); continue; }
                return "❌ 生成失败：" + e.getMessage();
            }
        }
        return "❌ AI 生成失败，请重试";
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 追问调整提示词 */
    private String buildAdjustPrompt(TravelInfo info, List<Map<String, Object>> wardrobe,
                                     List<Map<String, String>> history, String question) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是专业旅行穿搭造型师。以下是之前为一次出行生成的穿搭方案与对话，请根据用户最新的调整要求继续调整。\n\n");
        sb.append("【出行信息】目的地：").append(info == null ? "" : info.getCity())
          .append("，景点：").append(info == null || info.getSpots() == null ? "" : info.getSpots())
          .append("，天数：").append(info == null || info.getDays() == null ? 1 : info.getDays())
          .append("天，气温：").append(info == null || info.getTemp() == null ? "" : info.getTemp())
          .append("℃，").append(info != null && Boolean.TRUE.equals(info.getRain()) ? "有雨" : "无雨")
          .append("，风格：").append(info == null || info.getStyle() == null ? "" : info.getStyle())
          .append("\n");

        boolean useWardrobe = info != null && Boolean.TRUE.equals(info.getUseWardrobe())
                && wardrobe != null && !wardrobe.isEmpty();
        if (useWardrobe) {
            sb.append("【用户衣橱】仅使用下列自有衣物调整：\n");
            for (Map<String, Object> item : wardrobe) {
                sb.append("- ").append(item.getOrDefault("category", ""))
                  .append(" ").append(item.getOrDefault("subCategory", ""))
                  .append(" ").append(item.getOrDefault("color", ""))
                  .append(" ").append(item.getOrDefault("fabric", "")).append("\n");
            }
        }

        if (history != null && !history.isEmpty()) {
            sb.append("\n【对话历史】\n");
            int from = Math.max(0, history.size() - 6); // 保留最近 6 条
            for (int i = from; i < history.size(); i++) {
                Map<String, String> h = history.get(i);
                String role = "user".equals(h.get("role")) ? "用户" : "助手";
                String text = h.getOrDefault("text", "");
                if (!text.isBlank()) {
                    sb.append(role).append("：").append(truncate(text, 200)).append("\n");
                }
            }
        }

        sb.append("\n【用户最新要求】").append(question).append("\n\n");
        sb.append("请只回复本次调整的穿搭建议（可在必要时补充与之前方案的衔接），结构清晰，用 Markdown，中文回答。");
        return sb.toString();
    }

    /** 组装 DeepSeek 提示词 */
    private String buildPrompt(TravelInfo info, List<Map<String, Object>> wardrobe) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是专业旅行穿搭造型师。请基于以下信息生成出行穿搭方案。\n\n");
        sb.append("【出行信息】\n");
        sb.append("目的地：").append(info.getCity()).append("\n");
        if (info.getSpots() != null && !info.getSpots().isBlank()) {
            sb.append("游玩景点：").append(info.getSpots()).append("\n");
        }
        sb.append("出行天数：").append(info.getDays() == null ? 1 : info.getDays()).append(" 天\n");
        if (info.getTemp() != null && !info.getTemp().isBlank()) {
            sb.append("气温：").append(info.getTemp()).append("℃\n");
        }
        sb.append("天气：").append(Boolean.TRUE.equals(info.getRain()) ? "有降雨" : "无雨").append("\n");
        sb.append("出行风格：").append(info.getStyle() == null ? "舒适" : info.getStyle()).append("\n");

        boolean useWardrobe = Boolean.TRUE.equals(info.getUseWardrobe())
                && wardrobe != null && !wardrobe.isEmpty();
        if (useWardrobe) {
            sb.append("\n【用户衣橱】仅使用下列自有衣物搭配，不要推荐衣橱外的单品（可给出补充购买建议）：\n");
            for (Map<String, Object> item : wardrobe) {
                sb.append("- ").append(item.getOrDefault("category", ""))
                  .append(" ").append(item.getOrDefault("subCategory", ""))
                  .append(" ").append(item.getOrDefault("color", ""))
                  .append(" ").append(item.getOrDefault("fabric", ""))
                  .append(" 季节:").append(item.getOrDefault("season", ""))
                  .append(" 风格:").append(item.getOrDefault("style", "")).append("\n");
            }
        }

        sb.append("\n【要求】\n");
        sb.append("1. 生成 2-3 套完整穿搭，适配景点拍照 + 长时间步行舒适\n");
        sb.append("2. 结合景点风景色彩给出配色逻辑，兼顾防晒、防风、昼夜温差\n");
        sb.append("3. 每套列出：每日成套（上衣/下装或连衣裙 + 外套 + 鞋 + 配饰，标注面料与温度适配）+ 拍照出片技巧\n");
        if (useWardrobe) {
            sb.append("4. 每套明确列出用到了衣橱里的哪些单品；缺少适配单品时给补充购买建议\n");
        }
        sb.append("5. 最后附上打包精简清单\n");
        sb.append("6. 用 Markdown 输出，结构清晰，中文回答。");
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
