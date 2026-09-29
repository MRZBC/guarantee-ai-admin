import { computed, ref } from 'vue'
import type { Ref } from 'vue'

/**
 * 服务端搜索的 `el-select` 数据源（下拉候选 + 最少字数 + 防抖）。
 *
 * <p><b>为什么需要它</b>：项目与企业各有数千条，做全量下拉既渲染不动、也没法用
 * （要在一屏里翻三千项）。所以这类"引用其它实体"的筛选统一走服务端模糊搜索：
 * 输入至少 {@link MIN_KEYWORD_LEN} 个字才向后端要数据、{@link DEBOUNCE_MS} 防抖、
 * 最多取 {@link PAGE_SIZE} 条。</p>
 *
 * <p><b>为什么不用 el-select 的 `remote` 模式</b>：element-plus 2.14.6 下
 * `remote + filterable` 的组合有缺陷——候选虽然渲染出来，但
 * ①包着它们的 `.el-scrollbar` 被加内联 `display:none`，面板塌成一条 20px 的空条；
 * ②选中候选项后 `update:modelValue` 不生效，`v-model` 收不到值。
 * 两者都实测复现过（见 styles/main.css 里那条覆盖规则的注释）。</p>
 *
 * <p>因此改为"普通 filterable + 自己打后端"：由 `@input` 触发 {@link RemoteSearch.search}，
 * 用 `filter-method` 恒真关掉 Element Plus 的本地过滤（候选已经是服务端过滤过的结果）。
 * 非 remote 的 el-select 在同一页面实测面板与选中**都正常**，这正是这么改的依据。</p>
 *
 * <p><b>为什么抽成 composable</b>：投标订单页与项目页要用同一套规则。
 * "至少 2 个字""300ms 防抖""选中项必须留在候选里"一旦各写一份，迟早漂移成
 * "两个页面搜同一个企业，行为却不一样"。</p>
 */

/** 少于这个字数不查：单字关键词会把全表扫一遍且结果没有区分度 */
export const MIN_KEYWORD_LEN = 2

/** 单次远程查询最多取多少条候选 */
export const PAGE_SIZE = 20

/** 输入防抖：每次按键都打后端会让"边输边搜"变成压测 */
export const DEBOUNCE_MS = 300

/** 候选项至少要能给出 id 与展示名——这是服务端搜索对实体类型的最小要求。 */
interface Searchable {
  id: number
}

export interface RemoteSearchOptions<T> {
  /**
   * 调用后端分页接口。`keyword` 已 trim 且长度已达标，无需再判断。
   * 各页面复用的是既有的业务分页接口（如 `/api/projects?projectName=`），
   * 它们都是"登录即可访问"，因此这类筛选不需要额外权限。
   */
  fetch: (keyword: string, pageSize: number) => Promise<T[]>
  /** 候选项文案（例如「项目名（编码）」），用于区分重名 */
  labelOf: (item: T) => string
  /** 未达最小字数时的提示，例如「请输入至少 2 个字」 */
  minLengthText: string
  /** 已达标但无结果时的提示，例如「无匹配项目」 */
  emptyText: string
}

export interface RemoteSearch<T> {
  options: Ref<T[]>
  searching: Ref<boolean>
  noDataText: Ref<string>
  /**
   * 绑到 `el-select` 的 **`filter-method`**。
   *
   * <p>它收到的是输入框当前文本，正是搜索需要的关键字，因此一职两用：
   * 既触发服务端搜索（{@link search}），又**恒返回 true** 以关掉 Element Plus 的本地过滤。</p>
   *
   * <p><b>为什么必须返回 true</b>：候选已经是服务端按关键字过滤过的结果，再让组件按
   * 输入值本地过滤一遍，会把"服务端命中但本地字面不匹配"的项删掉；
   * 而且 `filter-method` 返回假值等于"该项不匹配"，候选会被整批隐藏——
   * 那正好会退化成"面板在、里面却是空的"。</p>
   *
   * <p><b>为什么不用 `@input`</b>：`el-select` 的 input 事件发的是**选中值**
   * （未选中时为 `null`），不是输入文本。拿它当关键字会抛
   * `(raw ?? '').trim is not a function`（已实测踩到）。</p>
   */
  filterMethod: (keyword: string) => void
  /** 绑到 `el-select` 的 `@change`，把选中项保留在候选里 */
  handleChange: (id: number | null) => void
  labelOf: (item: T) => string
  /**
   * 复位为初始状态：清掉已选项、关键词、候选与待触发的防抖定时器。
   *
   * <p>「重置」按钮必须调它，而不是只把 `query.xxxId` 置空——否则候选里会残留
   * 上一位选中项的标签，用户看到下拉里还挂着一个"已经重置掉的"企业。</p>
   */
  reset: () => void
}

export function useRemoteSearch<T extends Searchable>(
  options: RemoteSearchOptions<T>
): RemoteSearch<T> {
  const list = ref([]) as Ref<T[]>
  const searching = ref(false)
  const keyword = ref('')
  /** 已选项：搜索替换候选时靠它把选中项留在列表里 */
  const selected = ref<T | null>(null) as Ref<T | null>
  let timer: ReturnType<typeof setTimeout> | undefined

  const noDataText = computed(() =>
    keyword.value.trim().length < MIN_KEYWORD_LEN ? options.minLengthText : options.emptyText
  )

  /** 把选中项并入候选（已在其中则不重复） */
  function withSelected(next: T[]): T[] {
    const current = selected.value
    if (!current || next.some((item) => item.id === current.id)) return next
    return [current, ...next]
  }

  function search(raw: string): void {
    const kw = (raw ?? '').trim()
    keyword.value = kw
    clearTimeout(timer)

    if (kw.length < MIN_KEYWORD_LEN) {
      // 未达字数：清空候选但保留已选项，避免标签退化
      list.value = withSelected([])
      searching.value = false
      return
    }

    searching.value = true
    timer = setTimeout(() => {
      void (async () => {
        try {
          const rows = await options.fetch(kw, PAGE_SIZE)
          list.value = withSelected(rows ?? [])
        } catch {
          // 失败按"无结果"处理：具体错误由响应拦截器统一提示
          list.value = withSelected([])
        } finally {
          searching.value = false
        }
      })()
    }, DEBOUNCE_MS)
  }

  function handleChange(id: number | null): void {
    selected.value = id == null
      ? null
      : list.value.find((item) => item.id === id) ?? selected.value
  }

  function reset(): void {
    clearTimeout(timer)
    selected.value = null
    keyword.value = ''
    list.value = []
    searching.value = false
  }

  /**
   * `filter-method` 的入口：触发服务端搜索并恒返回 true（不做本地过滤）。
   *
   * <p>返回值必须是真值——Element Plus 把假值当作"该项不匹配"，会把候选整批隐藏。</p>
   */
  function filterMethod(raw: string): boolean {
    search(raw)
    return true
  }

  return {
    options: list,
    searching,
    noDataText,
    filterMethod,
    handleChange,
    labelOf: options.labelOf,
    reset
  }
}
