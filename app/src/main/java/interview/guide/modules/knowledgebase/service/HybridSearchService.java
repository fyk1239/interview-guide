package interview.guide.modules.knowledgebase.service;

import interview.guide.common.nlp.ChineseTextTokenizer;
import interview.guide.modules.knowledgebase.repository.VectorRepository;
import interview.guide.modules.knowledgebase.repository.VectorRepository.FtsHit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 混合检索编排——向量路 + FTS 路双路召回 → RRF 融合 → 可选 rerank 精排。
 *
 * <h3>检索漏斗</h3>
 * <ol>
 *   <li><b>向量路</b>：语义召回（pgvector HNSW），擅长同义改写</li>
 *   <li><b>FTS 路</b>：关键词召回（PG ts_rank_cd + jieba 分词），擅长精确术语</li>
 *   <li><b>RRF 融合</b>：按排名倒数融合——两路分数尺度不同，只比排名</li>
 *   <li><b>Rerank 精排</b>（可选）：跨编码器 query-doc 对打分，减少噪音</li>
 * </ol>
 *
 * <h3>降级链</h3>
 * rerank API 不可用 → 回到 RRF 融合结果（不丢数据，只为质量优化）
 *
 * <p>示例：问"spring 事务失效场景"——向量路召回"声明式事务失效"，
 * FTS 路精确命中含 {@code @Transactional} 的文档，RRF 按排名融合，
 * rerank 把真正相关的排到前几位。
 */
@Slf4j
@Service
public class HybridSearchService {

  private final KnowledgeBaseVectorService vectorService;
  private final VectorRepository vectorRepository;
  private final ChineseTextTokenizer tokenizer;
  private RerankService rerankService;

  private static final int DEFAULT_RRF_K = 60;

  public HybridSearchService(
      KnowledgeBaseVectorService vectorService,
      VectorRepository vectorRepository,
      ChineseTextTokenizer tokenizer) {
    this.vectorService = vectorService;
    this.vectorRepository = vectorRepository;
    this.tokenizer = tokenizer;
  }

  @Autowired(required = false)
  public void setRerankService(RerankService rerankService) {
    this.rerankService = rerankService;
  }

  public List<Document> hybridSearch(
      String query,
      List<Long> kbIds,
      int topK,
      double minScore,
      KnowledgeBaseQueryProperties props) {

    var hybridCfg = props.getHybrid();
    int vTopK = hybridCfg.getTopKVector();
    int fTopK = hybridCfg.getTopKFts();
    int cap = hybridCfg.getCandidateCap();
    int rrfK = hybridCfg.getRrfK() > 0 ? hybridCfg.getRrfK() : DEFAULT_RRF_K;

    // 1. 向量路 + FTS 路召回
    List<Document> vectorHits = vectorService.similaritySearch(query, kbIds, vTopK, minScore);
    String tokenQuery = tokenizer.tokenizeForSearch(query);
    List<FtsHit> ftsHits = !tokenQuery.isBlank()
        ? vectorRepository.ftsSearch(tokenQuery, kbIds, fTopK)
        : List.of();
    log.debug("混合检索: 向量{} + FTS{}", vectorHits.size(), ftsHits.size());

    if (vectorHits.isEmpty() && ftsHits.isEmpty()) {
      return List.of();
    }

    // 2. RRF 融合
    List<Document> rrfResults = rrfMerge(vectorHits, ftsHits, rrfK, cap);

    // 3. Rerank 精排（可选，失败降级）
    List<Document> results = applyRerank(query, rrfResults, topK, props);

    // 4. 最终 topK 截断
    if (results.size() > topK) {
      results = results.subList(0, topK);
    }
    log.info("混合检索完成: 向量{} + FTS{} → RRF{} → rerank{} → top{}",
        vectorHits.size(), ftsHits.size(), rrfResults.size(),
        rerankService != null ? results.size() : "(skip)", results.size());
    return results;
  }

