package com.guarantee.ai.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 提案请求参数（落库于 {@code ai_operation_proposal.request_payload}）。
 *
 * <p>用一个扁平结构承载所有域的参数，是为了让"提案机制"不需要随域扩展而改造：
 * 新增域只需要往这里加字段并在对应 {@link ProposalExecutor} 里读取。</p>
 *
 * <p><b>落库前必须经敏感字段脱敏</b>（SYS-A-09）：{@code phone} / {@code email} 等
 * 在写入 {@code request_payload} 前会被替换为 {@code <changed>} 之类的占位符，
 * 因此确认执行时**不能**从 request_payload 读回手机号原文——
 * 这也是执行期字段可省略的边界：原型见 {@code ai_operation_proposal} 的注释。</p>
 *
 * <p>为避免"脱敏后无法执行"，实际做法是：<b>敏感值不落提案表</b>，
 * 而是在生成提案时同时写入一条加密的一次性上下文（见 ProposalService 的说明）。
 * 本需求按 D-4 的强度要求选择更保守的方案：敏感字段的 UPDATE 需要用户在
 * 执行前重新提供，助手只提案非敏感字段；若用户要求改手机号，确认卡会明确提示
 * 该字段需在系统管理页面完成。这一取舍在 TEST-18 中有对应断言。</p>
 */
