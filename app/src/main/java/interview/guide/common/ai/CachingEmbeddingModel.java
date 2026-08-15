package interview.guide.common.ai;

import interview.guide.infrastructure.redis.RedisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Embedding 结果缓存包装器。
 *
 * <p>对相同文本（按内容 SHA-256 哈希）的 Embedding 请求命中缓存后直接返回，
 * 避免重复调用厂商 Embedding API 产生计费：
 * <ul>
 *   <li>文档侧：重新向量化同一文档（或不同知识库上传相同内容）时，chunk 文本不变 → 全部命中缓存</li>
 *   <li>查询侧：常见问题重复提问时复用同一向量</li>
 * </ul>
 *
 * <p>缓存键 = {@code embedding:cache:v1:{sha256(text)}}，值为 float[] 的 JSON 数组。
 * 注意：切换 Embedding 模型后语义可能不同，如需强制刷新可删除该前缀的 Redis 键。
 */
@Slf4j
public class CachingEmbeddingModel implements EmbeddingModel {

    private static final String KEY_PREFIX = "embedding:cache:v1:";

    private final EmbeddingModel delegate;
    private final RedisService redisService;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private volatile int hitCount;
    private volatile int missCount;

    public CachingEmbeddingModel(EmbeddingModel delegate, RedisService redisService,
                                 ObjectMapper objectMapper, boolean enabled) {
        this.delegate = delegate;
        this.redisService = redisService;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        if (!enabled || request.getInstructions().isEmpty()) {
            return delegate.call(request);
        }
        List<String> texts = request.getInstructions();
        List<float[]> cached = new ArrayList<>(texts.size());
        List<Integer> missIndexes = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            float[] hit = getCached(texts.get(i));
            if (hit != null) {
                cached.add(hit);
            } else {
                cached.add(null);
                missIndexes.add(i);
            }
        }
        if (!missIndexes.isEmpty()) {
            List<String> missTexts = missIndexes.stream().map(texts::get).toList();
            EmbeddingResponse response = delegate.call(new EmbeddingRequest(missTexts, request.getOptions()));
            List<Embedding> missResults = response.getResults();
            for (int j = 0; j < missIndexes.size(); j++) {
                int originalIndex = missIndexes.get(j);
                float[] vector = missResults.get(j).getOutput();
                cached.set(originalIndex, vector);
                putCached(texts.get(originalIndex), vector);
            }
        }
        List<Embedding> results = new ArrayList<>(cached.size());
        for (int i = 0; i < cached.size(); i++) {
            results.add(new Embedding(cached.get(i), i));
        }
        return new EmbeddingResponse(results);
    }

    @Override
    public float[] embed(Document document) {
        if (!enabled) {
            return delegate.embed(document);
        }
        // 与底层实现保持一致：使用 Embedding 实际发送的文本内容
        String content = delegate.getEmbeddingContent(document);
        float[] hit = getCached(content);
        if (hit != null) {
            return hit;
        }
        float[] vector = delegate.embed(document);
        putCached(content, vector);
        return vector;
    }

    public int getHitCount() {
        return hitCount;
    }

    public int getMissCount() {
        return missCount;
    }

    private float[] getCached(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            String json = redisService.get(cacheKey(text));
            if (json != null) {
                hitCount++;
                return objectMapper.readValue(json, float[].class);
            }
        } catch (Exception e) {
            log.warn("Embedding 缓存读取失败，降级直连 API: {}", e.getMessage());
        }
        missCount++;
        return null;
    }

    private void putCached(String text, float[] vector) {
        if (text == null || text.isBlank()) {
            return;
        }
        try {
            redisService.set(cacheKey(text), objectMapper.writeValueAsString(vector));
        } catch (JacksonException e) {
            log.warn("Embedding 缓存写入失败: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("Embedding 缓存写入失败: {}", e.getMessage());
        }
    }

    private String cacheKey(String text) {
        return KEY_PREFIX + sha256(text);
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
