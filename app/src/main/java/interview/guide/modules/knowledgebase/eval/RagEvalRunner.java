package interview.guide.modules.knowledgebase.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.modules.knowledgebase.service.HybridSearchService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryProperties;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorService;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * RAG 检索质量评测运行器——量化对比三种检索模式的召回效果。
 *
 * <h3>使用方式</h3>
 * <ol>
 *   <li>先将评测文档上传到知识库（如面试题综合文档），记下 kbId</li>
 *   <li>设置环境变量：
 *     <ul>
 *       <li>{@code APP_AI_RAG_HYBRID_ENABLED=true} —— 启用混合检索</li>
 *       <li>{@code APP_AI_RAG_RERANK_ENABLED=true} —— 启用重排序（需 API Key）</li>
 *       <li>{@code RAG_EVAL_KB_ID=1} —— 目标知识库 ID</li>
 *       <li>{@code RAG_EVAL_RUN=true} —— 触发评测（启动后自动运行并退出）</li>
 *     </ul>
 *   </li>
 *   <li>启动应用：{@code ./gradlew :app:bootRun}</li>
 *   <li>查看控制台输出的评测对比表</li>
 * </ol>
 *
 * <h3>评测指标</h3>
 * <ul>
 *   <li><b>Hit Rate@k</b>：top-k 中是否至少命中一个期望关键词——衡量"有用信息是否被召回"</li>
 *   <li><b>MRR</b>（Mean Reciprocal Rank）：第一个命中的排名的倒数均值——衡量"最佳答案排多靠前"</li>
 *   <li><b>Recall@k</b>：期望关键词在 top-k 文档中的覆盖比例——衡量"检索覆盖度"</li>
 * </ul>
 *
 * <h3>输出示例</h3>
 * <pre>{@code
 * ============================================================
 * RAG 检索质量评测报告
 * 评测模式              Hit Rate@5   MRR       Recall@5
 * ------------------------------------------------------------
 * 纯向量检索            72.0%        0.512     58.3%
 * 混合检索（无 Rerank） 86.0%        0.687     72.1%
 * 混合检索 + Rerank     90.0%        0.753     74.8%
 * ============================================================
 * }</pre>
 *
 * <p>与 {@link HybridSearchService} 共用相同的检索基础设施，结果直接反映生产管线质量。
 */
@Slf4j
@Component
@ConditionalOnExpression("${rag.eval.run:false}")
public class RagEvalRunner implements CommandLineRunner {

  /** classpath 资源路径（jar 打包后可访问，也作为 docs/ 源文件的打包副本） */
  private static final String QUESTIONS_PATH = "rag-eval/questions.json";
  /** 文件系统 fallback 路径（IDE 直接运行时） */
  private static final String QUESTIONS_FS_PATH = "docs/rag-eval/questions.json";
  private static final int TOP_K = 5;

  private final KnowledgeBaseVectorService vectorService;
  private final HybridSearchService hybridSearchService;
  private final KnowledgeBaseQueryProperties queryProperties;
  private final ObjectMapper objectMapper;

  public RagEvalRunner(
      KnowledgeBaseVectorService vectorService,
      HybridSearchService hybridSearchService,
      KnowledgeBaseQueryProperties queryProperties) {
    this.vectorService = vectorService;
    this.hybridSearchService = hybridSearchService;
    this.queryProperties = queryProperties;
    this.objectMapper = new ObjectMapper();
  }

  @Override
  public void run(String... args) {
    try {
      List<EvalQuestion> questions = loadQuestions();
      if (questions.isEmpty()) {
        log.warn("评测问题集为空，跳过评测");
        return;
      }

      Long kbId = resolveKbId();
      log.info("开始 RAG 检索质量评测: kbId={}, 问题数={}", kbId, questions.size());

      // 三种检索模式的配置
      EvalResult vectorOnly = evaluate("纯向量检索", questions, kbId,
          false, false);
      EvalResult hybridNoRerank = evaluate("混合检索（无 Rerank）", questions, kbId,
          true, false);
      EvalResult hybridWithRerank = evaluate("混合检索 + Rerank", questions, kbId,
          true, true);

      printReport(vectorOnly, hybridNoRerank, hybridWithRerank);

      // 评测完成后退出（-1 表示正常退出可由外部脚本捕获）
      log.info("评测完成，即将退出");
      System.exit(0);

    } catch (Exception e) {
      log.error("RAG 评测运行异常: {}", e.getMessage(), e);
      System.exit(1);
    }
  }

