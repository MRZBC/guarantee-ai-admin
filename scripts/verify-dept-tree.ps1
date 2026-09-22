# =====================================================================
#  ⛔ 历史留存 · 勿再执行（DO NOT RUN AGAIN）—— 断言已失效
#
#  本走查脚本是"部门树改造 / 部门归属机构"时期的产物，大量断言以
#  `$_.orgId` 的**机构归属**为前提（AC-2 / AC-5d / AC-5e / AC-6 / AC-8 等），
#  并会 POST/PUT 带 orgId 的部门创建与修改请求。
#
#  阶段一（docs/PLAN-移除用户与部门的机构归属.md）已删除
#  sys_user.org_id / sys_department.org_id（V4 迁移），部门不再携带机构信息：
#    - 树接口不再返回 orgId → 那些断言会因 $null 而假通过或直接失败；
#    - 带 orgId 的写请求与新入参口径不符。
#  因此本脚本的机构归属断言**整体失效**，保留仅为历史记录。
#
#  **不要重跑，也不要按它改逻辑**（改逻辑会掩盖历史）。
#  阶段一之后的结构校验改用：pwsh -File scripts/verify-no-org-on-user-dept.ps1
# =====================================================================
# 部门树改造 — 真实 HTTP 走查（AC-1 ~ AC-10）
# 用法：pwsh -File scripts/verify-dept-tree.ps1
$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:8081'
$pass = 0; $fail = 0

function Check([string]$name, [bool]$ok, [string]$detail = '') {
  if ($ok) { $script:pass++; Write-Host "  PASS  $name" -ForegroundColor Green }
  else { $script:fail++; Write-Host "  FAIL  $name  $detail" -ForegroundColor Red }
}

