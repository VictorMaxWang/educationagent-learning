package com.demo.util;

import com.demo.config.AiChatProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import jakarta.annotation.Resource;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

@Slf4j
@Component
public class CityIdAiUtil {

    @Resource
    private AiChatProperties aiChat;

    @Resource
    private RestTemplate restTemplate;

     //AI把中文城市名 → 转换成和风天气标准城市ID
    public String getQWeatherCityId(String cityName){

        String prompt = "你现在只需要严格输出和风天气官方标准城市ID，不要多余文字、不要解释。" +
                "查询城市："+cityName+"，只返回纯数字ID，找不到返回null";

        JSONObject reqBody = new JSONObject();
        aiChat.configureRequest(reqBody);
        reqBody.put("temperature",0.1);

        JSONObject msg = new JSONObject();
        msg.put("role","user");
        msg.put("content",prompt);
        reqBody.put("messages",new Object[]{msg});

        // 请求头
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.add("Authorization","Bearer "+aiChat.getApiKey());
        headers.add("Content-Type","application/json");
        org.springframework.http.HttpEntity<String> entity =
                new org.springframework.http.HttpEntity<>(reqBody.toJSONString(),headers);

        try {
            org.springframework.http.ResponseEntity<String> resp =
                    restTemplate.postForEntity(aiChat.getCompletionsUrl(),entity,String.class);
            JSONObject result = JSON.parseObject(resp.getBody());
            String content = result.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim();
            // 校验是否为纯数字ID
            if(content.matches("\\d+")){
                return content;
            }
            return null;
        }catch (Exception e){
            log.error("AI获取城市ID失败",e);
            return null;
        }
    }
}
