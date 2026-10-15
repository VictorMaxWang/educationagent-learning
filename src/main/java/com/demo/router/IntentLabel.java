package com.demo.router;

import java.util.List;

/**
 * L2 向量/TF-IDF 路由的意图标签，以及每个意图对应的真实 {@code @Tool} 工具名。
 *
 * <h3>为什么不照抄方案书的 7 类</h3>
 * 方案书给出的是 {@code CHAT / WEATHER / SEARCH / LAW / SCHEDULE / FILE / IMAGE}。
 * 但那些工具名（{@code get_weather} / {@code web_search} / {@code search_law_article} …）
 * 属于另一个项目，且该分类<b>没有覆盖本项目的一大半能力</b>：
 * 地图路线、火车票、宠物、邮件、浏览器自动化全部无处可归。
 * 若照抄，用户问「北京到上海高铁」会因为落不进任何类而被判 CHAT → 0 工具 → <b>功能直接失效</b>。
 *
 * <p>因此这里按本项目真实的 {@code @Tool} 方法名扩展为 15 类
 * （另有一个特殊的 {@link #RISK}：它不是意图，见其 javadoc）。
 * 工具名必须与 `@Tool` 方法名<b>逐字一致</b>（本项目 {@code @Tool} 的 value 是中文描述，
 * 真正的工具名取自方法名），否则过滤后会得到空集。
 * 可用 {@code RouterToolNamesTest} 校验本表里的名字全部真实存在。
 *
 * <p>{@link #LAW} 的工具表为空是<b>有意为之</b>：本项目没有法律 {@code @Tool}，
 * 法律能力是通过 RAG 上下文注入对话实现的（见 {@code com.demo.memory.HybridRagService}），
 * 不需要 LLM 调工具。{@link #CHAT} 同理为空。
 */
public enum IntentLabel {

    /** 闲聊：零工具。 */
    CHAT(List.of()),

    /** 天气。 */
    WEATHER(List.of("getWeather")),

    /** 联网搜索 / 网页读取。 */
    SEARCH(List.of("searchWeb", "webFetch", "readWebPage")),

    /** 法律咨询：工具表为空，靠 RAG 注入上下文。 */
    LAW(List.of()),

    /** 提醒 / 日报定时。 */
    SCHEDULE(List.of(
            "setReminder", "setReportTime", "enableReport", "disableReport",
            "checkReportStatus", "getTodayInfo", "calcOffsetDate", "getNowFullTime")),

    /** 文件读写 / 转换 / 生成。 */
    FILE(List.of(
            "convertFile", "generateFile", "readFile", "writeFile",
            "listDirectory", "createDirectory", "deleteFile")),

    /** 图片生成 / 编辑 / 截图。 */
    IMAGE(List.of("generateImage", "editImage", "takeScreenshot")),

    /** 地图：地理编码 / 路线 / POI / 测距。 */
    MAP(List.of(
            "geocode", "reverseGeocode", "drivingRoute", "walkingRoute",
            "transitRoute", "searchPoi", "measureDistance")),

    /** 火车票查询 / 预订 / 支付。 */
    TRAIN(List.of("queryTrainTicket", "bookTrainTicket", "payTrainTicket")),

    /** 宠物：档案 / 健康 / 疫苗 / 驱虫 / 体重 / 日程。 */
    PET(List.of(
            "checkPetSymptom", "getPetProfile", "listMyPets", "registerPet", "updatePet",
            "logPetWeight", "getPetWeightHistory", "logPetVaccine", "getPetVaccines",
            "logPetDeworming", "getPetDewormings", "logPetMedication", "getPetHealthScore",
            "logPetGrowth", "getPetGrowthDiary", "logPetExpense", "getPetExpenses",
            "addPetSchedule", "listPetSchedules", "cancelPetSchedule", "calcPetAge",
            "getPetDiagnosisHistory", "checkToxicFood")),

    /** 邮件。 */
    MAIL(List.of("sendEmail")),

    /** 数学计算。 */
    CALC(List.of("calculate")),

    /** 浏览器自动化：模拟搜索 / 填表 / 注册。 */
    BROWSER(List.of("simulateSearch", "fillForm", "simulateRegister")),

    /** 个人知识库写入。 */
    KNOWLEDGE(List.of("saveToMyKnowledge")),

    /** 小红书发布。 */
    XHS(List.of("publishXhsNote")),

    /**
     * 模糊风险（L2 的风险语义识别）。<b>它不是"意图"，而是一个特殊类别</b>：
     *
     * <ul>
     *   <li>工具集为空——它永远不会以"收窄工具"的方式被采纳；</li>
     *   <li>判定阈值与模式独立（见 {@code RouterProperties.Tfidf.Risk}），比意图判定更严；</li>
     *   <li>它的产出只有两种：{@code BLOCKED}（enforce 模式命中）或 {@code FALLBACK}（其余情况）。
     *       绝不会产出 {@code TOOL}——因为 0 工具会让那条消息静默失去全部能力。</li>
     * </ul>
     *
     * <p>与 L1 的分工：L1 收<b>精确关键词</b>（想死、割腕…），L2 收<b>没有命中关键词、
     * 但语义偏向高危</b>的措辞（"撑不下去了""想一了百了""觉得我是累赘"）。
     * 两层的词/语料刻意不重叠——重叠的部分 L1 已经拦了，放进 L2 只会拉低其质心纯度。
     */
    RISK(List.of());

    private final List<String> tools;

    IntentLabel(List<String> tools) {
        this.tools = tools;
    }

    /** 该意图对应的真实 {@code @Tool} 方法名。空列表表示「不需要工具」。 */
    public List<String> tools() {
        return tools;
    }

    /** 从字符串宽松解析，大小写不敏感；无法识别返回 {@code null}。 */
    public static IntentLabel parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim().toUpperCase();
        for (IntentLabel v : values()) {
            if (v.name().equals(s)) return v;
        }
        return null;
    }
}
