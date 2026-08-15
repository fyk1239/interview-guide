package interview.guide.common.nlp;

import org.springframework.ai.transformer.splitter.TextSplitter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Markdown 结构化分块器。
 *
 * <p>替代纯长度切分（{@code TokenTextSplitter}），按 Markdown 标题切分语义节，
 * 保证同一主题尽量落在同一 chunk 内，避免主题被硬切到两个 chunk：
 * <ul>
 *   <li>按 {@code # ~ ######} 标题行切节，标题行作为节首（保证上下文完整）</li>
 *   <li>相邻小节贪心合并，尽量接近目标大小（字符数 ≈ chunkSize × 1.3）</li>
 *   <li>单节仍超长时按字符切分，并在切点处回退 overlap 字符（优先在标点/换行处断开）</li>
 *   <li>无标题结构的纯文本退化为按长度切分，行为与纯长度切块一致</li>
 * </ul>
 *
 * <p>度量说明：中文为主场景下 1 token ≈ 1.3 字符（实测 500 token ≈ 647 字符），
 * 因此以字符数近似 token 数控制分块粒度，避免依赖 tokenizer 依赖。
 */
public class MarkdownAwareTextSplitter extends TextSplitter {

    /** 中文为主场景下字符/token 换算系数（实测 500 token ≈ 647 字符） */
    private static final double CHARS_PER_TOKEN = 1.3;

    private static final Pattern HEADING_PATTERN = Pattern.compile("^#{1,6}\\s+");

    /** 目标 chunk 字符数 ≈ chunkSize(tokens) × 1.3 */
    private final int maxChars;
    /** 超长节切分时的重叠字符数 ≈ overlap(tokens) × 1.3 */
    private final int overlapChars;

    public MarkdownAwareTextSplitter(int chunkSize, int overlap) {
        this.maxChars = Math.max(64, (int) Math.round(chunkSize * CHARS_PER_TOKEN));
        this.overlapChars = Math.max(0, (int) Math.round(overlap * CHARS_PER_TOKEN));
    }

    @Override
    protected List<String> splitText(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> sections = splitByHeadings(text);
        List<String> merged = mergeSections(sections);
        List<String> result = new ArrayList<>();
        for (String section : merged) {
            if (section.length() <= maxChars) {
                result.add(section);
            } else {
                result.addAll(splitLongSection(section));
            }
        }
        return result.stream().filter(s -> !s.isBlank()).toList();
    }

    /**
     * 按 Markdown 标题行切节。标题行作为节首，无标题的前言作为第一个节。
     */
    private List<String> splitByHeadings(String text) {
        List<String> sections = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : text.split("\n")) {
            if (HEADING_PATTERN.matcher(line).find()) {
                if (current.length() > 0) {
                    sections.add(current.toString());
                }
                current = new StringBuilder(line);
            } else {
                if (current.length() > 0) {
                    current.append('\n');
                }
                current.append(line);
            }
        }
        if (current.length() > 0) {
            sections.add(current.toString());
        }
        return sections;
    }

    /**
     * 贪心合并相邻小节至目标大小。单节超长时保留在结果中，由后续超长切分处理。
     */
    private List<String> mergeSections(List<String> sections) {
        List<String> result = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        for (String section : sections) {
            if (buffer.length() > 0 && buffer.length() + 1 + section.length() > maxChars) {
                result.add(buffer.toString());
                buffer.setLength(0);
            }
            if (buffer.length() > 0) {
                buffer.append('\n');
            }
            buffer.append(section);
        }
        if (buffer.length() > 0) {
            result.add(buffer.toString());
        }
        return result;
    }

    /**
     * 超长节按字符切分，优先在换行/标点处断开，切点回退 overlap 字符。
     */
    private List<String> splitLongSection(String section) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        int minCut = maxChars * 2 / 3;
        while (start < section.length()) {
            int end = Math.min(start + maxChars, section.length());
            int cut = end;
            for (int i = end; i > start + minCut; i--) {
                char c = section.charAt(i - 1);
                if (c == '\n' || c == '。' || c == '；' || c == '，' || c == '！' || c == '？'
                    || c == '.' || c == ';' || c == ',' || c == ' ' || c == '、') {
                    cut = i;
                    break;
                }
            }
            String chunk = section.substring(start, cut).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            if (cut >= section.length()) {
                break;
            }
            start = Math.max(cut - overlapChars, start + 1);
        }
        return chunks;
    }
}
