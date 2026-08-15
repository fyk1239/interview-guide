package interview.guide.common.config;

import interview.guide.common.ai.CachingEmbeddingModel;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.infrastructure.redis.RedisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration
@Slf4j
public class LlmEmbeddingConfig {

  private final RedisService redisService;
  private final ObjectMapper objectMapper;

  public LlmEmbeddingConfig(RedisService redisService, ObjectMapper objectMapper) {
    this.redisService = redisService;
    this.objectMapper = objectMapper;
  }

  @Bean
  public EmbeddingModel embeddingModel(
      LlmProviderRegistry registry,
      @Value("${app.ai.embedding-cache.enabled:true}") boolean embeddingCacheEnabled) {
    EmbeddingModel delegate = new EmbeddingModel() {
      @Override
      public EmbeddingResponse call(EmbeddingRequest request) {
        return registry.getDefaultEmbeddingModel().call(request);
      }

      @Override
      public float[] embed(Document document) {
        return registry.getDefaultEmbeddingModel().embed(document);
      }
    };
    if (embeddingCacheEnabled) {
      log.info("EmbeddingModel bean initialized as registry delegate with Redis cache");
      return new CachingEmbeddingModel(delegate, redisService, objectMapper, true);
    }
    log.info("EmbeddingModel bean initialized as registry delegate (cache disabled)");
    return delegate;
  }
}
