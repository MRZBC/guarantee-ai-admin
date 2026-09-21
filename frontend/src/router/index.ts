import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'
import { getToken } from '@/utils/storage'

/**
 * 路由使用 Hash 模式：无需后端/静态服务器做 history fallback，
 * 生产环境把 dist 交给任意静态服务器即可直接访问。
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
        meta: { title: '首页' }
      },
      {
        path: 'orders/tender',
        name: 'TenderOrders',
        component: () => import('@/views/orders/TenderOrders.vue'),
        meta: { title: '投标订单', parentTitle: '订单管理' }
      },
      {
        path: 'orders/performance',
        name: 'PerformanceOrders',
        component: () => import('@/views/orders/PerformanceOrders.vue'),
        meta: { title: '履约订单', parentTitle: '订单管理' }
      },
      {
        path: 'analysis/overview',
        name: 'AnalysisOverview',
        component: () => import('@/views/analysis/Overview.vue'),
        meta: { title: '数据概览', parentTitle: '数据分析' }
      },
      {
        path: 'projects',
        name: 'Projects',
        component: () => import('@/views/Projects.vue'),
        meta: { title: '项目管理' }
      },
      {
        path: 'enterprises',
        name: 'Enterprises',
        component: () => import('@/views/Enterprises.vue'),
        meta: { title: '企业管理' }
      },
      {
        path: 'system/insurance-types',
        name: 'InsuranceTypes',
        component: () => import('@/views/system/InsuranceTypes.vue'),
        meta: { title: '险种配置', parentTitle: '系统配置' }
      },
      {
        path: 'system/orgs',
        name: 'SystemOrgs',
        component: () => import('@/views/system/Orgs.vue'),
        meta: { title: '机构配置', parentTitle: '系统配置' }
      },
      {
        path: 'system/departments',
        name: 'SystemDepartments',
        component: () => import('@/views/system/Departments.vue'),
        meta: { title: '部门配置', parentTitle: '系统配置' }
      },
      {
        path: 'system/users',
        name: 'SystemUsers',
        component: () => import('@/views/system/Users.vue'),
        meta: { title: '用户配置', parentTitle: '系统配置' }
      },
      {
        path: 'system/roles',
        name: 'SystemRoles',
        component: () => import('@/views/system/Roles.vue'),
        meta: { title: '角色配置', parentTitle: '系统配置' }
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

/** 全局前置守卫：无 token 一律回到登录页 */
router.beforeEach((to) => {
  const token = getToken()
  if (!token && !to.meta.public) {
    return { path: '/login', query: to.fullPath !== '/' ? { redirect: to.fullPath } : undefined }
  }
  if (token && to.path === '/login') {
    return { path: '/dashboard' }
  }
  return true
})

router.afterEach((to) => {
  const title = (to.meta.title as string | undefined) ?? ''
  document.title = title ? `${title} - 担保业务管理平台` : '担保业务管理平台'
})

export default router
