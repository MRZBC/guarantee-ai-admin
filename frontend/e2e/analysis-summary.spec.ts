import { test, expect, type Page } from '@playwright/test'
import type { OrderReport, AnalysisSummary } from '../src/types/analysis'

async function open(page: Page): Promise<OrderReport> {
  await page.goto('/#/login')
  await page.getByPlaceholder('请输入用户名').fill('admin')
  await page.getByPlaceholder('请输入密码', { exact: true }).fill('Admin@123')
  await page.locator('.login-card__submit').click()
  await expect(page).toHaveURL(/dashboard/)
  const response = page.waitForResponse(r => new URL(r.url()).pathname === '/api/analysis/order-report')
  await page.goto('/#/analysis/overview?orderType=TENDER')
  const report = (await (await response).json()).data as OrderReport
  await expect(page.getByRole('button', { name: '让助手解读', exact: true })).toBeEnabled()
  return report
}

function fixture(report: OrderReport, status: AnalysisSummary['status'] = 'GENERATED'): AnalysisSummary {
  return { status, report, reportFingerprint: report.reportFingerprint, generatedAt: report.generatedAt,
    message: status === 'GENERATED' ? '已根据本次报告整理关注点' : '以下仅展示服务端统计事实', traceId: 'summary-browser-test',
    observations: status === 'GENERATED' ? [{ text: '本期订单数可由报告核对。', evidenceIds: ['orders'] }] : [],
    evidence: [{ id: 'orders', kind: 'CHANGE', text: `本期订单数${report.comparison.orderCount.current}笔`,
      drilldown: { startDate: report.comparison.criteria.startDate!, endDate: report.comparison.criteria.endDate! } }],
    limitations: ['缺少分类上期数据，无法判断各分类对涨跌的贡献或原因。'] }
}

test('解读携带已应用筛选、展示依据并下钻对应订单', async ({ page }) => {
  const report = await open(page)
  let calls = 0
  await page.route('**/api/ai/analysis-summary', async route => {
    calls++
    const body = route.request().postDataJSON()
    expect(body.reportFingerprint).toBe(report.reportFingerprint)
    expect(body.criteria).toEqual(report.comparison.criteria)
    await route.continue()
  })
  expect(calls).toBe(0)
  await page.getByRole('button', { name: '让助手解读', exact: true }).click()
  const result = page.getByTestId('analysis-interpretation-result')
  await expect(result).toContainText('AI 分析解读')
  await expect(result).toContainText(String(report.comparison.orderCount.current))
  await expect(result).toContainText('缺少分类上期数据')
  await page.screenshot({ path: 'test-results/analysis-interpretation.png', fullPage: true })
  await result.getByRole('button', { name: '查看对应订单' }).first().click()
  await expect(page).toHaveURL(/orders\/tender/)
  expect(page.url()).toContain(`startDate=${report.comparison.criteria.startDate}`)
  expect(calls).toBe(1)
})

test('数据更新先刷新报告，再由用户重试；模型失败仅展示事实', async ({ page }) => {
  const report = await open(page)
  const updated = structuredClone(report)
  updated.reportFingerprint = 'a'.repeat(64)
  updated.comparison.orderCount.current++
  let calls = 0
  await page.route('**/api/ai/analysis-summary', async route => {
    calls++
    await route.fulfill({ status: calls === 1 ? 409 : 200, json: { code: 0, data: fixture(updated, calls === 1 ? 'REPORT_CHANGED' : 'FACTS_ONLY') } })
  })
  await page.getByRole('button', { name: '让助手解读', exact: true }).click()
  await expect(page.getByTestId('analysis-interpretation')).toContainText('数据已更新，请重新解读')
  await expect(page.getByTestId('analysis-interpretation-result')).toHaveCount(0)
  expect(calls).toBe(1)
  await page.getByRole('button', { name: '让助手解读', exact: true }).click()
  await expect(page.getByTestId('analysis-interpretation-result')).toContainText('统计事实（未生成 AI 解读）')
  expect(calls).toBe(2)
})

test('未应用筛选禁用生成，取消与离页后的迟到结果不污染页面', async ({ page }) => {
  const report = await open(page)
  const start = page.getByRole('combobox', { name: '统计区间', exact: true })
  await start.fill('2026-09-02')
  await start.press('Enter')
  await expect(page.getByRole('button', { name: '让助手解读', exact: true })).toBeDisabled()
  const refreshed = page.waitForResponse(r => new URL(r.url()).pathname === '/api/analysis/order-report')
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await refreshed
  await expect(page.getByRole('button', { name: '让助手解读', exact: true })).toBeEnabled()
  let release!: () => void
  const gate = new Promise<void>(resolve => { release = resolve })
  await page.route('**/api/ai/analysis-summary', async route => {
    await gate
    await route.fulfill({ json: { code: 0, data: fixture(report) } }).catch(() => {})
  })
  await page.getByRole('button', { name: '让助手解读', exact: true }).click()
  await expect(page.getByText('正在根据本次报告整理关注点…')).toBeVisible()
  await page.getByRole('button', { name: '取消等待' }).click()
  release()
  await expect(page.getByTestId('analysis-interpretation-result')).toHaveCount(0)
  await page.goto('/#/dashboard')
  await expect(page.getByTestId('analysis-interpretation-result')).toHaveCount(0)
})

test('页面收回AI能力隐藏入口，非法请求由服务端拒绝', async ({ page }) => {
  await open(page)
  const token = await page.evaluate(() => localStorage.getItem('guarantee_admin_token'))
  const response = await page.request.post('/api/ai/analysis-summary', { data: {}, headers: { Authorization: `Bearer ${token}` } })
  expect((await response.json()).code).toBe(400)
  // 服务端缺单项权限的组合由AnalysisSummarySecurityTest验证；这里模拟客户端能力收回。
  await page.evaluate(() => {
    const user = JSON.parse(localStorage.getItem('guarantee_admin_user')!)
    user.permissions = user.permissions.filter((permission: string) => permission !== 'ai:chat')
    localStorage.setItem('guarantee_admin_user', JSON.stringify(user))
  })
  await page.reload()
  await expect(page.getByTestId('analysis-summary')).toBeVisible()
  await expect(page.getByRole('button', { name: '让助手解读', exact: true })).toHaveCount(0)
})
