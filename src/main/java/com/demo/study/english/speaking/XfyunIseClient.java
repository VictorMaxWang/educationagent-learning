package com.demo.study.english.speaking;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 讯飞 ISE 口语评测客户端（流式版 WebSocket）。
 * <p>
 * 协议：wss://ise-api.xfyun.cn/v2/open-ise，HMAC-SHA256 签名走 URL 鉴权参数（authorization/date/host）。
 * 流程：
 * 1. 连接成功后发送首帧 cmd=ssb（common.app_id + business 参数，text 需加 UTF-8 BOM FEFF）
 * 2. 分片发送 cmd=auw 音频帧（base64 放 data.data；首帧 aus=1、中间 aus=2、结束帧 aus=4 且 status=2 data 为空串）
 * 3. 服务端返回 data.data 为 base64 的 XML 评测结果（status=2 为最终结果）
 * 未配置凭证时抛异常，由上层提示。
 */
@Slf4j
@Component
public class XfyunIseClient {

    @Resource
    private XfyunIseConfig config;

    private static final String HOST = "ise-api.xfyun.cn";
    private static final String PATH = "/v2/open-ise";
    /** 单帧 base64 长度上限（官方 ≤26000，留余量） */
    private static final int CHUNK_B64 = 15000;
    /** 评测超时（秒） */
    private static final long TIMEOUT_SECONDS = 60;

    /**
     * 口语评测（流式 WebSocket）：返回评测结果 JSONObject。
     *
     * @param text        目标英文文本
     * @param audioBase64 用户录音（16kHz 16bit PCM 的 base64）
     * @param category    评测类型：read_word（单词）/ read_sentence（句子/短语）
     * @return {"scores":{"total":总分}, "raw_xml":"讯飞返回的 XML 评测明细"}
     */
    public JSONObject evaluate(String text, String audioBase64, String category) {
        if (!config.isConfigured()) {
            throw new IllegalStateException("讯飞 ISE 未配置（speech.xfyun.app-id/api-key/api-secret 为占位 xxx），请在配置里填入真实凭证");
        }
        String wsUrl = buildAuthUrl();
        System.out.println("[ISE] ===== 讯飞 ISE 流式评测 ===== category=" + category + " text=" + text
                + " audioBase64长度=" + (audioBase64 == null ? 0 : audioBase64.length()));
        log.info("🗣️ [ISE] 流式评测: category={}, text={}", category, text);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<JSONObject> resultRef = new AtomicReference<>();
        AtomicReference<String> errorRef = new AtomicReference<>();
        StringBuilder xmlBuf = new StringBuilder();

        try {
            WebSocketClient client = new WebSocketClient(new URI(wsUrl)) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    try {
                        // ① 首帧：参数上传
                        send(ssbFrame(text, category).toJSONString());
                        // ② 音频分片 + 结束帧
                        sendAudioFrames(this, audioBase64);
                    } catch (Exception e) {
                        errorRef.set("发送评测数据失败: " + e.getMessage());
                        latch.countDown();
                    }
                }

                @Override
                public void onMessage(String message) {
                    try {
                        JSONObject json = JSON.parseObject(message);
                        int code = json.getIntValue("code");
                        if (code != 0) {
                            errorRef.set("讯飞返回错误 code=" + code + ": " + json.getString("message"));
                            latch.countDown();
                            return;
                        }
                        JSONObject data = json.getJSONObject("data");
                        if (data != null) {
                            String b64 = data.getString("data");
                            int status = data.getIntValue("status");
                            if (b64 != null && !b64.isBlank()) {
                                // 关键修复：讯飞每帧 data.data 解码后是「一整个完整评测快照」，
                                // 官方 demo 只取 status==2 帧直接解析。此前用 append 拼接多帧完整文档，
                                // 导致 total_score.item(0) 取到第一帧中间快照的总分（常为 0）。
                                // 改为只保留当前帧，status==2 帧即最终结果。
                                xmlBuf.setLength(0);
                                xmlBuf.append(new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8));
                            }
                            log.info("🗣️ [ISE] 收到结果帧 status={} 解码XML长度={}", status, xmlBuf.length());
                            // status=2 为最终结果
                            if (status == 2) {
                                String finalXml = xmlBuf.toString();
                                System.out.println("[ISE] ==== 最终结果原始报文 ==== " + truncate(finalXml, 400));
                                resultRef.set(parseXmlResult(finalXml, category));
                                latch.countDown();
                                this.close();
                            }
                        }
                    } catch (Exception e) {
                        errorRef.set("解析讯飞响应失败: " + e.getMessage());
                        latch.countDown();
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    if (latch.getCount() > 0) {
                        errorRef.set("讯飞连接提前关闭 code=" + code + " reason=" + reason);
                        latch.countDown();
                    }
                }

                @Override
                public void onError(Exception ex) {
                    errorRef.set("讯飞 WebSocket 异常: " + ex.getMessage());
                    latch.countDown();
                }
            };

