package com.demo.agent;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import dev.langchain4j.internal.JsonSchemaElementJsonUtils;
import com.demo.chroma.LawContextProvider;
import com.demo.chroma.TravelKnowledgeService;
import com.demo.chroma.UserKnowledgeService;
import com.demo.config.AiChatProperties;
import com.demo.mcp.McpClientManager;
import com.demo.mcp.lazy.McpLazyProperties;
import com.demo.memory.MemoryIntegrationService;
import com.demo.metrics.BusinessMetrics;
import com.demo.router.HybridRouter;
import com.demo.router.MessageContext;
import com.demo.router.RouteResult;
import com.demo.router.ToolFilterResolver;
import com.demo.service.interrupt.InterruptManager;
import com.demo.service.interrupt.LlmInterruptException;
import com.demo.service.tools.ChatModeService;
import com.demo.util.ChatContextManager;
import com.demo.util.TraceContext;
import com.demo.util.VoiceContext;
import com.demo.agent.toolguard.AuditResult;
import com.demo.agent.toolguard.GuardSpec;
import com.demo.agent.toolguard.ToolGuardAudit;
import com.demo.edu.rag.EduContextProvider;
import com.demo.edu.safety.CrisisGuard;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 智能体核心执行器（有限状态机驱动）
 * <p>
 * 状态流转：
 * <pre>
 *   INIT → LLM_CALL → EXECUTE_TOOLS → LLM_CALL（工具结果回注，循环直到 LLM 不再调工具）
 *                    → VERIFY → DONE（合规）
 *                    → VERIFY → RETRY（违规，注入警告，最多1次）→ LLM_CALL
 * </pre>
 * 支持多用户独立会话上下文记忆、多工具统一注册调度。
 */
@Slf4j
public class AgentExecutor implements Assistant {

    // ======================== System Prompt ========================

    private static final String SYSTEM_PROMPT = """
        【人设定位】你是面向乡村学生的学习与成长助手，关心留守儿童的学习和身心健康。以平等、耐心的方式交流，不因家庭或居住地给学生贴标签；你是 AI，鼓励学生与家人、老师和可信任的成年人保持联系。
        【服务宗旨】学科问题先了解年级和已经尝试的步骤，按难度给提示、例子和解释，帮助学生自己理解。成长问题先倾听，再提供适龄知识与可行的小建议，不做医疗诊断、不替代现实支持。其他生活、办公和旅行需求仍按真实工具能力帮助完成。
        【沟通风格】亲切自然、条理清晰、分段规整、重点高亮、简洁干货，无废话、无空洞文案，所有推荐可直接落地执行。
        【强制思考语言】用户使用中文时，你的**全部思考过程（内心推理 reasoning）和最终回答都必须使用中文**。禁止因任务复杂、涉及对比/分析/代码就切换到英文思考。若发现自己在用英文思考，立即切回中文。回答始终使用用户的语言。
        【强制规则】查询火车票/高铁/动车/票价/余票/车次等实时出行信息必须调用工具，不凭记忆编造！

        你是严格遵守指令的智能助手，**所有工具调用为最高优先级，绝对不可跳过**。

        【铁律：工具调用 > 所有文字回复】
        1. 用户任何指令匹配下方工具触发词时，**必须先调用工具，再输出结果**。
        2. **禁止任何形式的"假装执行"**：只说"已切换""已关闭"但不调用工具，属于严重违规。
        3. 工具调用格式严格按系统要求，**不得编造参数、不得省略必填项**。

        ## 工具触发规则（唯一匹配，强制调用）
        ### 1. 语音模式控制（controlVoiceMode）
        - 用户说：开启语音、全程语音、一直说话、用语音回复 → **调用 global**
        - 用户说：关闭语音、不要语音、切回文字、静音、别说话 → **调用 off**
        - 用户说：这句用语音、念出来、语音回复本条 → **调用 single**
        触发后回复：**仅回复工具返回结果，不要额外解释**。

        ### 2. 音色切换（changeVoiceType）
        - 用户说：换声音、换音色、男声、女声、童声、御姐、少年 → **调用本工具**
        音色ID：101007=推荐女声,101002=成熟男声,101015=可爱童声,101301=情感男声,101006=活力女声,101008=少年男声,101016=御姐女声。

        ### 3. 文件操作（readFile/writeFile/deleteFile/listDirectory/createDirectory）
        - 用户说：读文件、查看文件、写入文件、创建文件、删除文件、列出目录、新建文件夹 → **立即调用对应工具**
        - 仅支持 D 盘，C 盘直接拒绝。
        - 回复固定为：**请在控制台查看验证码**，不透露验证码。

        ### 4. 天气查询（getWeather）
        - 用户说：天气、温度、冷热、体感 → **调用本工具**，缺城市先问。

        ### 5. 图片生成（generateImage）
        - 用户说：画图、生图、海报、图片 → **调用本工具**，扩展详细 prompt。

        ### 6. 网页搜索（searchWeb，双源对比）
        - 用户问：最近新闻、实时信息、最新消息、股价、汇率等需要联网的知识 → **先调用本工具**
        - 工具会同时返回【百度千帆】和【豆包搜索】两个来源的结果，**必须对比两方后回答**：
          - ① 两方一致的信息 → 直接采用
          - ② 不一致 → 在回答里简要指出差异，并选择更权威/更新/信息更完整的来源
          - ③ 剔除明显错误或与问题无关的条目
        - 搜索后基于对比后的可靠信息回答，不得凭记忆编造实时信息。
        - **重要：如果两个来源都返回"未搜索到有效结果"或报错，说明搜索不可用，请基于自身已有知识正常回答，不要反复搜索不同关键词。**

        ### 7. 网页读取（readWebPage）
        - 用户发送链接/网址 → **立即调用本工具读取内容**
        - 读取后基于提取的内容总结或回答，不得跳过读取直接凭标题猜测内容。

        ### 8. 数学计算（calculate）
        - 用户要求：计算、算一下、等于多少、解方程 → **立即调用本工具**
        - 禁止 LLM 自行心算，必须使用精确计算器工具。
        - 例如：25*48+137、2^10、sqrt(144)、(15+3)*2

        ### 9. 定时提醒（setReminder）
        - 用户说：提醒我、N分钟后提醒我、叫我、到点提醒 → **立即调用本工具**
        - message=提醒内容, delayMinutes=多少分钟后提醒（LLM 自行计算）
        - 例如："15分钟后提醒我洗头" → setReminder(message="洗头", delayMinutes=15)
        - "12:00提醒我打卡" → 假设现在11:30，则 delayMinutes=30
        - 回复格式：**仅回复工具返回结果，不要额外解释**。

        ### 10. 发送邮件（sendEmail）
        - 用户说：发邮件、发个邮件、写邮件、send email → **立即调用本工具**
        - 需提取三个参数：to（收件人邮箱）、subject（主题）、body（正文）
        - 用户没说全的让用户补充，例如只给了收件人没给内容就问"邮件内容写什么"

        ### 11. 实景图搜索（searchRealPhoto）
        - 用户说：真实照片、真实图片、实际图片、给我看看xxx的照片/图片 → **立即调用本工具**
        - 注意区别于 generateImage（文生图）：generateImage 用于画图/创作/生成图片，searchRealPhoto 用于搜索真实世界拍摄的照片
        - 例如："给我一张真实的大熊猫照片" → searchRealPhoto(query="大熊猫")
        - "故宫的真实图片" → searchRealPhoto(query="故宫")

        ### 12. 对话模式切换（changeMode）
        - 用户说：专家模式、精简模式、恢复普通模式 → **立即调用本工具**
        - 参数 mode：expert=专家模式，concise=精简模式，normal=普通模式
        - 设置后后续回答风格会相应改变，无需重复设置。

        ### 13. 图片编辑（editImage）
        - 用户说：修改图片、编辑图片、把这张图改成、给图片加上 → **先检查用户是否发过图片**
        - 如果用户发过图片，调用本工具；如果没发过图片，先让用户发送图片。
        - 例如用户发了一张猫的图片说"改成动漫风格" → editImage(prompt="转换成动漫风格")

        ### 14. 模仿模式（setMimicMode）
        - 用户说：模仿模式、学我说话、模仿这个风格 → **不要直接调用 setMimicMode**
        - 先回复："请给我需要模仿的文本或一句话～"
        - 等用户发来文本后，再将用户发的文本作为 sampleText 调用 setMimicMode
        - 调用后后续回答将模仿该风格。

        ### 15. 地图导航与定位（geocode / reverseGeocode / drivingRoute / walkingRoute / transitRoute / searchPoi / measureDistance）
        - **地理编码**：用户给地址问坐标、在哪 → 调用 geocode(address, city)
        - **逆地理编码**：用户给坐标问地址、这是哪里 → 调用 reverseGeocode(location)
        - **驾车路线**：用户问开车怎么走、自驾导航 → 调用 drivingRoute(origin, destination)
        - **步行路线**：用户问步行怎么走、走路路线 → 调用 walkingRoute(origin, destination)
        - **公交路线**：用户问坐公交怎么去、地铁换乘 → 调用 transitRoute(origin, destination, city)
        - **周边搜索**：用户问附近有什么餐厅/医院/银行、帮我搜索某类地点 → 调用 searchPoi(keywords, city)
        - **距离测量**：用户问多远、两地距离 → 调用 measureDistance(origins, destination)
        - 支持直接输入地址（如"天安门"），工具会自动转成坐标。
        - 路线会自动给出距离、预计耗时和分段导航步骤。

        ### 16. 火车票查询（queryTrainTickets）
        - **‼️ 核心规则：只要用户提到火车/高铁/动车/余票/票价/车次，必须调用 queryTrainTickets！禁止自己回答！**
        - 必填参数：fromStation（出发站）、toStation（到达站）
        - 可选参数：date（日期 yyyy-MM-dd，用户说"明天"自己算，没说留空）
        - 示例："查明天北京到上海的高铁" → queryTrainTickets(fromStation="北京", toStation="上海", date="2026-07-25")
        - "杭州到深圳的火车票" → queryTrainTickets(fromStation="杭州", toStation="深圳", date="")
        - "广州到长沙的动车" → queryTrainTickets(fromStation="广州", toStation="长沙", date="")

        ### 17. 火车票订票（juhe-train_book_train_ticket）
        - 用户说：买票、订票、购票、下单、帮我订、预订车票 → **立即调用本工具**
        - 必填参数（6 个）：departure_station, arrival_station, train_no, departure_date, passenger_name, seat_type
        - **缺任何信息时主动向用户询问，不要编造！**
        - **一次调用即完成预订，无需二次确认**

        ### 18. 火车票支付（juhe-train_pay_train_ticket）
        - **‼️ 前提：必须先订票成功并获得订单号！**
        - 用户说：支付、付款、买单、确认支付 → **立即调用本工具**
        - 必填参数（2 个）：order_no（订单号）、payment_method（支付方式，如微信支付/支付宝）

        ### 19. 滴滴打车（全流程，使用 didi_ 开头的 MCP 工具）
        - **‼️ 核心规则：用户说打车、叫车、滴滴、出租车、从A到B多远/怎么去 → 必须调用滴滴 MCP 工具！**
        - **标准流程（3步）**：
          ① **地点搜索**：调用 `didi_maps_textsearch` 分别搜索起点和终点，获取经纬度坐标
             - 参数：keywords（地点名）、city（城市名）
             - 示例：didi_maps_textsearch(keywords="西湖", city="杭州")
          ② **价格预估**：用第①步返回的坐标调用 `didi_taxi_estimate`
             - 参数：from_lat, from_lng, from_name, to_lat, to_lng, to_name
             - **‼️ lat 是纬度（中国范围 22~54），lng 是经度（中国范围 100~122）**
             - **‼️ lat 和 lng 值域完全不同，不能写反！如南京 lat≈31~33、lng≈118~119**
             - **‼️ 每一轮新的预估必须重新调 textsearch 获取新鲜坐标，禁止直接从对话历史抄坐标！**
          ③ **创建订单**：用户指定车型后，调用 `didi_taxi_create_order`
             - 参数：product_category（从 estimate 返回的车型编码）、estimate_trace_id（上一步返回的 trace_id）
        - **用户选车时（如说"豪华车""快车"），重新调 textsearch 获取新鲜坐标，再调 estimate 获取 trace_id，再调 create_order**
        - 下单成功后展示订单号、司机信息给用户。
        - **‼️ lat/lng 校验口诀：纬度(lat)=横向在地图上上的位置 30~33，经度(lng)=竖直方向116~122。传参前请检查数值范围，防止颠倒。**

        ### 20. 网页截图（takeScreenshot）
        - 用户说：截图、网页截图、截个图、长截图 → **立即调用本工具**
        - 参数 url：目标网页地址。如果用户没有明确网址但有上下文中的链接，从上下文提取。
        - 参数 fullPage：是否全页长截图。"true"=包含滚动内容，"false"=仅可视区域。
          用户说"长截图"时传"true"，普通"截图"传"false"或不传。
        - 截图完成后自动发送图片给用户。
        - **多工具链场景**：搜索后截图结果页 → 在一次对话中先后调用多个工具。

        ### 21. 文件消息处理（收到用户发的文件时）
        - **用户发文件时，你的首要任务是描述文件格式和内容给用户，而不是自动保存。**
        - 回复格式示例：
          "📄 收到文件：xxx.docx（Word文档）
           文件内容摘要：xxx..."
        - **绝对不要主动调用 saveToMyKnowledge 保存文件！** 只有用户明确说"存到我的知识库"、"保存这个文件"、"加到资料库"时才调用。
        - 用户发的文件已经在后台缓存了，等用户说保存时再存，不用担心丢失。
        - saveToMyKnowledge 工具的唯一触发条件：用户明确要求保存到知识库。

        ### 22. 日报定时推送（setReportTime / enableReport / disableReport / checkReportStatus）
        - **用户说：日报、每日推送、定时推送、每天早上X点推送 → 调用 setReportTime**
          - LLM 负责将口语化时间转为小时和分钟，分钟可以是 0~59 任意值
          - 例子：早上8点→hour=8,minute=0，下午5点半→hour=17,minute=30，11点21分→hour=11,minute=21
          - city 可选，用户没说城市则填"杭州"或不传
        - **用户说：开启日报、打开日报、启用日报 → 调用 enableReport**
        - **用户说：关闭日报、取消日报、停用日报 → 调用 disableReport**
        - **用户说：日报状态、查看日报、推送状态 → 调用 checkReportStatus**
        - 设置成功后回复用户设置结果即可。

        ### 23. 文件格式转换（convertFile）
        - 用户上传文件后说「转成xx」「转为xx」「转换成xx」「转成pdf」「转成图片」「convert to pdf」→ **立即调用 convertFile(targetFormat)**
        - targetFormat 传目标格式扩展名：pdf / docx / txt / md / rtf / html / jpg / png / webp
          - 转成word → docx；转成图片 → png；转成网页 → html；转成纯文本 → txt；转成pdf → pdf
        - 源文件是用户最近上传的文件（后台已缓存），**无需传路径**。
        - **与相似工具严格区分**：
          - 「转成图片/转成png」= 把已有文件转成图片（convertFile），**不是** generateImage 生图、也不是 editImage 修图
          - 只有涉及 D 盘磁盘路径的读/写/删才用 readFile/writeFile；文件转换只用用户上传的文件
          - 用户说「存到知识库」才用 saveToMyKnowledge，与转换无关
        - 如果用户还没发文件，先让用户发送文件再转换。
        - 回复格式：**仅回复工具返回结果，不要额外解释**。

        ### 24. 图表生成（antv-chart MCP）
        - **用户说：柱状图、折线图、饼图、环形图、雷达图、散点图、词云、面积图、漏斗图、桑基图等可视化 → 调用 antv-chart 图表工具**
        - 工具名：antvchart_generate_xxx_chart（柱状图=generate_bar_chart、折线图=generate_line_chart、饼图=generate_pie_chart、雷达图=generate_radar_chart 等，以实际可用工具为准）
        - 把图表数据/标题按该工具的**参数 schema** 传入；outputFormat 已强制为 base64，**无需也不可传**。
        - 用户描述了数据 / 数据已在你的分析里 → 直接组装进图表工具参数；用户没给数据时先询问要展示的数据。
        - 图表生成后自动发送图片给用户，回复**仅转述工具返回结果**，不要额外解释。
        - **⚠️ 不要要求用户手动生成文件/转格式/上传来凑图表数据**。

        ### 25. 机票查询（daolv-flight_searchAirports / daolv-flight_searchFlights）
        - **用户查机票、航班、机票价格、订机票 → 必须调用 daolv-flight 工具，禁止凭记忆回答航班/票价信息！**
        - **标准流程（2步）**：
          ① **先调 searchAirports**：搜出发/到达城市 → 获取城市/机场代码
          ② **再调 searchFlights**：用第①步拿到的代码 + 日期 → 返回航班列表
        - 具体参数以工具 schema 为准；缺出发地/到达地/日期时先问用户。
        - 道旅机票所有请求用 Authorization: Bearer 鉴权，服务未就绪会返回提示，此时如实告知用户稍后再试。

        ### 26. 办公文档生成（generateFile）
        - 用户说：生成Word、写个文档、生成Excel表格、生成表格、导出CSV、生成txt文件 → **立即调用本工具**
        - format：word/docx=Word文档，excel/xlsx=Excel表格，csv=CSV，txt=纯文本
        - 有表格数据时拼成 JSON：{"columns":["列1","列2"],"rows":[["a","1"],["b","2"]]}
        - 生成后自动保存到 D 盘并发送文件给用户，回复仅转述工具返回结果，不要额外解释。
        - **保存方式**：文件静默保存到 D 盘固定目录并返回完整路径，**不再弹出本地文件夹**（openFolder 参数已失效，忽略取值）。
        - **与相似工具区分**：转换已上传文件用 convertFile；读/写 D 盘指定路径用 readFile/writeFile。
        - **多步衔接**：生成了数据文件后若用户还要图表，**直接用数据调用 antv-chart 图表工具**，不要要求用户手动上传。

        ### 27. 小红书（publishXhsNote / xhs_* MCP工具集）
        - **发布意图触发**：用户说：发小红书、发布笔记、发一篇小红书笔记、发帖到小红书、帮我运营小红书 → 立即调用 publishXhsNote（一键发布封装工具）
        - 自动收集用户最近发送的图片、写临时文件，整理标题、正文、标签后执行发布，尽量减少用户额外操作。
        - **⚠️ 禁止串工具**：发布小红书是最终动作，用户要发小红书时**不要先去调 getWeather/搜索等无关工具**；即使笔记内容需要外部数据，也要以发布为主、先问清再一次性完成。
        - **⚠️ 禁止直接调用底层发布MCP**：上层禁止直接调用 MCP 的 `xhs_publish_content`、`xhs_publish_with_video`，图片路径、临时文件处理、参数校验全部交由 publishXhsNote 内部处理，由其内部再调用底层MCP。
        - 信息缺失处理：缺少图片、标题、正文等必填信息时直接向用户追问补齐，不自行编造笔记内容。    
        - 当xhs MCP懒加载完成后，全部xhs系列MCP工具会自动出现在可用工具列表，模型依据工具schema与用户意图选择对应工具调用。
        - MCP参数以工具schema为准；调用工具缺少必填参数时，优先向用户追问补齐。
        - 服务未就绪、未登录、鉴权失败会返回对应错误，如实将错误信息告知用户。
        - 懒加载说明：命中小红书触发器关键词会预加载全部14个MCP工具，等待加载完成后工具schema才会注入可用工具集合。
        
       
        【行为规范】
        - 工具调用失败前，**禁止任何文字回复**。
        - 工具返回结果后，**简洁回复，不额外添加说明**。
        - 语音模式下回复≤100字，**不要标注语音状态**。
        
        输出规范（强制遵守）：
        1. 禁止使用 ##、### 这类Markdown井号标题；禁止无意义的 --- 分隔线；
        2. 章节标题直接使用【标题名称】文字标签替代二级标题；
        3. 可以保留表格、有序/无序列表，不要多余空行；
        4. 不要额外增加开场白、结尾闲聊问句；只输出规划内容；
        5. 不要随意堆砌emoji，仅关键信息少量使用。

        """;

