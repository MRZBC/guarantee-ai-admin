package com.guarantee.ai.knowledge;

import com.guarantee.ai.knowledge.mapper.AiKnowledgeImportLogMapper;
import com.guarantee.ai.knowledge.mapper.AiKnowledgeItemMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mapper XML 的自检：XML 能解析、命名空间正确、且**接口上每个方法都有对应语句**。
 *
 * <p>为什么值得单独测：MyBatis 的 XML 不参与编译。XML 里方法名写错一个字母、
 * 忘了写某个 {@code <select>}、或者 {@code <include refid>} 指向不存在的片段，
 * 都要等到<b>运行期第一次调用</b>才炸——而且往往炸在用户按了发送之后。
 * 这里在 {@code mvn test} 阶段就把它们变成红灯。</p>
 *
 * <p>本测试不需要数据库：只做 MyBatis 的表单注册与绑定检查。</p>
 */
class KnowledgeMapperXmlTest {

    private static final List<String> XML_RESOURCES = List.of(
            "mapper/knowledge/AiKnowledgeItemMapper.xml",
            "mapper/knowledge/AiKnowledgeImportLogMapper.xml");

    @Test
    @DisplayName("两个 XML 都能解析，且命名空间与接口一一对应")
    void xmlResourcesParse() throws Exception {
        Configuration configuration = new Configuration();

        for (String resource : XML_RESOURCES) {
            try (InputStream in = Resources.getResourceAsStream(resource)) {
                assertThat(in).as("类路径上必须存在 %s（检查 mapper-locations 目录）", resource).isNotNull();
                new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }

        assertThat(configuration.hasMapper(AiKnowledgeItemMapper.class)).isTrue();
        assertThat(configuration.hasMapper(AiKnowledgeImportLogMapper.class)).isTrue();
    }

    @Test
    @DisplayName("接口方法必须有对应语句：新增方法忘记写 XML 会在这里红")
    void everyMapperMethodHasStatement() throws Exception {
        Configuration configuration = new Configuration();
        for (String resource : XML_RESOURCES) {
            try (InputStream in = Resources.getResourceAsStream(resource)) {
                assertThat(in).isNotNull();
                new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }

        assertEveryMethodBound(configuration, AiKnowledgeItemMapper.class);
        assertEveryMethodBound(configuration, AiKnowledgeImportLogMapper.class);
    }

    private static void assertEveryMethodBound(Configuration configuration, Class<?> mapperType) {
        for (Method method : mapperType.getDeclaredMethods()) {
            String statementId = mapperType.getName() + "." + method.getName();
            assertThat(configuration.hasStatement(statementId))
                    .as("%s 在 XML 里缺少对应语句（MyBatis 只在运行期才报这个错）", statementId)
                    .isTrue();
        }
    }
}
