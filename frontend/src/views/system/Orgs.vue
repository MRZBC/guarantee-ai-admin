<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { changeOrgStatus, deleteOrg, listOrgTree } from '@/api/system'
import RegionSelect from '@/components/RegionSelect.vue'
import { useUserStore } from '@/stores/user'
import { confirmText } from '@/utils/confirmText'
import { formatDateTime } from '@/utils/format'
import { regionMatches } from '@/utils/region'
import { isEnabled, statusLabel, statusParam, STATUS_OPTIONS } from '@/utils/status'
import type { OrgItem, OrgTreeNode, OrgTreeQuery } from '@/types/system'

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
const expandedKeys = ref<number[]>([])
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

/** 展开/收起：默认展开总部与省级，便于一眼看到三级结构。 */
function defaultExpanded(nodes: OrgTreeNode[], acc: number[] = []): number[] {
  for (const node of nodes) {
    if ((node.orgLevel ?? 9) <= 2) {
      acc.push(node.id)
    }
    defaultExpanded(node.children, acc)
  }
  return acc
}

async function loadData(): Promise<void> {
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
    if (expandedKeys.value.length === 0) {
      expandedKeys.value = defaultExpanded(treeData.value)
    }
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
  expandedKeys.value = []
  void loadData()
}

function expandAll(): void {
  const acc: number[] = []
  const walk = (nodes: OrgTreeNode[]): void => {
    for (const node of nodes) {
      acc.push(node.id)
      walk(node.children)
    }
  }
  walk(treeData.value)
  expandedKeys.value = acc
}

function collapseAll(): void {
  expandedKeys.value = []
}

/**
 * 展开/收起：把 Element Plus 给出的载荷归一化到 expandedKeys。
 *
 * <p>Element Plus 的 {@code expand-change} 载荷形状不统一：行内展开/收起时第二个参数是
 * 布尔值，全部展开/收起时是"已展开行数组"（树形表格）。两种都要支持，
 * 否则会出现"点一次展开后整棵树渲染异常"或"展开状态与图标不同步"。
 * 因此这里不假设第二种参数的类型，先判数组、再按布尔值兜底。</p>
 */
function handleExpandChange(row: OrgTreeNode, expanded: unknown): void {
  if (Array.isArray(expanded)) {
    expandedKeys.value = (expanded as OrgTreeNode[]).map((item) => item.id)
    return
  }
  const ids = new Set(expandedKeys.value)
  if (expanded === true) {
    ids.add(row.id)
  } else if (expanded === false) {
    ids.delete(row.id)
  } else if (ids.has(row.id)) {
    ids.delete(row.id)
  } else {
    ids.add(row.id)
  }
  expandedKeys.value = [...ids]
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
        v-loading="loading"
        :data="treeData"
        border
        row-key="id"
        default-expand-all
        :tree-props="{ children: 'children' }"
        :expand-row-keys="expandedKeys"
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
            <el-button v-if="canUpdate" link type="primary" disabled>修改</el-button>
            <el-button v-if="canCreate" link type="primary" disabled>新增下级</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无机构数据" :image-size="80" />
        </template>
      </el-table>
    </el-card>
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

.filter-hint {
  margin-bottom: 10px;
}



</style>
