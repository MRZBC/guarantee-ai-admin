import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { getToken } from '@/utils/storage'

/**
 * 路由 meta 的类型声明（P-06 / SYS-P-06）。
 *
 * 声明出来是为了让 `permission` 有类型、避免各处 `as string` 强转——
 * 强转会让"权限码写错一个字母"这种错误在编译期完全看不出来。
 */
declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    parentTitle?: string
    /** 无需登录即可访问 */
    public?: boolean
    /** 进入该路由所需的权限码；不配表示登录即可访问 */
    permission?: string
  }
}

/**
 * 路由使用 Hash 模式：无需后端/静态服务器做 history fallback，
 * 生产环境把 dist 交给任意静态服务器即可直接访问。
 *
 * 每条业务路由都声明 `meta.permission`（SYS-P-06）：菜单过滤只解决"看到不该看的入口"，
 * 直接输 URL 的入口由下面的守卫拦。**前端过滤不是安全边界**（SYS-NF-04）——
 * 后端 `@PreAuthorize` 与数据范围保持原样，这里只避免"渲染出空壳再弹 403"的体验。
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    meta: { title: '登录', public: true }
  },
  {
    path: '/',
    component: () => import('@/layout/AppLayout.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/Dashboard.vue'),
        meta: { title: '首页', permission: 'dashboard:view' }
      },
      {
        path: 'orders/tender',
        name: 'TenderOrders',
        component: () => import('@/views/orders/TenderOrders.vue'),
        meta: { title: '投标订单', parentTitle: '订单管理', permission: 'order:tender:view' }
      },
      {
        path: 'orders/performance',
        name: 'PerformanceOrders',
        component: () => import('@/views/orders/PerformanceOrders.vue'),
        meta: { title: '履约订单', parentTitle: '订单管理', permission: 'order:performance:view' }
      },
      {
        path: 'analysis/overview',
        name: 'AnalysisOverview',
        component: () => import('@/views/analysis/Overview.vue'),
        meta: { title: '数据概览', parentTitle: '数据分析', permission: 'analysis:overview:view' }
      },
      {
        path: 'projects',
        name: 'Projects',
        component: () => import('@/views/Projects.vue'),
        meta: { title: '项目管理', permission: 'project:view' }
      },
      {
        path: 'enterprises',
        name: 'Enterprises',
        component: () => import('@/views/Enterprises.vue'),
        meta: { title: '企业管理', permission: 'enterprise:view' }
      },
      {
        path: 'system/insurance-types',
        name: 'InsuranceTypes',
        component: () => import('@/views/system/InsuranceTypes.vue'),
        meta: { title: '险种配置', parentTitle: '系统配置', permission: 'system:insurance:view' }
      },
      {
        path: 'system/orgs',
        name: 'SystemOrgs',
        component: () => import('@/views/system/Orgs.vue'),
        meta: { title: '机构配置', parentTitle: '系统配置', permission: 'system:org:view' }
      },
      {
        path: 'system/departments',
        name: 'SystemDepartments',
        component: () => import('@/views/system/Departments.vue'),
        meta: { title: '部门配置', parentTitle: '系统配置', permission: 'system:dept:view' }
      },
      {
        path: 'system/users',
        name: 'SystemUsers',
        component: () => import('@/views/system/Users.vue'),
        meta: { title: '用户配置', parentTitle: '系统配置', permission: 'system:user:view' }
      },
      {
        path: 'system/roles',
        name: 'SystemRoles',
        component: () => import('@/views/system/Roles.vue'),
        meta: { title: '角色配置', parentTitle: '系统配置', permission: 'system:role:view' }
      },
      {
        path: 'system/operation-audits',
        name: 'SystemOperationAudits',
        component: () => import('@/views/system/OperationAudits.vue'),
        meta: { title: '操作审计', parentTitle: '系统配置', permission: 'system:audit:view' }
      }
    ]
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/dashboard'
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})

/** 查不到权限时的兜底落地页。 */
const FALLBACK_PATH = '/dashboard'

/**
 * 全局前置守卫：登录态 + 页面权限（P-06）。
 *
 * <p><b>为什么必须异步</b>：刷新页面时内存里可能只有 token（`user` 为空），
 * 此时直接查 `permissions` 会把"还没加载"误判成"没有权限"，
 * 表现为"一刷新就被踢回首页"。因此先 `fetchMe()` 补齐权限快照再判断。</p>
 *
 * <p><b>两个防死循环的细节</b>：① 兜底页自身无权限时不再跳兜底页
 * （否则 Vue Router 会判定"无限重定向"并中断导航）；② `fetchMe()` 失败时不抛错，
 * 交给 `request.ts` 统一提示与 401 跳登录，避免守卫把页面卡死。</p>
 */
router.beforeEach(async (to) => {
  const token = getToken()
  if (!token && !to.meta.public) {
    return { path: '/login', query: to.fullPath !== '/' ? { redirect: to.fullPath } : undefined }
  }
  if (token && to.path === '/login') {
    return { path: FALLBACK_PATH }
  }

  const required = to.meta.permission
  if (!token || !required) {
    return true
  }

  const userStore = useUserStore()
  if (!userStore.user) {
    try {
      await userStore.fetchMe()
    } catch {
      return true
    }
  }
  if (userStore.permissions.includes(required)) {
    return true
  }
  if (to.path === FALLBACK_PATH) {
    return true
  }
  ElMessage.warning('你当前没有访问该页面的权限')
  return { path: FALLBACK_PATH }
})

router.afterEach((to) => {
  const title = to.meta.title ?? ''
  document.title = title ? `${title} - 担保业务管理平台` : '担保业务管理平台'
})

export default router
