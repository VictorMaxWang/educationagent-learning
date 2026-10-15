package com.demo.chroma;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档分块引擎
 * <p>
 * 将原始文档文本按策略切分为适合向量化的 chunks。
 * 策略（按优先级）：
 * 1. 段落分割 — 按连续换行符分割，适合有自然段落的文档
 * 2. 递归字符分割 — 以最大长度硬切，带重叠，适合无格式长文本
 */
@Slf4j
@Component
public class TravelKnowledgeChunker {

    /** 最大块字符数 */
    private static final int CHUNK_SIZE = 500;

    /** 块间重叠字符数 */
    private static final int CHUNK_OVERLAP = 50;

    /**
     * 自动选择分块策略（段落优先，fallback 递归分割）
     */
    public List<Chunk> chunk(String text, String source) {
        return chunk(text, source, CHUNK_SIZE, CHUNK_OVERLAP);
    }

    public List<Chunk> chunk(String text, String source, int chunkSize, int chunkOverlap) {
        if (text == null || text.isBlank()) return Collections.emptyList();

        List<Chunk> chunks;
        if (text.contains("\n\n") || text.contains("\r\n\r\n")) {
            chunks = splitByParagraph(text, source, chunkSize, chunkOverlap);
        } else {
            chunks = recursiveSplit(text, source, 0, chunkSize, chunkOverlap);
        }

        int total = chunks.size();
        for (Chunk c : chunks) {
            c.setTotalChunks(total);
        }

        log.debug("📄 分块完成: {} chunks (来源: {})", chunks.size(), source);
        return chunks;
    }

    // ======================== 分割策略 ========================

    private List<Chunk> splitByParagraph(String text, String source, int chunkSize, int chunkOverlap) {
        String[] paragraphs = text.split("\\n\\s*\\n");
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int chunkIndex = 0;

        for (String para : paragraphs) {
            para = para.trim();
            if (para.isBlank()) continue;

            if (current.length() + para.length() > chunkSize && current.length() > 0) {
                chunks.add(new Chunk(current.toString().trim(), source, chunkIndex++));
                current = new StringBuilder();
            }

            if (para.length() > chunkSize) {
                if (current.length() > 0) {
                    chunks.add(new Chunk(current.toString().trim(), source, chunkIndex++));
                    current = new StringBuilder();
                }
                chunks.addAll(recursiveSplit(para, source, chunkIndex, chunkSize, chunkOverlap));
                chunkIndex = chunks.size();
                continue;
            }

            current.append(para).append("\n\n");
        }

        if (current.length() > 0) {
            chunks.add(new Chunk(current.toString().trim(), source, chunkIndex));
        }

        return chunks;
    }

    private List<Chunk> recursiveSplit(String text, String source, int startIndex, int chunkSize, int chunkOverlap) {
        List<Chunk> chunks = new ArrayList<>();
        int idx = startIndex;

        int pos = 0;
        while (pos < text.length()) {
            int end = Math.min(pos + chunkSize, text.length());
            String chunk = text.substring(pos, end).trim();
            if (!chunk.isBlank()) {
                chunks.add(new Chunk(chunk, source, idx++));
            }
            pos += chunkSize - chunkOverlap;
        }

        return chunks;
    }

    // ======================== 分块实体 ========================

    @Data
    public static class Chunk {
        private final String text;
        private final String source;
        private int chunkIndex;
        private int totalChunks;

        public Chunk(String text, String source, int chunkIndex) {
            this.text = text;
            this.source = source;
            this.chunkIndex = chunkIndex;
            this.totalChunks = 0;
        }

        public Map<String, Object> toMetadata() {
            Map<String, Object> meta = new HashMap<>();
            meta.put("source", source);
            meta.put("chunkIndex", chunkIndex);
            meta.put("totalChunks", totalChunks);
            return meta;
        }
    }
}
