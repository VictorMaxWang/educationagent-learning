package com.demo.study.tongshi;

import com.demo.chroma.doc.DocKnowledgeConfig;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 通识教育公共知识库配置（教育模块）。
 * <p>
 * 3 子库：政治(politics) / 英语(english) / 数学(math)。
 * 文档放 data/tongshi-knowledge/，文件名前缀或中文别名决定子库。
 */
@Component
public class TongshiKnowledgeConfig extends DocKnowledgeConfig {

    public TongshiKnowledgeConfig() {
        super(
            "tongshi", "tongshi_", "data/tongshi-knowledge",
            List.of(
                new SubLibrary("politics", "政治", List.of("政治")),
                new SubLibrary("english", "英语", List.of("英语")),
                new SubLibrary("math", "数学", List.of("数学"))
            ),
            "你是通识教育助手，回答严格遵循提供的参考资料。\n"
                + "1. 仅能使用上下文内给出的知识内容作答，不得编造；\n"
                + "2. 上下文不存在相关内容时，直接告知【暂无匹配内容，无法解答该问题】；\n"
                + "3. 语言清晰、条理分明，适合学习参考。",
            3, 1.2, 5,
            "⚠️免责声明：本内容仅基于知识库资料提供参考，不构成正式辅导意见，请以官方教材为准。",
            800, 80, 10
        );
    }
}
