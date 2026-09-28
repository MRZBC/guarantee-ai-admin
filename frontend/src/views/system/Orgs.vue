<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { changeOrgStatus, createOrg, deleteOrg, listOrgTree, updateOrg } from '@/api/system'
import RegionSelect from '@/components/RegionSelect.vue'
import { useUserStore } from '@/stores/user'
import { confirmText } from '@/utils/confirmText'
import { formatDateTime } from '@/utils/format'
import { regionMatches } from '@/utils/region'
import { isEnabled, statusLabel, statusParam, STATUS_OPTIONS } from '@/utils/status'
import {
  collectExpandableKeys,
  pruneKeysToTree,
  readOpenKeysFromDom,
  setRowExpansion,
  type ExpandableTable
} from '@/utils/treeExpand'
import type { OrgCreateParams, OrgItem, OrgTreeNode, OrgTreeQuery, OrgUpdateParams } from '@/types/system'

/** 机构层级：后端 orgLevel 是 TINYINT（1/2/3），不是字符串枚举。 */
const ORG_LEVEL_LABEL: Record<number, string> = {
  1: '总部',
  2: '省级',
  3: '市级'
}

const ORG_LEVEL_TAG: Record<number, 'danger' | 'warning' | 'success'> = {
  1: 'danger',
  2: 'warning',
  3: 'success'
}

/** 服务端条数上限（SYS-C-21 建议 2000）；命中上限时必须提示而不是静默截断。 */
const TREE_LIMIT = 2000

const userStore = useUserStore()

const loading = ref(false)
const rows = ref<OrgItem[]>([])
/** 展开态（唯一真相）；展开/收起都只改它，再由 syncRowExpansion 下发给表格 */
const expandedKeys = ref<Set<number>>(new Set())
const tableRef = ref<ExpandableTable | null>(null)
/** 过滤后命中节点数超过阈值时的提示（SYS-C-19）。 */
const filterHint = ref('')

const query = reactive<OrgTreeQuery>({
  orgName: '',
  regionCode: '',
  status: null
})

const canCreate = computed(() => userStore.permissions.includes('system:org:create'))
const canUpdate = computed(() => userStore.permissions.includes('system:org:update'))
const canDisable = computed(() => userStore.permissions.includes('system:org:disable'))
/** 无 system:org:delete 时不渲染删除入口（后端仍是安全边界，SYS-NF-04） */
const canDelete = computed(() => userStore.permissions.includes('system:org:delete'))

const total = computed(() => rows.value.length)

/* ---------------- 树组装 ---------------- */

/**
 * 由扁平列表按 parentId 组装三级树（SYS-C-16）。
 *
 * 关键点（SYS-C-19）：
 * ① 过滤在**客户端**做，但必须**保留命中节点的祖先链**，否则会出现"游离节点"——
 *    例如只命中市级节点时，如果不补上总部与省级，Element Plus 的树会把它当根节点挂在顶层。
 * ② 数据源必须是全量接口，分页接口会导致机构数超过一页时树静默缺节点（SYS-C-24）。
 */
