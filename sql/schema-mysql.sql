-- ===================================================
-- NoteNexus 数据库初始化 DDL（MySQL 8.0+ 业务库）
-- ===================================================
-- 改造说明（业务数据由 PostgreSQL 迁至 MySQL，向量存储单独保留）：
--   1. 业务数据全部迁移至 MySQL；向量存储（vector_store 表）保留在
--      PostgreSQL + pgvector，见 sql/schema-pgvector.sql。
--   2. 原表名 groups 为 MySQL 8.0 保留字（GROUPS），统一更名为 notenexus_groups。
--   3. 类型映射：BIGSERIAL→BIGINT AUTO_INCREMENT；JSONB→JSON；
--      BOOLEAN→TINYINT(1)；TIMESTAMP→DATETIME(3)；DOUBLE PRECISION→DOUBLE。
--   4. PG 部分索引（idx_documents_group_hash WHERE status='READY' AND deleted=FALSE）
--      改为普通联合索引（MySQL 不支持部分索引），查询谓词仍可命中最左前缀。
--   5. 长文本字段使用 MEDIUMTEXT（16MB），规避 MySQL TEXT 64KB 上限风险。
--   6. 脚本幂等：全部使用 CREATE TABLE IF NOT EXISTS，索引内联于建表语句，
--      可重复执行；存量表结构变更请走增量迁移脚本，不要改动本文件。
-- 字符集：utf8mb4 / utf8mb4_0900_ai_ci；引擎：InnoDB。
-- ===================================================

