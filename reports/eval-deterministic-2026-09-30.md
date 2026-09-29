# 助手评测报告（deterministic）

> 生成时间：2026-09-29T20:37:11.158Z
> 目标：`http://localhost:8081`（账号 admin）
> 数据基线：— 确定性集不需要真实数据基线
> 结果：**通过 12/12**，失败 0，未跑 0

| 套件 | 状态 | 说明 |
|---|---|---|
| deterministic | ok | 确定性集 12/12 通过（未跑 0） |
| live | not-run |  |

## 与基线的 diff（REQ-MCP-07）

基线：2026-09-29T20:36:56.417Z（suite=deterministic）

- **新增失败：0**
- 新修复：0
- 仍失败：0
- 基线里没有的新题：无
- 指标变化：
  - ⚠️ elapsedMs.avg：102.3 → 111.6

## 打分（REQ-MCP-06）

| 指标 | 值 |
|---|---|
| 通过率 | 1 |
| 口径正确率（有工具调用必有口径行） | 1 |
| 引用完整率（知识类必有来源行） | 1 |
| 禁用术语违规数 | 0 |
| 轮次 min/avg/max | 2 / 2 / 2 |
| 耗时(s) min/avg/max | 0.0 / 0.1 / 0.6 |
| 工具调用 min/avg/max | 1 / 1.2 / 2 |

## 逐题结果

| 编号 | 类别 | 结果 | 工具调用 | 轮次 | 耗时(s) | 正文字数 | 备注 |
|---|---|---|---|---|---|---|---|
| GQ-07 | 单维度统计 | ✅ | 1 | 2 | 0.6 | 207 |  |
| GQ-12 | 降级/失败 | ✅ | 1 | 2 | 0.1 | 205 |  |
| GQ-16 | 定义/知识类 | ✅ | 1 | 2 | 0.1 | 106 |  |
| GQ-17 | 定义/知识类 | ✅ | 1 | 2 | 0.0 | 112 |  |
| GQ-18 | 定义/知识类 | ✅ | 1 | 2 | 0.0 | 113 |  |
| GQ-19 | 定义/知识类 | ✅ | 1 | 2 | 0.0 | 105 |  |
| GQ-20 | 定义/知识类混合 | ✅ | 2 | 2 | 0.1 | 175 |  |
| GQ-21 | 定义/知识类混合 | ✅ | 2 | 2 | 0.0 | 178 |  |
| GQ-22 | 定义/知识类未收录 | ✅ | 1 | 2 | 0.1 | 9 |  |
| GQ-24 | 定义/知识类越权 | ✅ | 1 | 2 | 0.0 | 100 |  |
| GQ-26 | 单维度统计 | ✅ | 1 | 2 | 0.2 | 213 |  |
| GQ-33 | 定义/知识类 | ✅ | 1 | 2 | 0.0 | 105 |  |

## 单一事实源（REQ-MCP-12）

来源：`scripts/single-source-of-truth.mjs`

