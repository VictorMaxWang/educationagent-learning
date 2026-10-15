package com.demo.chroma;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TravelKnowledgeChunkerTest {

    @Test
    void chunkWithCustomSize() {
        TravelKnowledgeChunker chunker = new TravelKnowledgeChunker();
        String text = "x".repeat(1200); // 无 \n\n，走递归分割
        List<TravelKnowledgeChunker.Chunk> chunks = chunker.chunk(text, "test", 500, 50);
        assertTrue(chunks.size() > 1);
        for (TravelKnowledgeChunker.Chunk c : chunks) {
            assertTrue(c.getText().length() <= 500, "单块不应超过 500 字符");
        }
    }

    @Test
    void defaultChunkStillWorks() {
        TravelKnowledgeChunker chunker = new TravelKnowledgeChunker();
        List<TravelKnowledgeChunker.Chunk> chunks = chunker.chunk("a\n\nb\n\nc", "test");
        assertFalse(chunks.isEmpty());
    }
}
