<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  changeDepartmentStatus,
  createDepartment,
  deleteDepartment,
  listDepartmentTree,
  listOrgOptions,
  updateDepartment
} from '@/api/system'
import { useUserStore } from '@/stores/user'
import { formatDateTime } from '@/utils/format'
import { isEnabled, statusLabel, statusParam, STATUS_OPTIONS } from '@/utils/status'
import type {
  DepartmentItem,
  DepartmentTreeNode,
  DepartmentTreeQuery,
  OrgOption
} from '@/types/system'

/**
 * 部门树节点（页面视图模型）。
 *
 * 直接复用接口契约 `DepartmentTreeNode`，仅在"节点键"上收窄：
 * 接口的 `id` 是**实体 id**，而页面还需要一个跨实体唯一的 `key` 供 `el-table` 的
 * `row-key` 使用——机构 id 与部门 id 来自两张表、必然撞号（都有 id=1），
 * 共用 `id` 会让两个节点被当成同一个，展开态错乱。
 *
 * 另有三个**视图派生字段**不属于接口：`label`（展示名）、`key`、`children` 已在契约里。
 */
interface DepartmentNode extends DepartmentTreeNode {
  key: string
  label: string
  /**
   * 以下三个 id 在页面上收窄为**非空**：接口契约里它们是可空 `Long`（保守类型），
   * 但部门必定属于某机构（`org_id NOT NULL`）、`parent_id` 后端返回 0 而非 null，
   * 页面组装时已统一归一化（`?? 0`），因此这里收窄可避免满篇 `!` 断言。
   */
  id: number
  orgId: number
  parentId: number
  /**
   * 收窄子节点类型：接口契约里是 `DepartmentTreeNode[]`，页面里必须是已经带上
   * `key` / `label` 的 `DepartmentNode[]`，否则模板与递归函数都要反复断言。
   */
  children: DepartmentNode[]
}

/* ---------------- 树组装（Q1 方案 A：机构为顶级概览节点） ---------------- */

const userStore = useUserStore()

const loading = ref(false)
/** 全量部门（扁平，来自 /tree 接口）——**不能**用分页接口，否则树会静默缺节点 */
const flatRows = ref<DepartmentItem[]>([])
const orgOptions = ref<OrgOption[]>([])
const expandedKeys = ref<string[]>([])
const filterHint = ref('')

const query = reactive<DepartmentTreeQuery>({
  orgId: null,
  deptName: '',
  status: null
})

/** 无系统管理写/删权限时不渲染对应入口（后端仍是安全边界，SYS-NF-04） */
const canCreate = computed(() => userStore.permissions.includes('system:dept:create'))
const canUpdate = computed(() => userStore.permissions.includes('system:dept:update'))
const canDisable = computed(() => userStore.permissions.includes('system:dept:disable'))
const canDelete = computed(() => userStore.permissions.includes('system:dept:delete'))

/* ---------------- 树组装（只由部门自身的 parent_id 构成） ---------------- */

/**
 * 由扁平部门列表组装**部门树**。
 *
 * <p><b>纪律：机构（出函机构）不是部门树的一个层级。</b>两者是不同的实体：
 * 机构是业务主体（`sys_org`，自带 `org_level` 三级层级），部门是公司内部组织（`sys_department`，
 * 靠 `parent_id` 成树）；`org_id` 只是部门的一个**归属属性**。
 * 把机构塞进部门树的层级里会同时造成两个错误：
 * ① 机构在树上的位置是虚的（它不是任何部门的父节点，纯属人造成因）；
 * ② 部门真实的父子层级被压成平级（本项目数据是"每机构 1 个顶级 + 3 个挂其下"）。
 * 因此机构只以两种方式出现在本页：**筛选条件**（`orgId`）与**「所属机构」列 / 根节点上的机构标签**。</p>
 *
 * <p>过滤时按名称/状态计算命中集合，再把命中节点的祖先链补进来，避免游离节点。</p>
 */
