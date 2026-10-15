package com.demo.travel;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.demo.service.preprocessing.VisionService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 衣橱识别服务 — 复用 Qwen-VL 视觉模型识别衣物属性。
 * <p>
 * 输入衣物图片 → 结构化输出：品类(category)/细分类(subCategory)/颜色(color)/面料(fabric)/季节(season[])/风格(style[])。
 * 识别失败返回 null，由上层转为业务错误码 + 提示文案。
 */
@Slf4j
@Service
public class WardrobeService {

    /** Qwen-VL 结构化识别提示词（含明确品类定义，避免全部归为上衣） */
    private static final String RECOGNIZE_PROMPT =
            "你是专业的服装识别专家。请识别这张图片中的服装单品，先判断它穿在身体的哪个部位，再只输出一个 JSON 对象，不要任何解释文字。\n"
            + "格式：{\"category\":\"top\",\"subCategory\":\"针织衫\",\"color\":\"白色\",\"fabric\":\"棉\","
            + "\"season\":[\"spring\",\"autumn\"],\"style\":[\"休闲\"]}\n"
            + "品类定义（category 必须精确匹配其中一个）：\n"
            + "- top 上衣：覆盖躯干上部的衣物（T恤、衬衫、卫衣、毛衣、开衫、吊带，不含外套）\n"
            + "- bottom 下装：覆盖腿部的衣物（长裤、短裤、半身裙、阔腿裤）\n"
            + "- dress 连衣裙/连体衣：上下连体的整件衣服\n"
            + "- coat 外套：穿在最外层的外套、风衣、夹克、大衣、羽绒服\n"
            + "- shoes 鞋履：穿在脚上的鞋、靴、拖鞋、凉鞋\n"
            + "- accessory 配饰：帽子、包、围巾、腰带、眼镜、首饰\n"
            + "判断依据：图片中服装主体的形态和穿着部位。请务必区分：外套≠上衣、上衣≠下装、连衣裙≠上衣。\n"
            + "输出图中识别到的所有衣物，每件衣物一个 JSON 对象（可以输出多个对象）；如果图中只有一件衣物则只输出一个对象。\n"
            + "season 从 spring/summer/autumn/winter 中选择适配季节。\n"
            + "style 从 休闲/通勤/运动/法式/简约/复古/甜美/民族风/度假 中选择。\n"
            + "如果图片不是衣物或无法识别，输出 {\"category\":\"unknown\",\"subCategory\":\"\",\"color\":\"\","
            + "\"fabric\":\"\",\"season\":[],\"style\":[]}";

    @Resource
    private VisionService visionService;

    /**
     * 识别衣物图片，返回全部识别的衣物（可能多件）。
     *
     * @param imageBytes 图片二进制
     * @return 每件 {category, subCategory, color, fabric, season[], style[]}；全部失败/非衣物返回 null
     */
    public List<JSONObject> recognizeClothing(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            return null;
        }
        String desc = visionService.describe(imageBytes, RECOGNIZE_PROMPT);
        if (desc == null || desc.isBlank()) {
            log.warn("👗 衣物识别无返回");
            return null;
        }

        String json = desc.trim();
        // 容忍 ```json 代码块
        if (json.startsWith("```")) {
            json = json.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("```\\s*$", "");
        }

        List<JSONObject> garments = new ArrayList<>();
        for (String obj : extractJsonObjects(json)) {
            try {
                JSONObject o = JSON.parseObject(obj);
                if (o == null) continue;
                String category = o.getString("category");
                if (category == null || category.isBlank() || "unknown".equalsIgnoreCase(category)) continue;
                garments.add(o);
            } catch (Exception e) {
                log.warn("👗 单件解析失败跳过: {}", e.getMessage());
            }
        }
        if (garments.isEmpty()) {
            log.warn("👗 图片非衣物或无法识别: {}", truncate(desc, 200));
            return null;
        }
        log.info("👗 衣物识别成功，共 {} 件: {}", garments.size(), truncate(desc, 200));
        return garments;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** 提取所有完整 JSON 对象（花括号配平），容忍前导/多个对象 */
    private static List<String> extractJsonObjects(String json) {
        List<String> list = new ArrayList<>();
        int from = 0;
        while (from < json.length()) {
            int start = json.indexOf('{', from);
            if (start < 0) break;
            int depth = 0;
            int end = -1;
            for (int i = start; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        end = i;
                        break;
                    }
                }
            }
            if (end < 0) break;
            list.add(json.substring(start, end + 1));
            from = end + 1;
        }
        return list;
    }
}