const treeData = computed<OrgTreeNode[]>(() => {
  const all = rows.value
  const byId = new Map<number, OrgTreeNode>()
  for (const row of all) {
    byId.set(row.id, { ...row, children: [] })
  }

  const keyword = (query.orgName || '').trim().toLowerCase()
  const regionCode = (query.regionCode || '').trim()
  // 注意不能只判断 statusParam 的结果：未选择状态时它同样是 undefined，
  // 会被误当成"筛选了 undefined 状态"从而过滤掉全部节点。
  const statusSelected = query.status !== null && query.status !== undefined
  const status = statusParam(query.status)
  const hasFilter = keyword !== '' || regionCode !== '' || statusSelected

  // 命中集合：未过滤时全部命中
  const matched = new Set<number>()
  let matchedCount = 0
  for (const row of all) {
    if (!hasFilter) {
      matched.add(row.id)
      matchedCount++
      continue
    }
    const nameHit = keyword === '' || (row.orgName || '').toLowerCase().includes(keyword)
    // 与后端同一口径：选省 = 含其下所有市/区县（机构现在可以填到区县）
    const regionHit = regionMatches(row.regionCode, regionCode)
    const statusHit = !statusSelected || row.status === status
    if (nameHit && regionHit && statusHit) {
      matched.add(row.id)
      matchedCount++
    }
  }

  // 过滤时把命中节点的所有祖先补进可见集合，避免游离节点
  const visible = new Set<number>(matched)
  if (hasFilter) {
    for (const id of matched) {
      let current = byId.get(id)
      let guard = 0
      while (current && current.parentId && current.parentId !== 0 && guard++ < 20) {
        visible.add(current.parentId)
        current = byId.get(current.parentId)
      }
    }
  }

  const roots: OrgTreeNode[] = []
  for (const row of all) {
    const node = byId.get(row.id)
    if (!node || !visible.has(row.id)) continue
    const parent = row.parentId && row.parentId !== 0 ? byId.get(row.parentId) : undefined
    if (parent && visible.has(parent.id)) {
      parent.children.push(node)
    } else {
      roots.push(node)
    }
  }

  // 排序：层级升序 -> 排序号 -> id，与后端 selectTree 的口径一致
  const sortNodes = (nodes: OrgTreeNode[]): void => {
    nodes.sort((a, b) => {
      const la = a.orgLevel ?? 0
      const lb = b.orgLevel ?? 0
      if (la !== lb) return la - lb
      const sa = a.sortNo ?? 0
      const sb = b.sortNo ?? 0
      if (sa !== sb) return sa - sb
      return a.id - b.id
    })
    nodes.forEach((node) => sortNodes(node.children))
  }
  sortNodes(roots)

  filterHint.value = hasFilter && matchedCount > 50
    ? `当前过滤命中 ${matchedCount} 个机构（已保留其祖先链展示），如需精确定位请补充关键字`
    : ''
  return roots
})

/**
 * 树形表格的行键取用器（机构视图模型直接用实体 id 作 `row-key`）。
 *
 * 抽成常量是为了让「取键」与「取子节点」两个口径只写一次，避免展开/收起各处
 * 分别写 `node.id` / `node.children` 而出现不一致。
 */
const orgKeyOf = (node: OrgTreeNode): number => node.id
const orgChildrenOf = (node: OrgTreeNode): OrgTreeNode[] => node.children

/**
 * 展开态：把"期望值"落到表格上。
 *
 * <p><b>为什么以 DOM 的 `aria-expanded` 回读来定案</b>：el-table 的展开态有两个来源
 * （页面下发的 `toggleRowExpansion` 与用户点行内箭头），而 `expand-change` 是**逐行**
 * 事件。若拿"页面记录的集合"当已展开的依据，它随时会与表格实际状态漂移——
 * 本文件先后三次栽在这类不一致上：比对恒等、播种被事件覆盖、行对象被重建。
 * 因此这里统一为"期望 → 下发 → 回读实际"，只把表格真实渲染的结果记进 {@link expandedKeys}。</p>
 *
 * <p>回读用 el-table 自己渲染的 `aria-expanded`，配合 `row-key`，不依赖任何内部私有状态。</p>
 */
function reconcileExpansion(target: ReadonlySet<number>): void {
  const table = tableRef.value
  if (table) {
    // 两阶段：先关掉 DOM 里当前展开的（以实际为准），再打开期望的。
    // 这样"关闭"不会被 toggle 语义抵消，也不需要维护第二份状态。
    const openNow = readOpenKeysFromDom(treeData.value, orgKeyOf, orgChildrenOf)
    if (openNow.size > 0) {
      setRowExpansion(table, openNow, () => false, treeData.value, orgKeyOf, orgChildrenOf)
    }
    if (target.size > 0) {
      setRowExpansion(table, new Set<number>(), (key) => target.has(key), treeData.value, orgKeyOf, orgChildrenOf)
    }
  }

  // 以表格实际渲染结果为准记录展开态
  expandedKeys.value = readOpenKeysFromDom(treeData.value, orgKeyOf, orgChildrenOf)
}