const treeData = computed<DepartmentNode[]>(() => {
  const byId = new Map<number, DepartmentNode>()
  for (const row of flatRows.value) {
    byId.set(row.id, {
      key: `dept-${row.id}`,
      nodeKind: 'DEPT',
      id: row.id,
      orgId: row.orgId ?? 0,
      label: row.deptName,
      deptName: row.deptName,
      deptCode: row.deptCode,
      orgName: row.orgName,
      // 归一化：后端 0 表示顶级；null 只可能是异常数据，一并按顶级处理
      parentId: row.parentId ?? 0,
      status: row.status,
      sortNo: row.sortNo,
      createdAt: row.createdAt,
      // 只保留 isDeleted：页面不展示已删除数据，但「上级部门备选」要据此过滤
      isDeleted: row.isDeleted,
      children: []
    })
  }

  const keyword = (query.deptName || '').trim().toLowerCase()
  // 不能只判断 statusParam 的结果：未选择状态时它同样是 undefined，
  // 会被误当成"筛选了 undefined 状态"从而过滤掉全部节点。
  const statusSelected = query.status !== null && query.status !== undefined
  const status = statusParam(query.status)
  const hasFilter = keyword !== '' || statusSelected

  const matched = new Set<number>()
  for (const row of flatRows.value) {
    if (!hasFilter) {
      matched.add(row.id)
      continue
    }
    const nameHit =
      keyword === '' ||
      (row.deptName || '').toLowerCase().includes(keyword) ||
      (row.deptCode || '').toLowerCase().includes(keyword)
    const statusHit = !statusSelected || row.status === status
    if (nameHit && statusHit) {
      matched.add(row.id)
    }
  }

  // 命中节点的祖先必须保留，否则子部门会被当成根节点挂到顶层（游离节点）
  const visible = new Set<number>(matched)
  if (hasFilter) {
    for (const id of matched) {
      let current = byId.get(id)
      let guard = 0
      while (current && current.parentId && guard++ < 20) {
        visible.add(current.parentId)
        current = byId.get(current.parentId)
      }
    }
  }

  const sortNodes = (nodes: DepartmentNode[]): void => {
    nodes.sort((a, b) => {
      const sa = a.sortNo ?? 0
      const sb = b.sortNo ?? 0
      if (sa !== sb) return sa - sb
      return a.id - b.id
    })
    nodes.forEach((node) => sortNodes(node.children))
  }

  // 归属机构顺序：沿用机构下拉的顺序，便于「全部机构」视图下按机构成块阅读
  const orderOfOrg = new Map<number, number>()
  orgOptions.value.forEach((org, index) => orderOfOrg.set(org.id, index))

  const roots: DepartmentNode[] = []
  for (const row of flatRows.value) {
    const node = byId.get(row.id)
    if (!node || !visible.has(row.id)) continue
    // 只有"真正有父部门、且父也在可见集合里"的节点才挂到父下；
    // 其余（parent_id = 0，或父被过滤掉）都是根——不引入任何伪父节点
    const parent = row.parentId && row.parentId !== 0 ? byId.get(row.parentId) : undefined
    if (parent && visible.has(parent.id)) {
      parent.children.push(node)
    } else {
      roots.push(node)
    }
  }
  sortNodes(roots)

  // 根节点排序：先按归属机构（成块），再按 sortNo / id
  roots.sort((a, b) => {
    const oa = orderOfOrg.get(a.orgId) ?? 9999
    const ob = orderOfOrg.get(b.orgId) ?? 9999
    if (oa !== ob) return oa - ob
    const sa = a.sortNo ?? 0
    const sb = b.sortNo ?? 0
    if (sa !== sb) return sa - sb
    return a.id - b.id
  })

  // 层级路径：组树后推导，避免依赖"父行一定先于子行返回"
  const fillPath = (nodes: DepartmentNode[], prefix: string): void => {
    for (const node of nodes) {
      const path = prefix ? `${prefix} / ${node.label}` : node.label
      node.path = path
      fillPath(node.children, path)
    }
  }
  fillPath(roots, '')

  if (!hasFilter) {
    filterHint.value = ''
  } else {
    filterHint.value =
      matched.size > 50
        ? `当前过滤命中 ${matched.size} 个部门（已保留其上级链路展示），如需精确定位请补充关键字`
        : ''
  }
  return roots
})

