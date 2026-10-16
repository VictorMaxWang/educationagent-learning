package com.demo.service;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 模拟真实 Qwen JSON/WAV 契约，不读取实际凭据、不请求计费接口。 */
class AliyunTtsServiceTest {
    private static final String OSS = "dashscope-result-bj.oss-cn-beijing.aliyuncs.com";

    @Test
    void originalMaasCredentialOnlyGoesToMaasAndDownloadHasNoApiKey() {
        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
        AliyunTtsService tts = client(http, "maas-qwen", "sk-official-test", "sk-ws-maas-test", AliyunTtsService.MAAS_ENDPOINT);
        byte[] wav = wav(24000);
        server.expect(requestTo(AliyunTtsService.MAAS_ENDPOINT)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer sk-ws-maas-test"))
                .andExpect(request -> {
                    String raw = ((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString();
                    var body = JSON.parseObject(raw);
                    assertEquals("qwen3-tts-flash", body.getString("model"));
                    assertEquals("Cherry", body.getJSONObject("input").getString("voice"));
                    assertEquals("Chinese", body.getJSONObject("input").getString("language_type"));
                    assertEquals("一起观察植物。", body.getJSONObject("input").getString("text"));
                    assertFalse(body.containsKey("VoiceType"));
                })
                .andRespond(withSuccess("{\"output\":{\"audio\":{\"url\":\"http://" + OSS + "/sample.wav?Signature=fake\"}}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://" + OSS + "/sample.wav?Signature=fake"))
                .andExpect(method(HttpMethod.GET)).andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess(wav, MediaType.parseMediaType("audio/wav")));
        assertTrue(tts.isAvailable());
        assertArrayEquals(wav, tts.synthesizeWav("一起观察植物。", "Cherry"));
        server.verify();
    }

    @Test
    void officialAndMaasCredentialsCannotCrossProviderDomains() {
        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
        // 运行时构造明显的测试占位令牌，验证供应商边界；源码不保存可误认成凭证的长字符串。
        String foreignTestKey = "sk-ws-" + "fixture".repeat(3);
        AliyunTtsService officialWithMaasKey = client(http, "aliyun", foreignTestKey, "", AliyunTtsService.MAAS_ENDPOINT);
        assertFalse(officialWithMaasKey.isAvailable());
        assertNull(officialWithMaasKey.synthesizeWav("你好", "Cherry"));
        AliyunTtsService redirectedMaas = client(http, "maas-qwen", "", "sk-ws-test", AliyunTtsService.DEFAULT_ENDPOINT);
        assertFalse(redirectedMaas.isAvailable());
        assertNull(redirectedMaas.synthesizeWav("你好", "Cherry"));
        server.verify();
    }

    @Test
    void providerFailureAndUntrustedDownloadAreNotReportedAsAudio() {
        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
        AliyunTtsService tts = client(http, "maas-qwen", "", "sk-ws-test", AliyunTtsService.MAAS_ENDPOINT);
        server.expect(requestTo(AliyunTtsService.MAAS_ENDPOINT)).andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{}"));
        assertNull(tts.synthesizeWav("你好", "Cherry"));
        server.verify();
        assertNull(AliyunTtsService.trustedAudioUrl("http://127.0.0.1/private"));
        assertNull(AliyunTtsService.trustedAudioUrl("https://example.com/sample.wav"));
        assertNull(AliyunTtsService.trustedAudioUrl("https://" + OSS + "@example.com/audio.wav"));
        assertNull(AliyunTtsService.trustedAudioUrl("https://" + OSS + ":8080/audio.wav"));
    }

    @Test
    void malformedAudioDoesNotBecomeAnMp3CacheEntry() {
        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
        AliyunTtsService tts = client(http, "maas-qwen", "", "sk-ws-test", AliyunTtsService.MAAS_ENDPOINT);
        server.expect(requestTo(AliyunTtsService.MAAS_ENDPOINT))
                .andRespond(withSuccess("{\"output\":{\"audio\":{\"url\":\"https://" + OSS + "/sample.wav\"}}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://" + OSS + "/sample.wav"))
                .andRespond(withSuccess("not audio", MediaType.TEXT_PLAIN));
        assertNull(tts.synthesizeWav("你好", "Cherry"));
        server.verify();
    }

    @Test
    void unavailableConfigAndOversizedTextMakeNoHttpRequest() {
        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
        assertFalse(client(http, "maas-qwen", "sk-official-test", "", AliyunTtsService.MAAS_ENDPOINT).isAvailable());
        AliyunTtsService tts = client(http, "maas-qwen", "", "sk-ws-test", AliyunTtsService.MAAS_ENDPOINT);
        assertNull(tts.synthesizeWav("字".repeat(601), "Cherry"));
        server.verify();
    }

    private static AliyunTtsService client(RestTemplate http, String provider, String officialKey, String maasKey, String maasEndpoint) {
        return new AliyunTtsService(http, provider, officialKey, maasKey,
                AliyunTtsService.DEFAULT_ENDPOINT, maasEndpoint, AliyunTtsService.DEFAULT_MODEL);
    }

    static byte[] wav(int samples) {
        ByteBuffer b = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(b.capacity() - 8).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        b.putInt(16).putShort((short) 1).putShort((short) 1).putInt(24000).putInt(48000).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(samples * 2);
        for (int i = 0; i < samples; i++) b.putShort((short) (Math.sin(i * 2 * Math.PI * 440 / 24000) * 5000));
        return b.array();
    }
}