    // ======================== FSM 状态定义 ========================

    private enum State {
        /** 初始 / 消息刚进来 */
        INIT,
        /** 调用 LLM */
        LLM_CALL,
        /** 执行 LLM 请求的工具 */
        EXECUTE_TOOLS,
        /** 校验 LLM 纯文本回复是否违规 */
        VERIFY,
        /** 违规重试（注入强化警告） */
        RETRY,
        /** 最终返回给用户 */
        DONE
    }

    /**
     * 工具守卫规则：用户消息命中关键词列表 → 必须调用对应工具
     * 如果 LLM 返回纯文本（未调用 expectedTool），判定违规
     */
    private record ToolGuard(Set<String> keywords, String toolName, String ruleDesc) {}

    /** 关键词 → 工具 映射表（VERIFY 阶段用） */
    private static final List<ToolGuard> TOOL_GUARDS = List.of(
            // 注意：工具名必须与方法名逐字一致。这里曾经写成 queryTrainTickets（多了个 s），
            // 而真实工具名是 queryTrainTicket —— 该守卫因此永远不可满足，任何"提到火车票 +
            // 声称已完成"的回复都会被要求重试一个不存在的工具。
            new ToolGuard(Set.of("火车票", "高铁", "动车", "余票", "车次", "12306", "列车", "二等座", "一等座"),
                    "queryTrainTicket", "火车票查询"),
            new ToolGuard(Set.of("订票", "买票", "购票", "预订", "下单"),
                    "juhe-train_book_train_ticket", "火车票订票"),
            new ToolGuard(Set.of("支付", "付款", "买单", "结账"),
                    "juhe-train_pay_train_ticket", "火车票支付"),
            new ToolGuard(Set.of("打车", "叫车", "滴滴", "出租车", "叫个车"),
                    "didi_maps_textsearch", "滴滴打车地点搜索"),
            new ToolGuard(Set.of("确认下单", "确认打车", "好的就这个", "开始叫车", "帮我叫"),
                    "didi_taxi_create_order", "滴滴打车下单"),
            new ToolGuard(Set.of("天气", "温度", "冷热", "体感", "气温", "下雨", "刮风"),
                    "getWeather", "天气查询"),
            new ToolGuard(Set.of("语音", "说话", "念出来", "读出"),
                    "controlVoiceMode", "语音模式控制"),
            new ToolGuard(Set.of("声音", "音色", "男声", "女声", "童声", "御姐"),
                    "changeVoiceType", "音色切换"),
            new ToolGuard(Set.of("提醒我", "到点提醒", "定时提醒"),
                    "setReminder", "定时提醒"),
            new ToolGuard(Set.of("算一下", "等于多少", "解方程"),
                    "calculate", "数学计算"),
            new ToolGuard(Set.of("发邮件", "写邮件", "发个邮件"),
                    "sendEmail", "发送邮件"),
            new ToolGuard(Set.of("导航", "怎么去", "怎么走", "路线规划", "开车路线", "坐公交", "地铁换乘"),
                    "drivingRoute", "路线规划"),
            new ToolGuard(Set.of("附近", "周边"),
                    "searchPoi", "周边搜索"),
            new ToolGuard(Set.of("读文件", "查看文件", "写入文件", "创建文件", "删除文件", "列出目录", "新建文件夹"),
                    "readFile", "文件操作"),
            new ToolGuard(Set.of("转成pdf", "转为pdf", "转换成pdf", "转pdf", "转成docx", "转为docx", "转docx",
                    "转成word", "转成文档", "转成图片", "转图片", "转成网页", "转成纯文本", "转成txt", "转txt",
                    "转成md", "转成html", "转成jpg", "转成png", "转png", "转成webp",
                    "转换格式", "格式转换", "文件转换", "convert"),
                    "convertFile", "文件格式转换"),
            // 注意：不放「转成word/转成excel」——那是 convertFile（转换已上传文件）的职责，避免冲突
            new ToolGuard(Set.of("生成word", "生成docx", "生成excel", "生成xlsx", "生成csv", "生成txt",
                    "生成文档", "生成表格", "导出表格", "导出csv", "导出excel",
                    "写个文档", "写文档", "生成一个word", "生成一个excel", "做成表格", "整理成表格"),
                    "generateFile", "办公文档生成"),
            new ToolGuard(Set.of("小红书", "发小红书", "发布笔记", "发一篇笔记", "发笔记", "发帖", "小红书笔记"),
                    "xhs_publish", "小红书发布"),
            // 原「真实照片 → searchRealPhoto」守卫已删除：全项目不存在名为 searchRealPhoto 的工具
            // （实景图能力在 travel/RealImageSearchService，但从未暴露成 @Tool），
            // 该守卫永远不可满足，只会把正常回复判成违规去重试一个不存在的工具。
            new ToolGuard(Set.of("画图", "生图", "海报", "生成图片", "画一张"),
                    "generateImage", "图片生成"),
            new ToolGuard(Set.of("柱状图", "折线图", "饼图", "环形图", "雷达图", "散点图", "词云",
                    "生成图表", "画个图", "可视化", "面积图", "漏斗图", "桑基图"),
                    "antvchart", "图表生成（antv-chart MCP）"),
            new ToolGuard(Set.of("搜索一下", "搜一下", "最近新闻", "最新消息"),
                    "searchWeb", "网页搜索"),
            new ToolGuard(Set.of("专家模式", "精简模式", "普通模式"),
                    "changeMode", "对话模式切换"),
            new ToolGuard(Set.of("模仿", "学我说话"),
                    "setMimicMode", "模仿模式设置"),
            new ToolGuard(Set.of("http://", "https://"),
                    "readWebPage", "网页读取"),
            new ToolGuard(Set.of("精读", "读一下这篇文章", "读全文", "文章正文", "看正文", "展开内容", "全文内容"),
                    "web_fetch", "网页精读"),
            new ToolGuard(Set.of("修改", "编辑图片", "把这张图改成", "给图片加上"),
                    "editImage", "图片编辑"),
            new ToolGuard(Set.of("截图", "长截图", "网页截图", "截个图"),
                    "takeScreenshot", "网页截图"),
            // 日报定时
            new ToolGuard(Set.of("日报", "每日推送", "定时推送", "每天推送"),
                    "setReportTime", "日报设置"),
            new ToolGuard(Set.of("开启日报", "打开日报", "启用日报"),
                    "enableReport", "开启日报"),
            new ToolGuard(Set.of("关闭日报", "取消日报", "停用日报"),
                    "disableReport", "关闭日报")
    );

