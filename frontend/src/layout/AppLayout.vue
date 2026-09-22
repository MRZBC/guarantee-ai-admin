<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useAppStore } from '@/stores/app'
import { useUserStore } from '@/stores/user'
import AiCopilot from '@/components/AiCopilot.vue'

const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const userStore = useUserStore()

interface MenuItem {
  path: string
  title: string
  icon: string
}

const menuGroups: { title: string; icon: string; children: MenuItem[] }[] = [
  {
    title: '订单管理',
    icon: 'Tickets',
    children: [
      { path: '/orders/tender', title: '投标订单', icon: 'Document' },
      { path: '/orders/performance', title: '履约订单', icon: 'DocumentChecked' }
    ]
  },
  {
    title: '系统配置',
    icon: 'Setting',
    children: [
      { path: '/system/insurance-types', title: '险种配置', icon: 'Files' },
      { path: '/system/orgs', title: '机构配置', icon: 'OfficeBuilding' },
      { path: '/system/departments', title: '部门配置', icon: 'Grid' },
      { path: '/system/users', title: '用户配置', icon: 'User' },
      { path: '/system/roles', title: '角色配置', icon: 'Key' }
    ]
  }
]

/** 当前激活菜单项，直接使用路由 path */
const activeMenu = computed(() => route.path)

const breadcrumbs = computed(() => {
  const items: string[] = ['首页']
  const parentTitle = route.meta.parentTitle as string | undefined
  const title = route.meta.title as string | undefined
  if (parentTitle) items.push(parentTitle)
  if (title && title !== '首页') items.push(title)
  return items
})

async function handleLogout(): Promise<void> {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  userStore.logout()
  await router.push('/login')
}

function handleUserCommand(command: string): void {
  if (command === 'logout') void handleLogout()
}
</script>

<template>
  <el-container class="app-layout">
    <el-aside class="app-aside" :width="appStore.sidebarCollapsed ? '64px' : '220px'">
      <div class="app-logo">
        <el-icon class="app-logo__icon"><Shield /></el-icon>
        <span v-show="!appStore.sidebarCollapsed" class="app-logo__text">担保业务管理平台</span>
      </div>
      <el-scrollbar class="app-menu-scroll">
        <el-menu
          :default-active="activeMenu"
          :collapse="appStore.sidebarCollapsed"
          :collapse-transition="false"
          background-color="#1f2d3d"
          text-color="#c0c4cc"
          active-text-color="#ffffff"
          router
          unique-opened
        >
          <el-menu-item index="/dashboard">
            <el-icon><HomeFilled /></el-icon>
            <template #title>首页</template>
          </el-menu-item>

          <el-sub-menu v-for="group in menuGroups" :key="group.title" :index="group.title">
            <template #title>
              <el-icon><component :is="group.icon" /></el-icon>
              <span>{{ group.title }}</span>
            </template>
            <el-menu-item v-for="item in group.children" :key="item.path" :index="item.path">
              <el-icon><component :is="item.icon" /></el-icon>
              <template #title>{{ item.title }}</template>
            </el-menu-item>
          </el-sub-menu>

          <el-menu-item index="/analysis/overview">
            <el-icon><TrendCharts /></el-icon>
            <template #title>数据概览</template>
          </el-menu-item>
          <el-menu-item index="/projects">
            <el-icon><Folder /></el-icon>
            <template #title>项目管理</template>
          </el-menu-item>
          <el-menu-item index="/enterprises">
            <el-icon><OfficeBuilding /></el-icon>
            <template #title>企业管理</template>
          </el-menu-item>
        </el-menu>
      </el-scrollbar>
    </el-aside>

    <el-container class="app-main-container">
      <el-header class="app-header">
        <div class="app-header__left">
          <el-icon class="collapse-toggle" @click="appStore.toggleSidebar()">
            <component :is="appStore.sidebarCollapsed ? 'Expand' : 'Fold'" />
          </el-icon>
          <el-breadcrumb separator="/">
            <el-breadcrumb-item v-for="(item, index) in breadcrumbs" :key="`${item}-${index}`">
              {{ item }}
            </el-breadcrumb-item>
          </el-breadcrumb>
        </div>

        <div class="app-header__right">
          <el-tag v-for="role in userStore.roles" :key="role" size="small" type="info" effect="plain">
            {{ role }}
          </el-tag>
          <el-dropdown trigger="click" @command="handleUserCommand">
            <span class="user-trigger">
              <el-avatar :size="28" class="user-trigger__avatar">
                {{ userStore.realName.slice(0, 1) }}
              </el-avatar>
              <span class="user-trigger__name">{{ userStore.realName }}</span>
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item disabled>
                  {{ userStore.user?.deptName || '未分配部门' }}
                </el-dropdown-item>
                <el-dropdown-item divided command="logout">
                  <el-icon><SwitchButton /></el-icon>
                  退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="app-content">
        <router-view v-slot="{ Component }">
          <keep-alive :max="6">
            <component :is="Component" />
          </keep-alive>
        </router-view>
      </el-main>
    </el-container>

    <!-- AI 业务分析助手：全局挂载，所有页面可用 -->
    <AiCopilot />
  </el-container>
</template>

<style scoped>
.app-layout {
  height: 100vh;
}

.app-aside {
  background-color: #1f2d3d;
  transition: width 0.25s ease;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.app-logo {
  height: var(--app-header-height);
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 18px;
  color: #fff;
  font-weight: 600;
  font-size: 15px;
  white-space: nowrap;
  background-color: #172432;
}

.app-logo__icon {
  font-size: 20px;
  color: #409eff;
  flex-shrink: 0;
}

.app-menu-scroll {
  flex: 1;
  overflow-x: hidden;
}

.app-aside :deep(.el-menu) {
  border-right: none;
}

.app-main-container {
  height: 100vh;
  overflow: hidden;
}

.app-header {
  height: var(--app-header-height);
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  padding: 0 16px;
}

.app-header__left {
  display: flex;
  align-items: center;
  gap: 14px;
}

.collapse-toggle {
  font-size: 20px;
  cursor: pointer;
  color: #606266;
}

.collapse-toggle:hover {
  color: #409eff;
}

.app-header__right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.user-trigger {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  padding: 4px 6px;
  border-radius: 4px;
  outline: none;
}

.user-trigger:hover {
  background: #f5f7fa;
}

.user-trigger__avatar {
  background: #409eff;
  color: #fff;
  font-size: 13px;
}

.user-trigger__name {
  font-size: 14px;
  color: #303133;
}

.app-content {
  padding: 0;
  background: #f0f2f5;
  overflow-y: auto;
}
</style>
