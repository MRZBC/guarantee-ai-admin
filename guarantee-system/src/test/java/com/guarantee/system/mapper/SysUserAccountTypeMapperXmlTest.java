package com.guarantee.system.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code SysUserMapper.xml} 的自检（T5-06）。
 *
 * <p><b>为什么值得单独测</b>：XML 不参与编译。V10 给 {@code sys_user} 加了 {@code account_type}，
 * 但实体映射语句都是**显式列清单**——漏改一处的后果不是报错，而是
 * {@code getAccountType()} 永远返回 null（被归一成 HUMAN），服务账号强校验静默失效。
 * 这类缺陷不会让任何页面报错，只会让安全检查变成摆设。</p>
 *
 * <p>另外把"接口方法必须有对应语句"钉住：新增 {@code selectAccountTypeById} 却忘写
 * {@code <select>}，要等运行期第一次签发 MCP Token 才炸。</p>
 *
 * <p>本测试不需要数据库：只做 MyBatis 的语句注册与绑定检查。</p>
 */
class SysUserAccountTypeMapperXmlTest {

    private static final String XML_RESOURCE = "mapper/system/SysUserMapper.xml";
    private static final String NAMESPACE = "com.guarantee.system.mapper.SysUserMapper";

    /** 返回实体的语句：都必须把 account_type 映射成 accountType。 */
    private static final List<String> ENTITY_SELECTS = List.of(
            "selectEntityById",
            "selectEntitiesByIds",
            "selectByUsername",
            "selectEntityByIdIncludingDeleted");

    private static Configuration parsed() throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream in = Resources.getResourceAsStream(XML_RESOURCE)) {
            assertThat(in).as("类路径上必须存在 %s", XML_RESOURCE).isNotNull();
            new XMLMapperBuilder(in, configuration, XML_RESOURCE, configuration.getSqlFragments()).parse();
        }
        assertThat(configuration.hasMapper(SysUserMapper.class)).isTrue();
        return configuration;
    }

    private static String sqlOf(Configuration configuration, String statementId) {
        Map<String, Object> params = Map.of("id", 1L, "ids", List.of(1L), "username", "u");
        return configuration.getMappedStatement(statementId)
                .getBoundSql(params)
                .getSql()
                .replaceAll("\\s+", " ");
    }

    @Test
    @DisplayName("接口上每个方法都有对应语句（新增方法忘写 XML 会在这里红）")
    void everyMapperMethodHasStatement() throws Exception {
        Configuration configuration = parsed();
        for (Method method : SysUserMapper.class.getDeclaredMethods()) {
            String id = NAMESPACE + "." + method.getName();
            assertThat(configuration.hasStatement(id))
                    .as("SysUserMapper.%s 没有对应的 Mapper 语句（XML 漏写了 <select>/<update>）", method.getName())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("四个返回实体的语句都映射了 account_type AS accountType（漏一处 → 强校验静默失效）")
    void entitySelectsMapAccountType() throws Exception {
        Configuration configuration = parsed();
        for (String id : ENTITY_SELECTS) {
            String sql = sqlOf(configuration, NAMESPACE + "." + id);
            assertThat(sql)
                    .as("%s 必须把 account_type 映射成 accountType", id)
                    .containsIgnoringCase("account_type AS accountType");
        }
    }

    @Test
    @DisplayName("机器身份语句不读 password，且显式过滤逻辑删除")
    void machineIdentityStatementsAreMinimalAndScoped() throws Exception {
        Configuration configuration = parsed();

        String accountTypeSql = sqlOf(configuration, NAMESPACE + ".selectAccountTypeById");
        assertThat(accountTypeSql).containsIgnoringCase("account_type");
        assertThat(accountTypeSql).doesNotContainIgnoringCase("password");
        assertThat(accountTypeSql).containsIgnoringCase("is_deleted = 0");

        String identitySql = sqlOf(configuration, NAMESPACE + ".selectIdentityById");
        assertThat(identitySql).containsIgnoringCase("account_type AS accountType");
        assertThat(identitySql).containsIgnoringCase("status");
        assertThat(identitySql).containsIgnoringCase("is_deleted = 0");
        // 机器链路没有任何理由拿到人类凭据散列
        assertThat(identitySql).doesNotContainIgnoringCase("password");
    }
}
