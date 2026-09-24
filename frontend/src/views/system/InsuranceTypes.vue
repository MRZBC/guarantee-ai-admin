<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  changeInsuranceTypeStatus,
  createInsuranceType,
  deleteInsuranceType,
  pageInsuranceTypes,
  updateInsuranceType
} from '@/api/system'
import { useUserStore } from '@/stores/user'
import { confirmText } from '@/utils/confirmText'
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

const userStore = useUserStore()

const query = reactive<InsuranceTypeQuery>({
  pageNum: 1,
  pageSize: 10,
  typeName: '',
  category: '',
  status: null
})

/** 无 system:insurance:disable 时不渲染启停入口（后端仍是安全边界，SYS-NF-04） */
const canDisable = computed(() => userStore.permissions.includes('system:insurance:disable'))
/** 无 system:insurance:delete 时不渲染删除入口（后端仍是安全边界，SYS-NF-04） */
const canDelete = computed(() => userStore.permissions.includes('system:insurance:delete'))

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

/**
 * 保额区间的口径：**不填 = 不限**。
 *
 * <p>库里两列是 `NOT NULL DEFAULT 0`，所以"不限"在存储层就是 0；界面上则显示「不限」、
 * 输入框留空。三处口径必须一起改：表单显示（0 → 空）、提交（空 → 0）、列表/详情展示（0 → 不限）。</p>
 */
const AMOUNT_UNLIMITED_TEXT = '不限（留空即可）'

/** 后端值 → 表单值：0/空 都当作"不限"，输入框显示为空 */
function toFormAmount(value: number | null | undefined): number | null {
  return value === null || value === undefined || Number(value) === 0 ? null : Number(value)
}

/** 表单值 → 提交值：留空统一送 0（UPDATE 时送 0 表示"清空为不限"，不送才是"保持原值"） */
function toSubmitAmount(value: number | null): number {
  return value === null || value === undefined || Number(value) === 0 ? 0 : Number(value)
}

/** 列表展示：0/空 → 「不限」 */
function amountText(value: number | null | undefined): string {
  return value === null || value === undefined || Number(value) === 0 ? '不限' : formatAmount(value)
}

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
  // 保额区间可留空 = 不限，因此只校验非负，不做必填
  minAmount: [{ type: 'number', min: 0, message: '最低担保金额不能为负数（留空表示不限）', trigger: 'blur' }],
  maxAmount: [{ type: 'number', min: 0, message: '最高担保金额不能为负数（留空表示不限）', trigger: 'blur' }],
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
      status: statusParam(query.status),
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
  form.minAmount = toFormAmount(row.minAmount)
  form.maxAmount = toFormAmount(row.maxAmount)
  form.status = row.status ?? STATUS_ENABLED
  form.description = row.description ?? ''
  dialogVisible.value = true
}

async function handleSubmit(): Promise<void> {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  // 上限为"不限"（空/0）时不比较大小：不设上限时下限随便填
  if (
    form.minAmount !== null &&
    toSubmitAmount(form.maxAmount) !== 0 &&
    Number(form.maxAmount) < Number(form.minAmount)
  ) {
    ElMessage.error('最高担保金额不能小于最低担保金额（留空表示不限）')
    return
  }

  submitting.value = true
  try {
    if (isEdit.value && form.id !== null) {
      const payload: InsuranceTypeUpdateParams = {
        typeName: form.typeName,
        category: form.category,
        baseRate: form.baseRate,
        minAmount: toSubmitAmount(form.minAmount),
        maxAmount: toSubmitAmount(form.maxAmount),
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
        minAmount: toSubmitAmount(form.minAmount),
        maxAmount: toSubmitAmount(form.maxAmount),
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

/* ---------------- 启停（权限：system:insurance:disable） ---------------- */

/**
 * 启停险种。
 *
 * <p>停用**不校验订单引用**：后端会正常改状态，历史订单因此不受影响。
 * 删除同样不校验订单引用（口径已调整：删除只退出配置列表，历史订单仍显示名称、仍可筛选）。
 * 所以这里不能写"被引用会被拒绝"这类后端做不到的承诺。</p>
 */
async function toggleStatus(row: InsuranceTypeItem): Promise<void> {
  const next = isEnabled(row.status) ? 0 : 1
  const word = next === 0 ? '停用' : '启用'
  try {
    await ElMessageBox.confirm(
      next === 0
        ? confirmText(`确认停用「${row.typeName}」？`, [
            '停用后该险种不再出现在新订单的可选列表中，历史订单不受影响',
            '停用不是删除，记录仍然保留，可随时重新启用'
          ])
        : `确认启用「${row.typeName}」？`,
      `${word}险种`,
      { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await changeInsuranceTypeStatus(row.id, next)
    ElMessage.success(`已${word}「${row.typeName}」`)
    await loadData()
  } catch {
    // 失败原因（如「险种已处于目标状态，无需变更」）已由响应拦截器统一提示
  }
}

/* ---------------- 逻辑删除 / 恢复（权限：system:insurance:delete） ---------------- */

async function handleDelete(row: InsuranceTypeItem): Promise<void> {
  try {
    await ElMessageBox.confirm(
      confirmText(`确认删除险种「${row.typeName}」？`, [
        '删除后该险种不再出现在默认列表中，且页面不提供恢复入口——如只是暂停业务，请改用「停用」',
        '删除不影响历史订单：订单里仍显示该险种名称，也仍能按它筛选',
        '停用可随时启用，删除不可'
      ]),
      '删除险种',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await deleteInsuranceType(row.id)
    ElMessage.success(`已删除险种「${row.typeName}」`)
    await loadData()
  } catch {
    // 失败原因（如「该险种不能删除：存在 3 个启用中的产品」）已由响应拦截器统一提示
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

      <el-table
        v-loading="loading"
        :data="rows"
        border
        stripe
        height="520"
        row-key="id"
      >
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
          <template #default="{ row }">{{ amountText(row.minAmount) }}</template>
        </el-table-column>
        <el-table-column prop="maxAmount" label="最高担保金额(元)" width="170" align="right">
          <template #default="{ row }">{{ amountText(row.maxAmount) }}</template>
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
        <el-table-column label="操作" :width="canDelete ? 190 : 135" align="center" fixed="right">
          <template #default="{ row }">
            <!-- 无权限不渲染按钮（前端过滤仅为体验优化，后端仍是安全边界，SYS-NF-04）。
                 已删除险种不进列表，因此这里只有编辑、启停与删除；
                 恢复入口已随「显示已删除」开关撤除（2026-09-22 评审决定） -->
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="canDisable" link type="primary" @click="toggleStatus(row)">
              {{ isEnabled(row.status) ? '停用' : '启用' }}
            </el-button>
            <el-button v-if="canDelete" link type="danger" @click="handleDelete(row)">
              删除
            </el-button>
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
          <!-- 留空 = 不限（不设下限）：提交时统一转成 0，后端 0/空同义 -->
          <el-input-number
            v-model="form.minAmount"
            :min="0"
            :precision="2"
            :step="10000"
            :placeholder="AMOUNT_UNLIMITED_TEXT"
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
            :placeholder="AMOUNT_UNLIMITED_TEXT"
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
