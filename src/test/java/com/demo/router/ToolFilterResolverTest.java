package com.demo.router;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具过滤求交集与安全阀测试。
 *
 * <p>这段逻辑错了会导致<b>静默的功能回归</b>（模型看不见本该有的工具，用户只觉得"变笨了"），
 * 所以每个安全阀都要有独立用例钉住。
 */
class ToolFilterResolverTest {

    /** 模拟一份真实工具集片段。 */
    private static final Set<String> AVAILABLE = Set.of(
            "getWeather", "searchWeb", "generateImage", "editImage", "takeScreenshot",
            "queryTrainTicket", "bookTrainTicket", "payTrainTicket", "sendEmail");

    @Test
    void 正常收窄_只保留交集() {
        ToolFilterResolver.Resolution r = ToolFilterResolver.resolve(
                List.of("getWeather", "searchWeb"),
                IntentLabel.WEATHER.tools(),
                AVAILABLE,
                true);
        assertFalse(r.fellBackToAll());
        assertEquals(List.of("getWeather", "searchWeb"), r.tools());
        assertEquals("filtered", r.reason());
    }

    @Test
    void 请求的工具部分不存在时只保留存在的() {
        ToolFilterResolver.Resolution r = ToolFilterResolver.resolve(
                List.of("getWeather", "NOT_REGISTERED", "searchWeb"),
                IntentLabel.WEATHER.tools(),
                AVAILABLE,
                true);
        assertFalse(r.fellBackToAll());
        assertEquals(List.of("getWeather", "searchWeb"), r.tools());
    }

    @Test
    void 去重() {
        ToolFilterResolver.Resolution r = ToolFilterResolver.resolve(
                List.of("getWeather", "getWeather", "searchWeb"),
                IntentLabel.WEATHER.tools(),
                AVAILABLE,
                true);
        assertEquals(List.of("getWeather", "searchWeb"), r.tools());
    }

    /** 三态之一：null = 不做过滤。必须原样透出，不能被当成空列表。 */
    @Test
    void 不过滤时返回null表示发全量() {
        ToolFilterResolver.Resolution r = ToolFilterResolver.resolve(
                null, IntentLabel.WEATHER.tools(), AVAILABLE, true);
        assertNull(r.tools());
        assertFalse(r.fellBackToAll());
        assertEquals("not-filterable", r.reason());
    }

    /** 三态之二：空列表 = 真的一个工具都不发（闲聊）。安全阀不能把它误判成"交集为空"。 */
    @Test
    void 闲聊意图返回空列表而非回退全量() {
        ToolFilterResolver.Resolution r = ToolFilterResolver.resolve(
                List.of(), IntentLabel.CHAT.tools(), AVAILABLE, true);
        assertFalse(r.fellBackToAll(), "空工具集是正常情况，不该触发安全阀");
        assertNotNull(r.tools(), "空列表必须保持为空列表，不能被当成 null（null 的含义是发全量）");
        assertTrue(r.tools().isEmpty());
        assertEquals("filtered", r.reason());
    }

    /** 安全阀 1：请求了工具，但一个都不在当前工具集里 → 回退全量。 */
    @Test
    void 安全阀_交集为空时回退全量() {
        ToolFilterResolver.Resolution r = ToolFilterResolver.resolve(
                List.of("ghostToolA", "ghostToolB"),
                IntentLabel.IMAGE.tools(),
                AVAILABLE,
                true);
        assertNull(r.tools());
        assertTrue(r.fellBackToAll());
        assertEquals("empty-intersection", r.reason());
    }

    /**
     * 安全阀 2：意图原生工具全部缺失 → 回退全量。
     * <p>这是"意图表与工具注册表脱节"的信号，顺着过滤走会让整个意图静默失效。
     */
    @Test
    void 安全阀_意图工具全部缺失时回退全量() {
        ToolFilterResolver.Resolution r = ToolFilterResolver.resolve(
                List.of("searchWeb"),                 // 通用工具还在，所以交集非空
                List.of("queryTrainTicket", "bookTrainTicket"),  // 但意图原生工具已被改名/摘除
                Set.of("searchWeb", "getWeather"),    // 当前工具集里没有火车票工具
                true);
        assertNull(r.tools());
        assertTrue(r.fellBackToAll());
        assertEquals("intent-tools-missing", r.reason());
    }

    /** 关掉安全阀就应当顺着过滤走（保留通用工具），用于排查"到底是过滤还是安全阀在起作用"。 */
    @Test
    void 关闭安全阀后不再回退() {
        ToolFilterResolver.Resolution r = ToolFilterResolver.resolve(
                List.of("searchWeb"),
                List.of("queryTrainTicket"),
                Set.of("searchWeb", "getWeather"),
                false);
        assertFalse(r.fellBackToAll());
        assertEquals(List.of("searchWeb"), r.tools());
    }

