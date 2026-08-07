package interview.guide.common.nlp;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 基于 jieba 的中文分词器。
 *
 * <p>纯 Java 实现（Trie 词图 + HMM/Viterbi），无 native 依赖，线程安全。
 *
 * <h3>原理</h3>
 * <ul>
 *   <li>索引侧（入库 FTS 列）用 {@link JiebaSegmenter.SegMode#INDEX}——更细粒度，保证召回率</li>
 *   <li>查询侧用 {@link JiebaSegmenter.SegMode#SEARCH}——更短 token，匹配更精确</li>
 *   <li>分词结果空格连接，PG 用 {@code simple} parser 按空格切 token → 等价于中文分词后全文检索</li>
 * </ul>
 *
 * <h3>降级</h3>
 * 如果 jieba 库在某 Java 版本下异常，可替换为空白分词实现——英文/技术术语仍然有效。
 */
@Slf4j
@Component
public class ChineseTextTokenizer implements TextTokenizer {

  private final JiebaSegmenter segmenter;

  public ChineseTextTokenizer() {
    this.segmenter = new JiebaSegmenter();
    log.info("jieba 中文分词器已初始化（词典加载完成）");
  }

  /**
   * 索引侧分词——细粒度，用于入库写 {@code fts_text}。
   */
  @Override
  public String tokenize(String text) {
    return tokenize(text, JiebaSegmenter.SegMode.INDEX);
  }

  /**
   * 查询侧分词——短 token，用于检索时生成 query。
   */
  public String tokenizeForSearch(String text) {
    return tokenize(text, JiebaSegmenter.SegMode.SEARCH);
  }

  private String tokenize(String text, JiebaSegmenter.SegMode mode) {
    if (text == null || text.isBlank()) {
      return "";
    }
    try {
      return segmenter.process(text, mode).stream()
          .map(SegToken::getWord)
          .collect(Collectors.joining(" "));
    } catch (Exception e) {
      // 极端情况降级：保留字母/数字/连续汉字作为 token
      log.warn("jieba 分词异常，降级为简易分词: {}", e.getMessage());
      return fallbackTokenize(text);
    }
  }

  /**
   * 简易降级分词：保留 ASCII 字母数字、以及连续非空白字符作为 token。
   * 保证英文本语和技术术语在极端情况仍可检索。
   */
  private String fallbackTokenize(String text) {
    StringBuilder sb = new StringBuilder();
    boolean inToken = false;
    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i);
      if (Character.isWhitespace(ch)) {
        if (inToken) {
          sb.append(' ');
          inToken = false;
        }
      } else {
        sb.append(ch);
        inToken = true;
      }
    }
    return sb.toString().trim();
  }
}
