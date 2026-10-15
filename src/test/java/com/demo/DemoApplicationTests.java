package com.demo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * ⚠️ 记忆索引必须指向测试目录，不能落在运行时的 data/memory-index。
 *
 * <p>{@code @SpringBootTest} 会拉起完整的 Spring 上下文，其中 {@code LuceneMemoryStore}
 * 读的是配置里的索引路径。测试 JVM 一碰那个目录的 write.lock，正在运行的开发后端
 * 就会判定"索引被外部力量改动"、自己关掉 IndexWriter，之后写入全部静默失败。
 */
@SpringBootTest(properties = {
        "memory.store.index-path=target/test-memory-index",
        "memory.chroma.enabled=false",
        "bot.auto-start-count=0"
})
class DemoApplicationTests {

	@Test
	void contextLoads() {
	}

}