    /**
     * {@link #TOOL_GUARDS} 的 {@link GuardSpec} 视图，供独立包里的
     * {@code ToolGuardAudit} 使用。
     *
     * <p>刻意保留上面那个私有 record 并逐行不动规则表，只用这层适配把它交给审计组件：
     * 本文件是跨分支合并的冲突高发区，把 30 条规则表整体改写会显著放大冲突面，
     * 而规则表的内容并没有变——变的只是"谁来仲裁"。
     */
    private static final List<GuardSpec> GUARD_SPECS = TOOL_GUARDS.stream()
            .map(g -> new GuardSpec(g.keywords(), g.toolName(), g.ruleDesc()))
            .toList();

    /** 最大工具调用循环轮数 */
    private static final int MAX_TOOL_ITERATIONS = 8;
    /** 思考流最大转发字符数（超长截断转发，避免思考淹没聊天） */
    private static final int MAX_THINKING_CHARS = 2000;
    /** 违规重试最多 1 次 */
    private static final int MAX_VIOLATION_RETRIES = 1;

    /** DeepSeek 单次请求工具硬上限 128；留安全余量，超限时截断（静态核心工具在前必保留） */
    private static final int MAX_LLM_TOOLS = 100;

    // ======================== 依赖 ========================

    private final ChatModel chatModel;

    /** 流式对话模型（桌面端真流式专用，可空：为空时 chatStream 退化为非流式） */
    private final StreamingChatModel streamingChatModel;

    /** 自定义 OpenAI 兼容 SSE 与共享模型使用同一连接，避免流式请求仍访问旧供应商。 */
    @Resource
    private AiChatProperties aiChat;

    private final List<ToolSpecification> toolSpecifications;
    /** 静态核心工具（构造函数注册，始终发送给 LLM） */
    private final List<ToolSpecification> staticTools;
    /** MCP 动态工具（按消息关键词筛选后发送） */
    private final List<ToolSpecification> mcpTools = new CopyOnWriteArrayList<>();
    private final List<Object> toolInstances;
    private final ChatContextManager contextManager;
    private final ChatModeService chatModeService;

    /** 旅行知识库 RAG（可选） */
    private final TravelKnowledgeService travelKnowledgeService;

    /** 用户个人知识库 RAG（可选） */
    private final UserKnowledgeService userKnowledgeService;

    /** MCP 多源管理器（可选，由 McpToolBridge 启动时注入） */
    private volatile McpClientManager mcpManager;

    /** MCP 懒加载配置（触发词表，用于按消息筛选相关 MCP 工具） */
    @Resource
    private McpLazyProperties mcpLazyProperties;

    /**
     * 工具守卫三态审计（可选）。
     * <p>用字段注入而不是构造器参数，是为了不改变 {@code AgentExecutor} 的构造签名——
     * 那个签名由 {@code AgentConfig} 手工装配，本文件又是跨分支合并的冲突高发区。
     * 与 {@link #mcpLazyProperties} 的做法一致。未注入时审计自动跳过。
     */
    @Resource
    private ToolGuardAudit toolGuardAudit;

    /**
     * 法律 RAG 门控 + 上下文块构造（可选）。
     * <p>同样用字段注入：法律能力是旁路增强，缺失时主对话必须照常工作。
     */
    @Resource
    private LawContextProvider lawContextProvider;

    /**
     * 本地记忆库集成（可选）。
     * <p>{@code memory.integration.enabled=false} 时为本类保持"走原 Chroma 记忆链路"的旧行为。
     */
    @Resource
    private MemoryIntegrationService memoryIntegrationService;

    /** 业务指标埋点（可选，缺失时埋点自动跳过）。 */
    @Resource
    private BusinessMetrics businessMetrics;

    /**
     * 危机兜底（可选，缺失时按"未命中"处理）。
     *
     * <p>用 {@code @Resource} 字段注入而不是构造参数：本类由 {@code AgentConfig} 手动 {@code new}，
     * 加构造参数要同步改 {@code AgentConfig} 的调用点；而字段注入对本类是被验证过可用的
     * （同一个 {@code @Resource businessMetrics} 在运行时确实注入成功——指标页的 llm.calls 有数）。
     */
    @Resource
    private CrisisGuard crisisGuard;

    /** L4 后置内容校验裁判（可选，缺失时跳过校验）。 */
    @Resource
    private com.demo.edu.safety.EduContentSafetyJudge contentSafetyJudge;

    /**
     * 教育专题 RAG（生命安全与健康教育 / 青春期性教育，可选）。
     * 与 {@code crisisGuard} 一样用字段注入，避免改 {@code AgentConfig} 的构造调用。
     */
    @Resource
    private EduContextProvider eduContextProvider;

    /** 中断管理器（可选，用于打断对话） */
    private final InterruptManager interruptManager;

    /**
     * 前置路由（可选，可空）。
     *
     * <p>可空是为了兼容单测与手工构造——路由是纯旁路能力，缺失时必须退化成"发全量工具"，
     * 绝不能因为没注入就让 Agent 起不来。
     */
    private final HybridRouter hybridRouter;

    public AgentExecutor(ChatModel chatModel, StreamingChatModel streamingChatModel,
                         ChatModeService chatModeService,
                         ChatContextManager contextManager,
                         TravelKnowledgeService travelKnowledgeService,
                         UserKnowledgeService userKnowledgeService,
                         InterruptManager interruptManager,
                         HybridRouter hybridRouter,
                         Object... toolInstances) {
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
        this.chatModeService = chatModeService;
        this.contextManager = contextManager;
        this.travelKnowledgeService = travelKnowledgeService;
        this.userKnowledgeService = userKnowledgeService;
        this.interruptManager = interruptManager;
        this.hybridRouter = hybridRouter;
        this.toolInstances = List.of(toolInstances);

        // CopyOnWriteArrayList：懒加载时 MCP 工具在运行期动态增删，读（LLM 迭代）不受影响
        this.toolSpecifications = new CopyOnWriteArrayList<>();
        for (Object instance : this.toolInstances) {
            this.toolSpecifications.addAll(ToolSpecifications.toolSpecificationsFrom(instance));
        }
        // 静态核心工具快照（构造后不变，始终发送给 LLM）
        this.staticTools = List.copyOf(this.toolSpecifications);

        log.info("  注册工具 {} 个：", toolSpecifications.size());
        for (ToolSpecification spec : toolSpecifications) {
            log.info("    - {}({})", spec.name(), spec.description());
        }
        log.info("  FSM 违规守卫规则 {} 条", TOOL_GUARDS.size());
    }

    /**
     * 由 McpToolBridge 在启动就绪后调用，注入 MCP 动态工具。
     * 线程安全：volatile + synchronized 保证 toolSpecifications 的可见性和一致性。
     */
    public void registerMcpTools(List<ToolSpecification> mcpSpecs, McpClientManager manager) {
        if (mcpSpecs == null || mcpSpecs.isEmpty()) return;
        this.mcpManager = manager;
        synchronized (this.toolSpecifications) {
            this.toolSpecifications.addAll(mcpSpecs);
        }
        this.mcpTools.addAll(mcpSpecs);
        log.info("🔌 MCP 动态工具已注入：{} 个 (总计 {} 个工具)",
                mcpSpecs.size(), toolSpecifications.size());
    }

