package com.demo.service.travel;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.demo.entity.TravelRestaurantFavoriteEntity;
import com.demo.entity.TravelScheduleRestEntity;
import com.demo.mapper.TravelRestaurantFavoriteMapper;
import com.demo.mapper.TravelScheduleRestMapper;
import com.demo.service.tools.MapService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 旅行·美食推荐服务。
 * <p>
 * 高德 place/around 周边餐饮 POI（types=050000）+ 后端内存二次过滤（菜系/人均/评分/标签）
 * + 收藏 + 加入每日行程。高德 key 在后端，前端不暴露。
 */
@Slf4j
@Service
public class RestaurantService {

    @Resource
    private MapService mapService;
    @Resource
    private TravelRestaurantFavoriteMapper favMapper;
    @Resource
    private TravelScheduleRestMapper scheduleRestMapper;

    // ======================== 搜索 ========================

    /**
     * 周边餐馆搜索：高德粗筛 + 内存二次过滤 + 排序。
     *
     * @param locationName 地点名（lon/lat 为空时用其 geocode 定位）
     * @param lon          中心点经度（可空，优先用）
     * @param lat          中心点纬度
     * @param radius       搜索半径（米）
     * @param preference   偏好 {cuisineList,minPrice,maxPrice,minScore,tagList}
     * @param sortType     DISTANCE / SCORE / PRICE_ASC
     */
    public Map<String, Object> search(String locationName, Double lon, Double lat, String city,
                                      int radius, JSONObject preference, String sortType) {
        Map<String, Object> out = new LinkedHashMap<>();
        String location;
        if (lon != null && lat != null) {
            location = lon + "," + lat;
        } else {
            location = (locationName != null && !locationName.isBlank())
                    ? mapService.locationToCoord(locationName, city) : null;
        }
        if (location == null || location.isBlank()) {
            out.put("code", 400);
            out.put("msg", "无法定位中心点，请提供经纬度或有效地点名");
            return out;
        }

        JSONObject resp = mapService.placeAround(location, radius > 0 ? radius : 2000, "");
        if (resp == null || !"1".equals(resp.getString("status"))) {
            out.put("code", 500);
            out.put("msg", "周边搜索失败：" + (resp != null ? resp.getString("info") : "服务异常"));
            return out;
        }

        JSONArray pois = resp.getJSONArray("pois");
        List<Map<String, Object>> list = new ArrayList<>();
        if (pois != null) {
            for (int i = 0; i < pois.size(); i++) {
                Map<String, Object> r = mapPoi(pois.getJSONObject(i));
                if (matchesPreference(r, preference)) list.add(r);
            }
        }
        sortRestaurants(list, sortType);
        log.info("🍽 周边餐馆搜索: 中心={} 半径={} 粗筛={} 命中={} sort={}",
                location, radius, pois != null ? pois.size() : 0, list.size(), sortType);
        out.put("code", 200);
        out.put("data", list);
        return out;
    }

    // ======================== 收藏 ========================