```json
{
  "generatedAt": "2026-09-29T20:37:10.908Z",
  "generator": "scripts/single-source-of-truth.mjs",
  "requirement": "REQ-MCP-12 / AC-MCP-12",
  "tests": {
    "unit": {
      "kind": "surefire",
      "reportDirs": [
        "guarantee-ai/target/surefire-reports",
        "guarantee-analysis/target/surefire-reports",
        "guarantee-auth/target/surefire-reports",
        "guarantee-common/target/surefire-reports",
        "guarantee-order/target/surefire-reports",
        "guarantee-system/target/surefire-reports",
        "guarantee-web/target/surefire-reports"
      ],
      "suites": [
        {
          "suite": "com.guarantee.ai.config.AiConfigCatalogTest",
          "tests": 12,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.157,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.config.AiConfigCatalogTest.xml"
        },
        {
          "suite": "com.guarantee.ai.config.AiConfigServiceTest",
          "tests": 15,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 1.681,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.config.AiConfigServiceTest.xml"
        },
        {
          "suite": "com.guarantee.ai.config.PromptVersionServiceTest",
          "tests": 19,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.723,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.config.PromptVersionServiceTest.xml"
        },
        {
          "suite": "com.guarantee.ai.knowledge.KnowledgeDocumentParserTest",
          "tests": 19,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.052,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.knowledge.KnowledgeDocumentParserTest.xml"
        },
        {
          "suite": "com.guarantee.ai.knowledge.KnowledgeImporterTest",
          "tests": 9,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.021,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.knowledge.KnowledgeImporterTest.xml"
        },
        {
          "suite": "com.guarantee.ai.knowledge.KnowledgeMapperXmlTest",
          "tests": 2,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.186,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.knowledge.KnowledgeMapperXmlTest.xml"
        },
        {
          "suite": "com.guarantee.ai.knowledge.KnowledgeServiceTest",
          "tests": 16,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.039,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.knowledge.KnowledgeServiceTest.xml"
        },
        {
          "suite": "com.guarantee.ai.knowledge.KnowledgeSourceLoaderTest",
          "tests": 4,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.082,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.knowledge.KnowledgeSourceLoaderTest.xml"
        },
        {
          "suite": "com.guarantee.ai.knowledge.KnowledgeTermsTest",
          "tests": 4,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.003,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.knowledge.KnowledgeTermsTest.xml"
        },
        {
          "suite": "com.guarantee.ai.mcp.McpControllerProtocolTest",
          "tests": 12,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.189,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.mcp.McpControllerProtocolTest.xml"
        },
        {
          "suite": "com.guarantee.ai.mcp.McpEnabledConditionTest",
          "tests": 3,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.347,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.mcp.McpEnabledConditionTest.xml"
        },
        {
          "suite": "com.guarantee.ai.mcp.McpRateLimiterTest",
          "tests": 8,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.123,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.mcp.McpRateLimiterTest.xml"
        },
        {
          "suite": "com.guarantee.ai.mcp.McpTokenMappingTest",
          "tests": 6,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.009,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.mcp.McpTokenMappingTest.xml"
        },
        {
          "suite": "com.guarantee.ai.mcp.McpTokenServiceTest",
          "tests": 17,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.049,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.mcp.McpTokenServiceTest.xml"
        },
        {
          "suite": "com.guarantee.ai.mcp.McpToolCatalogTest",
          "tests": 6,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.011,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.mcp.McpToolCatalogTest.xml"
        },
        {
          "suite": "com.guarantee.ai.mcp.McpToolInvokerGateTest",
          "tests": 5,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.156,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.mcp.McpToolInvokerGateTest.xml"
        },
        {
          "suite": "com.guarantee.ai.mcp.McpToolInvokerTest",
          "tests": 13,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.06,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.mcp.McpToolInvokerTest.xml"
        },
        {
          "suite": "com.guarantee.ai.metrics.AiChatMetricsTest",
          "tests": 8,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.034,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.metrics.AiChatMetricsTest.xml"
        },
        {
          "suite": "com.guarantee.ai.metrics.AiTurnMetricMappingTest",
          "tests": 6,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.009,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.metrics.AiTurnMetricMappingTest.xml"
        },
        {
          "suite": "com.guarantee.ai.metrics.TurnMetricServiceTest",
          "tests": 19,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.039,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.metrics.TurnMetricServiceTest.xml"
        },
        {
          "suite": "com.guarantee.ai.service.AiChatServiceBudgetGuardTest",
          "tests": 3,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.371,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.service.AiChatServiceBudgetGuardTest.xml"
        },
        {
          "suite": "com.guarantee.ai.service.AiChatServiceRetractionNoticeTest",
          "tests": 5,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.002,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.service.AiChatServiceRetractionNoticeTest.xml"
        },
        {
          "suite": "com.guarantee.ai.service.AiConfigWiringTest",
          "tests": 10,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.084,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.service.AiConfigWiringTest.xml"
        },
        {
          "suite": "com.guarantee.ai.service.DataSourceClaimGuardTest",
          "tests": 11,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.004,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.service.DataSourceClaimGuardTest.xml"
        },
        {
          "suite": "com.guarantee.ai.service.KnowledgeClaimGuardTest",
          "tests": 11,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.005,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.service.KnowledgeClaimGuardTest.xml"
        },
        {
          "suite": "com.guarantee.ai.service.NumberClaimGuardTest",
          "tests": 6,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.002,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.service.NumberClaimGuardTest.xml"
        },
        {
          "suite": "com.guarantee.ai.service.OperationAuditServiceTest",
          "tests": 16,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.074,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.service.OperationAuditServiceTest.xml"
        },
        {
          "suite": "com.guarantee.ai.service.ProposalClaimGuardTest",
          "tests": 11,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.041,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.service.ProposalClaimGuardTest.xml"
        },
        {
          "suite": "com.guarantee.ai.service.ProposalNumberGuardTest",
          "tests": 8,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.003,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.service.ProposalNumberGuardTest.xml"
        },
        {
          "suite": "com.guarantee.ai.service.ProposalServiceReusePublishTest",
          "tests": 2,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.222,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.service.ProposalServiceReusePublishTest.xml"
        },
        {
          "suite": "com.guarantee.ai.service.ToolExecutionTimeoutGuardTest",
          "tests": 5,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.145,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.service.ToolExecutionTimeoutGuardTest.xml"
        },
        {
          "suite": "com.guarantee.ai.time.TimeSemanticParserTest",
          "tests": 16,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.068,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.time.TimeSemanticParserTest.xml"
        },
        {
          "suite": "com.guarantee.ai.tool.AiPermissionGuardTest",
          "tests": 6,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.003,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.tool.AiPermissionGuardTest.xml"
        },
        {
          "suite": "com.guarantee.ai.tool.AiToolRegistryTest",
          "tests": 15,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.585,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.tool.AiToolRegistryTest.xml"
        },
        {
          "suite": "com.guarantee.ai.tool.DataMetricsTest",
          "tests": 10,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.032,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.tool.DataMetricsTest.xml"
        },
        {
          "suite": "com.guarantee.ai.tool.DataSourceTextTest",
          "tests": 11,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.006,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.tool.DataSourceTextTest.xml"
        },
        {
          "suite": "com.guarantee.ai.tool.MyProposalsQueryToolTest",
          "tests": 8,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.011,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.tool.MyProposalsQueryToolTest.xml"
        },
        {
          "suite": "com.guarantee.ai.tool.OrderDistributionToolTest",
          "tests": 9,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.018,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.tool.OrderDistributionToolTest.xml"
        },
        {
          "suite": "com.guarantee.ai.tool.OrderTrendToolTest",
          "tests": 8,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.018,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.tool.OrderTrendToolTest.xml"
        },
        {
          "suite": "com.guarantee.ai.tool.QueryBusinessKnowledgeToolTest",
          "tests": 6,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.04,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.tool.QueryBusinessKnowledgeToolTest.xml"
        },
        {
          "suite": "com.guarantee.ai.tool.SanitizingToolCallbackTest",
          "tests": 4,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.009,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.tool.SanitizingToolCallbackTest.xml"
        },
        {
          "suite": "com.guarantee.ai.tool.SensitiveFieldMaskerTest",
          "tests": 10,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.005,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.tool.SensitiveFieldMaskerTest.xml"
        },
        {
          "suite": "com.guarantee.ai.tool.ToolResultSanitizerTest",
          "tests": 9,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.012,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.tool.ToolResultSanitizerTest.xml"
        },
        {
          "suite": "com.guarantee.ai.tool.TurnFactsTest",
          "tests": 12,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.01,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.tool.TurnFactsTest.xml"
        },
        {
          "suite": "com.guarantee.ai.tool.write.RoleProposalToolPermissionDisplayTest",
          "tests": 3,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.008,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.tool.write.RoleProposalToolPermissionDisplayTest.xml"
        },
        {
          "suite": "com.guarantee.ai.tool.write.WriteToolResultTest",
          "tests": 2,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.tool.write.WriteToolResultTest.xml"
        },
        {
          "suite": "com.guarantee.analysis.dto.OrderTrendQueryTest",
          "tests": 2,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.084,
          "file": "guarantee-analysis/target/surefire-reports/TEST-com.guarantee.analysis.dto.OrderTrendQueryTest.xml"
        },
        {
          "suite": "com.guarantee.analysis.mapper.DistributionDimensionNameTest",
          "tests": 2,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.013,
          "file": "guarantee-analysis/target/surefire-reports/TEST-com.guarantee.analysis.mapper.DistributionDimensionNameTest.xml"
        },
        {
          "suite": "com.guarantee.analysis.mapper.OrderTrendGranularityIT",
          "tests": 1,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 5.151,
          "file": "guarantee-analysis/target/surefire-reports/TEST-com.guarantee.analysis.mapper.OrderTrendGranularityIT.xml"
        },
        {
          "suite": "com.guarantee.analysis.mapper.TrendGranularityMapperXmlTest",
          "tests": 2,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.005,
          "file": "guarantee-analysis/target/surefire-reports/TEST-com.guarantee.analysis.mapper.TrendGranularityMapperXmlTest.xml"
        },
        {
          "suite": "com.guarantee.auth.config.SecurityConfigCorsTest",
          "tests": 2,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.211,
          "file": "guarantee-auth/target/surefire-reports/TEST-com.guarantee.auth.config.SecurityConfigCorsTest.xml"
        },
        {
          "suite": "com.guarantee.auth.security.JwtTokenProviderSecretTest",
          "tests": 11,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.292,
          "file": "guarantee-auth/target/surefire-reports/TEST-com.guarantee.auth.security.JwtTokenProviderSecretTest.xml"
        },
        {
          "suite": "com.guarantee.auth.security.TokenRevocationServiceFailureModeTest",
          "tests": 12,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 2.079,
          "file": "guarantee-auth/target/surefire-reports/TEST-com.guarantee.auth.security.TokenRevocationServiceFailureModeTest.xml"
        },
        {
          "suite": "com.guarantee.auth.service.AuthServiceServiceAccountLoginTest",
          "tests": 3,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.184,
          "file": "guarantee-auth/target/surefire-reports/TEST-com.guarantee.auth.service.AuthServiceServiceAccountLoginTest.xml"
        },
        {
          "suite": "com.guarantee.common.exception.GlobalExceptionHandlerTest",
          "tests": 3,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 2.31,
          "file": "guarantee-common/target/surefire-reports/TEST-com.guarantee.common.exception.GlobalExceptionHandlerTest.xml"
        },
        {
          "suite": "com.guarantee.common.region.RegionCodePrefixTest",
          "tests": 4,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.013,
          "file": "guarantee-common/target/surefire-reports/TEST-com.guarantee.common.region.RegionCodePrefixTest.xml"
        },
        {
          "suite": "com.guarantee.order.mapper.OrderDimensionNamePreservationTest",
          "tests": 2,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.085,
          "file": "guarantee-order/target/surefire-reports/TEST-com.guarantee.order.mapper.OrderDimensionNamePreservationTest.xml"
        },
        {
          "suite": "com.guarantee.system.controller.DepartmentControllerTreeAuthTest",
          "tests": 3,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.112,
          "file": "guarantee-system/target/surefire-reports/TEST-com.guarantee.system.controller.DepartmentControllerTreeAuthTest.xml"
        },
        {
          "suite": "com.guarantee.system.controller.OrderFilterDictionaryPermissionTest",
          "tests": 5,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.154,
          "file": "guarantee-system/target/surefire-reports/TEST-com.guarantee.system.controller.OrderFilterDictionaryPermissionTest.xml"
        },
        {
          "suite": "com.guarantee.system.entity.SysUserAccountTypeTest",
          "tests": 2,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.003,
          "file": "guarantee-system/target/surefire-reports/TEST-com.guarantee.system.entity.SysUserAccountTypeTest.xml"
        },
        {
          "suite": "com.guarantee.system.mapper.SysUserAccountTypeMapperXmlTest",
          "tests": 3,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.211,
          "file": "guarantee-system/target/surefire-reports/TEST-com.guarantee.system.mapper.SysUserAccountTypeMapperXmlTest.xml"
        },
        {
          "suite": "com.guarantee.system.mybatis.LogicalDeletePermissionsTest",
          "tests": 4,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.015,
          "file": "guarantee-system/target/surefire-reports/TEST-com.guarantee.system.mybatis.LogicalDeletePermissionsTest.xml"
        },
        {
          "suite": "com.guarantee.system.mybatis.LogicalDeleteSchemaIntegrationTest",
          "tests": 11,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 8.593,
          "file": "guarantee-system/target/surefire-reports/TEST-com.guarantee.system.mybatis.LogicalDeleteSchemaIntegrationTest.xml"
        },
        {
          "suite": "com.guarantee.system.mybatis.LogicalDeleteSqlRewriterTest",
          "tests": 10,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.014,
          "file": "guarantee-system/target/surefire-reports/TEST-com.guarantee.system.mybatis.LogicalDeleteSqlRewriterTest.xml"
        },
        {
          "suite": "com.guarantee.system.scope.DataScopeIntegrationTest",
          "tests": 12,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.152,
          "file": "guarantee-system/target/surefire-reports/TEST-com.guarantee.system.scope.DataScopeIntegrationTest.xml"
        },
        {
          "suite": "com.guarantee.system.scope.DataScopeTest",
          "tests": 8,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.004,
          "file": "guarantee-system/target/surefire-reports/TEST-com.guarantee.system.scope.DataScopeTest.xml"
        },
        {
          "suite": "com.guarantee.system.service.InsuranceTypeAmountBoundIntegrationTest",
          "tests": 6,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.082,
          "file": "guarantee-system/target/surefire-reports/TEST-com.guarantee.system.service.InsuranceTypeAmountBoundIntegrationTest.xml"
        },
        {
          "suite": "com.guarantee.system.service.InsuranceTypeFilterOptionsIntegrationTest",
          "tests": 7,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 1.066,
          "file": "guarantee-system/target/surefire-reports/TEST-com.guarantee.system.service.InsuranceTypeFilterOptionsIntegrationTest.xml"
        },
        {
          "suite": "com.guarantee.system.service.LogicalDeleteServiceIntegrationTest",
          "tests": 14,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 2.07,
          "file": "guarantee-system/target/surefire-reports/TEST-com.guarantee.system.service.LogicalDeleteServiceIntegrationTest.xml"
        },
        {
          "suite": "com.guarantee.system.service.OrgFilterOptionsIntegrationTest",
          "tests": 6,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.889,
          "file": "guarantee-system/target/surefire-reports/TEST-com.guarantee.system.service.OrgFilterOptionsIntegrationTest.xml"
        },
        {
          "suite": "com.guarantee.system.service.RegionServiceIntegrationTest",
          "tests": 9,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 2.188,
          "file": "guarantee-system/target/surefire-reports/TEST-com.guarantee.system.service.RegionServiceIntegrationTest.xml"
        },
        {
          "suite": "com.guarantee.system.service.RoleServicePermissionDisplayTest",
          "tests": 6,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.69,
          "file": "guarantee-system/target/surefire-reports/TEST-com.guarantee.system.service.RoleServicePermissionDisplayTest.xml"
        },
        {
          "suite": "com.guarantee.system.service.SysUserAccountTypeIntegrationTest",
          "tests": 6,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.135,
          "file": "guarantee-system/target/surefire-reports/TEST-com.guarantee.system.service.SysUserAccountTypeIntegrationTest.xml"
        },
        {
          "suite": "com.guarantee.system.service.UserServiceRoleDisplayTest",
          "tests": 8,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.142,
          "file": "guarantee-system/target/surefire-reports/TEST-com.guarantee.system.service.UserServiceRoleDisplayTest.xml"
        },
        {
          "suite": "com.guarantee.web.ai.AiConfigWiringIT",
          "tests": 3,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 8.086,
          "file": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.ai.AiConfigWiringIT.xml"
        },
        {
          "suite": "com.guarantee.web.ai.AiToolChainIT",
          "tests": 3,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 1.25,
          "file": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.ai.AiToolChainIT.xml"
        },
        {
          "suite": "com.guarantee.web.ai.config.AiConfigChangeAuditIT",
          "tests": 4,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 2.337,
          "file": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.ai.config.AiConfigChangeAuditIT.xml"
        },
        {
          "suite": "com.guarantee.web.ai.config.AiConfigChangeAuditTest",
          "tests": 6,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 1.948,
          "file": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.ai.config.AiConfigChangeAuditTest.xml"
        },
        {
          "suite": "com.guarantee.web.ai.KnowledgeDisabledIT",
          "tests": 1,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 2.002,
          "file": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.ai.KnowledgeDisabledIT.xml"
        },
        {
          "suite": "com.guarantee.web.ai.KnowledgeRetrievalIT",
          "tests": 4,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 1.359,
          "file": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.ai.KnowledgeRetrievalIT.xml"
        },
        {
          "suite": "com.guarantee.web.ai.OperationAuditAllLimitIT",
          "tests": 2,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 1.131,
          "file": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.ai.OperationAuditAllLimitIT.xml"
        },
        {
          "suite": "com.guarantee.web.ai.OrderDistributionToolIT",
          "tests": 5,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 1.882,
          "file": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.ai.OrderDistributionToolIT.xml"
        },
        {
          "suite": "com.guarantee.web.ai.OrderTrendToolIT",
          "tests": 4,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 1.013,
          "file": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.ai.OrderTrendToolIT.xml"
        },
        {
          "suite": "com.guarantee.web.ai.PermissionDeniedMappingIT",
          "tests": 2,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.195,
          "file": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.ai.PermissionDeniedMappingIT.xml"
        },
        {
          "suite": "com.guarantee.web.ai.ProposalClaimGuardIT",
          "tests": 4,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.888,
          "file": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.ai.ProposalClaimGuardIT.xml"
        },
        {
          "suite": "com.guarantee.web.ai.ProposalFingerprintClosureTest",
          "tests": 5,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.47,
          "file": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.ai.ProposalFingerprintClosureTest.xml"
        },
        {
          "suite": "com.guarantee.web.ai.ProposalFingerprintIT",
          "tests": 1,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.623,
          "file": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.ai.ProposalFingerprintIT.xml"
        },
        {
          "suite": "com.guarantee.web.ai.ProposalFlowIT",
          "tests": 13,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 1.151,
          "file": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.ai.ProposalFlowIT.xml"
        },
        {
          "suite": "com.guarantee.web.ai.ProposalRepairIT",
          "tests": 1,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.601,
          "file": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.ai.ProposalRepairIT.xml"
        },
        {
          "suite": "com.guarantee.web.ai.ToolRoundCapFallbackIT",
          "tests": 4,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 3.1,
          "file": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.ai.ToolRoundCapFallbackIT.xml"
        },
        {
          "suite": "com.guarantee.web.ai.WebAuditIT",
          "tests": 6,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.317,
          "file": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.ai.WebAuditIT.xml"
        },
        {
          "suite": "com.guarantee.web.AuthIpLockIT",
          "tests": 3,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 5.26,
          "file": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.AuthIpLockIT.xml"
        },
        {
          "suite": "com.guarantee.web.AuthLoginGuardIT",
          "tests": 5,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 9.974,
          "file": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.AuthLoginGuardIT.xml"
        },
        {
          "suite": "com.guarantee.web.LogicalDeleteWebIT",
          "tests": 4,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.709,
          "file": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.LogicalDeleteWebIT.xml"
        },
        {
          "suite": "com.guarantee.web.OnlineSessionIT",
          "tests": 9,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 1.276,
          "file": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.OnlineSessionIT.xml"
        },
        {
          "suite": "com.guarantee.web.RevocationFailClosedIT",
          "tests": 3,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.846,
          "file": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.RevocationFailClosedIT.xml"
        },
        {
          "suite": "com.guarantee.web.TokenLifecycleIT",
          "tests": 6,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.454,
          "file": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.TokenLifecycleIT.xml"
        }
      ],
      "totals": {
        "tests": 682,
        "failures": 0,
        "errors": 0,
        "skipped": 0,
        "timeSeconds": 79.56700000000001,
        "classes": 97,
        "newnessMs": 1790714142744.8584
      }
    },
    "integration": {
      "kind": "failsafe",
      "reportDirs": [
        "guarantee-analysis/target/failsafe-reports",
        "guarantee-system/target/failsafe-reports",
        "guarantee-web/target/failsafe-reports"
      ],
      "suites": [
        {
          "suite": "com.guarantee.analysis.mapper.OrderTrendGranularityIT",
          "tests": 1,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 4.968,
          "file": "guarantee-analysis/target/failsafe-reports/TEST-com.guarantee.analysis.mapper.OrderTrendGranularityIT.xml"
        },
        {
          "suite": "com.guarantee.system.mybatis.LogicalDeleteSchemaIntegrationTest",
          "tests": 11,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 5.975,
          "file": "guarantee-system/target/failsafe-reports/TEST-com.guarantee.system.mybatis.LogicalDeleteSchemaIntegrationTest.xml"
        },
        {
          "suite": "com.guarantee.web.ai.AiConfigWiringIT",
          "tests": 3,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 8.449,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.ai.AiConfigWiringIT.xml"
        },
        {
          "suite": "com.guarantee.web.ai.AiObservabilityIT",
          "tests": 2,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 1.094,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.ai.AiObservabilityIT.xml"
        },
        {
          "suite": "com.guarantee.web.ai.AiToolChainIT",
          "tests": 3,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 1.083,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.ai.AiToolChainIT.xml"
        },
        {
          "suite": "com.guarantee.web.ai.config.AiConfigChangeAuditIT",
          "tests": 4,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 2.362,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.ai.config.AiConfigChangeAuditIT.xml"
        },
        {
          "suite": "com.guarantee.web.ai.config.PromptVersionLifecycleIT",
          "tests": 2,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.761,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.ai.config.PromptVersionLifecycleIT.xml"
        },
        {
          "suite": "com.guarantee.web.ai.EvaluationDeterministicIT",
          "tests": 12,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 8.726,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.ai.EvaluationDeterministicIT.xml"
        },
        {
          "suite": "com.guarantee.web.ai.KnowledgeDisabledIT",
          "tests": 1,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.665,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.ai.KnowledgeDisabledIT.xml"
        },
        {
          "suite": "com.guarantee.web.ai.KnowledgeRetrievalIT",
          "tests": 4,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.748,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.ai.KnowledgeRetrievalIT.xml"
        },
        {
          "suite": "com.guarantee.web.ai.mcp.McpBackendIT",
          "tests": 8,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.831,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.ai.mcp.McpBackendIT.xml"
        },
        {
          "suite": "com.guarantee.web.ai.mcp.McpQuotaIT",
          "tests": 1,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.575,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.ai.mcp.McpQuotaIT.xml"
        },
        {
          "suite": "com.guarantee.web.ai.mcp.McpRateLimitIT",
          "tests": 1,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.6,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.ai.mcp.McpRateLimitIT.xml"
        },
        {
          "suite": "com.guarantee.web.ai.OperationAuditAllLimitIT",
          "tests": 2,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.756,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.ai.OperationAuditAllLimitIT.xml"
        },
        {
          "suite": "com.guarantee.web.ai.OrderDistributionToolIT",
          "tests": 5,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 1.622,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.ai.OrderDistributionToolIT.xml"
        },
        {
          "suite": "com.guarantee.web.ai.OrderTrendToolIT",
          "tests": 4,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 1.03,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.ai.OrderTrendToolIT.xml"
        },
        {
          "suite": "com.guarantee.web.ai.PermissionDeniedMappingIT",
          "tests": 2,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.17,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.ai.PermissionDeniedMappingIT.xml"
        },
        {
          "suite": "com.guarantee.web.ai.ProposalClaimGuardIT",
          "tests": 4,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.644,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.ai.ProposalClaimGuardIT.xml"
        },
        {
          "suite": "com.guarantee.web.ai.ProposalFingerprintIT",
          "tests": 1,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.527,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.ai.ProposalFingerprintIT.xml"
        },
        {
          "suite": "com.guarantee.web.ai.ProposalFlowIT",
          "tests": 13,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.987,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.ai.ProposalFlowIT.xml"
        },
        {
          "suite": "com.guarantee.web.ai.ProposalRepairIT",
          "tests": 1,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.556,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.ai.ProposalRepairIT.xml"
        },
        {
          "suite": "com.guarantee.web.ai.ToolRoundCapFallbackIT",
          "tests": 4,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 3.204,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.ai.ToolRoundCapFallbackIT.xml"
        },
        {
          "suite": "com.guarantee.web.ai.WebAuditIT",
          "tests": 6,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.354,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.ai.WebAuditIT.xml"
        },
        {
          "suite": "com.guarantee.web.AuthIpLockIT",
          "tests": 3,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 5.185,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.AuthIpLockIT.xml"
        },
        {
          "suite": "com.guarantee.web.AuthLoginGuardIT",
          "tests": 5,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 9.98,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.AuthLoginGuardIT.xml"
        },
        {
          "suite": "com.guarantee.web.LogicalDeleteWebIT",
          "tests": 4,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.716,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.LogicalDeleteWebIT.xml"
        },
        {
          "suite": "com.guarantee.web.OnlineSessionIT",
          "tests": 9,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 1.422,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.OnlineSessionIT.xml"
        },
        {
          "suite": "com.guarantee.web.RevocationFailClosedIT",
          "tests": 3,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 2.39,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.RevocationFailClosedIT.xml"
        },
        {
          "suite": "com.guarantee.web.TokenLifecycleIT",
          "tests": 6,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.718,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.TokenLifecycleIT.xml"
        }
      ],
      "totals": {
        "tests": 125,
        "failures": 0,
        "errors": 0,
        "skipped": 0,
        "timeSeconds": 67.098,
        "classes": 29,
        "newnessMs": 1790714230213.2734
      }
    },
    "total": {
      "tests": 807,
      "failures": 0,
      "errors": 0,
      "skipped": 0,
      "classes": 126
    },
    "newestReportAt": "2026-09-29T20:37:10.213Z",
    "newestSourceAt": "2026-09-29T20:20:20.937Z",
    "stale": false
  },
  "evaluation": {
    "script": {
      "file": "scripts/ai-golden-questions.mjs",
      "count": 33,
      "ids": [
        "GQ-01",
        "GQ-02",
        "GQ-03",
        "GQ-04",
        "GQ-05",
        "GQ-06",
        "GQ-07",
        "GQ-08",
        "GQ-09",
        "GQ-10",
        "GQ-11",
        "GQ-12",
        "GQ-13",
        "GQ-14",
        "GQ-15",
        "GQ-16",
        "GQ-17",
        "GQ-18",
        "GQ-19",
        "GQ-20",
        "GQ-21",
        "GQ-22",
        "GQ-23",
        "GQ-24",
        "GQ-25",
        "GQ-26",
        "GQ-27",
        "GQ-28",
        "GQ-29",
        "GQ-30",
        "GQ-31",
        "GQ-32",
        "GQ-33"
      ]
    },
    "doc": {
      "file": "docs/TEST-助手黄金问题集.md",
      "count": 33,
      "ids": [
        "GQ-01",
        "GQ-02",
        "GQ-03",
        "GQ-04",
        "GQ-05",
        "GQ-06",
        "GQ-07",
        "GQ-08",
        "GQ-09",
        "GQ-10",
        "GQ-11",
        "GQ-12",
        "GQ-13",
        "GQ-14",
        "GQ-15",
        "GQ-16",
        "GQ-17",
        "GQ-18",
        "GQ-19",
        "GQ-20",
        "GQ-21",
        "GQ-22",
        "GQ-23",
        "GQ-24",
        "GQ-25",
        "GQ-26",
        "GQ-27",
        "GQ-28",
        "GQ-29",
        "GQ-30",
        "GQ-31",
        "GQ-32",
        "GQ-33"
      ]
    },
    "consistent": true,
    "onlyInScript": [],
    "onlyInDoc": []
  },
  "tools": {
    "java": {
      "dir": "guarantee-ai/src/main/java/com/guarantee/ai/tool",
      "count": 18,
      "names": [
        "getCurrentDate",
        "proposeDepartmentChange",
        "proposeInsuranceTypeChange",
        "proposeOrgChange",
        "proposeRoleChange",
        "proposeUserChange",
        "queryBusinessKnowledge",
        "queryDepartment",
        "queryInsuranceType",
        "queryMyProposals",
        "queryMyToolCalls",
        "queryOperationAudit",
        "queryOrderDistribution",
        "queryOrderSummary",
        "queryOrderTrend",
        "queryOrg",
        "queryRole",
        "queryUser"
      ]
    },
    "registry": {
      "file": "guarantee-ai/src/main/java/com/guarantee/ai/tool/AiToolRegistry.java",
      "readClasses": 11,
      "writeClasses": 5
    },
    "mcp": {
      "file": "tools/business-mcp/src/catalog.ts",
      "count": 13,
      "names": [
        "getCurrentDate",
        "queryBusinessKnowledge",
        "queryDepartment",
        "queryInsuranceType",
        "queryMyProposals",
        "queryMyToolCalls",
        "queryOperationAudit",
        "queryOrderDistribution",
        "queryOrderSummary",
        "queryOrderTrend",
        "queryOrg",
        "queryRole",
        "queryUser"
      ],
      "isSubsetOfJavaTool": true,
      "missingInJava": [],
      "writeToolsExposed": []
    },
    "readOnlyMethods": 13
  },
  "metrics": {
    "file": "docs/REQ-第五阶段-MCP评测与可观测.md",
    "count": 10,
    "names": [
      "ai.chat.duration",
      "ai.chat.requests",
      "ai.chat.rounds",
      "ai.knowledge.retrieval",
      "ai.knowledge.retrieval.duration",
      "ai.prompt.publish",
      "ai.proposals",
      "ai.tokens",
      "ai.tool.calls",
      "ai.tool.duration"
    ]
  }
}
```

评测条数（本脚本 QUESTIONS）：33

分类配比：三维度对比 2、交叉维度（M2.1 新增能力） 2、趋势（M2.1 新增能力） 2、单维度 2、系统域回归 2、降级：缺维度 1、降级：空结果 1、降级：能力边界要给出替代问法 1、越界：业务数据写操作 1、越界：导出/预测 1、知识·定义（有收录） 4、知识·混合（定义 + 统计） 2、知识·未收录 2、知识·越权（只读用户不得看到审计口径） 1、知识·降级（关掉知识层） 1、单维度统计 3、交叉/趋势/分布 1、越界拒答 2、降级/失败 1、定义/知识类 1

确定性集覆盖：GQ-07、GQ-12、GQ-16、GQ-17、GQ-18、GQ-19、GQ-20、GQ-21、GQ-22、GQ-24、GQ-26、GQ-33

