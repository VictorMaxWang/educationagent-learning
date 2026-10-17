package com.demo.testsupport;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 配置保护本身不连接数据库；缺失或误指本机常用服务必须在 Spring 创建 Bean 前失败。 */
class IsolatedTestEnvironmentTest {
    private Map<String, String> mysql() {
        return new HashMap<>(Map.of(
                "TEST_MYSQL_URL", "jdbc:mysql://127.0.0.1:52818/educationagent_test?useUnicode=true",
                "TEST_MYSQL_USER", "educationagent_test",
                "TEST_MYSQL_PASSWORD", "test-only-placeholder"));
    }

    @Test
    void acceptsExplicitIsolatedMysqlWithoutChangingItsCredentials() {
        Map<String, String> input = mysql();
        Map<String, Object> result = IsolatedTestEnvironment.mysqlSettings(input);
        assertEquals(input.get("TEST_MYSQL_URL"), result.get("spring.datasource.url"));
        assertEquals(input.get("TEST_MYSQL_USER"), result.get("spring.datasource.username"));
        assertEquals(input.get("TEST_MYSQL_PASSWORD"), result.get("spring.datasource.password"));
        assertEquals("educationagent_test", result.get("test.isolation.database"));
    }

    @Test
    void missingExplicitConfigurationCannotFallBackToHostDefaults() {
        for (String name : mysql().keySet()) {
            Map<String, String> input = mysql();
            input.remove(name);
            assertTrue(assertThrows(IllegalStateException.class,
                    () -> IsolatedTestEnvironment.mysqlSettings(input)).getMessage().contains(name));
        }
    }

    @Test
    void rejectsDefaultMysqlPortRootRemoteHostAndNonTestSchema() {
        for (String url : new String[]{
                "jdbc:mysql://127.0.0.1:3306/educationagent_test",
                "jdbc:mysql://127.0.0.1/educationagent_test",
                "jdbc:mysql://database.example:52818/educationagent_test",
                "jdbc:mysql://127.0.0.1:52818/weChatBot_db",
                "jdbc:mysql://127.0.0.1:52818/educationagent_test/other",
                "jdbc:mysql://user:password@127.0.0.1:52818/educationagent_test"}) {
            Map<String, String> input = mysql(); input.put("TEST_MYSQL_URL", url);
            assertThrows(IllegalStateException.class, () -> IsolatedTestEnvironment.mysqlSettings(input));
        }
        Map<String, String> input = mysql(); input.put("TEST_MYSQL_USER", "root");
        assertThrows(IllegalStateException.class, () -> IsolatedTestEnvironment.mysqlSettings(input));
    }

    @Test
    void realRedisRequiresExplicitIndependentLoopbackInstance() {
        assertThrows(IllegalStateException.class, () -> IsolatedTestEnvironment.redisSettings(Map.of()));
        for (Map<String, String> input : List.of(
                Map.of("TEST_REDIS_HOST", "127.0.0.1", "TEST_REDIS_PORT", "6379"),
                Map.of("TEST_REDIS_HOST", "remote.example", "TEST_REDIS_PORT", "56379"),
                Map.of("TEST_REDIS_HOST", "127.0.0.1", "TEST_REDIS_PORT", "invalid"))) {
            assertThrows(IllegalStateException.class, () -> IsolatedTestEnvironment.redisSettings(input));
        }
        Map<String, Object> values = IsolatedTestEnvironment.redisSettings(
                Map.of("TEST_REDIS_HOST", "127.0.0.1", "TEST_REDIS_PORT", "56379"));
        assertEquals(56379, values.get("spring.data.redis.port"));
        assertEquals("", values.get("spring.data.redis.password"));
    }
}
