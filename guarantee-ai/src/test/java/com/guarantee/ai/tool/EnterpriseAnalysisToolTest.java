package com.guarantee.ai.tool;

import com.guarantee.analysis.dto.AnalysisCriteria;
import com.guarantee.analysis.service.OrderAnalysisService;
import com.guarantee.analysis.vo.EnterpriseGroupVO;
import com.guarantee.analysis.vo.EnterpriseRankVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code queryEnterpriseAnalysis} 的单测（不需要数据库与模型）。
 *
 * <p>守三件事：<b>字段映射不错位</b>（码/名/企业数/订单量/金额）、
 * <b>边界与错误可读</b>（limit 归一、维度/日期/区间非法给中文原因）、
 * <b>口径文本是人话</b>（无工具名、无英文参数名，见 REQ-BA-05 第 2 条）。</p>
 */
class EnterpriseAnalysisToolTest {

    private OrderAnalysisService orderAnalysisService;

    private EnterpriseAnalysisTool tool;

    @BeforeEach
    void setUp() {
        orderAnalysisService = mock(OrderAnalysisService.class);
        tool = new EnterpriseAnalysisTool(orderAnalysisService);
    }

    private static EnterpriseGroupVO group(String code, String name, long enterpriseCount,
                                           long orderCount, String guarantee, String premium) {
        EnterpriseGroupVO row = new EnterpriseGroupVO();
        row.setGroupCode(code);
        row.setGroupName(name);
        row.setEnterpriseCount(enterpriseCount);
        row.setOrderCount(orderCount);
        row.setGuaranteeAmount(new BigDecimal(guarantee));
        row.setPremiumAmount(new BigDecimal(premium));
        return row;
    }

    private static EnterpriseRankVO rank(String entCode, String entName, String industry,
                                         String level, long orderCount, String guarantee) {
        EnterpriseRankVO row = new EnterpriseRankVO();
        row.setEntCode(entCode);
        row.setEntName(entName);
        row.setIndustry(industry);
        row.setEntLevel(level);
        row.setOrderCount(orderCount);
        row.setGuaranteeAmount(new BigDecimal(guarantee));
        row.setPremiumAmount(BigDecimal.ONE);
        return row;
    }

    @Test
    @DisplayName("DISTRIBUTION：每个字段都按位置映射（码/名/企业数/订单量/保额/保费）")
    void distributionMapsEveryField() {
        when(orderAnalysisService.enterpriseDistribution(any(), eq("INDUSTRY"), anyInt()))
                .thenReturn(List.of(group("建筑", "建筑", 120, 340, "123456789.01", "987654.32")));

        EnterpriseAnalysisToolResult result =
                tool.queryEnterpriseAnalysis("DISTRIBUTION", "INDUSTRY", null, "TENDER",
                        "2026-04-01", "2026-06-30", null, null);

        assertThat(result.mode()).isEqualTo("DISTRIBUTION");
        assertThat(result.dimension()).isEqualTo("INDUSTRY");
        assertThat(result.orderType()).isEqualTo("TENDER");
        assertThat(result.startDate()).isEqualTo("2026-04-01");
        assertThat(result.endDate()).isEqualTo("2026-06-30");
        assertThat(result.items()).hasSize(1);
        EnterpriseAnalysisToolResult.EnterpriseItem item = result.items().get(0);
        assertThat(item.code()).isEqualTo("建筑");
        assertThat(item.name()).isEqualTo("建筑");
        assertThat(item.enterpriseCount()).isEqualTo(120);
        assertThat(item.orderCount()).isEqualTo(340);
        assertThat(item.guaranteeAmount()).isEqualByComparingTo("123456789.01");
        assertThat(item.premiumAmount()).isEqualByComparingTo("987654.32");
        assertThat(item.industry()).isNull();
        assertThat(item.entLevel()).isNull();
        assertThat(result.meta().truncated()).isFalse();
    }

    @Test
    @DisplayName("TOP：企业名/编码/行业/等级都映射；企业数恒 0；排序依据透传")
    void topMapsEnterpriseFields() {
        when(orderAnalysisService.enterpriseTop(any(), eq("GUARANTEE_AMOUNT"), anyInt()))
                .thenReturn(List.of(rank("ENT001", "示例建工", "建筑", "AAA", 88, "5000000.00")));

        EnterpriseAnalysisToolResult result =
                tool.queryEnterpriseAnalysis("TOP", null, "GUARANTEE_AMOUNT", "ALL",
                        null, null, "330000", 5);

        assertThat(result.mode()).isEqualTo("TOP");
        assertThat(result.orderBy()).isEqualTo("GUARANTEE_AMOUNT");
        assertThat(result.regionCode()).isEqualTo("330000");
        EnterpriseAnalysisToolResult.EnterpriseItem item = result.items().get(0);
        assertThat(item.code()).isEqualTo("ENT001");
        assertThat(item.name()).isEqualTo("示例建工");
        assertThat(item.industry()).isEqualTo("建筑");
        assertThat(item.entLevel()).isEqualTo("AAA");
        assertThat(item.enterpriseCount()).isZero();
        assertThat(item.guaranteeAmount()).isEqualByComparingTo("5000000.00");
    }

