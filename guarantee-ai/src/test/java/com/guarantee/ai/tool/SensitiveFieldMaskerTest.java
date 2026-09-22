package com.guarantee.ai.tool;

import com.guarantee.common.security.SensitiveFieldMasker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 敏感字段脱敏单元测试（TEST-12 / TEST-15 / TEST-18 / TEST-20 / D-4）。
 *
 * <p>覆盖三条不同的脱敏路径，它们必须由同一套规则实现，否则会出现旁路（RK-12）：</p>
 * <ol>
 *   <li>面向展示的掩码（工具返回值）；</li>
 *   <li>面向上帝视角的审计快照（只记字段名与是否变更）；</li>
 *   <li>面向任意 JSON 的递归落库脱敏（{@code ai_tool_call} / 提案载荷）。</li>
 * </ol>
 */
class SensitiveFieldMaskerTest {

    // ---------------- 展示掩码 ----------------

    @Test
    @DisplayName("手机号掩码：保留前三后四（SYS-P-11 规定的 138****5678）")
    void shouldMaskPhone() {
        assertThat(SensitiveFieldMasker.maskPhone("13812345678")).isEqualTo("138****5678");
        assertThat(SensitiveFieldMasker.maskValue("phone", "13812345678")).isEqualTo("138****5678");
    }

    @Test
    @DisplayName("邮箱掩码：保留首字母与域名（SYS-P-11 规定的 a***@guarantee.com）")
    void shouldMaskEmail() {
        assertThat(SensitiveFieldMasker.maskEmail("analyst@guarantee.com"))
                .isEqualTo("a***@guarantee.com");
        assertThat(SensitiveFieldMasker.maskValue("email", "analyst@guarantee.com"))
                .isEqualTo("a***@guarantee.com");
    }

    @Test
    @DisplayName("字段名不像敏感字段但值像手机号/邮箱时也要掩码")
    void shouldMaskByValueShape() {
        assertThat(SensitiveFieldMasker.maskValue("remark", "13812345678")).isEqualTo("138****5678");
        assertThat(SensitiveFieldMasker.maskValue("note", "a@b.com")).isEqualTo("a***@b.com");
    }

    @Test
    @DisplayName("密码/令牌类字段整体掩码，不返回任何片段")
    void shouldFullyMaskSecrets() {
        assertThat(SensitiveFieldMasker.maskValue("password", "Admin@123")).isEqualTo("******");
        assertThat(SensitiveFieldMasker.maskValue("token", "eyJhbGciOi")).isEqualTo("******");
        assertThat(SensitiveFieldMasker.maskValue("idCard", "330102199001011234")).isEqualTo("******");
    }

    @Test
    @DisplayName("非敏感字段原样返回，不做无谓改写")
    void shouldKeepNonSensitiveValues() {
        assertThat(SensitiveFieldMasker.maskValue("realName", "张力")).isEqualTo("张力");
        assertThat(SensitiveFieldMasker.maskValue("orgName", "浙江省第1保函运营机构"))
                .isEqualTo("浙江省第1保函运营机构");
    }

    // ---------------- 审计快照（D-4） ----------------

    @Test
    @DisplayName("审计快照对敏感字段只保留字段名与是否变更，不写任何具体值（D-4）")
    void shouldMaskAuditSnapshotWithoutValues() {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("realName", "张力");
        before.put("phone", "13812345678");
        before.put("email", "analyst@guarantee.com");
        before.put("deptId", 25L);

        Map<String, Object> masked = SensitiveFieldMasker.maskSnapshotForAudit(before);

        // 非敏感字段原样保留
        assertThat(masked.get("realName")).isEqualTo("张力");
        assertThat(masked.get("deptId")).isEqualTo(25L);
        // 敏感字段只有占位符，连掩码形态都没有
        assertThat(masked.get("phone")).isEqualTo(SensitiveFieldMasker.CHANGED_PLACEHOLDER);
        assertThat(masked.get("email")).isEqualTo(SensitiveFieldMasker.CHANGED_PLACEHOLDER);
        // 关键断言：序列化后的快照里查不到任何明文片段
        assertThat(masked.toString()).doesNotContain("13812345678").doesNotContain("analyst@");
    }

    @Test
    @DisplayName("审计快照脱敏与操作者角色无关：ADMIN 走的是同一个方法，无角色分支")
    void maskingHasNoRoleBranch() {
        // 本测试的意义在于锁定"没有角色参数"这一事实：
        // 一旦有人给脱敏方法加上角色判断，签名变化会让本测试编译失败（D-4 的回归护栏）。
        Map<String, Object> masked = SensitiveFieldMasker.maskSnapshotForAudit(Map.of("phone", "13812345678"));
        assertThat(masked.get("phone")).isEqualTo(SensitiveFieldMasker.CHANGED_PLACEHOLDER);
    }

    @Test
    @DisplayName("变更字段可检索：changed_fields 能体现手机号改过（但看不到值）")
    void shouldDetectChangedSensitiveFields() {
        Map<String, Object> before = Map.of("phone", "13812345678", "realName", "张力");
        Map<String, Object> after = Map.of("phone", "13900000000", "realName", "张力");

        assertThat(SensitiveFieldMasker.changedSensitiveFields(before, after)).containsExactly("phone");
    }

    // ---------------- 任意 JSON 递归脱敏（SYS-A-09） ----------------

    @Test
    @DisplayName("递归脱敏：嵌套结构中的敏感键被替换，其它值保留")
    @SuppressWarnings("unchecked")
    void shouldMaskDeeply() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("username", "user0123");
        nested.put("phone", "13812345678");
        nested.put("profile", Map.of("email", "user0123@guarantee.com", "city", "杭州"));

        Object masked = SensitiveFieldMasker.maskDeep(nested);
        Map<String, Object> result = (Map<String, Object>) masked;

        assertThat(result.get("username")).isEqualTo("user0123");
        assertThat(result.get("phone")).isEqualTo(SensitiveFieldMasker.CHANGED_PLACEHOLDER);
        Map<String, Object> profile = (Map<String, Object>) result.get("profile");
        assertThat(profile.get("email")).isEqualTo(SensitiveFieldMasker.CHANGED_PLACEHOLDER);
        assertThat(profile.get("city")).isEqualTo("杭州");
        assertThat(result.toString()).doesNotContain("13812345678").doesNotContain("@guarantee.com");
    }

    @Test
    @DisplayName("文本兜底脱敏：非 JSON 的错误消息里的手机号/邮箱也会被替换")
    void shouldMaskPlainText() {
        String text = "更新失败：手机号 13812345678 与邮箱 analyst@guarantee.com 校验不通过";
        String masked = SensitiveFieldMasker.maskText(text);
        assertThat(masked).doesNotContain("13812345678").doesNotContain("analyst@guarantee.com");
        assertThat(masked).contains("138****5678").contains("a***@guarantee.com");
    }
}
