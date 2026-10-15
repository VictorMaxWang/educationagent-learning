package com.demo.travel.controller;

import com.alibaba.fastjson2.JSONObject;
import com.demo.travel.AITryOnService;
import com.demo.service.tools.MapService;
import com.demo.travel.OutfitService;
import com.demo.travel.WardrobeService;
import com.demo.service.tools.WeatherService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 出行穿搭接口 — {@code /api/desktop/wardrobe/*}（桌面端 baseURL 已含 /api/desktop）。
 * 衣物图片识别（Qwen-VL）、穿搭生成（DeepSeek）、目的地天气（和风）。
 */
@Slf4j
@RestController
@RequestMapping("/api/desktop")
public class WardrobeController {

    @Resource
    private WardrobeService wardrobeService;

    @Resource
    private OutfitService outfitService;

    @Resource
    private WeatherService weatherService;

    @Resource
    private AITryOnService aiTryOnService;

    @Resource
    private MapService mapService;

    /**
     * 识别衣物图片。
     * body: {"image":"data:image/png;base64,xxx"}
     * 返回 {code, data:{category,subCategory,color,fabric,season,style}}；失败返回业务错误码 + 提示文案
     */
    @PostMapping("/wardrobe/recognize")
    public Map<String, Object> recognize(@RequestBody(required = false) Map<String, String> body) {
        String image = body != null ? body.getOrDefault("image", "") : "";
        byte[] bytes = decodeDataUrl(image);
        if (bytes == null || bytes.length == 0) {
            return error("图片数据为空");
        }
        List<JSONObject> garments = wardrobeService.recognizeClothing(bytes);
        if (garments == null || garments.isEmpty()) {
            return error("未能识别衣物，请重新上传清晰正面图");
        }
        Map<String, Object> out = ok();
        out.put("data", Map.of("garments", garments, "count", garments.size()));
        return out;
    }

    /**
     * 虚拟试穿（AI试衣 Plus / aitryon-plus，专业 VTON，支持多件整套）。
     * body: {"image":"人物全身照 base64", "garments":[{"image":"衣物图 base64","category":"top|bottom|dress|coat"}, ...]}
     * 返回 {code, image: data:image/jpeg;base64,...}；失败返回业务错误码
     */
    @PostMapping("/wardrobe/tryon")
    public Map<String, Object> tryOn(@RequestBody(required = false) Map<String, Object> body) {
        String image = str(body != null ? body.get("image") : null); // 人物全身照
        byte[] personBytes = decodeDataUrl(image);
        if (personBytes == null || personBytes.length == 0) {
            return error("请先上传人物照片");
        }

        List<AITryOnService.GarmentInput> garments = new ArrayList<>();
        Object g = body != null ? body.get("garments") : null;
        if (g instanceof List<?> raw) {
            for (Object item : raw) {
                if (!(item instanceof Map<?, ?> m)) continue;
                String img = str(m.get("image"));
                byte[] bytes = decodeDataUrl(img);
                if (bytes == null || bytes.length == 0) continue;
                garments.add(new AITryOnService.GarmentInput(bytes, str(m.get("category"))));
            }
        }
        if (garments.isEmpty()) {
            return error("请选择至少一件衣物进行试穿");
        }

        AITryOnService.TryOnResult result = aiTryOnService.tryOn(personBytes, garments);
        if (!result.success) {
            return error("试穿生成失败：" + (result.error == null ? "未知原因" : result.error));
        }
        Map<String, Object> out = ok();
        out.put("image", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(result.image));
        return out;
    }

    /**
     * 试穿解释：穿搭理由 / 景点适配 / 天气适配（DeepSeek 生成）。
     * body: {"travelInfo":{...}, "outfit":"白色棉质针织衫，休闲"}
     */
    @PostMapping("/wardrobe/tryon/explain")
    public Map<String, Object> explainTryOn(@RequestBody(required = false) Map<String, Object> body) {
        OutfitService.TravelInfo info = parseTravelInfo(body != null ? body.get("travelInfo") : null);
        String outfit = str(body != null ? body.get("outfit") : null);
        String text = outfitService.explainTryOn(info, outfit);
        Map<String, Object> out = ok();
        out.put("text", text);
        return out;
    }

    /**
     * 生成出行穿搭方案。
     * body: {"travelInfo":{city,spots,days,temp,rain,style,useWardrobe}, "wardrobe":[{...}]}
     * 返回 {code, plan}
     */
    @PostMapping("/outfit/generate")
    public Map<String, Object> generate(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) {
            return error("请求体为空");
        }
        OutfitService.TravelInfo info = new OutfitService.TravelInfo();
        Object t = body.get("travelInfo");
        if (t instanceof Map<?, ?> tm) {
            info.setCity(str(tm.get("city")));
            info.setSpots(str(tm.get("spots")));
            info.setDays(toInt(tm.get("days")));
            info.setTemp(str(tm.get("temp")));
            info.setRain(tm.get("rain") == null ? null : Boolean.parseBoolean(String.valueOf(tm.get("rain"))));
            info.setStyle(str(tm.get("style")));
            info.setUseWardrobe(tm.get("useWardrobe") == null ? null : Boolean.parseBoolean(String.valueOf(tm.get("useWardrobe"))));
        }
        if (info.getCity() == null || info.getCity().isBlank()) {
            return error("请先填写目的地城市");
        }

        List<Map<String, Object>> wardrobe = new ArrayList<>();
        Object w = body.get("wardrobe");
        if (w instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Map<String, Object> one = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        if (e.getKey() instanceof String k && e.getValue() != null) {
                            one.put(k, String.valueOf(e.getValue()));
                        }
                    }
                    wardrobe.add(one);
                }
            }
        }

        String plan = outfitService.generateOutfit(info, wardrobe);
        Map<String, Object> out = ok();
        out.put("plan", plan);
        return out;
    }

    /**
     * 行程生成：按城市生成每日景点列表。
     * body: {"city":"南京", "days":3, "style":"舒适", "extra":"亲子，不爬山"}
     * 返回 {code, itinerary:[{day, city, spots:[...]}]}；AI 解析失败返回 {code:500, msg}
     */
    @PostMapping("/outfit/plan")
    public Map<String, Object> plan(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) {
            return error("请求体为空");
        }
        String city = str(body.get("city"));
        if (city.isBlank()) {
            return error("请先填写目的地城市");
        }
        Integer days = toInt(body.get("days"));
        String style = str(body.get("style"));
        String extra = str(body.get("extra"));
        List<Map<String, Object>> itinerary = outfitService.generatePlan(city, days, style, extra);
        if (itinerary == null || itinerary.isEmpty()) {
            return error("行程生成失败，请重试（可手动添加天数）");
        }
        Map<String, Object> out = ok();
        out.put("itinerary", itinerary);
        return out;
    }

    /**
     * 按天生成穿搭方案（每天独立一套，含推荐衣橱 itemId）。
     * body: {"days":[{"dayId","day","city","spots":[],"temp","rain","style"}], "wardrobe":[...]}
     * 返回 {code, plans:[{dayId, dayLabel, plan, recommendedTopItemId, recommendedBottomItemId}]}；
     * 单天生成失败 → 该天 plan 为空（前端提示单独重试），不阻断其他天。
     */
    @PostMapping("/outfit/generate-multi")
    public Map<String, Object> generateMulti(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) {
            return error("请求体为空");
        }
        List<OutfitService.TravelDay> days = parseDays(body.get("days"));
        if (days.isEmpty()) {
            return error("请至少填写一天行程");
        }
        List<Map<String, Object>> wardrobe = parseWardrobe(body.get("wardrobe"));
        List<OutfitService.DayPlan> plans = outfitService.generateMultiOutfit(days, wardrobe);
        Map<String, Object> out = ok();
        out.put("plans", plans);
        return out;
    }

    /**
     * 汇总生成完整旅游穿搭手册。
     * body: {"days":[...], "outfits":[{"dayId","outfit"}]}  outfit 为每天实际选用衣物的描述串
     * 返回 {code, manual}
     */
    @PostMapping("/outfit/manual")
    public Map<String, Object> manual(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) {
            return error("请求体为空");
        }
        List<OutfitService.TravelDay> days = parseDays(body.get("days"));
        if (days.isEmpty()) {
            return error("请至少填写一天行程");
        }
        List<Map<String, String>> outfits = parseOutfits(body.get("outfits"));
        String manual = outfitService.generateManual(days, outfits);
        Map<String, Object> out = ok();
        out.put("manual", manual);
        return out;
    }

    /**
     * 查询景点间真实交通路线（高德地图）。
     * body: {"city":"南京", "legs":[{"from":"音乐台","to":"老门东"}, ...]}
     * 返回 {code, routes:[{from, to, transit, driving}]}；单段失败时对应字段为错误提示
     */
    @PostMapping("/outfit/routes")
    public Map<String, Object> routes(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) {
            return error("请求体为空");
        }
        String city = str(body.get("city"));
        List<Map<String, String>> legs = parseLegs(body.get("legs"));
        if (legs.isEmpty()) {
            return error("请至少提供一段路线");
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, String> leg : legs) {
            String from = leg.getOrDefault("from", "");
            String to = leg.getOrDefault("to", "");
            if (from.isBlank() || to.isBlank()) continue;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("from", from);
            r.put("to", to);
            r.put("transit", mapService.transitRoute(from, to, city));
            r.put("driving", mapService.drivingRoute(from, to, city));
            results.add(r);
        }
        Map<String, Object> out = ok();
        out.put("routes", results);
        return out;
    }

    /**
     * 追问调整穿搭（SSE 流式）。
     * body: {"travelInfo":{...}, "wardrobe":[...], "history":[{"role":"user|assistant","text":"..."}], "question":"加一件薄外套"}
     * 事件：start → message* → done
     */
    @PostMapping(value = "/outfit/chat-stream", produces = SSE_UTF8)
    public SseEmitter outfitChatStream(@RequestBody(required = false) Map<String, Object> body) {
        SseEmitter emitter = new SseEmitter(0L);
        if (body == null) {
            emitSseError(emitter, "请求体为空");
            return emitter;
        }
        OutfitService.TravelInfo info = parseTravelInfo(body.get("travelInfo"));
        if (info.getCity() == null || info.getCity().isBlank()) {
            emitSseError(emitter, "请先填写目的地城市");
            return emitter;
        }
        List<Map<String, Object>> wardrobe = parseWardrobe(body.get("wardrobe"));
        List<Map<String, String>> history = parseHistory(body.get("history"));
        String question = str(body.get("question"));

        CompletableFuture.runAsync(() -> {
            try {
                emitter.send(SseEmitter.event().name(EVT_START).data(""));
                String reply = outfitService.adjustOutfit(info, wardrobe, history, question);
                for (String chunk : chunkText(reply)) {
                    emitter.send(SseEmitter.event().name(EVT_MESSAGE).data(chunk));
                    try {
                        Thread.sleep(40);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                emitter.send(SseEmitter.event().name(EVT_DONE).data("[DONE]"));
                emitter.complete();
            } catch (IOException e) {
                log.warn("👗 SSE 连接中断: {}", e.getMessage());
                emitter.completeWithError(e);
            } catch (Exception e) {
                log.error("👗 SSE 处理异常", e);
                try {
                    emitter.send(SseEmitter.event().name(EVT_ERROR).data("处理失败：" + e.getMessage()));
                } catch (IOException ignored) {
                }
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    /**
     * 目的地天气（结构化）。
     * GET /api/weather/{city} → {code, temp, condition}
     */
    @GetMapping("/weather/{city}")
    public Map<String, Object> weather(@PathVariable String city) {
        Map<String, Object> w = weatherService.getWeatherStruct(city);
        Map<String, Object> out = ok();
        if (w.isEmpty()) {
            out.put("temp", null);
            out.put("condition", null);
            out.put("msg", "未获取到 " + city + " 的天气，请手动填写温度");
        } else {
            out.putAll(w);
        }
        return out;
    }

    // ======================== 工具方法 ========================

    private static byte[] decodeDataUrl(String dataUrl) {
        try {
            int comma = dataUrl.indexOf(',');
            if (comma < 0) return null;
            return Base64.getDecoder().decode(dataUrl.substring(comma + 1));
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(Object o) {
        if (o == null) return "";
        String s = String.valueOf(o);
        return s.isBlank() ? "" : s.trim();
    }

    private static Integer toInt(Object o) {
        if (o == null) return 1;
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (Exception e) {
            return 1;
        }
    }

    private static Map<String, Object> ok() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 200);
        return out;
    }

    private static Map<String, Object> error(String msg) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 500);
        out.put("msg", msg);
        return out;
    }

    // ======================== SSE 工具 ========================

    private static final String SSE_UTF8 = "text/event-stream;charset=UTF-8";
    private static final String EVT_START = "start";
    private static final String EVT_MESSAGE = "message";
    private static final String EVT_DONE = "done";
    private static final String EVT_ERROR = "error";
    private static final int CHUNK_SIZE = 20;

    private void emitSseError(SseEmitter emitter, String msg) {
        try {
            emitter.send(SseEmitter.event().name(EVT_ERROR).data(msg));
        } catch (IOException e) {
            // ignore
        }
        emitter.complete();
    }

    private static List<String> chunkText(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) return chunks;
        for (int i = 0; i < text.length(); i += CHUNK_SIZE) {
            chunks.add(text.substring(i, Math.min(text.length(), i + CHUNK_SIZE)));
        }
        return chunks;
    }

    // ======================== 参数解析 ========================

    private static OutfitService.TravelInfo parseTravelInfo(Object t) {
        OutfitService.TravelInfo info = new OutfitService.TravelInfo();
        if (t instanceof Map<?, ?> tm) {
            info.setCity(str(tm.get("city")));
            info.setSpots(str(tm.get("spots")));
            info.setDays(toInt(tm.get("days")));
            info.setTemp(str(tm.get("temp")));
            info.setRain(tm.get("rain") == null ? null : Boolean.parseBoolean(String.valueOf(tm.get("rain"))));
            info.setStyle(str(tm.get("style")));
            info.setUseWardrobe(tm.get("useWardrobe") == null ? null : Boolean.parseBoolean(String.valueOf(tm.get("useWardrobe"))));
        }
        return info;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parseWardrobe(Object w) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (w instanceof List<?> raw) {
            for (Object item : raw) {
                if (item instanceof Map<?, ?> m) {
                    Map<String, Object> one = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        if (e.getKey() instanceof String k && e.getValue() != null) {
                            one.put(k, String.valueOf(e.getValue()));
                        }
                    }
                    list.add(one);
                }
            }
        }
        return list;
    }

    /** 解析按天行程列表（spots 为字符串数组） */
    @SuppressWarnings("unchecked")
    private static List<OutfitService.TravelDay> parseDays(Object s) {
        List<OutfitService.TravelDay> list = new ArrayList<>();
        if (s instanceof List<?> raw) {
            for (Object item : raw) {
                if (!(item instanceof Map<?, ?> m)) continue;
                OutfitService.TravelDay day = new OutfitService.TravelDay();
                day.setDayId(str(m.get("dayId")));
                day.setDay(toInt(m.get("day")));
                day.setCity(str(m.get("city")));
                List<String> spots = new ArrayList<>();
                Object sp = m.get("spots");
                if (sp instanceof List<?> spRaw) {
                    for (Object one : spRaw) {
                        String name = str(one);
                        if (!name.isEmpty()) spots.add(name);
                    }
                }
                day.setSpots(spots);
                day.setTemp(str(m.get("temp")));
                day.setRain(m.get("rain") == null ? null : Boolean.parseBoolean(String.valueOf(m.get("rain"))));
                day.setStyle(str(m.get("style")));
                list.add(day);
            }
        }
        return list;
    }

    /** 解析各景点实际选用衣物 [{spotId, outfit}] */
    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> parseOutfits(Object o) {
        List<Map<String, String>> list = new ArrayList<>();
        if (o instanceof List<?> raw) {
            for (Object item : raw) {
                if (!(item instanceof Map<?, ?> m)) continue;
                Map<String, String> one = new LinkedHashMap<>();
                one.put("spotId", str(m.get("spotId")));
                one.put("outfit", str(m.get("outfit")));
                list.add(one);
            }
        }
        return list;
    }

    /** 解析交通路线段 [{from, to}] */
    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> parseLegs(Object o) {
        List<Map<String, String>> list = new ArrayList<>();
        if (o instanceof List<?> raw) {
            for (Object item : raw) {
                if (!(item instanceof Map<?, ?> m)) continue;
                Map<String, String> one = new LinkedHashMap<>();
                one.put("from", str(m.get("from")));
                one.put("to", str(m.get("to")));
                list.add(one);
            }
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> parseHistory(Object h) {
        List<Map<String, String>> list = new ArrayList<>();
        if (h instanceof List<?> raw) {
            for (Object item : raw) {
                if (item instanceof Map<?, ?> m) {
                    Map<String, String> one = new LinkedHashMap<>();
                    one.put("role", "assistant".equals(String.valueOf(m.get("role"))) ? "assistant" : "user");
                    one.put("text", m.get("text") == null ? "" : String.valueOf(m.get("text")));
                    list.add(one);
                }
            }
        }
        return list;
    }
}
