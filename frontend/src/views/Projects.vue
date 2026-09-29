<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { pageEnterprises } from '@/api/enterprise'
import { getProject, pageProjects } from '@/api/project'
import RegionSelect from '@/components/RegionSelect.vue'
import { MIN_KEYWORD_LEN, useRemoteSearch } from '@/composables/useRemoteSearch'
import { formatAmount, formatDate } from '@/utils/format'
import type { EnterpriseItem } from '@/types/enterprise'
import type { ProjectDetail, ProjectItem, ProjectQuery } from '@/types/project'

const loading = ref(false)
const rows = ref<ProjectItem[]>([])
const total = ref(0)

const query = reactive<ProjectQuery>({
  pageNum: 1,
  pageSize: 10,
  projectId: null,
  regionCode: '',
  projectType: '',
  status: '',
  enterpriseId: null
})

/**
 * 项目筛选：与投标订单页同款的**下拉 + 服务端模糊搜索**。
 *
 * <p>此前这里是 `el-input` 项目名称（按名称 LIKE 模糊匹配一批）。改成下拉后语义变成
 * "选中某一条项目"，因此提交的是 `projectId` 而不是名称——后端为此新增了主键过滤
 * （`ProjectQuery.projectId`）。名称模糊匹配仍保留，用于这里的候选搜索本身。</p>
 *
 * <p>规则（≥2 字、300ms 防抖、选中项留在候选里）由 {@link useRemoteSearch} 统一实现。</p>
 */
const projectSearch = useRemoteSearch<ProjectItem>({
  fetch: async (keyword, pageSize) => {
    const page = await pageProjects({ pageNum: 1, pageSize, projectName: keyword })
    return page?.list ?? []
  },
  labelOf: (item) => `${item.projectName}（${item.projectCode}）`,
  minLengthText: `请输入至少 ${MIN_KEYWORD_LEN} 个字`,
  emptyText: '无匹配项目'
})

/**
 * 企业筛选：与企业页/订单页一致地按**名称**远程模糊搜索。
 *
 * <p>此前这里是手填「企业ID」的 `el-input-number`——要用户先知道企业主键才能筛，
 * 实际上等于筛不了。后端 `ProjectQuery` 只接受 `enterpriseId`，因此与企业/订单页
 * 同一套做法：前端远程搜出企业、拿到 id 再提交，**不需要后端改动**。</p>
 *
 * <p>规则（≥2 字、300ms 防抖、选中项留在候选里）由 {@link useRemoteSearch} 统一实现。</p>
 */
const enterpriseSearch = useRemoteSearch<EnterpriseItem>({
  fetch: async (keyword, pageSize) => {
    const page = await pageEnterprises({ pageNum: 1, pageSize, entName: keyword })
    return page?.list ?? []
  },
  labelOf: (item) => `${item.entName}（${item.entCode}）`,
  minLengthText: `请输入至少 ${MIN_KEYWORD_LEN} 个字`,
  emptyText: '无匹配企业'
})

/**
 * 项目类型取值：`project.project_type` 存的是**中文**（房建/市政/交通/水利/其他），
 * 见 schema.sql 建表注释与 DataInitializer.PROJECT_TYPES。
 *
 * <p>后端 `ProjectMapper` 对该字段是**精确等值**匹配（`AND p.project_type = #{q.projectType}`），
 * 所以下拉的 value 必须与库里一致。此前这里写的是 TENDER/CONSTRUCTION/GOVERNMENT 这套
 * 自造英文枚举，既选不中任何数据（筛任意类型都是 0 条），列上也认不出来。</p>
 */
const projectTypeOptions = ['房建', '市政', '交通', '水利', '其他']

/**
 * 项目状态字典：`project.status` 是 VARCHAR(16) 枚举
 * **BIDDING / AWARDED / BUILDING / FINISHED**（schema.sql 建表注释、ProjectVO、DataInitializer 三处一致）。
 *
 * <p>此前这里写的是一套自造的 DRAFT/PENDING/IN_PROGRESS/COMPLETED，与库里对不上；
 * `labelOf()` 查不到就原样回显，页面上于是显示英文 `AWARDED` / `BIDDING`。
 * 状态筛选下拉同源，一起被带错（选"已完成"这类值永远筛不到数据）。</p>
 */