function Login([string]$u, [string]$p) {
  $r = Invoke-RestMethod -Method Post -Uri "$base/api/auth/login" -ContentType 'application/json' `
    -Body (@{ username = $u; password = $p } | ConvertTo-Json) -TimeoutSec 30
  return $r.data.token
}

$admin = Login 'admin' 'Admin@123'
$analyst = Login 'analyst' 'Analyst@123'
$ha = @{ Authorization = "Bearer $admin" }
$hn = @{ Authorization = "Bearer $analyst" }

# 部门树已收敛为**只保留 ORGHQ 一棵**（评审要求）：11 个部门，全部归属总部机构。
# 机构表仍有 21 个机构，只是其余机构不再有部门。
$DEPT_TOTAL = 11
$DEPT_ORGS = 1
$HQ_ORG_ID = 1

Write-Host "`n=== AC-1/AC-2：全量 $DEPT_TOTAL 条（单棵 ORGHQ 部门树） ==="
$all = (Invoke-RestMethod -Uri "$base/api/system/departments/tree" -Headers $ha -TimeoutSec 30).data
Check "AC-1 接口返回 $DEPT_TOTAL 条" ($all.Count -eq $DEPT_TOTAL) "实际=$($all.Count)"
Check "AC-2 顶级部门 $DEPT_ORGS 个（只有总部的树）" (($all | Where-Object { $_.parentId -eq 0 }).Count -eq $DEPT_ORGS) `
  "实际=$(($all | Where-Object { $_.parentId -eq 0 }).Count)"
$orgGroups = @($all | Group-Object orgId)
Check "AC-2 部门只归属 $DEPT_ORGS 个机构（单棵树）" ($orgGroups.Count -eq $DEPT_ORGS) `
  "实际=$($orgGroups.Count) 个机构：$($orgGroups.Name -join ',')"
$badOrg = $orgGroups | Where-Object { ($_.Group | Where-Object { $_.parentId -eq 0 }).Count -ne 1 }
Check 'AC-2 每机构恰 1 个顶级部门' ($badOrg.Count -eq 0) "异常机构=$($badOrg.Name -join ',')"
$ids = $all | ForEach-Object { $_.id }
$orphan = $all | Where-Object { $_.parentId -ne 0 -and ($ids -notcontains $_.parentId) }
Check 'AC-2 无游离节点（父都在返回集合内）' ($orphan.Count -eq 0) "游离=$($orphan.deptName -join ',')"
# 层级深度：本规格是三级（总部 → 一级部门 → 二级部门），且不能再有第四级
# 注意：这条断言必须跟着部门树规格走。早先的规格只有两级，脚本里写的是"无第三层"，
# 换规格后它仍然"通过"（因为当时的判据恰好不匹配任何行），属于失效断言——已按现规格重写。
$depthOf = {
  param($node)
  $d = 1; $cur = $node; $guard = 0
  while ($cur.parentId -ne 0 -and $guard++ -lt 20) {
    $cur = $all | Where-Object { $_.id -eq $cur.parentId } | Select-Object -First 1
    if ($null -eq $cur) { break }
    $d++
  }
  return $d
}
$depths = $all | ForEach-Object { & $depthOf $_ }
$maxDepth = ($depths | Measure-Object -Maximum).Maximum
$lvl4 = @($depths | Where-Object { $_ -gt 3 }).Count
Check 'AC-2 最大层级为 3（总部 → 一级部门 → 二级部门）' ($maxDepth -eq 3) "最大层级=$maxDepth"
Check 'AC-2 无第四层' ($lvl4 -eq 0) "超过三级的部门=$lvl4"

Write-Host "`n=== AC-3：不受分页影响（pageSize=1 仍是全量） ==="
$p1 = (Invoke-RestMethod -Uri "$base/api/system/departments/tree?pageSize=1&pageNum=1" -Headers $ha -TimeoutSec 30).data
Check "AC-3 pageSize=1 时仍返回 $DEPT_TOTAL 条" ($p1.Count -eq $DEPT_TOTAL) "实际=$($p1.Count)"
$paged = (Invoke-RestMethod -Uri "$base/api/system/departments?pageNum=1&pageSize=1" -Headers $ha -TimeoutSec 30).data
Check 'AC-3 对照：分页接口 pageSize=1 只返回 1 条（证明两者确实不同源）' ($paged.list.Count -eq 1) `
  "实际=$($paged.list.Count)"

Write-Host "`n=== AC-4：过滤命中 + 祖先链完整 ==="
# 中文查询参数必须自己 URL 编码：Invoke-RestMethod 不会替你编码
$deptKeyword = [System.Uri]::EscapeDataString('业务部')
$filtered = (Invoke-RestMethod -Uri "$base/api/system/departments/tree?deptName=$deptKeyword" -Headers $ha -TimeoutSec 30).data
Check "AC-4 「业务部」命中 $DEPT_ORGS 条（单棵树里只有一个）" ($filtered.Count -eq $DEPT_ORGS) "实际=$($filtered.Count)"
# 树形没有机构伪节点后，"命中节点的父不在结果集里"是正常的（父被同一个过滤条件排除了）。
# 真正要守的不变量是：parentId 必须在**全量数据**里可解析，不能是悬挂引用。
$allIds = $all | ForEach-Object { $_.id }
$unresolvable = $filtered | Where-Object { $_.parentId -ne 0 -and ($allIds -notcontains $_.parentId) }
Check 'AC-4 命中节点的父 id 都能在全量数据里解析（无悬挂父引用）' (@($unresolvable).Count -eq 0) `
  "悬挂=$(@($unresolvable).Count)"

Write-Host "`n=== AC-5：启停前置检查 + 状态过滤 ==="
# 上级部门 id 必须从**当前数据**里取：部门 id 会随部门树规格变化（本次由 1~80 变为 1001+），
# 写死旧 id 会得到"目标不存在或不在你的数据范围内"（404）。
$ac5ParentId = @($all | Where-Object { $_.orgId -eq $HQ_ORG_ID -and $_.parentId -eq 0 })[0].id
# 5a 负向：挑一个"确有启用用户"的部门，停用必须被拒（这条本身就是业务规则）
$usersPage = (Invoke-RestMethod -Uri "$base/api/system/users?pageNum=1&pageSize=200" -Headers $ha -TimeoutSec 30).data
$busyDeptIds = @($usersPage.list | Where-Object { $_.deptId -ne $null } | ForEach-Object { $_.deptId } | Select-Object -Unique)
$cand = @($all | Where-Object { $_.status -eq 1 -and ($busyDeptIds -contains $_.id) })[0]
Check 'AC-5a 前置：找到有启用用户的部门' ($null -ne $cand) "候选为空（演示数据异常）"
$deniedDisable = Invoke-RestMethod -Method Patch -Uri "$base/api/system/departments/$($cand.id)/status" -Headers $ha `
  -ContentType 'application/json' -Body '{"status":0}' -TimeoutSec 30
Check 'AC-5a 部门下有启用用户时停用被拒（含数量说明）' `
  ($deniedDisable.code -ne 0 -and $deniedDisable.message -like '*启用中的用户*') `
  "code=$($deniedDisable.code) msg=$($deniedDisable.message)"

# 5b 正向：造一个没有用户、没有下级的临时部门来验证 status=0 过滤
$ac5Code = "DEPT7$((Get-Random -Minimum 100 -Maximum 999))"
$ac5 = (Invoke-RestMethod -Method Post -Uri "$base/api/system/departments" -Headers $ha `
  -ContentType 'application/json' -Body (@{
    deptCode = $ac5Code; deptName = '走查临时部门-状态过滤'; orgId = $HQ_ORG_ID; parentId = $ac5ParentId; sortNo = 97
  } | ConvertTo-Json) -TimeoutSec 30).data
