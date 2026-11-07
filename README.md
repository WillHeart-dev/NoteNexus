# NoteNexus

面向学生的学习小组知识共享与 AI 复习平台。

同学按课程/科目组建**学习小组**，上传课件、笔记与真题在组内共享；AI 基于小组资料回答问题并标注出处；需要复习时，一键把全组资料汇总成结构统一的 Markdown 讲义。

---

## 功能

| 模块        | 说明                                                                                                     |
| --------- | ------------------------------------------------------------------------------------------------------ |
| **账号与权限** | JWT 双令牌（access + httpOnly refresh cookie）；系统角色 `ADMIN/USER` × 组内角色 `OWNER/MEMBER` 两级正交；Service 层守卫统一鉴权 |
| **学习小组**  | 创建小组、邀请成员、申请加入与审批、成员管理；群组是数据隔离的唯一边界，资料与问答不跨组                                                           |
| **资料中心**  | 分片上传（断点续传 / 秒传）、格式解析（txt / md / pdf / docx）、结构感知切片、异步 ETL 入库、失败可重试                                     |
| **知识问答**  | 混合检索（pgvector 向量通道 + Elasticsearch 关键词通道，RRF 融合）→ LLM 生成 → 四级证据评估 → 引用溯源；证据不足时主动拒答                     |
| **复习讲义**  | map/reduce 两阶段汇总全组资料 → 固定六章结构（导览 / 知识点精讲 / 易混对比 / 例题 / 自测题 / 速记卡）→ 导出 Markdown 下载                      |
| **练习题库**  | 从资料中抽取题目、在线作答、判分与正确率统计                                                                                 |
| **知识图谱**  | 从资料中抽取知识点与先修/组成关系构建图谱；按前置关系拓扑分层生成学习路径，结合做题正确率标注掌握度并给出优先补强清单                                              |
| **知识库评论** | 挂在学习资料模块底部，成员针对整个知识库发表评价与建议，支持引用回复与撤回                                                     |
| **运营后台**  | 用户管理；ETL 任务统计（成功 / 失败 / 积压）                                                                            |

---

## 技术栈

**后端**：Java 21 · Spring Boot 3.5 · Spring Security · MyBatis-Plus · MySQL 8（业务库）· PostgreSQL 16 + pgvector（向量库）· Elasticsearch 8 + IK（关键词检索）· MinIO（对象存储）· Spring AI（DashScope）

**前端**：Vue 3 · TypeScript · Vite · Pinia · Vue Router · Element Plus

**四套存储的分工**：MySQL 承载全部业务数据；PostgreSQL 专注向量检索（HNSW + 余弦距离）；Elasticsearch 负责中文关键词倒排；MinIO 存放原始文件与生成的讲义。一次检索同时命中向量与关键词两条通道，再由 RRF 融合排序。

---

## 目录结构

```
NoteNexus/
├── NoteNexus-backend/            # Spring Boot 后端
│   └── src/main/java/com/notenexus/rag/
│       ├── auth/                 # 认证、JWT、刷新令牌
│       ├── user/                 # 账号与用户管理
│       ├── group/                # 小组、邀请、加入申请
│       ├── document/             # 资料上传、下载、预览、删除
│       ├── ingestion/            # 解析 → 切片 → 向量化 ETL 流水线
│       ├── engine/               # 向量库 / ES / 对象存储适配
│       ├── qa/                   # 检索、问答、引用、反馈
│       ├── review/               # 复习讲义生成（map/reduce + Markdown 组装）
│       ├── exercise/             # 练习题库
│       ├── graph/                # 知识图谱抽取、构建任务与学习路径
│       ├── discussion/           # 知识库评论（前端入口在学习资料模块底部）
│       └── common/               # 统一响应、异常、枚举、日志、安全上下文
├── NoteNexus-frontend/           # Vue 3 前端
├── sql/                          # 建表脚本（MySQL 业务库 / PostgreSQL 向量库）与增量升级脚本
├── deploy/                       # Dockerfile、docker-compose、启动脚本
├── docs/                         # 技术文档与阶段验证报告
├── evaluation/                   # RAG 评测集与跑分脚本
└── security/                     # 越权测试脚本与报告
```

---

## 快速开始

### 依赖服务

