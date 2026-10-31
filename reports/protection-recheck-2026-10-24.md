# 已有防护复验与检索诊断（2026-10-24）

本轮只增加诊断测试和本报告，没有修改业务实现、知识条目、排序或提示词，没有调用付费模型。

## 已运行

- 定向单元测试：guarantee-ai 131 项、guarantee-web 指纹闭环 5 项，均无失败/错误。原始记录：`output/protection-recheck-full.log`。
- 覆盖知识权限与有效期过滤、来源纠正、提案编号校验、假提案声明纠正、工具超时记录及错误回灌、指纹变化与确认拒绝等机制。Stub/Mock 通过不代表真实模型行为通过。
- 随包知识的检索诊断：8 个探针，5 个正确条目进入 top-3，3 个未进入。原始记录：`output/retrieval-probe.log`。这是探索样本，不能作为整体准确率或独立验收集。
- 定向集成测试尝试了 ProposalFlowIT / ProposalClaimGuardIT / ProposalFingerprintIT / KnowledgeRetrievalIT / KnowledgeDisabledIT / ProposalRepairIT。沙箱首次报 getsockopt Permission denied；获准外部重跑后，默认 MySQL 127.0.0.1:3307 Connection refused，Spring 上下文未成功启动。不能将这些错误记作业务断言失败，也不能声称集成回归通过。

## 真实检索失败

使用 KnowledgeSourceLoader 的全部随包条目，经内存 Mapper 调用生产 KnowledgeService.search；无权限参数、domain=null、limit=3。验证范围是随包语料和生产检索实现，不代表运行中数据库内容或端到端模型回答。

| 问题 | 期望条目 | 实际 top-3 | 结果 |
|---|---|---|---|
| 部门停用有什么条件 | KB-SYSTEM-0005 | SYSTEM-0004 / SYSTEM-0005 / SYSTEM-0006 | 命中 |
| 科室下面还有在职人员，能暂停使用吗 | KB-SYSTEM-0005 | SYSTEM-0007 / SYSTEM-0012 / CONCEPT-0002 | 漏召回；“科室”是否纳入业务同义词还需确认 |
| 停用与删除的区别 | KB-SYSTEM-0011 | SYSTEM-0009 / SYSTEM-0011 / SYSTEM-0005 | 命中 |
| 暂时不用和彻底移除有什么不同 | KB-SYSTEM-0011 | ORDER-0005 / CONCEPT-0001 / ORDER-0001 | 漏召回；“彻底移除”有物理删除歧义，不能直接硬编码同义词 |
| 保额区间不填是什么意思 | KB-ORDER-0001 | ORDER-0001 / CONCEPT-0001 / ORDER-0005 | 命中 |
| 承保额度上下界留空会限制接单吗 | KB-ORDER-0001 | ORDER-0001 / CONCEPT-0001 / ORDER-0005 | 命中 |
| 金额保留几位小数 | KB-ORDER-0004 | ORDER-0004 / CONCEPT-0001 / ORDER-0001 | 命中 |
| 钱数显示到角还是分 | KB-ORDER-0004 | SYSTEM-0009 / ORDER-0001 / SYSTEM-0012 | 漏召回，建议优先处理 |

条目号在实际结果列省略 KB- 前缀。诊断测试的 BUILD SUCCESS 仅表示执行成功，不表示所有探针命中。

## 判因与建议（尚未实施）

当前词项由汉字整串与 2/3-gram 组成，按标签、标题、正文命中数排序；任一维度命中即进入候选，没有语义同义映射或条件满足判定。“钱数/角/分”无法关联“金额/小数位”，而“显示/还是”等片段可召回不相关条目。

先处理明确无歧义的金额表达，比较小规模同义词归一化、标签补充和噪声匹配处理；增加反例防止扩大误召回。保留当前探针作为开发证据，另准备未参与调优的验收题。暂不以此断言需要向量库。

## 验证缺口

- 重复确认现有集成用例是顺序调用；尚缺真实数据库并发确认和成功审计唯一性验证。
- 指纹闭环单元测试覆盖机构实际字段变化；现有指纹集成测试修改存储指纹，不等价于五种对象逐类修改真实业务字段后确认。
- 工具超时测试证明 FAILED 记录与可读错误回灌，不证明真实模型最终回复不会声称成功。
- 确认入口取 CurrentUser 权限；JWT 过滤器已有用户级撤销检查，不能直接判定“旧 token 必然能越权”。尚需撤权后携旧 token 的 HTTP 回归。
- 正确引用条目不代表每句回答均由该条目支持，仍需逐题核对内容。

## 后续接手

先恢复项目约定的本机 MySQL/Redis 测试环境，再复跑上述六类集成测试并补并发确认、真实目标变化用例。当前用户要求找到真实问题后先讨论，故保留失败证据，未修复检索问题。

项目上下文通过 knowledge-continuity 降级读取，已核对 Vault identity；未走 MCP 安全校验，未写外部 Vault。本报告作为本轮交接记录。
