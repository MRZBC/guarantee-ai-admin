<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import {
  createInsuranceType,
  pageInsuranceTypes,
  updateInsuranceType
} from '@/api/system'
import { formatAmount, formatDateTime, formatPercent } from '@/utils/format'
import {
  dictLabel,
  isEnabled,
  statusLabel,
  statusParam,
  STATUS_DISABLED,
  STATUS_ENABLED,
  STATUS_OPTIONS
} from '@/utils/status'
import type {
  InsuranceTypeCreateParams,
  InsuranceTypeItem,
  InsuranceTypeQuery,
  InsuranceTypeUpdateParams
} from '@/types/system'

const loading = ref(false)
const rows = ref<InsuranceTypeItem[]>([])
const total = ref(0)

const query = reactive<InsuranceTypeQuery>({
  pageNum: 1,
  pageSize: 10,
  typeName: '',
  category: '',
  status: null
})

const categoryMap: Record<string, string> = {
  TENDER: '投标担保',
  PERFORMANCE: '履约担保',
  BID: '投标担保',
  CONTRACT: '合同履约',
  QUALITY: '质量保证',
  ADVANCE: '预付款担保',
  OWNER: '业主支付',
  OTHER: '其他'
}

function categoryLabel(row: InsuranceTypeItem): string {
  if (row.categoryName) return row.categoryName
  return dictLabel(categoryMap, row.category)
}

type CategoryValue = 'TENDER' | 'PERFORMANCE' | 'QUALITY' | 'ADVANCE' | 'OTHER'

const form = reactive<{
  id: number | null
  typeCode: string
  typeName: string
  category: CategoryValue
  baseRate: number | null
  minAmount: number | null
  maxAmount: number | null
  /** 后端 TINYINT：1 启用 / 0 停用 */
  status: number
  description: string
}>({
  id: null,
  typeCode: '',
  typeName: '',
  category: 'TENDER',
  baseRate: null,
  minAmount: null,
  maxAmount: null,
  status: STATUS_ENABLED,
  description: ''
})

const dialogVisible = ref(false)
const submitting = ref(false)
const formRef = ref<FormInstance>()

const isEdit = computed(() => form.id !== null)
const dialogTitle = computed(() => (isEdit.value ? '编辑险种' : '新增险种'))

const rules = computed<FormRules>(() => ({
  typeCode: [
    { required: true, message: '请输入险种编码', trigger: 'blur' },
    {
      pattern: /^[A-Za-z0-9_-]{2,32}$/,
      message: '险种编码由 2-32 位字母、数字、下划线或中划线组成',
      trigger: 'blur'
    }
  ],
  typeName: [{ required: true, message: '请输入险种名称', trigger: 'blur' }],
  category: [{ required: true, message: '请选择险种类别', trigger: 'change' }],
  baseRate: [
    {
      validator: (_rule, value, callback) => {
        if (value === null || value === undefined || value === '') {
          callback(new Error('请输入基础费率(%)'))
          return
        }
        if (Number(value) < 0 || Number(value) > 100) {
          callback(new Error('基础费率需在 0-100 之间'))
          return
        }
        callback()
      },
      trigger: 'blur'
    }
  ],
  minAmount: [{ required: true, message: '请输入最低担保金额(元)', trigger: 'blur' }],
  status: [{ required: true, message: '请选择状态', trigger: 'change' }]
}))

