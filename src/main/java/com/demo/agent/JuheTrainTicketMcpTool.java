package com.demo.agent;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.demo.mcp.juhe.JuheMcpSource;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LangChain4j Agent 工具 —— 火车票订票 & 支付（通过聚合数据 MCP Server）
 * <p>
 * 三段式流程：
 * 1 bookTrainTicket(confirmed="")  → 首次下单，MCP 返回 code=299 要求确认
 * 2 bookTrainTicket(confirmed="true") → 用户确认后调用，MCP 返回 12306 登录二维码 + pay_token
 *   → LLM 展示二维码，引导用户扫码登录 12306
 *   → LLM 不再重复调用 bookTrainTicket（重复调用守卫拦截）
 * 3 payTrainTicket(orderNo, paymentMethod) → 用户登录后支付，payToken 由系统自动补齐
 * <p>
 * 支付参数持久化：booking 成功后自动存储 orderNo→payToken 映射到 L1 内存 + L2 Redis，
 * 重启不丢失。payTrainTicket 按 L1(内存) → L2(模糊匹配) → L3(Redis) 三级查找。
 */
@Slf4j
@Component
public class JuheTrainTicketMcpTool {

    private final JuheMcpSource mcpClient;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    private static final String REDIS_KEY_PREFIX = "booking:snapshot:";
    private static final Duration REDIS_TTL = Duration.ofHours(1);

    /** 已确认的订单指纹集合（防重复确认用） */
    private final Set<String> confirmedBookings = ConcurrentHashMap.newKeySet();

    /** orderNo → payToken 映射（L1 内存，LLM 漏传时自动补齐） */
    private final Map<String, String> orderPayTokens = new ConcurrentHashMap<>();

    /** bookingKey → {orderNo, payToken} 快照（L1 内存） */
    private final Map<String, BookingSnapshot> bookingSnapshots = new ConcurrentHashMap<>();

    public JuheTrainTicketMcpTool(JuheMcpSource mcpClient) {
        this.mcpClient = mcpClient;
    }

