package interview.guide.modules.knowledgebase.repository;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 向量存储Repository
 * 负责向量数据的增删改查操作
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class VectorRepository {
    
    private final JdbcTemplate jdbcTemplate;
    
    /**
     * 删除指定知识库的所有向量数据
     * 使用 SQL 直接删除，利用数据库索引和删除能力
     * <p>
     * Spring AI PgVectorStore 默认表名为 vector_store，元数据存储在 metadata 字段（JSONB类型）
     * 
     * @param knowledgeBaseId 知识库ID
     * @return 删除的行数
     */
    public int deleteByKnowledgeBaseId(Long knowledgeBaseId) {
        log.info("开始删除知识库向量数据: kbId={}", knowledgeBaseId);
        
        /* 
         * 注意：
         * 1. metadata 字段是 json 类型，不支持 jsonb_exists 函数。
         * 2. 使用 metadata->>'key' IS NOT NULL 来替代键存在性检查，这在 json/jsonb 下都有效。
         * 3. 这种写法完全避开了 PostgreSQL 的 '?' 操作符，不会引起 JDBC 占位符冲突。
         */
        String sql = """
            DELETE FROM vector_store
            WHERE metadata->>'kb_id' = ?
               OR (metadata->>'kb_id_long' IS NOT NULL AND (metadata->>'kb_id_long')::bigint = ?)
            """;
        
        try {
            // 第一个参数转为 String 匹配 kb_id，第二个参数保持 Long 匹配 kb_id_long
            int deletedRows = jdbcTemplate.update(sql, knowledgeBaseId.toString(), knowledgeBaseId);
            
            if (deletedRows > 0) {
                log.info("成功删除知识库向量数据: kbId={}, 删除行数={}", knowledgeBaseId, deletedRows);
            } else {
                log.info("未找到相关向量数据，无需删除: kbId={}", knowledgeBaseId);
            }
            
            return deletedRows;
            
        } catch (Exception e) {
            log.error("执行删除向量 SQL 失败: kbId={}, error={}", knowledgeBaseId, e.getMessage(), e);
            // 抛出异常以触发事务回滚
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_DELETE_FAILED, "删除向量数据失败");
        }
    }

    /**
     * 删除指定向量化任务写入的临时向量数据。
     */
    public int deleteByVectorJobId(String jobId) {
        String sql = """
            DELETE FROM vector_store
            WHERE metadata->>'kb_vector_job_id' = ?
            """;
        try {
            int deletedRows = jdbcTemplate.update(sql, jobId);
            log.info("已清理临时向量数据: jobId={}, 删除行数={}", jobId, deletedRows);
            return deletedRows;
        } catch (Exception e) {
            log.error("清理临时向量数据失败: jobId={}, error={}", jobId, e.getMessage(), e);
            throw new BusinessException(
                ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "清理临时向量数据失败");
        }
    }

    /**
     * 将临时向量任务提升为当前知识库的正式向量数据。
     */
    public int promoteVectorJob(Long knowledgeBaseId, String jobId) {
        String sql = """
            UPDATE vector_store
            SET metadata = (jsonb_set(
                    metadata::jsonb,
                    '{kb_id}',
                    to_jsonb(?::text),
                    true
                ) - 'kb_vector_job_id' - 'kb_target_id')::json
            WHERE metadata->>'kb_vector_job_id' = ?
            """;
        try {
            int updatedRows = jdbcTemplate.update(sql, knowledgeBaseId.toString(), jobId);
            log.info("临时向量数据已提升为正式数据: kbId={}, jobId={}, 更新行数={}",
                knowledgeBaseId, jobId, updatedRows);
            return updatedRows;
        } catch (Exception e) {
            log.error("提升临时向量数据失败: kbId={}, jobId={}, error={}",
                knowledgeBaseId, jobId, e.getMessage(), e);
            throw new BusinessException(
                ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "提升临时向量数据失败");
        }
    }

    /**
     * 全文检索命中记录。
     */
    public record FtsHit(UUID id, String content, String metadata, double ftsScore) {}

    /**
     * FTS 列回填用的行。
     */
    public record FtsTextRow(UUID id, String ftsText) {}

    /**
     * 按向量化任务 ID 批量写入 fts_text。
     *
     * @param rows id → fts_text 映射列表
     */
    public int[] batchUpdateFtsText(List<FtsTextRow> rows) {
        String sql = "UPDATE vector_store SET fts_text = ? WHERE id = ?::uuid";
        List<Object[]> batchArgs = new ArrayList<>();
        for (var row : rows) {
            batchArgs.add(new Object[]{row.ftsText(), row.id().toString()});
        }
        int[] results = jdbcTemplate.batchUpdate(sql, batchArgs);
        log.debug("批量更新 fts_text: {} 行", rows.size());
        return results;
    }

    /**
     * 全文检索——使用 PG 原生 ts_rank_cd 打分 + GIN 索引。
     *
     * <p>查询前由调用方用 jieba 分词并空格连接，PG 使用 {@code simple} parser 按空格切词。
     *
     * @param tokenQuery 空格连接的 jieba 分词 token 串
     * @param kbIds      限定知识库 ID 列表（空则不限）
     * @param limit      返回条数上限
     * @return FTS 命中列表，按得分降序
     */
    public List<FtsHit> ftsSearch(String tokenQuery, List<Long> kbIds, int limit) {
        StringBuilder sql = new StringBuilder("""
            SELECT id, content, metadata,
                   ts_rank_cd(to_tsvector('simple', fts_text), plainto_tsquery('simple', ?), 1) AS fts_score
            FROM vector_store
            WHERE fts_text IS NOT NULL
              AND to_tsvector('simple', fts_text) @@ plainto_tsquery('simple', ?)
            """);
        List<Object> params = new ArrayList<>();
        params.add(tokenQuery);
        params.add(tokenQuery);

        if (kbIds != null && !kbIds.isEmpty()) {
            // metadata 是 json 类型，用 ->> 取文本；bigint 兼容旧数据
            String kbPlaceholders = kbIds.stream().map(id -> "?").collect(Collectors.joining(", "));
            sql.append(" AND ( metadata->>'kb_id' = ANY(ARRAY[").append(kbPlaceholders).append("])");
            params.addAll(kbIds.stream().map(String::valueOf).toList());
            sql.append(" OR (metadata->>'kb_id_long')::bigint = ANY(ARRAY[").append(kbPlaceholders).append("]) )");
            params.addAll(kbIds.stream().map(Object.class::cast).toList());
        }

        sql.append(" ORDER BY fts_score DESC LIMIT ?");
        params.add(limit);

        try {
            return jdbcTemplate.query(sql.toString(), this::mapFtsHit, params.toArray());
        } catch (Exception e) {
            log.warn("FTS 检索失败，返回空结果: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 统计 fts_text 为空的向量行数（用于回填调度器判断是否还有活要干）。
     */
    public int countMissingFts() {
        String sql = "SELECT COUNT(*) FROM vector_store WHERE fts_text IS NULL";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * 查找 fts_text 为空的向量行（用于回填调度器）。
     */
    public List<UUID> findMissingFtsIds(int limit) {
        String sql = "SELECT id FROM vector_store WHERE fts_text IS NULL LIMIT ?";
        return jdbcTemplate.queryForList(sql, UUID.class, limit);
    }

    /**
     * 查找 fts_text 为空的向量行，返回 (id, content) 对（用于回填调度器）。
     */
    public List<Object[]> findMissingFtsIdContentPairs(int limit) {
        String sql = "SELECT id, content FROM vector_store WHERE fts_text IS NULL LIMIT ?";
        return jdbcTemplate.query(sql, (rs, rowNum) -> new Object[]{
            rs.getObject("id", UUID.class),
            rs.getString("content")
        }, limit);
    }

    /**
     * 按 ID 更新单行 fts_text。
     */
    public int updateFtsText(UUID id, String ftsText) {
        String sql = "UPDATE vector_store SET fts_text = ? WHERE id = ?::uuid";
        return jdbcTemplate.update(sql, ftsText, id.toString());
    }

    private FtsHit mapFtsHit(ResultSet rs, int rowNum) throws SQLException {
        return new FtsHit(
            rs.getObject("id", UUID.class),
            rs.getString("content"),
            rs.getString("metadata"),
            rs.getDouble("fts_score"));
    }
}
