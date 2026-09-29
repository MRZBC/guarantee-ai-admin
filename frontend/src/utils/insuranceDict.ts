/**
 * 险种类别字典（`insurance_type.category`，VARCHAR：TENDER / PERFORMANCE / …）。
 *
 * <p><b>为什么要单独抽一份</b>：同一个 code 至少要在三处渲染成中文——
 * 险种配置页的列表与筛选下拉、险种表单的「险种类别」下拉、以及订单详情里的「险种类别」。
 * 前两处原来各写一份（`categoryMap` + 表单里硬编码的 5 个 `el-option`），
 * 订单详情干脆没翻译，直接把 `TENDER` 显示给用户（现场反馈："险种类别要用中文"）。
 * 字典一旦分散，改一处就会漏一处——这与项目状态字典那次的根因是同一类问题。</p>
 */

/**
 * 类别 code → 文案。**取值以这里为准**：险种配置页的筛选下拉直接遍历它，
 * 因此新增/改名只需要动这一处。
 *
 * <p>前两个是当前表单可创建的口径（{@link CATEGORY_FORM_OPTIONS}）；
 * 其余为历史/预留类别，保留是为了让老数据在列表里也能显示中文而不是裸 code。</p>
 */
export const INSURANCE_CATEGORY_LABELS: Record<string, string> = {
  TENDER: '投标担保',
  PERFORMANCE: '履约担保',
  BID: '投标担保',
  CONTRACT: '合同履约',
  QUALITY: '质量保证',
  ADVANCE: '预付款担保',
  OWNER: '业主支付',
  OTHER: '其他'
}

/**
 * 表单里可选的类别（`InsuranceTypes.vue` 的新建/编辑下拉）。
 *
 * <p>比 {@link INSURANCE_CATEGORY_LABELS} 窄：`BID` / `CONTRACT` / `OWNER` 是历史口径，
 * 允许老数据展示、但不作为新险种的可选项。</p>
 */
export const CATEGORY_FORM_OPTIONS: { label: string; value: string }[] = [
  { label: INSURANCE_CATEGORY_LABELS.TENDER, value: 'TENDER' },
  { label: INSURANCE_CATEGORY_LABELS.PERFORMANCE, value: 'PERFORMANCE' },
  { label: INSURANCE_CATEGORY_LABELS.QUALITY, value: 'QUALITY' },
  { label: INSURANCE_CATEGORY_LABELS.ADVANCE, value: 'ADVANCE' },
  { label: INSURANCE_CATEGORY_LABELS.OTHER, value: 'OTHER' }
]

/**
 * 险种类别文案。
 *
 * <p>后端给了 `categoryName` 时以它为准（字典可配，将来可能由后端下发）；
 * 否则查本地字典；再查不到**原样回显 code 而不是空白**——宁可让人看到 `TENDER`，
 * 也不要显示一个不知道缺了什么的空格子。</p>
 */
export function insuranceCategoryLabel(
  category: string | null | undefined,
  categoryName?: string | null
): string {
  if (categoryName) return categoryName
  if (!category) return '--'
  return INSURANCE_CATEGORY_LABELS[category.toUpperCase()] ?? category
}
