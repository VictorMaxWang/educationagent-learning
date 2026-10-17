-- 对话消息表
CREATE TABLE IF NOT EXISTS chat_messages (
    id          BIGINT          AUTO_INCREMENT PRIMARY KEY,
    user_id     VARCHAR(128)    NOT NULL COMMENT '微信用户ID',
    role        VARCHAR(16)     NOT NULL COMMENT 'user 或 assistant',
    content     TEXT            NOT NULL COMMENT '消息内容',
    round_num   INT             NOT NULL DEFAULT 0 COMMENT '对话轮次序号',
    violation   VARCHAR(32)     DEFAULT NULL COMMENT '违规标记: FAKE_EXECUTION/SKIPPED_TOOL/RETRY_SUCCESS/null',
    created_at  TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_user_round (user_id, round_num),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 用户设置表（合并语音模式/音色/对话模式/日报设置）
CREATE TABLE IF NOT EXISTS user_settings (
    id           BIGINT         AUTO_INCREMENT PRIMARY KEY,
    user_id      VARCHAR(128)   NOT NULL UNIQUE COMMENT '微信用户ID',
    voice_mode   VARCHAR(16)    DEFAULT 'off' COMMENT '语音模式: off/global',
    voice_type   VARCHAR(16)    DEFAULT '101007' COMMENT 'TTS音色ID',
    chat_mode    VARCHAR(16)    DEFAULT 'normal' COMMENT '对话模式: normal/expert/concise/mimic',
    mimic_sample TEXT           COMMENT '模仿模式的风格样本',
    report_enabled TINYINT(1)   DEFAULT 0 COMMENT '日报推送开关',
    report_time  VARCHAR(5)     DEFAULT '08:00' COMMENT '日报推送时间 HH:mm',
    report_city  VARCHAR(32)    DEFAULT NULL COMMENT '日报天气城市',
    created_at   TIMESTAMP      DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE INDEX uk_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 定时提醒表
CREATE TABLE IF NOT EXISTS reminders (
    id           BIGINT         AUTO_INCREMENT PRIMARY KEY,
    user_id      VARCHAR(128)   NOT NULL COMMENT '微信用户ID',
    message      VARCHAR(500)   NOT NULL COMMENT '提醒内容',
    trigger_at   BIGINT         NOT NULL COMMENT '触发时间（epoch 毫秒）',
    executed     TINYINT        DEFAULT 0 COMMENT '0=待触发, 1=已触发',
    created_at   TIMESTAMP      DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_pending (trigger_at, executed),
    INDEX idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 打车订单表
CREATE TABLE IF NOT EXISTS taxi_orders (
    id              BIGINT          AUTO_INCREMENT PRIMARY KEY,
    user_id         VARCHAR(128)    NOT NULL COMMENT '微信用户ID',
    origin          VARCHAR(256)    COMMENT '起点地址',
    destination     VARCHAR(256)    COMMENT '终点地址',
    car_type        VARCHAR(32)     COMMENT '车型：快车/专车/豪华车/拼车',
    estimate_price  VARCHAR(32)     COMMENT '预估价格',
    order_no        VARCHAR(64)     COMMENT '订单号',
    status          VARCHAR(16)     DEFAULT 'created' COMMENT '订单状态: created/completed/cancelled',
    raw_response    TEXT            COMMENT 'MCP 原始返回 JSON',
    created_at      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_taxi_user (user_id),
    INDEX idx_taxi_order_no (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- MCP 调用日志表（每一次 MCP 工具调用的请求参数 & 返回结果）
CREATE TABLE IF NOT EXISTS mcp_call_logs (
    id              BIGINT          AUTO_INCREMENT PRIMARY KEY,
    source_name     VARCHAR(32)     NOT NULL COMMENT 'MCP来源: didi/juhe-train',
    tool_name       VARCHAR(128)    NOT NULL COMMENT '工具名',
    request_params  TEXT            COMMENT '请求参数 JSON',
    response_body   TEXT            COMMENT '返回结果（截断到4000字符）',
    status          VARCHAR(16)     DEFAULT 'success' COMMENT 'success/error',
    duration_ms     BIGINT          DEFAULT 0 COMMENT '调用耗时(毫秒)',
    created_at      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_mcp_log_source (source_name),
    INDEX idx_mcp_log_tool (tool_name),
    INDEX idx_mcp_log_time (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- =============================================================
-- 刷题系统
-- =============================================================

-- 试卷（一次上传文件解析出的题目集）
CREATE TABLE IF NOT EXISTS quiz_paper (
    id               BIGINT          AUTO_INCREMENT PRIMARY KEY,
    user_id          VARCHAR(128)    NOT NULL COMMENT '用户ID: desktop_xxx 或微信ID',
    title            VARCHAR(255)    NOT NULL COMMENT '试卷标题（默认取文件名）',
    source_file_name VARCHAR(255)    COMMENT '原始文件名',
    file_type        VARCHAR(32)     COMMENT 'docx/pdf/txt...',
    question_count   INT             DEFAULT 0 COMMENT '题目总数',
    deleted          TINYINT         DEFAULT 0 COMMENT '0=正常 1=已删除(软删保留错题)',
    created_at       TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_quiz_paper_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 题目
CREATE TABLE IF NOT EXISTS quiz_question (
    id              BIGINT          AUTO_INCREMENT PRIMARY KEY,
    paper_id        BIGINT          NOT NULL COMMENT '所属试卷ID',
    question_no     INT             NOT NULL COMMENT '题号（1-based）',
    q_type          VARCHAR(16)     NOT NULL COMMENT 'single/multi/judge/fill/short',
    stem            TEXT            NOT NULL COMMENT '题干',
    options         TEXT            COMMENT '选项 JSON 数组，选择/判断有，填空/简答为空',
    answer          TEXT            COMMENT '标准答案（可空=文件未提供）',
    explanation     TEXT            COMMENT '解析（可空）',
    knowledge_point VARCHAR(128)    COMMENT '知识点标签（LLM 打标）',
    has_answer      TINYINT         DEFAULT 0 COMMENT '文件是否自带答案',
    passage_no      INT             COMMENT '所属材料组号（听力/阅读），null=独立题',
    created_at      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_quiz_question_paper (paper_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 材料组（听力/阅读）
CREATE TABLE IF NOT EXISTS quiz_passage (
    id            BIGINT          AUTO_INCREMENT PRIMARY KEY,
    paper_id      BIGINT          NOT NULL COMMENT '所属试卷ID',
    passage_no    INT             NOT NULL COMMENT '组号（1-based）',
    passage_type  VARCHAR(16)     NOT NULL COMMENT 'listening/reading',
    title         VARCHAR(255)    COMMENT '材料标题，如"听力材料一"',
    content       TEXT            NOT NULL COMMENT '材料正文（听力稿/阅读文章）',
    created_at    TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_quiz_passage_paper (paper_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 做题记录（错题本数据源）
CREATE TABLE IF NOT EXISTS quiz_answer (
    id           BIGINT          AUTO_INCREMENT PRIMARY KEY,
    user_id      VARCHAR(128)    NOT NULL COMMENT '用户ID',
    paper_id     BIGINT          NOT NULL COMMENT '试卷ID',
    question_id  BIGINT          NOT NULL COMMENT '题目ID',
    user_answer  TEXT            COMMENT '用户作答',
    is_correct   TINYINT         DEFAULT 0 COMMENT '0=错误/未判, 1=正确',
    answered_at  TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_quiz_answer_user_wrong (user_id, is_correct),
    INDEX idx_quiz_answer_question (question_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 知识点收藏
CREATE TABLE IF NOT EXISTS quiz_knowledge_fav (
    id               BIGINT          AUTO_INCREMENT PRIMARY KEY,
    user_id          VARCHAR(128)    NOT NULL COMMENT '用户ID',
    knowledge_point  VARCHAR(128)    NOT NULL COMMENT '知识点',
    created_at       TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,

    UNIQUE INDEX uk_user_kp (user_id, knowledge_point)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 题目收藏
CREATE TABLE IF NOT EXISTS quiz_question_fav (
    id              BIGINT          AUTO_INCREMENT PRIMARY KEY,
    user_id         VARCHAR(128)    NOT NULL COMMENT '用户ID',
    question_id     BIGINT          NOT NULL COMMENT '题目ID',
    created_at      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,

    UNIQUE INDEX uk_user_question (user_id, question_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 英语词库（墨墨背单词联动）
CREATE TABLE IF NOT EXISTS english_words (
    id              BIGINT          AUTO_INCREMENT PRIMARY KEY,
    word            VARCHAR(128)    NOT NULL COMMENT '单词',
    phonetic        VARCHAR(128)    COMMENT '音标',
    meaning         TEXT            COMMENT '释义',
    part_of_speech  VARCHAR(32)     COMMENT '词性',
    example         TEXT            COMMENT '例句',
    status          VARCHAR(16)     DEFAULT 'new' COMMENT 'new/mastered/weak/forgotten',
    book_type       VARCHAR(16)     COMMENT 'cet4/cet6/kaoyan/ielts',
    source          VARCHAR(16)     DEFAULT 'local' COMMENT 'ai/youdao/local',
    in_notebook     TINYINT         DEFAULT 0 COMMENT '生词本：0=否 1=是',
    review_level    INT             DEFAULT 0 COMMENT '艾宾浩斯复习等级(0-7)',
    next_review_at  BIGINT          DEFAULT 0 COMMENT '下次复习时间(epoch毫秒)',
    last_reviewed_at BIGINT         DEFAULT 0 COMMENT '上次复习时间(epoch毫秒)',
    created_at      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,

    UNIQUE INDEX uk_word (word),
    INDEX idx_word_due (next_review_at, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- =============================================================
-- 历史对话侧边栏（桌面端会话管理）
-- chat_session: 会话元数据  /  chat_message: 会话消息
-- 与 chat_messages（微信轮次记忆）并行，互不冲突
-- 时间统一 epoch 毫秒 BIGINT
-- =============================================================

-- 会话元数据表
CREATE TABLE IF NOT EXISTS chat_session (
    session_id  VARCHAR(64)  PRIMARY KEY COMMENT '会话ID (UUID)',
    user_id     VARCHAR(128) NOT NULL COMMENT '所属桌面端用户ID (user_xxx)',
    title       VARCHAR(255) NOT NULL DEFAULT '新对话' COMMENT '会话标题',
    create_time BIGINT       NOT NULL COMMENT '创建时间(epoch毫秒)',
    update_time BIGINT       NOT NULL COMMENT '更新时间(epoch毫秒)',

    INDEX idx_session_user (user_id, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 会话消息表
CREATE TABLE IF NOT EXISTS chat_message (
    msg_id      VARCHAR(64)  PRIMARY KEY COMMENT '消息ID (UUID)',
    session_id  VARCHAR(64)  NOT NULL COMMENT '所属会话ID',
    role        VARCHAR(16)  NOT NULL COMMENT 'user/assistant',
    content     TEXT         NOT NULL COMMENT '消息内容',
    image       VARCHAR(512) DEFAULT NULL COMMENT '消息关联图片URL(/chat/images/xxx.png)',
    create_time BIGINT       NOT NULL COMMENT '创建时间(epoch毫秒)',

    INDEX idx_msg_session (session_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- =============================================================
-- 宠物模块数据库 DDL（4 张表）
-- =============================================================

-- 宠物档案表
CREATE TABLE IF NOT EXISTS pets (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id       VARCHAR(128) NOT NULL COMMENT '用户ID（桌面端=用户名/用户ID）',
    pet_name      VARCHAR(100) NOT NULL COMMENT '宠物名字',
    species       VARCHAR(50)  COMMENT '种类：狗/猫/兔/鸟/其他',
    breed         VARCHAR(100) COMMENT '品种：金毛/英短/布偶/...',
    gender        VARCHAR(10)  COMMENT '性别：公/母/未确认',
    birth_date    DATE         COMMENT '出生日期',
    weight_kg     DECIMAL(5,2) COMMENT '体重(kg)',
    photo_url     VARCHAR(500) COMMENT '宠物照片本地路径',
    illnesses     TEXT         COMMENT '生过的病 JSON：[{"date":"...","name":"皮炎","note":"..."}]',
    features      TEXT         COMMENT '其他特征 JSON：{"color":"棕黄","personality":"活泼"}',
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_pet (user_id, pet_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='宠物档案';

-- 宠物智能诊断记录表
CREATE TABLE IF NOT EXISTS pet_diagnoses (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id       VARCHAR(128) NOT NULL,
    pet_name      VARCHAR(100) NOT NULL COMMENT '关联宠物名字',
    image_url     VARCHAR(500) COMMENT '上传的图片本地路径',
    concern       VARCHAR(500) COMMENT '用户主诉（如：皮肤红肿怎么办）',
    ai_raw_text   TEXT         COMMENT 'AI生成的完整回复原文',
    diagnosis     TEXT         COMMENT '解析后诊断结论',
    suggestions   TEXT         COMMENT '处理建议（换行分隔）',
    keywords      VARCHAR(500) COMMENT '关键词（逗号分隔）',
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_diag_user_pet (user_id, pet_name),
    INDEX idx_diag_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='宠物智能诊断记录';

-- 宠物定时推送任务表
CREATE TABLE IF NOT EXISTS pet_schedules (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id       VARCHAR(128) NOT NULL,
    pet_name      VARCHAR(100) NOT NULL COMMENT '关联宠物名字',
    time_expr     VARCHAR(100) NOT NULL COMMENT '原始时间表达式（如：每天晚上8点）',
    cron_expr     VARCHAR(50)  NOT NULL COMMENT 'cron表达式（如：0 20 * * *）',
    action        VARCHAR(500) NOT NULL COMMENT '推送内容（如：带旺财去散步）',
    enabled       TINYINT      DEFAULT 1,
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_psched_enabled (enabled),
    INDEX idx_psched_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='宠物定时推送任务';

-- 宠物健康记录通用表（体重/疫苗/驱虫/用药/成长/花费/症状 合并，record_type 区分）
CREATE TABLE IF NOT EXISTS pet_records (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id       VARCHAR(128) NOT NULL,
    pet_name      VARCHAR(100) NOT NULL,
    record_type   VARCHAR(16) NOT NULL COMMENT 'weight/vaccine/deworm/medication/growth/expense/symptom',
    record_date   DATE NOT NULL COMMENT '记录日期（体重=记录日 疫苗=接种日 驱虫=驱虫日 用药=开始日 成长=事件日 花费=花费日 症状=症状日）',
    name          VARCHAR(200) COMMENT '疫苗名/驱虫类型(体内体外)/药名/事件类型/费用类别/症状',
    amount        DECIMAL(12,2) COMMENT '体重(kg) / 每天次数 / 金额',
    text1         VARCHAR(500) COMMENT '备注 / 药品名 / 用量 / 成长内容',
    text2         VARCHAR(500) COMMENT '症状严重度(轻/中/重)',
    next_date     DATE COMMENT '疫苗/驱虫下次日期',
    end_date      DATE COMMENT '用药结束日期',
    active        TINYINT DEFAULT 1 COMMENT '用药是否进行中(1/0)',
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_rec_user_pet (user_id, pet_name),
    INDEX idx_rec_type (record_type),
    INDEX idx_rec_date (record_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='宠物健康记录(通用表)';

-- =============================================================
-- 增量迁移（由 DatabaseMigration.java 在启动时执行）
-- 支持所有 MySQL 8.x 版本，不依赖 IF NOT EXISTS 语法
-- =============================================================

-- ============================================================
-- 生活企划·小红书（xhs_hot_schedule / xhs_black_comments）
-- CREATE TABLE IF NOT EXISTS 幂等
-- ============================================================
CREATE TABLE IF NOT EXISTS xhs_hot_schedule (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    enabled     TINYINT DEFAULT 0 COMMENT '定时发布开关 1开/0关',
    days        VARCHAR(32) DEFAULT '3' COMMENT '星期几，逗号分隔，1=周一..7=周日',
    time        VARCHAR(5) DEFAULT '14:00' COMMENT '发布时刻 HH:mm',
    max_videos  INT DEFAULT 5 COMMENT '最多取几个热门视频',
    tags        VARCHAR(128) DEFAULT '#热门视频 #本周热门' COMMENT '发布话题标签（空格分隔）',
    updated_at  TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='小红书热门视频定时发布配置';

CREATE TABLE IF NOT EXISTS xhs_black_comments (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    note_id     VARCHAR(64) NOT NULL COMMENT '笔记ID',
    comment_id  VARCHAR(128) NOT NULL COMMENT '评论ID（唯一去重）',
    content     TEXT COMMENT '评论内容',
    reported_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_comment (comment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='小红书黑评去重';

-- ============================================================
-- 旅行·周边餐馆 相关表
-- ============================================================

-- 1) 收藏餐馆
CREATE TABLE IF NOT EXISTS travel_restaurant_favorite (
    id            BIGINT NOT NULL AUTO_INCREMENT,
    travel_plan_id VARCHAR(128) NOT NULL COMMENT '行程方案ID',
    poi_id        VARCHAR(64) NOT NULL COMMENT '高德POI ID',
    name          VARCHAR(200) COMMENT '餐馆名称',
    lon           VARCHAR(32) COMMENT '经度',
    lat           VARCHAR(32) COMMENT '纬度',
    avg_cost      INT COMMENT '人均(元)',
    rating        DECIMAL(3,1) COMMENT '评分',
    create_time   TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_plan_poi (travel_plan_id, poi_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='旅行收藏餐馆';

-- 2) 每日行程关联餐馆（午餐/晚餐）
CREATE TABLE IF NOT EXISTS travel_schedule_rest (
    id            BIGINT NOT NULL AUTO_INCREMENT,
    schedule_id   VARCHAR(128) NOT NULL COMMENT '每日行程ID',
    poi_id        VARCHAR(64) NOT NULL COMMENT '高德POI ID',
    name          VARCHAR(200) COMMENT '餐馆名称',
    time_period   VARCHAR(16) COMMENT 'lunch/dinner',
    remark        VARCHAR(500) COMMENT '备注',
    create_time   TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_schedule (schedule_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='行程关联餐馆';

-- ============================================================
-- RAG 自动化评估 相关表
-- ============================================================

-- 1) 评估黄金集（LLM 从知识库 chunk 反向生成，持久化复用以保证跨轮次可比）
CREATE TABLE IF NOT EXISTS rag_eval_case (
    id            BIGINT NOT NULL AUTO_INCREMENT,
    collection    VARCHAR(128) NOT NULL COMMENT 'Chroma 集合名，如 travel_knowledge',
    question      VARCHAR(1000) NOT NULL COMMENT '测试问题',
    ground_truth  TEXT COMMENT '标准答案',
    source_chunk  TEXT COMMENT '生成该用例的原始 chunk 文本',
    chunk_hash    VARCHAR(64) NOT NULL COMMENT '源 chunk 的 SHA-256，用于去重',
    create_time   TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_col_chunk (collection, chunk_hash),
    KEY idx_collection (collection)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG评估黄金集';

-- 2) 评估结果（一个用例一行；run 级聚合由 AVG() GROUP BY run_id 现算）
CREATE TABLE IF NOT EXISTS rag_eval_report (
    id                  BIGINT NOT NULL AUTO_INCREMENT,
    run_id              VARCHAR(64) NOT NULL COMMENT '批次ID，同一次评估的用例共享',
    collection          VARCHAR(128) NOT NULL COMMENT 'Chroma 集合名',
    top_k               INT COMMENT '该行使用的检索条数，重跑时复用同一参数以保证可比',
    question            VARCHAR(1000) COMMENT '测试问题',
    ground_truth        TEXT COMMENT '标准答案',
    retrieved_contexts  TEXT COMMENT '检索到的 context，JSON 数组',
    answer              TEXT COMMENT '基于 context 现算的答案',
    context_recall      DECIMAL(4,3) COMMENT '标准答案事实点被 context 覆盖的比例',
    faithfulness        DECIMAL(4,3) COMMENT '答案陈述被 context 支撑的比例',
    factual_correctness DECIMAL(4,3) COMMENT '答案与标准答案的事实一致度',
    judge_detail        TEXT COMMENT '裁判返回的原始 JSON（含各指标理由）',
    judge_model         VARCHAR(64) COMMENT '裁判模型，如 deepseek-chat',
    prompt_version      VARCHAR(16) COMMENT '裁判 prompt 版本；prompt 一改必须 bump，否则新旧分数不可比',
    status              VARCHAR(16) COMMENT 'RUNNING/SUCCESS/FAILED/INTERRUPTED',
    error_msg           VARCHAR(1000) COMMENT '失败原因',
    duration_ms         BIGINT COMMENT '该用例耗时(ms)',
    create_time         TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_run (run_id),
    KEY idx_collection (collection)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG评估结果';

-- 3) 周度评估调度配置（单行）
CREATE TABLE IF NOT EXISTS rag_eval_schedule (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    enabled     TINYINT DEFAULT 0 COMMENT '定时评估开关 1开/0关',
    days        VARCHAR(32) COMMENT '星期几，逗号分隔，1=周一..7=周日',
    time        VARCHAR(16) COMMENT '评估时刻 HH:mm',
    collection  VARCHAR(128) COMMENT '评估目标集合',
    sample_size INT COMMENT '每轮用例数',
    top_k       INT COMMENT '检索条数，上限20',
    updated_at  TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG评估周度调度配置';

-- ==================== 教育域（AI 普惠教育 / 乡村教师助教）====================
-- 设计前提：本域刻意"最小化"。只有班级归属，没有姓名、学号、花名册、学校。
-- 教师端要的是"班级聚合趋势"，不是"知道这是谁"——不存在的字段不可能被泄露，
-- 这比"存了但接口不查"可靠得多（本项目全无鉴权体系，任何接口都可能被直接调用）。

CREATE TABLE IF NOT EXISTS edu_user_class (
    user_id     VARCHAR(128) NOT NULL COMMENT '学生标识（微信 userId 或桌面端 user_xxx）',
    class_id    VARCHAR(64)  NOT NULL COMMENT '班级标识，如 class_demo_1',
    role        VARCHAR(16)  NOT NULL DEFAULT 'student' COMMENT 'student / teacher',
    created_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id),
    KEY idx_class (class_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='教育域：学生↔班级映射（无姓名无花名册）';

-- ★ 危机信号【聚合】计数：故意没有 user_id / session_id / 时间点 / 原句。
-- 即使整库被拿走也无法还原"是哪个学生"。个体信息只在进程内存里用于当日去重，用完即弃。
-- 教师端因此不是"被限制看不到个体"，而是"个体数据根本不存在"。
CREATE TABLE IF NOT EXISTS edu_crisis_daily (
    id          BIGINT      AUTO_INCREMENT PRIMARY KEY,
    class_id    VARCHAR(64) NOT NULL COMMENT '班级标识',
    stat_date   DATE        NOT NULL COMMENT '统计日期',
    signal_type VARCHAR(32) NOT NULL DEFAULT 'self_harm' COMMENT '粗粒度信号类别（故意不记具体说法，避免泄露原话）',
    cnt         INT         NOT NULL DEFAULT 0 COMMENT '当日计数（已按 学生+日期 去重：一个学生一天最多计 1）',
    updated_at  TIMESTAMP   DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_class_date_type (class_id, stat_date, signal_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='教育域：危机信号脱敏聚合计数（按班级按日，无个人标识）';

CREATE TABLE IF NOT EXISTS edu_resource (
    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
    class_id    VARCHAR(64)  NOT NULL COMMENT '归属班级',
    file_name   VARCHAR(255) NOT NULL COMMENT '文件名',
    lib_id      VARCHAR(64)  NOT NULL DEFAULT 'school' COMMENT '入库子库',
    chunks      INT          DEFAULT NULL COMMENT '入库分片数（用于确认"传了但没进索引"）',
    uploaded_at TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    KEY idx_class_time (class_id, uploaded_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='教育域：校本资源元数据（文件本体在 data/school-knowledge/）';

CREATE TABLE IF NOT EXISTS edu_feedback (
    id          BIGINT        AUTO_INCREMENT PRIMARY KEY,
    class_id    VARCHAR(64)   NOT NULL COMMENT '提交反馈的班级',
    lib_id      VARCHAR(64)   DEFAULT NULL COMMENT '相关子库',
    message     VARCHAR(1000) NOT NULL COMMENT '反馈内容（教师写的，不含学生个人数据）',
    created_at  TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    KEY idx_class_time (class_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='教育域：教师错误反馈';

-- 演示用班级映射（幂等，可反复执行）。
-- 为什么要有种子数据：危机信号计数与教师端聚合都以「用户归属哪个班」为前提，
-- 表为空时学生端照常收到引导话术，但教师端会恒为 0 —— 演示当天看起来像"功能坏了"。
-- 演示班刻意放 6 个人：k-匿名（少于 3 条不出数）才有意义，也避免"1 条"被反推到个人。
INSERT IGNORE INTO edu_user_class (user_id, class_id, role) VALUES
    ('user_cvic4056', 'class_demo_1', 'student'),
    ('user_demo_1',   'class_demo_1', 'student'),
    ('user_demo_2',   'class_demo_1', 'student'),
    ('user_demo_3',   'class_demo_1', 'student'),
    ('user_demo_4',   'class_demo_1', 'student'),
    ('user_demo_5',   'class_demo_1', 'student');

-- 班级信息。class_id 沿用 VARCHAR：与全项目标识口径一致（user_cvic4056 / class_demo_1），
-- 不引入自增 BIGINT 主键——那需要回填既有四张表的 class_id 并改掉全部聚合代码与前端，
-- 而字符串标识在学校名册导入与演示里都更好用。
CREATE TABLE IF NOT EXISTS edu_class (
    class_id    VARCHAR(64) NOT NULL COMMENT '班级标识，如 class_demo_1',
    class_name  VARCHAR(64) NOT NULL COMMENT '班级名称，如 初一2班',
    create_time TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (class_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='教育域：班级信息';

-- 教师账号。password_hash 存 PBKDF2（JDK 自带 PBKDF2WithHmacSHA256，不引入
-- spring-security / bcrypt 依赖，与本项目"能不加依赖就不加"的风格一致）。
-- 格式：pbkdf2$<迭代次数>$<盐base64>$<哈希base64>。绝不存明文。
CREATE TABLE IF NOT EXISTS edu_teacher (
    id            BIGINT       AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL COMMENT '教师登录账号',
    password_hash VARCHAR(255) NOT NULL COMMENT 'PBKDF2 哈希（含盐与迭代次数），绝不存明文',
    class_id      VARCHAR(64)  NOT NULL COMMENT '绑定班级（教师只能访问该班数据）',
    create_time   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_teacher_username (username),
    KEY idx_teacher_class (class_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='教育域：乡村教师账号';

-- 专题提问计数：供"生命安全与健康教育 / 青春期性教育专题提问占比"出图。
-- 只存 班级 + 日期 + 专题 + 计数——没有 user_id、没有提问原文，与危机计数同款隐私口径。
-- 统计口径是"提问条数"而不是"人数"（不需要用户维度，也就不可能被反推到某个学生）。
CREATE TABLE IF NOT EXISTS edu_topic_ask (
    id        BIGINT      AUTO_INCREMENT PRIMARY KEY,
    class_id  VARCHAR(64) NOT NULL COMMENT '班级标识',
    stat_date DATE        NOT NULL COMMENT '统计日期',
    topic     VARCHAR(32) NOT NULL COMMENT '专题标识：lifesafety / sexedu / school',
    cnt       INT         NOT NULL DEFAULT 0 COMMENT '当日该专题提问条数',
    UNIQUE KEY uk_class_date_topic (class_id, stat_date, topic)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='教育域：专题提问计数（无个人标识）';

-- 演示班级名（幂等）
INSERT IGNORE INTO edu_class (class_id, class_name) VALUES ('class_demo_1', '初一2班（演示班）');

-- 教师自编习题（发布到本班的作业题）。
--
-- ★ 题目的**内容不另存一份**：题干/选项/答案/解析/知识点全部复用现有 quiz_question，
--   "作业"这个容器复用 quiz_paper（user_id 存教师标识、title 存作业名）。
--   同一份题存两处（如再建一张 exercise 内容表）必然漂移，而且学情统计与错题本
--   读的是 quiz_answer + quiz_question.knowledge_point —— 另起一套表等于让
--   "教师发布的题"永远不出现在学情大盘里。
--
-- 本表只存"谁创建、发给哪个班、是否已发布、截止到什么时候"。
-- 发布关系没有单独建表：本项目的教师**只绑定一个班**（角色门就是这么实现的），
-- 发布是 1:1，单独一张 release 表只多一次 join 不增加表达能力。
-- 将来教师能带多个班时，再加 edu_exercise_release(class_id, question_id) 即可。
CREATE TABLE IF NOT EXISTS edu_exercise (
    question_id     BIGINT       NOT NULL COMMENT '题目ID → quiz_question.id',
    class_id        VARCHAR(64)  NOT NULL COMMENT '目标班级（教师只能发给自己的班）',
    creator         VARCHAR(64)  NOT NULL COMMENT '创建教师（edu_teacher.username）',
    knowledge_point VARCHAR(128) DEFAULT NULL COMMENT '知识点标签（薄弱项归因用）',
    published       TINYINT      NOT NULL DEFAULT 0 COMMENT '0=草稿 1=已发布（学生可见）',
    expire_time     DATETIME     DEFAULT NULL COMMENT '截止时间（可空=长期有效）',
    create_time     TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (question_id),
    KEY idx_class_published (class_id, published)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='教育域：教师自编习题与发布状态（内容复用 quiz_question）';

-- 班级 × 专题库 的访问开关。
-- ★ 语义是「默认开放」：表里**没有记录**就表示开放（与本项目"专题库默认对本班学生开放"一致），
--   教师关闭时插入/更新 enabled=0。这样新班级、新专题库都不需要预先铺数据。
CREATE TABLE IF NOT EXISTS edu_class_domain_access (
    id          BIGINT      AUTO_INCREMENT PRIMARY KEY,
    class_id    VARCHAR(64) NOT NULL COMMENT '班级标识',
    domain_id   VARCHAR(64) NOT NULL COMMENT '知识库领域：lifesafety / sexedu / school …',
    enabled     TINYINT     NOT NULL DEFAULT 1 COMMENT '1=开放 0=本班停用（默认开放，关掉才写记录）',
    updated_at  TIMESTAMP   DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_class_domain (class_id, domain_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='教育域：班级级知识库访问开关（默认开放）';

-- 单人课堂：保存大纲、逐页生成结果、学习进度和提交去重记录。
-- 题目和作答仍在既有 quiz_* 表中，避免建立第二套错题本。
CREATE TABLE IF NOT EXISTS edu_learning_course (
    id          VARCHAR(36) NOT NULL PRIMARY KEY,
    document    LONGTEXT NOT NULL,
    updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_learning_course_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='乡村学习课堂状态';

-- 仅保存经真实模型生成并人工核对的公共成长问答，不存访客的个人续聊或上传附件。
-- 联合主键保证一次性准备脚本可用 INSERT IGNORE 只补缺失组，避免覆盖已有内容。
CREATE TABLE IF NOT EXISTS edu_growth_conversation (
    group_id    VARCHAR(40) NOT NULL,
    grade       INT NOT NULL,
    messages    LONGTEXT NOT NULL,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (group_id, grade)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='成长课堂公共问答历史';

-- 本轮推荐、首次表述与核对结果只增量写入此表，不重写 edu_learning_course 或历史 quiz_*。
CREATE TABLE IF NOT EXISTS edu_classroom_study_state (
    kind        VARCHAR(40) NOT NULL,
    entry_id    VARCHAR(160) COLLATE utf8mb4_bin NOT NULL,
    course_id   VARCHAR(64) NOT NULL,
    scene_id    VARCHAR(80) NOT NULL DEFAULT '',
    document    LONGTEXT NOT NULL,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (kind, entry_id),
    KEY idx_study_course_kind_updated (course_id, kind, scene_id, updated_at),
    KEY idx_study_course_kind_created (course_id, kind, scene_id, created_at DESC, entry_id),
    KEY idx_study_course_kind_recent (course_id, kind, updated_at DESC, entry_id),
    KEY idx_study_kind_updated (kind, updated_at, entry_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='共享课堂推荐与可追溯学习反馈';


-- 作答事件保持追加；请求票据及错题队列状态独立存放，不重写原quiz_answer或QQ演示答卷。
CREATE TABLE IF NOT EXISTS quiz_attempt_request (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    request_id VARCHAR(100) NOT NULL,
    paper_id BIGINT NOT NULL,
    operation VARCHAR(16) NOT NULL,
    payload_fingerprint CHAR(64) NOT NULL,
    question_fingerprint CHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'prepared',
    result_json MEDIUMTEXT NULL,
    last_evaluation_id VARCHAR(36) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_quiz_attempt_request (user_id,request_id),
    KEY idx_quiz_attempt_paper (paper_id,created_at)
);

CREATE TABLE IF NOT EXISTS quiz_mistake_resolution (
    user_id VARCHAR(128) NOT NULL,
    question_id BIGINT NOT NULL,
    resolved TINYINT NOT NULL DEFAULT 0,
    request_sequence BIGINT NOT NULL,
    resolution_answer_id BIGINT NULL,
    resolution_kind VARCHAR(16) NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id,question_id),
    KEY idx_quiz_resolution_sequence (request_sequence)
);
