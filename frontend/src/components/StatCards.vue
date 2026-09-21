<script setup lang="ts">
export interface StatCardItem {
  label: string
  value: string | number
  unit?: string
  color?: string
}

withDefaults(
  defineProps<{
    items: StatCardItem[]
    /** 每行列数（响应式栅格） */
    span?: number
  }>(),
  { span: 6 }
)
</script>

<template>
  <el-row :gutter="12">
    <el-col v-for="item in items" :key="item.label" :xs="24" :sm="12" :md="span" :lg="span">
      <el-card class="stat-card" shadow="hover">
        <div class="stat-card__label">{{ item.label }}</div>
        <div class="stat-card__value" :style="item.color ? { color: item.color } : undefined">
          {{ item.value }}
          <span v-if="item.unit" class="stat-card__unit">{{ item.unit }}</span>
        </div>
      </el-card>
    </el-col>
  </el-row>
</template>

<style scoped>
.stat-card {
  margin-bottom: 12px;
}
</style>