    @Tool("预订火车票/高铁票/动车票。两段式确认流程，注意：两步都必须填写全部6个参数，缺一不可！ "
            + "1.首次调用：填写 trainNo,fromStation,toStation,date,passengerName,seatType，confirmed留空；"
            + "2.用户确认后再次调用：填写上述全部6个参数 + confirmed=\"true\"，所有参数必须从对话记忆中完整提取，不得遗漏任何一个！ "
            + "⚠️ 重要：确认成功后绝对不要再次调用本工具！当用户说\"支付/付款/买单\"时应调用 payTrainTicket。"
            + "参数名必须精确使用英文！")
    public String bookTrainTicket(
            @P(value = "车次号，例如：G1234", name = "trainNo") String trainNo,
            @P(value = "出发站名称", name = "fromStation") String fromStation,
            @P(value = "到达站名称", name = "toStation") String toStation,
            @P(value = "出发日期 yyyy-MM-dd", name = "date") String date,
            @P(value = "乘车人姓名", name = "passengerName") String passengerName,
            @P(value = "座位类型，如二等座", name = "seatType") String seatType,
            @P(value = "确认标志：首次留空，用户确认后传true", name = "confirmed") String confirmed) {

        boolean isConfirmed = "true".equalsIgnoreCase(confirmed);
        log.info("  MCP 订票{}: train={} {}→{} {} {} {}",
                isConfirmed ? "(确认)" : "(首次)",
                trainNo, fromStation, toStation, date, passengerName, seatType);

        if (!mcpClient.isReady()) {
            return "聚合数据订票服务暂未就绪，请稍后再试。";
        }

        if (isConfirmed) {
            String missing = checkRequiredParams(trainNo, fromStation, toStation, date, passengerName, seatType);
            if (missing != null) {
                log.warn("  确认时参数缺失：{}", missing);
                return "参数缺失：" + missing + "，请重新提供完整的订票信息（车次、出发站、到达站、日期、乘车人、座位类型）。";
            }
        }

        // ---- 防重复确认守卫 ----
        String bookingKey = buildBookingKey(trainNo, fromStation, toStation, date, passengerName);
        if (isConfirmed && confirmedBookings.contains(bookingKey)) {
            log.warn("  ⛔ 重复确认拦截：订单 {} 已确认过", bookingKey);
            // 尝试从缓存获取：L1 内存 → L2 Redis
            BookingSnapshot snap = bookingSnapshots.get(bookingKey);
            if (snap == null) snap = loadSnapshotFromRedis(bookingKey);
            if (snap != null) {
                return "━━━━━━━━━━━━━━━━━━━━━━\n"
                        + "✅ 该订单已确认提交，无需重复预订！\n\n"
                        + "📋 订票信息：\n"
                        + "   🚄 " + trainNo + "  " + fromStation + " → " + toStation + "\n"
                        + "   📅 " + date + "   🙋 " + passengerName + "  " + seatType + "\n\n"
                        + "📌 【支付凭据——用户说「支付」时请使用以下参数调用 payTrainTicket】\n"
                        + "   orderNo: " + snap.orderNo + "\n"
                        + "   payToken: " + snap.payToken + "\n\n"
                        + "⬇️ 下一步：用户说「支付」时，调用 payTrainTicket(orderNo=\"" + snap.orderNo
                        + "\", paymentMethod=用户说的方式, payToken=\"" + snap.payToken + "\")\n"
                        + "【系统提示：不要再次调用 bookTrainTicket，等待用户说「支付」后调 payTrainTicket】";
            }
            return "✅ 该订单已确认提交，等待用户说「支付」后调用 payTrainTicket。\n"
                    + "【系统提示：不要再次调用 bookTrainTicket】";
        }

        // 构建 MCP 参数
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("departure_station", fromStation);
        args.put("arrival_station", toStation);
        args.put("train_no", trainNo);
        args.put("departure_date", date);
        args.put("passenger_name", passengerName);
        args.put("book_train_seat_type", seatType);

        if (isConfirmed) {
            args.put("confirm", true);
        }

        log.info("  MCP 参数: {}", JSON.toJSONString(args));
        String result = mcpClient.callTool("book_train_ticket", args);
        log.info("  MCP 返回: {}...", result != null && result.length() > 200 ? result.substring(0, 200) + "..." : result);
        // ======== 调试：打印完整返回文本 ========
        if (isConfirmed && result != null) {
            System.out.println("[DEBUG-bookTrainTicket] 完整返回文本(" + result.length() + "字符):");
            System.out.println(result);
            System.out.println("[DEBUG-bookTrainTicket] 文本中是否含 pay_train_token: " + result.contains("pay_train_token"));
            System.out.println("[DEBUG-bookTrainTicket] 文本中是否含 pay_token: " + result.contains("pay_token"));
        }

        // ---- 确认成功后：记录指纹 + 存储支付参数 ----
        if (isConfirmed) {
            confirmedBookings.add(bookingKey);

            String payToken = extractField(result, "pay_train_token");
            if (payToken == null) payToken = extractField(result, "pay_token");
            String orderNo = extractField(result, "order_no");
            log.info("  🔍 提取结果: payToken={} orderNo={}", payToken, orderNo);

            if (payToken != null) {
                // L1 内存
                bookingSnapshots.put(bookingKey, new BookingSnapshot(orderNo, payToken));
                if (orderNo != null) {
                    orderPayTokens.put(orderNo, payToken);
                    String llmStyleKey = trainNo + "-" + date.replace("-", "");
                    if (!llmStyleKey.equals(orderNo)) {
                        orderPayTokens.put(llmStyleKey, payToken);
                    }
                }

                // L2 Redis（重启不丢失）
                saveSnapshotToRedis(bookingKey, orderNo, payToken);

                log.info("  ✅ 支付参数已持久化(L1+L2): key={} token={}", bookingKey, payToken);
            }

            // 提取并打印二维码链接到控制台
            try {
                JSONObject resultJson = JSON.parseObject(result);
                if (resultJson.containsKey("data")) {
                    String imageUrl = resultJson.getJSONObject("data").getString("image_url");
                    if (imageUrl != null) {
                        System.out.println("\n========================================");
                        System.out.println("🔗 二维码链接（浏览器打开）：");
                        System.out.println(imageUrl);
                        System.out.println("========================================\n");
                    }
                }
            } catch (Exception ignored) {}

            // 拼接最终返回
            StringBuilder enriched = new StringBuilder(result);
            enriched.append("\n\n━━━━━━━━━━━━━━━━━━━━━━\n");
            enriched.append("✅ 订票已提交！请用户扫描上方二维码登录 12306 完成身份验证。\n");
            if (payToken != null) {
                enriched.append("\n📌 【支付凭据——用户说「支付」时请使用以下参数调用 payTrainTicket】\n");
                enriched.append("   orderNo: ").append(orderNo).append("\n");
                enriched.append("   payToken: ").append(payToken).append("\n");
                enriched.append("\n⬇️ 下一步：展示二维码，用户扫码登录后，等用户说「支付/付款」，\n");
                enriched.append("   调用 payTrainTicket(orderNo=\"").append(orderNo)
                        .append("\", paymentMethod=用户说的方式, payToken=\"").append(payToken).append("\")\n");
            }
            enriched.append("【系统提示：不要再次调用 bookTrainTicket】");
            return enriched.toString();
        }

        return result;
    }

