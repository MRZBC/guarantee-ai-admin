<script setup lang="ts">
/**
 * 权限勾选树（角色授权用）。
 *
 * <p><b>为什么按前缀分组，而不是用后端 {@code parentId}</b>：
 * {@code sys_permission.parent_id} 由初始化器**恒定写入 0**
 * （{@code PermissionSyncInitializer} 的 INSERT 里 parent_id 是字面量 0），
 * 因此它不具备树形语义。这里按 {@code permCode} 的模块前缀合成两层树
 * （**仅含单个权限的分组会被提升为顶层叶子**，避免同名两级，见 {@code treeData}）。</p>
 *
 * <p><b>这顺带消除了一个数据风险</b>：分组节点是**合成节点**（key 前缀 {@code group::}），
 * 不是真实权限码，因此提交前按 {@code permCode} 白名单过滤即可——
 * 不存在"半选父节点是一个真实菜单权限码、漏提交就把菜单权限静默撤销"的问题。
 * 若将来改用真实父级权限码组树，必须重新审视这一点。</p>
 *
 * <p>本组件只负责"树 + 勾选"，**不负责**变更对比、影响提示与提交：
 * 那些依赖角色原始权限集与持有用户数，属于页面（对话框）的职责。</p>
 */
import { computed } from 'vue'
import type { PermissionItem } from '@/types/system'

/** 树节点：分组节点无 permCode，叶子节点的 key 就是 permCode */
interface TreeNode {
  key: string
  label: string
  /**
   * 叶子才有；分组节点为 undefined。
   *
   * **仅供识别与提交过滤使用，不在界面上展示**：权限码对业务用户没有意义，
   * 展示它只会增加噪音（曾经的实现会在名称后附一段灰色英文，已移除）。
   */
  permCode?: string
  /** 危险权限：渲染警示图标 + tooltip（原因见 DANGER_REASONS） */
  dangerReason?: string
  children?: TreeNode[]
}

const props = withDefaults(
  defineProps<{
    /** 已勾选的权限码集合（v-model） */
    modelValue: string[]
    /** 权限主数据扁平列表（来自 GET /api/system/permissions） */
    permissions: PermissionItem[]
    disabled?: boolean
  }>(),
  { disabled: false }
)

const emit = defineEmits<{ 'update:modelValue': [string[]] }>()

/**
 * 模块分组定义。**顺序即展示顺序**，与后端 `PermissionCatalog.PERMISSIONS` 的分段一致。
 *
 * 两处 `权限与审计` 前缀会合并为同一个分组（见下方按 label 归并）。
 * 前缀之间不存在互为前缀的情况（`system:org:` 与 `system:order:` 在第 10 个字符处即分叉）。
 */
const GROUP_DEFINITIONS: { label: string; prefix: string }[] = [
  { label: '首页', prefix: 'dashboard:' },
  { label: '订单管理', prefix: 'order:' },
  { label: '数据概览', prefix: 'analysis:' },
  { label: '项目管理', prefix: 'project:' },
  { label: '企业管理', prefix: 'enterprise:' },
  { label: '险种配置', prefix: 'system:insurance:' },
  { label: '机构配置', prefix: 'system:org:' },
  { label: '部门配置', prefix: 'system:dept:' },
  { label: '用户配置', prefix: 'system:user:' },
  { label: '角色配置', prefix: 'system:role:' },
  { label: '权限与审计', prefix: 'system:permission:' },
  { label: '权限与审计', prefix: 'system:audit:' },
  { label: '在线会话', prefix: 'system:session:' },
  { label: 'AI 助手', prefix: 'ai:' }
]

/** 未命中任何前缀时的兜底分组，避免权限码"凭空消失"。 */
const FALLBACK_GROUP_LABEL = '其他'

/**
 * 危险权限及其原因（勾选时给出警示）。
 *
 * 判据：**能改变他人权限，或扩大自身权限面**。清单与需求文档 §4.3 一致——
 * 改这里必须同步改文档，否则两处口径会漂移。
 */
const DANGER_REASONS: Record<string, string> = {
  'system:role:assign-permission': '授予它等于允许该角色给自己加任何权限（自提权）',
  'system:user:assign-role': '可给任意用户分配任意角色，等价于间接提权',
  'system:audit:view': '可查看全站操作审计，含他人操作的字段级前后值',
  'system:session:kick': '可强制其他用户下线',
  'ai:system:write': '打开 AI 助手的全部写能力（提案通道），是 propose* 工具的总开关'
}