  private EvalResult evaluate(
      String label,
      List<EvalQuestion> questions,
      Long kbId,
      boolean hybridEnabled,
      boolean rerankEnabled) {

    queryProperties.getHybrid().setEnabled(hybridEnabled);
    queryProperties.getRerank().setEnabled(rerankEnabled);

    int total = questions.size();
    int hitCount = 0;
    double mrrSum = 0.0;
    double recallSum = 0.0;

    for (var q : questions) {
      List<Document> docs;
      try {
        if (hybridEnabled) {
          docs = hybridSearchService.hybridSearch(
              q.question(), List.of(kbId), TOP_K, 0.0, queryProperties);
        } else {
          docs = vectorService.similaritySearch(
              q.question(), List.of(kbId), TOP_K, 0.0);
        }
      } catch (Exception e) {
        log.warn("检索失败: q={}, error={}", q.id(), e.getMessage());
        docs = List.of();
      }

      // Hit Rate@k: 至少命中一个关键词
      boolean hit = false;
      double firstHitRank = 0;
      int matchedKeywords = 0;

      for (int rank = 0; rank < docs.size(); rank++) {
        String text = docs.get(rank).getText();
        if (text == null) continue;
        String lowerText = text.toLowerCase();

        for (var kw : q.expectedKeywords()) {
          if (lowerText.contains(kw.toLowerCase())) {
            matchedKeywords++;
            if (!hit) {
              hit = true;
              firstHitRank = rank + 1;
            }
          }
        }
      }

      if (hit) hitCount++;
      if (firstHitRank > 0) mrrSum += 1.0 / firstHitRank;
      if (!q.expectedKeywords().isEmpty()) {
        // Recall: 去重后命中的关键词数 / 总关键词数
        recallSum += (double) Math.min(matchedKeywords, q.expectedKeywords().size())
            / q.expectedKeywords().size();
      }
    }

    double hitRate = total > 0 ? (double) hitCount / total : 0;
    double mrr = total > 0 ? mrrSum / total : 0;
    double recall = total > 0 ? recallSum / total : 0;

    log.info("[{}] Hit Rate@{}: {:.1f}%, MRR: {:.3f}, Recall@{}: {:.1f}%",
        label, TOP_K, hitRate * 100, mrr, TOP_K, recall * 100);

    return new EvalResult(label, hitRate, mrr, recall, total);
  }

  private void printReport(EvalResult... results) {
    StringBuilder sb = new StringBuilder();
    sb.append("\n");
    sb.append("============================================================\n");
    sb.append("           RAG 检索质量评测报告 (top-").append(TOP_K).append(")\n");
    sb.append("------------------------------------------------------------\n");
    sb.append(String.format("%-22s  %-10s  %-8s  %-8s%n",
        "评测模式", "Hit Rate", "MRR", "Recall"));
    sb.append("------------------------------------------------------------\n");
    for (var r : results) {
      sb.append(String.format("%-22s  %5.1f%%      %.3f    %5.1f%%%n",
          r.label(), r.hitRate() * 100, r.mrr(), r.recall() * 100));
    }
    sb.append("============================================================\n");
    sb.append("说明: Hit Rate 越高 = 有用信息召回越全; MRR 越高 = 最佳答案排越靠前\n");
    sb.append("      共评测 ").append(results[0].total()).append(" 道问题，检索 top-").append(TOP_K).append(" 篇文档\n");

    // 同时输出到日志，确保在容器/Docker 日志中可见
    log.info(sb.toString());
    // 也输出到标准输出，方便重定向
    System.out.println(sb);
  }

  private List<EvalQuestion> loadQuestions() {
    try (InputStream in = getClass().getClassLoader()
        .getResourceAsStream(QUESTIONS_PATH)) {
      if (in == null) {
        // 尝试从文件系统加载（IDE 直接运行时 classpath 可能不包含资源）
        for (String fsPathStr : List.of(QUESTIONS_FS_PATH, QUESTIONS_PATH)) {
          java.nio.file.Path fsPath = java.nio.file.Path.of(
              System.getProperty("user.dir", "."), fsPathStr);
          if (java.nio.file.Files.exists(fsPath)) {
            String content = java.nio.file.Files.readString(fsPath);
            return parseQuestions(content);
          }
        }
        log.error("评测问题集未找到: {}（classpath 和文件系统均未找到）",
            QUESTIONS_PATH);
        return List.of();
      }
      String content = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      return parseQuestions(content);
    } catch (Exception e) {
      log.error("加载评测问题集失败: {}", e.getMessage(), e);
      return List.of();
    }
  }

  private List<EvalQuestion> parseQuestions(String json) throws Exception {
    Map<String, Object> root = objectMapper.readValue(json,
        new TypeReference<LinkedHashMap<String, Object>>() {});
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rawList = (List<Map<String, Object>>) root.get("questions");
    if (rawList == null) return List.of();

    List<EvalQuestion> questions = new ArrayList<>();
    for (var raw : rawList) {
      int id = (int) raw.get("id");
      String question = (String) raw.get("question");
      @SuppressWarnings("unchecked")
      List<String> keywords = (List<String>) raw.get("expectedKeywords");
      if (question != null && keywords != null) {
        questions.add(new EvalQuestion(id, question, keywords));
      }
    }
    return questions;
  }

  private Long resolveKbId() {
    String env = System.getenv("RAG_EVAL_KB_ID");
    if (env != null && !env.isBlank()) {
      return Long.parseLong(env);
    }
    String prop = System.getProperty("rag.eval.kb-id");
    if (prop != null && !prop.isBlank()) {
      return Long.parseLong(prop);
    }
    log.warn("未设置 RAG_EVAL_KB_ID，使用默认 kbId=1");
    return 1L;
  }

  /** 评测问题 */
  private record EvalQuestion(int id, String question, List<String> expectedKeywords) {}

  /** 评测结果 */
  private record EvalResult(
      String label, double hitRate, double mrr, double recall, int total) {}
}