    @Tool("支付已预订的火车票订单。前提：必须先通过 bookTrainTicket 预订成功并获得订单号！ "
            + "用户说支付/付款/买单时调用。注意：所有参数都可以留空，系统会自动补齐！"
            + "参数名必须精确使用英文！")
    public String payTrainTicket(
            @P(value = "订单号，预订成功后返回", name = "orderNo") String orderNo,
            @P(value = "支付方式，如微信支付", name = "paymentMethod") String paymentMethod,
            @P(value = "支付令牌，从 bookTrainTicket 返回结果中获取，可选参数", name = "payToken") String payToken) {

        // ---- 自动补齐 orderNo 和 payToken（三级查找：L1内存 → L2模糊匹配 → L3 Redis）----
        if ((orderNo == null || orderNo.isBlank()) && !bookingSnapshots.isEmpty()) {
            Map.Entry<String, BookingSnapshot> latest = bookingSnapshots.entrySet().iterator().next();
            orderNo = latest.getValue().orderNo();
            if (payToken == null || payToken.isBlank()) payToken = latest.getValue().payToken();
            log.info("  orderNo 自动补齐(L1): {} (from {})", orderNo, latest.getKey());
        }

        if ((orderNo == null || orderNo.isBlank()) && redisTemplate != null) {
            // L3：从 Redis 恢复最近一条
            Set<String> keys = redisTemplate.keys(REDIS_KEY_PREFIX + "*");
            if (keys != null && !keys.isEmpty()) {
                String latestKey = keys.stream().sorted().reduce((a, b) -> b).orElse(null);
                if (latestKey != null) {
                    String json = redisTemplate.opsForValue().get(latestKey);
                    if (json != null) {
                        JSONObject obj = JSON.parseObject(json);
                        orderNo = obj.getString("orderNo");
                        if (payToken == null || payToken.isBlank()) payToken = obj.getString("payToken");
                        log.info("  orderNo 自动补齐(L3 Redis): {} (from {})", orderNo, latestKey);
                        // 回填 L1
                        if (orderNo != null) {
                            String bk = latestKey.replace(REDIS_KEY_PREFIX, "");
                            bookingSnapshots.put(bk, new BookingSnapshot(orderNo, payToken));
                        }
                    }
                }
            }
        }

        if ((payToken == null || payToken.isBlank()) && orderNo != null && !orderNo.isBlank()) {
            String stored = orderPayTokens.get(orderNo);
            if (stored == null) stored = findPayTokenByOrderFuzzy(orderNo);
            if (stored == null) stored = loadPayTokenFromRedis(orderNo); // L3 Redis
            if (stored != null) {
                log.info("  payToken 自动补齐: order={}", orderNo);
                payToken = stored;
            } else {
                log.warn("  payToken 未找到 order={}, orderPayTokens={}", orderNo, orderPayTokens.keySet());
                return "⚠️ 未找到订单 " + orderNo + " 的支付令牌，请重新查询和预订。";
            }
        }

        log.info("  MCP 支付: order={} method={} token={}", orderNo, paymentMethod, payToken);

        // 支付方式自动补齐（LLM 经常不传）
        if (paymentMethod == null || paymentMethod.isBlank()) {
            paymentMethod = "支付宝";
            log.info("  paymentMethod 自动补齐: {}", paymentMethod);
        }

        if (!mcpClient.isReady()) {
            return "聚合数据支付服务暂未就绪，请稍后再试。";
        }

        Map<String, Object> args = new LinkedHashMap<>();
        if (orderNo != null && !orderNo.isBlank()) args.put("order_no", orderNo);
        if (paymentMethod != null && !paymentMethod.isBlank()) args.put("payment_method", paymentMethod);
        if (payToken != null && !payToken.isBlank()) {
            args.put("pay_train_token", payToken);
        }
        log.info("  MCP 支付参数: {}", JSON.toJSONString(args));

        String result = mcpClient.callTool("pay_train_ticket", args);
        log.info("  支付返回: {}", result);

        // 支付成功后清除缓存
        if (orderNo != null) {
            String bookingKeyToRemove = null;
            for (Map.Entry<String, BookingSnapshot> e : bookingSnapshots.entrySet()) {
                String trainNoInKey = e.getKey().split("\\|")[0];
                if (orderNo.startsWith(trainNoInKey) || orderNo.equals(e.getValue().orderNo())) {
                    bookingKeyToRemove = e.getKey();
                    break;
                }
            }
            if (bookingKeyToRemove != null) {
                bookingSnapshots.remove(bookingKeyToRemove);
                confirmedBookings.remove(bookingKeyToRemove);
                deleteSnapshotFromRedis(bookingKeyToRemove);
            }
            orderPayTokens.clear();
        }

        return result;
    }

    // ======================== Redis 持久化 ========================

