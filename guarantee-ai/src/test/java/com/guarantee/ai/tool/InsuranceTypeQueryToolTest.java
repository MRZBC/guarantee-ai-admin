package com.guarantee.ai.tool;

import com.guarantee.common.api.PageResult;
import com.guarantee.common.security.Permissions;
import com.guarantee.system.dto.InsuranceTypeDto;
import com.guarantee.system.service.InsuranceTypeService;
import com.guarantee.system.vo.InsuranceTypeVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code queryInsuranceType} 的单元测试（不需要数据库）。
 *
 * <p><b>防的是什么</b>：2026-09-30 真机黄金问题集 GQ-10 / GQ-20 暴露的事故——
 * 问「投标保函（标准）这个险种是启用还是停用？基准费率是多少？」时，助手连调三次工具：
 * 先按名称查（0 条）、再换短词查（还是 0 条）、最后只能兜底 {@code category=TENDER} 才拿到数据，
 * 白烧 3 次调用 4 轮，撞破"≤3 轮"的预算。</p>
 *
 * <p>根因在**本工具与 Mapper 的契约**：Mapper 的 {@code queryWhere} 里
 * {@code typeName} / {@code typeCode} / {@code keyword} 是三个并列的 AND 条件，
 * 而本工具当时把同一个关键字同时塞进了这三个字段 → SQL 等价于
 * {@code type_name LIKE %X% AND type_code LIKE %X%} → 名称关键字永远查不到
 * （编码里不可能出现"投标保函"四个字，反向亦然）。</p>
 *
 * <p>因此这里守住两件事：① 查询条件**只设 keyword**（防线回归）；
 * ② 名称关键字能命中名称，且字段映射与「不限」渲染正确。</p>
 */
class InsuranceTypeQueryToolTest {

    private InsuranceTypeService insuranceTypeService;
    private InsuranceTypeQueryTool tool;

    @BeforeEach
    void setUp() {
        insuranceTypeService = mock(InsuranceTypeService.class);
        tool = new InsuranceTypeQueryTool(insuranceTypeService);
    }

    @Test
    @DisplayName("查询条件只设 keyword：typeName / typeCode 必须留空（否则名称搜索恒 0 条）")
    void keywordMustNotBeDuplicatedIntoTypeNameAndTypeCode() {
        stubPage(1, List.of(tenderStd()));

        tool.queryInsuranceType("投标保函（标准）", null, null, null, null, contextWith(Permissions.INSURANCE_VIEW));

        ArgumentCaptor<InsuranceTypeDto.Query> captor = ArgumentCaptor.forClass(InsuranceTypeDto.Query.class);
        verify(insuranceTypeService).page(captor.capture());
        InsuranceTypeDto.Query query = captor.getValue();

        assertThat(query.getKeyword()).isEqualTo("投标保函（标准）");
        assertThat(query.getTypeName())
                .as("typeName 与 keyword 会被 Mapper 用 AND 串起来 → 名称关键字恒查不到，绝不能再设")
                .isNull();
        assertThat(query.getTypeCode())
                .as("同上：把名称塞进 typeCode 会让条件恒假")
                .isNull();
    }

    @Test
    @DisplayName("按名称查得到：命中 1 条，费率/状态/保额区间逐字段映射正确")
    void nameKeywordReturnsTheMatchingType() {
        stubPage(1, List.of(tenderStd()));

        InsuranceTypeQueryToolResult result =
                tool.queryInsuranceType("投标保函（标准）", null, null, null, null, contextWith(Permissions.INSURANCE_VIEW));

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.meta().denied()).isFalse();
        InsuranceTypeQueryToolResult.InsuranceItem item = result.items().get(0);
        assertThat(item.typeCode()).isEqualTo("TENDER_STD");
        assertThat(item.typeName()).isEqualTo("投标保函（标准）");
        assertThat(item.baseRate()).isEqualTo("0.008000");
        assertThat(item.baseRatePercent()).isEqualTo("0.8");
        assertThat(item.status()).isZero();
        assertThat(item.statusName()).isEqualTo("停用");
        assertThat(item.minAmount()).as("库里 0 表示不设限，必须渲染成「不限」而不是 0").isEqualTo("不限");
        assertThat(item.maxAmount()).isEqualTo("不限");
    }

    @Test
    @DisplayName("category / status / limit 原样下传，limit 超上限收敛到 50")
    void categoryStatusAndLimitArePassedThrough() {
        stubPage(3, List.of());

        tool.queryInsuranceType(null, "TENDER", 1, null, 999, contextWith(Permissions.INSURANCE_VIEW));

        ArgumentCaptor<InsuranceTypeDto.Query> captor = ArgumentCaptor.forClass(InsuranceTypeDto.Query.class);
        verify(insuranceTypeService).page(captor.capture());
        InsuranceTypeDto.Query query = captor.getValue();

        assertThat(query.getKeyword()).isNull();
        assertThat(query.getCategory()).isEqualTo("TENDER");
        assertThat(query.getStatus()).isEqualTo(1);
        assertThat(query.getPageSize()).as("limit 上限 50").isEqualTo(50);
    }

    @Test
    @DisplayName("无 system:insurance:view 权限：明确拒绝，且不携带任何业务数据")
    void deniedWithoutPermission() {
        InsuranceTypeQueryToolResult result =
                tool.queryInsuranceType("投标保函（标准）", null, null, null, null, contextWith(Permissions.AI_CHAT));

        assertThat(result.meta().denied()).isTrue();
        assertThat(result.meta().deniedReason()).contains(Permissions.INSURANCE_VIEW);
        assertThat(result.total()).isZero();
        assertThat(result.items()).isEmpty();
    }

    private void stubPage(long total, List<InsuranceTypeVO> rows) {
        when(insuranceTypeService.page(any(InsuranceTypeDto.Query.class)))
                .thenReturn(PageResult.of(1, 20, total, rows));
    }

    /** 「投标保函（标准）」：库里 status=0（停用），保额区间为 0/0（= 不限）。 */
    private static InsuranceTypeVO tenderStd() {
        return new InsuranceTypeVO(1L, "TENDER_STD", "投标保函（标准）", "TENDER", "投标保函",
                new BigDecimal("0.008000"), new BigDecimal("0.8"),
                BigDecimal.ZERO, BigDecimal.ZERO, 0, "标准投标保函",
                null, null, 0, null, null);
    }

    private static ToolContext contextWith(String... permissions) {
        Map<String, Object> context = new HashMap<>();
        context.put(AiToolContextKeys.PERMISSIONS, List.of(permissions));
        return new ToolContext(context);
    }
}