/** "全部展开/收起"：以当前 `expandedKeys` 作为期望值下发。 */
function syncExpansionToTable(): void {
  reconcileExpansion(expandedKeys.value)
}

/** 是否已完成首次加载（用于区分"首次默认展开"与"用户手动收起后的刷新"）。 */
const loadedOnce = ref(false)

/**
 * 首次进入的默认展开深度：展开总部（层级 1）与省级（层级 2），便于一眼看到三级结构。
 * 与「全部展开」「全部收起」共用同一个 expandedKeys，不存在第二套状态。
 */
function defaultExpandedKeys(nodes: OrgTreeNode[]): Set<number> {
  const keys = new Set<number>()
  const walk = (list: OrgTreeNode[]): void => {
    for (const node of list) {
      if ((node.orgLevel ?? 9) <= 2 && node.children.length > 0) keys.add(node.id)
      walk(node.children)
    }
  }
  walk(nodes)
  return keys
}

async function loadData(): Promise<void> {
  // 刷新前先从 DOM 收下用户当前的展开态（表格重挂载后 DOM 会被重建，之后就读不到了）
  const previousOpen = readOpenKeysFromDom(treeData.value, orgKeyOf, orgChildrenOf)

  loading.value = true
  try {
    const list = await listOrgTree({
      orgName: query.orgName || undefined,
      regionCode: query.regionCode || undefined,
      status: statusParam(query.status),
    })
    rows.value = list ?? []
    if (rows.value.length >= TREE_LIMIT) {
      // 超限不能静默截断（SYS-C-19）
      ElMessage.warning(`机构数量已达到接口上限 ${TREE_LIMIT}，展示可能不完整，请联系管理员评估`)
    }

    // 期望展开态：首次加载套默认深度；之后沿用"上一轮展开态中仍然存在的键"。
    // 用 size 判断而非只认 loadedOnce：handleReset 会显式清空以求重套默认展开
    const target = loadedOnce.value && previousOpen.size > 0
      ? new Set(pruneKeysToTree(previousOpen, treeData.value, orgKeyOf, orgChildrenOf))
      : defaultExpandedKeys(treeData.value)

    loadedOnce.value = true
    // 新数据渲染完成后 DOM 里所有行都是收起的，直接按期望值打开即可
    expandedKeys.value = new Set()
    await nextTick()
    reconcileExpansion(target)
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
}

function handleSearch(): void {
  void loadData()
}

function handleReset(): void {
  query.orgName = ''
  query.regionCode = ''
  query.status = null
  // 重置筛选时清空展开态，让下方 loadData 重新套用默认展开（与"第一次打开页面"一致）
  expandedKeys.value = new Set()
  void loadData()
}

async function expandAll(): Promise<void> {
  expandedKeys.value = new Set(collectExpandableKeys(treeData.value, orgKeyOf, orgChildrenOf))
  await nextTick()
  syncExpansionToTable()
}

async function collapseAll(): Promise<void> {
  expandedKeys.value = new Set()
  await nextTick()
  syncExpansionToTable()
}

/**
 * 行内箭头展开/收起：只把展开态同步成"表格实际渲染的结果"。
 *
 * <p>不做增量记账：`expand-change` 可能因程序化下发而触发，增量维护会与真实状态漂移。
 * 以 DOM 回读为准，用户点一行与页面下发一批走的是同一条判定。</p>
 */
function handleExpandChange(): void {
  expandedKeys.value = readOpenKeysFromDom(treeData.value, orgKeyOf, orgChildrenOf)
}

/* ---------------- 写操作（按权限渲染，SYS-C-18） ---------------- */

async function toggleStatus(node: OrgTreeNode): Promise<void> {
  const next = isEnabled(node.status) ? 0 : 1
  const word = next === 0 ? '停用' : '启用'
  try {
    await ElMessageBox.confirm(
      next === 0
        ? confirmText(`确认停用机构「${node.orgName}」？`, [
            '存在启用中的下级机构或启用用户时，系统会拒绝停用',
            '停用不是删除，记录仍然保留，可随时重新启用'
          ])
        : `确认启用「${node.orgName}」？`,
      `${word}机构`,
      { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await changeOrgStatus(node.id, next)
    ElMessage.success(`已${word}「${node.orgName}」`)
    await loadData()
  } catch {
    // 错误提示已由响应拦截器统一处理
  }
}

/* ---------------- 逻辑删除 / 恢复（权限：system:org:delete） ---------------- */

async function handleDelete(node: OrgTreeNode): Promise<void> {
  try {
    await ElMessageBox.confirm(
      confirmText(`确认删除机构「${node.orgName}」？`, [
        '删除后该机构不再出现在默认列表中，且页面不提供恢复入口——如只是暂停业务，请改用「停用」',
        '删除不影响历史订单：订单里仍显示该机构名称，也仍能按它筛选',
        '停用可随时启用，删除不可'
      ]),
      '删除机构',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await deleteOrg(node.id)
    ElMessage.success(`已删除机构「${node.orgName}」`)
    await loadData()
  } catch {
    // 失败原因（如「该机构不能删除：存在 3 个启用中的下级机构」）已由响应拦截器统一提示
  }
}

/* ---------------- 新增下级 / 修改机构（权限：system:org:create / :update） ---------------- */

/**
 * 新增与修改共用同一个对话框（方案 §4.2）。
 *
 * <p>两者字段高度重合，差异只在「哪些字段可改」与提交时调哪个接口，因此用一个表单
 * 加 `isEdit` 区分，避免维护两份几乎相同的校验规则——重复的校验规则一旦漂移，
 * 就会出现"新增能过、修改过不了"这类难查的问题。</p>
 *
 * <p><b>为什么修改时必须回传 orgLevel</b>：后端 `OrgDto.UpdateRequest` 的 `orgLevel`
 * 是可选字段，`null` 会被 `updateById` 跳过——看起来"不传也能行"，但 `parentId` 一旦变更
 * 就会与旧层级组合出**不一致的层级关系**（父节点层级 + 1 ≠ 子节点层级），而这条约束在后端
 * **只对新增生效**，修改时不会兜底。因此两种模式下都提交自动推导出的层级。</p>
 *
 * <p><b>为什么 regionCode 始终回传非空值</b>：后端在 `regionCode` 为空时走 else 分支，
 * 会真正写掉 `regionName`（`regionCode` 因 null 被跳过而不变），出现"码没变名变了"。
 * 提交非空值即可确保走字典校验 + 名称同步的正常分支。</p>
 */
const dialogVisible = ref(false)
const submitting = ref(false)
const formRef = ref<FormInstance>()

/** 新增模式下为 null，修改模式下为被编辑机构 id。 */
const form = reactive<{
  id: number | null
  orgCode: string
  orgName: string
  regionCode: string
  parentId: number
  sortNo: number
}>({
  id: null,
  orgCode: '',
  orgName: '',
  regionCode: '',
  parentId: 0,
  sortNo: 0
})

const isEdit = computed(() => form.id !== null)
const dialogTitle = computed(() => (isEdit.value ? '修改机构' : '新增机构'))

const rules: FormRules = {
  // orgCode 只在新增时有值可填（修改模式该输入框只读），因此规则本身不需要分模式
  orgCode: [
    { required: true, message: '请输入机构编码', trigger: 'blur' },
    { max: 32, message: '机构编码长度不能超过 32', trigger: 'blur' }
  ],
  orgName: [
    { required: true, message: '请输入机构名称', trigger: 'blur' },
    { max: 64, message: '机构名称长度不能超过 64', trigger: 'blur' }
  ],
  regionCode: [{ required: true, message: '请选择行政区划', trigger: 'change' }]
}

/**
 * 层级由上级自动推导（父层级 + 1；上级为顶级时固定 1），不做成可手填项。
 *
 * <p>层级是树形结构与后端写校验的共同依据，允许手填等于给用户"选错"的机会
 * （方案 §4.3 明确要求不给这个机会）。</p>
 *
 * <p><b>为什么用 min(…, 3) 而不是原样返回</b>：层级只有 1/2/3 三档，后端
 * {@code @Max(3)} 会直接 400。能触发这一步的只有"历史数据本身层级错乱"这种边缘情形
 * （父层级为 3 时推导结果是 4）；此时收敛到 3 至少让这次保存成功，而不是把一个与
 * 用户输入无关的 400 抛到脸上。正常数据下 1/2/3 的推导结果不受影响。</p>
 */
const derivedLevel = computed<number>(() => {
  if (!form.parentId) return 1
  const parent = rows.value.find((row) => row.id === form.parentId)
  const parentLevel = parent?.orgLevel ?? 1
  return Math.min(parentLevel + 1, 3)
})

/** 收集某节点的全部后代 id（含自身），用于把"自己与自己的下级"挡在上级选项之外（防环）。 */
function descendantIdsOf(orgId: number): Set<number> {
  const result = new Set<number>()
  const collect = (nodes: OrgTreeNode[]): void => {
    for (const node of nodes) {
      result.add(node.id)
      collect(node.children)
    }
  }
  const find = (nodes: OrgTreeNode[]): OrgTreeNode | null => {
    for (const node of nodes) {
      if (node.id === orgId) return node
      const hit = find(node.children)
      if (hit) return hit
    }
    return null
  }
  const target = find(treeData.value)
  if (target) {
    result.add(target.id)
    collect(target.children)
  }
  return result
}

/**
 * 可选上级机构。
 *
 * <p>两道过滤：</p>
 * <ol>
 *   <li><b>防环</b>：排除自己与自己的全部下级——后端 `validateUpdate` 会拒绝，但让用户
 *       先选再报错是纯浪费，且方案 §4.3 要求这些节点在选项里就不可选。
 *       新增模式下没有"自己"，整条过滤不生效。</li>
 *   <li><b>层级别</b>：只保留"层级 = 自身层级 - 1"的机构，与后端新增校验
 *       （上级层级必须为 N-1）同一口径。放宽它会造出层级错乱的树，
 *       而修改路径后端并不兜底。</li>
 * </ol>
 *
 * <p><b>当前上级永远保留</b>：历史数据可能不满足"父层级 + 1"（后端只在新增时校验），
 * 若因层级别把它滤掉，选项里会显示不出当前值——用户一旦改动别的字段再保存，
 * 上级就会被静默改掉。</p>
 */
const parentOptions = computed<Array<{ id: number; label: string }>>(() => {
  const excluded = isEdit.value && form.id !== null
    ? descendantIdsOf(form.id)
    : new Set<number>()
  const wantLevel = derivedLevel.value - 1

  const result: Array<{ id: number; label: string }> = []
  const walk = (nodes: OrgTreeNode[], path: string[]): void => {
    for (const node of nodes) {
      const nextPath = [...path, node.orgName]
      const selectable = !excluded.has(node.id)
        && (node.orgLevel === wantLevel || node.id === form.parentId)
      if (selectable) {
        result.push({ id: node.id, label: nextPath.join(' / ') })
      }
      walk(node.children, nextPath)
    }
  }
  walk(treeData.value, [])
  return result
})

function resetForm(): void {
  form.id = null
  form.orgCode = ''
  form.orgName = ''
  form.regionCode = ''
  form.parentId = 0
  form.sortNo = 0
  formRef.value?.clearValidate()
}

/**
 * 打开新增对话框。
 *
 * @param parentId 上级机构；0 表示顶级（卡片工具栏入口），树节点入口传该节点 id
 */
function openCreate(parentId = 0): void {
  resetForm()
  form.parentId = parentId
  dialogVisible.value = true
}

function openEdit(node: OrgTreeNode): void {
  resetForm()
  form.id = node.id
  form.orgCode = node.orgCode ?? ''
  form.orgName = node.orgName ?? ''
  // 注意 ?? 而不是 ||：区划码是字符串，空串与 null 在这里都表示"未设置"，收敛成空串即可
  form.regionCode = node.regionCode ?? ''
  form.parentId = node.parentId ?? 0
  form.sortNo = node.sortNo ?? 0
  dialogVisible.value = true
}

async function handleSubmit(): Promise<void> {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    if (form.id !== null) {
      // 修改：orgCode 不可改（后端 updateById 不接受该列），因此不放进提交体
      const payload: OrgUpdateParams = {
        orgName: form.orgName.trim(),
        regionCode: form.regionCode,
        // regionName 不回传：后端在区划码非空时会用字典全称补齐，避免"码新名旧"
        parentId: form.parentId,
        orgLevel: derivedLevel.value,
        sortNo: form.sortNo
      }
      await updateOrg(form.id, payload)
      ElMessage.success(`机构「${payload.orgName}」修改成功`)
    } else {
      const payload: OrgCreateParams = {
        orgCode: form.orgCode.trim(),
        orgName: form.orgName.trim(),
        regionCode: form.regionCode,
        orgLevel: derivedLevel.value,
        parentId: form.parentId,
        sortNo: form.sortNo
      }
      await createOrg(payload)
      ElMessage.success(`机构「${payload.orgName}」新增成功`)
    }
    dialogVisible.value = false
    // loadData 只在 expandedKeys 为空时兜底默认展开，因此刷新后用户当前的展开状态得以保留
    await loadData()
  } catch {
    // 失败原因（如「机构编码已存在」「上级机构不能是自己的下级机构」）已由响应拦截器统一提示
  } finally {
    submitting.value = false
  }
}

onMounted(loadData)
</script>

<template>
  <div class="page-container">
    <el-card class="filter-card" shadow="never">
      <el-form :model="query" label-width="82px" @submit.prevent>
        <el-row :gutter="12">
          <el-col :xs="24" :sm="12" :md="8" :lg="6">
            <el-form-item label="机构名称">
              <el-input
                v-model="query.orgName"
                placeholder="请输入机构名称/编码"
                clearable
                @keyup.enter="handleSearch"
              />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8" :lg="6">
            <el-form-item label="地区">
              <!-- 地区下拉（行政区划字典）：替代原先手填"区域编码"；下方树仍是本地过滤 -->
              <RegionSelect v-model="query.regionCode" />
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
        <span class="table-toolbar__title">机构树 · 共 {{ total }} 个机构</span>
        <div class="table-toolbar__actions">
          <!-- 无 system:org:create 时不渲染入口（SYS-C-18）；后端 @PreAuthorize 仍是安全边界 -->
          <el-button v-if="canCreate" type="primary" size="small" @click="openCreate()">
            新增机构
          </el-button>
          <el-button size="small" @click="expandAll">全部展开</el-button>
          <el-button size="small" @click="collapseAll">全部收起</el-button>
        </div>
      </div>

      <el-alert
        v-if="filterHint"
        :title="filterHint"
        type="info"
        show-icon
        :closable="false"
        class="filter-hint"
      />

      <el-table
        ref="tableRef"
        v-loading="loading"
        :data="treeData"
        border
        row-key="id"
        :tree-props="{ children: 'children' }"
        height="560"
        @expand-change="handleExpandChange"
      >
        <el-table-column prop="orgName" label="机构名称" min-width="240" show-overflow-tooltip />
        <el-table-column prop="orgCode" label="机构编码" width="140" show-overflow-tooltip />
        <el-table-column prop="regionName" label="区划" width="120" show-overflow-tooltip />
        <el-table-column prop="regionCode" label="区划编码" width="110" align="center">
          <template #default="{ row }">{{ row.regionCode || '--' }}</template>
        </el-table-column>
        <el-table-column prop="orgLevel" label="层级" width="90" align="center">
          <template #default="{ row }">
            <el-tag
              v-if="row.orgLevel"
              size="small"
              :type="ORG_LEVEL_TAG[row.orgLevel] || 'info'"
              effect="plain"
            >
              {{ ORG_LEVEL_LABEL[row.orgLevel] || row.orgLevel }}
            </el-tag>
            <span v-else>--</span>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="90" align="center">
          <template #default="{ row }">
            <!-- 停用机构用灰色标签区分（SYS-C-17） -->
            <el-tag
              :type="isEnabled(row.status) ? 'success' : 'info'"
              size="small"
              :effect="isEnabled(row.status) ? 'light' : 'plain'"
            >
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="sortNo" label="排序" width="80" align="center">
          <template #default="{ row }">{{ row.sortNo ?? '--' }}</template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="170" align="center">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column
          v-if="canUpdate || canDisable || canDelete"
          label="操作"
          width="250"
          align="center"
          fixed="right"
        >
          <template #default="{ row }">
            <!-- 无权限不渲染按钮（前端过滤仅为体验优化，后端仍是安全边界，SYS-NF-04）。
                 已删除机构不进列表，因此这里不再有"已删除行只能恢复"的分支——
                 恢复入口已随「显示已删除」开关一并撤除（2026-09-22 评审决定）。 -->
            <el-button
              v-if="canDisable"
              link
              type="primary"
              @click="toggleStatus(row)"
            >
              {{ isEnabled(row.status) ? '停用' : '启用' }}
            </el-button>
            <el-button v-if="canDelete" link type="danger" @click="handleDelete(row)">
              删除
            </el-button>
            <el-button v-if="canUpdate" link type="primary" @click="openEdit(row)">
              修改
            </el-button>
            <!-- 新增下级：parentId 预填该节点 id，层级由父层级 + 1 自动推导 -->
            <el-button v-if="canCreate" link type="primary" @click="openCreate(row.id)">
              新增下级
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无机构数据" :image-size="80" />
        </template>
      </el-table>
    </el-card>

    <!-- 新增机构 / 新增下级 / 修改机构（权限：system:org:create / :update）。
         无权限时入口不渲染，后端 @PreAuthorize 仍是安全边界（SYS-NF-04） -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogTitle"
      width="560px"
      @closed="resetForm"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="机构编码" prop="orgCode">
          <el-input
            v-model="form.orgCode"
            placeholder="如 ORG330100"
            :disabled="isEdit"
            clearable
          />
          <div class="form-hint text-muted">
            {{
              isEdit
                ? '机构编码创建后不可修改（订单与筛选都按它引用机构）'
                : '编码需全局唯一，同一编码只能存在一个机构'
            }}
          </div>
        </el-form-item>
        <el-form-item label="机构名称" prop="orgName">
          <el-input v-model="form.orgName" placeholder="如 杭州市分公司" clearable />
        </el-form-item>
        <el-form-item label="行政区划" prop="regionCode">
          <RegionSelect v-model="form.regionCode" placeholder="请选择省 / 市 / 区县" />
          <div class="form-hint text-muted">
            机构可挂到省、市或区县；保存时名称按字典同步，避免"码新名旧"
          </div>
        </el-form-item>
        <el-form-item label="上级机构" prop="parentId">
          <el-select
            v-model="form.parentId"
            placeholder="顶级（无上级）"
            filterable
            clearable
            style="width: 100%"
            @clear="form.parentId = 0"
          >
            <el-option label="顶级（无上级，仅总部）" :value="0" />
            <el-option
              v-for="opt in parentOptions"
              :key="opt.id"
              :label="opt.label"
              :value="opt.id"
            />
          </el-select>
          <div class="form-hint text-muted">
            {{
              isEdit
                ? '已排除该机构自身与其全部下级；按层级约束，这里只列出可直接作为上级的机构'
                : '按层级约束，这里只列出可直接作为上级的机构（新增时无需排除任何节点）'
            }}
          </div>
        </el-form-item>
        <el-form-item label="机构层级">
          <el-tag :type="ORG_LEVEL_TAG[derivedLevel] || 'info'" effect="plain" size="small">
            {{ ORG_LEVEL_LABEL[derivedLevel] || derivedLevel }}
          </el-tag>
          <div class="form-hint text-muted">
            由上级自动推导（父层级 + 1），不单独填写，避免层级与上级不匹配
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
.table-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.table-toolbar__title {
  font-weight: 600;
  color: #303133;
}

.table-toolbar__actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.filter-hint {
  margin-bottom: 10px;
}

.form-hint {
  font-size: 12px;
  line-height: 1.6;
}



</style>