const totalDepartments = computed(() => flatRows.value.length)
/** 当前视图涉及的机构数（机构是归属属性，这里的"M 个机构"只是概览，不代表树层级） */
const totalOrgGroups = computed(() => new Set(flatRows.value.map((row) => row.orgId)).size)

/** 默认展开所有有子节点的部门（本项目数据只有两级，等价于全展开） */
function initExpanded(): void {
  const keys: string[] = []
  const walk = (nodes: DepartmentNode[]): void => {
    for (const node of nodes) {
      if (node.children.length > 0) keys.push(node.key)
      walk(node.children)
    }
  }
  walk(treeData.value)
  expandedKeys.value = keys
}

function handleExpandChange(row: DepartmentNode, expanded: DepartmentNode[] | boolean): void {
  // Element Plus 对树形表格的 expand-change 会传 (row, expandedRows)，这里以传入行为准做增量维护
  const isExpanded = Array.isArray(expanded) ? expanded.some((r) => r.key === row.key) : expanded
  if (isExpanded) {
    if (!expandedKeys.value.includes(row.key)) expandedKeys.value = [...expandedKeys.value, row.key]
  } else {
    expandedKeys.value = expandedKeys.value.filter((key) => key !== row.key)
  }
}

/* ---------------- 数据加载 ---------------- */

async function loadOrgOptions(): Promise<void> {
  try {
    orgOptions.value = (await listOrgOptions()) ?? []
  } catch {
    orgOptions.value = []
  }
}

async function loadData(): Promise<void> {
  loading.value = true
  try {
    const list = await listDepartmentTree({
      orgId: query.orgId ?? undefined,
      deptName: query.deptName || undefined,
      status: statusParam(query.status)
    })
    flatRows.value = list ?? []
    initExpanded()
  } catch {
    flatRows.value = []
  } finally {
    loading.value = false
  }
}

function handleSearch(): void {
  void loadData()
}

function handleReset(): void {
  query.orgId = null
  query.deptName = ''
  query.status = null
  expandedKeys.value = []
  void loadData()
}

/**
 * 点击根节点上的机构标签 = 只看该机构（等价于选中筛选下拉）；再次点击取消。
 *
 * 机构是筛选维度而不是树层级，所以入口放在"标签/列"上，不放在行本身——
 * 行是部门，点行不应该有"筛选"这种副作用。
 */
function handleOrgClick(orgId: number): void {
  query.orgId = query.orgId === orgId ? null : orgId
  expandedKeys.value = []
  void loadData()
}

/* ---------------- 新增 / 修改 / 启停 ---------------- */

const dialogVisible = ref(false)
const submitting = ref(false)
const formRef = ref<FormInstance>()

const form = reactive<{
  id: number | null
  deptCode: string
  deptName: string
  orgId: number | null
  parentId: number
  sortNo: number
}>({
  id: null,
  deptCode: '',
  deptName: '',
  orgId: null,
  parentId: 0,
  sortNo: 0
})

const isEdit = computed(() => form.id !== null)
const dialogTitle = computed(() => (isEdit.value ? '修改部门' : '新增部门'))

const rules = computed<FormRules>(() => ({
  deptCode: [
    { required: true, message: '请输入部门编码', trigger: 'blur' },
    {
      pattern: /^[A-Za-z0-9_-]{2,32}$/,
      message: '部门编码由 2-32 位字母、数字、下划线或中划线组成',
      trigger: 'blur'
    }
  ],
  deptName: [{ required: true, message: '请输入部门名称', trigger: 'blur' }],
  orgId: [{ required: true, message: '请选择所属机构', trigger: 'change' }]
}))

/**
 * 可选上级部门：只列**同机构未删除**部门。
 *
 * <p>两条约束都来自后端，前端先收窄避免用户提交后才被拒：
 * ① 上级部门必须与本部门属于同一机构（`DepartmentService.validateCreate/Update`）；
 * ② 不能选自己或自己的后代（否则在部门树上形成环）。</p>
 */
