/**
 * 树形表格（`el-table` + `row-key` + `tree-props`）的展开/收起工具。
 *
 * 机构配置与部门配置是两棵同构的树形表格，本文件是它们**共用**的展开逻辑。
 *
 * ## 为什么不用 `:expand-row-keys`
 *
 * 直觉上 `:expand-row-keys="keys"` 是"受控展开"的正解，但在 el-table 上它**不可靠**：
 * 当 keys 在挂载后才被赋值（异步取数的页面必然如此），表格不会重新应用它。
 * 实测（element-plus 2.14.6，用 CDP 驱动真实浏览器逐项验证）：
 *
 * | 场景 | 结果 |
 * | --- | --- |
 * | keys 在 setup 时就非空 | 不生效 |
 * | keys 从 `[]` 异步改成 `[1]` | 不生效 |
 * | 用 `v-if` 把表格推迟到 keys 就绪后挂载 | 不生效 |
 * | 改 `:key` 强制重新挂载 | 不生效 |
 * | **用 ref 调 `toggleRowExpansion(row, true/false)`** | **生效** |
 *
 * 根因在 el-table 内部：`props.expandRowKeys` 经 `style-helper` 的 `watchEffect` 同步进
 * store 后，`tree.mjs` 的 `updateTreeData` 并没有把新 keys 落到每行的 `expanded` 标志上
 * （`getExpanded` 走 `ifChangeExpandRowKeys` 分支时仍被 `ifExpandAll` 短路）。
 * 现象极具迷惑性——`store.expandRowKeys` 显示已是 `[1,2,8]`，但每行 `expanded` 仍是 false，
 * 表格纹丝不动。行内箭头能正常展开，是因为它走的是 `toggleTreeExpansion` 这条
 * **真正会写 expanded 标志**的路径。
 *
 * 结论：展开状态以**表格自身**为准（{@link readExpandedKeys} 读），页面只维护"期望值"，
 * 由 {@link setRowExpansion} 把差异下发给表格。
 */

/** 行级展开 API（`el-table` 通过 ref 暴露的最小契约）。 */
export interface ExpandableTable {
  toggleRowExpansion: (row: unknown, expanded: boolean) => void
}

/**
 * 深度优先收集所有**有子节点**的行。
 *
 * 只收父节点：叶子节点没有展开箭头，对它们调 `toggleRowExpansion` 是无效动作。
 * 返回节点本身而不是键——下发时要把握手用的节点对象交回给 `el-table`。
 */
export function collectExpandableNodes<T>(nodes: readonly T[], childrenOf: (node: T) => readonly T[]): T[] {
  const result: T[] = []
  const walk = (list: readonly T[]): void => {
    for (const node of list) {
      const children = childrenOf(node)
      if (children.length > 0) {
        result.push(node)
        walk(children)
      }
    }
  }
  walk(nodes)
  return result
}

/**
 * 深度优先收集所有**有子节点**的行的键。
 *
 * 与 {@link collectExpandableNodes} 分开，是因为消费方不同：收集键用于"默认展开到第几层"
 * 这类只关心键的场合（如按 `orgLevel <= 2` 筛选），收集节点用于把状态下发给表格。
 */
export function collectExpandableKeys<K, T>(
  nodes: readonly T[],
  keyOf: (node: T) => K,
  childrenOf: (node: T) => readonly T[]
): K[] {
  return collectExpandableNodes(nodes, childrenOf).map(keyOf)
}

/**
 * 从表格当前的展开状态反推出"哪些行是展开的"。
 *
 * <p><b>为什么必须由表格来告诉我们</b>：el-table 的展开态有两个来源——页面下发的
 * `toggleRowExpansion`，以及用户点行内箭头。后者只通过 `expand-change` 事件通知一行，
 * 但"全部展开/收起"是批量动作，事件逐个到达既慢又难对齐。直接在渲染前读一次表格的
 * 实际状态，是唯一不会与用户操作脱节的口径。</p>
 *
 * <p>返回全量（不只是有子节点的行），调用方自行取交集即可。</p>
 */
