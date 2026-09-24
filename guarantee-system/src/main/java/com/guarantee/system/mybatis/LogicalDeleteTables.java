package com.guarantee.system.mybatis;

import java.util.Locale;
import java.util.Set;

/**
 * 逻辑删除受管表清单（设计文档 §5.2「受管表」）。
 *
 * <p>共 19 张表（18 张业务/配置表 + {@code sys_region} 行政区划字典）。集中在一处是为了让
 * "新增表时忘记登记"变成一次显式修改，而不是散落在拦截器的正则里。</p>
 *
 * <p><b>唯一例外：{@code ai_operation_secret} 不在清单内</b>（LD-EX-01 / 设计 §7.3a）。
 * 该表的存在目的就是缩短手机号等敏感参数密文的存储窗口（15 分钟），
 * 逻辑删除会让密文永久滞留，直接违反 D-4 / SYS-A-02b。它保持物理删除，
 * 因此也**不能**被拦截器注入 {@code is_deleted = 0}——那会因为列不存在而报 SQL 错误。</p>
 */
public final class LogicalDeleteTables {

    /** 19 张受管表（表名小写）。 */
    public static final Set<String> MANAGED = Set.of(
            "sys_org",
            "sys_department",
            "sys_user",
            "sys_role",
            "sys_permission",
            "sys_user_role",
            "sys_role_permission",
            // 行政区划字典：与 sys_permission 同属"基础数据"，同样走逻辑删除
            // （地区是启用/停用为主，删除只用于彻底下架一个区划码；已删除的地区不出现在下拉里，
            //   但业务数据里的 region_code 仍按字符串保留，所以删除不会破坏历史订单）
            "sys_region",
            "insurance_type",
            "enterprise",
            "project",
            "tender_order",
            "performance_order",
            "ai_conversation",
            "ai_message",
            "ai_tool_call",
            "ai_audit_log",
            "ai_operation_proposal",
            "ai_operation_audit");

    /** 明确不加字段、保持物理删除的表（LD-EX-01）。 */
    public static final String PHYSICAL_DELETE_EXCEPTION = "ai_operation_secret";

    private LogicalDeleteTables() {
    }

    public static boolean isManaged(String tableName) {
        return tableName != null && MANAGED.contains(tableName.toLowerCase(Locale.ROOT));
    }
}