    /**
     * 移除指定 MCP 源的全部动态工具（懒加载卸载时调用）。
     * 按 qualifiedName 前缀 {sourceName}_ 匹配。
     */
    public void removeMcpTools(String sourceName) {
        String prefix = sourceName + "_";
        synchronized (this.toolSpecifications) {
            int before = toolSpecifications.size();
            toolSpecifications.removeIf(spec -> spec.name().startsWith(prefix));
            int removed = before - toolSpecifications.size();
            if (removed > 0) {
                log.info("🔌 MCP 动态工具已移除：{} 个（{}），剩余 {} 个工具",
                        removed, sourceName, toolSpecifications.size());
            }
        }
        this.mcpTools.removeIf(spec -> spec.name().startsWith(prefix));
    }

    /** 当前注册的工具数量（含静态 @Tool + MCP 动态工具） */
    public int toolCount() {
        return toolSpecifications.size();
    }

    /**
     * 返回发送给 LLM 的工具列表。
     * <ul>
     *   <li>静态核心工具：默认全发；被前置路由收窄时只发命中的那几个</li>
     *   <li>MCP 工具：先按消息关键词筛源，再按路由收窄</li>
     *   <li>上限 {@link #MAX_LLM_TOOLS} 兜底，保证不超过 DeepSeek 128 硬限</li>
     * </ul>
     *
     * @param route 前置路由结果；{@code null} 或 {@link RouteResult#getTools()} 为
     *              {@code null} 时不做收窄（行为与改造前完全一致）
     */
    private List<ToolSpecification> llmToolSpecifications(String userMessage, RouteResult route) {
        Set<String> allowed = route == null ? null
                : (route.getTools() == null ? null : new java.util.HashSet<>(route.getTools()));

        List<ToolSpecification> out = new ArrayList<>();
        for (ToolSpecification spec : staticTools) {
            if (allowed == null || allowed.contains(spec.name())) {
                out.add(spec);
            }
        }

        Set<String> relevant = relevantMcpSources(userMessage);
        boolean hasRelevant = !relevant.isEmpty();
        for (ToolSpecification spec : mcpTools) {
            // 先按触发词筛源（原有逻辑），再按路由收窄
            if (hasRelevant && !relevant.contains(sourceOfTool(spec.name()))) {
                continue;
            }
            if (allowed != null && !allowed.contains(spec.name())) {
                continue;
            }
            out.add(spec);
        }

        if (out.size() > MAX_LLM_TOOLS) {
            log.warn("⚠️ 发送给 LLM 的工具 {} 个超上限 {}，已截断（静态核心必保留）", out.size(), MAX_LLM_TOOLS);
            return new ArrayList<>(out.subList(0, MAX_LLM_TOOLS));
        }
        return out;
    }

    // ======================== 前置路由（L1 规则 → L2 TF-IDF → L3 LLM） ========================

    /**
     * 每轮对话判定一次路由，并把「意图想要的工具」与「当前真实注册的工具」求交集。
     *
     * <p>为什么求交集放在这里而不是路由层：{@code AgentExecutor} 是唯一知道
     * 静态工具（{@code AgentConfig} 装配）与动态 MCP 工具（懒加载增删）全集的地方。
     *
     * @return 已回填最终工具集的 {@link RouteResult}；返回 {@code null} 表示<b>不做收窄</b>
     *         （路由关闭 / 未就绪 / 判定或求交集时异常）
     */
    private RouteResult routeTurn(String memoryId, String userMessage) {
        if (hybridRouter == null || !hybridRouter.isEnabled()) {
            return null;
        }
        MessageContext ctx = MessageContext.inferFrom(memoryId);
        try {
            RouteResult r = hybridRouter.route(memoryId, userMessage, ctx);
            RouteResult applied = applyToolFilter(r);
            hybridRouter.recordEval(memoryId, userMessage, applied, ctx);
            return applied;
        } catch (Exception e) {
            // 路由是纯旁路能力：它挂了最多回到"发全量工具"，绝不能影响主对话
            log.warn("⚠️ [{}] 前置路由异常，本轮回退全量工具：{}", memoryId, e.toString());
            return null;
        }
    }

    /**
     * 意图工具 ∩ 真实工具，并执行安全阀。
     *
     * <p>安全阀的必要性：意图表里的工具名一旦写错（或对应工具被摘掉注册），
     * 交集就会是空集，那条消息会<b>静默失去全部工具能力</b>。
     * 所以当"意图声明的工具一个都不存在"时，宁可放弃省 token 也要回退全量。
     * 注意 {@code CHAT}/{@code LAW} 这类意图本来就声明空工具集，属于正常情况，不触发安全阀。
     */
    /**
     * 从 {@code ChatResponse} 里取 token 用量并记一次 LLM 指标。
     *
     * <p>token 用量取自 LangChain4j 的 {@code response.tokenUsage()}。
     * 两条流式来源的处理不同：
     * <ul>
     *   <li>自建 DeepSeek SSE（{@link #doDeepSeekStream}）：provider 把全量 usage 挂在最后一个
     *       分片上，由本类解析后<b>自行回填</b>到 {@code ChatResponse.tokenUsage}——
     *       不填就是恒 null，指标页的累计 token 会永远显示 0</li>
     *   <li>LangChain4j 流式：usage 有没有取决于 provider 是否在末片带上</li>
     * </ul>
     * 全程 null 安全——拿不到 token 就只记耗时，不要编一个 0 出来污染统计
     * （{@code BusinessMetrics} 内部也是 {@code in/out} 为 null 时按 0 累加，
     * 所以这里区分"没拿到"与"真是 0"只能靠日志）。
     */
    private void recordLlmMetrics(ChatResponse response, long startMs, boolean success) {
        if (businessMetrics == null) return;
        try {
            Integer in = null;
            Integer out = null;
            if (response != null && response.tokenUsage() != null) {
                in = response.tokenUsage().inputTokenCount();
                out = response.tokenUsage().outputTokenCount();
            }
            businessMetrics.recordLlmCall(System.currentTimeMillis() - startMs, in, out, success);
        } catch (Exception e) {
            log.debug("记录 LLM 指标失败：{}", e.toString());
        }
    }

    /**
     * 判断工具结果是否是失败。
     *
     * <p>{@code executeTool} 失败时不会抛异常，而是返回约定前缀的错误文案
     * （"工具执行失败：…" / "未找到工具：…" / "MCP 工具执行失败：…"）。
     * 这里按前缀识别——字符串匹配确实不如结构化返回值干净，
     * 但为此改动 {@code executeTool} 的返回类型会牵动所有调用点，
     * 因此把前缀判定<b>集中在这一个方法里</b>，将来要改成结构化返回只需改两处。
     */
    private static boolean isToolFailure(String toolResult) {
        if (toolResult == null || toolResult.isBlank()) return true;
        return toolResult.startsWith("工具执行失败")
                || toolResult.startsWith("未找到工具")
                || toolResult.startsWith("MCP 工具执行失败");
    }

    private RouteResult applyToolFilter(RouteResult r) {
        int total = toolSpecifications.size();
        if (!hybridRouter.isToolFilteringEnabled()) {
            return r.withToolSelection(null, total);
        }

        Set<String> available = new java.util.HashSet<>();
        for (ToolSpecification spec : toolSpecifications) {
            available.add(spec.name());
        }
        List<String> intentTools = r.getIntent() == null ? List.of() : r.getIntent().tools();

        ToolFilterResolver.Resolution res = ToolFilterResolver.resolve(
                r.getTools(), intentTools, available, hybridRouter.isFallbackOnEmpty());

        if (res.fellBackToAll()) {
            log.warn("⚠️ 工具过滤安全阀触发（{}）→ 回退全量 {} 个工具", res.reason(), total);
        }
        return r.withToolSelection(res.tools(), total);
    }

    /** 消息关键词 → 相关 MCP 源集合（复用 mcp.lazy.triggers 触发词表） */
    private Set<String> relevantMcpSources(String message) {
        Set<String> relevant = new java.util.HashSet<>();
        if (mcpLazyProperties == null || message == null || message.isBlank()) return relevant;
        Map<String, String> triggers = mcpLazyProperties.getTriggers();
        if (triggers == null || triggers.isEmpty()) return relevant;
        for (Map.Entry<String, String> e : triggers.entrySet()) {
            String words = e.getValue();
            if (words == null || words.isBlank()) continue;
            for (String kw : words.split("[,，]")) {
                String w = kw.trim();
                if (!w.isEmpty() && message.contains(w)) {
                    relevant.add(e.getKey());
                    break;
                }
            }
        }
        return relevant;
    }

    /** MCP 工具名 → 源名（qualifiedName 前缀，源名不含下划线） */
    private static String sourceOfTool(String toolName) {
        int idx = toolName.indexOf('_');
        return idx > 0 ? toolName.substring(0, idx) : "";
    }

    // ======================== 主入口 ========================

    @Override
    public String chat(String memoryId, String userMessage) {
        // ★ 前置路由：整轮只判定一次，同时供「工具收窄」「法律/教育 RAG 门控」与「安全拦截」使用。
        //   安全规则是 L1 的第一条，所以命中 BLOCKED 时会在 L2/L3 之前返回——
        //   连 L3 的那次 LLM 调用都不会发生。
        RouteResult route = routeTurn(memoryId, userMessage);

        Optional<String> blocked = safetyShortCircuit(memoryId, userMessage);
        if (blocked.isPresent()) return blocked.get();

        List<ChatMessage> messages = buildMessages(memoryId, userMessage, route);
        String reply = runFsm(memoryId, userMessage, messages, route, chatModel::chat);
        // ★ L4 后置内容校验：非流式路径可以在返回前替换（流式做不到，见 applyContentCheck）
        return applyContentCheck(reply, false, null);
    }

    @Override
    public String chatStream(String memoryId, String userMessage, Consumer<String> onToken) {
        return chatStream(memoryId, userMessage, onToken, null);
    }

    @Override
    public String chatStream(String memoryId, String userMessage, Consumer<String> onToken, Consumer<String> onThinking) {
        RouteResult route = routeTurn(memoryId, userMessage);

        // ★ 安全短路：桌面链路（不经过 CmdService）。3 参重载委托到这里，所以守一处即可。
        Optional<String> blocked = safetyShortCircuit(memoryId, userMessage);
        if (blocked.isPresent()) {
            String script = blocked.get();
            if (onToken != null) onToken.accept(script);
            return script;
        }

        List<ChatMessage> messages = buildMessages(memoryId, userMessage, route);
        if (streamingChatModel == null) {
            // 未配置流式模型 → 退化为非流式，整段回调一次
            String reply = runFsm(memoryId, userMessage, messages, route, chatModel::chat);
            if (onToken != null) onToken.accept(reply);
            return reply;
        }
        String reply = runFsm(memoryId, userMessage, messages, route,
                request -> streamingCall(memoryId, request, onToken, onThinking));
        // ★ L4 后置内容校验：流式路径的 token 已经推给用户了，**撤回不了**，
        //   所以这里只能追加一条更正提示（并记 WARN）。非流式路径才能真的替换。
        return applyContentCheck(reply, true, onToken);
    }