    /**
     * 当前真实注册到 Agent 的工具名全集。
     *
     * <p>来源：{@code AgentConfig.weatherAgent(...)} 里传给 {@code new AgentExecutor(...)} 的
     * 27 个工具实例，取其 {@code @Tool} 方法名（本项目的 {@code @Tool} value 是中文描述，
     * 真正的工具名取自方法名）。
     *
     * <p><b>刻意不含 {@code ChartTool} 的 {@code generateChart}/{@code previewExcel}</b>——
     * 那个类带 {@code @Component} 但没进任何工具列表，已被 {@code antvchart} MCP 取代
     * （CLAUDE.md 已登记为死代码）。若将来它被注册，本测试会失败并提醒把这行补上。
     */
    private static final Set<String> REGISTERED_TOOLS = Set.of(
            // WeatherTool / WebSearchTool / WebPageReaderTool / WebFetchTool
            "getWeather", "searchWeb", "readWebPage", "webFetch",
            // VoiceModeTool / VoiceTypeTool / ChatModeTool
            "controlVoiceMode", "changeVoiceType", "changeMode", "setMimicMode",
            // FileSystemTool / FileConvertTool / FileGenTool
            "readFile", "writeFile", "deleteFile", "listDirectory", "createDirectory",
            "convertFile", "generateFile",
            // ImageGenTool / ImageEditTool / ScreenshotTool
            "generateImage", "editImage", "takeScreenshot",
            // CalculatorTool / TimeTool / ReminderTool / ReportScheduleTool
            "calculate", "getTodayInfo", "calcOffsetDate", "getNowFullTime",
            "setReminder", "setReportTime", "enableReport", "disableReport", "checkReportStatus",
            // SendEmailTool / UserKnowledgeTool / XhsPublishMcpTool
            "sendEmail", "saveToMyKnowledge", "publishXhsNote",
            // MapTool
            "geocode", "reverseGeocode", "drivingRoute", "walkingRoute",
            "transitRoute", "searchPoi", "measureDistance",
            // TrainTicketTool / JuheTrainTicketMcpTool
            "queryTrainTicket", "bookTrainTicket", "payTrainTicket",
            // BrowserSearchTool / BrowserFormTool / BrowserRegisterTool
            "simulateSearch", "fillForm", "simulateRegister",
            // PetAgentTool
            "checkPetSymptom", "getPetDiagnosisHistory", "checkToxicFood",
            "logPetWeight", "getPetWeightHistory", "logPetVaccine", "getPetVaccines",
            "logPetDeworming", "getPetDewormings", "logPetMedication", "getPetHealthScore",
            "logPetGrowth", "getPetGrowthDiary", "logPetExpense", "getPetExpenses",
            "registerPet", "getPetProfile", "listMyPets", "updatePet",
            "addPetSchedule", "listPetSchedules", "cancelPetSchedule", "calcPetAge");

    /**
     * L1 规则层直接收窄的工具。
     *
     * <p>这些刻意<b>不</b>进 L2 意图表：语音/音色/模仿模式都有固定触发词
     * （「换音色」「开启语音」「模仿这个语气」），规则匹配比质心相似度更准，
     * 放进 L2 只会互相抢分。它们与 {@code RuleBasedRouter} 里的工具表一一对应。
     */
    private static final Set<String> L1_COVERED_TOOLS = Set.of(
            "controlVoiceMode", "changeVoiceType",   // VOICE_PANEL
            "setMimicMode", "changeMode",            // STYLE_IMITATE
            "generateImage", "editImage");           // DRAW（同时也在 IMAGE 意图里）

    /** 用真实意图表跑一遍：每个意图声明的工具名必须真实存在。 */
    @Test
    void 意图表里的工具名必须真实存在() {
        for (IntentLabel label : IntentLabel.values()) {
            for (String tool : label.tools()) {
                assertTrue(REGISTERED_TOOLS.contains(tool),
                        "意图 " + label + " 声明了不存在的工具 [" + tool + "]——"
                                + "真实工具集里但凡有一个意图工具缺失，安全阀就会让该意图完全失去过滤能力");
            }
        }
    }

    /**
     * 反向校验：真实工具集里每个工具都应能被某个意图或 L1 规则覆盖。
     *
     * <p>否则它就是一个"路由命中时永远发不出去"的死工具——用户明明需要，
     * 却因为本轮工具集被收窄而拿不到，属于静默功能回归。
     * 本用例第一次跑就抓出了 10 个这类工具（calculate / publishXhsNote /
     * saveToMyKnowledge / 三个 browser 工具等），据此补齐了 CALC / BROWSER /
     * KNOWLEDGE / XHS 四个意图。
     */
    @Test
    void 每个已注册工具都至少被一个意图覆盖() {
        Set<String> covered = new java.util.HashSet<>();
        for (IntentLabel label : IntentLabel.values()) {
            covered.addAll(label.tools());
        }
        covered.addAll(L1_COVERED_TOOLS);

        List<String> uncovered = REGISTERED_TOOLS.stream()
                .filter(t -> !covered.contains(t))
                .sorted()
                .toList();
        assertTrue(uncovered.isEmpty(),
                "以下工具既不在任何 L2 意图里、也不被 L1 规则收窄，路由命中时永远发不出去：" + uncovered);
    }
}
