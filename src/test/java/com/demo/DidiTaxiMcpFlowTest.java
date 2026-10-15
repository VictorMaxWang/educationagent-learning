package com.demo;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.demo.mcp.McpClientManager;
import com.demo.mcp.McpSource;
import com.demo.mcp.McpToolDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 滴滴打车 MCP 端到端流程测试。
 * <p>
 * 完整链路：地点搜索 → 价格预估 → 创建订单。
 * 使用 Mock McpSource 模拟 MCP Server 响应，不依赖真实网络。
 */
@DisplayName("滴滴打车 MCP 端到端流程")
class DidiTaxiMcpFlowTest {

    private McpClientManager manager;
    private MockDidiSource mockSource;

    @BeforeEach
    void setUp() {
        manager = new McpClientManager();
        mockSource = new MockDidiSource();
        mockSource.init();
        manager.register(mockSource);
    }

    // ======================== 步骤 1: 地点搜索 ========================

    @Test
    @DisplayName("步骤1 — 地点搜索：搜索「西湖」上车点")
    void step1_placeSearch() {
        String result = manager.callTool("didi_maps_textsearch", Map.of(
                "keywords", "西湖",
                "city", "杭州"
        ));

        System.out.println("📍 地点搜索结果: " + result);
        assertNotNull(result);
        assertTrue(result.contains("西湖"));
    }

    // ======================== 步骤 2: 价格预估 ========================

    @Test
    @DisplayName("步骤2 — 价格预估：坐标→车型+价格")
    void step2_estimateTaxi() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("from_lat", "30.220735");
        args.put("from_lng", "120.108532");
        args.put("from_name", "杭州西湖风景名胜区");
        args.put("to_lat", "30.29145");
        args.put("to_lng", "120.21307");
        args.put("to_name", "杭州东站");

        String result = manager.callTool("didi_taxi_estimate", args);

        System.out.println("🚕 价格预估结果: " + result);

