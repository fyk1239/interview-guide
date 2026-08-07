package interview.guide.common.nlp;

/**
 * 文本分词器接口。
 *
 * <p>为中文全文检索提供统一的分词抽象，默认 jieba 实现，也可切换或降级为空白分词。
 */
public interface TextTokenizer {

  /**
   * 对文本进行分词，返回 token 列表。
   *
   * @param text 原始文本（中文/英文混合）
   * @return 空格连接的 token 串，用于 PG {@code to_tsvector('simple', ...)} 建索引
   */
  String tokenize(String text);
}
