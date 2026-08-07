package interview.guide.modules.knowledgebase.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.ai.rag")
public class KnowledgeBaseQueryProperties {

    private Rewrite rewrite = new Rewrite();
    private Search search = new Search();
    private History history = new History();
    private String systemPromptPath = "classpath:prompts/knowledgebase-query-system.st";
    private String userPromptPath = "classpath:prompts/knowledgebase-query-user.st";
    private String rewritePromptPath = "classpath:prompts/knowledgebase-query-rewrite.st";

    @Data
    public static class Rewrite {
        private boolean enabled = true;
    }

    @Data
    public static class Search {
        private int shortQueryLength = 4;
        private int topkShort = 20;
        private int topkMedium = 12;
        private int topkLong = 8;
        private double minScoreShort = 0.25;
        private double minScoreDefault = 0.28;
    }

    @Data
    public static class History {
        private boolean enabled = true;
        private int maxMessages = 10;
    }

    /** 分块配置 */
    private Chunk chunk = new Chunk();

    /** 混合检索配置 */
    private Hybrid hybrid = new Hybrid();

    /** 重排序配置 */
    private Rerank rerank = new Rerank();

    @Data
    public static class Chunk {
        /** 每个 chunk 的目标 token 数（TokenTextSplitter 默认 800） */
        private int defaultChunkSize = 500;
        /** 相邻 chunk 的重叠 token 数 */
        private int overlap = 50;
    }

    @Data
    public static class Hybrid {
        private boolean enabled = false;
        /** 向量路召回 top-K */
        private int topKVector = 20;
        /** FTS 路召回 top-K */
        private int topKFts = 20;
        /** 融合后候选数量上限（送入 rerank 或 LLM 之前） */
        private int candidateCap = 30;
        /** 融合方式：rrf | weighted */
        private String fusion = "rrf";
        /** RRF 的 k 参数（rank 平滑项） */
        private int rrfK = 60;
        /** 加权融合时向量路权重（rrf 模式忽略） */
        private double vectorWeight = 0.5;
        /** 加权融合时 FTS 路权重（rrf 模式忽略） */
        private double ftsWeight = 0.5;
    }

    @Data
    public static class Rerank {
        private boolean enabled = false;
        private String model = "qwen3-rerank";
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-api/v1";
        /** API Key，默认复用 AI_BAILIAN_API_KEY */
        private String apiKey = null;
        /** 精排后返回 top-N */
        private int topN = 10;
        /** HTTP 超时秒数 */
        private int timeoutSeconds = 10;
        /** 低于此分的候选直接丢弃 */
        private double minRelevanceScore = 0.5;
    }
}