/** 分组节点 key 前缀：与真实权限码区分，便于提交前过滤。 */
const GROUP_KEY_PREFIX = 'group::'

function groupLabelOf(permCode: string): string {
  const matched = GROUP_DEFINITIONS.find((group) => permCode.startsWith(group.prefix))
  return matched ? matched.label : FALLBACK_GROUP_LABEL
}

/**
 * 按分组把扁平权限列表组织成两层树。
 *
 * <p><b>单权限分组会被提升为顶层叶子</b>：`首页` / `数据概览` / `项目管理` / `企业管理`
 * 各只有一个权限，若保留分组就会渲染成同名两级（`▼ 数据概览` → `☑ 数据概览`），
 * 纯属噪音。提升后层级与点击次数都不变，只是少了一层同名包裹。</p>
 */
const treeData = computed<TreeNode[]>(() => {
  // 先按 label 归并（顺序取首次出现的位置），保证"权限与审计"这类多前缀分组只出现一次
  const order: string[] = []
  const buckets = new Map<string, TreeNode[]>()
  for (const permission of props.permissions) {
    const label = groupLabelOf(permission.permCode)
    if (!buckets.has(label)) {
      buckets.set(label, [])
      order.push(label)
    }
    buckets.get(label)!.push({
      key: permission.permCode,
      label: permission.permName || permission.permCode,
      permCode: permission.permCode,
      dangerReason: DANGER_REASONS[permission.permCode],
      // 分组已按模块拆分，叶子不再需要 children
    })
  }
  return order.map((label) => {
    const children = buckets.get(label)!
    // 单权限分组：直接返回该叶子（它的 key 就是 permCode，仍是顶层可勾选项）
    if (children.length === 1) {
      return children[0]
    }
    return {
      key: `${GROUP_KEY_PREFIX}${label}`,
      label,
      children
    }
  })
})

/** 全部真实权限码，用于把勾选结果里的合成分组 key 滤掉。 */
const permCodeSet = computed(() => new Set(props.permissions.map((p) => p.permCode)))

/**
 * 把 el-tree 给出的勾选 key 收敛为**真实权限码**。
 *
 * 分组节点被整体勾选时，其合成 key（`group::xxx`）也会出现在 checkedKeys 里，
 * 因此必须按权限码白名单过滤——不能把 checkedKeys 原样提交给后端。
 */
function toPermCodes(checkedKeys: string[]): string[] {
  return checkedKeys.filter((key) => permCodeSet.value.has(key))
}

/**
 * el-tree 的 check 事件。第二个参数带有 checkedKeys，因此**无需持有组件实例**，
 * 也就避免了"用 ref 调 setCheckedKeys 同步初始勾选态"这套易错逻辑。
 */
function handleCheck(_node: TreeNode, info: { checkedKeys: string[] }): void {
  emit('update:modelValue', toPermCodes(info.checkedKeys ?? []))
}
</script>

<template>
  <div class="permission-tree">
    <el-tree
      :data="treeData"
      node-key="key"
      show-checkbox
      default-expand-all
      :default-checked-keys="modelValue"
      :props="{ label: 'label', children: 'children' }"
      :disabled="disabled"
      @check="handleCheck"
    >
      <template #default="{ data }">
        <span class="permission-tree__node">
          <span :class="{ 'permission-tree__leaf': !!data.permCode }">{{ data.label }}</span>
          <!-- 危险权限：图标 + tooltip 说明原因。禁用元素不触发鼠标事件，故用 el-tooltip 包 span -->
          <el-tooltip v-if="data.dangerReason" :content="data.dangerReason" placement="right">
            <span class="permission-tree__danger">
              <el-icon><WarningFilled /></el-icon>
              <span class="permission-tree__danger-text">高危</span>
            </span>
          </el-tooltip>
        </span>
      </template>
    </el-tree>
  </div>
</template>

<style scoped>
.permission-tree {
  max-height: 380px;
  overflow-y: auto;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  padding: 8px;
}

.permission-tree__node {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.permission-tree__leaf {
  color: var(--el-text-color-regular);
}

.permission-tree__danger {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  color: var(--el-color-danger);
  font-size: 12px;
}

.permission-tree__danger-text {
  font-weight: 600;
}
</style>
