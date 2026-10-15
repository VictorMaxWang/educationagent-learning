package com.demo.study.psychology;

import com.demo.chroma.doc.DocKnowledgeConfig;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 心理学公共知识库配置（教育模块）。
 * <p>
 * 单子库（暂不分子库）：general，所有文件归入该子库。
 * 文档放 data/psychology-knowledge/，文件名不限。
 */
@Component
public class PsychologyKnowledgeConfig extends DocKnowledgeConfig {

    public PsychologyKnowledgeConfig() {
        super(
            "psychology", "psychology_", "data/psychology-knowledge",
            List.of(
                new SubLibrary("general", "心理学", List.of())
            ),
            "你是心理学知识助手，回答严格遵循提供的参考资料。\n"
                + "1. 仅能使用上下文内给出的知识内容作答，不得编造；\n"
                + "2. 上下文不存在相关内容时，直接告知【暂无匹配内容，无法解答该问题】；\n"
                + "3. 语言通俗易懂，条理清晰。",
            3, 1.2, 5,
            "⚠️免责声明：本内容仅基于知识库资料提供参考，不构成专业心理咨询或诊断意见，涉及心理健康问题请咨询专业人士。",
            800, 80, 10
        );
    }
}
