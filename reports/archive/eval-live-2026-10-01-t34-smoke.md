# 助手评测报告（live）

> 生成时间：2026-09-30T18:05:33.935Z
> 目标：`http://127.0.0.1:8092`（账号 admin）
> 数据基线：✅ 总订单量 150000（投标 100000 / 履约 50000），数据区间 2025-01-01 ~ 2026-09-30（seed 未自动校验：无 HTTP 出口）
> 结果：**通过 1/1**，失败 0，未跑 0

| 套件 | 状态 | 说明 |
|---|---|---|
| deterministic | not-run |  |
| live | ok | 全部通过 |

## 打分（REQ-MCP-06）

| 指标 | 值 |
|---|---|
| 通过率 | 1 |
| 口径正确率（有工具调用必有口径行） | — |
| 引用完整率（知识类必有来源行） | — |
| 禁用术语违规数 | 0 |
| 轮次 min/avg/max | 0 / 0 / 0 |
| 耗时(s) min/avg/max | 2.6 / 2.6 / 2.6 |
| 工具调用 min/avg/max | 0 / 0 / 0 |

## 逐题结果

| 编号 | 类别 | 结果 | 工具调用 | 轮次 | 连续周期 | 耗时(s) | 正文字数 | 备注 |
|---|---|---|---|---|---|---|---|---|
| GQ-31 | 越界拒答 | ✅ | 0 | 0 | — | 2.6 | 235 |  |

## 单一事实源（REQ-MCP-12）

来源：`scripts/single-source-of-truth.mjs`

