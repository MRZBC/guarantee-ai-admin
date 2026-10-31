package com.guarantee.web.ai;

import com.guarantee.ai.tool.AiToolContextKeys;
import com.guarantee.ai.tool.RoleQueryTool;
import com.guarantee.web.GuaranteeAiAdminApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

/** 实际工具→Service→Mapper→数据库，事务回滚测试角色。无模型调用。 */
@SpringBootTest(classes = {GuaranteeAiAdminApplication.class, ProposalFlowIT.StubChatModelConfig.class},
        properties = {"guarantee.data-init.enabled=false", "guarantee.ai.proposal-expire-interval-ms=3600000"})
@Transactional
class RoleQueryAcceptanceIT {
    @Autowired RoleQueryTool tool;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;

    @Test
    void codeOrNameMatchesAndOtherFiltersStillRestrict() {
        String code = "ACCEPT_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String name = "独立名称" + code.substring(7);
        jdbc.update("INSERT INTO sys_role(role_code, role_name, status) VALUES (?,?,1)", code, name);
        ToolContext context = new ToolContext(Map.of(AiToolContextKeys.PERMISSIONS,
                List.of("system:role:view", "system:role:delete"), AiToolContextKeys.ROLES, List.of("ADMIN")));
        assertThat(tool.queryRole("ROLE", code, null, null, false, 20, context).total()).isEqualTo(1);
        assertThat(tool.queryRole("ROLE", name, null, null, false, 20, context).total()).isEqualTo(1);
        assertThat(tool.queryRole("ROLE", name, code, 1, false, 20, context).total()).isEqualTo(1);
        assertThat(tool.queryRole("ROLE", name, code, 0, false, 20, context).total()).isZero();
        jdbc.update("UPDATE sys_role SET is_deleted=1 WHERE role_code=?", code);
        // JDBC 更新绕过 MyBatis；清除本事务先前查询的一级缓存。
        sqlSession.clearCache();
        assertThat(tool.queryRole("ROLE", code, null, null, false, 20, context).total()).isZero();
        assertThat(tool.queryRole("ROLE", code, null, null, true, 20, context).total()).isEqualTo(1);
    }
}