const parentOptions = computed<DepartmentNode[]>(() => {
  if (form.orgId === null) return []
  const result: DepartmentNode[] = []
  const walk = (nodes: DepartmentNode[]): void => {
    for (const node of nodes) {
      if (node.orgId !== form.orgId) continue
      // 已删除部门不作为可选的上级（页面不展示已删除数据，但接口可能仍返回，保险起见过滤）
      if (node.isDeleted === 1) continue
      result.push(node)
      walk(node.children)
    }
  }
  walk(treeData.value)
  return result
})

function descendantKeysOf(deptId: number): Set<number> {
  const result = new Set<number>()
  const find = (nodes: DepartmentNode[]): DepartmentNode | null => {
    for (const node of nodes) {
      if (node.id === deptId) return node
      const found = find(node.children)
      if (found) return found
    }
    return null
  }
  const collect = (nodes: DepartmentNode[]): void => {
    for (const node of nodes) {
      result.add(node.id)
      collect(node.children)
    }
  }
  const target = find(treeData.value)
  if (target) collect(target.children)
  return result
}

/** 上级部门可选项：排除自己与自己的后代（防止把部门挂到自己的子部门下形成环） */
const availableParentOptions = computed<DepartmentNode[]>(() => {
  if (!isEdit.value || form.id === null) return parentOptions.value
  const excluded = descendantKeysOf(form.id)
  excluded.add(form.id)
  return parentOptions.value.filter((node) => !excluded.has(node.id))
})

function resetForm(): void {
  form.id = null
  form.deptCode = ''
  form.deptName = ''
  form.orgId = query.orgId ?? null
  form.parentId = 0
  form.sortNo = 0
  formRef.value?.clearValidate()
}

/**
 * 新增部门。
 *
 * @param orgId    已确定的所属机构（从机构行或部门行的"新增下级"进来时不为空）
 * @param parentId 上级部门；0 表示顶级
 */
function openCreate(orgId?: number, parentId?: number): void {
  resetForm()
  const targetOrg = orgId ?? query.orgId
  if (targetOrg === null || targetOrg === undefined) {
    // 「全部机构」视图下无法推断归属，直接要求先选机构，避免提交后才被后端拒绝
    ElMessage.warning('请先在「所属机构」中选定一个机构，再新增部门')
    dialogVisible.value = true
    return
  }
  form.orgId = targetOrg
  form.parentId = parentId ?? 0
  dialogVisible.value = true
}

function openEdit(node: DepartmentNode): void {
  resetForm()
  form.id = node.id
  form.deptCode = node.deptCode ?? ''
  form.deptName = node.label
  form.orgId = node.orgId
  form.parentId = node.parentId ?? 0
  form.sortNo = node.sortNo ?? 0
  dialogVisible.value = true
}

async function handleSubmit(): Promise<void> {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  if (form.orgId === null) {
    ElMessage.error('请选择所属机构')
    return
  }

  submitting.value = true
  try {
    if (isEdit.value && form.id !== null) {
      // deptCode 与 orgId 不可改（SYS-W-03）：换机构请停用后新建，因此不提交这两个字段
      await updateDepartment(form.id, {
        deptName: form.deptName.trim(),
        parentId: form.parentId,
        sortNo: form.sortNo
      })
      ElMessage.success(`部门「${form.deptName}」修改成功`)
    } else {
      await createDepartment({
        deptCode: form.deptCode.trim(),
        deptName: form.deptName.trim(),
        orgId: form.orgId,
        parentId: form.parentId,
        sortNo: form.sortNo
      })
      ElMessage.success(`部门「${form.deptName}」新增成功`)
    }
    dialogVisible.value = false
    await loadData()
  } catch {
    // 失败原因（如「部门编码已存在」）已由响应拦截器统一提示
  } finally {
    submitting.value = false
  }
}