    @Test
    @DisplayName("维度/日期/区间非法都给可读中文，不把堆栈抛给模型")
    void invalidInputsGiveReadableChinese() {
        assertThatThrownBy(() -> tool.queryEnterpriseAnalysis(null, "INDUSTRYY", null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("企业维度只能是");

        assertThatThrownBy(() -> tool.queryEnterpriseAnalysis(null, null, null, null, "2026/04/01", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("yyyy-MM-dd");

        assertThatThrownBy(() -> tool.queryEnterpriseAnalysis(null, null, null, null, "2026-07-01", "2026-06-30", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能晚于");

        assertThatThrownBy(() -> tool.queryEnterpriseAnalysis("SOMETHING", null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("查询模式只能是");
    }

    @Test
    @DisplayName("limit 归一：默认 10、上限 50、非法值回落默认")
    void normalizeLimit() {
        assertThat(EnterpriseAnalysisTool.normalizeLimit(null)).isEqualTo(10);
        assertThat(EnterpriseAnalysisTool.normalizeLimit(0)).isEqualTo(10);
        assertThat(EnterpriseAnalysisTool.normalizeLimit(-5)).isEqualTo(10);
        assertThat(EnterpriseAnalysisTool.normalizeLimit(25)).isEqualTo(25);
        assertThat(EnterpriseAnalysisTool.normalizeLimit(999)).isEqualTo(50);

        when(orderAnalysisService.enterpriseDistribution(any(), anyString(), anyInt()))
                .thenReturn(List.of());
        tool.queryEnterpriseAnalysis(null, null, null, null, null, null, null, 999);
        ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(orderAnalysisService).enterpriseDistribution(any(AnalysisCriteria.class), eq("INDUSTRY"), limitCaptor.capture());
        assertThat(limitCaptor.getValue()).isEqualTo(50);
    }

    @Test
    @DisplayName("条数用满 → 显式标记截断（不得把榜单说成完整清单）")
    void markTruncatedWhenFull() {
        List<EnterpriseGroupVO> rows = new ArrayList<>();
        rows.add(group("建筑", "建筑", 10, 10, "1", "1"));
        rows.add(group("交通", "交通", 9, 9, "1", "1"));
        when(orderAnalysisService.enterpriseDistribution(any(), anyString(), anyInt())).thenReturn(rows);

        EnterpriseAnalysisToolResult result =
                tool.queryEnterpriseAnalysis(null, null, null, null, null, null, null, 2);

        assertThat(result.meta().truncated()).isTrue();
        assertThat(result.meta().truncatedHint()).contains("前 2");
    }

    @Test
    @DisplayName("口径文本是人话：含业务条件、无工具名、无英文参数名、区域给中文名")
    void dataSourceIsHumanReadable() {
        when(orderAnalysisService.enterpriseDistribution(any(), anyString(), anyInt()))
                .thenReturn(List.of());

        EnterpriseAnalysisToolResult result = tool.queryEnterpriseAnalysis(
                "DISTRIBUTION", "LEVEL", null, "PERFORMANCE",
                "2026-01-01", "2026-06-30", "330000", null);

        String dataSource = result.meta().dataSource();
        assertThat(dataSource).startsWith("企业分析");
        assertThat(dataSource).contains("查询模式：分组分布");
        assertThat(dataSource).contains("维度：企业等级");
        assertThat(dataSource).contains("险种：履约保函");
        assertThat(dataSource).contains("起始日期：2026-01-01");
        assertThat(dataSource).contains("区域编码：330000（浙江省）");
        // 禁止项：工具名与英文参数名
        assertThat(dataSource).doesNotContain("queryEnterpriseAnalysis");
        assertThat(dataSource).doesNotContain("dimension");
        assertThat(dataSource).doesNotContain("orderType");
        assertThat(dataSource).doesNotContain("regionCode");
        assertThat(dataSource).doesNotContain("TENDER");
        assertThat(dataSource).doesNotContain("INDUSTRY");
    }

    @Test
    @DisplayName("TOP 模式口径文本带排序依据；未加过滤时显式写全量")
    void dataSourceForTopAndUnfiltered() {
        when(orderAnalysisService.enterpriseTop(any(), anyString(), anyInt())).thenReturn(List.of());

        String sorted = tool.queryEnterpriseAnalysis("TOP", "REGION", "ORDER_COUNT", "ALL",
                null, null, null, null).meta().dataSource();
        assertThat(sorted).contains("查询模式：企业排行");
        assertThat(sorted).contains("排序依据：订单量");
        // 未生效的条件必须被跳过（不是把它们列成"不限"制造噪音）
        assertThat(sorted).doesNotContain("起始日期").doesNotContain("区域编码");

        String byAmount = tool.queryEnterpriseAnalysis("TOP", "REGION", "GUARANTEE_AMOUNT", "ALL",
                null, null, null, null).meta().dataSource();
        assertThat(byAmount).contains("排序依据：保额");
    }
}
