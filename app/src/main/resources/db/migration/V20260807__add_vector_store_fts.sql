-- 为向量存储表新增全文检索（FTS）辅助列。
-- 应用层 jieba 分词后的结果写入此列，PG 使用 simple parser 建倒排索引，
-- 实现零扩展的中文全文检索。
ALTER TABLE vector_store ADD COLUMN IF NOT EXISTS fts_text TEXT;

-- GIN 索引 on to_tsvector('simple', fts_text)
-- simple parser 按空格切 token —— 应用层已将 jieba 分词结果空格连接。
CREATE INDEX IF NOT EXISTS idx_vector_store_fts_gin
  ON vector_store USING gin (to_tsvector('simple', fts_text));