| 服务                       | 端口          | 用途             |
| ------------------------ | ----------- | -------------- |
| MySQL 8                  | 3306        | 业务主库           |
| PostgreSQL 16 + pgvector | 5432        | 向量检索           |
| Elasticsearch 8 + IK     | 9200        | 关键词检索          |
| MinIO                    | 9000 / 9001 | 对象存储（控制台 9001） |
| 后端                       | 10001       | Spring Boot    |
| 前端                       | 5173        | Vite 开发服务器     |

### 启动步骤

```bash
# 1) 建库建表
mysql -uroot -p -e "CREATE DATABASE notenexus_rag DEFAULT CHARSET utf8mb4;"
mysql -uroot -p notenexus_rag < sql/schema-mysql.sql

psql -U postgres -c "CREATE DATABASE notenexus_rag_vector;"
psql -U postgres -d notenexus_rag_vector -f sql/schema-pgvector.sql

# 2) 后端（默认 local 环境）
cd NoteNexus-backend && ./mvnw spring-boot:run

# 3) 前端
cd NoteNexus-frontend && npm install && npm run dev
```

访问 <http://localhost:5173> 。

### 关键配置

配置位于 `NoteNexus-backend/src/main/resources/application-local.yml`（本地，不入库）与 `application-dev.yml`（模板）。常用环境变量：

| 变量                                                                          | 说明                       |
| --------------------------------------------------------------------------- | ------------------------ |
| `DB_HOST` / `DB_PORT` / `DB_NAME`                                           | MySQL 业务库                |
| `VECTOR_DB_HOST` / `VECTOR_DB_NAME`                                         | PostgreSQL 向量库           |
| `MINIO_ENDPOINT` / `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` / `MINIO_BUCKET` | 对象存储                     |
| `ES_URI` / `ES_INDEX_NAME`                                                  | Elasticsearch            |
| `DASHSCOPE_API_KEY`                                                         | Chat 与 Embedding 共用      |
| `NOTENEXUS_AUTH_JWT_SECRET`                                                 | JWT 签名密钥（≥ 32 字节，生产必须替换） |

---

## 接口概览

| 前缀                                    | 说明                              |
| ------------------------------------- | ------------------------------- |
| `/api/auth`                           | 登录 / 注册 / 刷新 / 登出 / 当前用户        |
| `/api/account`                        | 修改密码                            |
| `/api/groups`                         | 小组查询、创建、成员管理                    |
| `/api/invitations`                    | 邀请发出、审批                         |
| `/api/documents`                      | 资料列表、上传（初始化 / 分片 / 完成）、预览、下载、删除 |
| `/api/qa` · `/api/qa/records`         | 提问、历史记录、反馈                      |
| `/api/review-documents`               | 复习讲义生成、列表、详情、下载、删除              |
| `/api/exercises`                      | 题目生成、作答、统计                      |
| `/api/graph`                          | 图谱构建、图谱视图、学习路径                  |
| `/api/discussions`                    | 知识库评论                          |
| `/api/admin/users` · `/api/admin/etl` | 用户管理、ETL 统计                     |

统一响应体：`{ success, data, message }`；下载类接口直接返回流，不做包装。

---

## 数据模型

业务库共 22 张表，核心几张：

- `users` / `user_refresh_tokens` — 账号与刷新令牌
- `notenexus_groups` / `group_memberships` / `group_invitations` / `group_join_requests` — 小组与成员关系
- `documents` / `document_upload_sessions` / `document_upload_chunks` / `document_chunks` / `ingestion_jobs` — 资料与切片
- `qa_records` / `qa_record_citations` / `qa_feedback` — 问答与引用快照
- `review_documents` — 复习讲义（状态机 + 存储键 + Markdown 源）
- `exercise_questions` / `exercise_attempts` / `group_discussion_messages` — 练习与知识库评论
- `knowledge_graph_nodes` / `knowledge_graph_edges` / `knowledge_graph_build_tasks` — 知识图谱与构建任务

> 完整 DDL 见 `sql/schema-mysql.sql`，增量升级见 `sql/upgrade-*.sql`。

---

## 文档

| 文档                                           | 内容                          |
| -------------------------------------------- | --------------------------- |
| [`docs/技术文档.md`](docs/技术文档.md)               | 架构、模块划分、数据模型、核心流程、接口清单、关键配置 |
| [`docs/开发记录.md`](docs/开发记录.md)               | 各阶段做了什么、验证数据、踩坑备忘           |
| [`docs/启动流程与配置加载说明.md`](docs/启动流程与配置加载说明.md) | 启动时加载了哪些配置、初始化顺序与原因         |

##