/** 启停：停用会被"部门下仍有启用用户"拒绝，成功文案里说明影响面 */
async function handleToggleStatus(node: DepartmentNode): Promise<void> {
  const target = isEnabled(node.status) ? 0 : 1
  const action = target === 1 ? '启用' : '停用'
  try {
    await ElMessageBox.confirm(
      target === 1
        ? `确认启用部门「${node.label}」？`
        : `确认停用部门「${node.label}」？① 该部门下不能有启用中的用户，否则会被拒绝；② 停用不是删除，记录仍然可见。`,
      `${action}部门`,
      { type: 'warning', confirmButtonText: `确认${action}`, cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await changeDepartmentStatus(node.id, target)
    ElMessage.success(`已${action}部门「${node.label}」`)
    await loadData()
  } catch {
    // 失败原因由响应拦截器提示
  }
}

/* ---------------- 逻辑删除（权限：system:dept:delete） ---------------- */

/**
 * 删除部门。
 *
 * <p>页面已撤除恢复入口（2026-09-22 评审决定），因此确认文案里**不能**再写
 * "可以在「显示已删除」里恢复"——那会给出页面做不到的承诺。</p>
 */
async function handleDelete(node: DepartmentNode): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认删除部门「${node.label}」？`
        + '① 删除后该部门不再出现在部门树中；'
        + '② 该部门下有子部门或有用户时，删除会被拒绝并给出数量；'
        + '③ 如只是暂停业务，请改用「停用」——停用可随时启用，删除不可。',
      '删除部门',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await deleteDepartment(node.id)
    ElMessage.success(`已删除部门「${node.label}」`)
    await loadData()
  } catch {
    // 失败原因（如「该部门不能删除：存在 3 个未删除的用户」）已由响应拦截器统一提示
  }
}

onMounted(async () => {
  // 先取机构下拉：树的机构分组顺序依赖它，先加载可避免首次渲染时机构顺序抖动
  await loadOrgOptions()
  await loadData()
})
</script>

<template>
  <div class="page-container">
    <el-card class="filter-card" shadow="never">
      <el-form :model="query" label-width="82px" @submit.prevent>
        <el-row :gutter="12">
          <el-col :xs="24" :sm="12" :md="8" :lg="6">
            <el-form-item label="所属机构">
              <el-select v-model="query.orgId" placeholder="全部机构" clearable filterable>
                <el-option
                  v-for="org in orgOptions"
                  :key="org.id"
                  :label="org.orgName"
                  :value="org.id"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8" :lg="6">
            <el-form-item label="部门名称">
              <el-input
                v-model="query.deptName"
                placeholder="部门名称或编码"
                clearable
                @keyup.enter="handleSearch"
              />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8" :lg="6">
            <el-form-item label="状态">
              <el-select v-model="query.status" placeholder="全部状态" clearable>
                <el-option
                  v-for="opt in STATUS_OPTIONS"
                  :key="opt.value"
                  :label="opt.label"
                  :value="opt.value"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8" :lg="6" class="filter-actions">
            <el-form-item label-width="0">
              <el-button type="primary" icon="Search" @click="handleSearch">查询</el-button>
              <el-button icon="Refresh" @click="handleReset">重置</el-button>
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
    </el-card>

    <el-card class="table-card" shadow="never">
      <div class="table-toolbar">
        <span class="table-toolbar__title">
          部门树 · 共 {{ totalDepartments }} 个部门 / 归属 {{ totalOrgGroups }} 个机构
        </span>
        <el-button v-if="canCreate" type="primary" icon="Plus" @click="openCreate()">
          新增部门
        </el-button>
      </div>

      <div v-if="filterHint" class="filter-hint text-muted">{{ filterHint }}</div>

      <el-table
        v-loading="loading"
        :data="treeData"
        border
        row-key="key"
        :tree-props="{ children: 'children' }"
        :expand-row-keys="expandedKeys"
        height="520"
        @expand-change="handleExpandChange"
      >
        <el-table-column label="部门名称" min-width="260" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ row.label }}</span>
            <!--
              机构是**归属属性**，不是树的层级：只在"顶级部门"（parent_id=0）这一行上挂一个机构标签，
              说明"这棵部门树属于哪个出函机构"，点击即只看该机构。
              子部门不再重复——它们的归属从父节点一眼可见，每行都挂纯属噪音。
            -->
            <el-tag
              v-if="row.parentId === 0 && row.orgName"
              type="info"
              size="small"
              effect="plain"
              class="org-tag"
              @click="handleOrgClick(row.orgId)"
            >
              {{ row.orgName }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="部门编码" width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.deptCode || '--' }}</template>
        </el-table-column>
        <el-table-column label="所属机构" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.orgName || '--' }}</template>
        </el-table-column>
        <el-table-column label="层级路径" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ row.path || '--' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag
              :type="isEnabled(row.status) ? 'success' : 'info'"
              size="small"
              :effect="isEnabled(row.status) ? 'light' : 'plain'"
            >
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="排序" width="80" align="center">
          <template #default="{ row }">{{ row.sortNo ?? '--' }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="170" align="center">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column
          v-if="canCreate || canUpdate || canDisable || canDelete"
          label="操作"
          width="250"
          align="center"
          fixed="right"
        >
          <template #default="{ row }">
            <!-- 每一行都是部门；机构没有"行"，因此这里不存在机构分支 -->
            <el-button v-if="canCreate" link type="primary" @click="openCreate(row.orgId, row.id)">
              新增下级
            </el-button>
            <el-button v-if="canUpdate" link type="primary" @click="openEdit(row)">修改</el-button>
            <el-button v-if="canDisable" link type="primary" @click="handleToggleStatus(row)">
              {{ isEnabled(row.status) ? '停用' : '启用' }}
            </el-button>
            <el-button v-if="canDelete" link type="danger" @click="handleDelete(row)">
              删除
            </el-button>
            <span v-if="!canCreate && !canUpdate && !canDisable && !canDelete">--</span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无部门数据" :image-size="80" />
        </template>
      </el-table>

      <!-- 树形不分页（SYS-C-24）：提示文案替代分页器，避免误以为还有下一页 -->
      <div class="pagination-wrapper text-muted">
        树形视图一次性展示范围内的全部部门，不使用分页；如需缩小范围请使用上方筛选条件。
      </div>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="560px" @closed="resetForm">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="部门编码" prop="deptCode">
          <el-input
            v-model="form.deptCode"
            placeholder="如 DEPT0081"
            :disabled="isEdit"
            clearable
          />
          <div v-if="isEdit" class="form-hint text-muted">部门编码创建后不可修改</div>
        </el-form-item>
        <el-form-item label="部门名称" prop="deptName">
          <el-input v-model="form.deptName" placeholder="如 法务合规部" clearable />
        </el-form-item>
        <el-form-item label="所属机构" prop="orgId">
          <el-select
            v-model="form.orgId"
            placeholder="请选择所属机构"
            filterable
            :disabled="isEdit"
            style="width: 100%"
            @change="form.parentId = 0"
          >
            <el-option
              v-for="org in orgOptions"
              :key="org.id"
              :label="org.orgName"
              :value="org.id"
            />
          </el-select>
          <div v-if="isEdit" class="form-hint text-muted">
            所属机构创建后不可修改（换机构请停用后新建，SYS-W-03）
          </div>
        </el-form-item>
        <el-form-item label="上级部门" prop="parentId">
          <el-select
            v-model="form.parentId"
            placeholder="顶级部门"
            filterable
            clearable
            style="width: 100%"
            @clear="form.parentId = 0"
          >
            <el-option label="顶级部门（无上级）" :value="0" />
            <el-option
              v-for="node in availableParentOptions"
              :key="node.id"
              :label="node.path || node.label"
              :value="node.id"
            />
          </el-select>
          <div class="form-hint text-muted">
            只能选择同一机构内的部门；后端会拒绝跨机构与"挂到自己的下级之下"。
          </div>
        </el-form-item>
        <el-form-item label="排序号" prop="sortNo">
          <el-input-number v-model="form.sortNo" :min="0" :step="1" controls-position="right" />
          <div class="form-hint text-muted">同级内按排序号升序展示，相同则按 id 升序。</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.table-toolbar__title {
  font-weight: 600;
  color: #303133;
}

.filter-hint {
  margin-bottom: 10px;
}

.org-tag {
  margin-right: 6px;
}

.form-hint {
  font-size: 12px;
  line-height: 1.6;
}

.pagination-wrapper {
  margin-top: 12px;
  font-size: 12px;
  text-align: center;
}
</style>
