package com.guarantee.ai.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 知识真源加载器：扫描 classpath 上的 {@code knowledge/**&#47;*.md} 并解析。
 *
 * <p>真源随 jar 发布（REQ-RAG-02），因此这里读的是 classpath 资源而不是文件系统路径；
 * 解析失败的单个文件**不抛出**，而是收集成 {@link Failure} 由调用方记录——
 * 一个写坏的真源文件不应该让整个应用起不来（RK-RAG-08）。</p>
 *
 * <p>同时返回 {@code discoveredPaths}（本次实际发现的全部真源路径）：导入器需要它来判断
 * "某个条目最后一次导入的来源文件是否还存在"。注意**解析失败的文件仍算"存在"**——
 * 否则一次笔误就会把一条知识静默停用。</p>
 */
@Component
public class KnowledgeSourceLoader {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSourceLoader.class);

    /** 真源位置（REQ-RAG-02）。 */
    public static final String LOCATION_PATTERN = "classpath*:knowledge/**/*.md";

    private final ResourcePatternResolver resolver;

    /**
     * 生产用构造器：使用默认的 classpath 解析器（线程上下文类加载器，
     * 目录与 jar 两种形态都能解析）。
     */
    public KnowledgeSourceLoader() {
        this(new PathMatchingResourcePatternResolver());
    }

    /** 测试用构造器：可传入自定义解析器（例如指向测试资源目录）。 */
    public KnowledgeSourceLoader(ResourcePatternResolver resolver) {
        this.resolver = resolver;
    }

    /** 加载结果：解析成功的文档 + 解析失败清单 + 发现到的真源路径。 */
    public record LoadResult(List<KnowledgeDocument> documents,
                             List<Failure> failures,
                             List<String> discoveredPaths) {

        public int documentCount() {
            return documents.size();
        }

        public int failureCount() {
            return failures.size();
        }
    }

    /** 单个文件的解析失败（不阻断其余文件）。 */
    public record Failure(String sourcePath, String reason) {
    }

    public LoadResult load() {
        List<KnowledgeDocument> documents = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        Set<String> paths = new TreeSet<>();

        Resource[] resources;
        try {
            resources = resolver.getResources(LOCATION_PATTERN);
        } catch (IOException ex) {
            // 连扫描都失败：按"知识层不可用"处理，不抛出（启动不能被知识层拖垮）
            log.error("扫描知识真源失败：{}", ex.getMessage(), ex);
            return new LoadResult(List.of(), List.of(new Failure(LOCATION_PATTERN, "扫描失败：" + ex.getMessage())), List.of());
        }

        for (Resource resource : resources) {
            String path = logicalPath(resource);
            paths.add(path);
            try {
                String text = resource.getContentAsString(StandardCharsets.UTF_8);
                documents.add(KnowledgeDocumentParser.parse(text, path));
            } catch (KnowledgeParseException ex) {
                failures.add(new Failure(path, ex.getMessage()));
            } catch (IOException | RuntimeException ex) {
                failures.add(new Failure(path, path + "：读取失败 —— " + ex.getMessage()));
            }
        }

        documents.sort(Comparator.comparing(KnowledgeDocument::knowledgeNo));
        return new LoadResult(List.copyOf(documents), List.copyOf(failures), List.copyOf(paths));
    }

    /**
     * 把资源 URL 归一成稳定的 classpath 相对路径，例如
     * {@code knowledge/SYSTEM/KB-SYSTEM-0001-org-hierarchy.md}。
     *
     * <p>留痕里记的就是这个路径（目录运行与 jar 运行结果一致），
     * 因此"文件是否还存在"的比较不会因为打包形态不同而误判。</p>
     */
    static String logicalPath(Resource resource) {
        try {
            String url = resource.getURL().toString();
            int idx = url.indexOf("knowledge/");
            if (idx >= 0) {
                return url.substring(idx);
            }
        } catch (IOException ignored) {
            // 取不到 URL 时退回文件名（只影响留痕可读性，不影响导入正确性）
        }
        String filename = resource.getFilename();
        return filename == null ? resource.getDescription() : "knowledge/" + filename;
    }
}
