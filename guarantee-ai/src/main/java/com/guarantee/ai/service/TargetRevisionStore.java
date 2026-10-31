package com.guarantee.ai.service;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.Map;

/** 应用写入维护版本；页面与助手按同一目标行串行复核。 */
@Component
@DependsOn("dataSourceScriptDatabaseInitializer")
public class TargetRevisionStore implements InitializingBean {
    private final JdbcTemplate jdbc;
    public TargetRevisionStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void afterPropertiesSet() {
        Integer installed = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('sys_user','sys_role','sys_org','sys_department','insurance_type') AND COLUMN_NAME='mutation_version'", Integer.class);
        if (installed == null || installed != 5) throw new IllegalStateException("目标版本列未安装，请执行 V14__target_revision.sql 后启动");
    }

    public long lock(String type, Long id) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("目标版本锁必须处于事务中");
        String table = Map.of("USER", "sys_user", "ROLE", "sys_role", "ORG", "sys_org",
                "DEPT", "sys_department", "INSURANCE_TYPE", "insurance_type").get(type);
        if (table == null) throw new IllegalArgumentException("未知目标类型");
        var rows = jdbc.queryForList("SELECT mutation_version FROM " + table + " WHERE id=? FOR UPDATE", Long.class, id);
        return rows.isEmpty() ? -1L : rows.getFirst();
    }
}
