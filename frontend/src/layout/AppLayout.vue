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
  /** 进入该页面所需的权限码，与 `router/index.ts` 的 `meta.permission` 一一对应 */
  permission: string
}

interface MenuGroup {
  title: string
  icon: string
  children: MenuItem[]
}

/** 是否持有该权限码。菜单过滤只解决"看到不该看的入口"，后端仍是安全边界（SYS-NF-04）。 */
function can(permission: string): boolean {
  return userStore.permissions.includes(permission)
}

/**
 * 菜单定义（含所需权限码）——**唯一数据源**。
 *
 * <p>原先 `menuGroups` 是硬编码常量、所有登录用户看到同一份菜单（V-4）：
 * VIEWER 能看到"角色配置"，点进去只有 403，体验像"系统坏了"。
 * 现在按 `userStore.permissions` 过滤（SYS-P-06），
 * 且**分组内全部子项被过滤掉时整组不渲染**（否则会出现点不开的空分组）。</p>
 *
 * <p>与 `router/index.ts` 的 `meta.permission` 必须同步：菜单是"看不看得见"，
 * 路由守卫是"直接输 URL 进不进得去"，两处用的是同一批权限码。</p>
 */
const menuItemsBeforeGroups: MenuItem[] = [
  { path: '/dashboard', title: '首页', icon: 'HomeFilled', permission: 'dashboard:view' }
]

/** 这三个在视觉上排在分组之后，因此单独一组，只为保持既有顺序不变。 */
const menuItemsAfterGroups: MenuItem[] = [
  {
    path: '/analysis/overview',
    title: '数据概览',
    icon: 'TrendCharts',
    permission: 'analysis:overview:view'
  },
  { path: '/projects', title: '项目管理', icon: 'Folder', permission: 'project:view' },
  { path: '/enterprises', title: '企业管理', icon: 'OfficeBuilding', permission: 'enterprise:view' }
]

const menuGroupDefinitions: MenuGroup[] = [
  {
    title: '订单管理',
    icon: 'Tickets',
    children: [
      { path: '/orders/tender', title: '投标订单', icon: 'Document', permission: 'order:tender:view' },
      {
        path: '/orders/performance',
        title: '履约订单',
        icon: 'DocumentChecked',
        permission: 'order:performance:view'
      }
    ]
  },
  {
    title: '系统配置',
    icon: 'Setting',
    children: [
      {
        path: '/system/insurance-types',
        title: '险种配置',
        icon: 'Files',
        permission: 'system:insurance:view'
      },
      { path: '/system/orgs', title: '机构配置', icon: 'OfficeBuilding', permission: 'system:org:view' },
      { path: '/system/departments', title: '部门配置', icon: 'Grid', permission: 'system:dept:view' },
      { path: '/system/users', title: '用户配置', icon: 'User', permission: 'system:user:view' },
      { path: '/system/roles', title: '角色配置', icon: 'Key', permission: 'system:role:view' },
      // 操作审计（P-07）：当前权限矩阵下仅 ADMIN 持有 system:audit:view
      {
        path: '/system/operation-audits',
        title: '操作审计',
        icon: 'List',
        permission: 'system:audit:view'
      },
      // AI 运行（T5-04）：只读可视化；权限与操作审计同源（system:audit:view）
      {
        path: '/system/ai-runtime',
        title: 'AI 运行',
        icon: 'DataLine',
        permission: 'system:audit:view'
      }
    ]
  }
]

const visibleBeforeGroups = computed(() => menuItemsBeforeGroups.filter((item) => can(item.permission)))

const visibleAfterGroups = computed(() => menuItemsAfterGroups.filter((item) => can(item.permission)))

/** 过滤后的分组：空分组整体剔除。 */
const menuGroups = computed<MenuGroup[]>(() =>
  menuGroupDefinitions
    .map((group) => ({ ...group, children: group.children.filter((item) => can(item.permission)) }))
    .filter((group) => group.children.length > 0)
)

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
  if (command === 'logout') {
    void handleLogout()
    return
  }
  if (command === 'change-password') {
    // 主动改密（非强制模式）。被强制改密的用户走不到这里——守卫会把他锁在改密页
    void router.push('/change-password')
  }
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
          <el-menu-item v-for="item in visibleBeforeGroups" :key="item.path" :index="item.path">
            <el-icon><component :is="item.icon" /></el-icon>
            <template #title>{{ item.title }}</template>
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

          <el-menu-item v-for="item in visibleAfterGroups" :key="item.path" :index="item.path">
            <el-icon><component :is="item.icon" /></el-icon>
            <template #title>{{ item.title }}</template>
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
                <el-dropdown-item divided command="change-password">
                  <el-icon><Key /></el-icon>
                  修改密码
                </el-dropdown-item>
                <el-dropdown-item command="logout">
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
/*
 * 高度一律用 100%（而不是 100vh）：html/body/#app 都已是 height:100%（styles/main.css），
 * 所以 100% 恰好等于视口内容高度；而 100vh 在**页面出现横向滚动条**时比
 * documentElement.clientHeight 高出一个滚动条厚度，于是 body 多出 1px 级纵向溢出。
 * 这点溢出本身看不见，却会让 element-plus 的弹窗锁屏误判"body 有纵向溢出"，
 * 把 body 宽度收窄一个滚动条宽度 —— 表现就是打开详情弹窗时整页向左缩进 ~15px
 * （实测复现与覆盖规则见 styles/main.css 的「弹窗锁屏」一节）。
 */
.app-layout {
  height: 100%;
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
  height: 100%;
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
