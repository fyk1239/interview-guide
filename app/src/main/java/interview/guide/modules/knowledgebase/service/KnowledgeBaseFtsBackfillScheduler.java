package interview.guide.modules.knowledgebase.service;

import interview.guide.common.nlp.ChineseTextTokenizer;
import interview.guide.modules.knowledgebase.repository.VectorRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * FTS 文本回填调度器——渐进补齐历史向量数据的 {@code fts_text} 列。
 *
 * <p>每 30 秒处理一批（最多 50 行），兼容低配服务器。新入库数据在
 * {@link KnowledgeBaseVectorService#writeFtsTextForBatch} 中同步写入，
 * 本调度器只处理遗漏或旧数据。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeBaseFtsBackfillScheduler {

  private final VectorRepository vectorRepository;
  private final ChineseTextTokenizer tokenizer;

  private static final int BATCH_SIZE = 50;

  /**
   * 每 30 秒执行一次渐进回填。
   */
  @Scheduled(fixedDelay = 30_000)
  public void backfillMissingFts() {
    try {
      int totalMissing = vectorRepository.countMissingFts();
      if (totalMissing == 0) {
        return;
      }
      var rows = vectorRepository.findMissingFtsIdContentPairs(BATCH_SIZE);
      if (rows.isEmpty()) {
        return;
      }
      int updated = 0;
      for (var row : rows) {
        UUID id = (UUID) row[0];
        String content = (String) row[1];
        if (content == null || content.isBlank()) {
          continue;
        }
        String ftsText = tokenizer.tokenize(content);
        if (ftsText.isBlank()) {
          continue;
        }
        vectorRepository.updateFtsText(id, ftsText);
        updated++;
      }
      if (updated > 0) {
        log.info("FTS 回填完成: {} 行（剩余 {} 行）", updated, totalMissing - updated);
      }
    } catch (Exception e) {
      log.warn("FTS 回填调度器异常: {}", e.getMessage());
    }
  }
}
