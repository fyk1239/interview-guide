package interview.guide.modules.knowledgebase.config;

import interview.guide.modules.knowledgebase.service.DashScopeRerankService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryProperties;
import interview.guide.modules.knowledgebase.service.RerankService;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Rerank 服务配置——仅在 {@code app.ai.rag.rerank.enabled=true} 时创建 Bean。
 */
@Slf4j
@Configuration
public class RerankConfiguration {

  @Bean
  @ConditionalOnProperty(name = "app.ai.rag.rerank.enabled", havingValue = "true",
      matchIfMissing = false)
  public RerankService rerankService(KnowledgeBaseQueryProperties props) {
    var cfg = props.getRerank();
    String apiKey = cfg.getApiKey() != null ? cfg.getApiKey()
        : System.getenv("AI_BAILIAN_API_KEY");
    if (apiKey == null || apiKey.isBlank()) {
      log.warn("Rerank 已启用但未配置 API Key，将跳过重排");
      return (query, documents, topN) -> List.of();
    }
    log.info("Rerank 服务初始化: model={}, baseUrl={}", cfg.getModel(), cfg.getBaseUrl());
    return new DashScopeRerankService(cfg.getBaseUrl(), apiKey, cfg.getModel(),
        cfg.getTimeoutSeconds());
  }
}
