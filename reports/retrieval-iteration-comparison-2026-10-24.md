# 检索探索集逐题对照（2026-10-24）

口径：正确条目进入前三；NONE 表示该权限下应无可回答条目。探索集已参与调优，不是独立验收准确率。无模型调用，调用成本为零；本次没有采集逐题耗时，不填估算值。

| 问题 | 期望 | 改进前结果 | 改进后结果 | 前/后符合 |
|---|---|---|---|---|
| 部门停用有什么条件 | expected=KB-SYSTEM-0005 | actual=[KB-SYSTEM-0004, KB-SYSTEM-0005, KB-SYSTEM-0006] | actual=[KB-SYSTEM-0004, KB-SYSTEM-0005, KB-SYSTEM-0006] | hit=true / hit=true |
| 科室下面还有在职人员，能暂停使用吗 | expected=KB-SYSTEM-0005 | actual=[KB-SYSTEM-0007, KB-SYSTEM-0012, KB-CONCEPT-0002] | actual=[KB-SYSTEM-0007, KB-SYSTEM-0012, KB-CONCEPT-0002] | hit=false / hit=false |
| 停用与删除的区别 | expected=KB-SYSTEM-0011 | actual=[KB-SYSTEM-0009, KB-SYSTEM-0011, KB-SYSTEM-0005] | actual=[KB-SYSTEM-0009, KB-SYSTEM-0011, KB-SYSTEM-0005] | hit=true / hit=true |
| 暂时不用和彻底移除有什么不同 | expected=KB-SYSTEM-0011 | actual=[KB-ORDER-0005, KB-CONCEPT-0001, KB-ORDER-0001] | actual=[KB-ORDER-0005, KB-CONCEPT-0001, KB-ORDER-0001] | hit=false / hit=false |
| 保额区间不填是什么意思 | expected=KB-ORDER-0001 | actual=[KB-ORDER-0001, KB-CONCEPT-0001, KB-ORDER-0005] | actual=[KB-ORDER-0001, KB-CONCEPT-0001, KB-ORDER-0005] | hit=true / hit=true |
| 承保额度上下界留空会限制接单吗 | expected=KB-ORDER-0001 | actual=[KB-ORDER-0001, KB-CONCEPT-0001, KB-ORDER-0005] | actual=[KB-ORDER-0001, KB-CONCEPT-0001, KB-ORDER-0005] | hit=true / hit=true |
| 金额保留几位小数 | expected=KB-ORDER-0004 | actual=[KB-ORDER-0004, KB-CONCEPT-0001, KB-ORDER-0001] | actual=[KB-ORDER-0004, KB-CONCEPT-0001, KB-ORDER-0001] | hit=true / hit=true |
| 钱数显示到角还是分 | expected=KB-ORDER-0004 | actual=[KB-SYSTEM-0009, KB-ORDER-0001, KB-SYSTEM-0012] | actual=[KB-ORDER-0004, KB-ORDER-0001, KB-CONCEPT-0001] | hit=false / hit=true |
| 逻辑删除后可以恢复吗 | expected=KB-SYSTEM-0009 | actual=[KB-SYSTEM-0009, KB-SYSTEM-0011, KB-SYSTEM-0012] | actual=[KB-SYSTEM-0009, KB-SYSTEM-0011, KB-SYSTEM-0012] | hit=true / hit=true |
| 删掉之后去哪找回 | expected=KB-SYSTEM-0009 | actual=[KB-ORDER-0003, KB-SYSTEM-0009, KB-SYSTEM-0011] | actual=[KB-ORDER-0003, KB-SYSTEM-0009, KB-SYSTEM-0011] | hit=true / hit=true |
| 删除有哪些前置检查 | expected=KB-SYSTEM-0012 | actual=[KB-SYSTEM-0009, KB-SYSTEM-0012, KB-SYSTEM-0005] | actual=[KB-SYSTEM-0009, KB-SYSTEM-0012, KB-SYSTEM-0005] | hit=true / hit=true |
| 部门还有子部门能停用吗 | expected=KB-SYSTEM-0005 | actual=[KB-SYSTEM-0004, KB-SYSTEM-0005, KB-SYSTEM-0006] | actual=[KB-SYSTEM-0004, KB-SYSTEM-0005, KB-SYSTEM-0006] | hit=true / hit=true |
| 部门没有子部门但还有启用用户，能停用吗 | expected=KB-SYSTEM-0005 | actual=[KB-SYSTEM-0004, KB-SYSTEM-0005, KB-SYSTEM-0006] | actual=[KB-SYSTEM-0004, KB-SYSTEM-0005, KB-SYSTEM-0006] | hit=true / hit=true |
| 部门没有启用用户但还有下级，能停用吗 | expected=KB-SYSTEM-0005 | actual=[KB-SYSTEM-0004, KB-SYSTEM-0005, KB-SYSTEM-0006] | actual=[KB-SYSTEM-0004, KB-SYSTEM-0005, KB-SYSTEM-0006] | hit=true / hit=true |
| 保函金额与保费有什么区别 | expected=KB-CONCEPT-0001 | actual=[KB-CONCEPT-0001, KB-ORDER-0004, KB-CONCEPT-0002] | actual=[KB-CONCEPT-0001, KB-ORDER-0004, KB-CONCEPT-0002] | hit=true / hit=true |
| 担保额度就是我要交的钱吗 | expected=KB-CONCEPT-0001 | actual=[KB-ORDER-0001, KB-CONCEPT-0001, KB-CONCEPT-0002] | actual=[KB-ORDER-0001, KB-CONCEPT-0001, KB-CONCEPT-0002] | hit=true / hit=true |
| 地区编码如何前缀匹配 | expected=KB-ORDER-0002 | actual=[KB-ORDER-0002, KB-SYSTEM-0001, KB-SYSTEM-0007] | actual=[KB-ORDER-0002, KB-SYSTEM-0001, KB-SYSTEM-0007] | hit=true / hit=true |
| 查广东是不是包含下面的市 | expected=KB-ORDER-0002 | actual=[KB-SYSTEM-0002, KB-ORDER-0002, KB-ORDER-0005] | actual=[KB-SYSTEM-0002, KB-ORDER-0002, KB-ORDER-0005] | hit=true / hit=true |
| 机构层级有哪些 | expected=KB-SYSTEM-0001 | actual=[KB-SYSTEM-0001, KB-SYSTEM-0002, KB-SYSTEM-0004] | actual=[KB-SYSTEM-0001, KB-SYSTEM-0002, KB-SYSTEM-0004] | hit=true / hit=true |
| 机构是不是外部出函机构 | expected=KB-SYSTEM-0002 | actual=[KB-SYSTEM-0001, KB-SYSTEM-0002, KB-SYSTEM-0003] | actual=[KB-SYSTEM-0001, KB-SYSTEM-0002, KB-SYSTEM-0003] | hit=true / hit=true |
| 数据范围是什么 | expected=KB-SYSTEM-0003 | actual=[KB-SYSTEM-0003, KB-SYSTEM-0008, KB-SYSTEM-0007] | actual=[KB-SYSTEM-0003, KB-SYSTEM-0008, KB-SYSTEM-0007] | hit=true / hit=true |
| 为什么我只能看到自己部门的数据 | expected=KB-SYSTEM-0003 | actual=[KB-SYSTEM-0004, KB-SYSTEM-0005, KB-SYSTEM-0003] | actual=[KB-SYSTEM-0004, KB-SYSTEM-0005, KB-SYSTEM-0003] | hit=true / hit=true |
| 上个月怎么计算 | expected=KB-ORDER-0005 | actual=[KB-ORDER-0005, KB-CONCEPT-0001, KB-CONCEPT-0002] | actual=[KB-ORDER-0005, KB-CONCEPT-0001, KB-CONCEPT-0002] | hit=true / hit=true |
| 电子保函是什么 | expected=KB-CONCEPT-0002 | actual=[KB-CONCEPT-0002, KB-ORDER-0003, KB-CONCEPT-0001] | actual=[KB-CONCEPT-0002, KB-ORDER-0003, KB-CONCEPT-0001] | hit=true / hit=true |
| 保证金退还流程是怎样的 | expected=NONE | actual=[] | actual=[] | hit=true / hit=true |
| 保证金专户的规定是什么 | expected=NONE | actual=[KB-ORDER-0005, KB-SYSTEM-0003, KB-SYSTEM-0006] | actual=[] | hit=false / hit=true |
| 申请保函需要哪些材料 | expected=NONE | actual=[KB-CONCEPT-0002, KB-ORDER-0003, KB-CONCEPT-0001] | actual=[] | hit=false / hit=true |
| 个人申请保函有哪些办理条件 | expected=NONE | actual=[KB-CONCEPT-0002, KB-ORDER-0003, KB-SYSTEM-0005] | actual=[] | hit=false / hit=true |
| 操作审计记录的内容与渠道 | expected=NONE | actual=[KB-SYSTEM-0007, KB-ORDER-0003, KB-CONCEPT-0001] | actual=[] | hit=false / hit=true |
| 退保以后多久到账 | expected=NONE | actual=[] | actual=[] | hit=true / hit=true |