    /**
     * 安全短路：命中自伤风险或违规内容请求时返回固定话术，调用方立即返回、不拼提示词、不调 LLM。
     *
     * <h3>与路由 L1 的分工（这是本项目的安全接线方式）</h3>
     * <ul>
     *   <li><b>主路径</b>：安全规则是 {@code RuleBasedRouter} L1 的<b>第一条</b>规则。
     *       由于 {@code HybridRouter} 的漏斗语义是"任何一层给出非 FALLBACK 的结论即采纳"，
     *       命中即 {@code BLOCKED} 并短路 L2/L3——被拦截的消息不会触碰任何模型。</li>
     *   <li><b>兜底</b>：本方法仍然每次都判定一次。原因是安全不能挂在开关上：
     *       {@code router.enabled=false} 或 {@code router.rule.enabled=false} 时 L1 整层不跑，
     *       而这一层恰恰是自伤风险的唯一自动防线。代价只是一次约 60 个关键词的字符串包含判断
     *       （微秒级），换来的是"无论怎么配开关，风险消息都不会进模型"。</li>
     * </ul>
     *
     * <p>两条路径的判定源都是 {@code SafetyKeywordRules}，话术与计数都在 {@code CrisisGuard} 里，
     * 所以不存在两套行为。{@code crisisGuard} 未注入时按"未命中"处理——安全功能不该因为装配问题
     * 让整条对话起不来，但启动面板会打印它的开关与模式（见 {@code CrisisGuardHealthPrinter}）。
     */
    private Optional<String> safetyShortCircuit(String memoryId, String userMessage) {
        if (crisisGuard == null) return Optional.empty();
        try {
            return crisisGuard.check(memoryId, userMessage);
        } catch (Exception e) {
            log.warn("⚠️ [{}] 安全短路检查异常（按未命中继续）：{}", memoryId, e.toString());
            return Optional.empty();
        }
    }

    /**
     * L4 后置内容校验（可选，裁判缺失时原样返回）。
     *
     * <h3>两条链路的处置必然不同（这里没有优雅解法）</h3>
     * <ul>
     *   <li><b>非流式</b>（微信链路）：回答还没发给用户，可以<b>替换</b>成标准提示；</li>
     *   <li><b>流式</b>（桌面链路）：token 是边生成边推的，等裁判出结果时用户已经看到了，
     *       所以只能<b>追加一条更正提示</b>——这是"事后更正"，不是"事后拦截"。</li>
     * </ul>
     * 文档里按这个口径写，不要笼统说"L4 会拦下不合适的内容"。
     *
     * <p>固定安全话术（热线引导 / 违规拒绝）不做校验：它们是逐字审过的，
     * 再花一次 LLM 既浪费、又有被误判后替换掉热线号码的风险。
     */
    private String applyContentCheck(String reply, boolean streamed, Consumer<String> onToken) {
        if (contentSafetyJudge == null || reply == null || reply.isBlank()) return reply;
        if (CrisisGuard.isSafetyScript(reply)) return reply;
        try {
            if (streamed) {
                Optional<String> correction = contentSafetyJudge.streamCorrectionIfUnsafe(reply);
                if (correction.isPresent()) {
                    if (onToken != null) onToken.accept(correction.get());
                    return reply + correction.get();
                }
                return reply;
            }
            return contentSafetyJudge.replacementIfUnsafe(reply).orElse(reply);
        } catch (Exception e) {
            log.warn("⚠️ L4 内容校验异常（按通过处理）：{}", e.toString());
            return reply;
        }
    }

    /**
     * 共享 FSM 循环。LLM 调用通过 {@link LlmCaller} 抽象，支持非流式/流式两种后端。
     */
    private String runFsm(String memoryId, String userMessage, List<ChatMessage> messages,
                          RouteResult route, LlmCaller llmCaller) {
        log.debug("[{}] 发送消息数：{}", memoryId, messages.size());

        // FSM 上下文
        State state = State.INIT;
        String resultText = null;          // 最终返回的文本
        String violationType = null;       // 违规类型标记
        int toolIterations = 0;            // 工具调用次数
        int violationRetries = 0;          // 违规重试次数
        Set<String> calledTools = new java.util.HashSet<>(); // 本轮已调用的工具

        // 路由在调用方（chat / chatStream）算好并传进来：整个 FSM 循环复用同一份判定。
        // 刻意不放在循环里——多轮工具调用（EXECUTE_TOOLS ↔ LLM_CALL）若每轮重判，
        // 工具集会在轮次之间跳变，模型可能中途"看不见"自己上一轮用过的工具。
        if (route != null) {
            log.info("🧭 [{}] 前置路由：{} → {} | 工具 {} / {}（省约 {} token）",
                    memoryId, route.getLayer(), route.getDecision(),
                    route.getTools() == null ? toolSpecifications.size() : route.getToolsCount(),
                    toolSpecifications.size(),
                    route.getToolsSaved() * (hybridRouter == null ? 60 : hybridRouter.tokensPerTool()));
        }

        while (state != State.DONE) {
            // ★ 中断检查：除 INIT/DONE 外，每个 FSM 状态前检查用户是否已打断
            if (state != State.INIT && state != State.DONE && interruptManager != null) {
                if (interruptManager.checkAndConsume(memoryId)) {
                    log.warn("🚨 [{}] FSM 检测到用户中断，终止执行", memoryId);
                    TraceContext.span("用户中断");
                    resultText = "⛔ 已中断，你说~";
                    state = State.DONE;
                    continue;
                }
            }

            switch (state) {

                case INIT -> {
                    state = State.LLM_CALL;
                }

                case LLM_CALL -> {
                    log.info("🤖 [{}] FSM:LLM_CALL | toolIter={} violationRetry={}",
                            memoryId, toolIterations, violationRetries);
                    TraceContext.span("LLM 调用(toolIter=" + toolIterations + ")");

                    ChatResponse response;
                    long llmStartMs = System.currentTimeMillis();
                    try {
                        List<ToolSpecification> specs = llmToolSpecifications(userMessage, route);
                        ChatRequest.Builder requestBuilder = ChatRequest.builder().messages(messages);
                        // 工具集为空（闲聊/法律这类靠上下文作答的意图）时不传空的 tools 数组,
                        // 避免把空数组当成非法参数发出去
                        if (!specs.isEmpty()) {
                            requestBuilder.toolSpecifications(specs);
                        }
                        response = llmCaller.call(requestBuilder.build());
                        recordLlmMetrics(response, llmStartMs, true);
                    } catch (LlmInterruptException e) {
                        // 【缺陷 1】LLM HTTP 连接已被拦截器主动取消 → 返回中断提示
                        log.info("🚨 [{}] LLM 调用因用户中断而取消", memoryId);
                        TraceContext.span("LLM 被用户中断");
                        resultText = "⛔ 已中断，你说~";
                        state = State.DONE;
                        continue;
                    } catch (Exception e) {
                        // 检查是否因中断导致（拦截器 + 线程中断双重保障）
                        if (interruptManager != null && interruptManager.isSignaled(memoryId)) {
                            log.info("🚨 [{}] LLM 调用因用户中断而失败: {}", memoryId, e.getMessage());
                            TraceContext.span("LLM 被用户中断", e.getMessage());
                            interruptManager.checkAndConsume(memoryId); // 消费信号
                            resultText = "⛔ 已中断，你说~";
                        } else {
                            log.error("💥 [{}] LLM 调用失败", memoryId, e);
                            TraceContext.span("LLM 异常", e.getMessage());
                            // 用户主动打断不算服务失败，只有这里才是真失败
                            recordLlmMetrics(null, llmStartMs, false);
                            resultText = "抱歉，AI 服务暂时不可用，请稍后再试。";
                        }
                        state = State.DONE;
                        continue;
                    }

                    AiMessage aiMessage = response.aiMessage();

                    if (aiMessage.hasToolExecutionRequests()) {
                        // 正常路径：LLM 调用了工具
                        toolIterations++;
                        messages.add(aiMessage);
                        state = State.EXECUTE_TOOLS;

                        // 从 RETRY 回来且本次调了工具 → 标记重试成功
                        if (violationType != null && violationRetries > 0) {
                            log.info("✅ [{}] 违规重试成功：LLM 在第 {} 次重试后调用了 {}",
                                    memoryId, violationRetries,
                                    aiMessage.toolExecutionRequests().stream()
                                            .map(ToolExecutionRequest::name).toList());
                        }
                    } else {
                        // LLM 返回纯文本 → 进入违规校验
                        resultText = aiMessage.text();
                        messages.add(aiMessage);
                        state = State.VERIFY;
                    }
                }

                case EXECUTE_TOOLS -> {
                    // 取最后一条 AiMessage 的工具请求
                    ChatMessage lastMsg = messages.get(messages.size() - 1);
                    if (!(lastMsg instanceof AiMessage aiMsg) || !aiMsg.hasToolExecutionRequests()) {
                        log.warn("[{}] FSM:EXECUTE_TOOLS 但最后一条消息无工具请求，回退 LLM_CALL", memoryId);
                        state = State.LLM_CALL;
                        continue;
                    }

                    List<ToolExecutionRequest> toolRequests = aiMsg.toolExecutionRequests();
                    log.info("🔧 [{}] FSM:EXECUTE_TOOLS 执行 {} 个工具", memoryId, toolRequests.size());

                    for (ToolExecutionRequest toolRequest : toolRequests) {
                        calledTools.add(toolRequest.name());
                        TraceContext.span("调用工具:" + toolRequest.name(), toolRequest.arguments());
                        String toolResult = executeTool(toolRequest);
                        log.info("✅ [{}] 工具 {} 执行完成", memoryId, toolRequest.name());
                        TraceContext.span("工具结果:" + toolRequest.name(), truncate(toolResult, 80));
                        if (businessMetrics != null) {
                            businessMetrics.recordToolInvoke(toolRequest.name(), !isToolFailure(toolResult));
                        }

                        // ★ 长度护栏：工具结果会留在上下文里跨轮累积（最多 8 轮），
                        //   长文本（网页正文 / 大文件 / MCP 冗长 JSON）会撑爆 DeepSeek 的输入长度上限，
                        //   报错却是笼统的 "Range of input length should be [1, 1000000]"。
                        ToolResultGuard.Result guarded = ToolResultGuard.guard(toolResult);
                        if (guarded.truncated()) {
                            log.warn("⚠️ [{}] 工具 {} 结果过长（{} 字符）已截断到 {} 字符",
                                    memoryId, toolRequest.name(),
                                    guarded.originalLength(), ToolResultGuard.DEFAULT_MAX_CHARS);
                        }
                        messages.add(ToolExecutionResultMessage.from(toolRequest, guarded.text()));
                    }

                    // 检查是否超过最大迭代次数
                    if (toolIterations >= MAX_TOOL_ITERATIONS) {
                        log.warn("[{}] 超出最大工具迭代次数({})，强制结束", memoryId, MAX_TOOL_ITERATIONS);
                        resultText = "抱歉，处理时间过长，请稍后再试。";
                        state = State.DONE;
                    } else {
                        state = State.LLM_CALL;
                    }
                }

                case VERIFY -> {
                    // 检测 LLM 是否应该调工具但没调
                    // calledTools: 本轮已执行过的工具，跳过重复强制
                    ToolGuard violated = detectViolation(userMessage, resultText, calledTools);
                    if (violated != null && violationRetries < MAX_VIOLATION_RETRIES) {
                        violationRetries++;
                        violationType = "SKIPPED_TOOL:" + violated.toolName;

                        log.warn("🚨 [{}] FSM:VERIFY 违规！应调 {} 但未调用 | LLM回复: {} | 进入 RETRY",
                                memoryId, violated.toolName, truncate(resultText, 80));
                        TraceContext.span("违规检测",
                                "应调" + violated.toolName + "但未调，进入RETRY(" + violationRetries + "/" + MAX_VIOLATION_RETRIES + ")");

                        // 移除最后一条 AiMessage（LLM 的废话回复），替换为强化指令
                        messages.remove(messages.size() - 1);
                        messages.add(buildRetryUserMessage(violated));
                        state = State.RETRY;

                    } else if (violated != null) {
                        // 重试次数耗尽但仍违规 → 标记违规并接受
                        violationType = "FAKE_EXECUTION:" + violated.toolName;
                        log.warn("🚨 [{}] FSM:VERIFY 违规重试耗尽 | 应调 {} 但 LLM 仍然不调 | 接受回复",
                                memoryId, violated.toolName);
                        state = State.DONE;

                    } else {
                        // 无违规，正常回复
                        state = State.DONE;
                    }
                }

                case RETRY -> {
                    log.info("🔄 [{}] FSM:RETRY → LLM_CALL（第 {} 次重试）",
                            memoryId, violationRetries);
                    state = State.LLM_CALL;
                }

                case DONE -> {
                    // unreachable — loop 会 break
                }
            }
        }

        // 最终：存记忆 + 返回
        log.info("💬 [{}] 最终回复: {}", memoryId, truncate(resultText, 100));
        if (violationType != null) {
            contextManager.addMessage(memoryId, userMessage, resultText, violationType);
            log.warn("🚨 [{}] 违规记录已写入 MySQL | type={}", memoryId, violationType);
        } else {
            contextManager.addMessage(memoryId, userMessage, resultText);
        }

        // 本地记忆库写入：抽取用户事实 + 处理反馈。
        // ★ 用真实 userId 而不是 memoryId——桌面会话模式下 memoryId 是 session_xxx，
        //   用它当用户键会让记忆写进一个永远不会被读回的"会话账户"。
        //   这正是旧 Chroma 记忆链路存在的读写不对称问题（写用 memoryId、读用 userId）。
        if (memoryIntegrationService != null && memoryIntegrationService.isEnabled()) {
            String memoryUserId = VoiceContext.getUserId();
            if (memoryUserId == null || memoryUserId.isBlank()) {
                memoryUserId = memoryId;
            }
            memoryIntegrationService.write(memoryUserId, userMessage);
        }
        return resultText;
    }

