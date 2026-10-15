package com.demo.service.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.demo.util.StationNameUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 火车票实时查询业务服务类
 * 对接聚合数据火车票查询开放API，获取车次、时间、票价、余票等信息
 * 核心流程：用户输入站名 -> 本地工具类转为12306标准站点编码 -> 调用API查询 -> 格式化结果返回
 * 接口请求模式：采用站点编码模式查询，识别准确率更高
 * 依赖组件
 * 1. StationNameUtil：本地车站名称与编码映射转换工具
 * 2. RestTemplate：HTTP远程请求调用工具
 * 3. 配置文件train.api.key：聚合数据平台专属接口密钥
 * 接口官方文档：https://www.juhe.cn/docs/api/id/817
 */
@Slf4j
@Service
public class TrainTicketService {

    @Value("${train.api.key}")
    private String apiKey;

    @Resource
    private RestTemplate restTemplate;

    @Resource
    private StationNameUtil stationNameUtil;

    private static final String QUERY_URL = "https://apis.juhe.cn/fapigw/train/query";

    /**
     * 核心火车票查询入口方法
     * @param from 出发站点中文名称 例：杭州西、徐州东
     * @param to 到达站点中文名称 例：南京南、苏州北
     * @param date 出行日期 格式固定 yyyy-MM-dd，为空自动使用当日日期
     * @param filter 车次类型过滤条件 支持G高铁/D动车/K普快等，多类型逗号分隔
     * @return 拼接好的中文格式化车次信息文案，异常直接返回友好提示语
     */
    public String queryTickets(String from, String to, String date, String filter) {
        // 基础非空校验，出发到达站不能为空
        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            return "请输入出发站和到达站，例如：杭州东 到 上海虹桥";
        }

