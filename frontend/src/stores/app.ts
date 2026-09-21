import { ref, watch } from 'vue'
import { defineStore } from 'pinia'

const COLLAPSE_KEY = 'guarantee_admin_sidebar_collapsed'

export const useAppStore = defineStore('app', () => {
  const sidebarCollapsed = ref<boolean>(localStorage.getItem(COLLAPSE_KEY) === '1')

  function toggleSidebar(): void {
    sidebarCollapsed.value = !sidebarCollapsed.value
  }

  function setSidebarCollapsed(value: boolean): void {
    sidebarCollapsed.value = value
  }

  watch(sidebarCollapsed, (value) => {
    localStorage.setItem(COLLAPSE_KEY, value ? '1' : '0')
  })

  return { sidebarCollapsed, toggleSidebar, setSidebarCollapsed }
})
