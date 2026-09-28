---
type: lesson
title: 树形表格的展开状态必须从 DOM 回读
status: active
related:
  - 决策 - 业务口径唯一实现
  - 后端写接口已通而前端入口未接
tags: [frontend, element-plus, el-table, debugging]
---

> `el-table` 的 `:expand-row-keys` 在**挂载后才赋值**时不生效；
> 展开态有两个来源，页面自己"记账"必然漂移。
> 可靠做法只有一条：**用 ref 调 `toggleRowExpansion` 下发，再从 DOM 回读实际状态定案。**

## 发生了什么

机构配置页的「全部展开 / 全部收起」点了没反应（部门配置页当时连这两个按钮都没有）。
这个 bug 花了 **4 轮**才修对，前 3 轮的诊断和修复**全是错的**——记录错误的推理过程比记录结论更有价值。

### 第 1 轮：错在"读了源码就以为懂了"

现象是"收起无效"。读 `element-plus/es/components/table/src/store/tree.mjs` 后发现：

```js
const updateTreeData = (ifChangeExpandRowKeys = false, ifExpandAll) => {
  ifExpandAll ||= instance.store?.states.defaultExpandAll.value;   // ← 看起来是元凶
  ...
};
```

于是判定"`default-expand-all` 与 `expand-row-keys` 冲突"，**删掉 `default-expand-all`** 就收工。
**结果：完全无效。** 源码读得没错，但那只解释了一部分行为，我把"看起来像根因的线索"当成了根因。

### 第 2 轮：错在"没有验证手段，继续推理"

又去读渲染层（`config.mjs` / `render-helper.mjs`），推出"展开态两个来源不一致"，于是写了个共用工具
`treeExpand.ts`。**仍然无效**，而且我还写出了一行自相矛盾的代码：

```ts
// current 与 wanted 来自同一个集合 —— 这个判断恒为 false，永远不调 API
if (current.has(key) !== wanted(key)) { table.toggleRowExpansion(node, want) }
```

它读起来完全像"纠偏"，实际是**静默空转**。这类错误靠读代码发现不了，只有跑起来才暴露。

### 第 3 轮：改用真实浏览器，才拿到真结果

用 Chrome DevTools Protocol（headless + `--remote-debugging-port`）驱动真实页面，逐项验证：

| 场景 | 结果 |
| --- | --- |
| keys 在 setup 时就非空 | ❌ 不生效 |
| keys 从 `[]` 异步改成 `[1]` | ❌ 不生效 |
| 用 `v-if` 把表格推迟到 keys 就绪后挂载 | ❌ 不生效 |
| 改 `:key` 强制重新挂载 | ❌ 不生效 |
| **用 ref 调 `toggleRowExpansion(row, true/false)`** | ✅ **生效** |

**真相：`:expand-row-keys` 在本版本（element-plus 2.14.6）根本不可靠**，与
`default-expand-all` 无关。我第 1 轮删掉 `default-expand-all` 只是顺手改对了半个症状。

### 第 4 轮：状态口径又一次错

改成行级 API 后，"展开"生效了，"收起"仍无效。加日志才看到：
`current`（表格实际态）与 `target`（期望态）**是同一个集合**，于是又退化成恒等空转。

最终定下的方案：**以 DOM 回读的展开态为唯一事实**。

```js
// aria-expanded 是 el-table 自己渲染的，它就是渲染结果的直接反映。
// 注意：树形表格的 <tr> 上**没有** data-row-key，无法按 key 定位；
// 可行的口径是"顺序对齐"——el-table 以同一深度优先顺序渲染
// 「有子节点的行」与其展开图标，按序配对即可。
export function readOpenKeysFromDom(nodes, keyOf, childrenOf) {
  const icons = document.querySelectorAll('.el-table__expand-icon')
  let cursor = 0
  // 按同样的深度优先顺序遍历树，只对"有子节点"的行计数
  ...
}
```

下发改成两阶段：先按 DOM 实际态**关闭**，再按期望值**打开**
（`toggleRowExpansion` 是切换语义，不先关会被"当前态"抵消）。

## 为什么重要

- **"读了源码"不等于"验证了行为"。** 我第 1 轮读完 `tree.mjs` 就敢下结论，结果是错的。
  真正的信息来自那张对照表——而它只能用真实浏览器跑出来。
- **前端没有测试框架时，"推理链"是唯一手段，也最容易自欺。**
  本项目 `vue-tsc` 只查类型、`vite build` 只查打包，两者都**发现不了**"API 调了但少传一个参数"
  和"判断恒等"这类错误。
- **同一个坑以三种不同面貌出现过**：比对恒等、播种被事件覆盖、行对象被重建。
  它们共同的根因是"试图在页面侧维护一份与组件内部并行的状态"。
  **只要存在两个真相来源，就一定会漂移**——这不是这次特有的问题，是通用规律。

## 怎么做的

`frontend/src/utils/treeExpand.ts`（机构配置与部门配置共用）：

```text
collectExpandableNodes / collectExpandableKeys   收集有子节点的行
setRowExpansion(table, current, wanted, …)       按差异下发 toggleRowExpansion
readOpenKeysFromDom(nodes, keyOf, childrenOf)    ★ 从 DOM 回读真实展开态
pruneKeysToTree                                  丢弃树上已不存在的键
```

页面侧固定为三段式：

```text
期望值(target) → 下发(reconcile: 先关实际、再开期望) → 回读 DOM 并记入 expandedKeys
```

## 坑 / 限制

- **`:expand-row-keys` 不要用**（本版本实测不可靠）。行内箭头、表头图标都能正常工作，
  不可靠的只有这个 prop。
- **树形表格的 `<tr>` 没有 `data-row-key`**，别按 key 选择行；用"可展开行 ↔ 展开图标"
  的顺序对齐。
- **`toggleRowExpansion` 是切换语义**，下发前必须知道当前是否已展开，否则会反向翻转。
- **`default-expand-all` 的响应式也不能用来收起**：把它改回 `false` 不会收起已展开的行
  （`getExpanded` 里 `oldValue?.expanded || …` 会保住旧值）。它只在"展开"方向可用。
- 需要程序化控制展开时，**优先找组件暴露的行级 API，而不是受控 prop**——
  行级 API 走的是与用户点击完全相同的代码路径，因此不会出现"prop 设了但没反应"。

## 调试方法本身也值得记下

CDP 驱动真实页面的脚手架（本次临时搭的，用完已删）值得复用：

```text
1. chrome --headless=new --remote-debugging-port=9333 --user-data-dir=<临时目录>
2. 连 /json/list 拿 webSocketDebuggerUrl
3. Runtime.evaluate 里 fetch('/api/auth/login') 拿 token → 写 localStorage
4. Page.navigate 到 '#/<路由>'（注意是 hash 路由）
5. 在页面里构造"对照实验"（多种写法并排），把结果写进 document.body.dataset
6. Runtime.evaluate 读回来
```

两个坑：**必须用文件重定向或 CDP 读结果**（`--dump-dom` 直接管道在某些调用方式下拿不到输出）；
**hash 路由**要写成 `/#/system/orgs`，写成 `/system/orgs` 会被重定向到首页。