        try {
            // 处理查询日期，无传入日期默认使用系统当前日期
            String queryDate = date;
            if (queryDate == null || queryDate.isBlank()) {
                queryDate = java.time.LocalDate.now().toString();
            }

            // 去除首尾空格，统一站点名称格式
            String displayFrom = from.trim();
            String displayTo = to.trim();

            // 站名→编码查表（本地 JSON，零 API 调用）
            String depCode = stationNameUtil.toCode(displayFrom);
            String arrCode = stationNameUtil.toCode(displayTo);

            if (depCode == null) return "无法识别出发站：" + displayFrom + "，请使用12306标准站名（如：杭州东）";
            if (arrCode == null) return "无法识别到达站：" + displayTo + "，请使用12306标准站名（如：徐州东）";

            // 组装接口请求参数，有序Map保证参数顺序
            // search_type=2：使用 12306 站点编码查询
            Map<String, String> params = new LinkedHashMap<>();
            params.put("key", apiKey);
            params.put("search_type", "2");
            params.put("departure_station", depCode);
            params.put("arrival_station", arrCode);
            params.put("date", queryDate.trim());
            params.put("filter", (filter != null && !filter.isBlank()) ? filter.trim() : "");
            params.put("enable_booking", "");
            params.put("departure_time_range", "");

            // 拼接URL请求参数字符串，中文参数进行URL编码防止乱码
            String queryString = params.entrySet().stream()
                    .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                    .collect(Collectors.joining("&"));

            // 拼接完整可直接访问的接口请求地址
            String fullUrl = QUERY_URL + "?" + queryString;
            log.info("🚄 查询火车票：{} → {}，日期：{}（编码：{}→{}）", displayFrom, displayTo, queryDate, depCode, arrCode);
            log.debug("请求URL：{}", fullUrl);

            // 发起GET请求获取接口原始JSON响应数据
            String json = restTemplate.getForObject(fullUrl, String.class);
            // 调用响应解析方法，格式化数据为用户易读文案
            return parseTicketResponse(json, displayFrom, displayTo, queryDate);

        } catch (Exception e) {
            log.error("火车票查询失败: {}", e.getMessage());
            return "🚫 火车票查询服务暂时不可用，请稍后再试";
        }
    }

    /**
     * 解析火车票接口返回的原始JSON数据
     * 按照业务需求提取车次、时间、时长、票价、余票、可预订状态等信息
     * @param json 接口返回原始JSON字符串
     * @param from 展示用出发站名
     * @param to 展示用到达站名
     * @param queryDate 出行日期
     * @return 拼接完成的可视化车次信息文本
     */
    private String parseTicketResponse(String json, String from, String to, String queryDate) {
        // 反序列化JSON根对象
        JSONObject root = JSON.parseObject(json);
        // 获取接口响应错误码，0代表请求成功
        int errorCode = root.getIntValue("error_code");

        // 非0错误码，匹配对应错误场景返回精准提示
        if (errorCode != 0) {
            String reason = root.getString("reason");
            if (reason == null) reason = "未知错误";
            return switch (errorCode) {
                case 10001 -> "API Key 无效，请检查配置";
                case 205001 -> "查询无结果，请检查出发站和到达站是否正确";
                case 205003 -> "日期格式错误或超出查询范围（仅支持15天内）";
                case 281701 -> "无法识别车站名，请尝试使用12306标准站名（如：杭州东、徐州东）";
                case 281702 -> "查询无结果或车站名无法识别";
                default -> "查询失败（" + errorCode + "）：" + reason;
            };
        }

        JSONArray list = root.getJSONArray("result");
        if (list == null || list.isEmpty()) {
            return "未找到 " + from + " → " + to + " 的列车信息（" + queryDate + "）";
        }

        // 初始化字符串拼接容器，组装最终返回文案
        StringBuilder sb = new StringBuilder("🚆 ")
                .append(from).append(" → ").append(to)
                .append("  ").append(queryDate)
                .append("\n").append("━".repeat(16)).append("\n");

        // 限制最多展示15趟车次，避免返回内容过长
        int max = Math.min(list.size(), 15);
        for (int i = 0; i < max; i++) {
            JSONObject train = list.getJSONObject(i);
            String trainNo = train.getString("train_no");
            String depTime = train.getString("departure_time");
            String arrTime = train.getString("arrival_time");
            String duration = train.getString("duration");
            String enableBooking = train.getString("enable_booking");

            sb.append("\n").append(i + 1).append(". 🚄 ").append(trainNo);
            if ("Y".equals(enableBooking)) {
                sb.append(" ✅可订");
            }
            sb.append("\n");
            sb.append("   ").append(depTime).append(" → ").append(arrTime);
            if (duration != null && !duration.isBlank()) {
                String[] parts = duration.split(":");
                if (parts.length == 2) {
                    sb.append("  （").append(Integer.parseInt(parts[0])).append("时").append(Integer.parseInt(parts[1])).append("分）");
                } else {
                    sb.append("  （").append(duration).append("）");
                }
            }
            sb.append("\n");

            // 遍历提取该车次所有座位类型、票价、剩余票数
            JSONArray prices = train.getJSONArray("prices");
            if (prices != null && !prices.isEmpty()) {
                for (int j = 0; j < prices.size(); j++) {
                    JSONObject seat = prices.getJSONObject(j);
                    String seatName = seat.getString("seat_name");
                    double price = seat.getDoubleValue("price");
                    String num = seat.getString("num");

                    if (price <= 0) continue;

                    sb.append("     ").append(seatName).append("：")
                            .append("¥").append(price % 1 == 0 ? String.format("%.0f", price) : String.format("%.1f", price));

                    if (num != null && !num.isBlank() && !"无".equals(num)) {
                        sb.append("  余").append(num).append("张");
                    } else if ("无".equals(num)) {
                        sb.append("  无票");
                    }
                    sb.append("\n");
                }
            }
        }

        if (list.size() > max) {
            sb.append("\n...共 ").append(list.size()).append(" 趟列车，仅展示前 ").append(max).append(" 趟");
        }

        return sb.toString();
    }
}
