package com.demo;

import org.junit.jupiter.api.Test;
import com.demo.testsupport.IsolatedSpringBootTest;
import com.demo.testsupport.IsolatedRedisStubContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockingDetails;

/**
 * ⚠️ 记忆索引必须指向测试目录，不能落在运行时的 data/memory-index。
 *
 * <p>{@code @SpringBootTest} 会拉起完整的 Spring 上下文，其中 {@code LuceneMemoryStore}
 * 读的是配置里的索引路径。测试 JVM 一碰那个目录的 write.lock，正在运行的开发后端
 * 就会判定"索引被外部力量改动"、自己关掉 IndexWriter，之后写入全部静默失败。
 */
@IsolatedSpringBootTest
class DemoApplicationTests extends IsolatedRedisStubContext {

    @Autowired private ApplicationContext context;
    @Autowired private Environment environment;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void contextLoads() {
        assertEquals(List.of("test-isolated"), List.of(environment.getActiveProfiles()));
        assertEquals(environment.getProperty("test.isolation.database"), jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertTrue(mockingDetails(context.getBean("redisTemplate")).isMock());
        assertTrue(mockingDetails(context.getBean("deepseekChatModel")).isMock());
        assertTrue(mockingDetails(context.getBean("mailSender")).isMock());
        assertTrue(environment.getProperty("memory.store.index-path", "").startsWith(environment.getRequiredProperty("test.isolation.data-root")));
    }

}