    /** LLM 调用抽象：非流式（chatModel::chat）或流式（streamingCall） */
    @FunctionalInterface
    private interface LlmCaller {
        ChatResponse call(ChatRequest request);
    }

    /**
     * 流式 LLM 调用（桌面端，可捕获思考过程）。
     * <p>
     * 当提供了 onThinking 时，优先走自定义 OpenAI 兼容 SSE 读取器（捕获
     * {@code reasoning_content}）；失败自动回退 langchain4j 流式（思考不显示）。
     *
     * @param onThinking 思考过程增量回调（可为 null：不捕获思考，走 langchain4j）
     */
    /** 一次性诊断日志：确认自定义 OpenAI 兼容 流式（思考捕获）是否启用 */
    private static final java.util.concurrent.atomic.AtomicBoolean STREAM_LOG = new java.util.concurrent.atomic.AtomicBoolean(false);

    private ChatResponse streamingCall(String memoryId, ChatRequest request, Consumer<String> onToken, Consumer<String> onThinking) {
        if (onThinking != null && aiChat != null && aiChat.hasApiKey()) {
            if (STREAM_LOG.compareAndSet(false, true)) {
                log.info("🔊 自定义 OpenAI 兼容流式已启用 | model={} | baseUrl={}", aiChat.getModel(), aiChat.getBaseUrl());
            }
            log.debug("[{}] 🔵 自定义 OpenAI 兼容 流式调用（思考捕获）", memoryId);
            java.util.concurrent.atomic.AtomicBoolean contentEmitted = new java.util.concurrent.atomic.AtomicBoolean(false);
            try {
                return doDeepSeekStream(memoryId, request, onToken, onThinking, contentEmitted);
            } catch (LlmInterruptException e) {
                throw e;
            } catch (Exception e) {
                if (contentEmitted.get()) {
                    // 已输出正文内容，不再回退（避免重复输出），转为错误
                    log.error("💥 文本模型流式中途失败（不回退，避免内容重复）: {}", e.getMessage());
                    throw new RuntimeException("文本模型流式中断：" + e.getMessage(), e);
                }
                log.warn("⚠️ 自定义 OpenAI 兼容 流式失败，回退 langchain4j（思考不显示）: {}", e.getMessage());
            }
        } else {
            if (STREAM_LOG.compareAndSet(false, true)) {
                log.warn("🔕 自定义 OpenAI 兼容 流式未启用（思考不可用）| onThinking={} | key 空={}",
                        onThinking != null, aiChat == null || !aiChat.hasApiKey());
            }
            log.debug("[{}] ⚪ 走 langchain4j 流式（思考不可用）", memoryId);
        }
        return streamingCallLangChain(memoryId, request, onToken);
    }

    /**
     * 流式 LLM 调用（langchain4j 标准实现，不捕获思考）。
     * <p>
     * 通过 streamingChatModel 发起 SSE 流，文本增量实时回调 onToken；
     * 阻塞等待 onCompleteResponse 拿到与 {@code chatModel.chat()} 同构的完整
     * ChatResponse（含 tool_calls），FSM 逻辑完全复用，不感知流式与否。
     */
    private ChatResponse streamingCallLangChain(String memoryId, ChatRequest request, Consumer<String> onToken) {
        CompletableFuture<ChatResponse> future = new CompletableFuture<>();
        StringBuilder acc = new StringBuilder();

        streamingChatModel.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(PartialResponse partial, PartialResponseContext ctx) {
                String text = partial.text();
                if (text == null || text.isEmpty()) return;
                // 兼容两种实现：SSE delta 为增量块，或为累积全文（前缀判断取差量）
                String delta;
                if (text.length() > acc.length() && text.startsWith(acc.toString())) {
                    delta = text.substring(acc.length());
                    acc.append(delta);
                } else {
                    delta = text;
                    acc.append(text);
                }
                if (!delta.isEmpty() && onToken != null) {
                    onToken.accept(delta);
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                future.complete(completeResponse);
            }

            @Override
            public void onError(Throwable error) {
                future.completeExceptionally(error);
            }
        });

