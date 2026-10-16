package com.demo.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 网站朗读入口：课堂教师、同学、成长问答与全局语音球统一使用 Cherry（芊悦）。
 * 原 TtsService 保留腾讯协议，微信和英语已有明确音色不受本次网站设置影响。
 */
@Service
public class WebsiteTtsService {
    public static final String DEFAULT_VOICE = "Cherry";
    public static final String DEFAULT_VOICE_NAME = "芊悦";
    private final TtsService tencent;
    private final AliyunTtsService aliyun;
    private final MaasRealtimeTtsService realtime;
    private final WebsiteAudioEncoder encoder;
    private final String provider;
    private final String voice;

    public WebsiteTtsService(TtsService tencent, AliyunTtsService aliyun, MaasRealtimeTtsService realtime, WebsiteAudioEncoder encoder,
            @Value("${website.tts.provider:maas-qwen-realtime}") String provider,
            @Value("${website.tts.voice:Cherry}") String voice) {
        this.tencent = tencent;
        this.aliyun = aliyun;
        this.realtime = realtime;
        this.encoder = encoder;
        this.provider = provider == null ? "maas-qwen-realtime" : provider.trim().toLowerCase(java.util.Locale.ROOT);
        this.voice = voice == null || voice.isBlank() ? DEFAULT_VOICE : voice.trim();
    }

    public boolean isAvailable() {
        return switch (provider) {
            case "aliyun", "maas-qwen" -> aliyun.isAvailable() && encoder.isAvailable();
            case "maas-qwen-realtime" -> realtime.isAvailable() && encoder.isAvailable();
            case "tencent" -> tencent.isAvailable();
            default -> false;
        };
    }
    public String provider() { return provider; }
    public String model() { return "maas-qwen-realtime".equals(provider) ? realtime.model() : isQwen() ? aliyun.model() : "TextToVoice"; }
    public String getDefaultVoiceType() { return isQwen() ? voice : tencent.getDefaultVoiceType(); }

    /** 不把旧腾讯数字 ID 猜测映射为阿里云音色；阿里云模式只使用明确的名称配置。 */
    public String resolveVoice(String requested) {
        if (isQwen()) return voice;
        return requested == null || requested.isBlank() ? tencent.getDefaultVoiceType() : requested.trim();
    }

    public String voiceName(String voiceId) {
        if (isQwen()) return DEFAULT_VOICE.equals(voiceId) ? DEFAULT_VOICE_NAME : "阿里云音色 " + voiceId;
        return TtsService.voiceName(voiceId);
    }

    /** 缓存必须包含供应商和模型；只改音色标签不能复用旧腾讯音频。 */
    public String cacheIdentity(String voiceId) {
        String source = "maas-qwen-realtime".equals(provider) ? realtime.cacheIdentity() : isQwen() ? aliyun.cacheIdentity() : "tencent\nTextToVoice";
        return "website-tts-v2\n" + source + "\n" + resolveVoice(voiceId) + "\nmp3\n16000\n64k";
    }

    /** 每次接收课堂已分好的 ≤150 字片段，绝不返回伪装为 MP3 的 WAV。 */
    public byte[] synthesize(String text, String requestedVoice, int sampleRate) {
        if (text == null || text.isBlank() || text.length() > 150 || !isAvailable()) return null;
        String selected = resolveVoice(requestedVoice);
        if ("tencent".equals(provider)) return tencent.synthesize(text, selected, sampleRate);
        if (!isQwen()) return null;
        byte[] wav = "maas-qwen-realtime".equals(provider) ? realtime.synthesizeWav(text, selected) : aliyun.synthesizeWav(text, selected);
        return wav == null ? null : encoder.toMp3(wav);
    }

    private boolean isQwen() { return "aliyun".equals(provider) || "maas-qwen".equals(provider) || "maas-qwen-realtime".equals(provider); }
}
