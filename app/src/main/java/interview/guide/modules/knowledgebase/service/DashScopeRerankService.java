package interview.guide.modules.knowledgebase.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * DashScope（百炼）重排服务——调用 {@code qwen3-rerank} 跨编码器精排。
 *
 * <h3>调用方式</h3>
 * 使用 OpenAI 兼容 HTTP 接口 {@code POST /compatible-api/v1/reranks}（非 provider 的
 * {@code compatible-mode} 路径），用 Bearer API Key 认证。
 *
 * <h3>降级链</h3>
 * <ul>
 *   <li>rerank 未配置/未启用 → 跳过重排，直接用粗排结果</li>
 *   <li>rerank API 不可用（429/超时/异常）→ 降级，沿用粗排结果</li>
 * </ul>
 */
@Slf4j
public class DashScopeRerankService implements RerankService {

  private final RestClient restClient;
  private final ObjectMapper objectMapper;
  private final String model;
  private final int timeoutSeconds;

  public DashScopeRerankService(String baseUrl, String apiKey, String model, int timeoutSeconds) {
    this.model = model;
    this.timeoutSeconds = timeoutSeconds;
    this.objectMapper = new ObjectMapper();

    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(Math.min(timeoutSeconds, 5)));
    factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));

    this.restClient = RestClient.builder()
        .requestFactory(factory)
        .baseUrl(baseUrl)
        .defaultHeader("Authorization", "Bearer " + apiKey)
        .defaultHeader("Content-Type", "application/json")
        .build();
  }

  @Override
  public List<RerankHit> rerank(String query, List<String> documents, int topN) {
    if (documents == null || documents.isEmpty()) {
      return List.of();
    }
    try {
      var requestBody = Map.of(
          "model", model,
          "query", query,
          "documents", documents,
          "top_n", Math.min(topN, documents.size())
      );
      String json = objectMapper.writeValueAsString(requestBody);

      var response = restClient.post()
          .uri("/reranks")
          .body(json)
          .retrieve()
          .body(RerankResponse.class);

      if (response == null || response.results() == null) {
        log.warn("Rerank API 返回空结果，query='{}'", query);
        return List.of();
      }

      List<RerankHit> hits = response.results().stream()
          .map(r -> new RerankHit(r.index(), r.relevanceScore(), r.document()))
          .toList();
      log.debug("Rerank 完成: query='{}', 候选{} → 重排top{}", query, documents.size(), hits.size());
      return hits;

    } catch (Exception e) {
      log.warn("Rerank API 调用失败，降级使用粗排结果: query='{}', error={}", query, e.getMessage());
      return List.of(); // 空结果 → 上层降级到粗排
    }
  }

  /** OpenAI 兼容 rerank 响应格式 */
  private record RerankResult(
      @JsonProperty("index") int index,
      @JsonProperty("relevance_score") double relevanceScore,
      @JsonProperty("document") String document) {
  }

  private record RerankResponse(
      @JsonProperty("results") List<RerankResult> results) {
  }
}
