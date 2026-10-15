package com.demo.controller;

import com.demo.watermark.WatermarkService;
import com.demo.watermark.model.dto.WatermarkAddDTO;
import com.demo.watermark.model.dto.WatermarkRemoveDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 图片水印接口 — 桌面端「图片水印」页。
 * <p>
 * 加水印（纯 Java Graphics2D）+ 去水印（Java2D 邻域填充）。
 * 图片以 Data URL（base64）进出，后端内存处理不落盘。
 * 返回结构 {@code {code, msg, image}}，与发票 OCR 接口保持一致。
 */
@Slf4j
@RestController
@RequestMapping("/api/desktop/watermark")
public class WatermarkController {

    private static final Pattern DATA_URL = Pattern.compile("^data:([^;,]+);base64,(.*)$", Pattern.DOTALL);

    @Resource
    private WatermarkService watermarkService;

    /**
     * 加水印。
     * body: {@code {"image":"data:image/png;base64,xxx","text":"水印","fontSize":48,"opacity":0.6,"position":"center","tiled":false,"color":"#FF0000"}}
     * position: top-left / top-right / bottom-left / bottom-right / center / tiled
     */
    @PostMapping("/add")
    public Map<String, Object> add(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        String image = body != null ? str(body.get("image")) : "";
        if (image.isBlank()) {
            result.put("code", 400);
            result.put("msg", "图片数据为空");
            return result;
        }
        try {
            String mime = "image/png";
            byte[] imageBytes;
            Matcher m = DATA_URL.matcher(image);
            if (m.matches()) {
                mime = m.group(1);
                imageBytes = Base64.getDecoder().decode(m.group(2));
            } else {
                imageBytes = Base64.getDecoder().decode(image);
            }

            WatermarkAddDTO dto = WatermarkAddDTO.builder()
                    .text(nul(body, "text"))
                    .color(nul(body, "color"))
                    .fontSize(intVal(body != null ? body.get("fontSize") : null, 0))
                    .opacity(floatVal(body != null ? body.get("opacity") : null, 0f))
                    .position(nul(body, "position"))
                    .rotation(doubleVal(body != null ? body.get("rotation") : null, 0))
                    .tiled(boolVal(body != null ? body.get("tiled") : null))
                    .outputQuality(0.92f)
                    .build();

            byte[] out = watermarkService.addWatermark(imageBytes, dto);
            if (out == null) {
                result.put("code", 500);
                result.put("msg", "加水印失败");
                return result;
            }
            result.put("code", 200);
            result.put("msg", "加水印完成");
            result.put("image", "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(out));
            return result;
        } catch (Exception e) {
            log.error("💧 加水印失败", e);
            result.put("code", 500);
            result.put("msg", "加水印失败：" + e.getMessage());
            return result;
        }
    }

    /**
     * 去水印（Java2D 邻域填充，淡化水印区域）。
     * body: {@code {"image":"data:image/png;base64,xxx","regionType":"auto","position":"bottom-right"}}
     * regionType: corner=角落水印 / tiled=平铺半透明水印 / auto=自动
     */
    @PostMapping("/remove")
    public Map<String, Object> remove(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        String image = body != null ? str(body.get("image")) : "";
        if (image.isBlank()) {
            result.put("code", 400);
            result.put("msg", "图片数据为空");
            return result;
        }
        try {
            String mime = "image/png";
            byte[] imageBytes;
            Matcher m = DATA_URL.matcher(image);
            if (m.matches()) {
                mime = m.group(1);
                imageBytes = Base64.getDecoder().decode(m.group(2));
            } else {
                imageBytes = Base64.getDecoder().decode(image);
            }

            WatermarkRemoveDTO dto = WatermarkRemoveDTO.builder()
                    .regionType(nul(body, "regionType"))
                    .position(nul(body, "position"))
                    .build();

            byte[] out = watermarkService.removeWatermark(imageBytes, dto);
            if (out == null) {
                result.put("code", 500);
                result.put("msg", "去水印失败");
                return result;
            }
            result.put("code", 200);
            result.put("msg", "去水印完成");
            result.put("image", "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(out));
            return result;
        } catch (Exception e) {
            log.error("💧 去水印失败", e);
            result.put("code", 500);
            result.put("msg", "去水印失败：" + e.getMessage());
            return result;
        }
    }

    /** 取字符串字段；空白则返回 null（由实现回退默认值） */
    private static String nul(Map<String, Object> body, String key) {
        Object v = body != null ? body.get(key) : null;
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static int intVal(Object o, int def) {
        try {
            return o == null ? def : (int) Double.parseDouble(String.valueOf(o));
        } catch (Exception e) {
            return def;
        }
    }

    private static float floatVal(Object o, float def) {
        try {
            return o == null ? def : Float.parseFloat(String.valueOf(o));
        } catch (Exception e) {
            return def;
        }
    }

    private static double doubleVal(Object o, double def) {
        try {
            return o == null ? def : Double.parseDouble(String.valueOf(o));
        } catch (Exception e) {
            return def;
        }
    }

    private static boolean boolVal(Object o) {
        return o != null && (Boolean.TRUE.equals(o) || "true".equalsIgnoreCase(String.valueOf(o)));
    }
}
