package com.demo.edu.classroom.guide;

import com.demo.edu.mapper.GrowthConversationMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实 schema 片段与 MyBatis SQL，验证联合主键和跨年级读取，避免只测替身。 */
class GrowthConversationMapperTest {
    @Test void schemaIsIdempotentAndMapperKeepsGradeAndGroupSeparate() throws Exception {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:growth_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        String schema = Files.readString(Path.of("src/main/resources/schema.sql"));
        int start = schema.indexOf("CREATE TABLE IF NOT EXISTS edu_growth_conversation");
        assertTrue(start >= 0);
        String ddl = schema.substring(start, schema.indexOf(';', start) + 1);
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute(ddl); statement.execute(ddl);
            statement.executeUpdate("INSERT INTO edu_growth_conversation(group_id,grade,messages) VALUES ('practice-creation',5,'grade-five'),('practice-creation',6,'grade-six'),('language-general',5,'language-five')");
            assertThrows(SQLException.class, () -> statement.executeUpdate("INSERT INTO edu_growth_conversation(group_id,grade,messages) VALUES ('practice-creation',5,'overwrite')"));
        }
        var configuration = new Configuration(new Environment("growth-test", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(GrowthConversationMapper.class);
        try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession()) {
            var mapper = session.getMapper(GrowthConversationMapper.class);
            assertEquals("grade-five", mapper.find("practice-creation", 5));
            assertEquals("grade-six", mapper.find("practice-creation", 6));
            assertEquals("language-five", mapper.find("language-general", 5));
            assertNull(mapper.find("mind-communication", 5));
        }
    }
}
