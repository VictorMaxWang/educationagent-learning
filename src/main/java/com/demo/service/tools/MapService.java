package com.demo.service.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * 高德地图业务服务
 * 提供地理编码/逆编码、路线规划、POI搜索、距离测量
 * 依赖：amap.key（高德 Web 服务 API Key）
 * 文档：https://lbs.amap.com/api/webservice/guide/api
 */
@Slf4j
@Service
public class MapService {

    @Value("${amap.key}")


    private String amapKey;

    @Resource
    private RestTemplate restTemplate;

    // 高德 API 地址
    private static final String GEO_URL = "https://restapi.amap.com/v3/geocode/geo";
    private static final String REGEO_URL = "https://restapi.amap.com/v3/geocode/regeo";
    private static final String DIRECTION_URL = "https://restapi.amap.com/v3/direction/driving";
    private static final String WALKING_URL = "https://restapi.amap.com/v3/direction/walking";
    private static final String TRANSIT_URL = "https://restapi.amap.com/v3/direction/transit/integrated";
    private static final String POI_URL = "https://restapi.amap.com/v3/place/text";
    private static final String AROUND_URL = "https://restapi.amap.com/v3/place/around";
    private static final String DISTANCE_URL = "https://restapi.amap.com/v3/distance";

    // ======================== 1. 地理编码 ========================

    /**
     * 地址 → 经纬度
     * @param address 详细地址，如"北京市朝阳区望京SOHO T1"
     * @param city    所在城市，如"北京"（可为空）
     * @return 格式化结果 "经度,纬度" 或错误提示
     *
     *
     */
    public String geocode(String address, String city) {
        try {
            JSONObject params = new JSONObject();
            params.put("key", amapKey);
            params.put("address", address);
            if (city != null && !city.isBlank()) params.put("city", city);

            String url = buildUrl(GEO_URL, params);
            JSONObject resp = GET(url);

            if (!"1".equals(resp.getString("status"))) {
                return "地理编码失败：" + resp.getString("info");
            }
            JSONArray geocodes = resp.getJSONArray("geocodes");
            if (geocodes == null || geocodes.isEmpty()) {
                return "未找到该地址的坐标信息";
            }
            JSONObject first = geocodes.getJSONObject(0);
            String location = first.getString("location");
            String formatted = first.getString("formatted_address");
            return "📍 " + formatted + "\n  坐标：" + location;
        } catch (Exception e) {
            log.error("地理编码失败", e);
            return "地理编码服务暂时不可用";
        }
    }

    // ======================== 2. 逆地理编码 ========================

