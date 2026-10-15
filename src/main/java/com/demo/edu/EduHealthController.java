package com.demo.edu;

import com.demo.chroma.doc.DocKnowledgeAssistant;
import com.demo.chroma.doc.DocKnowledgeConfig;
import com.demo.chroma.doc.DocKnowledgeRegistry;
import com.demo.edu.auth.EduAuthProperties;
import com.demo.edu.safety.CrisisGuardProperties;
import com.demo.edu.safety.CrisisSignalDetector;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 教育域自检面板 {@code GET /api/edu/health}。
 *
 * <h3>为什么要单独做这个接口</h3>
 * 本项目已经三次被"不报错、只是不生效"坑到（累计 token 恒 0、DeepSeek 响应被 gzip 截成 1 字节、
 * 天气 Key 与 Host 错配）。教育域更是如此：<b>语料没放对目录、索引状态还停在 NOT_STARTED、
 * 危机兜底被配成 log 模式</b>——这三种情况在页面上都"看起来正常"。
 * 所以演示前一屏看完全部开关与索引状态。
 *
 * <p>接口里刻意<b>不含</b>教师口令本身，只报"配了几个班"。
 */
@Slf4j
@RestController
@RequestMapping("/api/edu")
public class EduHealthController {

    @Resource
    private CrisisGuardProperties crisisProperties;
    /** L4 内容校验（可选，缺失时只报未装配）。 */
    @Resource
    private com.demo.edu.safety.EduContentSafetyJudge contentSafetyJudge;

    /** 演示样本注入器（可选）。 */
    @Resource
    private com.demo.edu.demo.EduDemoDataSeeder demoSeeder;
    @Resource
    private EduAuthProperties authProperties;
    @Resource
    private DocKnowledgeRegistry docRegistry;

    /** 各领域的真实配置（用于按配置里的 knowledgeDir 数语料，而不是猜目录命名）。 */
    @Autowired(required = false)
    private List<DocKnowledgeConfig> docConfigs;

    @Value("${edu.teacher.min-count-to-show:3}")
    private int minCountToShow;

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> data = new LinkedHashMap<>();

        // ---- 危机兜底 ----
        Map<String, Object> crisis = new LinkedHashMap<>();
        crisis.put("enabled", crisisProperties.isEnabled());
        crisis.put("mode", crisisProperties.getMode());
        crisis.put("enforce", crisisProperties.isEnforce());
        crisis.put("signalWords", CrisisSignalDetector.signalCount()
                + (crisisProperties.getExtraWords() == null ? 0 : crisisProperties.getExtraWords().size()));
        crisis.put("ok", crisisProperties.isEnabled() && crisisProperties.isEnforce());
        crisis.put("hint", crisisProperties.isEnforce()
                ? "命中即返回固定话术（12356 / 12355），不进 LLM"
                : "⚠️ 当前为 log 模式：命中后仍会进入 LLM，话术不会被替换");
        data.put("crisisGuard", crisis);

        // ---- L4 后置内容校验 ----
        data.put("contentSafety", contentSafetyJudge == null
                ? Map.of("enabled", false, "note", "未装配")
                : contentSafetyJudge.snapshot());

        // ---- 演示样本（如实标注性质，避免被当成真实数据）----
        data.put("demoSample", demoSeeder == null
                ? List.of("未装配")
                : demoSeeder.describe());

        // ---- 教师端角色门 ----
        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("enabled", authProperties.isEnabled());
        auth.put("configuredClasses", authProperties.getTeacherClasses().size());
        auth.put("minCountToShow", minCountToShow);
        auth.put("ok", authProperties.isEnabled() && !authProperties.getTeacherClasses().isEmpty());
        auth.put("hint", authProperties.isEnabled()
                ? "口令头 X-Edu-Token；班级由口令决定，接口不接收 classId 参数"
                : "⚠️ 已关闭：/api/edu/teacher/** 一律 401（fail-closed，不是放开）");
        data.put("teacherAuth", auth);

        // ---- 知识库：索引状态 + 语料文件数（"语料放没放对地方"一眼可见）----
        List<Map<String, Object>> libs = new ArrayList<>();
        for (String domainId : docRegistry.domainIds().stream().sorted().toList()) {
            DocKnowledgeAssistant assistant = docRegistry.getAssistant(domainId);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("domainId", domainId);
            item.putAll(assistant.health());
            item.put("subLibraries", assistant.subLibraries());
            item.put("corpusFiles", countCorpusFiles(domainId));
            libs.add(item);
        }
        data.put("knowledgeBases", libs);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 200);
        out.put("msg", "ok");
        out.put("data", data);
        return out;
    }

    /**
     * 数一下该领域知识库目录里的文件数（含子目录），目录取自<b>该领域自己的配置</b>
     * （{@code DocKnowledgeConfig.knowledgeDir}），不靠命名约定猜。
     *
     * <p>这个数字是给"语料放错地方"准备的：文件放在项目根目录而不是
     * {@code data/xxx-knowledge/} 下时，索引器扫描结果为 0，状态依然会走到 DONE ——
     * 页面上看不出任何异常，只有这里能看出来。返回 -1 表示目录不存在。
     */
    private int countCorpusFiles(String domainId) {
        Path dir = null;
        if (docConfigs != null) {
            for (DocKnowledgeConfig c : docConfigs) {
                if (domainId.equals(c.getDomainId())) {
                    dir = Paths.get(c.getKnowledgeDir());
                    break;
                }
            }
        }
        if (dir == null || !Files.exists(dir)) return -1;
        try (Stream<Path> walk = Files.walk(dir)) {
            return (int) walk.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .count();
        } catch (IOException e) {
            return -1;
        }
    }
}
