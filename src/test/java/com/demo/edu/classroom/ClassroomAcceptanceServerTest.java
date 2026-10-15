package com.demo.edu.classroom;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

/** 验证隔离入口的 URL/DDL 边界；没有 Spring 上下文，也不会调用模型或语音供应商。 */
class ClassroomAcceptanceServerTest {
    private static final String DATABASE="educationagent_acceptance_20261015_190001";
    private static final String H2="jdbc:h2:mem:"+DATABASE+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    @Test void acceptsOnlyDedicatedMemoryAndLocalMysqlDatabases() {
        assertEquals(DATABASE,ClassroomAcceptanceServer.acceptanceDatabase(H2));
        assertEquals(DATABASE,ClassroomAcceptanceServer.acceptanceDatabase("jdbc:mysql://127.0.0.1:3306/"+DATABASE+"?useSSL=false&createDatabaseIfNotExist=true"));
        assertEquals(DATABASE,ClassroomAcceptanceServer.acceptanceDatabase("jdbc:mysql://localhost/"+DATABASE));
    }

    @ParameterizedTest
    @ValueSource(strings={
            "jdbc:h2:mem:production;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "jdbc:h2:file:educationagent_acceptance_20261015_190001;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "jdbc:h2:tcp://localhost/mem:educationagent_acceptance_20261015_190001;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "jdbc:h2:mem:educationagent_acceptance_20261015_190001",
            "jdbc:h2:mem:educationagent_acceptance_20261015_190001;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=RUNSCRIPT FROM 'evil.sql'",
            "jdbc:h2:mem:educationagent_acceptance_20261015_190001;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;AUTO_SERVER=TRUE",
            "jdbc:h2:mem:educationagent_acceptance_20261015_190001;DATABASE_TO_LOWER=TRUE;MODE=MySQL;DB_CLOSE_DELAY=-1",
            "jdbc:mysql://127.0.0.1:3306/newbot",
            "jdbc:mysql://remote.example:3306/educationagent_acceptance_20261015_190001",
            "jdbc:mysql://localhost/educationagent_acceptance_20261015_190001?sessionVariables=sql_mode=ANSI",
            "jdbc:mysql://localhost/educationagent_acceptance_20261015_190001?allowMultiQueries=true",
            "jdbc:mysql://user:password@localhost/educationagent_acceptance_20261015_190001"
    })
    void rejectsNonIsolatedOrExtendedConnections(String url) {
        assertThrows(IllegalArgumentException.class,()->ClassroomAcceptanceServer.acceptanceDatabase(url));
    }

    @Test void selectedSchemaAndMysqlMapperSyntaxWorkInMemory() throws Exception {
        String original;
        try(var input=new ClassPathResource("schema.sql").getInputStream()) { original=new String(input.readAllBytes(),StandardCharsets.UTF_8); }
        String sql=ClassroomAcceptanceServer.acceptanceSchemaSql(original,true);
        assertFalse(sql.contains("ENGINE=")); assertFalse(sql.contains("INSERT IGNORE"));
        assertEquals(9,sql.split("CREATE TABLE IF NOT EXISTS").length-1);
        JdbcDataSource source=new JdbcDataSource(); source.setURL(H2); source.setUser("sa"); source.setPassword("");
        ResourceDatabasePopulator populator=new ResourceDatabasePopulator(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
        populator.execute(source);
        try(var connection=source.getConnection();var statement=connection.createStatement()) {
            assertEquals(DATABASE,connection.getCatalog());
            try(var tables=statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public'")) {
                assertTrue(tables.next()); assertEquals(9,tables.getInt(1));
            }
            statement.executeUpdate("INSERT INTO edu_learning_course(id,document,updated_at) VALUES('c1','{}',CURRENT_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE document=VALUES(document),updated_at=CURRENT_TIMESTAMP(3)");
            statement.executeUpdate("INSERT INTO edu_learning_course(id,document,updated_at) VALUES('c1','{\"title\":\"课\"}',CURRENT_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE document=VALUES(document),updated_at=CURRENT_TIMESTAMP(3)");
            try(var rows=statement.executeQuery("SELECT document FROM edu_learning_course WHERE id='c1'")) {
                assertTrue(rows.next()); assertEquals("{\"title\":\"课\"}",rows.getString(1));
            }
            statement.executeUpdate("INSERT INTO quiz_paper(user_id,title,source_file_name,file_type,question_count,deleted) VALUES('classroom','验收题','test','classroom',1,0)");
            try(var identity=statement.executeQuery("SELECT LAST_INSERT_ID()")) { assertTrue(identity.next()); assertTrue(identity.getLong(1)>0); }
        }
    }
}