$stopResp = Invoke-RestMethod -Method Patch -Uri "$base/api/system/departments/$($ac5.id)/status" -Headers $ha `
  -ContentType 'application/json' -Body '{"status":0}' -TimeoutSec 30
Check 'AC-5b 无启用用户时可停用' ($stopResp.code -eq 0 -and $stopResp.data.status -eq 0) `
  "code=$($stopResp.code) msg=$($stopResp.message)"

$disabled = @((Invoke-RestMethod -Uri "$base/api/system/departments/tree?status=0" -Headers $ha -TimeoutSec 30).data)
Check 'AC-5c status=0 命中 1 条（就是刚停用的那个）' `
  ($disabled.Count -eq 1 -and $disabled[0].id -eq $ac5.id) "实际=$($disabled.Count)"
Check 'AC-5d 停用部门带出所属机构（机构标签必须能显示）' ($disabled[0].orgId -eq $HQ_ORG_ID) "orgId=$($disabled[0].orgId)"
# 机构行保留的判据：该 orgId 在未过滤视图里也有部门
Check 'AC-5e 该机构在完整视图里存在（机构行不会因筛状态而消失）' `
  (@($all | Where-Object { $_.orgId -eq $HQ_ORG_ID }).Count -gt 0)

$restoreStatus = Invoke-RestMethod -Method Patch -Uri "$base/api/system/departments/$($ac5.id)/status" -Headers $ha `
  -ContentType 'application/json' -Body '{"status":1}' -TimeoutSec 30
Check 'AC-5f 启用成功（可逆）' ($restoreStatus.code -eq 0 -and $restoreStatus.data.status -eq 1) `
  "code=$($restoreStatus.code)"

# 5g 立刻清掉夹具：否则后续用绝对条数断言的用例会被带偏 1（走查脚本自己踩过一次）
Invoke-RestMethod -Method Delete -Uri "$base/api/system/departments/$($ac5.id)" -Headers $ha -TimeoutSec 30 | Out-Null
$afterAc5 = @((Invoke-RestMethod -Uri "$base/api/system/departments/tree" -Headers $ha -TimeoutSec 30).data)
Check "AC-5g 夹具清除后回到 $DEPT_TOTAL 条" ($afterAc5.Count -eq $DEPT_TOTAL) "实际=$($afterAc5.Count)"

Write-Host "`n=== AC-6：orgId 过滤 ==="
$one = @((Invoke-RestMethod -Uri "$base/api/system/departments/tree?orgId=$HQ_ORG_ID" -Headers $ha -TimeoutSec 30).data)
Check "AC-6 orgId=1（总部）返回全部 $DEPT_TOTAL 条" ($one.Count -eq $DEPT_TOTAL) "实际=$($one.Count)"
Check 'AC-6 全部属于总部机构' (@($one | Where-Object { $_.orgId -ne $HQ_ORG_ID }).Count -eq 0)

