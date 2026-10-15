package com.demo.study.psychology;

import com.demo.chroma.doc.BaseDocKnowledgeController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 心理学知识助手接口（桌面端）。
 * <p>
 * 独立 REST + 独立 LLM 调用，不进主对话。
 */
@RestController
@RequestMapping("/api/desktop/psychology")
public class PsychologyController extends BaseDocKnowledgeController {

    @Override
    protected String domainId() {
        return "psychology";
    }
}
