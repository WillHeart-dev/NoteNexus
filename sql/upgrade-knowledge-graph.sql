-- ===================================================
-- 增量迁移：知识图谱与学习路径
-- ===================================================
-- 适用版本：已在运行、且尚未包含 knowledge_graph_* 三张表的库。
-- 全新部署直接执行 sql/schema-mysql.sql 即可，无需本脚本。
-- 脚本幂等，可重复执行。
-- 执行方式：
--   mysql -uroot -p notenexus_rag < sql/upgrade-knowledge-graph.sql
-- ===================================================

-- 知识点节点表
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

-- 知识点关系表
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

-- 图谱构建任务表
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
    CONSTRAINT fk_kg_task_group     FOREIGN KEY (group_id)     REFERENCES notenexus_groups (id),
    CONSTRAINT fk_kg_task_requester FOREIGN KEY (requested_by) REFERENCES users (id),
    KEY idx_kg_task_group (group_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '知识图谱构建任务表';