Write-Host "`n=== AC-7：includeDeleted 的参数级鉴权 ==="
# 注意：本应用的 HTTP 状态码恒为 200，业务码在 body 的 code 字段里，
# 因此不能按 HTTP 状态码判断，必须读 code。
$denied = Invoke-RestMethod -Uri "$base/api/system/departments/tree?includeDeleted=true" -Headers $hn -TimeoutSec 30
Check 'AC-7 ANALYST 传 includeDeleted=true 被拒（code=403）' ($denied.code -eq 403) "code=$($denied.code) msg=$($denied.message)"
Check 'AC-7 拒绝文案指明缺失权限' ($denied.message -like '*system:dept:delete*') "msg=$($denied.message)"
$allowed = Invoke-RestMethod -Uri "$base/api/system/departments/tree" -Headers $hn -TimeoutSec 30
Check 'AC-7b ANALYST 正常查树可以（code=0）' ($allowed.code -eq 0) "code=$($allowed.code)"
Check 'AC-7c 对照：分页接口同样被拒（两接口口径一致）' `
  ((Invoke-RestMethod -Uri "$base/api/system/departments?includeDeleted=true" -Headers $hn -TimeoutSec 30).code -eq 403)

Write-Host "`n=== AC-8：数据范围（analyst=市级机构） ==="
$scoped = @((Invoke-RestMethod -Uri "$base/api/system/departments/tree" -Headers $hn -TimeoutSec 30).data)
Check "AC-8 演示账号（已全部归属总部）看到的是全量 $DEPT_TOTAL 条" ($scoped.Count -eq $DEPT_TOTAL) "实际=$($scoped.Count)"
Check 'AC-8 全部属于同一机构' (@($scoped | Group-Object orgId).Count -eq 1) `
  "机构数=$(@($scoped | Group-Object orgId).Count)"

Write-Host "`n=== AC-9：写操作闭环（新增 → 修改 → 启停 → 删除 → 恢复） ==="
# 以"当前实际条数"为基线，避免夹具残留时绝对数字漂移（走查脚本自己踩过一次）
$base0 = @((Invoke-RestMethod -Uri "$base/api/system/departments/tree" -Headers $ha -TimeoutSec 30).data).Count
Check "AC-9 前置：默认视图基线为 $DEPT_TOTAL 条" ($base0 -eq $DEPT_TOTAL) "基线=$base0"
# 已存在的软删行数（上一次走查若中断可能残留）：includeDeleted 断言必须按它做偏移，否则脚本不可重复执行
$preDeleted = @((Invoke-RestMethod -Uri "$base/api/system/departments/tree?includeDeleted=true" -Headers $ha -TimeoutSec 30).data |
  Where-Object { $_.isDeleted -eq 1 }).Count
Write-Host "  （当前库内已软删部门 $preDeleted 条，将作为 includeDeleted 断言的偏移）"

$newCode = "DEPT9$((Get-Random -Minimum 100 -Maximum 999))"
# orgId 必须是 $HQ_ORG_ID：部门树只剩 ORGHQ 一棵，若建到别的机构，
# 后面把 parentId 指向总部的部门时会被后端以"上级必须与本部门属于同一机构"拒绝（脚本自己踩过）
$created = (Invoke-RestMethod -Method Post -Uri "$base/api/system/departments" -Headers $ha `
  -ContentType 'application/json' -Body (@{
    deptCode = $newCode; deptName = '走查临时部门-法务合规部'; orgId = $HQ_ORG_ID; parentId = 0; sortNo = 99
  } | ConvertTo-Json) -TimeoutSec 30).data
Check 'AC-9 新增成功且返回 id' ($created.id -gt 0) "id=$($created.id)"
Check 'AC-9 新增后 parentId=0（顶级）' ($created.parentId -eq 0)

$afterCreate = @((Invoke-RestMethod -Uri "$base/api/system/departments/tree" -Headers $ha -TimeoutSec 30).data)
Check 'AC-9 新增后树 +1 条' ($afterCreate.Count -eq $base0 + 1) "实际=$($afterCreate.Count) 基线=$base0"

# 改上级时必须挑一个**已存在的顶级部门**（parentId=0）。两个踩过的坑：
# ① 复用 AC-5 的夹具部门 id —— 它在 AC-5g 已被删掉，会指向不存在的部门；
# ② 挑"某个非顶级部门" —— 可能恰好是本用例刚新建那个顶级部门的下级，
#    于是新部门被挂到自己的下级之下，后端以"形成环"拒绝（这是正确行为）。
$newParent = @($all | Where-Object { $_.parentId -eq 0 })[0]
Check 'AC-9 前置：找到用于改挂的上级部门' ($null -ne $newParent) "候选为空（部门树异常）"
$updated = (Invoke-RestMethod -Method Put -Uri "$base/api/system/departments/$($created.id)" -Headers $ha `
  -ContentType 'application/json' -Body (@{
    deptName = '走查临时部门-法务合规部（改）'; parentId = $newParent.id; sortNo = 98
  } | ConvertTo-Json) -TimeoutSec 30).data
Check 'AC-9 修改成功（改名 + 改上级）' ($updated.deptName -like '*（改）' -and $updated.parentId -eq $newParent.id) `
  "name=$($updated.deptName) parent=$($updated.parentId)"

# 停用前置检查：该部门无启用用户，应能停用
$st = (Invoke-RestMethod -Method Patch -Uri "$base/api/system/departments/$($created.id)/status" -Headers $ha `
  -ContentType 'application/json' -Body '{"status":0}' -TimeoutSec 30).data
