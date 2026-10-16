package com.demo.service;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/** 网站语音按明确端点发送凭据，禁止通过 HTTP 跳转把密钥或下载请求转向其他服务。 */
@Configuration
public class WebsiteSpeechHttpConfig {
    @Bean(destroyMethod = "close")
    public CloseableHttpClient websiteSpeechHttpClient() {
        return HttpClients.custom().disableRedirectHandling()
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectTimeout(Timeout.ofSeconds(15)).setResponseTimeout(Timeout.ofSeconds(120)).build())
                .build();
    }

    @Bean
    public RestTemplate websiteSpeechRestTemplate(RestTemplateBuilder builder,
            @Qualifier("websiteSpeechHttpClient") CloseableHttpClient httpClient) {
        return builder.requestFactory(() -> new HttpComponentsClientHttpRequestFactory(httpClient)).build();
    }
}
