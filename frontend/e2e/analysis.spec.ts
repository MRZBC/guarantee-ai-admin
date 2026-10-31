import { test, expect, type Page } from '@playwright/test'
import type { OrderReport } from '../src/types/analysis'

async function login(page: Page, account = 'admin', password = 'Admin@123') {
  await page.goto('/#/login')
  await page.getByPlaceholder('请输入用户名').fill(account)
  await page.getByPlaceholder('请输入密码', { exact: true }).fill(password)
  await page.locator('.login-card__submit').click()
  await expect(page).toHaveURL(/dashboard/)
}

async function openAnalysis(page: Page, type = 'TENDER'): Promise<OrderReport> {
  const response = page.waitForResponse(r => new URL(r.url()).pathname === '/api/analysis/order-report')
  await page.goto(`/#/analysis/overview?orderType=${type}`)
  const body = await (await response).json()
  expect(body.code).toBe(0)
  await expect(page.getByTestId('analysis-summary')).toBeVisible()
  return body.data
}

for (const type of ['TENDER', 'PERFORMANCE']) {
  for (const dimension of ['地区 Top 10', '险种', '机构与地区 Top 10']) {
    test(`${type} ${dimension} 下钻数量、刷新与返回一致`, async ({ page }) => {
      await login(page)
      const report = await openAnalysis(page, type)
      const source = dimension === '地区 Top 10' ? report.regions : dimension === '险种' ? report.insurances : report.institutions
      expect(source.length).toBeGreaterThan(0)
      await page.getByRole('tab', { name: dimension, exact: true }).click()
      const response = page.waitForResponse(r => new URL(r.url()).pathname === `/api/orders/${type === 'TENDER' ? 'tender' : 'performance'}`)
      await page.locator('.el-tab-pane:visible tbody tr').first().getByRole('button', { name: '查看订单' }).click()
      const body = await (await response).json()
      expect(body.data.total).toBe(source[0].orderCount)
      await expect(page).toHaveURL(/orders\/(tender|performance)/)
      expect(page.url()).toContain(`startDate=${report.comparison.criteria.startDate}`)
      if (dimension !== '险种') expect(page.url()).toContain('regionExact=true')
      const refreshed = page.waitForResponse(r => new URL(r.url()).pathname.startsWith('/api/orders/'))
      await page.reload()
      expect((await (await refreshed).json()).data.total).toBe(source[0].orderCount)
      await page.goBack()
      await expect(page.getByTestId('analysis-summary')).toContainText(report.comparison.criteria.startDate!)
    })
  }
}

test('摘要下载包含服务端数值与日期口径', async ({ page }) => {
  await login(page)
  const overview = page.waitForResponse(r => new URL(r.url()).pathname === '/api/analysis/overview')
  const report = await openAnalysis(page)
  await overview
  const download = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出分析摘要 CSV' }).click()
  const file = await download
  expect(file.suggestedFilename()).toBe('运营分析摘要.csv')
  const stream = await file.createReadStream()
  const chunks: Buffer[] = []
  for await (const chunk of stream!) chunks.push(Buffer.from(chunk))
  const csv = Buffer.concat(chunks).toString('utf8')
  expect(csv).toContain(report.comparison.criteria.startDate!)
  expect(csv).toContain(String(report.comparison.orderCount.current))
  expect(csv).toContain('Top 10，非全量')
  await page.screenshot({ path: 'test-results/analysis-overview.png', fullPage: true })
})

test('运营角色无导出入口，直接请求也被后端拒绝', async ({ page }) => {
  await login(page, 'operator', 'Operator@123')
  await openAnalysis(page)
  await expect(page.getByRole('button', { name: '导出分析摘要 CSV' })).toHaveCount(0)
  const token = await page.evaluate(() => localStorage.getItem('guarantee_admin_token'))
  const response = await page.request.get('/api/analysis/order-report/export?orderType=TENDER', { headers: { Authorization: `Bearer ${token}` } })
  expect((await response.json()).code).toBe(403)
})

test('非法日期路由不会发出无条件订单查询', async ({ page }) => {
  await login(page)
  let queried = false
  page.on('request', request => { if (new URL(request.url()).pathname === '/api/orders/tender') queried = true })
  await page.goto('/#/orders/tender?startDate=2024-02-30&endDate=2024-03-01')
  await expect(page.getByText('申请日期区间无效', { exact: true })).toBeVisible()
  expect(queried).toBe(false)
})