Check 'AC-9 停用成功' ($st.status -eq 0) "status=$($st.status)"

# 删除前置：有下级时拒绝——给它挂一个子部门再删
# orgId 必须与父部门一致（后端强制"上级与本部门同机构"），否则子部门建不出来，这条断言会假通过
$child = (Invoke-RestMethod -Method Post -Uri "$base/api/system/departments" -Headers $ha `
  -ContentType 'application/json' -Body (@{
    deptCode = "$($newCode)C"; deptName = '走查临时子部门'; orgId = $HQ_ORG_ID; parentId = $created.id; sortNo = 1
  } | ConvertTo-Json) -TimeoutSec 30).data
Check 'AC-9 前置：子部门挂载成功' ($child.id -gt 0 -and $child.parentId -eq $created.id) `
  "id=$($child.id) parent=$($child.parentId)"
$blocked = Invoke-RestMethod -Method Delete -Uri "$base/api/system/departments/$($created.id)" -Headers $ha -TimeoutSec 30
Check 'AC-9 有下级部门时删除被拒（引用说明）' ($blocked.code -ne 0 -and $blocked.message -like '*下级部门*') `
  "code=$($blocked.code) msg=$($blocked.message)"

# 先删子、再删父
Invoke-RestMethod -Method Delete -Uri "$base/api/system/departments/$($child.id)" -Headers $ha -TimeoutSec 30 | Out-Null
$del = (Invoke-RestMethod -Method Delete -Uri "$base/api/system/departments/$($created.id)" -Headers $ha -TimeoutSec 30).data
Check 'AC-9 无阻碍时删除成功' ($del.isDeleted -eq 1) "isDeleted=$($del.isDeleted)"

$default = @((Invoke-RestMethod -Uri "$base/api/system/departments/tree" -Headers $ha -TimeoutSec 30).data)
Check 'AC-9 删除后默认视图回到基线条数' ($default.Count -eq $base0) "实际=$($default.Count) 基线=$base0"
$inc = @((Invoke-RestMethod -Uri "$base/api/system/departments/tree?includeDeleted=true" -Headers $ha -TimeoutSec 30).data)
Check "AC-9 「显示已删除」视图 = 基线 + 2 + 既有软删 $preDeleted" `
  ($inc.Count -eq $base0 + 2 + $preDeleted) "实际=$($inc.Count) 期望=$($base0 + 2 + $preDeleted)"
$deletedNodes = @($inc | Where-Object { $_.isDeleted -eq 1 -and $_.id -eq $created.id })
Check 'AC-9 已删除部门仍在原层级（parentId 未变）' ($deletedNodes.Count -eq 1 -and $deletedNodes[0].parentId -eq $newParent.id)

$restored = (Invoke-RestMethod -Method Post -Uri "$base/api/system/departments/$($created.id)/restore" -Headers $ha -TimeoutSec 30).data
Check 'AC-9 恢复成功' ($restored.isDeleted -eq 0) "isDeleted=$($restored.isDeleted)"

Write-Host "`n=== 清理：物理删除走查夹具（逻辑删除行会让 DataScopeIntegrationTest 数错，必须物理清） ==="
$mysql = 'D:\environment\mysql-8.0.29-winx64\bin\mysql.exe'
$sql = "DELETE FROM sys_department WHERE id IN ($($created.id), $($child.id), $($ac5.id)); SELECT CONCAT('剩余部门=', COUNT(*)) FROM sys_department WHERE is_deleted=0;"
$f = Join-Path $env:TEMP 'cleanup-dept-fixture.sql'
[System.IO.File]::WriteAllText($f, $sql, (New-Object System.Text.UTF8Encoding($false)))
& $mysql '--host=127.0.0.1' '--port=3307' '--user=guarantee' '--password=guarantee@2026' `
  '--default-character-set=utf8mb4' '--skip-column-names' guarantee_ai_admin --execute="source $f" 2>$null
$after = (Invoke-RestMethod -Uri "$base/api/system/departments/tree" -Headers $ha -TimeoutSec 30).data
Check "清理后树回到 $DEPT_TOTAL 条" ($after.Count -eq $DEPT_TOTAL) "实际=$($after.Count)"

Write-Host "`n=== 结果：PASS=$pass  FAIL=$fail ===" -ForegroundColor $(if ($fail -eq 0) { 'Green' } else { 'Red' })
if ($fail -gt 0) { exit 1 }
