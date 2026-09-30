package com.guarantee.ai.tool;

import com.guarantee.analysis.service.OrderAnalysisService;
import com.guarantee.analysis.vo.ProjectGroupVO;
import com.guarantee.analysis.vo.ProjectRankVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code queryProjectAnalysis} 的单测。
 *
 * <p>除了字段映射/边界/口径文本，这里还钉住一条**业务口径**：项目类型是中文枚举，
 * 必须**原样透传**（{@code 交通} 就是 {@code 交通}），不允许出现任何"翻译"或英文映射。</p>
 */
class ProjectAnalysisToolTest {

    private OrderAnalysisService orderAnalysisService;

    private ProjectAnalysisTool tool;

    @BeforeEach
    void setUp() {
        orderAnalysisService = mock(OrderAnalysisService.class);
        tool = new ProjectAnalysisTool(orderAnalysisService);
    }

    private static ProjectGroupVO group(String code, String name, long projectCount,
                                        long orderCount, String guarantee) {
        ProjectGroupVO row = new ProjectGroupVO();
        row.setGroupCode(code);
        row.setGroupName(name);
        row.setProjectCount(projectCount);
        row.setOrderCount(orderCount);
        row.setGuaranteeAmount(new BigDecimal(guarantee));
        row.setPremiumAmount(new BigDecimal("100.50"));
        return row;
    }

    private static ProjectRankVO rank(String code, String name, String type, String regionName,
                                      long orderCount, String guarantee) {
        ProjectRankVO row = new ProjectRankVO();
        row.setProjectCode(code);
        row.setProjectName(name);
        row.setProjectType(type);
        row.setRegionName(regionName);
        row.setOrderCount(orderCount);
        row.setGuaranteeAmount(new BigDecimal(guarantee));
        row.setPremiumAmount(BigDecimal.ONE);
        return row;
    }

    @Test
    @DisplayName("DISTRIBUTION：项目类型**原样中文透传**（不翻译），字段逐项映射")
    void distributionKeepsChineseProjectType() {
        when(orderAnalysisService.projectDistribution(any(), eq("PROJECT_TYPE"), anyInt()))
                .thenReturn(List.of(group("交通", "交通", 42, 66, "88888888.88")));

        ProjectAnalysisToolResult result = tool.queryProjectAnalysis(
                "DISTRIBUTION", "PROJECT_TYPE", "TENDER", "2026-04-01", "2026-06-30", null, null);

        assertThat(result.mode()).isEqualTo("DISTRIBUTION");
        assertThat(result.dimension()).isEqualTo("PROJECT_TYPE");
        ProjectAnalysisToolResult.ProjectItem item = result.items().get(0);
        assertThat(item.code()).isEqualTo("交通");
        assertThat(item.name()).isEqualTo("交通");
        assertThat(item.projectCount()).isEqualTo(42);
        assertThat(item.orderCount()).isEqualTo(66);
        assertThat(item.guaranteeAmount()).isEqualByComparingTo("88888888.88");
        // 不允许出现英文/拼音映射
        assertThat(item.name()).doesNotContain("TRAFFIC").doesNotContain("Transport");
    }

    @Test
    @DisplayName("TOP：项目名/编码/类型/地区映射；项目数恒 0")
    void topMapsProjectFields() {
        when(orderAnalysisService.projectTop(any(), anyInt()))
                .thenReturn(List.of(rank("PRJ001", "示例大桥工程", "交通", "浙江省", 3, "120000000.00")));

        ProjectAnalysisToolResult result =
                tool.queryProjectAnalysis("TOP", null, "ALL", null, null, null, 10);

        ProjectAnalysisToolResult.ProjectItem item = result.items().get(0);
        assertThat(item.code()).isEqualTo("PRJ001");
        assertThat(item.name()).isEqualTo("示例大桥工程");
        assertThat(item.projectType()).isEqualTo("交通");
        assertThat(item.regionName()).isEqualTo("浙江省");
        assertThat(item.projectCount()).isZero();
        assertThat(item.guaranteeAmount()).isEqualByComparingTo("120000000.00");
    }

    @Test
    @DisplayName("非法维度/模式/日期给可读中文")
    void invalidInputsGiveReadableChinese() {
        assertThatThrownBy(() -> tool.queryProjectAnalysis(null, "INDUSTRY", null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("项目维度只能是");

        assertThatThrownBy(() -> tool.queryProjectAnalysis("RANKING2", null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("查询模式只能是");

        assertThatThrownBy(() -> tool.queryProjectAnalysis(null, null, null, "2026-13-01", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("yyyy-MM-dd");
    }

    @Test
    @DisplayName("limit 归一与截断标记")
    void limitAndTruncation() {
        assertThat(ProjectAnalysisTool.normalizeLimit(null)).isEqualTo(10);
        assertThat(ProjectAnalysisTool.normalizeLimit(999)).isEqualTo(50);

        when(orderAnalysisService.projectDistribution(any(), anyString(), anyInt()))
                .thenReturn(List.of(group("房建", "房建", 1, 1, "1"), group("市政", "市政", 1, 1, "1")));
        ProjectAnalysisToolResult result =
                tool.queryProjectAnalysis(null, "PROJECT_TYPE", null, null, null, null, 2);
        assertThat(result.meta().truncated()).isTrue();
    }

    @Test
    @DisplayName("口径文本：项目分析 + 中文维度 + 区域中文名；无工具名/英文参数名")
    void dataSourceIsHumanReadable() {
        when(orderAnalysisService.projectDistribution(any(), anyString(), anyInt())).thenReturn(List.of());

        String dataSource = tool.queryProjectAnalysis("DISTRIBUTION", "REGION", "PERFORMANCE",
                "2026-01-01", "2026-06-30", "330000", null).meta().dataSource();

        assertThat(dataSource).startsWith("项目分析");
        assertThat(dataSource).contains("维度：地区");
        assertThat(dataSource).contains("险种：履约保函");
        assertThat(dataSource).contains("区域编码：330000（浙江省）");
        assertThat(dataSource).doesNotContain("queryProjectAnalysis");
        assertThat(dataSource).doesNotContain("dimension");
        assertThat(dataSource).doesNotContain("PROJECT_TYPE");
    }
}