        assertNotNull(result);
        JSONObject json = JSON.parseObject(result);
        assertEquals(200, json.getIntValue("code"));
        assertTrue(json.containsKey("data"), "应包含车型数据");
        JSONObject data = json.getJSONObject("data");
        assertTrue(data.containsKey("trace_id"), "应包含 trace_id");
    }

    // ======================== 步骤 3: 创建订单 ========================

    @Test
    @DisplayName("步骤3 — 创建订单：product_category + trace_id → 订单号")
    void step3_createOrder() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("product_category", "17");
        args.put("estimate_trace_id", "0ac56cf26a67048689f92fec98b1f602");

        String result = manager.callTool("didi_taxi_create_order", args);

        System.out.println("✅ 创建订单结果: " + result);

        assertNotNull(result);
        JSONObject json = JSON.parseObject(result);
        assertEquals(200, json.getIntValue("code"));
        assertTrue(json.containsKey("order_no"), "应包含 order_no");
        assertFalse(json.getString("order_no").isEmpty(), "订单号不应为空");
    }

    // ======================== 完整 4 步链路 ========================

    @Test
    @DisplayName("完整链路：地点搜索→估价→创建订单 → 端到端通过")
    void fullFlow_endToEnd() {
        System.out.println("\n========== 🚕 滴滴打车 MCP 端到端测试 ==========\n");

        // Step 1: 地点搜索
        System.out.println("--- Step 1: 地点搜索 ---");
        String searchResult = manager.callTool("didi_maps_textsearch", Map.of(
                "keywords", "西湖", "city", "杭州"));
        System.out.println("   📍 " + searchResult);
        assertTrue(searchResult.contains("西湖"));

        String searchResult2 = manager.callTool("didi_maps_textsearch", Map.of(
                "keywords", "杭州东站", "city", "杭州"));
        System.out.println("   📍 " + searchResult2);
        assertTrue(searchResult2.contains("杭州东站"));

        // Step 2: 价格预估
        System.out.println("--- Step 2: 价格预估 ---");
        Map<String, Object> estArgs = new LinkedHashMap<>();
        estArgs.put("from_lat", "30.220735");
        estArgs.put("from_lng", "120.108532");
        estArgs.put("from_name", "杭州西湖风景名胜区");
        estArgs.put("to_lat", "30.29145");
        estArgs.put("to_lng", "120.21307");
        estArgs.put("to_name", "杭州东站");

        String estResult = manager.callTool("didi_taxi_estimate", estArgs);
        System.out.println("   🚕 " + estResult);
        JSONObject estJson = JSON.parseObject(estResult);
        assertEquals(200, estJson.getIntValue("code"));
        String traceId = estJson.getJSONObject("data").getString("trace_id");
        assertNotNull(traceId);

        // Step 3: 创建订单
        System.out.println("--- Step 3: 创建订单 ---");
        Map<String, Object> orderArgs = new LinkedHashMap<>();
        orderArgs.put("product_category", "17");
        orderArgs.put("estimate_trace_id", traceId);

        String orderResult = manager.callTool("didi_taxi_create_order", orderArgs);
        System.out.println("   ✅ " + orderResult);
        JSONObject orderJson = JSON.parseObject(orderResult);
        assertEquals(200, orderJson.getIntValue("code"));
        assertTrue(orderJson.containsKey("order_no"));

        System.out.println("\n✅ 端到端测试全部通过！\n");
    }

    // ======================== 测试：13 个工具注册 ========================

    @Test
    @DisplayName("懒加载：登记不发现工具，激活后注入 13 个工具")
    void toolRegistration_13tools() {
        // 懒加载契约：register 只登记引用，零网络、不发现工具
        assertEquals(0, manager.toolCount(), "登记后未激活，不应发现工具");
        assertTrue(manager.hasRegistered("didi"), "源引用已登记");

        // 激活后：发现全部 13 个工具并可路由
        assertTrue(manager.activate("didi"), "激活应成功");
        assertEquals(13, manager.getAllTools().size(), "激活后应发现 13 个工具");
        assertTrue(manager.hasTool("didi_taxi_estimate"));
        assertTrue(manager.hasTool("didi_taxi_create_order"));
        assertTrue(manager.hasTool("didi_maps_textsearch"));
    }

    // ======================== Mock Didi MCP Source ========================

    static class MockDidiSource implements McpSource {

        private boolean ready = false;

        @Override public String getSourceName() { return "didi"; }
        @Override public boolean isReady() { return ready; }
        @Override public void init() { this.ready = true; }

        @Override
        public List<McpToolDefinition> listTools() {
            JSONObject schema = new JSONObject();
            schema.put("type", "object");
            schema.put("properties", new JSONObject());

            return List.of(
                    tool("maps_direction_bicycling", "骑行路线规划"),
                    tool("maps_direction_driving", "驾车路线规划"),
                    tool("maps_direction_transit", "公交路线规划"),
                    tool("maps_direction_walking", "步行路线规划"),
                    tool("maps_place_around", "周边POI搜索"),
                    tool("maps_regeocode", "逆地理编码"),
                    tool("maps_textsearch", "地点搜索"),
                    tool("taxi_cancel_order", "取消订单"),
                    tool("taxi_create_order", "创建订单"),
                    tool("taxi_estimate", "价格预估"),
                    tool("taxi_generate_ride_app_link", "生成打车链接"),
                    tool("taxi_get_driver_location", "司机位置"),
                    tool("taxi_query_order", "订单查询")
            );
        }

        @Override
        public String callTool(String toolName, Map<String, Object> arguments) {
            return switch (toolName) {
                case "maps_textsearch" -> {
                    JSONObject r = new JSONObject();
                    r.put("code", 200);
                    r.put("data", List.of(
                            Map.of("name", arguments.get("keywords"),
                                   "lat", 30.237, "lng", 120.140)));
                    yield r.toJSONString();
                }
                case "taxi_estimate" -> {
                    JSONObject r = new JSONObject();
                    r.put("code", 200);
                    JSONObject data = new JSONObject();
                    data.put("trace_id", "mock_trace_" + System.currentTimeMillis());
                    data.put("products", List.of(
                            Map.of("name", "惊喜特价", "product_category", "191", "price", "¥31.00"),
                            Map.of("name", "豪华车", "product_category", "17", "price", "¥68.00")));
                    r.put("data", data);
                    yield r.toJSONString();
                }
                case "taxi_create_order" -> {
                    JSONObject r = new JSONObject();
                    r.put("code", 200);
                    r.put("message", "订单创建成功");
                    r.put("order_no", "MOCK_" + System.currentTimeMillis());
                    yield r.toJSONString();
                }
                default -> {
                    JSONObject r = new JSONObject();
                    r.put("code", 200);
                    r.put("message", "Mock: " + toolName + " 执行成功");
                    yield r.toJSONString();
                }
            };
        }

        private McpToolDefinition tool(String name, String desc) {
            JSONObject schema = new JSONObject();
            schema.put("type", "object");
            schema.put("properties", new JSONObject());
            return new McpToolDefinition(name, desc, "didi", schema);
        }
    }
}
