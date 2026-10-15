package com.demo.chroma.eval;

import com.demo.chroma.base.EmbeddingService;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * Embedding 通道实跑诊断（默认跳过，需显式开启）。
 *
 * <p>为什么单独做这个：embedding 是整条 RAG 链路的唯一前置依赖，一旦不可用，
 * 入库、检索、RAG 评估全部瘫痪。而跑起整个 Spring 应用还需要 MySQL / Redis / Chroma /
 * bot 控制台，代价很高。本诊断直接实例化 {@link EmbeddingService}，
 * <b>10 秒内</b>给出「通道是否通、维度是多少、失败原因是什么」。</p>
 *
 * <p><b>启用方式</b>：</p>
 * <pre>
 * RAG_EVAL_LIVE=1 ./mvnw test -Dtest=EmbeddingChannelDiag
 * </pre>
 *
 * <p>它会优先读 {@code -Dmemory.embedding.provider=xxx} 指定的提供方，
 * 未指定时读配置文件里的 {@code memory.embedding.provider}。</p>
 */
public class EmbeddingChannelDiag {

    @org.junit.jupiter.api.Test
    void runDiagnostic() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getenv("RAG_EVAL_LIVE") != null,
                "实跑诊断需显式开启：RAG_EVAL_LIVE=1 mvn test -Dtest=EmbeddingChannelDiag");
        main(new String[0]);
    }

    public static void main(String[] args) throws Exception {
        Properties props = loadProps();
        String provider = System.getProperty("memory.embedding.provider",
                props.getProperty("memory.embedding.provider", "dashscope"));

        System.out.println("============ Embedding 通道实跑诊断 ============");
        System.out.println("  提供方: " + provider);
        System.out.println();

        EmbeddingService svc = new EmbeddingService();
        set(svc, "provider", provider);
        set(svc, "expectedDimension",
                Integer.parseInt(props.getProperty("memory.embedding.dimension", "1024")));
        set(svc, "dashscopeApiKey", props.getProperty("dashscope.api.key", ""));
        set(svc, "dashscopeModel", props.getProperty("memory.embedding.model", "text-embedding-v3"));
        set(svc, "ollamaUrl", props.getProperty("memory.embedding.ollama.url", "http://127.0.0.1:11434"));
        set(svc, "ollamaModel", props.getProperty("memory.embedding.ollama.model", "bge-m3"));
        set(svc, "siliconflowKey", props.getProperty("siliconflow.api.key", ""));
        set(svc, "siliconflowUrl",
                props.getProperty("memory.embedding.siliconflow.url", "https://api.siliconflow.cn/v1/embeddings"));
        set(svc, "siliconflowModel", props.getProperty("memory.embedding.siliconflow.model", "BAAI/bge-m3"));

        RestTemplate rt = simpleRestTemplate(30);
        RestTemplate llm = simpleRestTemplate(180);
        set(svc, "restTemplate", rt);
        set(svc, "ollamaRestTemplate", llm);

        // init() 内含启动期自检，会打印实际维度或失败原因
        svc.init();

        System.out.println();
        System.out.println("---------------- 单条嵌入 ----------------");
        List<Float> v = svc.generateEmbedding("民法典规定十八周岁以上的自然人为成年人。");
        if (v == null || v.isEmpty()) {
            System.out.println("  ★ 失败：返回 null（详见上方提供方自检的报错原文）");
        } else {
            System.out.println("  OK 维度 = " + v.size());
            System.out.println("  前 4 个值 = " + v.subList(0, Math.min(4, v.size())));
        }

        System.out.println();
        System.out.println("---------------- 批量嵌入（3 条）----------------");
        List<List<Float>> batch = svc.generateEmbeddingsBatch(
                List.of("十八周岁以上的自然人为成年人。",
                        "不满十八周岁的自然人为未成年人。",
                        "民事主体的人身权利受法律保护。"), 3);
        int ok = 0;
        for (List<Float> one : batch) if (one != null && !one.isEmpty()) ok++;
        System.out.println("  成功 " + ok + "/" + batch.size() + " 条");
        for (int i = 0; i < batch.size(); i++) {
            List<Float> one = batch.get(i);
            System.out.println("    #" + i + " -> " + (one == null || one.isEmpty() ? "失败" : ("维度 " + one.size())));
        }

        System.out.println();
        System.out.println("===============================================");
    }

    private static RestTemplate simpleRestTemplate(int readTimeoutSeconds) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(10_000);
        f.setReadTimeout(readTimeoutSeconds * 1000);
        return new RestTemplate(f);
    }

    private static Properties loadProps() {
        Properties p = new Properties();
        for (String f : List.of("src/main/resources/application.properties",
                "src/main/resources/application-dev.properties")) {
            Path path = Path.of(f);
            if (!Files.exists(path)) continue;
            try (InputStream in = Files.newInputStream(path)) {
                p.load(in);
            } catch (Exception e) {
                System.out.println("读取 " + f + " 失败: " + e.getMessage());
            }
        }
        return p;
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