            client.connect();
            boolean finished = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                client.close();
                throw new RuntimeException("讯飞 ISE 评测超时（" + TIMEOUT_SECONDS + "s）");
            }
            if (errorRef.get() != null) {
                throw new RuntimeException(errorRef.get());
            }
            JSONObject r = resultRef.get();
            if (r == null) {
                throw new RuntimeException("讯飞 ISE 未返回评测结果");
            }
            System.out.println("[ISE] 评测完成, 总分=" + r.getJSONObject("scores").getIntValue("total"));
            return r;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("讯飞 ISE 调用异常: " + e.getMessage(), e);
        }
    }

    /** 构造带鉴权参数的 wss URL（官方 getAuthUrl 逻辑：authorization/date/host 三个 URL 参数） */
    private String buildAuthUrl() {
        try {
            SimpleDateFormat df = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
            df.setTimeZone(TimeZone.getTimeZone("GMT"));
            String date = df.format(new Date());

            String signatureOrigin = "host: " + HOST + "\ndate: " + date + "\nGET " + PATH + " HTTP/1.1";
            String signature = Base64.getEncoder().encodeToString(hmacSha256(config.getApiSecret(), signatureOrigin));
            String authorizationOrigin = "api_key=\"" + config.getApiKey() + "\", algorithm=\"hmac-sha256\", "
                    + "headers=\"host date request-line\", signature=\"" + signature + "\"";
            String authorization = Base64.getEncoder().encodeToString(authorizationOrigin.getBytes(StandardCharsets.UTF_8));

            return "wss://" + HOST + PATH
                    + "?authorization=" + URLEncoder.encode(authorization, StandardCharsets.UTF_8)
                    + "&date=" + URLEncoder.encode(date, StandardCharsets.UTF_8)
                    + "&host=" + HOST;
        } catch (Exception e) {
            throw new RuntimeException("讯飞鉴权 URL 生成失败", e);
        }
    }

    /** 首帧 cmd=ssb：common.app_id + business 参数（英文试题需加试卷头标记） */
    private JSONObject ssbFrame(String text, String category) {
        JSONObject common = new JSONObject();
        common.put("app_id", config.getAppId());

        JSONObject business = new JSONObject();
        business.put("cmd", "ssb");
        business.put("sub", "ise");
        business.put("ent", "en_vip");
        business.put("category", category);
        business.put("tte", "utf-8");
        business.put("auf", "audio/L16;rate=16000");
        business.put("aue", "raw");
        // 英文题型必须在试题前加试卷头标记，否则引擎报 48195（评测试卷未设置）：
        // read_word → [word]，read_sentence/其他 → [content]
        business.put("text", ("read_word".equals(category) ? "[word]\n" : "[content]\n") + text);

        JSONObject data = new JSONObject();
        data.put("status", 0);

        JSONObject frame = new JSONObject();
        frame.put("common", common);
        frame.put("business", business);
        frame.put("data", data);
        return frame;
    }

    /**
     * 分片发送音频帧 cmd=auw：base64 放 data.data。
     * 首帧 aus=1、中间分片 aus=2、最后结束帧 aus=4 且 status=2 data 为空串。
     */
    private void sendAudioFrames(WebSocketClient client, String audioBase64) {
        if (audioBase64 == null || audioBase64.isBlank()) {
            throw new IllegalArgumentException("录音音频为空");
        }
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < audioBase64.length(); i += CHUNK_B64) {
            chunks.add(audioBase64.substring(i, Math.min(i + CHUNK_B64, audioBase64.length())));
        }
        for (int i = 0; i < chunks.size(); i++) {
            // 关键修复：首帧 data.status 必须为 0（协议要求首帧=0/中间=1），此前全部传 1 导致服务端会话初始化异常
            int aus = (i == 0) ? 1 : 2;
            int status = (i == 0) ? 0 : 1;
            client.send(audioFrame(aus, status, chunks.get(i)).toJSONString());
            // 按官方推荐节奏发送（每帧 40ms 起），避免突发丢帧/错序
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // 结束帧：aus=4，status=2，data 为空字符串（明确的结束信号）
        client.send(audioFrame(4, 2, "").toJSONString());
    }

    /** 音频上传帧 cmd=auw */
    private JSONObject audioFrame(int aus, int status, String base64Data) {
        JSONObject business = new JSONObject();
        business.put("cmd", "auw");
        business.put("aus", aus);
        business.put("aue", "raw");

        JSONObject data = new JSONObject();
        data.put("status", status);
        data.put("data", base64Data);
        data.put("data_type", 1);
        data.put("encoding", "raw");

        JSONObject frame = new JSONObject();
        frame.put("business", business);
        frame.put("data", data);
        return frame;
    }

    /**
     * 把讯飞返回的 XML 评测明细解析为 JSONObject：scores.total + raw_xml 原文。
     * 按评测类型分支（read_word 与 read_sentence 字段结构不同）：
     *  - read_word：直接提取 total_score（元素或 read_chapter 属性），0-5 → ×20 转 0-100
     *  - read_sentence：read_chapter 的 accuracy/fluency/integrity（0-5），加权 准确×0.5+流利×0.3+完整×0.2 → ×20
     * 拒绝标记：read_word 下 reject_type=1024 为正常标识，不拦截。
     */
    private JSONObject parseXmlResult(String xml, String category) {
        if (xml == null || xml.isBlank()) {
            throw new RuntimeException("讯飞返回空评测结果");
        }
        try {
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            JSONObject scores = new JSONObject();

            int score = 0;
            boolean rejected = false;
            int rejectType = 0;
            boolean isWord = "read_word".equals(category);

            NodeList chapters = doc.getElementsByTagName("read_chapter");
            Element chapter = chapters.getLength() > 0 ? (Element) chapters.item(0) : null;
            if (chapter != null) {
                // 维度分（两模式都尝试存，read_sentence 用它打分）
                scores.put("accuracy", attrDouble(chapter, "accuracy_score"));
                scores.put("fluency", attrDouble(chapter, "fluency_score"));
                scores.put("integrity", attrDouble(chapter, "integrity_score"));
                // 拒绝标记
                rejected = "true".equalsIgnoreCase(chapter.getAttribute("is_rejected"));
                rejectType = attrInt(chapter, "reject_type");
                // read_word 下 reject_type=1024 为正常标识，无需拦截
                if (rejectType == 1024) {
                    rejected = false;
                    System.out.println("[ISE] read_word reject_type=1024 视为正常，不拦截");
                }
                scores.put("rejected", rejected);
                scores.put("rejectType", rejectType);
                if (rejected) {
                    System.out.println("[ISE] ⚠️ 引擎拒绝本次评测 reject_type=" + rejectType
                            + "（录音过短/过安静/背景噪音等）");
                }
            }

            // 逐词得分：<ww> 元素（read_sentence/read_word 共用，含 per-word 三维度与总分）
            List<Map<String, Object>> perWord = new ArrayList<>();
            NodeList wws = doc.getElementsByTagName("ww");
            for (int i = 0; i < wws.getLength(); i++) {
                Element ww = (Element) wws.item(i);
                String w = ww.getTextContent() == null ? "" : ww.getTextContent().trim();
                if (w.isEmpty()) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("word", w);
                m.put("accuracy", attrDouble(ww, "accuracy"));
                m.put("fluency", attrDouble(ww, "fluency"));
                m.put("integrity", attrDouble(ww, "integrity"));
                m.put("total", attrDouble(ww, "total"));
                perWord.add(m);
            }
            scores.put("perWord", perWord);

            if (isWord) {
                // read_word：直接取 total_score（元素优先，其次 read_chapter 属性），0-5 → ×20
                double raw = 0;
                NodeList totals = doc.getElementsByTagName("total_score");
                if (totals.getLength() > 0) {
                    raw = Double.parseDouble(totals.item(0).getTextContent().trim());
                } else if (chapter != null) {
                    raw = attrDouble(chapter, "total_score");
                }
                score = (int) Math.round(raw * 20);
                System.out.println("[ISE] read_word total_score=" + raw + " → 换算总分=" + score);
            } else {
                // read_sentence：准确/流利/完整加权（0-5）→ ×20
                if (chapter != null) {
                    double acc = attrDouble(chapter, "accuracy_score");
                    double flu = attrDouble(chapter, "fluency_score");
                    double intg = attrDouble(chapter, "integrity_score");
                    double total5 = acc * 0.5 + flu * 0.3 + intg * 0.2; // 0-5
                    score = (int) Math.round(total5 * 20);               // ×20 → 0-100
                    System.out.println("[ISE] read_sentence 维度分: 准确=" + acc + " 流利=" + flu
                            + " 完整=" + intg + " → 换算总分=" + score);
                }
            }
            scores.put("total", score);

            JSONObject r = new JSONObject();
            r.put("scores", scores);
            r.put("raw_xml", xml);
            return r;
        } catch (Exception e) {
            throw new RuntimeException("评测结果 XML 解析失败: " + e.getMessage()
                    + " | xml=" + truncate(xml, 300), e);
        }
    }

    /** 读取元素属性为 double，缺失/非法返回 0 */
    private double attrDouble(Element el, String name) {
        try {
            String v = el.getAttribute(name);
            return v == null || v.isEmpty() ? 0 : Double.parseDouble(v);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 读取元素属性为 int，缺失/非法返回 0 */
    private int attrInt(Element el, String name) {
        try {
            String v = el.getAttribute(name);
            return v == null || v.isEmpty() ? 0 : (int) Double.parseDouble(v);
        } catch (Exception e) {
            return 0;
        }
    }

    private byte[] hmacSha256(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 失败", e);
        }
    }

    private String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
