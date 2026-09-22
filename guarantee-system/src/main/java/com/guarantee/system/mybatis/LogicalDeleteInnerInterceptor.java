package com.guarantee.system.mybatis;

import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 逻辑删除查询过滤拦截器（设计文档 §5.2）。
 *
 * <table>
 *   <caption>设计要点</caption>
 *   <tr><td>拦截点</td><td>{@code StatementHandler#prepare}，只处理 {@code SELECT}</td></tr>
 *   <tr><td>行为</td><td>解析最外层 FROM/JOIN，为 18 张受管表注入 {@code 别名.is_deleted = 0}</td></tr>
 *   <tr><td>豁免</td><td>Mapper 方法名以 {@code IncludingDeleted} 结尾（"显示已删除"与恢复读取）</td></tr>
 *   <tr><td>已有条件</td><td>SQL 中已显式出现 {@code is_deleted} 条件时跳过（避免重复条件）</td></tr>
 *   <tr><td>解析失败</td><td><b>抛出异常，绝不静默放行</b>——静默放行 = 已删除数据泄漏</td></tr>
 *   <tr><td>开关</td><td>{@code guarantee.logical-delete.enabled}（默认 true），供批次 3 出问题时快速关闭</td></tr>
 *   <tr><td>性能</td><td>{@code ConcurrentHashMap} 缓存"SQL 文本 → 改写结果"</td></tr>
 * </table>
 *
 * <p><b>为什么失败要抛异常</b>：本拦截器的职责是"让已删除数据不可见"。一旦解析出的结果
 * 不确定，放行就等于把已删除数据（含被删角色对应的权限）暴露给业务与鉴权链路；
 * 报错只会让这一次请求失败，代价小得多（LD-R5）。</p>
 */
@Intercepts(@Signature(type = StatementHandler.class, method = "prepare",
        args = {Connection.class, Integer.class}))
@Component
public class LogicalDeleteInnerInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(LogicalDeleteInnerInterceptor.class);

    /** 方法名豁免后缀：用于"显示已删除"列表与恢复前的读取（设计 §5.2）。 */
    public static final String EXEMPT_SUFFIX = "IncludingDeleted";

    /** SQL 模板缓存上限：动态条件会放大 SQL 文本种类，超限后不再缓存（只影响性能）。 */
    private static final int CACHE_LIMIT = 4096;

    private final boolean enabled;
    private final Map<String, String> sqlCache = new ConcurrentHashMap<>();

    public LogicalDeleteInnerInterceptor(
            @Value("${guarantee.logical-delete.enabled:true}") boolean enabled) {
        this.enabled = enabled;
        if (enabled) {
            log.info("逻辑删除查询过滤拦截器已启用（受管表 {} 张，豁免后缀 {}，可用 "
                    + "guarantee.logical-delete.enabled=false 关闭）",
                    LogicalDeleteTables.MANAGED.size(), EXEMPT_SUFFIX);
        } else {
            log.warn("逻辑删除查询过滤拦截器已被配置关闭（guarantee.logical-delete.enabled=false）："
                    + "已删除数据将出现在默认查询结果中，请仅用于故障应急");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        if (!enabled) {
            return invocation.proceed();
        }
        StatementHandler handler = (StatementHandler) invocation.getTarget();
        MetaObject metaObject = SystemMetaObject.forObject(handler);
        MappedStatement mappedStatement =
                (MappedStatement) metaObject.getValue("delegate.mappedStatement");
        if (mappedStatement == null
                || mappedStatement.getSqlCommandType() != SqlCommandType.SELECT
                || isExempt(mappedStatement.getId())) {
            return invocation.proceed();
        }
        BoundSql boundSql = handler.getBoundSql();
        String sql = boundSql.getSql();
        if (sql == null || sql.isEmpty()) {
            return invocation.proceed();
        }
        String rewritten = cachedRewrite(sql);
        if (!rewritten.equals(sql)) {
            // BoundSql 没有 setSql 访问器，用 MyBatis 反射工具写私有字段（与 mybatis-plus 同做法）
            SystemMetaObject.forObject(boundSql).setValue("sql", rewritten);
            if (log.isDebugEnabled()) {
                log.debug("注入逻辑删除过滤 [{}]: {} -> {}", mappedStatement.getId(),
                        LogicalDeleteSqlRewriter.describe(sql),
                        LogicalDeleteSqlRewriter.describe(rewritten));
            }
        }
        return invocation.proceed();
    }

    private String cachedRewrite(String sql) {
        String cached = sqlCache.get(sql);
        if (cached != null) {
            return cached;
        }
        String rewritten;
        try {
            rewritten = LogicalDeleteSqlRewriter.rewrite(sql);
        } catch (RuntimeException ex) {
            // 明确失败而不是静默放行：放行会让已删除数据泄漏（LD-R5）
            throw new IllegalStateException(
                    "逻辑删除过滤注入失败，已拒绝执行该查询（请检查 SQL 或临时设置 "
                            + "guarantee.logical-delete.enabled=false）："
                            + LogicalDeleteSqlRewriter.describe(sql), ex);
        }
        if (sqlCache.size() < CACHE_LIMIT) {
            sqlCache.put(sql, rewritten);
        }
        return rewritten;
    }

    /** 豁免判定：Mapper 方法名以 IncludingDeleted 结尾。 */
    static boolean isExempt(String mappedStatementId) {
        if (mappedStatementId == null) {
            return false;
        }
        int dot = mappedStatementId.lastIndexOf('.');
        String methodName = dot < 0 ? mappedStatementId : mappedStatementId.substring(dot + 1);
        return methodName.endsWith(EXEMPT_SUFFIX);
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {
        // 无额外属性：开关走 Spring 配置项，便于运维单点调整
    }
}