  private List<Document> rrfMerge(List<Document> vectorHits, List<FtsHit> ftsHits,
      int rrfK, int cap) {
    Map<UUID, RrfEntry> rrfMap = new LinkedHashMap<>();

    for (int i = 0; i < vectorHits.size(); i++) {
      var doc = vectorHits.get(i);
      UUID id = safeParseUuid(doc.getId());
      if (id != null) {
        var entry = rrfMap.computeIfAbsent(id,
            k -> new RrfEntry(id, doc.getText(), doc.getMetadata()));
        entry.rrfScore += 1.0 / (rrfK + i + 1);
      }
    }
    for (int i = 0; i < ftsHits.size(); i++) {
      var hit = ftsHits.get(i);
      UUID id = hit.id();
      if (id != null) {
        var entry = rrfMap.computeIfAbsent(id,
            k -> new RrfEntry(id, hit.content(), null));
        entry.rrfScore += 1.0 / (rrfK + i + 1);
      }
    }

    var sorted = rrfMap.values().stream()
        .sorted(Comparator.comparingDouble((RrfEntry e) -> -e.rrfScore))
        .limit(cap)
        .toList();

    List<Document> results = new ArrayList<>();
    for (var entry : sorted) {
      if (entry.content != null && !entry.content.isBlank()) {
        var doc = new Document(entry.id.toString(), entry.content,
            entry.metadata != null ? new java.util.HashMap<>(entry.metadata) : new java.util.HashMap<>());
        results.add(doc);
      }
    }
    return results;
  }

  private List<Document> applyRerank(String query, List<Document> candidates, int topK,
      KnowledgeBaseQueryProperties props) {
    if (rerankService == null || !props.getRerank().isEnabled()) {
      return candidates;
    }
    var rerankCfg = props.getRerank();
    try {
      List<String> texts = candidates.stream().map(Document::getText).toList();
      List<RerankHit> hits = rerankService.rerank(query, texts, rerankCfg.getTopN());

      if (hits.isEmpty()) {
        log.debug("Rerank 返回空结果，降级使用 RRF 融合结果");
        return candidates;
      }

      List<Document> reranked = new ArrayList<>();
      for (var hit : hits) {
        if (hit.relevanceScore() < rerankCfg.getMinRelevanceScore()) {
          continue;
        }
        // 从原候选按 index 取文本与 metadata（DashScope rerank 响应不含 document 字段，
        // hit.document() 恒为 null，不能用来构造 Document）
        if (hit.index() >= 0 && hit.index() < candidates.size()) {
          var orig = candidates.get(hit.index());
          var metadata = new java.util.HashMap<>(orig.getMetadata());
          metadata.put("rerank_score", hit.relevanceScore());
          var doc = new Document(orig.getId(), orig.getText(), metadata);
          reranked.add(doc);
        } else {
          log.warn("Rerank 返回的 index 越界，跳过该命中: index={}, candidates={}",
              hit.index(), candidates.size());
        }
      }
      // rerank 出结果太少时补齐剩余 RRF 候选
      if (reranked.size() < topK && reranked.size() < candidates.size()) {
        var rerankedTexts = reranked.stream().map(Document::getText).toList();
        for (var cand : candidates) {
          if (reranked.size() >= topK) break;
          if (!rerankedTexts.contains(cand.getText())) {
            reranked.add(cand);
          }
        }
      }
      return reranked;
    } catch (Exception e) {
      log.warn("Rerank 异常，降级使用 RRF 融合结果: {}", e.getMessage());
      return candidates;
    }
  }

  private static UUID safeParseUuid(String s) {
    if (s == null || s.isBlank()) return null;
    try { return UUID.fromString(s); }
    catch (IllegalArgumentException e) { return null; }
  }

  private static class RrfEntry {
    final UUID id;
    final String content;
    final Map<String, Object> metadata;
    double rrfScore = 0.0;

    RrfEntry(UUID id, String content, Map<String, Object> metadata) {
      this.id = id; this.content = content; this.metadata = metadata;
    }
  }
}
