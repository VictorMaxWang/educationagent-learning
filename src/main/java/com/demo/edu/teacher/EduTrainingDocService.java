package com.demo.edu.teacher;

import com.demo.chroma.doc.DocKnowledgeAssistant;
import com.demo.chroma.doc.DocKnowledgeConfig;
import com.demo.chroma.doc.DocKnowledgeRegistry;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 教师端专题教育培训参考资料。
 *
 * <h3>为什么返回的是"知识库自己的口径"而不是另写一份培训材料</h3>
 * 教师端要与学生端<b>看到同一套内容边界</b>：如果这里另写一份"教学建议"，
 * 两份口径迟早不一致，而教师按培训材料讲、学生端按知识库答，出现矛盾时最难解释。
 * 所以这里直接把该知识库的<b>系统提示词（它的教学口径与边界）</b>、
 * <b>语料清单</b>与<b>免责声明</b>给教师看——教师知道 AI 会怎么答、依据是什么，
 * 他的课堂讲解才能和学生端对齐。
 *
 * <p>不落库、不含任何学生数据；无专题参数时返回全部专题。
 */
@Slf4j
@Service
public class EduTrainingDocService {

    /** 教育域下允许教师查阅的专题（顺序即展示顺序）。 */
    private static final Map<String, String> TOPIC_TITLES = new LinkedHashMap<>();

    static {
        TOPIC_TITLES.put("lifesafety", "生命安全与健康教育");
        TOPIC_TITLES.put("sexedu", "青春期性教育");
        TOPIC_TITLES.put("school", "校本资源");
    }

    /** 每个专题最多列出多少个语料文件（教师端只用于核对"依据了哪些材料"）。 */
    private static final int MAX_SOURCES = 30;

    @Resource
    private DocKnowledgeRegistry registry;

    @Autowired(required = false)
    private List<DocKnowledgeConfig> docConfigs;

    /**
     * @param topic 专题标识；为空或未知时返回全部专题
     */
    public List<Map<String, Object>> trainingDocs(String topic) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, String> entry : TOPIC_TITLES.entrySet()) {
            String domainId = entry.getKey();
            if (topic != null && !topic.isBlank() && !topic.equals(domainId)) continue;
            out.add(one(domainId, entry.getValue()));
        }
        return out;
    }

    private Map<String, Object> one(String domainId, String title) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("topic", domainId);
        doc.put("title", title);

        DocKnowledgeConfig config = configOf(domainId);
        DocKnowledgeAssistant assistant = registry == null ? null : registry.getAssistant(domainId);

        doc.put("available", assistant != null);
        doc.put("subLibraries", assistant == null ? List.of() : assistant.subLibraries());
        doc.put("guidance", config == null ? null : config.getSystemPrompt());
        doc.put("disclaimer", config == null ? null : config.getDisclaimer());
        doc.put("sources", config == null ? List.of() : listSources(config));
        doc.put("note", "本节口径与该专题知识库对学生作答时使用的规则一致；"
                + "课件与讲解请以此为准，避免教师端与学生端出现两套说法。");
        return doc;
    }

    private DocKnowledgeConfig configOf(String domainId) {
        if (docConfigs == null) return null;
        for (DocKnowledgeConfig c : docConfigs) {
            if (domainId.equals(c.getDomainId())) return c;
        }
        return null;
    }

    /** 该专题知识库目录下的语料文件名（不含隐藏文件）。 */
    private List<String> listSources(DocKnowledgeConfig config) {
        List<String> names = new ArrayList<>();
        Path dir = Paths.get(config.getKnowledgeDir());
        if (!Files.exists(dir)) return names;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .limit(MAX_SOURCES)
                    .forEach(p -> names.add(p.getFileName().toString()));
        } catch (Exception e) {
            log.warn("培训资料：列举语料失败（{}）：{}", config.getDomainId(), e.toString());
        }
        return names;
    }
}