    /**
     * 经纬度 → 详细地址
     * @param location 经纬度，格式 "116.397428,39.90923"
     * @return 格式化地址信息
     */
    public String reverseGeocode(String location) {
        try {
            JSONObject params = new JSONObject();
            params.put("key", amapKey);
            params.put("location", location);
            params.put("extensions", "base");

            String url = buildUrl(REGEO_URL, params);
            JSONObject resp = GET(url);

            if (!"1".equals(resp.getString("status"))) {
                return "逆地理编码失败：" + resp.getString("info");
            }
            JSONObject regeocode = resp.getJSONObject("regeocode");
            if (regeocode == null) return "未找到该坐标的地址信息";

            String formatted = regeocode.getString("formatted_address");
            JSONObject addressComponent = regeocode.getJSONObject("addressComponent");

            StringBuilder sb = new StringBuilder("📍 坐标 ").append(location).append("\n");
            sb.append("  地址：").append(formatted != null ? formatted : "未知");
            if (addressComponent != null) {
                String businessAreas = addressComponent.getString("businessAreas");
                if (businessAreas != null && !businessAreas.isBlank() && !"[]".equals(businessAreas)) {
                    sb.append("\n  所在商圈：").append(businessAreas);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("逆地理编码失败", e);
            return "逆地理编码服务暂时不可用";
        }
    }

    // ======================== 3. 驾车路线规划 ========================

    /**
     * 驾车路线规划
     * @param origin      起点坐标 "116.397428,39.90923" 或地址
     * @param destination 终点坐标或地址
     * @return 路线详情（距离、时间、路段）
     */
    public String drivingRoute(String origin, String destination) {
        try {
            // 自动将地址转为坐标
            String originLoc = resolveLocation(origin);
            if (originLoc == null) return "无法识别起点：" + origin;
            String destLoc = resolveLocation(destination);
            if (destLoc == null) return "无法识别终点：" + destination;

            JSONObject params = new JSONObject();
            params.put("key", amapKey);
            params.put("origin", originLoc);
            params.put("destination", destLoc);
            params.put("extensions", "base");
            params.put("strategy", 0); // 0=速度优先

            String url = buildUrl(DIRECTION_URL, params);
            JSONObject resp = GET(url);

            if (!"1".equals(resp.getString("status"))) {
                return "路线规划失败：" + resp.getString("info");
            }
            JSONObject route = resp.getJSONObject("route");
            if (route == null) return "未找到驾车路线";

            JSONArray paths = route.getJSONArray("paths");
            if (paths == null || paths.isEmpty()) return "未找到驾车路线";

            JSONObject best = paths.getJSONObject(0);
            long distance = best.getLongValue("distance");
            long duration = best.getLongValue("duration");
            String tolls = best.getString("tolls");

            // 提取路段步骤简要描述
            JSONArray steps = best.getJSONArray("steps");
            StringBuilder detail = new StringBuilder();
            if (steps != null && steps.size() > 0) {
                int count = Math.min(steps.size(), 7); // 最多 7 条提示
                for (int i = 0; i < count; i++) {
                    String instruction = steps.getJSONObject(i).getString("instruction");
                    if (instruction != null) detail.append("  ").append(i + 1).append(". ").append(instruction).append("\n");
                }
                if (steps.size() > count) detail.append("  ...共").append(steps.size()).append("个路段\n");
            }

            return "🚗 驾车路线\n"
                    + "  起点：" + origin + "\n"
                    + "  终点：" + destination + "\n"
                    + "  距离：" + formatDistance(distance) + "\n"
                    + "  预计耗时：" + formatDuration(duration) + "\n"
                    + (tolls != null && !"0".equals(tolls) ? "  过路费：" + tolls + "元\n" : "")
                    + "\n📋 导航步骤：\n" + detail;
        } catch (Exception e) {
            log.error("驾车路线规划失败", e);
            return "路线规划服务暂时不可用";
        }
    }

    /**
     * 驾车路线规划（带城市约束，出行模块用）
     * 景点名 + 城市 → 坐标时带城市约束，避免重名景点解析到异地。
     */
    public String drivingRoute(String origin, String destination, String city) {
        try {
            String originLoc = resolveLocation(origin, city);
            if (originLoc == null) return "无法识别起点：" + origin;
            String destLoc = resolveLocation(destination, city);
            if (destLoc == null) return "无法识别终点：" + destination;

            JSONObject params = new JSONObject();
            params.put("key", amapKey);
            params.put("origin", originLoc);
            params.put("destination", destLoc);
            params.put("extensions", "base");
            params.put("strategy", 0); // 0=速度优先

            String url = buildUrl(DIRECTION_URL, params);
            JSONObject resp = GET(url);

            if (!"1".equals(resp.getString("status"))) {
                return "路线规划失败：" + resp.getString("info");
            }
            JSONObject route = resp.getJSONObject("route");
            if (route == null) return "未找到驾车路线";

            JSONArray paths = route.getJSONArray("paths");
            if (paths == null || paths.isEmpty()) return "未找到驾车路线";

            JSONObject best = paths.getJSONObject(0);
            long distance = best.getLongValue("distance");
            long duration = best.getLongValue("duration");
            String tolls = best.getString("tolls");

            JSONArray steps = best.getJSONArray("steps");
            StringBuilder detail = new StringBuilder();
            if (steps != null && steps.size() > 0) {
                int count = Math.min(steps.size(), 7); // 最多 7 条提示
                for (int i = 0; i < count; i++) {
                    String instruction = steps.getJSONObject(i).getString("instruction");
                    if (instruction != null) detail.append("  ").append(i + 1).append(". ").append(instruction).append("\n");
                }
                if (steps.size() > count) detail.append("  ...共").append(steps.size()).append("个路段\n");
            }

            return "🚗 驾车路线\n"
                    + "  起点：" + origin + "\n"
                    + "  终点：" + destination + "\n"
                    + "  距离：" + formatDistance(distance) + "\n"
                    + "  预计耗时：" + formatDuration(duration) + "\n"
                    + (tolls != null && !"0".equals(tolls) ? "  过路费：" + tolls + "元\n" : "")
                    + "\n📋 导航步骤：\n" + detail;
        } catch (Exception e) {
            log.error("驾车路线规划失败", e);
            return "路线规划服务暂时不可用";
        }
    }

    // ======================== 4. 步行路线规划 ========================

    /**
     * 步行路线规划
     */
    public String walkingRoute(String origin, String destination) {
        try {
            String originLoc = resolveLocation(origin);
            if (originLoc == null) return "无法识别起点：" + origin;
            String destLoc = resolveLocation(destination);
            if (destLoc == null) return "无法识别终点：" + destination;

            JSONObject params = new JSONObject();
            params.put("key", amapKey);
            params.put("origin", originLoc);
            params.put("destination", destLoc);

            String url = buildUrl(WALKING_URL, params);
            JSONObject resp = GET(url);

            if (!"1".equals(resp.getString("status"))) {
                return "步行路线规划失败：" + resp.getString("info");
            }
            JSONObject route = resp.getJSONObject("route");
            if (route == null) return "未找到步行路线";

            JSONArray paths = route.getJSONArray("paths");
            if (paths == null || paths.isEmpty()) return "未找到步行路线";

            JSONObject best = paths.getJSONObject(0);
            long distance = best.getLongValue("distance");
            long duration = best.getLongValue("duration");

            JSONArray steps = best.getJSONArray("steps");
            StringBuilder detail = new StringBuilder();
            if (steps != null && steps.size() > 0) {
                int count = Math.min(steps.size(), 5);
                for (int i = 0; i < count; i++) {
                    String instruction = steps.getJSONObject(i).getString("instruction");
                    if (instruction != null) detail.append("  ").append(i + 1).append(". ").append(instruction).append("\n");
                }
                if (steps.size() > count) detail.append("  ...共").append(steps.size()).append("个路段\n");
            }

            return "🚶 步行路线\n"
                    + "  起点：" + origin + "\n"
                    + "  终点：" + destination + "\n"
                    + "  距离：" + formatDistance(distance) + "\n"
                    + "  预计耗时：" + formatDuration(duration) + "\n"
                    + "\n📋 步行步骤：\n" + detail;
        } catch (Exception e) {
            log.error("步行路线规划失败", e);
            return "步行路线规划服务暂时不可用";
        }
    }

    // ======================== 5. 公交路线规划 ========================

    /**
     * 公交/地铁路线规划
     */
    public String transitRoute(String origin, String destination, String city) {
        try {
            String originLoc = resolveLocation(origin);
            if (originLoc == null) return "无法识别起点：" + origin;
            String destLoc = resolveLocation(destination);
            if (destLoc == null) return "无法识别终点：" + destination;

            JSONObject params = new JSONObject();
            params.put("key", amapKey);
            params.put("origin", originLoc);
            params.put("destination", destLoc);
            params.put("city", city != null ? city : "北京");
            params.put("extensions", "base");

            String url = buildUrl(TRANSIT_URL, params);
            JSONObject resp = GET(url);

            if (!"1".equals(resp.getString("status"))) {
                return "公交路线规划失败：" + resp.getString("info");
            }
            JSONObject route = resp.getJSONObject("route");
            if (route == null) return "未找到公交路线";

            JSONArray transits = route.getJSONArray("transits");
            if (transits == null || transits.isEmpty()) return "未找到公交路线";

            // 取前 2 条方案
            StringBuilder sb = new StringBuilder("🚌 公交路线\n")
                    .append("  起点：").append(origin).append("\n")
                    .append("  终点：").append(destination).append("\n\n");
            int maxPlans = Math.min(transits.size(), 2);
            for (int p = 0; p < maxPlans; p++) {
                JSONObject transit = transits.getJSONObject(p);
                long cost = transit.getLongValue("duration");
                long walkDist = transit.getLongValue("walking_distance");
                long totalDist = transit.getLongValue("distance");

                JSONArray segments = transit.getJSONArray("segments");
                StringBuilder segDesc = new StringBuilder();
                if (segments != null) {
                    for (int i = 0; i < segments.size(); i++) {
                        JSONObject seg = segments.getJSONObject(i);
                        JSONObject bus = seg.getJSONObject("bus");
                        JSONObject walk = seg.getJSONObject("walking");
                        if (bus != null) {
                            String busLine = bus.getString("busline");
                            JSONArray buslines = bus.getJSONArray("buslines");
                            if (buslines != null && !buslines.isEmpty()) {
                                String lineName = buslines.getJSONObject(0).getString("name");
                                String departStop = buslines.getJSONObject(0).getString("departure_stop");
                                String arriveStop = buslines.getJSONObject(0).getString("arrival_stop");
                                segDesc.append("    🚇 ").append(lineName)
                                        .append("（").append(departStop).append(" → ").append(arriveStop).append("）\n");
                            }
                        } else if (walk != null) {
                            long wd = walk.getLongValue("distance");
                            if (wd > 0) segDesc.append("    🚶 步行").append(wd).append("米\n");
                        }
                    }
                }

                sb.append("方案").append(p + 1).append("（耗时").append(formatDuration(cost)).append("，").append(formatDistance(totalDist)).append("）：\n")
                        .append(segDesc).append("\n");
            }
            if (transits.size() > maxPlans) sb.append("  ...共").append(transits.size()).append("种方案\n");

            return sb.toString();
        } catch (Exception e) {
            log.error("公交路线规划失败", e);
            return "公交路线规划服务暂时不可用";
        }
    }

    // ======================== 6. POI 周边搜索 ========================

    /**
     * 搜索指定地点的 POI（兴趣点）
     * @param keywords 搜索关键词，如"火锅、医院、银行"
     * @param city     所在城市，如"北京"
     * @return POI 列表
     */
    public String searchPoi(String keywords, String city, String pageSize) {
        try {
            JSONObject params = new JSONObject();
            params.put("key", amapKey);
            params.put("keywords", keywords);
            if (city != null && !city.isBlank()) params.put("city", city);
            int size = 5;
            if (pageSize != null && !pageSize.isBlank()) {
                try { size = Math.min(Integer.parseInt(pageSize), 20); } catch (NumberFormatException ignored) {}
            }
            params.put("offset", size);
            params.put("page", 1);
            params.put("extensions", "all");

            String url = buildUrl(POI_URL, params);
            JSONObject resp = GET(url);

            if (!"1".equals(resp.getString("status"))) {
                return "POI 搜索失败：" + resp.getString("info");
            }
            JSONArray pois = resp.getJSONArray("pois");
            if (pois == null || pois.isEmpty()) {
                return "未找到与【" + keywords + "】相关的信息";
            }

            long count = resp.getLongValue("count");
            StringBuilder sb = new StringBuilder("🔍 找到 ").append(count).append(" 个结果：\n\n");
            int max = Math.min(pois.size(), 10);
            for (int i = 0; i < max; i++) {
                JSONObject poi = pois.getJSONObject(i);
                String name = poi.getString("name");
                String address = poi.getString("address");
                String location = poi.getString("location");
                String distance = poi.getString("distance");
                String tel = poi.getString("tel");

                sb.append(i + 1).append(". ").append(name).append("\n");
                if (address != null && !address.isBlank()) sb.append("     地址：").append(address).append("\n");
                if (tel != null && !tel.isBlank()) sb.append("     电话：").append(tel).append("\n");
                if (distance != null && !distance.isBlank() && !"0".equals(distance)) {
                    try {
                        sb.append("     距中心点：约").append(formatDistance(Long.parseLong(distance))).append("\n");
                    } catch (NumberFormatException e) {
                        log.debug("POI distance 非数字格式，跳过: {}", distance);
                    }
                }
                sb.append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.error("POI 搜索失败", e);
            return "POI 搜索服务暂时不可用";
        }
    }

    // ======================== 6.5 周边搜索 + 坐标解析（周边餐馆用） ========================

    /**
     * 高德周边餐饮 POI 搜索（types=050000）。
     * @param location 中心点坐标 "经度,纬度"
     * @param radius   搜索半径（米）
     * @param keywords 附加关键词，可空
     */
    public JSONObject placeAround(String location, int radius, String keywords) {
        try {
            JSONObject params = new JSONObject();
            params.put("key", amapKey);
            params.put("location", location);
            params.put("types", "050000"); // 餐饮
            params.put("radius", radius);
            if (keywords != null && !keywords.isBlank()) params.put("keywords", keywords);
            params.put("offset", 20);
            params.put("page", 1);
            params.put("extensions", "all");
            String url = buildUrl(AROUND_URL, params);
            JSONObject resp = GET(url);
            if (!"1".equals(resp.getString("status"))) {
                log.warn("周边搜索失败: info={}", resp.getString("info"));
            }
            return resp;
        } catch (Exception e) {
            log.error("周边搜索异常", e);
            return null;
        }
    }

    /** 地址/地点名 → 坐标 "经度,纬度"（供餐厅搜索中心点定位用），无法解析返回 null。 */
    public String locationToCoord(String address) {
        return resolveLocation(address, null);
    }

    /** 带城市约束的地点名 → 坐标（避免重名景点解析到异地） */
    public String locationToCoord(String address, String city) {
        return resolveLocation(address, city);
    }

    // ======================== 7. 距离测量 ========================

    /**
     * 测量两点间的直线距离
     * @param origins  起点坐标，支持多个用 "|" 分隔
     * @param dest     终点坐标
     * @param type     0=直线, 1=驾车, 2=骑行, 3=步行
     * @return 距离结果
     */
    public String measureDistance(String origins, String dest, String type) {
        try {
            JSONObject params = new JSONObject();
            params.put("key", amapKey);
            params.put("origins", origins);
            params.put("destination", dest);
            params.put("type", type != null && !type.isBlank() ? type : "0");

            String url = buildUrl(DISTANCE_URL, params);
            JSONObject resp = GET(url);

            if (!"1".equals(resp.getString("status"))) {
                return "距离测量失败：" + resp.getString("info");
            }
            JSONArray results = resp.getJSONArray("results");
            if (results == null || results.isEmpty()) return "距离测量无结果";

            StringBuilder sb = new StringBuilder("📏 距离测量\n");
            String[] originArr = origins.split("\\|");
            for (int i = 0; i < results.size(); i++) {
                JSONObject r = results.getJSONObject(i);
                long d = r.getLongValue("distance");
                String originLabel = i < originArr.length ? originArr[i] : "点" + (i + 1);
                sb.append("  ").append(originLabel).append(" → ").append(dest).append("：").append(formatDistance(d)).append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.error("距离测量失败", e);
            return "距离测量服务暂时不可用";
        }
    }

    // ======================== 公共工具方法 ========================

    /**
     * 解析地址/坐标为高德标准坐标
     * 如果输入的是 "经度,纬度" 格式则直接返回
     * 否则调用地理编码转换
     */
    private String resolveLocation(String input) {
        return resolveLocation(input, null);
    }

    /** 地址/坐标 → 高德坐标（带城市约束，景点名更可靠；无法地理编码时走 POI 关键词兜底） */
    private String resolveLocation(String input, String city) {
        if (input == null || input.isBlank()) return null;
        // 检查是否已经是坐标格式（经度,纬度，用逗号分隔的两个数字）
        if (input.matches("\\d+\\.?\\d*,\\d+\\.?\\d*")) {
            return input;
        }
        // ① 地理编码（带城市约束）
        try {
            JSONObject params = new JSONObject();
            params.put("key", amapKey);
            params.put("address", input);
            if (city != null && !city.isBlank()) params.put("city", city);
            String url = buildUrl(GEO_URL, params);
            JSONObject resp = GET(url);
            if ("1".equals(resp.getString("status"))) {
                JSONArray geocodes = resp.getJSONArray("geocodes");
                if (geocodes != null && !geocodes.isEmpty()) {
                    String loc = geocodes.getJSONObject(0).getString("location");
                    if (loc != null && !loc.isBlank()) return loc;
                }
            }
        } catch (Exception e) {
            log.warn("地理编码转坐标失败：{}", input);
        }
        // ② 兜底：POI 关键词搜索（带城市约束，景点名更可靠）
        if (city != null && !city.isBlank()) {
            try {
                JSONObject params = new JSONObject();
                params.put("key", amapKey);
                params.put("keywords", input);
                params.put("city", city);
                params.put("offset", 1);
                params.put("page", 1);
                String url = buildUrl(POI_URL, params);
                JSONObject resp = GET(url);
                if ("1".equals(resp.getString("status"))) {
                    JSONArray pois = resp.getJSONArray("pois");
                    if (pois != null && !pois.isEmpty()) {
                        String loc = pois.getJSONObject(0).getString("location");
                        if (loc != null && !loc.isBlank()) return loc;
                    }
                }
            } catch (Exception e) {
                log.warn("POI 转坐标失败：{}", input);
            }
        }
        return null;
    }

    private JSONObject GET(String url) {
        String json = restTemplate.getForObject(url, String.class);
        return JSON.parseObject(json);
    }

    private String buildUrl(String base, JSONObject params) {
        StringBuilder sb = new StringBuilder(base).append("?");
        for (String key : params.keySet()) {
            Object val = params.get(key);
            if (val != null) {
                sb.append(key).append("=").append(val).append("&");
            }
        }
        sb.deleteCharAt(sb.length() - 1);
        return sb.toString();
    }

    private static String formatDistance(long meters) {
        if (meters < 1000) return meters + "米";
        return String.format("%.1f公里", meters / 1000.0);
    }

    private static String formatDuration(long seconds) {
        if (seconds < 60) return seconds + "秒";
        if (seconds < 3600) return (seconds / 60) + "分钟";
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        return h + "小时" + (m > 0 ? m + "分钟" : "");
    }
}
