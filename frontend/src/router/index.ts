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
    /*
      修改密码（D1=C 的连带项）。

      **刻意与 /login 同级、不在 AppLayout 之下**：被强制改密的用户此时什么都做不了，
      让他看到带侧边栏与菜单的空壳只会制造"我是不是坏了"的错觉。
    */
    path: '/change-password',
    name: 'ChangePassword',
    component: () => import('@/views/ChangePassword.vue'),
    // 不设 public：改密接口靠 token 认人，未登录访问要先登录
    meta: { title: '修改密码' }
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
      },
      {
        /*
          AI 运行（只读，REQ-MCP-11 / T5-04）。
          权限与操作审计同一枚 `system:audit:view`：两者都是"看运行痕迹"的只读页，
          当前权限矩阵下仅 ADMIN 持有；再拆一枚新权限只会增加配置面而无新的安全收益（REQ §5.3.4 的裁定）。
        */
        path: 'system/ai-runtime',
        name: 'SystemAiRuntime',
        component: () => import('@/views/system/AiRuntime.vue'),
        meta: { title: 'AI 运行', parentTitle: '系统配置', permission: 'system:audit:view' }
      },
      {
        /*
          AI 配置（第四阶段 REQ-CFG-08/10，T4-04）。
          权限 `ai:config:view`：ADMIN 默认拥有、其余角色默认无；
          写操作另有 `ai:config:update`（危险权限），由服务端 @PreAuthorize 兜底，前端隐藏按钮不算数。
        */
        path: 'system/ai-config',
        name: 'SystemAiConfig',
        component: () => import('@/views/system/AiConfig.vue'),
        meta: { title: 'AI 配置', parentTitle: '系统配置', permission: 'ai:config:view' }
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

/** 强制改密页路径（§6.2b 第 2 条）。 */
const CHANGE_PASSWORD_PATH = '/change-password'

/**
 * 全局前置守卫：登录态 + 强制改密闸门 + 页面权限（P-06 / D1=C）。
 *
 * <p><b>为什么必须异步</b>：刷新页面时内存里可能只有 token（`user` 为空），
 * 此时直接查 `permissions` 会把"还没加载"误判成"没有权限"，
 * 表现为"一刷新就被踢回首页"。因此先 `fetchMe()` 补齐权限快照再判断。</p>
 *
 * <p><b>强制改密闸门（§6.2b 第 2 条）</b>：`mustChangePassword` 为 true 时，
 * 除改密页本身外的一切路由都重定向到 `/change-password`。这只是**体验**层——
 * 真正的安全边界是服务端 `PasswordChangeRequiredFilter`（用户直接调接口照样被 403/1006 拒绝）。
 * 因为标记会被持久化（见 `stores/user.ts`），守卫在刷新后**第一次导航时**就能生效，
 * 不必等 `fetchMe()` 回来才发现"该用户必须改密"。</p>
 *
 * <p><b>三个防死循环的细节</b>：① 兜底页自身无权限时不再跳兜底页
 * （否则 Vue Router 会判定"无限重定向"并中断导航）；② 改密页不受权限码约束
 * （它没有 `meta.permission`，且必须对"什么权限都没有"的新账号可用）；
 * ③ `fetchMe()` 失败时不抛错，交给 `request.ts` 统一提示与 401 跳登录，避免守卫把页面卡死。</p>
 */
router.beforeEach(async (to) => {
  const token = getToken()
  if (!token && !to.meta.public) {
    return { path: '/login', query: to.fullPath !== '/' ? { redirect: to.fullPath } : undefined }
  }

  const userStore = useUserStore()

  if (token && to.path === '/login') {
    // 已被强制改密的用户重新打开登录页时，应回到改密页而不是首页（首页必然被闸门拒绝）
    return { path: userStore.mustChangePassword ? CHANGE_PASSWORD_PATH : FALLBACK_PATH }
  }

  // 强制改密闸门：除改密页自身外一律重定向过去
  if (token && userStore.mustChangePassword && to.path !== CHANGE_PASSWORD_PATH) {
    return { path: CHANGE_PASSWORD_PATH }
  }

  const required = to.meta.permission
  if (!token || !required) {
    return true
  }

  if (!userStore.user) {
    try {
      await userStore.fetchMe()
    } catch {
      return true
    }
    // fetchMe 可能刚刚把标记打开（例如缓存被清过），需要重新判一次
    if (userStore.mustChangePassword && to.path !== CHANGE_PASSWORD_PATH) {
      return { path: CHANGE_PASSWORD_PATH }
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
