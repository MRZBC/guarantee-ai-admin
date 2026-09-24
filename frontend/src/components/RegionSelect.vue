<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { listRegionOptions } from '@/api/system'
import type { RegionOption } from '@/types/system'

/**
 * 地区选择（行政区划字典：省 / 市 / 区县三级级联）。
 *
 * <p><b>为什么是级联而不是普通下拉</b>：整本字典有 3400+ 条（34 省 + 342 市 + 3056 区县），
 * 平铺下拉既扫不动也没法用。级联把层级关系直接表达出来，配合
 * <b>{@code check-strictly}</b> 可以只选到省或市，配合 <b>{@code filterable}</b> 可以
 * 直接输入名称或区划码跨级搜索。</p>
 *
 * <p><b>口径</b>（产品口径）：**下拉看到整本字典数据**——调用 `onlyWithData=false`
 * 拿全部启用地区；不与"是否有业务数据"挂钩。筛选值的匹配由后端按**层级前缀**处理
 * （{@code RegionCodePrefix}：省 330000 → 匹配 `33%`），所以"选省"能筛出挂在市/区县码上的记录。</p>
 *
 * <p><b>值语义</b>：`v-model` 绑定**单个区划码**（string，`emitPath: false`）；
 * 清空 = `null` = "全部地区"，页面把它转成"不带 regionCode 参数"。</p>
 */
withDefaults(
  defineProps<{
    /**
     * 已选区划码（v-model）；null / undefined / 空串 都表示"全部地区"。
     *
     * <p>刻意兼容三种空值：四个页面的查询对象里 `regionCode?: string`（可选字段），
     * 清空时控件也可能给 `''`，组件内部统一收敛成 null 再向外 emit。</p>
     */
    modelValue?: string | null
    placeholder?: string
    disabled?: boolean
  }>(),
  { placeholder: '全部地区（可选到省/市/区县）', disabled: false }
)

const emit = defineEmits<{ 'update:modelValue': [string | null] }>()

interface RegionNode {
  code: string
  name: string
  shortName: string | null
  level: number
  children?: RegionNode[]
}

/*
  模块级缓存：四个页面（以及同一页面的多次挂载）共用一份字典，
  否则每进一个页面就多打一次 3400 条的数据。失败不入缓存，下次进入还能重试。
*/
let cache: RegionNode[] | null = null
let inflight: Promise<RegionNode[]> | null = null

/** 扁平列表 → 省/市/区县树（按 level + parentCode 组装，顺序沿用后端的 sort_no） */
function buildTree(rows: RegionOption[]): RegionNode[] {
  const nodes = new Map<string, RegionNode>()
  rows.forEach((row) => nodes.set(row.code, { ...row, children: [] }))

  const roots: RegionNode[] = []
  rows.forEach((row) => {
    const node = nodes.get(row.code) as RegionNode
    const parent = row.parentCode
    if (row.level === 1 || !parent) {
      roots.push(node)
      return
    }
    const parentNode = nodes.get(parent)
    if (parentNode) {
      parentNode.children = parentNode.children ?? []
      parentNode.children.push(node)
    } else {
      // 上级缺失（理论上不会发生）时挂到根，避免数据整块消失
      roots.push(node)
    }
  })

  // 叶子节点不留空 children，级联控件才不会给"可展开"的假象
  nodes.forEach((node) => {
    if (node.children && node.children.length === 0) delete node.children
  })
  return roots
}

async function fetchRegions(): Promise<RegionNode[]> {
  if (cache) return cache
  if (!inflight) {
    // onlyWithData=false：整本字典（产品口径）；level 不传 = 全层级
    inflight = listRegionOptions({ onlyWithData: false })
      .then((list) => {
        cache = buildTree(list ?? [])
        return cache
      })
      .catch(() => [])
      .finally(() => {
        inflight = null
      })
  }
  return inflight
}

const options = ref<RegionNode[]>([])
const loading = ref(false)

/** 搜索：名称、简称、区划码都能命中（默认只按名称匹配） */
function filterMethod(node: { text?: string; value?: unknown }, keyword: string): boolean {
  const q = keyword.trim().toLowerCase()
  if (!q) return true
  return (node.text ?? '').toLowerCase().includes(q)
    || String(node.value ?? '').includes(q)
}

function handleChange(value: string | number | null | undefined): void {
  emit('update:modelValue', value ? String(value) : null)
}

onMounted(async () => {
  loading.value = true
  try {
    options.value = await fetchRegions()
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <el-cascader
    :model-value="modelValue ?? null"
    :options="options"
    :props="{
      value: 'code',
      label: 'name',
      checkStrictly: true,
      emitPath: false
    }"
    :placeholder="loading ? '加载中…' : placeholder"
    :disabled="disabled"
    :show-all-levels="false"
    :filter-method="filterMethod"
    clearable
    filterable
    separator=" / "
    style="width: 100%"
    @change="handleChange"
  />
</template>
