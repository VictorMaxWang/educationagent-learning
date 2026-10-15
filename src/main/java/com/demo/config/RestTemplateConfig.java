package com.demo.config;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;

/**
 * RestTemplate 装配。三个 Bean 只差超时，其余完全一致。
 *
 * <h3>为什么只对 DeepSeek 关压缩、其余 Host 继续保持自动解压</h3>
 *
 * 两类服务端对 gzip 的态度正好相反，一刀切两头都会坏事：
 *
 * <table border="1">
 *   <caption>实测（同机同 Key）</caption>
 *   <tr><th>服务端</th><th>行为</th><th>客户端若发 Accept-Encoding: gzip</th></tr>
 *   <tr>
 *     <td>DeepSeek（openresty）</td>
 *     <td>尊重请求头；带了才压</td>
 *     <td><b>只解出 1 个字节</b>（body 长度 = 1，内容就是一个 {@code "{"}），
 *         且解压层会删掉 {@code Content-Encoding} 头，看响应头像"没压缩"；
 *         下游 {@code JSON.parseObject} 报 {@code not allow unquoted fieldName, offset 1}</td>
 *   </tr>
 *   <tr>
 *     <td>和风天气（QWeather）</td>
 *     <td><b>不问就压</b>，不带 Accept-Encoding 也回 {@code Content-Encoding: gzip}</td>
 *     <td>正常。但若客户端<b>关掉</b>自动解压，就会拿到裸 gzip 字节 → JSON 解析失败</td>
 *   </tr>
 * </table>
 *
 * 所以结论是<b>分家</b>：
 * <ul>
 *   <li>内容压缩<b>保持开启</b>（默认行为不动）——和风这类"不问就压"的响应才有得解压；</li>
 *   <li>只对 {@link #NO_COMPRESSION_HOSTS} 里的 Host 显式声明 {@code Accept-Encoding: identity}，
 *       让服务端压根别压——DeepSeek 尊重这个头，回明文 JSON，1 字节的坑就绕过去了。</li>
 * </ul>
 *
 * 实测（带本拦截器，压缩照常开着）：
 * <pre>
 *   DeepSeek → 818 字节完整 JSON ✓      和风 → 439 字节 code=200 ✓
 * </pre>
 *
 * 走 RestTemplate 的 DeepSeek 调用全都在这些 Bean 上（{@code CityIdAiUtil} / {@code CnToEnUtil} /
 * {@code MemoryExtractor} / {@code ToolGuardJudgeService} / {@code LlmRouter} /
 * {@code OutfitService} / {@code ScenicPhotoService}），拦截器按 Host 生效，
 * <b>调用点一行都不用改</b>；将来再遇到同样症状的 Host，往列表里加一条即可。
 */
@Configuration
public class RestTemplateConfig {

    /**
     * 需要显式声明 {@code identity} 的 Host（gzip 解压会把响应弄坏的那些）。
     *
     * <p>只写 DeepSeek；别顺手加和风——和风是"不问就压"，对它要的是<b>保留</b>自动解压。
     */
    private static final List<String> NO_COMPRESSION_HOSTS = List.of("api.deepseek.com");

    /**
     * 命中 {@link #NO_COMPRESSION_HOSTS} 时不请求压缩。
     *
     * <p>在拦截器里设头（而不是全局关客户端解压）的好处：HttpClient5 只在请求没带
     * {@code Accept-Encoding} 时才自己加 {@code gzip, deflate}，这里先占住这个头即可，
     * 其余 Host 的压缩与透明解压完全不受影响。
     */
    private static final ClientHttpRequestInterceptor NO_COMPRESSION_FOR_LLM =
            (request, body, execution) -> {
                String host = request.getURI().getHost();
                if (host != null) {
                    for (String h : NO_COMPRESSION_HOSTS) {
                        if (host.endsWith(h)) {
                            request.getHeaders().set("Accept-Encoding", "identity");
                            break;
                        }
                    }
                }
                return execution.execute(request, body);
            };

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return build(builder, Duration.ofSeconds(10), Duration.ofSeconds(30));
    }

    /**
     * 视觉 API 专用 RestTemplate（超时更长，大图识别可能耗时 30-90s）
     */
    @Bean
    public RestTemplate visionRestTemplate(RestTemplateBuilder builder) {
        return build(builder, Duration.ofSeconds(15), Duration.ofSeconds(120));
    }

    /**
     * LLM 长文本生成专用 RestTemplate（DeepSeek 行程/穿搭/攻略，整段生成可能耗时 1-2 分钟）
     */
    @Bean
    public RestTemplate llmRestTemplate(RestTemplateBuilder builder) {
        return build(builder, Duration.ofSeconds(15), Duration.ofSeconds(180));
    }

    /**
     * 统一装配：超时 + DeepSeek 免压缩拦截器。
     *
     * <p>仍然经过 {@code RestTemplateBuilder}——它会挂上 Micrometer 的
     * {@code http.client.requests} 观测拦截器，绕开 builder 会丢指标。
     * 超时用 HttpClient5 的 {@code RequestConfig} 表达（{@code setResponseTimeout}
     * 即原 readTimeout），不走已废弃的工厂 setter。
     */
    private static RestTemplate build(RestTemplateBuilder builder, Duration connectTimeout, Duration readTimeout) {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(connectTimeout.toMillis()))
                .setResponseTimeout(Timeout.ofMilliseconds(readTimeout.toMillis()))
                .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                // ⚠️ 刻意不调 disableContentCompression()：和风那类"不问就压"的响应要靠它解压
                .setDefaultRequestConfig(requestConfig)
                .build();

        ClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        return builder
                .additionalInterceptors(NO_COMPRESSION_FOR_LLM)
                .requestFactory(() -> factory)
                .build();
    }
}