```json
{
  "generatedAt": "2026-09-30T18:05:33.655Z",
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
          "tests": 13,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.175,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.config.AiConfigCatalogTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782938479.517
        },
        {
          "suite": "com.guarantee.ai.config.AiConfigServiceTest",
          "tests": 16,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 1.778,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.config.AiConfigServiceTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782940265.412
        },
        {
          "suite": "com.guarantee.ai.config.PromptVersionServiceTest",
          "tests": 19,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.723,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.config.PromptVersionServiceTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782940990.7136
        },
        {
          "suite": "com.guarantee.ai.knowledge.KnowledgeDocumentParserTest",
          "tests": 19,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.058,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.knowledge.KnowledgeDocumentParserTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782941049.714
        },
        {
          "suite": "com.guarantee.ai.knowledge.KnowledgeImporterTest",
          "tests": 9,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.018,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.knowledge.KnowledgeImporterTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782941073.7383
        },
        {
          "suite": "com.guarantee.ai.knowledge.KnowledgeMapperXmlTest",
          "tests": 2,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.208,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.knowledge.KnowledgeMapperXmlTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782941279.1807
        },
        {
          "suite": "com.guarantee.ai.knowledge.KnowledgeServiceTest",
          "tests": 16,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.038,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.knowledge.KnowledgeServiceTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782941321.729
        },
        {
          "suite": "com.guarantee.ai.knowledge.KnowledgeSourceLoaderTest",
          "tests": 4,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.068,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.knowledge.KnowledgeSourceLoaderTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782941389.803
        },
        {
          "suite": "com.guarantee.ai.knowledge.KnowledgeTermsTest",
          "tests": 4,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.002,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.knowledge.KnowledgeTermsTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782941394.8206
        },
        {
          "suite": "com.guarantee.ai.mcp.McpControllerProtocolTest",
          "tests": 12,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.177,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.mcp.McpControllerProtocolTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782941576.9116
        },
        {
          "suite": "com.guarantee.ai.mcp.McpEnabledConditionTest",
          "tests": 3,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.348,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.mcp.McpEnabledConditionTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782941929.643
        },
        {
          "suite": "com.guarantee.ai.mcp.McpRateLimiterTest",
          "tests": 8,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.14,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.mcp.McpRateLimiterTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782942069.6792
        },
        {
          "suite": "com.guarantee.ai.mcp.McpTokenMappingTest",
          "tests": 6,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.011,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.mcp.McpTokenMappingTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782942079.685
        },
        {
          "suite": "com.guarantee.ai.mcp.McpTokenServiceTest",
          "tests": 17,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.056,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.mcp.McpTokenServiceTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782942137.2114
        },
        {
          "suite": "com.guarantee.ai.mcp.McpToolCatalogTest",
          "tests": 6,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.009,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.mcp.McpToolCatalogTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782942144.2107
        },
        {
          "suite": "com.guarantee.ai.mcp.McpToolInvokerGateTest",
          "tests": 5,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.153,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.mcp.McpToolInvokerGateTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782942299.6013
        },
        {
          "suite": "com.guarantee.ai.mcp.McpToolInvokerTest",
          "tests": 13,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.059,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.mcp.McpToolInvokerTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782942360.1213
        },
        {
          "suite": "com.guarantee.ai.metrics.AiChatMetricsTest",
          "tests": 8,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.036,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.metrics.AiChatMetricsTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782942393.6448
        },
        {
          "suite": "com.guarantee.ai.metrics.AiTurnMetricMappingTest",
          "tests": 6,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.007,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.metrics.AiTurnMetricMappingTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782942403.649
        },
        {
          "suite": "com.guarantee.ai.metrics.TurnMetricServiceTest",
          "tests": 19,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.039,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.metrics.TurnMetricServiceTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782942441.8389
        },
        {
          "suite": "com.guarantee.ai.service.AiChatServiceBudgetGuardTest",
          "tests": 5,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.424,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.service.AiChatServiceBudgetGuardTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782942878.0737
        },
        {
          "suite": "com.guarantee.ai.service.AiChatServiceRetractionNoticeTest",
          "tests": 5,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.002,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.service.AiChatServiceRetractionNoticeTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782942883.0737
        },
        {
          "suite": "com.guarantee.ai.service.AiConfigWiringTest",
          "tests": 12,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.114,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.service.AiConfigWiringTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782942999.265
        },
        {
          "suite": "com.guarantee.ai.service.DataSourceClaimGuardTest",
          "tests": 14,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.009,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.service.DataSourceClaimGuardTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782943012.2622
        },
        {
          "suite": "com.guarantee.ai.service.KnowledgeClaimGuardTest",
          "tests": 11,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.006,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.service.KnowledgeClaimGuardTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782943022.2627
        },
        {
          "suite": "com.guarantee.ai.service.NumberClaimGuardTest",
          "tests": 6,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.004,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.service.NumberClaimGuardTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782943032.2627
        },
        {
          "suite": "com.guarantee.ai.service.OperationAuditServiceTest",
          "tests": 16,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.072,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.service.OperationAuditServiceTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782943086.2686
        },
        {
          "suite": "com.guarantee.ai.service.ProposalClaimGuardTest",
          "tests": 11,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.041,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.service.ProposalClaimGuardTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782943126.46
        },
        {
          "suite": "com.guarantee.ai.service.ProposalNumberGuardTest",
          "tests": 9,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.004,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.service.ProposalNumberGuardTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782943132.4592
        },
        {
          "suite": "com.guarantee.ai.service.ProposalServiceMetricsTest",
          "tests": 9,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.268,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.service.ProposalServiceMetricsTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782943410.897
        },
        {
          "suite": "com.guarantee.ai.service.ProposalServiceReusePublishTest",
          "tests": 2,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.003,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.service.ProposalServiceReusePublishTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782943419.8972
        },
        {
          "suite": "com.guarantee.ai.service.ToolExecutionTimeoutGuardTest",
          "tests": 5,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.145,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.service.ToolExecutionTimeoutGuardTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782943561.13
        },
        {
          "suite": "com.guarantee.ai.time.TimeSemanticParserTest",
          "tests": 16,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.045,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.time.TimeSemanticParserTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782943601.6523
        },
        {
          "suite": "com.guarantee.ai.tool.AiPermissionGuardTest",
          "tests": 6,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.002,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.tool.AiPermissionGuardTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782943607.6577
        },
        {
          "suite": "com.guarantee.ai.tool.AiToolRegistryTest",
          "tests": 15,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.55,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.tool.AiToolRegistryTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782944156.034
        },
        {
          "suite": "com.guarantee.ai.tool.DataMetricsTest",
          "tests": 10,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.027,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.tool.DataMetricsTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782944182.2668
        },
        {
          "suite": "com.guarantee.ai.tool.DataSourceTextTest",
          "tests": 11,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.005,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.tool.DataSourceTextTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782944187.7715
        },
        {
          "suite": "com.guarantee.ai.tool.InsuranceTypeQueryToolTest",
          "tests": 4,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.009,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.tool.InsuranceTypeQueryToolTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782944201.7783
        },
        {
          "suite": "com.guarantee.ai.tool.MyProposalsQueryToolTest",
          "tests": 8,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.008,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.tool.MyProposalsQueryToolTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782944214.7773
        },
        {
          "suite": "com.guarantee.ai.tool.OrderDistributionToolTest",
          "tests": 9,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.017,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.tool.OrderDistributionToolTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782944229.7776
        },
        {
          "suite": "com.guarantee.ai.tool.OrderTrendToolTest",
          "tests": 8,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.015,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.tool.OrderTrendToolTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782944241.7766
        },
        {
          "suite": "com.guarantee.ai.tool.QueryBusinessKnowledgeToolTest",
          "tests": 6,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.035,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.tool.QueryBusinessKnowledgeToolTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782944278.7832
        },
        {
          "suite": "com.guarantee.ai.tool.SanitizingToolCallbackTest",
          "tests": 4,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.009,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.tool.SanitizingToolCallbackTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782944283.7832
        },
        {
          "suite": "com.guarantee.ai.tool.SensitiveFieldMaskerTest",
          "tests": 10,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.004,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.tool.SensitiveFieldMaskerTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782944289.8667
        },
        {
          "suite": "com.guarantee.ai.tool.ToolResultSanitizerTest",
          "tests": 9,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.011,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.tool.ToolResultSanitizerTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782944299.8728
        },
        {
          "suite": "com.guarantee.ai.tool.TurnFactsTest",
          "tests": 12,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.01,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.tool.TurnFactsTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782944311.8716
        },
        {
          "suite": "com.guarantee.ai.tool.write.RoleProposalToolPermissionDisplayTest",
          "tests": 3,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.008,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.tool.write.RoleProposalToolPermissionDisplayTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782944318.612
        },
        {
          "suite": "com.guarantee.ai.tool.write.WriteToolResultTest",
          "tests": 2,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.002,
          "file": "guarantee-ai/target/surefire-reports/TEST-com.guarantee.ai.tool.write.WriteToolResultTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782944323.6106
        },
        {
          "suite": "com.guarantee.analysis.dto.OrderTrendQueryTest",
          "tests": 2,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.093,
          "file": "guarantee-analysis/target/surefire-reports/TEST-com.guarantee.analysis.dto.OrderTrendQueryTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782928367.3208
        },
        {
          "suite": "com.guarantee.analysis.mapper.DistributionDimensionNameTest",
          "tests": 2,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.012,
          "file": "guarantee-analysis/target/surefire-reports/TEST-com.guarantee.analysis.mapper.DistributionDimensionNameTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782928384.8357
        },
        {
          "suite": "com.guarantee.analysis.mapper.TrendGranularityMapperXmlTest",
          "tests": 2,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.005,
          "file": "guarantee-analysis/target/surefire-reports/TEST-com.guarantee.analysis.mapper.TrendGranularityMapperXmlTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782928389.837
        },
        {
          "suite": "com.guarantee.auth.config.SecurityConfigCorsTest",
          "tests": 2,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.234,
          "file": "guarantee-auth/target/surefire-reports/TEST-com.guarantee.auth.config.SecurityConfigCorsTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782921328.681
        },
        {
          "suite": "com.guarantee.auth.security.JwtTokenProviderSecretTest",
          "tests": 11,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.329,
          "file": "guarantee-auth/target/surefire-reports/TEST-com.guarantee.auth.security.JwtTokenProviderSecretTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782921660.9348
        },
        {
          "suite": "com.guarantee.auth.security.TokenRevocationServiceFailureModeTest",
          "tests": 12,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 1.862,
          "file": "guarantee-auth/target/surefire-reports/TEST-com.guarantee.auth.security.TokenRevocationServiceFailureModeTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782923543.024
        },
        {
          "suite": "com.guarantee.auth.service.AuthServiceServiceAccountLoginTest",
          "tests": 3,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.161,
          "file": "guarantee-auth/target/surefire-reports/TEST-com.guarantee.auth.service.AuthServiceServiceAccountLoginTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782923705.0767
        },
        {
          "suite": "com.guarantee.common.exception.GlobalExceptionHandlerTest",
          "tests": 3,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 3.143,
          "file": "guarantee-common/target/surefire-reports/TEST-com.guarantee.common.exception.GlobalExceptionHandlerTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782902012.9731
        },
        {
          "suite": "com.guarantee.common.region.RegionCodePrefixTest",
          "tests": 4,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.011,
          "file": "guarantee-common/target/surefire-reports/TEST-com.guarantee.common.region.RegionCodePrefixTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782902022.978
        },
        {
          "suite": "com.guarantee.order.mapper.OrderDimensionNamePreservationTest",
          "tests": 2,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.104,
          "file": "guarantee-order/target/surefire-reports/TEST-com.guarantee.order.mapper.OrderDimensionNamePreservationTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782925979.599
        },
        {
          "suite": "com.guarantee.system.controller.DepartmentControllerTreeAuthTest",
          "tests": 3,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.119,
          "file": "guarantee-system/target/surefire-reports/TEST-com.guarantee.system.controller.DepartmentControllerTreeAuthTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782905187.4995
        },
        {
          "suite": "com.guarantee.system.controller.OrderFilterDictionaryPermissionTest",
          "tests": 5,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.194,
          "file": "guarantee-system/target/surefire-reports/TEST-com.guarantee.system.controller.OrderFilterDictionaryPermissionTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782905386.8982
        },
        {
          "suite": "com.guarantee.system.entity.SysUserAccountTypeTest",
          "tests": 2,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.004,
          "file": "guarantee-system/target/surefire-reports/TEST-com.guarantee.system.entity.SysUserAccountTypeTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782905393.9011
        },
        {
          "suite": "com.guarantee.system.mapper.SysUserAccountTypeMapperXmlTest",
          "tests": 3,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.263,
          "file": "guarantee-system/target/surefire-reports/TEST-com.guarantee.system.mapper.SysUserAccountTypeMapperXmlTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782905653.443
        },
        {
          "suite": "com.guarantee.system.mybatis.LogicalDeletePermissionsTest",
          "tests": 4,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.013,
          "file": "guarantee-system/target/surefire-reports/TEST-com.guarantee.system.mybatis.LogicalDeletePermissionsTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782905666.9548
        },
        {
          "suite": "com.guarantee.system.mybatis.LogicalDeleteSchemaIntegrationTest",
          "tests": 11,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 5.812,
          "file": "guarantee-system/target/surefire-reports/TEST-com.guarantee.system.mybatis.LogicalDeleteSchemaIntegrationTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782911485.1814
        },
        {
          "suite": "com.guarantee.system.mybatis.LogicalDeleteSqlRewriterTest",
          "tests": 10,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.013,
          "file": "guarantee-system/target/surefire-reports/TEST-com.guarantee.system.mybatis.LogicalDeleteSqlRewriterTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782911497.182
        },
        {
          "suite": "com.guarantee.system.scope.DataScopeIntegrationTest",
          "tests": 12,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.154,
          "file": "guarantee-system/target/surefire-reports/TEST-com.guarantee.system.scope.DataScopeIntegrationTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782911652.2485
        },
        {
          "suite": "com.guarantee.system.scope.DataScopeTest",
          "tests": 8,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.004,
          "file": "guarantee-system/target/surefire-reports/TEST-com.guarantee.system.scope.DataScopeTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782911659.2473
        },
        {
          "suite": "com.guarantee.system.service.InsuranceTypeAmountBoundIntegrationTest",
          "tests": 6,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.089,
          "file": "guarantee-system/target/surefire-reports/TEST-com.guarantee.system.service.InsuranceTypeAmountBoundIntegrationTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782911749.9885
        },
        {
          "suite": "com.guarantee.system.service.InsuranceTypeFilterOptionsIntegrationTest",
          "tests": 7,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 1.023,
          "file": "guarantee-system/target/surefire-reports/TEST-com.guarantee.system.service.InsuranceTypeFilterOptionsIntegrationTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782912769.4204
        },
        {
          "suite": "com.guarantee.system.service.LogicalDeleteServiceIntegrationTest",
          "tests": 14,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 1.978,
          "file": "guarantee-system/target/surefire-reports/TEST-com.guarantee.system.service.LogicalDeleteServiceIntegrationTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782914751.5437
        },
        {
          "suite": "com.guarantee.system.service.OrgFilterOptionsIntegrationTest",
          "tests": 6,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.78,
          "file": "guarantee-system/target/surefire-reports/TEST-com.guarantee.system.service.OrgFilterOptionsIntegrationTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782915526.452
        },
        {
          "suite": "com.guarantee.system.service.RegionServiceIntegrationTest",
          "tests": 9,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 2.052,
          "file": "guarantee-system/target/surefire-reports/TEST-com.guarantee.system.service.RegionServiceIntegrationTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782917580.599
        },
        {
          "suite": "com.guarantee.system.service.RoleServicePermissionDisplayTest",
          "tests": 6,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.695,
          "file": "guarantee-system/target/surefire-reports/TEST-com.guarantee.system.service.RoleServicePermissionDisplayTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782918284.9797
        },
        {
          "suite": "com.guarantee.system.service.SysUserAccountTypeIntegrationTest",
          "tests": 6,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.131,
          "file": "guarantee-system/target/surefire-reports/TEST-com.guarantee.system.service.SysUserAccountTypeIntegrationTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782918416.707
        },
        {
          "suite": "com.guarantee.system.service.UserServiceRoleDisplayTest",
          "tests": 8,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.114,
          "file": "guarantee-system/target/surefire-reports/TEST-com.guarantee.system.service.UserServiceRoleDisplayTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782918532.7256
        },
        {
          "suite": "com.guarantee.web.ai.config.AiConfigChangeAuditTest",
          "tests": 6,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 2.01,
          "file": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.ai.config.AiConfigChangeAuditTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782948928.7744
        },
        {
          "suite": "com.guarantee.web.ai.ProposalFingerprintClosureTest",
          "tests": 5,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.49,
          "file": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.ai.ProposalFingerprintClosureTest.xml",
          "kind": "surefire",
          "mtimeMs": 1790782949424.809
        }
      ],
      "totals": {
        "tests": 617,
        "failures": 0,
        "errors": 0,
        "skipped": 0,
        "timeSeconds": 27.843999999999998,
        "classes": 77,
        "newnessMs": 1790782949424.809
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
          "timeSeconds": 4.874,
          "file": "guarantee-analysis/target/failsafe-reports/TEST-com.guarantee.analysis.mapper.OrderTrendGranularityIT.xml",
          "kind": "failsafe",
          "mtimeMs": 1790782935478.453
        },
        {
          "suite": "com.guarantee.web.ai.AiConfigWiringIT",
          "tests": 3,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 8.562,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.ai.AiConfigWiringIT.xml",
          "kind": "failsafe",
          "mtimeMs": 1790782960851.818
        },
        {
          "suite": "com.guarantee.web.ai.AiObservabilityIT",
          "tests": 2,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 1.374,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.ai.AiObservabilityIT.xml",
          "kind": "failsafe",
          "mtimeMs": 1790782962224.0776
        },
        {
          "suite": "com.guarantee.web.ai.AiToolChainIT",
          "tests": 3,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 1.043,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.ai.AiToolChainIT.xml",
          "kind": "failsafe",
          "mtimeMs": 1790782963266.2585
        },
        {
          "suite": "com.guarantee.web.ai.config.AiConfigChangeAuditIT",
          "tests": 4,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 2.299,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.ai.config.AiConfigChangeAuditIT.xml",
          "kind": "failsafe",
          "mtimeMs": 1790782965570.865
        },
        {
          "suite": "com.guarantee.web.ai.config.PromptVersionLifecycleIT",
          "tests": 2,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.716,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.ai.config.PromptVersionLifecycleIT.xml",
          "kind": "failsafe",
          "mtimeMs": 1790782966284.424
        },
        {
          "suite": "com.guarantee.web.ai.EvaluationDeterministicIT",
          "tests": 12,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 1.347,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.ai.EvaluationDeterministicIT.xml",
          "kind": "failsafe",
          "mtimeMs": 1790782967638.8132
        },
        {
          "suite": "com.guarantee.web.ai.KnowledgeDisabledIT",
          "tests": 1,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.671,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.ai.KnowledgeDisabledIT.xml",
          "kind": "failsafe",
          "mtimeMs": 1790782968302.025
        },
        {
          "suite": "com.guarantee.web.ai.KnowledgeRetrievalIT",
          "tests": 4,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.732,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.ai.KnowledgeRetrievalIT.xml",
          "kind": "failsafe",
          "mtimeMs": 1790782969038.2908
        },
        {
          "suite": "com.guarantee.web.ai.mcp.McpBackendIT",
          "tests": 8,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.836,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.ai.mcp.McpBackendIT.xml",
          "kind": "failsafe",
          "mtimeMs": 1790782969877.4746
        },
        {
          "suite": "com.guarantee.web.ai.mcp.McpQuotaIT",
          "tests": 1,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.599,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.ai.mcp.McpQuotaIT.xml",
          "kind": "failsafe",
          "mtimeMs": 1790782970477.3157
        },
        {
          "suite": "com.guarantee.web.ai.mcp.McpRateLimitIT",
          "tests": 1,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.528,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.ai.mcp.McpRateLimitIT.xml",
          "kind": "failsafe",
          "mtimeMs": 1790782971003.9968
        },
        {
          "suite": "com.guarantee.web.ai.OperationAuditAllLimitIT",
          "tests": 2,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.752,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.ai.OperationAuditAllLimitIT.xml",
          "kind": "failsafe",
          "mtimeMs": 1790782971756.4463
        },
        {
          "suite": "com.guarantee.web.ai.OrderDistributionToolIT",
          "tests": 5,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 1.494,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.ai.OrderDistributionToolIT.xml",
          "kind": "failsafe",
          "mtimeMs": 1790782973251.0022
        },
        {
          "suite": "com.guarantee.web.ai.OrderTrendToolIT",
          "tests": 4,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 1.01,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.ai.OrderTrendToolIT.xml",
          "kind": "failsafe",
          "mtimeMs": 1790782974261.0852
        },
        {
          "suite": "com.guarantee.web.ai.PermissionDeniedMappingIT",
          "tests": 2,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.161,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.ai.PermissionDeniedMappingIT.xml",
          "kind": "failsafe",
          "mtimeMs": 1790782974422.9678
        },
        {
          "suite": "com.guarantee.web.ai.ProposalClaimGuardIT",
          "tests": 4,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.621,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.ai.ProposalClaimGuardIT.xml",
          "kind": "failsafe",
          "mtimeMs": 1790782975048.448
        },
        {
          "suite": "com.guarantee.web.ai.ProposalFingerprintIT",
          "tests": 1,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.497,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.ai.ProposalFingerprintIT.xml",
          "kind": "failsafe",
          "mtimeMs": 1790782975542.2715
        },
        {
          "suite": "com.guarantee.web.ai.ProposalFlowIT",
          "tests": 13,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.926,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.ai.ProposalFlowIT.xml",
          "kind": "failsafe",
          "mtimeMs": 1790782976477.287
        },
        {
          "suite": "com.guarantee.web.ai.ProposalRepairIT",
          "tests": 1,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.785,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.ai.ProposalRepairIT.xml",
          "kind": "failsafe",
          "mtimeMs": 1790782977255.6265
        },
        {
          "suite": "com.guarantee.web.ai.ToolRoundCapFallbackIT",
          "tests": 4,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 3.127,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.ai.ToolRoundCapFallbackIT.xml",
          "kind": "failsafe",
          "mtimeMs": 1790782980385.0603
        },
        {
          "suite": "com.guarantee.web.ai.WebAuditIT",
          "tests": 6,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.25,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.ai.WebAuditIT.xml",
          "kind": "failsafe",
          "mtimeMs": 1790782980637.3838
        },
        {
          "suite": "com.guarantee.web.AuthIpLockIT",
          "tests": 3,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 5.17,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.AuthIpLockIT.xml",
          "kind": "failsafe",
          "mtimeMs": 1790782985804.469
        },
        {
          "suite": "com.guarantee.web.AuthLoginGuardIT",
          "tests": 5,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 9.951,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.AuthLoginGuardIT.xml",
          "kind": "failsafe",
          "mtimeMs": 1790782995757.6
        },
        {
          "suite": "com.guarantee.web.LogicalDeleteWebIT",
          "tests": 4,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.646,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.LogicalDeleteWebIT.xml",
          "kind": "failsafe",
          "mtimeMs": 1790782996403.5317
        },
        {
          "suite": "com.guarantee.web.OnlineSessionIT",
          "tests": 9,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 1.196,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.OnlineSessionIT.xml",
          "kind": "failsafe",
          "mtimeMs": 1790782997602.7173
        },
        {
          "suite": "com.guarantee.web.RevocationFailClosedIT",
          "tests": 3,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 2.347,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.RevocationFailClosedIT.xml",
          "kind": "failsafe",
          "mtimeMs": 1790782999950.2126
        },
        {
          "suite": "com.guarantee.web.TokenLifecycleIT",
          "tests": 6,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "timeSeconds": 0.581,
          "file": "guarantee-web/target/failsafe-reports/TEST-com.guarantee.web.TokenLifecycleIT.xml",
          "kind": "failsafe",
          "mtimeMs": 1790783000535.3389
        }
      ],
      "totals": {
        "tests": 114,
        "failures": 0,
        "errors": 0,
        "skipped": 0,
        "timeSeconds": 53.095000000000006,
        "classes": 28,
        "newnessMs": 1790783000535.3389
      }
    },
    "total": {
      "tests": 731,
      "failures": 0,
      "errors": 0,
      "skipped": 0,
      "classes": 105
    },
    "dedup": {
      "droppedSuites": 23,
      "droppedTests": 99,
      "rule": "按类名归属去重：类名以 IT 结尾归 failsafe（surefire 排除 **/*IT.java），其余归 surefire（failsafe 只 include **/*IT.java）",
      "details": [
        {
          "suite": "com.guarantee.analysis.mapper.OrderTrendGranularityIT",
          "droppedKind": "surefire",
          "keptKind": "failsafe",
          "droppedFile": "guarantee-analysis/target/surefire-reports/TEST-com.guarantee.analysis.mapper.OrderTrendGranularityIT.xml",
          "droppedTests": 1
        },
        {
          "suite": "com.guarantee.system.mybatis.LogicalDeleteSchemaIntegrationTest",
          "droppedKind": "failsafe",
          "keptKind": "surefire",
          "droppedFile": "guarantee-system/target/failsafe-reports/TEST-com.guarantee.system.mybatis.LogicalDeleteSchemaIntegrationTest.xml",
          "droppedTests": 11
        },
        {
          "suite": "com.guarantee.web.ai.AiConfigWiringIT",
          "droppedKind": "surefire",
          "keptKind": "failsafe",
          "droppedFile": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.ai.AiConfigWiringIT.xml",
          "droppedTests": 3
        },
        {
          "suite": "com.guarantee.web.ai.AiToolChainIT",
          "droppedKind": "surefire",
          "keptKind": "failsafe",
          "droppedFile": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.ai.AiToolChainIT.xml",
          "droppedTests": 3
        },
        {
          "suite": "com.guarantee.web.ai.config.AiConfigChangeAuditIT",
          "droppedKind": "surefire",
          "keptKind": "failsafe",
          "droppedFile": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.ai.config.AiConfigChangeAuditIT.xml",
          "droppedTests": 4
        },
        {
          "suite": "com.guarantee.web.ai.KnowledgeDisabledIT",
          "droppedKind": "surefire",
          "keptKind": "failsafe",
          "droppedFile": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.ai.KnowledgeDisabledIT.xml",
          "droppedTests": 1
        },
        {
          "suite": "com.guarantee.web.ai.KnowledgeRetrievalIT",
          "droppedKind": "surefire",
          "keptKind": "failsafe",
          "droppedFile": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.ai.KnowledgeRetrievalIT.xml",
          "droppedTests": 4
        },
        {
          "suite": "com.guarantee.web.ai.OperationAuditAllLimitIT",
          "droppedKind": "surefire",
          "keptKind": "failsafe",
          "droppedFile": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.ai.OperationAuditAllLimitIT.xml",
          "droppedTests": 2
        },
        {
          "suite": "com.guarantee.web.ai.OrderDistributionToolIT",
          "droppedKind": "surefire",
          "keptKind": "failsafe",
          "droppedFile": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.ai.OrderDistributionToolIT.xml",
          "droppedTests": 5
        },
        {
          "suite": "com.guarantee.web.ai.OrderTrendToolIT",
          "droppedKind": "surefire",
          "keptKind": "failsafe",
          "droppedFile": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.ai.OrderTrendToolIT.xml",
          "droppedTests": 4
        },
        {
          "suite": "com.guarantee.web.ai.PermissionDeniedMappingIT",
          "droppedKind": "surefire",
          "keptKind": "failsafe",
          "droppedFile": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.ai.PermissionDeniedMappingIT.xml",
          "droppedTests": 2
        },
        {
          "suite": "com.guarantee.web.ai.ProposalClaimGuardIT",
          "droppedKind": "surefire",
          "keptKind": "failsafe",
          "droppedFile": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.ai.ProposalClaimGuardIT.xml",
          "droppedTests": 4
        },
        {
          "suite": "com.guarantee.web.ai.ProposalFingerprintIT",
          "droppedKind": "surefire",
          "keptKind": "failsafe",
          "droppedFile": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.ai.ProposalFingerprintIT.xml",
          "droppedTests": 1
        },
        {
          "suite": "com.guarantee.web.ai.ProposalFlowIT",
          "droppedKind": "surefire",
          "keptKind": "failsafe",
          "droppedFile": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.ai.ProposalFlowIT.xml",
          "droppedTests": 13
        },
        {
          "suite": "com.guarantee.web.ai.ProposalRepairIT",
          "droppedKind": "surefire",
          "keptKind": "failsafe",
          "droppedFile": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.ai.ProposalRepairIT.xml",
          "droppedTests": 1
        },
        {
          "suite": "com.guarantee.web.ai.ToolRoundCapFallbackIT",
          "droppedKind": "surefire",
          "keptKind": "failsafe",
          "droppedFile": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.ai.ToolRoundCapFallbackIT.xml",
          "droppedTests": 4
        },
        {
          "suite": "com.guarantee.web.ai.WebAuditIT",
          "droppedKind": "surefire",
          "keptKind": "failsafe",
          "droppedFile": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.ai.WebAuditIT.xml",
          "droppedTests": 6
        },
        {
          "suite": "com.guarantee.web.AuthIpLockIT",
          "droppedKind": "surefire",
          "keptKind": "failsafe",
          "droppedFile": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.AuthIpLockIT.xml",
          "droppedTests": 3
        },
        {
          "suite": "com.guarantee.web.AuthLoginGuardIT",
          "droppedKind": "surefire",
          "keptKind": "failsafe",
          "droppedFile": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.AuthLoginGuardIT.xml",
          "droppedTests": 5
        },
        {
          "suite": "com.guarantee.web.LogicalDeleteWebIT",
          "droppedKind": "surefire",
          "keptKind": "failsafe",
          "droppedFile": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.LogicalDeleteWebIT.xml",
          "droppedTests": 4
        },
        {
          "suite": "com.guarantee.web.OnlineSessionIT",
          "droppedKind": "surefire",
          "keptKind": "failsafe",
          "droppedFile": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.OnlineSessionIT.xml",
          "droppedTests": 9
        },
        {
          "suite": "com.guarantee.web.RevocationFailClosedIT",
          "droppedKind": "surefire",
          "keptKind": "failsafe",
          "droppedFile": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.RevocationFailClosedIT.xml",
          "droppedTests": 3
        },
        {
          "suite": "com.guarantee.web.TokenLifecycleIT",
          "droppedKind": "surefire",
          "keptKind": "failsafe",
          "droppedFile": "guarantee-web/target/surefire-reports/TEST-com.guarantee.web.TokenLifecycleIT.xml",
          "droppedTests": 6
        }
      ]
    },
    "newestReportAt": "2026-09-30T15:43:20.535Z",
    "newestSourceAt": "2026-09-30T18:05:29.788Z",
    "stale": true
  },
  "evaluation": {
    "script": {
      "file": "scripts/ai-golden-questions.mjs",
      "count": 35,
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
        "GQ-33",
        "GQ-34",
        "GQ-35"
      ]
    },
    "doc": {
      "file": "docs/TEST-助手黄金问题集.md",
      "count": 35,
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
        "GQ-33",
        "GQ-34",
        "GQ-35"
      ]
    },
    "consistent": true,
    "onlyInScript": [],
    "onlyInDoc": []
  },
  "tools": {
    "java": {
      "dir": "guarantee-ai/src/main/java/com/guarantee/ai/tool",
      "count": 19,
      "names": [
        "getCurrentDate",
        "proposeDepartmentChange",
        "proposeInsuranceTypeChange",
        "proposeOrgChange",
        "proposeRoleChange",
        "proposeUserChange",
        "queryBusinessKnowledge",
        "queryDepartment",
        "queryEnterpriseAnalysis",
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
    "readOnlyMethods": 14
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

评测条数（本脚本 QUESTIONS）：35

分类配比：三维度对比 2、交叉维度（M2.1 新增能力） 2、趋势（M2.1 新增能力） 2、单维度 2、系统域回归 2、降级：缺维度 1、降级：空结果 1、降级：能力边界要给出替代问法 1、越界：业务数据写操作 1、越界：导出/预测 1、知识·定义（有收录） 4、知识·混合（定义 + 统计） 2、知识·未收录 2、知识·越权（只读用户不得看到审计口径） 1、知识·降级（关掉知识层） 1、单维度统计 3、交叉/趋势/分布 1、越界拒答 4、降级/失败 1、定义/知识类 1

确定性集覆盖：GQ-07、GQ-12、GQ-16、GQ-17、GQ-18、GQ-19、GQ-20、GQ-21、GQ-22、GQ-24、GQ-26、GQ-33

