package com.demo.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 数据库增量迁移 — 启动时执行，安全地添加缺失的列
 * 解决 CREATE TABLE IF NOT EXISTS 不对已有表加新列、以及 ALTER ADD COLUMN IF NOT EXISTS
 * 在低版本 MySQL 不兼容的问题
 */
@Slf4j
@Component
public class DatabaseMigration {

    @Resource
    private DataSource dataSource;

    @PostConstruct
    public void migrate() {
        try (Connection conn = dataSource.getConnection()) {
            log.info("🔧 数据库增量迁移检查...");

            // chat_message.image（会话消息图片持久化：相对路径 /chat/images/xxx.png）
            addColumnIfMissing(conn, "chat_message", "image",
                    "VARCHAR(512) DEFAULT NULL COMMENT '消息关联图片URL(/chat/images/xxx.png)'",
                    "AFTER content");

            // chat_messages.violation
            addColumnIfMissing(conn, "chat_messages", "violation",
                    "VARCHAR(32) DEFAULT NULL COMMENT '违规标记: FAKE_EXECUTION/SKIPPED_TOOL/RETRY_SUCCESS/null'",
                    "AFTER round_num");

            // user_settings.report_enabled
            addColumnIfMissing(conn, "user_settings", "report_enabled",
                    "TINYINT(1) DEFAULT 0 COMMENT '日报推送开关'",
                    "AFTER mimic_sample");
            // user_settings.report_time
            addColumnIfMissing(conn, "user_settings", "report_time",
                    "VARCHAR(5) DEFAULT '08:00' COMMENT '日报推送时间 HH:mm'",
                    "AFTER report_enabled");
            // user_settings.report_city
            addColumnIfMissing(conn, "user_settings", "report_city",
                    "VARCHAR(32) DEFAULT NULL COMMENT '日报天气城市'",
                    "AFTER report_time");

            // english_words 生词本 + 艾宾浩斯复习字段
            addColumnIfMissing(conn, "english_words", "in_notebook",
                    "TINYINT DEFAULT 0 COMMENT '生词本：0=否 1=是'",
                    "AFTER source");
            addColumnIfMissing(conn, "english_words", "review_level",
                    "INT DEFAULT 0 COMMENT '艾宾浩斯复习等级(0-7)'",
                    "AFTER in_notebook");
            addColumnIfMissing(conn, "english_words", "next_review_at",
                    "BIGINT DEFAULT 0 COMMENT '下次复习时间(epoch毫秒)'",
                    "AFTER review_level");
            addColumnIfMissing(conn, "english_words", "last_reviewed_at",
                    "BIGINT DEFAULT 0 COMMENT '上次复习时间(epoch毫秒)'",
                    "AFTER next_review_at");

            // ==================== 教育域（2026-10 教师助教工作台）====================
            // 危机计数已按「学生+日期」去重，所以同一天只留一条——但这样会丢掉"最严重的那一条"。
            // 因此另存当日最高风险等级：一个学生上午说"活着没意思"(1)、下午说"我割腕了"(3)，
            // 计数仍为 1，等级取 3。教师看数字，不需要知道细节，但严重程度不能丢。
            addColumnIfMissing(conn, "edu_crisis_daily", "max_level",
                    "TINYINT DEFAULT 1 COMMENT '当日最高风险等级 1低/2中/3高'",
                    "AFTER cnt");

            // 教师错误反馈：补 提问标签 + 出错来源文档（message 列即教师备注，保持兼容不重建表）
            addColumnIfMissing(conn, "edu_feedback", "query_tag",
                    "VARCHAR(256) DEFAULT NULL COMMENT '提问分类标签（生命安全与健康教育/青春期性教育/通识）'",
                    "AFTER class_id");
            addColumnIfMissing(conn, "edu_feedback", "source_doc",
                    "VARCHAR(256) DEFAULT NULL COMMENT '出错的知识库来源文档名'",
                    "AFTER query_tag");

            // 老站即使关闭 schema.sql 初始化也能得到只读成长历史表；不改动任何原有记录。
            try (Statement statement = conn.createStatement()) {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS edu_growth_conversation (" +
                    "group_id VARCHAR(40) NOT NULL, grade INT NOT NULL, messages LONGTEXT NOT NULL, " +
                    "created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), " +
                    "PRIMARY KEY (group_id,grade)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 " +
                    "COMMENT='成长课堂公共问答历史'");
            }

            log.info("✅ 数据库迁移检查完成");
        } catch (Exception e) {
            log.error("❌ 数据库迁移执行失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 安全添加列：先查询 information_schema 确认列不存在再 ALTER
     */
    private void addColumnIfMissing(Connection conn, String table, String column,
                                     String definition, String afterClause) {
        try {
            String sql = String.format(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '%s' AND COLUMN_NAME = '%s'",
                    table, column);

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                rs.next();
                if (rs.getInt(1) > 0) {
                    log.info("   ✅ {}.{} 已存在，跳过", table, column);
                    return;
                }
            }

            // 列不存在，执行 ALTER TABLE
            String alterSql = String.format("ALTER TABLE %s ADD COLUMN %s %s %s",
                    table, column, definition, afterClause);
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(alterSql);
                log.info("   🆗 {}.{} 已添加", table, column);
            }
        } catch (Exception e) {
            log.warn("   ⚠ {}.{} 添加失败: {}", table, column, e.getMessage());
        }
    }
}
