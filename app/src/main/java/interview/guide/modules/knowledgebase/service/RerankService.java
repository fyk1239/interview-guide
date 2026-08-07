package interview.guide.modules.knowledgebase.service;

import java.util.List;

/**
 * 重排服务接口——对粗排后的候选文档做精排。
 *
 * <h3>原理</h3>
 * 粗排（向量+BM25 混合）追求高召回 → 候选多、含噪音。
 * 精排（cross-encoder）追求高精度 → query 和每个候选拼成一对打分，
 * 从粗排 top-N 里挑最相关的 top-K 喂给 LLM。
 */
public interface RerankService {

  /**
   * 对候选文档重排序。
   *
   * @param query     用户原始查询
   * @param documents 候选文档文本列表
   * @param topN      返回前 top-N 个相关文档
   * @return 重排结果（按相关性分降序）
   */
  List<RerankHit> rerank(String query, List<String> documents, int topN);
}
