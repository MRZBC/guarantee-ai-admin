// 部门树结构校验：用真实接口数据复核"树里只有部门、机构不参与层级"，并校验部门层级形状。
//
// 为什么需要它：部门树是前端按 parent_id 组装的，后端集成测试看不到组装结果；
// 而"把机构塞进部门层级"这个错误正是发生在组装这一步。
// 前端没有测试框架（package.json 只有 dev/build/preview），所以用一个独立 Node 脚本验证结构不变量。
//
// 用法：node scripts/verify-dept-tree-shape.mjs [baseUrl]

const base = process.argv[2] ?? 'http://127.0.0.1:8081'

// 期望的部门树形状（与 DataInitializer.DEPT_SPEC 一致）。
// 现状：部门树**只保留 ORGHQ 一棵**（评审要求），其余机构没有部门。
const ORG_WITH_DEPTS = 'ORGHQ'
const EXPECTED_SHAPE = {
  总部: ['业务部', '财务部', '人事部', '行政部', '技术部'],
  业务部: ['杭州部', '台州部', '温州部'],
  技术部: ['大数据部', '系统部'],
  财务部: [],
  人事部: [],
  行政部: [],
  杭州部: [],
  台州部: [],
  温州部: [],
  大数据部: [],
  系统部: []
}
const DEPTS_IN_TREE = Object.keys(EXPECTED_SHAPE).length