        try {
            return future.get(120, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmInterruptException(memoryId);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof LlmInterruptException) throw (LlmInterruptException) cause;
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException("LLM 流式调用失败", cause);
        } catch (TimeoutException e) {
            throw new RuntimeException("LLM 流式调用超时", e);
        }
    }

    // ======================== 自定义 OpenAI 兼容 SSE（捕获思考过程） ========================

    /**
     * 自定义 OpenAI 兼容 SSE 流式调用：直接 POST chat/completions(stream=true)，
     * 逐行解析 {@code delta.reasoning_content}（→ onThinking）与 {@code delta.content}（→ onToken），
     * 并累积 {@code delta.tool_calls} 组回 ChatResponse 供 FSM 使用。
     */
    private ChatResponse doDeepSeekStream(String memoryId, ChatRequest request,
                                         Consumer<String> onToken, Consumer<String> onThinking,
                                         java.util.concurrent.atomic.AtomicBoolean contentEmitted) throws Exception {
        // 1. 序列化请求体（langchain4j ChatRequest → OpenAI 兼容 格式）
        JSONObject body = new JSONObject();
        aiChat.configureRequest(body);
        body.put("stream", true);
        body.put("stream_options", Map.of("include_usage", true));
        body.put("messages", serializeMessages(request.messages()));
        if (request.toolSpecifications() != null && !request.toolSpecifications().isEmpty()) {
            body.put("tools", serializeTools(request.toolSpecifications()));
            body.put("tool_choice", "auto");
        }
        if (request.parameters() != null && request.parameters().temperature() != null) {
            body.put("temperature", request.parameters().temperature());
        }

        // 2. POST 发起流式请求
        URL url = new URL(aiChat.getCompletionsUrl());
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "text/event-stream");
        conn.setRequestProperty("Authorization", "Bearer " + aiChat.getApiKey());
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(120_000);
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toJSONString().getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        if (code != 200) {
            String err = readErrorBody(conn);
            conn.disconnect();
            throw new RuntimeException("文本模型 HTTP " + code + ": " + err);
        }

        // 3. 逐行读取 SSE：正文按 THINK/ANSWER 标记切分为思考/回答两类
        StreamStripper splitter = new StreamStripper();
        int thinkingChars = 0; // reasoning_content 累计字符数（诊断用）
        boolean contentStarted = false; // 思考流→正文流切换标记
        Map<Integer, StringBuilder> toolNameFrags = new HashMap<>();
        Map<Integer, StringBuilder> toolArgFrags = new HashMap<>();
        Map<Integer, String> toolIds = new HashMap<>();
        // token 用量：DeepSeek 把「全量 usage」挂在最后一个分片上（不是单独分片），
        // 所以这里逐分片尝试读取、读到就覆盖——见下方解析处关于"必须在 skip 之前读"的说明
        Integer usageInput = null;
        Integer usageOutput = null;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // 中断检查
                if (interruptManager != null && interruptManager.isSignaled(memoryId)) {
                    throw new LlmInterruptException(memoryId);
                }
                if (line == null || !line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) continue;

                JSONObject chunk = JSON.parseObject(data);
                if (chunk == null) continue;

                // token 用量：必须放在下面两处 skip 之前读。
                // 兼容两种实现——DeepSeek 把 usage 挂在最后一个内容分片上（choices 非空、delta 可能为 null），
                // 而 OpenAI 式实现会单独发一个 choices 为空数组的 usage-only 分片。
                // 若把这段放在 `delta == null` / `choices.isEmpty()` 之后，usage 永远读不到，累计 token 恒为 0。
                JSONObject usage = chunk.getJSONObject("usage");
                if (usage != null) {
                    Integer pt = usage.getInteger("prompt_tokens");
                    Integer ct = usage.getInteger("completion_tokens");
                    if (pt != null) usageInput = pt;
                    if (ct != null) usageOutput = ct;
                }

                JSONArray choices = chunk.getJSONArray("choices");
                if (choices == null || choices.isEmpty()) continue;
                JSONObject delta = choices.getJSONObject(0).getJSONObject("delta");
                if (delta == null) continue;

                // 思考过程增量：原生 reasoning_content → onThinking（实时转发，超长截断容错）
                String reasoning = delta.getString("reasoning_content");
                if (reasoning != null && !reasoning.isEmpty()) {
                    thinkingChars += reasoning.length();
                    if (onThinking != null && thinkingChars <= MAX_THINKING_CHARS) {
                        onThinking.accept(reasoning);
                        log.debug("[{}] thinking+{}B", memoryId, reasoning.length());
                    }
                }
                // 正文增量：剥离 THINK/ANSWER 标记，只输出回答段 → onToken（实时）
                String content = delta.getString("content");
                if (content != null && !content.isEmpty()) {
                    if (!contentStarted) {
                        contentStarted = true;
                        log.info("🔄 [{}] 思考流结束→正文流开始 | thinkingChars={}", memoryId, thinkingChars);
                    }
                    splitter.feed(content, seg -> {
                        contentEmitted.set(true);
                        if (onToken != null) onToken.accept(seg);
                        log.debug("[{}] content+{}B", memoryId, seg.length());
                    });
                }
                // 工具调用增量（按 index 累积片段）
                JSONArray tcs = delta.getJSONArray("tool_calls");
                if (tcs != null) {
                    for (int i = 0; i < tcs.size(); i++) {
                        JSONObject tc = tcs.getJSONObject(i);
                        int idx = tc.getIntValue("index");
                        String id = tc.getString("id");
                        if (id != null && !id.isBlank()) toolIds.put(idx, id);
                        JSONObject fn = tc.getJSONObject("function");
                        if (fn != null) {
                            String name = fn.getString("name");
                            if (name != null && !name.isEmpty()) {
                                toolNameFrags.computeIfAbsent(idx, k -> new StringBuilder()).append(name);
                            }
                            String args = fn.getString("arguments");
                            if (args != null) {
                                toolArgFrags.computeIfAbsent(idx, k -> new StringBuilder()).append(args);
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect();
        }

        // 4. 组回 ChatResponse（文本 + 工具调用）
        List<ToolExecutionRequest> toolRequests = new ArrayList<>();
        for (int idx : new TreeSet<>(toolNameFrags.keySet())) {
            toolRequests.add(ToolExecutionRequest.builder()
                    .id(toolIds.get(idx))
                    .name(toolNameFrags.get(idx).toString())
                    .arguments(toolArgFrags.getOrDefault(idx, new StringBuilder()).toString())
                    .build());
        }
        // 流结束：flush 残余（任何未闭合状态 → 剩余作为答案，绝不丢失内容）
        splitter.flush(seg -> {
            contentEmitted.set(true);
            if (onToken != null) onToken.accept(seg);
        });
        log.info("🔍 [{}] 文本模型流式完成 | thinkingChars={} | answerChars={}",
                memoryId, thinkingChars, splitter.answerText().length());

        // 用「干净回答」（不含标记）作为 aiMessage.text，供 FSM 违规检测 + 最终回复
        String finalText = splitter.answerText();
        AiMessage aiMessage = toolRequests.isEmpty()
                ? AiMessage.builder().text(finalText.isEmpty() ? null : finalText).build()
                : AiMessage.builder()
                        .text(finalText.isEmpty() ? null : finalText)
                        .toolExecutionRequests(toolRequests)
                        .build();

        // usage 回填进 ChatResponse，供 FSM 的 recordLlmMetrics 读取。
        // 不填的话流式路径的 tokenUsage 恒为 null → recordLlmMetrics 只记耗时，
        // 指标页的「累计 token」永远是 0（流式是桌面端唯一路径，等于该指标全程失效）。
        ChatResponse.Builder respBuilder = ChatResponse.builder().aiMessage(aiMessage);
        if (usageInput != null || usageOutput != null) {
            int in = usageInput == null ? 0 : usageInput;
            int out = usageOutput == null ? 0 : usageOutput;
            respBuilder.tokenUsage(new TokenUsage(in, out, in + out));
            log.info("🔢 [{}] 流式 token：输入 {} / 输出 {}（合计 {}）", memoryId, in, out, in + out);
        } else {
            log.warn("⚠️ [{}] 流式响应未带 usage，本次 token 不计入指标（仅记耗时）", memoryId);
        }
        return respBuilder.build();
    }

    /**
     * 流式标记剥离器：从正文中剥离 【THINK_START…THINK_END】 思考块与
     * 【ANSWER_START/ANSWER_END】 标记，输出干净的回答。
     * <p>
     * 思考展示由 reasoning_content（原生）承担，本类只负责让回答干净；
     * 模型不跟标记 / 标记不闭合时，一律把剩余内容作为回答兜底，绝不丢失。
     */
    /**
     * 流式标记剥离器（真·实时流）：正文增量**立即输出**回答段，不缓冲；
     * 仅在末尾可能截断标记时保留少量字符。剥离 【THINK_START…THINK_END】 思考块
     * 与 ANSWER 标记，不展示给用户。思考展示由 reasoning_content（原生）承担。
     */
    static class StreamStripper {
        private static final String[] MARKERS = {"【THINK_START】", "【THINK_END】", "【ANSWER_START】", "【ANSWER_END】"};
        private static final int MAX_KEEP = 13; // 最长标记前缀保留长度（【ANSWER_START】14 字符）
        /** 思考块未闭合安全阈值：剥离超过此字符仍未遇到 THINK_END，视为误判，退出剥离 */
        private static final int MAX_THINK_STRIP = 3000;

        private final StringBuilder buf = new StringBuilder();
        private final StringBuilder answer = new StringBuilder(); // 干净回答累积
        private boolean inThink = false; // 是否在思考块内（剥离）
        private long strippedChars = 0; // 已剥离的"思考"字符数

        /** 喂入 content 增量，实时输出剥离后的回答段 */
        void feed(String delta, Consumer<String> answerCb) {
            buf.append(delta);
            while (buf.length() > 0) {
                String s = buf.toString();
                // 1) 找完整标记
                int bi = -1, bt = -1;
                for (int t = 0; t < MARKERS.length; t++) {
                    int i = s.indexOf(MARKERS[t]);
                    if (i >= 0 && (bi < 0 || i < bi)) { bi = i; bt = t; }
                }
                if (bi >= 0) {
                    if (bi > 0) {
                        emit(s.substring(0, bi), answerCb);
                        buf.delete(0, bi);
                    }
                    handleMarker(bt, answerCb);
                    continue;
                }
                // 2) 尾部可能是标记前缀 → 保留尾部，其余立即输出
                int keep = markerPrefixKeep(s);
                if (keep > 0) {
                    if (s.length() > keep) {
                        emit(s.substring(0, s.length() - keep), answerCb);
                        buf.delete(0, s.length() - keep);
                    }
                    return;
                }
                // 3) 正常内容 → 全量实时输出
                emit(s, answerCb);
                buf.setLength(0);
                return;
            }
        }

        /** 输出（思考块内剥离；正常内容追加到 answer 并实时回调） */
        private void emit(String text, Consumer<String> answerCb) {
            if (text.isEmpty()) return;
            if (inThink) {
                // 安全兜底：剥离超长仍未闭合 → 退出思考剥离，避免正文被误剥
                strippedChars += text.length();
                if (strippedChars > MAX_THINK_STRIP) {
                    log.warn("思考块疑似未闭合（已剥离 {} 字符），退出剥离以防正文丢失", strippedChars);
                    inThink = false;
                } else {
                    return;
                }
            }
            answer.append(text);
            if (answerCb != null) answerCb.accept(text);
        }

        /** 处理开头即标记的情况（调用前 buf 以标记开头） */
        private void handleMarker(int type, Consumer<String> answerCb) {
            if (type == 0) { // THINK_START
                inThink = true;
            } else if (type == 1) { // THINK_END
                inThink = false;
            } else if (type == 3) { // ANSWER_END → 结束，忽略后续
                buf.setLength(0);
                return;
            }
            buf.delete(0, MARKERS[type].length());
        }

        /** 若 s 的尾部（或整体）是某标记前缀，返回需保留的尾部长度；否则 0 */
        private int markerPrefixKeep(String s) {
            for (String m : MARKERS) {
                int maxKeep = m.length() - 1;
                if (s.length() <= maxKeep && m.startsWith(s)) return s.length();
                if (s.length() > maxKeep) {
                    String tail = s.substring(s.length() - maxKeep);
                    if (m.startsWith(tail)) return maxKeep;
                }
            }
            return 0;
        }

        /** 流结束：残余内容输出为回答，绝不丢失 */
        void flush(Consumer<String> answerCb) {
            String rest = buf.toString();
            if (!rest.isEmpty()) emit(rest, answerCb);
            buf.setLength(0);
        }

        /** 干净的最终回答文本（不含标记） */
        String answerText() {
            return answer.toString();
        }
    }

    /** langchain4j 消息列表 → OpenAI 兼容 messages */
    private JSONArray serializeMessages(List<ChatMessage> messages) {
        JSONArray arr = new JSONArray();
        for (ChatMessage m : messages) {
            JSONObject o = new JSONObject();
            if (m instanceof SystemMessage sm) {
                o.put("role", "system");
                o.put("content", sm.text());
            } else if (m instanceof UserMessage um) {
                String text = um.singleText();
                o.put("role", "user");
                o.put("content", text != null ? text : "");
            } else if (m instanceof AiMessage am) {
                o.put("role", "assistant");
                o.put("content", am.text() != null ? am.text() : "");
                List<ToolExecutionRequest> tcs = am.toolExecutionRequests();
                if (tcs != null && !tcs.isEmpty()) {
                    JSONArray tcArr = new JSONArray();
                    for (ToolExecutionRequest tr : tcs) {
                        JSONObject tc = new JSONObject();
                        tc.put("id", tr.id());
                        tc.put("type", "function");
                        JSONObject fn = new JSONObject();
                        fn.put("name", tr.name());
                        fn.put("arguments", tr.arguments() != null ? tr.arguments() : "{}");
                        tc.put("function", fn);
                        tcArr.add(tc);
                    }
                    o.put("tool_calls", tcArr);
                }
            } else if (m instanceof ToolExecutionResultMessage trm) {
                o.put("role", "tool");
                o.put("tool_call_id", trm.id());
                o.put("content", trm.text());
            }
            arr.add(o);
        }
        return arr;
    }

    /** ToolSpecification → OpenAI 兼容 tools */
    private JSONArray serializeTools(List<ToolSpecification> tools) {
        JSONArray arr = new JSONArray();
        for (ToolSpecification ts : tools) {
            JSONObject o = new JSONObject();
            o.put("type", "function");
            JSONObject fn = new JSONObject();
            fn.put("name", ts.name());
            fn.put("description", ts.description() != null ? ts.description() : "");
            if (ts.parameters() != null) {
                // 用 langchain4j 自带的序列化器：正确产出 {"type":"object","properties":...}
                Map<String, Object> schemaMap = JsonSchemaElementJsonUtils.toMap(ts.parameters());
                fn.put("parameters", new JSONObject(schemaMap));
            } else {
                fn.put("parameters", new JSONObject().fluentPut("type", "object")
                        .fluentPut("properties", new JSONObject()));
            }
            o.put("function", fn);
            arr.add(o);
        }
        return arr;
    }

    /** 读取错误响应体（截断） */
    private String readErrorBody(HttpURLConnection conn) {
        try (InputStream is = conn.getErrorStream()) {
            if (is == null) return "";
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            String s = bos.toString(StandardCharsets.UTF_8);
            return s.length() > 200 ? s.substring(0, 200) : s;
        } catch (Exception e) {
            return "";
        }
    }

    // ======================== 违规检测 ========================

    /**
     * 检测用户消息是否命中工具守卫规则，且 LLM 未调用对应工具。
     *
     * <p>委托给 {@code com.demo.agent.toolguard.ToolGuardAudit} 做三态裁决
     * （PASS / FAIL / AMBIGUOUS），只有裁决为 FAIL 且 {@code mode=enforce} 时才返回违规。
     *
     * @return 被判违规的守卫，null 表示合规或不需要阻断
     */
    private ToolGuard detectViolation(String userMessage, String llmText, Set<String> calledTools) {
        if (userMessage == null || llmText == null) return null;
        // 审计组件未注入（手工构造/单测）→ 不做审计
        if (toolGuardAudit == null) return null;

        // ★ 只传用户原话，不能传拼了记忆/历史的完整 prompt——
        //    否则历史里的"天气""法律"等词会把规则匹配带偏，与用户这一句无关的回复被判违规。
        AuditResult audit = toolGuardAudit.audit(userMessage, llmText, calledTools, GUARD_SPECS);

        if (!toolGuardAudit.shouldBlock(audit)) {
            return null;
        }
        // 防御：FAIL 必然带非空期望工具，但真出现空值时宁可不判违规，
        // 也不要把一个 toolName=null 的守卫丢给重试提示词
        if (audit.primaryTool() == null) {
            log.warn("⚠️ 守卫裁决为 FAIL 但期望工具为空，已忽略本次违规：{}", audit);
            return null;
        }
        return new ToolGuard(Set.of(), audit.primaryTool(), audit.ruleDesc());
    }

    /** 构建违规重试时的强化指令消息 */
    private UserMessage buildRetryUserMessage(ToolGuard violated) {
        return new UserMessage(
                "【系统警告 — 违规重试】\n"
                        + "你上一次回复没有调用「" + violated.toolName + "」工具就直接回答了。\n"
                        + "这是一次严重违规！规则要求：用户提到「" + violated.ruleDesc + "」时，"
                        + "必须调用 " + violated.toolName + " 工具。\n"
                        + "请**现在立即调用** " + violated.toolName + " 工具，不要再跳过。\n"
                        + "调用完成前禁止任何文字回复。");
    }

    // ======================== 消息构建 ========================

    private List<ChatMessage> buildMessages(String memoryId, String userMessage, RouteResult route) {
        List<ChatMessage> messages = new ArrayList<>();

        // 用户级标识：优先真实 userId（微信=微信ID，桌面端=user_xxx），兜底 memoryId。
        // 微信路径 memoryId 即用户ID；桌面会话模式 memoryId 是 session_{sessionId}，
        // 用户设置/长期记忆/个人知识都应按真实 userId 隔离，否则缓存 key 变成 session_xxx 永远查不到。
        String settingsUserId = VoiceContext.getUserId();
        if (settingsUserId == null || settingsUserId.isBlank()) {
            settingsUserId = memoryId;
        }

        String modeInstruction = chatModeService.getModeInstruction(settingsUserId);
        messages.add(new SystemMessage(SYSTEM_PROMPT + modeInstruction));

        // 用户记忆：本地 Lucene 记忆库（新）与 Chroma 长期记忆（旧）是<b>二选一</b>。
        // 两者职责完全相同、只是存法不同，同时注入会把同一件事说两遍并白吃上下文预算。
        // memory.integration.enabled=true → 走本地（云端 embedding 欠费也能用）；
        // =false → 保持原有 Chroma 链路，行为与改造前完全一致。
        TraceContext.span("检索长期记忆");
        if (memoryIntegrationService != null && memoryIntegrationService.isEnabled()) {
            MemoryIntegrationService.Block memoryBlock = memoryIntegrationService.buildContextBlock(
                    settingsUserId, userMessage, route == null ? null : route.getIntent());
            if (memoryBlock != null && memoryBlock.text() != null && !memoryBlock.text().isBlank()) {
                messages.add(new SystemMessage(memoryBlock.text()));
                log.info("📌 [{}] 注入 {}/{} 条融合记忆（本地记忆库）",
                        memoryId, memoryBlock.memoryCount(), memoryBlock.fusedCount());
                TraceContext.span("注入本地记忆", memoryBlock.memoryCount() + "条");
            }
        } else {
            List<String> longTermMemories = contextManager.getLongTermMemories(settingsUserId, userMessage);
            if (!longTermMemories.isEmpty()) {
                String memoryBlock = "【关于用户的长期记忆（请结合以下记忆回答）】\n"
                        + longTermMemories.stream()
                        .map(m -> "- " + m)
                        .collect(Collectors.joining("\n"));
                messages.add(new SystemMessage(memoryBlock));
                log.info("📌 [{}] 注入 {} 条长期记忆", memoryId, longTermMemories.size());
                TraceContext.span("注入长期记忆", longTermMemories.size() + "条");
            }
        }

        TraceContext.span("检索知识库");
        List<String> knowledge = (travelKnowledgeService != null)
                ? travelKnowledgeService.searchKnowledge(userMessage, 5)
                : Collections.emptyList();
        if (!knowledge.isEmpty()) {
            String knowledgeBlock = "【旅行知识参考（请优先使用以下信息回答）】\n"
                    + knowledge.stream()
                    .map(k -> "- " + k)
                    .collect(Collectors.joining("\n"));
            messages.add(new SystemMessage(knowledgeBlock));
            log.info("📚 [{}] 注入 {} 条知识库", memoryId, knowledge.size());
            TraceContext.span("注入知识库", knowledge.size() + "条");
        }

        TraceContext.span("检索用户知识库");
        List<String> userKnowledge = (userKnowledgeService != null)
                ? userKnowledgeService.searchKnowledge(settingsUserId, userMessage, 5)
                : Collections.emptyList();
        if (!userKnowledge.isEmpty()) {
            String ukBlock = "【用户个人知识库（你上传的资料）】\n"
                    + userKnowledge.stream()
                    .map(k -> "- " + k)
                    .collect(Collectors.joining("\n"));
            messages.add(new SystemMessage(ukBlock));
            log.info("📂 [{}] 注入 {} 条个人知识", memoryId, userKnowledge.size());
            TraceContext.span("注入个人知识", userKnowledge.size() + "条");
        }

        // 法律条文参考：只在 LAW 意图（或强制关键词）命中时才检索。
        // 本项目原先法律 RAG 完全没接进对话链路，只能靠 REST 手工调用——这里是新增接线，
        // 因此门控不是"优化"而是必需：否则每条消息都会去打一次 Chroma 并把无关法条塞进上下文。
        TraceContext.span("检索法律条文");
        if (lawContextProvider != null) {
            try {
                LawContextProvider.Block lawBlock = lawContextProvider.buildBlock(
                        route == null ? null : route.getIntent(), userMessage, null);
                if (lawBlock != null && lawBlock.text() != null && !lawBlock.text().isBlank()) {
                    messages.add(new SystemMessage(lawBlock.text()));
                    TraceContext.span("注入法律条文", lawBlock.articleCount() + "条");
                }
            } catch (Exception e) {
                log.warn("⚖️ [{}] 法律上下文注入失败（不影响主对话）：{}", memoryId, e.toString());
            }
        }

        // 教育专题参考：生命安全与健康教育 / 青春期性教育，纯关键词门控（跳过词优先）。
        // 门控口径见 EduContextProvider 类注释——词表刻意收紧，宁可漏也不要错。
        if (eduContextProvider != null) {
            try {
                EduContextProvider.Block eduBlock = eduContextProvider.buildBlock(userMessage);
                if (eduBlock != null && eduBlock.text() != null && !eduBlock.text().isBlank()) {
                    messages.add(new SystemMessage(eduBlock.text()));
                    TraceContext.span("注入教育专题资料", eduBlock.chunkCount() + "段");
                }
            } catch (Exception e) {
                log.warn("🎓 [{}] 教育专题上下文注入失败（不影响主对话）：{}", memoryId, e.toString());
            }
        }

        TraceContext.span("加载本地历史",
                contextManager.getRoundCount(memoryId) + "/" + contextManager.getMaxRounds() + "轮");
        List<ChatMessage> history = contextManager.getHistory(memoryId);
        messages.addAll(history);

        messages.add(new UserMessage(userMessage));
        return messages;
    }

    // ======================== 工具执行 ========================

    private String executeTool(ToolExecutionRequest request) {
        String toolName = request.name();
        String argumentsJson = request.arguments();
        log.debug("执行工具：{}，参数：{}", toolName, argumentsJson);

        // 1. 先在本地 @Tool 实例中查找
        for (Object instance : toolInstances) {
            for (Method method : instance.getClass().getMethods()) {
                Tool toolAnnotation = method.getAnnotation(Tool.class);
                if (toolAnnotation == null) continue;
                if (!method.getName().equals(toolName)) continue;

                try {
                    JSONObject args = JSON.parseObject(argumentsJson);
                    Parameter[] params = method.getParameters();
                    Object[] invokeArgs = new Object[params.length];

                    for (int i = 0; i < params.length; i++) {
                        String paramName = params[i].getName();
                        dev.langchain4j.agent.tool.P pAnnotation =
                                params[i].getAnnotation(dev.langchain4j.agent.tool.P.class);
                        if (pAnnotation != null && !pAnnotation.name().isEmpty()) {
                            paramName = pAnnotation.name();
                        }
                        // 根据参数类型做类型转换（fix: 之前所有类型统一 getString 导致 int/boolean 等报类型不匹配）
                        Class<?> paramType = params[i].getType();
                        if (paramType == String.class) {
                            invokeArgs[i] = args.getString(paramName);
                        } else if (paramType == int.class || paramType == Integer.class) {
                            invokeArgs[i] = args.getInteger(paramName);
                        } else if (paramType == long.class || paramType == Long.class) {
                            invokeArgs[i] = args.getLong(paramName);
                        } else if (paramType == boolean.class || paramType == Boolean.class) {
                            invokeArgs[i] = args.getBoolean(paramName);
                        } else if (paramType == double.class || paramType == Double.class) {
                            invokeArgs[i] = args.getDouble(paramName);
                        } else {
                            invokeArgs[i] = args.getString(paramName);
                        }
                    }

                    method.setAccessible(true);
                    Object result = method.invoke(instance, invokeArgs);
                    return result != null ? result.toString() : "工具执行成功但无返回值";
                } catch (Exception e) {
                    log.error("工具执行失败：{}", toolName, e);
                    return "工具执行失败：" + e.getMessage();
                }
            }
        }

        // 2. 本地未找到 → 回退到 MCP 动态工具
        if (mcpManager != null && mcpManager.hasTool(toolName)) {
            return executeMcpTool(request);
        }
        return "未找到工具：" + toolName;
    }

    /**
     * 通过 MCP 执行工具（当本地 @Tool 实例不匹配时回退）。
     * 解析 argumentsJson → Map 后委托给 McpClientManager 路由到正确的 McpSource。
     */
    private String executeMcpTool(ToolExecutionRequest request) {
        if (mcpManager == null) {
            return "未找到工具：" + request.name() + "（MCP 未启用）";
        }
        try {
            JSONObject argsJson = JSON.parseObject(request.arguments());
            Map<String, Object> args = new java.util.LinkedHashMap<>();
            for (String key : argsJson.keySet()) {
                args.put(key, argsJson.get(key));
            }
            return mcpManager.callTool(request.name(), args);
        } catch (Exception e) {
            log.error("MCP 工具执行失败：{}", request.name(), e);
            return "MCP 工具执行失败：" + e.getMessage();
        }
    }

    // ======================== 工具方法 ========================

    private static boolean containsAny(String text, Set<String> keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return null;
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