const statusMap: Record<string, string> = {
  BIDDING: '招标中',
  AWARDED: '已中标',
  BUILDING: '建设中',
  FINISHED: '已完成'
}

function labelOf(map: Record<string, string>, value: string | null | undefined): string {
  if (!value) return '--'
  return map[value.toUpperCase()] ?? value
}

/**
 * 状态标签配色：在途状态给彩色（招标中=警告、已中标=主题、建设中=成功），
 * 终态「已完成」用灰色归档；未知值同样退 info，不抛错（配色不参与判断口径）。
 */
function statusTagType(
  status: string | null | undefined
): 'primary' | 'success' | 'warning' | 'danger' | 'info' {
  const code = (status ?? '').toUpperCase()
  if (code === 'BIDDING') return 'warning'
  if (code === 'AWARDED') return 'primary'
  if (code === 'BUILDING') return 'success'
  if (code === 'FINISHED') return 'info'
  return 'info'
}

async function loadData(): Promise<void> {
  loading.value = true
  try {
    const result = await pageProjects({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      projectId: query.projectId ?? undefined,
      regionCode: query.regionCode || undefined,
      projectType: query.projectType || undefined,
      status: query.status || undefined,
      enterpriseId: query.enterpriseId ?? undefined
    })
    rows.value = result?.list ?? []
    total.value = result?.total ?? 0
  } catch {
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function handleSearch(): void {
  query.pageNum = 1
  void loadData()
}

function handleReset(): void {
  query.projectId = null
  query.regionCode = ''
  query.projectType = ''
  query.status = ''
  query.enterpriseId = null
  // 搜索型筛选项要把"已选 + 关键词 + 候选"一起复位，否则会留下上一家企业/项目的标签
  projectSearch.reset()
  enterpriseSearch.reset()
  query.pageNum = 1
  void loadData()
}

function handlePageChange(page: number): void {
  query.pageNum = page
  void loadData()
}

function handleSizeChange(size: number): void {
  query.pageSize = size
  query.pageNum = 1
  void loadData()
}

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<ProjectDetail | ProjectItem | null>(null)

async function openDetail(row: ProjectItem): Promise<void> {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = row
  try {
    detail.value = await getProject(row.id)
  } catch {
    // 保留列表数据
  } finally {
    detailLoading.value = false
  }
}

function asDetail(value: ProjectDetail | ProjectItem | null): ProjectDetail | null {
  return value && 'totalGuaranteeAmount' in value ? (value as ProjectDetail) : null
}

/** 详情接口返回后才有统计字段 */
const detailStats = computed(() => asDetail(detail.value))

onMounted(loadData)
</script>

<template>
  <div class="page-container">
    <el-card class="filter-card" shadow="never">
      <el-form :model="query" label-width="82px" @submit.prevent>
        <el-row :gutter="12">
          <el-col :xs="24" :sm="12" :md="8" :lg="6">
            <el-form-item label="项目名称">
              <!--
                与投标订单页同款：下拉 + 服务端模糊搜索（≥2 个字才查、300ms 防抖、最多 20 条）。
                候选项带项目编码，便于区分"浙江省水利工程项目0681"这类重名。
              -->
              <el-select
                v-model="query.projectId"
                placeholder="项目名称（至少 2 个字）"
                clearable
                filterable
                :filter-method="projectSearch.filterMethod"
                :loading="projectSearch.searching"
                :no-data-text="projectSearch.noDataText"
                @change="projectSearch.handleChange"
              >
                <el-option
                  v-for="item in projectSearch.options.value"
                  :key="item.id"
                  :label="projectSearch.labelOf(item)"
                  :value="item.id"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8" :lg="6">
            <el-form-item label="地区">
              <!-- 地区下拉（行政区划字典）：替代原先手填"区域编码" -->
              <RegionSelect v-model="query.regionCode" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8" :lg="6">
            <el-form-item label="项目类型">
              <el-select v-model="query.projectType" placeholder="全部类型" clearable>
                <!-- value 直接就是库里的中文取值：后端是等值匹配，不接受编码转换 -->
                <el-option v-for="type in projectTypeOptions" :key="type" :label="type" :value="type" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8" :lg="6">
            <el-form-item label="状态">
              <el-select v-model="query.status" placeholder="全部状态" clearable>
                <el-option
                  v-for="(label, value) in statusMap"
                  :key="value"
                  :label="label"
                  :value="value"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8" :lg="6">
            <el-form-item label="企业">
              <!--
                企业有数千条，不做全量下拉：远程模糊搜索，≥2 个字才查，300ms 防抖。
                候选项带企业编码，便于区分重名（与企业页、订单页同一套规则）。
              -->
              <el-select
                v-model="query.enterpriseId"
                placeholder="企业名称（至少 2 个字）"
                clearable
                filterable
                :filter-method="enterpriseSearch.filterMethod"
                :loading="enterpriseSearch.searching"
                :no-data-text="enterpriseSearch.noDataText"
                @change="enterpriseSearch.handleChange"
              >
                <el-option
                  v-for="item in enterpriseSearch.options.value"
                  :key="item.id"
                  :label="enterpriseSearch.labelOf(item)"
                  :value="item.id"
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
        <span class="table-toolbar__title">项目列表 · 共 {{ total }} 条</span>
      </div>

      <el-table v-loading="loading" :data="rows" border stripe height="520" row-key="id">
        <el-table-column type="index" label="#" width="52" align="center" fixed />
        <el-table-column prop="projectCode" label="项目编号" width="160" fixed show-overflow-tooltip />
        <el-table-column prop="projectName" label="项目名称" min-width="220" show-overflow-tooltip />
        <el-table-column prop="enterpriseName" label="所属企业" min-width="180" show-overflow-tooltip />
        <el-table-column prop="regionName" label="区域" width="130" show-overflow-tooltip />
        <el-table-column prop="projectAmount" label="项目金额(元)" width="150" align="right">
          <template #default="{ row }">{{ formatAmount(row.projectAmount) }}</template>
        </el-table-column>
        <el-table-column prop="projectType" label="项目类型" width="120" align="center">
          <template #default="{ row }">{{ row.projectType || '--' }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">
              {{ labelOf(statusMap, row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="tenderDate" label="招标日期" width="120" align="center">
          <template #default="{ row }">{{ formatDate(row.tenderDate) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无项目数据" :image-size="80" />
        </template>
      </el-table>

      <div class="pagination-wrapper">
        <el-pagination
          :current-page="query.pageNum"
          :page-size="query.pageSize"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>
    </el-card>

    <el-dialog v-model="detailVisible" title="项目详情" width="820px">
      <div v-loading="detailLoading">
        <el-descriptions v-if="detail" :column="2" border>
          <el-descriptions-item label="项目编号">{{ detail.projectCode || '--' }}</el-descriptions-item>
          <el-descriptions-item label="项目名称">{{ detail.projectName || '--' }}</el-descriptions-item>
          <el-descriptions-item label="所属企业">
            {{ detail.enterpriseName || '--' }}
          </el-descriptions-item>
          <el-descriptions-item label="区域">
            {{ detail.regionName || '--' }}
            <span v-if="detail.regionCode" class="text-muted">({{ detail.regionCode }})</span>
          </el-descriptions-item>
          <el-descriptions-item label="项目金额">
            {{ formatAmount(detail.projectAmount) }} 元
          </el-descriptions-item>
          <el-descriptions-item label="项目类型">
            {{ detail.projectType || '--' }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusTagType(detail.status)" size="small">
              {{ labelOf(statusMap, detail.status) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="招标日期">
            {{ formatDate(detail.tenderDate) }}
          </el-descriptions-item>
          <template v-if="detailStats">
            <el-descriptions-item label="投标订单数">
              {{ detailStats.tenderOrderCount }} 笔
            </el-descriptions-item>
            <el-descriptions-item label="履约订单数">
              {{ detailStats.performanceOrderCount }} 笔
            </el-descriptions-item>
            <el-descriptions-item label="担保总金额">
              {{ formatAmount(detailStats.totalGuaranteeAmount) }} 元
            </el-descriptions-item>
            <el-descriptions-item label="保费总额">
              {{ formatAmount(detailStats.totalPremiumAmount) }} 元
            </el-descriptions-item>
          </template>
        </el-descriptions>
      </div>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.table-toolbar__title {
  font-weight: 600;
  color: #303133;
}
</style>
