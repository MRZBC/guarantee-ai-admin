package com.guarantee.ai.tool;

import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 本轮对话「工具事实」的收集器（提示词口径行与提案编号的服务端唯一真值来源）。
 *
 * <p><b>为什么要有它</b>：口径行与提案编号原本都靠"要求模型逐字照抄工具返回值"，
 * 而模型编造这两样东西已经被真机复现过多次（假编号、假口径），事后的
 * {@code ProposalClaimGuard} / {@code DataSourceClaimGuard} 只是**话术启发式**兜底：
 * 抓得住"零工具却说已生成提案"，抓不住"会话里有一条 PENDING 但正文引用了另一个编号"。
 * 根治办法是让服务端掌握真值：每次工具调用返回时把 {@code dataSource} 与真实编号收集起来，
 * 收尾时由服务端生成口径、并按白名单校验正文里的编号。</p>
 *
 * <p><b>为什么在这里做提取而不是在每个工具里</b>：工具返回值是 record 序列化后的 JSON，
 * 只有 {@link AiToolCallRecorder} 这一个咽喉点能看到**全部**工具（含未来新增的）。
 * 在装饰器链上收集，新增工具自动纳管，不存在"某个工具忘了登记事实"的可能。</p>
 *
 * <p><b>提取规则（对嵌套结构递归）</b>：</p>
 * <ul>
 *   <li>任意层级上键名为 {@code dataSource} 的字符串值 → 口径；</li>
 *   <li>任意层级上键名为 {@code proposalNo} 的字符串值 → 真编号；</li>
 *   <li>任意字符串值里形如 {@link ProposalNoFormat} 的串 → 真编号（覆盖被包在
 *       summary / message 等自由文本里的情况）。</li>
 * </ul>
 *
 * <p><b>提取失败一律降级为"没有事实"</b>：结果被 16KB 上限截断成非法 JSON、
 * 或工具返回了非 JSON 文本时，只影响本轮口径与编号校验（模型正文里出现的编号会被判为
 * 不可信），绝不影响工具本身的执行与返回。</p>
 */
public final class TurnFacts {

    /** 一次工具返回值的提取结果。 */
    public record Facts(List<String> dataSources, Set<String> proposalNumbers) {

        static final Facts EMPTY = new Facts(List.of(), Set.of());

        public Facts {
            dataSources = List.copyOf(dataSources);
            proposalNumbers = Set.copyOf(proposalNumbers);
        }
    }

    private final List<String> dataSources = new ArrayList<>();
    private final Set<String> proposalNumbers = new LinkedHashSet<>();

    /**
     * 从工具返回值的原始 JSON 文本里提取事实。
     *
     * @param toolResultJson 工具返回值（可能为 null / 非 JSON / 被截断）
     * @param mapper         与工具层同一实例的 ObjectMapper
     */
    public static Facts extract(String toolResultJson, ObjectMapper mapper) {
        if (toolResultJson == null || toolResultJson.isBlank() || mapper == null) {
            return Facts.EMPTY;
        }
        try {
            return extractFrom(mapper.readValue(toolResultJson, Object.class));
        } catch (Exception ex) {
            // 截断 / 非 JSON：视为"本轮没有可采信的事实"，由调用方决定如何降级。
            return Facts.EMPTY;
        }
    }

    /** 从已解析的 JSON 树里提取事实（包级可见，便于单元测试直接喂 Map/List）。 */
    static Facts extractFrom(Object root) {
        if (root == null) {
            return Facts.EMPTY;
        }
        List<String> dataSources = new ArrayList<>();
        Set<String> proposalNumbers = new LinkedHashSet<>();
        walk(root, dataSources, proposalNumbers);
        return new Facts(dataSources, proposalNumbers);
    }

    private static void walk(Object node, List<String> dataSources, Set<String> proposalNumbers) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object value = entry.getValue();
                if ("dataSource".equals(key) && value instanceof String ds && !ds.isBlank()) {
                    if (!dataSources.contains(ds)) {
                        dataSources.add(ds);
                    }
                } else if ("proposalNo".equals(key) && value instanceof String no && !no.isBlank()) {
                    proposalNumbers.add(no.trim());
                }
                walk(value, dataSources, proposalNumbers);
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                walk(item, dataSources, proposalNumbers);
            }
        } else if (node instanceof String text) {
            proposalNumbers.addAll(ProposalNoFormat.findAll(text));
        }
    }

    /** 合并一次提取结果（线程安全：工具批量执行可能并发）。 */
    public synchronized void merge(Facts facts) {
        if (facts == null) {
            return;
        }
        for (String dataSource : facts.dataSources()) {
            if (dataSource != null && !dataSource.isBlank() && !dataSources.contains(dataSource)) {
                dataSources.add(dataSource);
            }
        }
        proposalNumbers.addAll(facts.proposalNumbers());
    }

    /** 本轮真实出现过的口径（按工具执行顺序去重）。 */
    public synchronized List<String> dataSources() {
        return List.copyOf(dataSources);
    }

    /** 本轮工具真实返回过的提案编号。 */
    public synchronized Set<String> proposalNumbers() {
        return Set.copyOf(proposalNumbers);
    }
}