async function loadData(): Promise<void> {
  loading.value = true
  try {
    const result = await pageInsuranceTypes({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      typeName: query.typeName || undefined,
      category: query.category || undefined,
      status: statusParam(query.status)
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
  query.typeName = ''
  query.category = ''
  query.status = null
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

function resetForm(): void {
  form.id = null
  form.typeCode = ''
  form.typeName = ''
  form.category = 'TENDER'
  // 后端以百分数存储 baseRate（如 0.5 表示 0.5%），表单展示用的就是百分数值
  form.baseRate = null
  form.minAmount = null
  form.maxAmount = null
  form.status = STATUS_ENABLED
  form.description = ''
  formRef.value?.clearValidate()
}

function openCreate(): void {
  resetForm()
  dialogVisible.value = true
}

function openEdit(row: InsuranceTypeItem): void {
  resetForm()
  form.id = row.id
  form.typeCode = row.typeCode
  form.typeName = row.typeName
  form.category = (row.category?.toUpperCase() as CategoryValue) ?? 'TENDER'
  form.baseRate = row.baseRate ?? null
  form.minAmount = row.minAmount ?? null
  form.maxAmount = row.maxAmount ?? null
  form.status = row.status ?? STATUS_ENABLED
  form.description = row.description ?? ''
  dialogVisible.value = true
}

async function handleSubmit(): Promise<void> {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  if (
    form.minAmount !== null &&
    form.maxAmount !== null &&
    Number(form.maxAmount) < Number(form.minAmount)
  ) {
    ElMessage.error('最高担保金额不能小于最低担保金额')
    return
  }

  submitting.value = true
  try {
    if (isEdit.value && form.id !== null) {
      const payload: InsuranceTypeUpdateParams = {
        typeName: form.typeName,
        category: form.category,
        baseRate: form.baseRate,
        minAmount: form.minAmount,
        maxAmount: form.maxAmount,
        status: form.status,
        description: form.description
      }
      await updateInsuranceType(form.id, payload)
      ElMessage.success('险种更新成功')
    } else {
      const payload: InsuranceTypeCreateParams = {
        typeCode: form.typeCode,
        typeName: form.typeName,
        category: form.category,
        baseRate: form.baseRate,
        minAmount: form.minAmount,
        maxAmount: form.maxAmount,
        description: form.description
      }
      await createInsuranceType(payload)
      ElMessage.success('险种新增成功')
    }
    dialogVisible.value = false
    await loadData()
  } catch {
    // 拦截器已提示
  } finally {
    submitting.value = false
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
            <el-form-item label="险种名称">
              <el-input
                v-model="query.typeName"
                placeholder="请输入险种名称"
                clearable
                @keyup.enter="handleSearch"
              />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8" :lg="6">
            <el-form-item label="险种类别">
              <el-select v-model="query.category" placeholder="全部类别" clearable>
                <el-option
                  v-for="(label, value) in categoryMap"
                  :key="value"
                  :label="label"
                  :value="value"
                />
              </el-select>
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
        <span class="table-toolbar__title">险种列表 · 共 {{ total }} 条</span>
        <el-button type="primary" icon="Plus" @click="openCreate">新增险种</el-button>
      </div>

      <el-table v-loading="loading" :data="rows" border stripe height="520" row-key="id">
        <el-table-column type="index" label="#" width="52" align="center" fixed />
        <el-table-column prop="typeCode" label="险种编码" width="150" fixed show-overflow-tooltip />
        <el-table-column prop="typeName" label="险种名称" min-width="180" show-overflow-tooltip />
        <el-table-column prop="category" label="险种类别" width="130" align="center">
          <template #default="{ row }">{{ categoryLabel(row) }}</template>
        </el-table-column>
        <el-table-column prop="baseRate" label="基础费率(%)" width="130" align="right">
          <template #default="{ row }">{{ formatPercent(row.baseRate) }}</template>
        </el-table-column>
        <el-table-column prop="minAmount" label="最低担保金额(元)" width="170" align="right">
          <template #default="{ row }">{{ formatAmount(row.minAmount) }}</template>
        </el-table-column>
        <el-table-column prop="maxAmount" label="最高担保金额(元)" width="170" align="right">
          <template #default="{ row }">{{ formatAmount(row.maxAmount) }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="isEnabled(row.status) ? 'success' : 'info'" size="small">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="说明" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.description || '--' }}</template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="170" align="center">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column prop="updatedAt" label="更新时间" width="170" align="center">
          <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无险种数据" :image-size="80" />
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

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="640px" @closed="resetForm">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="130px">
        <el-form-item label="险种编码" prop="typeCode">
          <el-input
            v-model="form.typeCode"
            placeholder="如 TENDER_BOND"
            :disabled="isEdit"
            clearable
          />
          <div v-if="isEdit" class="form-hint text-muted">险种编码创建后不可修改</div>
        </el-form-item>
        <el-form-item label="险种名称" prop="typeName">
          <el-input v-model="form.typeName" placeholder="如 投标保证金保函" clearable />
        </el-form-item>
        <el-form-item label="险种类别" prop="category">
          <el-select v-model="form.category" placeholder="请选择险种类别" style="width: 100%">
            <el-option label="投标担保" value="TENDER" />
            <el-option label="履约担保" value="PERFORMANCE" />
            <el-option label="质量保证" value="QUALITY" />
            <el-option label="预付款担保" value="ADVANCE" />
            <el-option label="其他" value="OTHER" />
          </el-select>
        </el-form-item>
        <el-form-item label="基础费率(%)" prop="baseRate">
          <el-input-number
            v-model="form.baseRate"
            :min="0"
            :max="100"
            :precision="4"
            :step="0.01"
            controls-position="right"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="最低担保金额(元)" prop="minAmount">
          <el-input-number
            v-model="form.minAmount"
            :min="0"
            :precision="2"
            :step="10000"
            controls-position="right"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="最高担保金额(元)" prop="maxAmount">
          <el-input-number
            v-model="form.maxAmount"
            :min="0"
            :precision="2"
            :step="10000"
            controls-position="right"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item v-if="isEdit" label="状态" prop="status">
          <el-radio-group v-model="form.status">
            <el-radio :value="STATUS_ENABLED">启用</el-radio>
            <el-radio :value="STATUS_DISABLED">停用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="说明" prop="description">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            maxlength="255"
            show-word-limit
            placeholder="选填，险种说明"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.table-toolbar__title {
  font-weight: 600;
  color: #303133;
}

.form-hint {
  font-size: 12px;
  line-height: 1.6;
}
</style>
