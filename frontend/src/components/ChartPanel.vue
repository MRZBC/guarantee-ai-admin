<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { echarts, type EChartsInstance, type EChartsOption } from '@/utils/echarts'

const props = withDefaults(
  defineProps<{
    /** ECharts option */
    option: EChartsOption
    /** 容器高度，数字按 px 处理 */
    height?: number | string
    /** 是否显示加载遮罩 */
    loading?: boolean
    /** 空数据提示（由父组件控制是否处于空态） */
    empty?: boolean
  }>(),
  {
    height: 320,
    loading: false,
    empty: false
  }
)

const containerRef = ref<HTMLDivElement | null>(null)
// 用 shallowRef 保存实例，避免 Vue 对 ECharts 内部结构做响应式代理
const chart = shallowRef<EChartsInstance | null>(null)
let observer: ResizeObserver | null = null
let resizeTimer: number | null = null

const resolvedHeight = () =>
  typeof props.height === 'number' ? `${props.height}px` : props.height

function applyOption(option: EChartsOption, notMerge = false): void {
  if (!chart.value) return
  chart.value.setOption(option, notMerge)
}

function scheduleResize(): void {
  if (resizeTimer !== null) window.clearTimeout(resizeTimer)
  resizeTimer = window.setTimeout(() => {
    chart.value?.resize()
  }, 100)
}

function handleWindowResize(): void {
  scheduleResize()
}

onMounted(() => {
  if (!containerRef.value) return
  chart.value = echarts.init(containerRef.value)
  applyOption(props.option, true)
  window.addEventListener('resize', handleWindowResize)
  // ResizeObserver 同时覆盖侧边栏折叠等容器尺寸变化
  if (typeof ResizeObserver !== 'undefined' && containerRef.value) {
    observer = new ResizeObserver(() => scheduleResize())
    observer.observe(containerRef.value)
  }
})

watch(
  () => props.option,
  (option) => {
    applyOption(option, true)
    scheduleResize()
  },
  { deep: true }
)

watch(
  () => props.height,
  () => scheduleResize()
)

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleWindowResize)
  if (resizeTimer !== null) window.clearTimeout(resizeTimer)
  observer?.disconnect()
  observer = null
  chart.value?.dispose()
  chart.value = null
})

defineExpose({
  /** 手动获取底层实例（很少需要） */
  getInstance: () => chart.value,
  resize: () => chart.value?.resize()
})
</script>

<template>
  <div v-loading="props.loading" class="chart-panel" :style="{ height: resolvedHeight() }">
    <div v-show="!props.empty" ref="containerRef" class="chart-panel__canvas" />
    <div v-if="props.empty && !props.loading" class="chart-panel__empty">
      <el-empty description="暂无数据" :image-size="72" />
    </div>
  </div>
</template>

<style scoped>
.chart-panel {
  width: 100%;
  position: relative;
}

.chart-panel__canvas {
  width: 100%;
  height: 100%;
}

.chart-panel__empty {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>
