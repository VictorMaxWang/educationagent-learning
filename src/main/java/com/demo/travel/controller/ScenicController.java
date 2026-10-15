package com.demo.travel.controller;

import com.demo.travel.ScenicPhotoService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 景点拍照参考接口 — {@code /api/desktop/scenic/*}。
 * body: {"scenicName":"美龄宫", "style":"氛围感"}
 * 返回 {code, imageUrls[], guideContent, tip}
 */
@Slf4j
@RestController
@RequestMapping("/api/desktop/scenic")
public class ScenicController {

    @Resource
    private ScenicPhotoService scenicPhotoService;

    @PostMapping("/photo-reference")
    public Map<String, Object> photoReference(@RequestBody(required = false) Map<String, Object> body) {
        String name = body == null ? "" : str(body.get("scenicName"));
        String style = body == null ? "" : str(body.get("style"));
        if (name.isBlank()) {
            return error("景点名不能为空");
        }
        ScenicPhotoService.ScenicPhotoResult r = scenicPhotoService.getReference(name, style);
        Map<String, Object> out = ok();
        out.put("imageUrls", r.getImageUrls());
        out.put("guideContent", r.getGuideContent());
        out.put("tip", r.getTip());
        return out;
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

    private static String str(Object o) {
        if (o == null) return "";
        String s = String.valueOf(o);
        return s.isBlank() ? "" : s.trim();
    }
}
