package com.demo;

import com.alibaba.fastjson2.JSONObject;
import com.demo.study.english.speaking.XfyunIseClient;
import com.demo.study.english.speaking.XfyunIseConfig;

import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.util.Properties;

/**
 * 讯飞 ISE 流式评测诊断：用 PCM 文件 + 生产 XfyunIseClient 跑一次评测。
 * <p>
 * 用法：IseWsDiag [16k_pcm文件] [文本] [read_word|read_sentence]
 * - 有 PCM 文件参数：用该文件；无参数：合成 2s 纯音
 * - 凭证从 application-dev.properties 的 speech.xfyun.* 读取
 */
public class IseWsDiag {

    public static void main(String[] args) throws Exception {
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream("src/main/resources/application-dev.properties")) {
            p.load(in);
        }
        XfyunIseConfig cfg = new XfyunIseConfig();
        cfg.setAppId(p.getProperty("speech.xfyun.app-id"));
        cfg.setApiKey(p.getProperty("speech.xfyun.api-key"));
        cfg.setApiSecret(p.getProperty("speech.xfyun.api-secret"));

        byte[] pcm;
        if (args.length > 0) {
            pcm = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(args[0]));
        } else {
            int n = 16000 * 2;
            pcm = new byte[n * 2];
            for (int i = 0; i < n; i++) {
                short s = (short) (Math.sin(2 * Math.PI * 440 * ((double) i / 16000)) * 12000);
                pcm[i * 2] = (byte) (s & 0xff);
                pcm[i * 2 + 1] = (byte) ((s >> 8) & 0xff);
            }
        }
        String text = args.length > 1 ? args[1] : "apple";
        String category = args.length > 2 ? args[2] : "read_word";
        System.out.println("[Diag] " + pcm.length + " 字节 PCM, text=" + text + ", category=" + category);

        XfyunIseClient client = new XfyunIseClient();
        Field f = XfyunIseClient.class.getDeclaredField("config");
        f.setAccessible(true);
        f.set(client, cfg);

        String b64 = java.util.Base64.getEncoder().encodeToString(pcm);
        try {
            JSONObject r = client.evaluate(text, b64, category);
            System.out.println("[Diag] ✅ score=" + r.getJSONObject("scores").getIntValue("total")
                    + " raw_xml=" + r.getString("raw_xml").length() + " chars");
        } catch (Exception e) {
            System.out.println("[Diag] ❌ " + e.getMessage());
        }
    }
}
