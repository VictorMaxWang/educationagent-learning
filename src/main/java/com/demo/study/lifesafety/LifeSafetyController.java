package com.demo.study.lifesafety;

import com.demo.chroma.doc.BaseDocKnowledgeController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 生命安全与健康教育知识库 REST：
 * <ul>
 *   <li>{@code GET  /api/desktop/lifesafety/health}</li>
 *   <li>{@code POST /api/desktop/lifesafety/search}         body: {uid, question, lib_id}</li>
 *   <li>{@code POST /api/desktop/lifesafety/clear-context}  body: {uid}</li>
 *   <li>{@code GET  /api/desktop/lifesafety/libs}</li>
 * </ul>
 * 危机信号在此之前已被 {@code CrisisGuard} 拦下（见 BaseDocKnowledgeController.search）。
 */
@RestController
@RequestMapping("/api/desktop/lifesafety")
public class LifeSafetyController extends BaseDocKnowledgeController {

    @Override
    protected String domainId() {
        return "lifesafety";
    }
}