test('下钻后重置清空精确地区和日期条件，离页缓存不发额外查询', async ({ page }) => {
  await login(page)
  await openAnalysis(page)
  await page.getByRole('button', { name: '查看订单', exact: true }).first().click()
  await expect(page).toHaveURL(/orders\/tender/)
  let analysisQueries = 0
  page.on('request', request => {
    if (new URL(request.url()).pathname === '/api/analysis/order-report') analysisQueries++
  })
  const response = page.waitForResponse(r => {
    const url = new URL(r.url())
    return url.pathname === '/api/orders/tender' && !url.searchParams.has('startDate') && !url.searchParams.has('regionCode')
  })
  await page.getByRole('button', { name: '重置', exact: true }).click()
  expect((await (await response).json()).data.total).toBe(100000)
  expect(page.url()).not.toContain('regionExact')
  expect(page.url()).not.toContain('startDate')
  expect(analysisQueries).toBe(0)
})

test('旧的慢分析响应不会覆盖新的筛选结果', async ({ page }) => {
  await login(page)
  let release: (() => void) | undefined
  const gate = new Promise<void>(resolve => { release = resolve })
  let firstStarted: (() => void) | undefined
  const started = new Promise<void>(resolve => { firstStarted = resolve })
  await page.route('**/api/analysis/order-report?**', async route => {
    const response = await route.fetch()
    if (new URL(route.request().url()).searchParams.get('orderType') === 'TENDER') { firstStarted!(); await gate }
    await route.fulfill({ response })
  })
  const older = page.waitForResponse(r => r.url().includes('/analysis/order-report?') && r.url().includes('TENDER'))
  await page.goto('/#/analysis/overview?orderType=TENDER')
  await started
  const newer = page.waitForResponse(r => r.url().includes('/analysis/order-report?') && r.url().includes('PERFORMANCE'))
  await page.evaluate(() => { location.hash = '/analysis/overview?orderType=PERFORMANCE' })
  await newer
  await expect(page.getByTestId('analysis-summary')).toContainText('履约订单分析')
  release!()
  await older
  await expect(page.getByTestId('analysis-summary')).toContainText('履约订单分析')
})

test('AI 提案经用户确认才发执行请求（模型与写接口受控模拟）', async ({ page }) => {
  await login(page)
  let confirmed = false
  let proposed = false
  const proposal = { proposalId: 991, proposalNo: 'P-TEST', conversationId: 881, action: 'UPDATE', actionName: '修改',
    targetType: 'ORG', targetTypeName: '机构', targetId: 1, targetName: '浏览器回归样本', dangerous: false,
    summary: '仅用于验证确认流程，不更改真实数据', status: 'PENDING', expiresAt: new Date(Date.now() + 600000).toISOString(),
    toolName: 'proposeUpdateOrg', changes: [], warnings: [], userText: '修改测试机构名称', impact: [] }
  await page.route('**/api/ai/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/ai/chat') {
      proposed = true
      const events = [['meta', { conversationId: 881 }], ['delta', { content: '请核对变更后确认。' }],
        ['proposal', proposal], ['done', { conversationId: 881, messageId: 882 }]]
      await route.fulfill({ contentType: 'text/event-stream', body: events.map(([event, data]) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`).join('') })
    } else if (path.endsWith('/991/confirm')) {
      confirmed = true
      await route.fulfill({ json: { code: 0, data: { ...proposal, status: 'EXECUTED' } } })
    } else await route.fulfill({ json: { code: 0, data: path === '/api/ai/proposals' && proposed && !confirmed ? [proposal] : [] } })
  })
  await page.locator('.ai-copilot__fab').click()
  await page.getByPlaceholder('输入问题，Enter 发送，Shift+Enter 换行').fill('修改测试机构名称')
  await page.getByPlaceholder('输入问题，Enter 发送，Shift+Enter 换行').press('Enter')
  await expect(page.locator('.proposal-card')).toBeVisible()
  expect(confirmed).toBe(false)
  await page.getByRole('button', { name: '确认执行', exact: true }).click()
  await expect.poll(() => confirmed).toBe(true)
  await expect(page.getByText('变更已执行', { exact: true })).toBeVisible()
})
