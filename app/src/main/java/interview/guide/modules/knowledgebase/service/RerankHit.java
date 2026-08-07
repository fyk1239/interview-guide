package interview.guide.modules.knowledgebase.service;

/**
 * 重排命中结果。
 */
public record RerankHit(
    /** 原始候选中第几个（0-based） */
    int index,
    /** 重排相关性分（0-1） */
    double relevanceScore,
    /** 候选文本 */
    String document) {
}
