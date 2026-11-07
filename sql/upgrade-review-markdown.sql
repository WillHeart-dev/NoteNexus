-- =============================================================
-- 升级脚本：复习讲义导出格式由 PDF 改为 Markdown
--
-- 背景：原方案由 XeLaTeX 编译生成 PDF，依赖外部 TeX 进程；
--       现改为直接输出 .md 文档，不再需要 LaTeX 排版与编译。
--
-- 变更内容：
--   1. review_documents.tex_source 列重命名为 markdown_source，
--      语义由「LaTeX 源码」改为「Markdown 源码」
--   2. 表注释同步更新
--   3. 历史 PDF 记录软删除（其对象存储内容仍为 .pdf，与新格式不一致）
--
-- 执行库：notenexus_rag
-- 执行方式：mysql -uroot -p notenexus_rag < sql/upgrade-review-markdown.sql
-- =============================================================

ALTER TABLE review_documents
    CHANGE COLUMN tex_source markdown_source MEDIUMTEXT NULL COMMENT '生成的 Markdown 源码（便于追溯与二次编辑）';

ALTER TABLE review_documents
    COMMENT = '复习文档表，AI 汇总小组资料生成的可下载 Markdown 文档';

-- 历史记录导出的是 PDF，软删除后前端不再展示；对象存储中的旧文件可另行清理
UPDATE review_documents
SET deleted = 1
WHERE deleted = 0
  AND (storage_object_key LIKE '%.pdf' OR file_name LIKE '%.pdf');
