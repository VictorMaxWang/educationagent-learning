package com.demo.chroma.doc;

import com.demo.study.psychology.PsychologyKnowledgeConfig;
import com.demo.study.tongshi.TongshiKnowledgeConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class DocKnowledgeConfigTest {

    private final DocKnowledgeConfig config = new TongshiKnowledgeConfig();

    @Test
    void resolveByEnglishPrefix() {
        assertEquals("politics", config.resolveSubLibraryId("politics_毛概.txt"));
        assertEquals("math", config.resolveSubLibraryId("math_高数.pdf"));
    }

    @Test
    void resolveByChineseAlias() {
        assertEquals("politics", config.resolveSubLibraryId("政治_毛概.txt"));
        assertEquals("english", config.resolveSubLibraryId("英语_真题.docx"));
    }

    @Test
    void resolveUnmatchedReturnsNull() {
        assertNull(config.resolveSubLibraryId("物理_量子力学.txt"));
    }

    @Test
    void collectionName() {
        assertEquals("tongshi_politics", config.collectionName("politics"));
    }

    @Test
    void psychologySingleLibrary() {
        DocKnowledgeConfig psych = new PsychologyKnowledgeConfig();
        assertEquals("general", psych.resolveSubLibraryId("任意心理文档.pdf"));
        assertEquals("psychology_general", psych.collectionName("general"));
    }
}