    private void saveSnapshotToRedis(String bookingKey, String orderNo, String payToken) {
        if (redisTemplate == null) return;
        try {
            JSONObject json = new JSONObject();
            json.put("orderNo", orderNo);
            json.put("payToken", payToken);
            redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + bookingKey, json.toJSONString(), REDIS_TTL);
            log.debug("  Redis 快照已保存: {}", bookingKey);
        } catch (Exception e) {
            log.warn("  Redis 写入失败: {}", e.getMessage());
        }
    }

    private BookingSnapshot loadSnapshotFromRedis(String bookingKey) {
        if (redisTemplate == null) return null;
        try {
            String json = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX + bookingKey);
            if (json != null) {
                JSONObject obj = JSON.parseObject(json);
                String orderNo = obj.getString("orderNo");
                String payToken = obj.getString("payToken");
                if (orderNo != null && payToken != null) {
                    bookingSnapshots.put(bookingKey, new BookingSnapshot(orderNo, payToken));
                    orderPayTokens.put(orderNo, payToken);
                    log.info("  Redis 快照已恢复: {} order={}", bookingKey, orderNo);
                    return new BookingSnapshot(orderNo, payToken);
                }
            }
        } catch (Exception e) {
            log.warn("  Redis 读取失败: {}", e.getMessage());
        }
        return null;
    }

    private String loadPayTokenFromRedis(String orderNo) {
        if (redisTemplate == null || orderNo == null) return null;
        try {
            // 遍历所有 Redis 快照，查找匹配的 orderNo
            Set<String> keys = redisTemplate.keys(REDIS_KEY_PREFIX + "*");
            if (keys == null) return null;
            for (String key : keys) {
                String json = redisTemplate.opsForValue().get(key);
                if (json == null) continue;
                JSONObject obj = JSON.parseObject(json);
                if (orderNo.equals(obj.getString("orderNo"))) {
                    String token = obj.getString("payToken");
                    if (token != null) {
                        // 回填 L1 加速下次
                        String bk = key.replace(REDIS_KEY_PREFIX, "");
                        bookingSnapshots.put(bk, new BookingSnapshot(orderNo, token));
                        orderPayTokens.put(orderNo, token);
                        return token;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("  Redis 遍历查找失败: {}", e.getMessage());
        }
        return null;
    }

    private void deleteSnapshotFromRedis(String bookingKey) {
        if (redisTemplate == null) return;
        try {
            redisTemplate.delete(REDIS_KEY_PREFIX + bookingKey);
        } catch (Exception ignored) {}
    }

    // ======================== helpers ========================

    private String buildBookingKey(String trainNo, String from, String to, String date, String passenger) {
        return (trainNo != null ? trainNo : "?") + "|"
                + (from != null ? from : "?") + "|"
                + (to != null ? to : "?") + "|"
                + (date != null ? date : "?") + "|"
                + (passenger != null ? passenger : "?");
    }

    private String checkRequiredParams(String... params) {
        String[] names = {"trainNo(车次号)", "fromStation(出发站)", "toStation(到达站)",
                "date(日期)", "passengerName(乘车人)", "seatType(座位类型)"};
        for (int i = 0; i < params.length && i < names.length; i++) {
            if (params[i] == null || params[i].isBlank()) return names[i];
        }
        return null;
    }

    private String findPayTokenByOrderFuzzy(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) return null;
        String exact = orderPayTokens.get(orderNo);
        if (exact != null) return exact;
        for (Map.Entry<String, BookingSnapshot> entry : bookingSnapshots.entrySet()) {
            String trainNoInKey = entry.getKey().split("\\|")[0];
            if (orderNo.startsWith(trainNoInKey)) {
                log.info("  payToken 模糊匹配成功: orderNo={} → bookingKey={}", orderNo, entry.getKey());
                orderPayTokens.put(orderNo, entry.getValue().payToken());
                return entry.getValue().payToken();
            }
        }
        return null;
    }

    private String extractField(String result, String fieldName) {
        if (result == null) return null;
        // 尝试 JSON 解析：先找顶层，再找 data 子对象
        try {
            JSONObject obj = JSON.parseObject(result);
            String val = obj.getString(fieldName);
            if (val != null && !val.isBlank()) return val;
            if (obj.containsKey("data")) {
                val = obj.getJSONObject("data").getString(fieldName);
                if (val != null && !val.isBlank()) return val;
            }
        } catch (Exception ignored) {}
        // 正则 fallback：支持 : = ：以及 JSON 的 " 分隔符
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                fieldName + "[=：:\\s\"]+([a-zA-Z0-9_\\-]+)").matcher(result);
        return m.find() ? m.group(1) : null;
    }

    private record BookingSnapshot(String orderNo, String payToken) {}
}
