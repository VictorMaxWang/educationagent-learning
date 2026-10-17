package com.demo.edu.classroom.recommendation;

import com.demo.config.AiChatProperties;
import com.demo.edu.classroom.ai.ClassroomAiGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** 真实朗诵失败的最小重放：省略号拼接引文必须拒绝，修订应指出具体字段而非只说JSON格式。 */
class RecitationMatchContractRecoveryTest {
    private final ObjectMapper json=new ObjectMapper();
    private static final String TITLE="毛泽东《沁园春·长沙》—朗诵版";
    private static final String STITCHED="毛泽东《沁园春·长沙》—朗诵版...橘子洲头的芦苇...湘江的百舸争流...更是“到中流击水”的永恒信念";
    private final BilibiliVideoCatalog.Video video=new BilibiliVideoCatalog.Video("BV1swoEYCEzp:whole","BV1swoEYCEzp",0,
            "https://www.bilibili.com/video/BV1swoEYCEzp/",TITLE,
            "橘子洲头的芦苇，是农民的叹息，亦是革命的火种；湘江的百舸争流，是旧时代的残影，更是新中国的号角。更是“到中流击水”的永恒信念。",
            "","公开视频作者",180,"2026-10-17T03:47:45Z",json.createObjectNode());

    @Test void repairsStitchedQuoteOnlyAfterReceivingTheSpecificFieldAndEvidenceRule() throws Exception {
        List<JsonNode> requests=new CopyOnWriteArrayList<>();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{
            try {
                JsonNode request=json.readTree(exchange.getRequestBody());requests.add(request);
                String repair=request.path("messages").path(3).path("content").asText();
                boolean targeted=repair.contains("candidates[0].metadataQuote")&&repair.contains("连续原文")&&repair.contains("拼接");
                ObjectNode envelope=json.createObjectNode().put("model","qwen3.8-flash").put("id","known-fixture");
                envelope.putArray("choices").addObject().put("finish_reason","stop").putObject("message").put("content",matching(targeted?TITLE:STITCHED).toString());
                byte[] response=json.writeValueAsBytes(envelope);exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);
            }finally{exchange.close();}
        });
        server.start();
        var properties=new AiChatProperties(new MockEnvironment().withProperty("AI_CHAT_BASE_URL","http://127.0.0.1:"+server.getAddress().getPort()+"/v1")
                .withProperty("AI_CHAT_MODEL","qwen3.8-flash").withProperty("AI_CHAT_API_KEY","synthetic-fixture-key"));
        try(var gateway=new ClassroomAiGateway(properties)) {
            var result=gateway.structured("video-metadata-matching","只引用连续原文","公开视频资料",Duration.ofSeconds(5),
                    value->ClassroomRecommendationService.validateMatches(value,Set.of("感受朗诵节奏"),List.of(video)));
            assertEquals(TITLE,result.value().path("candidates").get(0).path("metadataQuote").asText());
            assertEquals(2,requests.size(),"一次已知校验失败只能使用原有的一次修订预算");
            JsonNode first=result.evidence().path("attempts").get(0);
            assertEquals("VIDEO_METADATA_QUOTE_INVALID",first.path("contractCode").asText());
            assertEquals("candidates[0].metadataQuote",first.path("contractField").asText());
            assertFalse(result.evidence().toString().contains(STITCHED),"诊断仅记录字段路径，不保存原始响应");
        }finally{server.stop(0);}
    }
    @Test void stillRejectsTheSameStitchedQuoteWhenBothResponsesKeepIt() throws Exception {
        List<JsonNode> requests=new CopyOnWriteArrayList<>();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{
            try {
                requests.add(json.readTree(exchange.getRequestBody()));
                ObjectNode envelope=json.createObjectNode().put("model","qwen3.8-flash");
                envelope.putArray("choices").addObject().put("finish_reason","stop").putObject("message").put("content",matching(STITCHED).toString());
                byte[] response=json.writeValueAsBytes(envelope);exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);
            }finally{exchange.close();}
        });
        server.start();
        var properties=new AiChatProperties(new MockEnvironment().withProperty("AI_CHAT_BASE_URL","http://127.0.0.1:"+server.getAddress().getPort()+"/v1")
                .withProperty("AI_CHAT_MODEL","qwen3.8-flash").withProperty("AI_CHAT_API_KEY","synthetic-fixture-key"));
        try(var gateway=new ClassroomAiGateway(properties)) {
            var failure=assertThrows(ClassroomAiGateway.Failure.class,()->gateway.structured("video-metadata-matching","只引用连续原文","公开视频资料",Duration.ofSeconds(5),
                    value->ClassroomRecommendationService.validateMatches(value,Set.of("感受朗诵节奏"),List.of(video))));
            assertEquals("contract-invalid",failure.failureKind());assertEquals(2,requests.size());
            assertEquals("candidates[0].metadataQuote",failure.evidence().path("attempts").get(1).path("contractField").asText());
        }finally{server.stop(0);}
    }
    @Test void reportsBothInvalidQuoteFieldsInTheSameAllowedRepair() throws Exception {
        var second=new BilibiliVideoCatalog.Video("BV1FN4y1v7Yx:whole","BV1FN4y1v7Yx",0,"https://www.bilibili.com/video/BV1FN4y1v7Yx/",
                "毛主席诗词《沁园春·长沙》湖南话+普通话朗诵","独立寒秋，湘江北去，橘子洲头。","","公开视频作者",180,"2026-10-17T03:47:45Z",json.createObjectNode());
        String secondStitched=second.title()+"...独立寒秋，湘江北去，橘子洲头...";
        List<JsonNode> requests=new CopyOnWriteArrayList<>();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{
            try {
                JsonNode request=json.readTree(exchange.getRequestBody());requests.add(request);
                String repair=request.path("messages").path(3).path("content").asText();
                ObjectNode output=matching(repair.contains("candidates[0].metadataQuote")?TITLE:STITCHED);
                ObjectNode other=(ObjectNode)matching(repair.contains("candidates[1].metadataQuote")?second.title():secondStitched).path("candidates").get(0);
                other.put("id",second.id());((com.fasterxml.jackson.databind.node.ArrayNode)output.path("candidates")).add(other);
                ObjectNode envelope=json.createObjectNode().put("model","qwen3.8-flash");
                envelope.putArray("choices").addObject().put("finish_reason","stop").putObject("message").put("content",output.toString());
                byte[] response=json.writeValueAsBytes(envelope);exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);
            }finally{exchange.close();}
        });
        server.start();var properties=new AiChatProperties(new MockEnvironment().withProperty("AI_CHAT_BASE_URL","http://127.0.0.1:"+server.getAddress().getPort()+"/v1")
                .withProperty("AI_CHAT_MODEL","qwen3.8-flash").withProperty("AI_CHAT_API_KEY","synthetic-fixture-key"));
        try(var gateway=new ClassroomAiGateway(properties)) {
            var result=gateway.structured("video-metadata-matching","只引用连续原文","公开视频资料",Duration.ofSeconds(5),
                    value->ClassroomRecommendationService.validateMatches(value,Set.of("感受朗诵节奏"),List.of(video,second)));
            assertEquals(2,requests.size());assertEquals(second.title(),result.value().path("candidates").get(1).path("metadataQuote").asText());
            JsonNode fields=result.evidence().path("attempts").get(0).path("contractFields");
            assertEquals(json.valueToTree(List.of("candidates[0].metadataQuote","candidates[1].metadataQuote")),fields);
        }finally{server.stop(0);}
    }
    private ObjectNode matching(String quote) {
        ObjectNode value=json.createObjectNode();ObjectNode item=value.putArray("candidates").addObject()
                .put("id",video.id()).put("eligible",true).put("gradeCompatibility","compatible").put("purpose","recitation")
                .put("reason","补充朗诵示范").put("metadataQuote",quote).put("preferenceMatch",false);
        item.putArray("goals").add("感受朗诵节奏");return value;
    }
}
