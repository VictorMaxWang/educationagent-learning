package com.demo.study.sexedu;

import com.demo.chroma.doc.BaseDocKnowledgeController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 青春期性教育知识库 REST：
 * <ul>
 *   <li>{@code GET  /api/desktop/sexedu/health}</li>
 *   <li>{@code POST /api/desktop/sexedu/search}         body: {uid, question, lib_id}</li>
 *   <li>{@code POST /api/desktop/sexedu/clear-context}  body: {uid}</li>
 *   <li>{@code GET  /api/desktop/sexedu/libs}</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/desktop/sexedu")
public class SexEduController extends BaseDocKnowledgeController {

    @Override
    protected String domainId() {
        return "sexedu";
    }
}