-- 用户表
CREATE TABLE IF NOT EXISTS users (
    id                   BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    user_code            VARCHAR(64)   NOT NULL                COMMENT '用户编码，前端展示用，不可修改',
    username             VARCHAR(64)   NOT NULL                COMMENT '登录用户名，唯一',
    email                VARCHAR(128)  NOT NULL                COMMENT '邮箱，唯一',
    display_name         VARCHAR(128)  NOT NULL                COMMENT '显示名称',
    password_hash        VARCHAR(256)  NOT NULL                COMMENT 'BCrypt 密码哈希',
    system_role          VARCHAR(16)   NOT NULL DEFAULT 'USER'  COMMENT '系统角色：ADMIN | USER',
    status               VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT '账号状态：ACTIVE | DISABLED',
    must_change_password TINYINT(1)    NOT NULL DEFAULT 0      COMMENT '是否强制修改密码',
    last_login_at        DATETIME(3)   NULL                    COMMENT '最后登录时间',
    created_at           DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at           DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    CONSTRAINT uq_users_username UNIQUE (username),
    CONSTRAINT uq_users_email    UNIQUE (email)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户表';

-- Refresh Token 表
CREATE TABLE IF NOT EXISTS user_refresh_tokens (
    id          BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    user_id     BIGINT        NOT NULL                COMMENT '关联的用户 ID',
    token_id    VARCHAR(64)   NOT NULL                COMMENT 'token 唯一标识（UUID 去横线）',
    token_hash  VARCHAR(256)  NOT NULL                COMMENT 'BCrypt 哈希后的 token',
    expires_at  DATETIME(3)   NOT NULL                COMMENT '过期时间',
    revoked_at  DATETIME(3)   NULL                    COMMENT '吊销时间，null 表示未吊销',
    created_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    CONSTRAINT fk_refresh_token_user FOREIGN KEY (user_id) REFERENCES users (id),
    KEY idx_refresh_token_token_id (token_id),
    KEY idx_refresh_token_user_active (user_id, revoked_at, expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户 Refresh Token 表';

-- ===================================================
-- 群组与成员管理
-- ===================================================

-- 群组表（原表名 groups，因 MySQL 保留字更名）
CREATE TABLE IF NOT EXISTS notenexus_groups (
    id            BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    group_code    VARCHAR(64)   NOT NULL                COMMENT '群组编码，唯一，前端展示用',
    group_name    VARCHAR(128)  NOT NULL                COMMENT '群组名称',
    description   VARCHAR(1024) NOT NULL DEFAULT ''     COMMENT '群组描述',
    owner_user_id BIGINT        NOT NULL                COMMENT '群组创建者（所有者）用户 ID',
    status        VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT '群组状态：ACTIVE | ARCHIVED',
    created_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    CONSTRAINT uq_groups_group_code UNIQUE (group_code),
    CONSTRAINT fk_groups_owner FOREIGN KEY (owner_user_id) REFERENCES users (id),
    KEY idx_groups_owner (owner_user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '群组表（知识库）';

-- 群组成员表
CREATE TABLE IF NOT EXISTS group_memberships (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    group_id   BIGINT       NOT NULL                COMMENT '群组 ID',
    user_id    BIGINT       NOT NULL                COMMENT '用户 ID',
    role       VARCHAR(16)  NOT NULL DEFAULT 'MEMBER' COMMENT '群组内角色：OWNER | MEMBER',
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '加入时间',
    updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    CONSTRAINT fk_membership_group FOREIGN KEY (group_id) REFERENCES notenexus_groups (id),
    CONSTRAINT fk_membership_user  FOREIGN KEY (user_id)  REFERENCES users (id),
    CONSTRAINT uq_membership_group_user UNIQUE (group_id, user_id),
    KEY idx_membership_user (user_id),
    KEY idx_membership_group_role (group_id, role)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '群组成员关系表';

-- 群组邀请表
CREATE TABLE IF NOT EXISTS group_invitations (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    group_id        BIGINT       NOT NULL                COMMENT '群组 ID',
    inviter_user_id BIGINT       NOT NULL                COMMENT '邀请人用户 ID',
    invitee_user_id BIGINT       NOT NULL                COMMENT '被邀请人用户 ID',
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '邀请状态：PENDING | ACCEPTED | REFUSED | CANCELLED',
    decided_at      DATETIME(3)  NULL                    COMMENT '决定时间（接受/拒绝时设置）',
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    CONSTRAINT fk_invitation_group   FOREIGN KEY (group_id)        REFERENCES notenexus_groups (id),
    CONSTRAINT fk_invitation_inviter FOREIGN KEY (inviter_user_id) REFERENCES users (id),
    CONSTRAINT fk_invitation_invitee FOREIGN KEY (invitee_user_id) REFERENCES users (id),
    KEY idx_invitation_invitee_status (invitee_user_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '群组邀请表';

-- 群组加入申请表
CREATE TABLE IF NOT EXISTS group_join_requests (
    id                 BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    group_id           BIGINT       NOT NULL                COMMENT '群组 ID',
    applicant_user_id  BIGINT       NOT NULL                COMMENT '申请人用户 ID',
    status             VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '申请状态：PENDING | APPROVED | REJECTED | CANCELLED',
    decided_by_user_id BIGINT       NULL                    COMMENT '审批人用户 ID',
    decided_at         DATETIME(3)  NULL                    COMMENT '审批时间',
    created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    CONSTRAINT fk_join_request_group     FOREIGN KEY (group_id)          REFERENCES notenexus_groups (id),
    CONSTRAINT fk_join_request_applicant FOREIGN KEY (applicant_user_id)  REFERENCES users (id),
    CONSTRAINT fk_join_request_decider   FOREIGN KEY (decided_by_user_id) REFERENCES users (id),
    KEY idx_join_request_group_status (group_id, status),
    KEY idx_join_request_applicant (applicant_user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '群组加入申请表';

-- ===================================================
-- 文档管理
-- ===================================================

-- 文档表
CREATE TABLE IF NOT EXISTS documents (
    id                 BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    group_id           BIGINT        NOT NULL                COMMENT '所属群组 ID',
    uploader_user_id   BIGINT        NOT NULL                COMMENT '上传者用户 ID',
    file_name          VARCHAR(512)  NOT NULL                COMMENT '原始文件名',
    file_ext           VARCHAR(32)   NOT NULL DEFAULT ''     COMMENT '文件扩展名（不含点）',
    content_type       VARCHAR(128)  NOT NULL DEFAULT ''     COMMENT 'MIME 类型',
    file_size          BIGINT        NOT NULL DEFAULT 0      COMMENT '文件大小（字节）',
    file_hash          VARCHAR(128)  NOT NULL                COMMENT '文件 SHA-256 哈希',
    storage_bucket     VARCHAR(128)  NOT NULL DEFAULT ''     COMMENT '对象存储 Bucket 名称',
    storage_object_key VARCHAR(512)  NOT NULL DEFAULT ''     COMMENT '对象存储 Object Key',
    status             VARCHAR(16)   NOT NULL DEFAULT 'UPLOADED' COMMENT '处理状态：UPLOADED | PROCESSING | READY | FAILED',
    deleted            TINYINT(1)    NOT NULL DEFAULT 0      COMMENT '逻辑删除标记',
    failure_reason     MEDIUMTEXT    NULL                    COMMENT '处理失败原因',
    preview_text       MEDIUMTEXT    NULL                    COMMENT '文档预览文本（前若干字符）',
    uploaded_at        DATETIME(3)   NULL                    COMMENT '上传完成时间',
    processed_at       DATETIME(3)   NULL                    COMMENT '处理完成时间',
    created_at         DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at         DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    CONSTRAINT fk_document_group    FOREIGN KEY (group_id)         REFERENCES notenexus_groups (id),
    CONSTRAINT fk_document_uploader FOREIGN KEY (uploader_user_id) REFERENCES users (id),
    KEY idx_documents_group_deleted (group_id, deleted),
    KEY idx_documents_group_hash (group_id, file_hash),
    KEY idx_documents_status (status, deleted),
    KEY idx_documents_uploader (uploader_user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '文档表';

-- 文档上传会话表（分片上传）
CREATE TABLE IF NOT EXISTS document_upload_sessions (
    id                 BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    upload_id          VARCHAR(64)   NOT NULL                COMMENT '上传会话唯一标识（UUID）',
    group_id           BIGINT        NOT NULL                COMMENT '目标群组 ID',
    uploader_user_id   BIGINT        NOT NULL                COMMENT '上传者用户 ID',
    file_name          VARCHAR(512)  NOT NULL                COMMENT '原始文件名',
    file_ext           VARCHAR(32)   NOT NULL DEFAULT ''     COMMENT '文件扩展名',
    content_type       VARCHAR(128)  NOT NULL DEFAULT ''     COMMENT 'MIME 类型',
    file_size          BIGINT        NOT NULL DEFAULT 0      COMMENT '文件总大小（字节）',
    file_hash          VARCHAR(128)  NOT NULL DEFAULT ''     COMMENT '完整文件 SHA-256 哈希',
    chunk_size         BIGINT        NOT NULL DEFAULT 0      COMMENT '每个分片的标准大小（字节）',
    chunk_count        INT           NOT NULL DEFAULT 0      COMMENT '总分片数',
    status             VARCHAR(16)   NOT NULL DEFAULT 'INIT' COMMENT '会话状态：INIT | UPLOADING | COMPLETING | COMPLETED | EXPIRED',
    storage_bucket     VARCHAR(128)  NOT NULL DEFAULT ''     COMMENT '对象存储 Bucket 名称',
    merged_object_key  VARCHAR(512)  NULL                    COMMENT '合并后完整文件的对象存储 Key',
    expires_at         DATETIME(3)   NOT NULL                COMMENT '会话过期时间',
    created_at         DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at         DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    CONSTRAINT fk_upload_session_group    FOREIGN KEY (group_id)         REFERENCES notenexus_groups (id),
    CONSTRAINT fk_upload_session_uploader FOREIGN KEY (uploader_user_id) REFERENCES users (id),
    CONSTRAINT uq_upload_session_upload_id UNIQUE (upload_id),
    KEY idx_upload_session_reusable (group_id, uploader_user_id, file_hash, status, expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '文档上传会话表（分片上传）';

-- 文档上传分片表
CREATE TABLE IF NOT EXISTS document_upload_chunks (
    id                 BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    upload_id          VARCHAR(64)   NOT NULL                COMMENT '所属上传会话 ID',
    chunk_index        INT           NOT NULL                COMMENT '分片序号（从 0 开始）',
    chunk_size         BIGINT        NOT NULL DEFAULT 0      COMMENT '分片实际大小（字节）',
    chunk_hash         VARCHAR(128)  NOT NULL DEFAULT ''     COMMENT '分片 SHA-256 哈希',
    storage_bucket     VARCHAR(128)  NOT NULL DEFAULT ''     COMMENT '对象存储 Bucket 名称',
    storage_object_key VARCHAR(512)  NOT NULL DEFAULT ''     COMMENT '分片对象存储 Key',
    uploaded_at        DATETIME(3)   NULL                    COMMENT '分片上传完成时间',
    created_at         DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at         DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    CONSTRAINT fk_upload_chunk_session FOREIGN KEY (upload_id) REFERENCES document_upload_sessions (upload_id),
    CONSTRAINT uq_upload_chunk UNIQUE (upload_id, chunk_index),
    KEY idx_upload_chunk_upload_id (upload_id, chunk_index)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '文档上传分片表';

-- ===================================================
-- 文档摄入（ETL 流水线）
-- ===================================================

-- 文档切片表
CREATE TABLE IF NOT EXISTS document_chunks (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    document_id   BIGINT       NOT NULL                COMMENT '所属文档 ID',
    group_id      BIGINT       NOT NULL                COMMENT '所属群组 ID（冗余，加速查询）',
    chunk_index   INT          NOT NULL                COMMENT '切片在文档中的序号',
    chunk_text    MEDIUMTEXT   NOT NULL                COMMENT '切片文本内容',
    chunk_summary MEDIUMTEXT   NULL                    COMMENT '切片摘要（可选）',
    char_start    INT          NOT NULL DEFAULT 0      COMMENT '切片在原文档中的起始字符位置',
    char_end      INT          NOT NULL DEFAULT 0      COMMENT '切片在原文档中的结束字符位置',
    metadata_json JSON         NULL                    COMMENT '切片元数据（JSON 格式）',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    CONSTRAINT fk_chunk_document FOREIGN KEY (document_id) REFERENCES documents (id),
    CONSTRAINT fk_chunk_group    FOREIGN KEY (group_id)    REFERENCES notenexus_groups (id),
    KEY idx_chunk_document (document_id),
    KEY idx_chunk_group (group_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '文档切片表（检索的最小单元）';

-- 摄入任务表（异步 Worker 调度）
CREATE TABLE IF NOT EXISTS ingestion_jobs (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    document_id   BIGINT       NOT NULL                COMMENT '关联的文档 ID',
    group_id      BIGINT       NOT NULL                COMMENT '关联的群组 ID',
    job_type      VARCHAR(32)  NOT NULL DEFAULT 'INGEST_DOCUMENT' COMMENT '任务类型：INGEST_DOCUMENT',
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '任务状态：PENDING | RUNNING | SUCCEEDED | FAILED | CANCELLED',
    retry_count   INT          NOT NULL DEFAULT 0      COMMENT '当前重试次数',
    max_retries   INT          NOT NULL DEFAULT 3      COMMENT '最大重试次数',
    worker_id     VARCHAR(128) NULL                    COMMENT '执行该任务的 Worker 标识',
    started_at    DATETIME(3)  NULL                    COMMENT '任务开始执行时间',
    finished_at   DATETIME(3)  NULL                    COMMENT '任务完成时间',
    next_retry_at DATETIME(3)  NULL                    COMMENT '下次重试时间（null 表示无需重试）',
    last_error    MEDIUMTEXT   NULL                    COMMENT '最近一次失败的错误信息',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    CONSTRAINT fk_job_document FOREIGN KEY (document_id) REFERENCES documents (id),
    CONSTRAINT fk_job_group    FOREIGN KEY (group_id)    REFERENCES notenexus_groups (id),
    KEY idx_job_runnable (status, next_retry_at, created_at),
    KEY idx_job_document (document_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '文档摄入任务表（异步 Worker 调度）';

-- ===================================================
-- LLM 调用统计记录表
-- ===================================================

CREATE TABLE IF NOT EXISTS llm_usage_records (
    id                BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    user_id           BIGINT        NOT NULL                COMMENT '调用用户ID',
    group_id          BIGINT        NULL                    COMMENT '关联群组ID（可为空）',
    module            VARCHAR(32)   NOT NULL                COMMENT '模块标识: QA / ASSISTANT',
    endpoint          VARCHAR(64)   NOT NULL                COMMENT '接口标识: qa/ask, qa/stream-ask, assistant/chat, assistant/chat/stream',
    session_id        VARCHAR(64)   NULL                    COMMENT '助手会话ID（QA模块可为空）',
    prompt_tokens     INT           NOT NULL DEFAULT 0      COMMENT '输入token数',
    completion_tokens INT           NOT NULL DEFAULT 0      COMMENT '输出token数',
    total_tokens      INT           NOT NULL DEFAULT 0      COMMENT '总token数',
    is_estimated      TINYINT(1)    NOT NULL DEFAULT 0      COMMENT '是否为估算值（流式调用可能无法获取精确token数）',
    cost_amount       DECIMAL(12,6) NOT NULL DEFAULT 0      COMMENT '本次调用费用（元）',
    cost_currency     VARCHAR(8)    NOT NULL DEFAULT 'CNY'  COMMENT '货币单位',
    latency_ms        BIGINT        NOT NULL DEFAULT 0      COMMENT '响应耗时(毫秒)',
    success           TINYINT(1)    NOT NULL DEFAULT 1      COMMENT '是否成功',
    error_message     MEDIUMTEXT    NULL                    COMMENT '失败原因',
    model_name        VARCHAR(64)   NULL                    COMMENT '使用的模型名称',
    created_at        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录时间',
    KEY idx_llm_usage_user_created (user_id, created_at),
    KEY idx_llm_usage_group_created (group_id, created_at),
    KEY idx_llm_usage_module_created (module, created_at),
    KEY idx_llm_usage_created_at (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'LLM调用统计记录表';

-- ===================================================
-- QA records persistence（问答记录与引用快照）
-- ===================================================

CREATE TABLE IF NOT EXISTS qa_records (
    id                BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '问答记录主键',
    user_id           BIGINT       NOT NULL                COMMENT '提问用户 ID',
    group_id          BIGINT       NOT NULL                COMMENT '问答发生的知识库群组 ID',
    question          MEDIUMTEXT   NOT NULL                COMMENT '用户原始问题',
    answer            MEDIUMTEXT   NULL                    COMMENT '模型回答正文，拒答或失败时可为空',
    answered          TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '是否成功回答问题',
    reason_code       VARCHAR(64)  NULL                    COMMENT '拒答或失败原因编码',
    reason_message    MEDIUMTEXT   NULL                    COMMENT '拒答或失败原因说明',
    evidence_level    VARCHAR(32)  NULL                    COMMENT '检索证据充分性等级',
    citation_count    INT          NOT NULL DEFAULT 0      COMMENT '保存的引用快照数量',
    prompt_tokens     INT          NOT NULL DEFAULT 0      COMMENT '输入 token 数',
    completion_tokens INT          NOT NULL DEFAULT 0      COMMENT '输出 token 数',
    total_tokens      INT          NOT NULL DEFAULT 0      COMMENT '总 token 数',
    is_estimated      TINYINT(1)   NOT NULL DEFAULT 0      COMMENT 'token 用量是否为估算值',
    latency_ms        BIGINT       NOT NULL DEFAULT 0      COMMENT '问答耗时，单位毫秒',
    model_name        VARCHAR(64)  NULL                    COMMENT '调用的大模型名称',
    endpoint          VARCHAR(64)  NOT NULL                COMMENT '触发记录的接口端点',
    success           TINYINT(1)   NOT NULL DEFAULT 1      COMMENT '本次问答流程是否成功完成',
    error_message     MEDIUMTEXT   NULL                    COMMENT '系统异常信息，业务拒答时通常为空',
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录创建时间',
    CONSTRAINT fk_qa_record_user  FOREIGN KEY (user_id)  REFERENCES users (id),
    CONSTRAINT fk_qa_record_group FOREIGN KEY (group_id) REFERENCES notenexus_groups (id),
    KEY idx_qa_records_user_created (user_id, created_at),
    KEY idx_qa_records_group_created (group_id, created_at),
    KEY idx_qa_records_group_user_created (group_id, user_id, created_at),
    KEY idx_qa_records_answered_created (answered, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'QA 问答记录表，保存每次知识库问答的完整结果和用量信息';

CREATE TABLE IF NOT EXISTS qa_record_citations (
    id                   BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '引用快照主键',
    qa_record_id         BIGINT       NOT NULL                COMMENT '所属 QA 问答记录 ID',
    document_id          BIGINT       NULL                    COMMENT '来源文档 ID',
    document_version_id  BIGINT       NULL                    COMMENT '来源文档版本 ID，当前系统未启用版本时可为空',
    chunk_id             BIGINT       NULL                    COMMENT '来源切片 ID',
    chunk_index          INT          NULL                    COMMENT '切片在文档中的序号',
    start_chunk_index    INT          NULL                    COMMENT '合并证据片段的起始切片序号',
    end_chunk_index      INT          NULL                    COMMENT '合并证据片段的结束切片序号',
    file_name            VARCHAR(512) NOT NULL                COMMENT '来源文件名',
    score                DOUBLE       NULL                    COMMENT '最终相关性得分',
    retrieval_source     VARCHAR(32)  NULL                    COMMENT '检索来源类型，如 VECTOR、KEYWORD、BOTH',
    vector_score         DOUBLE       NULL                    COMMENT '向量检索得分',
    keyword_score        DOUBLE       NULL                    COMMENT '关键词检索得分',
    hybrid_score         DOUBLE       NULL                    COMMENT '混合检索融合得分',
    snippet              MEDIUMTEXT   NULL                    COMMENT '回答时使用的证据文本快照',
    created_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '快照创建时间',
    CONSTRAINT fk_qa_record_citation_record FOREIGN KEY (qa_record_id) REFERENCES qa_records (id) ON DELETE CASCADE,
    KEY idx_qa_record_citations_record (qa_record_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'QA 引用快照表，保存回答时实际使用的检索证据';

-- ============================================================
-- QA 问答反馈表（阶段 8：问答反馈闭环）
-- ============================================================
CREATE TABLE IF NOT EXISTS qa_feedback (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    record_id     BIGINT       NOT NULL COMMENT '关联 qa_records.id',
    user_id       BIGINT       NOT NULL COMMENT '反馈用户 ID',
    group_id      BIGINT       NOT NULL COMMENT '问答所属群组 ID（冗余，便于按群统计）',
    feedback_type VARCHAR(16)  NOT NULL COMMENT '反馈类型：LIKE/DISLIKE',
    reason        VARCHAR(255) NULL COMMENT '点踩原因（可空）',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_record_user (record_id, user_id),
    KEY idx_group_type (group_id, feedback_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'QA 问答反馈表，点赞/点踩与低质原因收集';

-- ============================================================
-- 复习文档表（阶段 9：AI 复习资料生成）
-- 一个学习小组可生成多份复习 PDF；生成过程异步，状态机 PENDING → GENERATING → COMPLETED | FAILED
-- ============================================================
CREATE TABLE IF NOT EXISTS review_documents (
    id                    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    group_id              BIGINT       NOT NULL COMMENT '所属学习小组 ID',
    title                 VARCHAR(256) NOT NULL COMMENT '复习文档标题',
    status                VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING | GENERATING | COMPLETED | FAILED',
    source_document_count INT          NOT NULL DEFAULT 0 COMMENT '参与汇总的资料篇数',
    source_document_names MEDIUMTEXT   NULL COMMENT '参与汇总的资料文件名快照（JSON 数组）',
    chunk_count           INT          NOT NULL DEFAULT 0 COMMENT '参与汇总的切片总数',
    file_name             VARCHAR(512) NOT NULL DEFAULT '' COMMENT '生成的 Markdown 文件名',
    file_size             BIGINT       NOT NULL DEFAULT 0 COMMENT 'Markdown 文件大小（字节）',
    storage_bucket        VARCHAR(128) NOT NULL DEFAULT '' COMMENT '对象存储 Bucket',
    storage_object_key    VARCHAR(512) NOT NULL DEFAULT '' COMMENT '对象存储 Object Key',
    markdown_source       MEDIUMTEXT   NULL COMMENT '生成的 Markdown 源码（便于追溯与二次编辑）',
    failure_reason        MEDIUMTEXT   NULL COMMENT '失败原因，仅 status=FAILED 时有值',
    created_by            BIGINT       NOT NULL COMMENT '触发生成的用户 ID',
    started_at            DATETIME(3)  NULL COMMENT '开始生成时间',
    completed_at          DATETIME(3)  NULL COMMENT '生成完成时间',
    deleted               TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
    created_at            DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at            DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    CONSTRAINT fk_review_document_group   FOREIGN KEY (group_id)   REFERENCES notenexus_groups (id),
    CONSTRAINT fk_review_document_creator FOREIGN KEY (created_by) REFERENCES users (id),
    KEY idx_review_group_deleted (group_id, deleted),
    KEY idx_review_status (status, deleted)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '复习文档表，AI 汇总小组资料生成的可下载 Markdown 文档';

-- ===================================================
-- 群组讨论、习题库（阶段 10 新增）
-- ===================================================

-- 群组讨论消息表
CREATE TABLE IF NOT EXISTS group_discussion_messages (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    group_id    BIGINT       NOT NULL COMMENT '所属群组 ID',
    user_id     BIGINT       NOT NULL COMMENT '发送者用户 ID',
    content     MEDIUMTEXT   NOT NULL COMMENT '消息正文（Markdown）',
    reply_to_id BIGINT       NULL COMMENT '引用回复的消息 ID',
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE | DELETED',
    deleted     TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '发送时间',
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    CONSTRAINT fk_discussion_group FOREIGN KEY (group_id) REFERENCES notenexus_groups (id),
    CONSTRAINT fk_discussion_user  FOREIGN KEY (user_id)  REFERENCES users (id),
    KEY idx_discussion_group_time (group_id, deleted, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '群组讨论消息表';

-- 习题库表：由 AI 从群组资料中抽取的习题
CREATE TABLE IF NOT EXISTS exercise_questions (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    group_id          BIGINT       NOT NULL COMMENT '所属群组 ID',
    source_document_id BIGINT      NULL COMMENT '来源资料 ID，为空表示人工录入',
    source_chunk_id   BIGINT       NULL COMMENT '来源切片 ID',
    question_type     VARCHAR(16)  NOT NULL DEFAULT 'SINGLE_CHOICE' COMMENT '题型：SINGLE_CHOICE | MULTIPLE_CHOICE | TRUE_FALSE | SHORT_ANSWER',
    stem              MEDIUMTEXT   NOT NULL COMMENT '题干',
    options_json      MEDIUMTEXT   NULL COMMENT '选项 JSON 数组，单选题/多选题使用',
    answer            MEDIUMTEXT   NOT NULL COMMENT '标准答案',
    analysis          MEDIUMTEXT   NULL COMMENT '解析',
    difficulty        VARCHAR(8)   NOT NULL DEFAULT 'MEDIUM' COMMENT '难度：EASY | MEDIUM | HARD',
    tags              VARCHAR(256) NOT NULL DEFAULT '' COMMENT '标签，逗号分隔',
    source_snippet    MEDIUMTEXT   NULL COMMENT '资料原文依据片段，用于溯源',
    deleted           TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
    created_by        BIGINT       NOT NULL COMMENT '创建者用户 ID',
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    CONSTRAINT fk_exercise_group    FOREIGN KEY (group_id) REFERENCES notenexus_groups (id),
    CONSTRAINT fk_exercise_creator  FOREIGN KEY (created_by) REFERENCES users (id),
    KEY idx_exercise_group_deleted (group_id, deleted),
    KEY idx_exercise_type (group_id, question_type, deleted)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '习题库表';

-- 刷题作答记录表
CREATE TABLE IF NOT EXISTS exercise_attempts (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    group_id     BIGINT       NOT NULL COMMENT '所属群组 ID',
    question_id  BIGINT       NOT NULL COMMENT '习题 ID',
    user_id      BIGINT       NOT NULL COMMENT '作答用户 ID',
    user_answer  MEDIUMTEXT   NOT NULL COMMENT '用户作答内容',
    is_correct   TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否正确',
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '作答时间',
    PRIMARY KEY (id),
    CONSTRAINT fk_attempt_group    FOREIGN KEY (group_id)    REFERENCES notenexus_groups (id),
    CONSTRAINT fk_attempt_question FOREIGN KEY (question_id) REFERENCES exercise_questions (id),
    CONSTRAINT fk_attempt_user     FOREIGN KEY (user_id)     REFERENCES users (id),
    KEY idx_attempt_user_question (user_id, question_id),
    KEY idx_attempt_group_time (group_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '刷题作答记录表';

-- ===================================================
-- 知识图谱与学习路径
-- ===================================================

-- 知识点节点表（图谱顶点）
CREATE TABLE IF NOT EXISTS knowledge_graph_nodes (
    id               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    group_id         BIGINT        NOT NULL COMMENT '所属群组 ID',
    node_key         VARCHAR(160)  NOT NULL COMMENT '概念归一化键（小写、去空白与标点），组内唯一',
    name             VARCHAR(160)  NOT NULL COMMENT '知识点名称',
    summary          VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '知识点释义，由资料原文归纳',
    importance       INT           NOT NULL DEFAULT 5 COMMENT '重要度 1-10，用于图谱节点大小与路径排序',
    document_count   INT           NOT NULL DEFAULT 1 COMMENT '支撑该知识点的资料份数',
    source_documents VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '来源资料名，逗号分隔',
    created_at       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    CONSTRAINT fk_kg_node_group FOREIGN KEY (group_id) REFERENCES notenexus_groups (id),
    CONSTRAINT uq_kg_node_key   UNIQUE (group_id, node_key),
    KEY idx_kg_node_group (group_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '知识图谱节点表';

-- 知识点关系表（图谱边）
CREATE TABLE IF NOT EXISTS knowledge_graph_edges (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    group_id       BIGINT       NOT NULL COMMENT '所属群组 ID',
    source_node_id BIGINT       NOT NULL COMMENT '起点知识点 ID',
    target_node_id BIGINT       NOT NULL COMMENT '终点知识点 ID',
    relation_type  VARCHAR(24)  NOT NULL DEFAULT 'RELATED_TO' COMMENT '关系：PREREQUISITE_OF（起点是先修）| PART_OF（起点属于终点）| RELATED_TO',
    weight         DOUBLE       NOT NULL DEFAULT 1 COMMENT '关系权重，随多份资料共同印证而累加',
    evidence       VARCHAR(512) NOT NULL DEFAULT '' COMMENT '关系依据的原文摘录',
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    CONSTRAINT fk_kg_edge_group  FOREIGN KEY (group_id)       REFERENCES notenexus_groups (id),
    CONSTRAINT fk_kg_edge_source FOREIGN KEY (source_node_id) REFERENCES knowledge_graph_nodes (id) ON DELETE CASCADE,
    CONSTRAINT fk_kg_edge_target FOREIGN KEY (target_node_id) REFERENCES knowledge_graph_nodes (id) ON DELETE CASCADE,
    CONSTRAINT uq_kg_edge UNIQUE (group_id, source_node_id, target_node_id, relation_type),
    KEY idx_kg_edge_group (group_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '知识图谱关系表';

-- 图谱构建任务表（异步任务状态机）
CREATE TABLE IF NOT EXISTS knowledge_graph_build_tasks (
    id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    group_id        BIGINT        NOT NULL COMMENT '所属群组 ID',
    requested_by    BIGINT        NOT NULL COMMENT '发起用户 ID',
    status          VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING | GENERATING | COMPLETED | FAILED',
    document_count  INT           NOT NULL DEFAULT 0 COMMENT '参与抽取的资料份数',
    node_count      INT           NOT NULL DEFAULT 0 COMMENT '生成的知识点数量',
    edge_count      INT           NOT NULL DEFAULT 0 COMMENT '生成的关系数量',
    failure_reason  VARCHAR(2000) NULL COMMENT '失败原因',
    started_at      DATETIME(3)   NULL COMMENT '开始时间',
    completed_at    DATETIME(3)   NULL COMMENT '结束时间',
    created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    CONSTRAINT fk_kg_task_group    FOREIGN KEY (group_id)     REFERENCES notenexus_groups (id),
    CONSTRAINT fk_kg_task_requester FOREIGN KEY (requested_by) REFERENCES users (id),
    KEY idx_kg_task_group (group_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '知识图谱构建任务表';