async function login(username, password) {
  const res = await fetch(`${base}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password })
  })
  const body = await res.json()
  if (body.code !== 0) throw new Error(`登录失败: ${body.message}`)
  return body.data.token
}

/** 与 Departments.vue 的 treeData 同构：只按 parent_id 组树，不引入任何其他实体 */
function buildTree(rows) {
  const byId = new Map(rows.map((row) => [row.id, { ...row, children: [] }]))
  const roots = []
  for (const row of rows) {
    const node = byId.get(row.id)
    const parent = row.parentId && row.parentId !== 0 ? byId.get(row.parentId) : undefined
    if (parent) parent.children.push(node)
    else roots.push(node)
  }
  return roots
}

const checks = []
function check(name, ok, detail = '') {
  checks.push({ name, ok, detail })
}

const token = await login('admin', 'Admin@123')
const headers = { Authorization: `Bearer ${token}` }

const res = await fetch(`${base}/api/system/departments/tree`, { headers })
const body = await res.json()
if (body.code !== 0) throw new Error(`树接口失败: ${body.message}`)
const rows = body.data

console.log(`接口返回 ${rows.length} 个部门（期望 ${DEPTS_IN_TREE}）\n`)

// 1) 全是部门行，没有任何"机构行"混入
const orgLike = rows.filter((r) => !r.deptCode || !r.deptName)
check('接口数据里每一行都是部门（有 deptCode 与 deptName）', orgLike.length === 0, `异常 ${orgLike.length} 行`)

// 2) 树由 parent_id 构成，不引入伪父节点
const tree = buildTree(rows)
const flatten = (nodes, acc = []) => {
  for (const n of nodes) {
    acc.push(n)
    flatten(n.children, acc)
  }
  return acc
}
const all = flatten(tree)
check('组树后节点总数与接口一致（无节点丢失/重复）', all.length === rows.length, `${all.length} vs ${rows.length}`)
check(`部门总数 = ${DEPTS_IN_TREE}（只保留一棵树）`, rows.length === DEPTS_IN_TREE, `实际 ${rows.length}`)

// 3) 关键：树里只有部门——每个根节点都必须是**真实部门**（有 deptCode），不能是机构伪节点
const orgLikeRoots = tree.filter((r) => !r.deptCode || !r.deptName)
check('根节点全部是真实部门（无机构伪节点）', orgLikeRoots.length === 0, `${orgLikeRoots.length} 个非部门根节点`)

// 3b) 根节点数必须等于"parent_id=0 的部门数"，且只有 1 个（单棵树）
const topLevel = rows.filter((r) => !r.parentId || r.parentId === 0)
check(
  `根节点数(${tree.length}) = 顶级部门数(${topLevel.length}) = 1（单棵树）`,
  tree.length === topLevel.length && topLevel.length === 1,
  `根=${tree.length} 顶级=${topLevel.length}`
)
check(`唯一的根节点是「总部」`, topLevel[0]?.deptName === '总部', `实际 ${topLevel[0]?.deptName}`)

// 4) 树只属于一个机构，且是 ORGHQ
const deptOrgs = [...new Set(rows.map((r) => r.orgId))]
check(`部门只归属一个机构（${ORG_WITH_DEPTS}）`, deptOrgs.length === 1, `实际 ${deptOrgs.length} 个机构`)

// 5) 父子必属同一机构
const crossOrg = all.filter((n) => {
  if (!n.parentId || n.parentId === 0) return false
  const parent = all.find((p) => p.id === n.parentId)
  return parent && parent.orgId !== n.orgId
})
check('父子部门必属同一机构（无跨机构父子链）', crossOrg.length === 0, `跨机构 ${crossOrg.length} 条`)

// 6) 层级形状与 EXPECTED_SHAPE 完全一致
const byName = new Map(rows.map((r) => [r.deptName, r]))
const shapeProblems = []
for (const [parentName, childNames] of Object.entries(EXPECTED_SHAPE)) {
  const parent = byName.get(parentName)
  if (!parent) {
    shapeProblems.push(`缺部门 ${parentName}`)
    continue
  }
  const actualChildren = rows
    .filter((r) => r.parentId === parent.id)
    .map((r) => r.deptName)
    .sort()
  const expectedChildren = [...childNames].sort()
  if (actualChildren.join(',') !== expectedChildren.join(',')) {
    shapeProblems.push(`${parentName} 下级=[${actualChildren.join(',')}] 期望=[${expectedChildren.join(',')}]`)
  }
}
check('部门树形状与规格一致（总部→一级→二级）', shapeProblems.length === 0, shapeProblems.slice(0, 3).join(' | '))

// 7) 树的根节点归属 ORGHQ
const orgRes = await fetch(`${base}/api/system/orgs?pageNum=1&pageSize=100`, { headers })
const orgBody = await orgRes.json()
if (orgBody.code !== 0) throw new Error(`机构列表失败: ${orgBody.message}`)
const orgs = orgBody.data.list ?? []
const hq = orgs.find((o) => o.orgCode === ORG_WITH_DEPTS)
check(`${ORG_WITH_DEPTS} 在机构表里存在`, Boolean(hq), '未找到总部机构')
check(
  `部门树归属 ${ORG_WITH_DEPTS}（orgId=${hq?.id}）`,
  rows.every((r) => r.orgId === hq?.id),
  `机构 id 集合=${deptOrgs.join(',')}`
)

// 8) 人员归属：所有用户都归属 ORGHQ，且都挂在真实部门上
const userRes = await fetch(`${base}/api/system/users?pageNum=1&pageSize=200`, { headers })
const userBody = await userRes.json()
if (userBody.code !== 0) throw new Error(`用户列表失败: ${userBody.message}`)
const users = userBody.data.list ?? []
console.log(`用户样本 ${users.length} 个（分页上限）\n`)

const usersWithoutDept = users.filter((u) => u.deptId === null)
check(
  '所有用户都挂了部门',
  usersWithoutDept.length === 0,
  `${usersWithoutDept.length} 个无部门：${usersWithoutDept.slice(0, 3).map((u) => u.username).join(',')}`
)
const usersOutsideHq = users.filter((u) => u.orgId !== hq?.id)
check(
  `所有用户都归属 ${ORG_WITH_DEPTS}`,
  usersOutsideHq.length === 0,
  `${usersOutsideHq.length} 个不在总部：${usersOutsideHq.slice(0, 3).map((u) => u.username).join(',')}`
)
const deptIds = new Set(rows.map((r) => r.id))
const danglingDept = users.filter((u) => u.deptId !== null && !deptIds.has(u.deptId))
check('用户部门都指向存在的部门（无悬挂引用）', danglingDept.length === 0, `${danglingDept.length} 个悬挂`)

// 9) 每个部门都有人（数据铺满整棵树，便于演示）
const deptUserCount = new Map()
for (const u of users) {
  if (u.deptId !== null) deptUserCount.set(u.deptId, (deptUserCount.get(u.deptId) ?? 0) + 1)
}
// 用户接口分页上限 200，样本不足 300 时这条只能对"出现过的部门"成立，故按样本判断而非全量
const sampleDeptsWithPeople = [...deptUserCount.keys()].length
check(
  `样本覆盖多个部门（${sampleDeptsWithPeople}/${DEPTS_IN_TREE} 个部门在样本里有用户）`,
  sampleDeptsWithPeople >= Math.min(DEPTS_IN_TREE, Math.floor(users.length / 10)),
  `仅 ${sampleDeptsWithPeople} 个部门有用户`
)

console.log('部门树（全部）：')
for (const root of tree) {
  console.log(`  [${root.orgName}] ${root.deptName}`)
  for (const child of root.children) {
    console.log(`      ├ ${child.deptName}`)
    for (const grand of child.children) console.log(`      │   └ ${grand.deptName}`)
  }
}

console.log('')
let failed = 0
for (const c of checks) {
  console.log(`  ${c.ok ? 'PASS' : 'FAIL'}  ${c.name}${c.ok ? '' : '  ' + c.detail}`)
  if (!c.ok) failed++
}
console.log(`\n结果：PASS=${checks.length - failed}  FAIL=${failed}`)
// 不用 process.exit()：Windows 上 fetch 的句柄未关闭时强退会触发 libuv 断言崩溃
process.exitCode = failed === 0 ? 0 : 1
