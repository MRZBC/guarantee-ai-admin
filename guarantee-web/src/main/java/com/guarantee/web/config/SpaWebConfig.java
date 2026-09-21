package com.guarantee.web.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 把前端构建产物（{@code frontend/dist}）直接挂到后端，
 * 使 {@code http://localhost:<port>/} 就能打开管理后台，无需再单独启动 Vite。
 *
 * <p>前端使用 hash 路由（{@code /#/dashboard}），所有深链接都落在 {@code /} 上，
 * 因此服务端只需把 {@code /} 转发到 {@code index.html}，不需要 history fallback。</p>
 *
 * <p>开发时仍然推荐 {@code npm run dev}（5273，带 HMR），两种方式可以并存。
 * 找不到产物目录时不影响启动，只是首页会返回 404。</p>
 */
@Configuration(proxyBeanMethods = false)
public class SpaWebConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(SpaWebConfig.class);

    /** 显式指定的产物目录，优先级最高；留空则自动探测。 */
    private final String configuredLocation;

    public SpaWebConfig(@Value("${guarantee.web.spa-location:}") String configuredLocation) {
        this.configuredLocation = configuredLocation;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Optional<Path> spaDir = resolveSpaDir();
        if (spaDir.isEmpty()) {
            log.warn("未找到前端构建产物（frontend/dist/index.html），首页不可用。"
                    + "如需在浏览器打开后台，请先执行：cd frontend && npm run build");
            return;
        }
        Path dir = spaDir.get();
        // 注意必须用 /** 而不是 /assets/**：
        // ResourceHttpRequestHandler 是把「pattern 中 ** 匹配到的部分」拼到 location 上的，
        // 若写成 /assets/** + location=dist/ ，请求 /assets/x.js 会被解析成 dist/x.js 而 404。
        // 用 /** 则是标准做法：控制器（/api/**、/actuator/**）优先级更高，不会被这里抢走。
        registry.addResourceHandler("/**")
                .addResourceLocations(toResourceLocation(dir))
                .setCachePeriod(3600);
        log.info("已挂载前端静态资源 {} -> 浏览器访问 http://localhost:{}/",
                dir, System.getProperty("server.port", "8080"));
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        if (resolveSpaDir().isPresent()) {
            registry.addViewController("/").setViewName("forward:/index.html");
        }
    }

    /**
     * 自动探测产物目录。
     *
     * <p>不能只依赖 {@code ./frontend/dist}：{@code mvn spring-boot:run} 的工作目录是模块目录
     * （{@code guarantee-web/}），而 {@code java -jar} 通常是项目根目录，因此要逐个候选目录探测。</p>
     */
    private Optional<Path> resolveSpaDir() {
        List<Path> candidates = new ArrayList<>();
        if (configuredLocation != null && !configuredLocation.isBlank()) {
            candidates.add(Path.of(stripFilePrefix(configuredLocation)));
        }
        Path cwd = Path.of(System.getProperty("user.dir"));
        candidates.add(cwd.resolve("frontend").resolve("dist"));
        candidates.add(cwd.resolve("..").resolve("frontend").resolve("dist"));
        candidates.add(cwd.resolve("..").resolve("..").resolve("frontend").resolve("dist"));

        for (Path candidate : candidates) {
            Path normalized = candidate.normalize().toAbsolutePath();
            if (Files.isRegularFile(normalized.resolve("index.html"))) {
                return Optional.of(normalized);
            }
        }
        return Optional.empty();
    }

    private static String stripFilePrefix(String location) {
        return location.startsWith("file:") ? location.substring("file:".length()) : location;
    }

    /** Spring 的资源位置必须是 {@code file:.../} 形式（目录结尾带斜杠）。 */
    private static String toResourceLocation(Path dir) {
        String path = dir.toString().replace('\\', '/');
        return path.endsWith("/") ? "file:" + path : "file:" + path + "/";
    }
}