    public Map<String, Object> favorite(String travelPlanId, String poiId, String name,
                                        String lon, String lat, Integer avgCost, Double rating) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (travelPlanId == null || poiId == null || poiId.isBlank()) {
            out.put("code", 400);
            out.put("msg", "缺少行程ID或POI ID");
            return out;
        }
        if (favMapper.findByPlanAndPoi(travelPlanId, poiId) != null) {
            out.put("code", 200);
            out.put("msg", "已收藏过");
            return out;
        }
        TravelRestaurantFavoriteEntity e = new TravelRestaurantFavoriteEntity();
        e.setTravelPlanId(travelPlanId);
        e.setPoiId(poiId);
        e.setName(name);
        e.setLon(lon);
        e.setLat(lat);
        e.setAvgCost(avgCost);
        e.setRating(rating);
        favMapper.insert(e);
        log.info("🍽 收藏餐馆: plan={} poi={} name={}", travelPlanId, poiId, name);
        out.put("code", 200);
        out.put("msg", "收藏成功");
        return out;
    }

    public Map<String, Object> listFavorites(String travelPlanId) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<TravelRestaurantFavoriteEntity> rows = (travelPlanId == null || travelPlanId.isBlank())
                ? List.of() : favMapper.findByPlan(travelPlanId);
        List<Map<String, Object>> list = new ArrayList<>();
        for (TravelRestaurantFavoriteEntity f : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("poiId", f.getPoiId());
            m.put("name", f.getName());
            m.put("lon", f.getLon());
            m.put("lat", f.getLat());
            m.put("avgCost", f.getAvgCost());
            m.put("rating", f.getRating());
            list.add(m);
        }
        out.put("code", 200);
        out.put("data", list);
        return out;
    }

    // ======================== 加入行程 ========================

    public Map<String, Object> addRestaurantToSchedule(String scheduleId, String poiId, String name,
                                                       String timePeriod, String remark) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (scheduleId == null || scheduleId.isBlank() || poiId == null || poiId.isBlank()) {
            out.put("code", 400);
            out.put("msg", "缺少行程ID或POI ID");
            return out;
        }
        TravelScheduleRestEntity e = new TravelScheduleRestEntity();
        e.setScheduleId(scheduleId);
        e.setPoiId(poiId);
        e.setName(name);
        e.setTimePeriod(timePeriod);
        e.setRemark(remark);
        scheduleRestMapper.insert(e);
        log.info("🍽 加入行程: schedule={} period={} name={}", scheduleId, timePeriod, name);
        out.put("code", 200);
        out.put("msg", "已加入行程");
        return out;
    }

    // ======================== 内部方法 ========================

    /** 高德 POI → 餐馆结构 {name,address,distance,rating,avgCost,cuisine,businessTime,photos,poiId} */
    private Map<String, Object> mapPoi(JSONObject poi) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("poiId", poi.getString("id"));
        r.put("name", poi.getString("name"));
        r.put("address", poi.getString("address"));
        r.put("location", poi.getString("location"));
        r.put("distance", poi.getString("distance"));
        r.put("tel", poi.getString("tel"));

        JSONObject biz = poi.getJSONObject("biz_ext");
        double rating = 0;
        int avgCost = 0;
        String opentime = poi.getString("opentime");
        if (biz != null) {
            try { rating = biz.getDoubleValue("rating"); } catch (Exception ignored) {}
            try { avgCost = (int) biz.getDoubleValue("cost"); } catch (Exception ignored) {}
            // 营业时间在 biz_ext.opentime2 / open_time，顶层 opentime 常为空
            if (opentime == null || opentime.isBlank()) {
                opentime = biz.getString("opentime2");
                if (opentime == null || opentime.isBlank()) opentime = biz.getString("open_time");
            }
        }
        r.put("rating", rating);
        r.put("avgCost", avgCost);
        r.put("businessTime", opentime);

        JSONArray photos = poi.getJSONArray("photos");
        List<String> photoUrls = new ArrayList<>();
        if (photos != null) {
            for (int j = 0; j < photos.size() && j < 3; j++) {
                String u = photos.getJSONObject(j).getString("url");
                if (u != null && !u.isBlank()) photoUrls.add(u);
            }
        }
        r.put("photos", photoUrls);
        r.put("cuisine", inferCuisine(poi));
        return r;
    }

    /** 菜系：优先高德 type 第二/三段，其次名称关键词兜底 */
    private List<String> inferCuisine(JSONObject poi) {
        List<String> out = new ArrayList<>();
        String type = poi.getString("type");
        if (type != null) {
            String[] parts = type.split(";");
            if (parts.length >= 2 && !parts[1].isBlank()) out.add(parts[1]);
            if (parts.length >= 3 && !parts[2].isBlank() && !parts[2].equals(parts[1])) out.add(parts[2]);
        }
        String name = poi.getString("name");
        if (name != null) {
            String[][] kw = {
                    {"火锅", "火锅"}, {"日料", "日料"}, {"寿司", "日料"}, {"居酒屋", "日料"},
                    {"西餐", "西餐"}, {"牛排", "西餐"}, {"咖啡", "咖啡"}, {"奶茶", "奶茶"},
                    {"甜品", "甜品"}, {"面包", "甜品"}, {"面", "面食"}, {"烧烤", "烧烤"},
                    {"粤菜", "粤菜"}, {"川菜", "川菜"}, {"湘菜", "湘菜"}, {"本帮", "本帮菜"},
            };
            for (String[] p : kw) {
                if (name.contains(p[0]) && !out.contains(p[1])) out.add(p[1]);
            }
        }
        return out;
    }

    /** 偏好过滤：菜系/人均/最低评分/特色标签 */
    private boolean matchesPreference(Map<String, Object> r, JSONObject pref) {
        if (pref == null) return true;
        double rating = ((Number) r.getOrDefault("rating", 0)).doubleValue();
        int cost = ((Number) r.getOrDefault("avgCost", 0)).intValue();
        List<String> cuisine = (List<String>) r.get("cuisine");
        String name = String.valueOf(r.get("name"));

        Double minScore = pref.getDouble("minScore");
        if (minScore != null && rating > 0 && rating < minScore) return false;

        Integer minPrice = pref.getInteger("minPrice");
        Integer maxPrice = pref.getInteger("maxPrice");
        if (cost > 0) {
            if (minPrice != null && cost < minPrice) return false;
            if (maxPrice != null && cost > maxPrice) return false;
        }

        JSONArray cuisineList = pref.getJSONArray("cuisineList");
        if (cuisineList != null && !cuisineList.isEmpty()) {
            boolean any = false;
            for (int i = 0; i < cuisineList.size(); i++) {
                String c = cuisineList.getString(i);
                if ((cuisine != null && cuisine.contains(c)) || name.contains(c)) { any = true; break; }
            }
            if (!any) return false;
        }

        JSONArray tagList = pref.getJSONArray("tagList");
        if (tagList != null && !tagList.isEmpty()) {
            boolean any = false;
            for (int i = 0; i < tagList.size(); i++) {
                String t = tagList.getString(i);
                if (name.contains(t)) { any = true; break; }
            }
            if (!any) return false;
        }
        return true;
    }

    private void sortRestaurants(List<Map<String, Object>> list, String sortType) {
        Comparator<Map<String, Object>> cmp;
        if ("SCORE".equalsIgnoreCase(sortType)) {
            cmp = Comparator.comparingDouble((Map<String, Object> m) -> ((Number) m.getOrDefault("rating", 0)).doubleValue()).reversed();
        } else if ("PRICE_ASC".equalsIgnoreCase(sortType)) {
            cmp = Comparator.comparingInt((Map<String, Object> m) -> ((Number) m.getOrDefault("avgCost", 0)).intValue());
        } else { // DISTANCE 默认
            cmp = Comparator.comparingLong((Map<String, Object> m) -> parseDist((String) m.get("distance")));
        }
        list.sort(cmp);
    }

    private long parseDist(String d) {
        try {
            return Long.parseLong(d);
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }
}
