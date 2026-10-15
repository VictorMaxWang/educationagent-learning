package com.demo.study.tongshi;

import com.demo.chroma.doc.BaseDocKnowledgeController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通识教育知识助手接口（桌面端）。
 * <p>
 * 独立 REST + 独立 LLM 调用，不进主对话。
 */
@RestController
@RequestMapping("/api/desktop/tongshi")
public class TongshiController extends BaseDocKnowledgeController {

    @Override
    protected String domainId() {
        return "tongshi";
    }
}