export function readExpandedKeys<K, T>(
  nodes: readonly T[],
  keyOf: (node: T) => K,
  childrenOf: (node: T) => readonly T[],
  isExpanded: (node: T) => boolean
): Set<K> {
  const expanded = new Set<K>()
  const walk = (list: readonly T[]): void => {
    for (const node of list) {
      if (isExpanded(node)) expanded.add(keyOf(node))
      walk(childrenOf(node))
    }
  }
  walk(nodes)
  return expanded
}

/**
 * 把页面的**期望展开态**下发到表格：只对不一致的行调 API。
 *
 * <p><b>`current` 必须是表格的实际状态，而不是期望值本身</b>。本函数早先的版本把两者
 * 写成同一个集合（`expanded.has(key) !== shouldExpand(key)` 恒为 false），于是永远不调
 * API、永远不生效——这个错误静默且难查，因为读起来完全像是在做"纠偏"。</p>
 *
 * <p>只纠偏不一致的行：`toggleRowExpansion` 是"切换"语义，对已经处于目标态的行再调一次
 * 会把它翻回去。</p>
 *
 * @param current 表格**实际**已展开的键集合
 * @param wanted  页面的目标态：键 → 是否应展开
 */
export function setRowExpansion<K, T>(
  table: ExpandableTable | null | undefined,
  current: ReadonlySet<K>,
  wanted: (key: K) => boolean,
  nodes: readonly T[],
  keyOf: (node: T) => K,
  childrenOf: (node: T) => readonly T[]
): void {
  if (!table) return
  const walk = (list: readonly T[]): void => {
    for (const node of list) {
      const children = childrenOf(node)
      if (children.length === 0) continue
      const key = keyOf(node)
      const want = wanted(key)
      if (current.has(key) !== want) {
        table.toggleRowExpansion(node, want)
      }
      walk(children)
    }
  }
  walk(nodes)
}

/**
 * 从**已渲染的 DOM** 反查当前展开的行键。
 *
 * <p>这是整个展开方案里最可靠的一环，也是踩了多次坑之后确定下来的做法：
 * el-table 的展开态有两个来源（页面下发的行级 API、用户点行内箭头），而
 * `expand-change` 是**逐行**事件；任何"页面自己记账"的方案都迟早与表格实际状态漂移。</p>
 *
 * <p><b>为什么不能按 `data-row-key` 查</b>：实测（element-plus 2.14.6）树形表格的行上
 * **没有** `data-row-key` 属性，无法直接用键定位到行。可行的口径是**顺序对齐**：
 * Element Plus 以同一深度优先顺序渲染"有子节点的行"与它们的展开图标，
 * 因此把树上"可展开的行"按序取出，与页面上的 `.el-table__expand-icon` 按序配对即可。</p>
 *
 * @returns 当前 `aria-expanded === 'true'` 的行的键集合
 */
export function readOpenKeysFromDom<K, T>(
  nodes: readonly T[],
  keyOf: (node: T) => K,
  childrenOf: (node: T) => readonly T[]
): Set<K> {
  const open = new Set<K>()
  if (typeof document === 'undefined') return open
  const icons = document.querySelectorAll('.el-table__expand-icon')
  let cursor = 0
  const walk = (list: readonly T[]): void => {
    for (const node of list) {
      const children = childrenOf(node)
      if (children.length === 0) continue
      const icon = icons[cursor]
      cursor += 1
      if (icon?.getAttribute('aria-expanded') === 'true') open.add(keyOf(node))
      walk(children)
    }
  }
  walk(nodes)
  return open
}

/**
 * 把展开键收敛为**当前树上仍然存在的键**。
 *
 * 刷新后树的形状可能变了（节点被删除、被筛选条件过滤掉、或被移动到别处）。旧的键如果
 * 原样留着会随每次刷新累积，也会让"期望态"里混入树上不存在的键。过滤一次即可同步。
 */
export function pruneKeysToTree<K, T>(
  keys: ReadonlySet<K>,
  nodes: readonly T[],
  keyOf: (node: T) => K,
  childrenOf: (node: T) => readonly T[]
): K[] {
  const alive = new Set<K>()
  const walk = (list: readonly T[]): void => {
    for (const node of list) {
      alive.add(keyOf(node))
      walk(childrenOf(node))
    }
  }
  walk(nodes)
  return [...keys].filter((key) => alive.has(key))
}