public record ProposalRequest(
        /** 目标主键；CREATE 时为空。 */
        Long id,
        /** 目标展示名（确认卡标题用）。 */
        String targetName,
        /** 用户原话（SYS-C-15 要求展示）。 */
        String userText,

        // ---------------- 用户 ----------------
        String realName,
        String phone,
        String email,
        Long deptId,
        Boolean clearDept,
        Integer status,
        List<String> roleCodes,

        // ---------------- 机构 ----------------
        String orgCode,
        String orgName,
        String regionCode,
        String regionName,
        Integer orgLevel,
        Long parentId,
        Integer sortNo,

        // ---------------- 部门 ----------------
        String deptCode,
        String deptName,
        Long orgId,

        // ---------------- 角色 ----------------
        String roleCode,
        List<String> permCodes,

        // ---------------- 险种 ----------------
        String typeCode,
        String typeName,
        String category,
        BigDecimal baseRate,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        String description,

        /** 兜底：执行器可读取的其它参数。 */
        Map<String, Object> extra) {

    /**
     * 空请求（所有字段为 null）。
     *
     * <p>刻意用 Builder 而不是裸构造器：30 个位置参数一旦增删字段就会静默错位，
     * 而 Builder 让"改一个字段"永远是局部改动。</p>
     */
    public static ProposalRequest empty() {
        return builder().build();
    }

    /**
     * 字段级覆盖，未提供的字段沿用当前值。
     *
     * <p>写工具有 20 多个可选字段，若每处都手写 30 个构造参数，任何一次加字段都要改遍所有工具，
     * 而且极易把字段写错位。这里用一个小型 Builder 收敛它。</p>
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** {@link ProposalRequest} 的字段级 Builder。 */
    public static final class Builder {

        private Long id;
        private String targetName;
        private String userText;
        private String realName;
        private String phone;
        private String email;
        private Long deptId;
        private Boolean clearDept;
        private Integer status;
        private List<String> roleCodes;
        private String orgCode;
        private String orgName;
        private String regionCode;
        private String regionName;
        private Integer orgLevel;
        private Long parentId;
        private Integer sortNo;
        private String deptCode;
        private String deptName;
        private Long orgId;
        private String roleCode;
        private List<String> permCodes;
        private String typeCode;
        private String typeName;
        private String category;
        private BigDecimal baseRate;
        private BigDecimal minAmount;
        private BigDecimal maxAmount;
        private String description;
        private Map<String, Object> extra = new java.util.LinkedHashMap<>();

        private Builder() {
        }

        private Builder(ProposalRequest source) {
            this.id = source.id();
            this.targetName = source.targetName();
            this.userText = source.userText();
            this.realName = source.realName();
            this.phone = source.phone();
            this.email = source.email();
            this.deptId = source.deptId();
            this.clearDept = source.clearDept();
            this.status = source.status();
            this.roleCodes = source.roleCodes();
            this.orgCode = source.orgCode();
            this.orgName = source.orgName();
            this.regionCode = source.regionCode();
            this.regionName = source.regionName();
            this.orgLevel = source.orgLevel();
            this.parentId = source.parentId();
            this.sortNo = source.sortNo();
            this.deptCode = source.deptCode();
            this.deptName = source.deptName();
            this.orgId = source.orgId();
            this.roleCode = source.roleCode();
            this.permCodes = source.permCodes();
            this.typeCode = source.typeCode();
            this.typeName = source.typeName();
            this.category = source.category();
            this.baseRate = source.baseRate();
            this.minAmount = source.minAmount();
            this.maxAmount = source.maxAmount();
            this.description = source.description();
            if (source.extra() != null) {
                this.extra.putAll(source.extra());
            }
        }

        public Builder id(Long value) {
            this.id = value;
            return this;
        }

        public Builder targetName(String value) {
            this.targetName = value;
            return this;
        }

        public Builder userText(String value) {
            this.userText = value;
            return this;
        }

        public Builder realName(String value) {
            this.realName = value;
            return this;
        }

        public Builder phone(String value) {
            this.phone = value;
            return this;
        }

        public Builder email(String value) {
            this.email = value;
            return this;
        }

        public Builder deptId(Long value) {
            this.deptId = value;
            return this;
        }

        public Builder clearDept(Boolean value) {
            this.clearDept = value;
            return this;
        }

        public Builder status(Integer value) {
            this.status = value;
            return this;
        }

        public Builder roleCodes(List<String> value) {
            this.roleCodes = value;
            return this;
        }

        public Builder orgCode(String value) {
            this.orgCode = value;
            return this;
        }

        public Builder orgName(String value) {
            this.orgName = value;
            return this;
        }

        public Builder regionCode(String value) {
            this.regionCode = value;
            return this;
        }

        public Builder regionName(String value) {
            this.regionName = value;
            return this;
        }

        public Builder orgLevel(Integer value) {
            this.orgLevel = value;
            return this;
        }

        public Builder parentId(Long value) {
            this.parentId = value;
            return this;
        }

        public Builder sortNo(Integer value) {
            this.sortNo = value;
            return this;
        }

        public Builder deptCode(String value) {
            this.deptCode = value;
            return this;
        }

        public Builder deptName(String value) {
            this.deptName = value;
            return this;
        }

        public Builder orgId(Long value) {
            this.orgId = value;
            return this;
        }

        public Builder roleCode(String value) {
            this.roleCode = value;
            return this;
        }

        public Builder permCodes(List<String> value) {
            this.permCodes = value;
            return this;
        }

        public Builder typeCode(String value) {
            this.typeCode = value;
            return this;
        }

        public Builder typeName(String value) {
            this.typeName = value;
            return this;
        }

        public Builder category(String value) {
            this.category = value;
            return this;
        }

        public Builder baseRate(BigDecimal value) {
            this.baseRate = value;
            return this;
        }

        public Builder minAmount(BigDecimal value) {
            this.minAmount = value;
            return this;
        }

        public Builder maxAmount(BigDecimal value) {
            this.maxAmount = value;
            return this;
        }

        public Builder description(String value) {
            this.description = value;
            return this;
        }

        public Builder extra(Map<String, Object> value) {
            if (value != null) {
                this.extra.putAll(value);
            }
            return this;
        }

        /** 放入一个自定义键值（例如版本指纹 fingerprint）。 */
        public Builder putExtra(String key, Object value) {
            this.extra.put(key, value);
            return this;
        }

        public ProposalRequest build() {
            return new ProposalRequest(id, targetName, userText, realName, phone, email, deptId,
                    clearDept, status, roleCodes, orgCode, orgName, regionCode, regionName, orgLevel,
                    parentId, sortNo, deptCode, deptName, orgId, roleCode, permCodes, typeCode,
                    typeName, category, baseRate, minAmount, maxAmount, description,
                    java.util.Collections.unmodifiableMap(extra));
        }
    }
}
