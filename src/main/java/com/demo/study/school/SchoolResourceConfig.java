package com.demo.study.school;

import com.demo.chroma.doc.DocKnowledgeConfig;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 校本资源知识库（乡村教师助教工作台的备课素材来源）。
 *
 * <p>单子库 {@code school}：教师上传的讲义、教案、试卷等校本材料统一进这一库，
 * 供"备课素材生成"检索。文件放 {@code data/school-knowledge/school/}。
 *
 * <h3>为什么不分班级建子库</h3>
 * {@code chroma/doc} 框架的子库是<b>构造器里静态声明</b>的，不支持按班级动态增子库；
 * 而且真实学校里校本资源本来就是<b>校内共享</b>的（同一所学校各班的讲义通常互通）。
 * 所以这里按"一所学校一个库"设计，上传时在 {@code edu_resource.class_id} 里记"谁传的"作为来源。
 * 如果将来真需要按班隔离，做法是给 {@code ChromaClient} 加带 {@code where} 的查询重载
 * （同文件 {@code deleteByWhere} 已有现成的 where 拼法），在检索层按 metadata 过滤——已记在方案里。
 *
 * <h3>上传后必须强制重建</h3>
 * 索引器在 {@code state == DONE} 时会直接早退，<b>程序启动之后新增的文件永远扫不到</b>。
 * 所以上传流程里显式调用 {@code DocKnowledgeRegistry.reindex("school")}。
 */
@Component
public class SchoolResourceConfig extends DocKnowledgeConfig {

    public SchoolResourceConfig() {
        super(
            "school", "school_", "data/school-knowledge",
            List.of(
                new SubLibrary("school", "校本资源", List.of("校本", "教研"))
            ),
            "你是乡村教师助教，基于本校上传的教学资料协助备课。要求：\n"
                + "1. 优先使用【参考资料】的内容，读懂后组织成教师可直接使用的素材（导入语、重难点、例题、"
                + "板书结构、课堂提问、作业建议）；\n"
                + "2. 资料里有的要点标注【资料】并尽量给出出处（文件名），你自己补充的标注【补充】；\n"
                + "3. 不得编造数字、机构名、政策与教材名称；资料没有的内容如实说明；\n"
                + "4. 面向中小学课堂，语言准确、可操作；不要照抄资料原文，也不要罗列标题；\n"
                + "5. 篇幅 400~800 字，分小标题组织，便于直接粘贴进教案。",
            5, 1.05, 5,
            "⚠️提示：本素材由 AI 基于本校上传资料生成，供备课参考，请教师核对后再使用。",
            800, 80, 10,
            1.15, 10, true
        );
    }
}
