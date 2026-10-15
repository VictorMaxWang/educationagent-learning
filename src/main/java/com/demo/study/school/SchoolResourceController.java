package com.demo.study.school;

import com.demo.chroma.doc.BaseDocKnowledgeController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 校本资源知识库 REST：
 * <ul>
 *   <li>{@code GET  /api/desktop/school/health}</li>
 *   <li>{@code POST /api/desktop/school/search}         body: {uid, question, lib_id}</li>
 *   <li>{@code POST /api/desktop/school/clear-context}  body: {uid}</li>
 *   <li>{@code GET  /api/desktop/school/libs}</li>
 * </ul>
 * 上传入口在教师端：{@code POST /api/edu/teacher/resource/upload}（走角色门）。
 */
@RestController
@RequestMapping("/api/desktop/school")
public class SchoolResourceController extends BaseDocKnowledgeController {

    @Override
    protected String domainId() {
        return "school";
    }
}
