package com.demo.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 火车站名称转12306站点编码工具类
 * 功能：本地读取车站JSON字典，实现站名快速转铁路标准编码
 * 优势：启动一次性加载，后续查询O(1)速度，无需调用第三方接口
 * 匹配规则：精确匹配 > 去除站字匹配 > 前缀模糊唯一匹配
 * 数据源：all_train_stations.json 全网12306官方标准车站库
 */
@Slf4j
@Component
public class StationNameUtil {

    /**
     * 车站名称 -> 车站编码 内存缓存映射表
     * key：中文车站全名
     * value：12306官方三位站点编码
     */
    private final Map<String, String> codeMap = new HashMap<>();

    /**
     * 读取项目资源目录下的车站JSON文件
     * classpath: 指向resources目录下的文件
     */
    @Value("classpath:all_train_stations.json")
    private Resource stationJson;

    /**
     * 项目Bean初始化完成后自动执行
     * 项目启动时加载全部车站数据到内存，只执行一次
     */
    @PostConstruct
    public void init() {
        // 以UTF-8编码读取JSON文件流，防止中文乱码
        try (InputStreamReader reader = new InputStreamReader(stationJson.getInputStream(), StandardCharsets.UTF_8)) {
            // 解析全站站点JSON根对象
            JSONObject root = JSON.parseObject(reader);
            // 遍历所有车站名称
            for (String name : root.keySet()) {
                // 根据车站名取出对应的官方站点编码
                String code = root.getJSONObject(name).getString("station_code");
                // 过滤空编码，只存入有效站点
                if (code != null && !code.isBlank()) {
                    codeMap.put(name, code);
                }
            }
            log.info("✅ 加载 {} 个火车站编码映射", codeMap.size());
        } catch (Exception e) {
            log.error("❌ 加载火车站编码 JSON 失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 对外暴露核心转换方法：中文站名转铁路编码
     * @param input 用户输入原始站名 例：杭州东、杭州东站、徐州东
     * @return 标准站点编码 例：HVU、KZH，识别失败返回null
     */
    public String toCode(String input) {
        if (input == null || input.isBlank()) return null;
        String key = input.trim();

        // 第一层：完全精准匹配（用户输入和库内站名一字不差）
        String code = codeMap.get(key);
        if (code != null) {
            log.info("站名编码（精确）：{} → {}", key, code);
            return code;
        }

        // 第二层：兼容用户多打“站”字场景（杭州东站 → 截取为杭州东 再次匹配）
        if (key.endsWith("站") && key.length() > 1) {
            code = codeMap.get(key.substring(0, key.length() - 1));
            if (code != null) {
                log.info("站名编码（去站）：{} → {}", key, code);
                return code;
            }
        }

        // 第三层：前缀模糊匹配兜底
        String fuzzy = fuzzyMatch(key);
        if (fuzzy != null) {
            log.info("站名编码（模糊）：{} → {}", key, fuzzy);
            return fuzzy;
        }

        // 三层规则全部匹配失败，记录警告日志
        log.warn("站名编码未找到: {}", key);
        return null;
    }

    /**
     * 私有模糊匹配方法
     * 匹配逻辑：优先匹配以输入文字开头的车站
     * 限制条件：仅匹配出唯一结果才生效，避免多站混淆
     * 例：输入杭州 → 匹配过多，直接放弃模糊匹配
     * 例：输入杭州西 → 唯一匹配成功
     */
    private String fuzzyMatch(String key) {
        // 存储匹配到的编码、统计匹配数量
        String prefixMatch = null;
        int matchCount = 0;

        // 遍历所有已加载车站
        for (Map.Entry<String, String> entry : codeMap.entrySet()) {
            String name = entry.getKey();
            // 判断当前车站名是否以用户输入内容开头
            if (name.startsWith(key)) {
                prefixMatch = entry.getValue();
                matchCount++;
            }
        }

        // 只匹配到1个车站，直接返回编码
        if (matchCount == 1) {
            return prefixMatch;
        }

        // 多个前缀匹配时，看看 key 本身是不是某个站的去掉东西（如"徐州"匹配"徐州东"和"徐州"）
        // 如果 key 恰好等于某个站名，精确匹配已在前面处理，这里选前缀最短的（通常就是城市主站）
        if (matchCount > 1) {
            // 优先返回完全等于 key 的那个（如"徐州" → "徐州"而非"徐州东"）
            String exact = codeMap.get(key);
            if (exact != null) return exact;
        }

        return null;
    }
}
