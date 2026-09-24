<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { changePassword } from '@/api/auth'
import { useUserStore } from '@/stores/user'

/**
 * 修改密码页（§6.2a，D1=C 的强制连带项）。
 *
 * <p><b>为什么它是独立于用户管理页的一级路由</b>：没有它，「固定默认密码 + 强制首次改密」
 * 就是一道死锁——用户被服务端闸门关在"必须改密"后面，却没有任何改密入口，账号直接变砖。</p>
 *
 * <p><b>两种模式共存</b>：`userStore.mustChangePassword` 为 true 时是"被强制"模式
 * （顶部醒目说明 + 不提供返回）；为 false 时就是普通功能，入口在 `AppLayout` 的用户菜单。
 * 接口与页面反正都要做，把主动改密一并开放是零成本的，还补上了系统里
 * "用户无法自己改密码"这个存在已久的缺口。</p>
 */

const router = useRouter()
const userStore = useUserStore()

/** 强制模式：此时除改密与登出外什么都做不了，因此不提供"返回"。 */
const forced = computed(() => userStore.mustChangePassword)

const formRef = ref<FormInstance>()
const submitting = ref(false)

const form = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})

/** D8=A 的强度策略：8~64 位、不同于旧密码、不等于默认密码（最后一条由后端校验）。 */
const NEW_PASSWORD_MIN = 8
const NEW_PASSWORD_MAX = 64

const rules = computed<FormRules>(() => ({
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    {
      min: NEW_PASSWORD_MIN,
      max: NEW_PASSWORD_MAX,
      message: `新密码长度需为 ${NEW_PASSWORD_MIN}-${NEW_PASSWORD_MAX} 位`,
      trigger: 'blur'
    },
    {
      validator: (_rule, value, callback) => {
        // 后端同样会拒，这里只是让用户不必先点一次提交才知道
        if (value && value === form.oldPassword) {
          callback(new Error('新密码不能与原密码相同'))
          return
        }
        callback()
      },
      trigger: 'blur'
    }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        if (value !== form.newPassword) {
          callback(new Error('两次输入的新密码不一致'))
          return
        }
        callback()
      },
      trigger: 'blur'
    }
  ]
}))

/**
 * 提交改密。
 *
 * <p>成功后的动作不是"回首页"而是"回登录页"：后端会撤销该用户的**全部令牌**
 * （否则旧令牌里的强制改密标记恒为 true，用户改完仍被闸门拦住），停留在这里的每一次
 * 请求都会 401。因此主动清本地登录态并 `replace` 到登录页，避免用户看到一次无意义的
 * "登录状态已失效"提示。</p>
 */
async function handleSubmit(): Promise<void> {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    await changePassword({
      oldPassword: form.oldPassword,
      newPassword: form.newPassword
    })
    ElMessage.success('密码已修改，请使用新密码重新登录')
    // 不清 token 的话，用户的旧令牌已失效，下一次请求会先弹"登录状态已失效"再跳登录页
    userStore.logout()
    await router.replace('/login')
  } catch {
    // 失败原因（「原密码不正确」/「新密码不能与旧密码相同」等）已由响应拦截器统一提示；
    // 刻意不清空表单，便于就地改正。
  } finally {
    submitting.value = false
  }
}

/**
 * 主动模式下退出登录。
 *
 * <p>强制模式下这个入口是**必须的**（§7 E-8）：`POST /auth/logout` 在服务端闸门白名单内，
 * 否则用户会被永久锁死在改密页，连退出都做不到，只能清浏览器存储。</p>
 */
function handleLogout(): void {
  userStore.logout()
  void router.replace('/login')
}

/** 主动模式下的返回：回到用户来时的页面。 */
function handleBack(): void {
  router.back()
}
</script>

<template>
  <div class="change-password-page">
    <div class="change-password-page__bg" />
    <el-card class="change-password-card" shadow="always">
      <div class="change-password-card__header">
        <el-icon class="change-password-card__icon"><Lock /></el-icon>
        <h1 class="change-password-card__title">修改密码</h1>
        <p class="change-password-card__subtitle">
          {{ forced ? '首次登录需先修改初始密码' : '修改你的登录密码' }}
        </p>
      </div>

      <!-- 强制模式的醒目说明：用户此时除改密与登出外什么都做不了，必须让他知道为什么 -->
      <el-alert
        v-if="forced"
        type="warning"
        :closable="false"
        show-icon
        class="change-password-card__alert"
      >
        <template #title>首次登录需先修改初始密码</template>
        <div class="change-password-card__alert-body">
          你的账号当前使用的是系统默认初始密码。为防止账号被他人抢先登录，
          请先设置一个新密码——<strong>在此之前系统会拒绝其它所有操作</strong>。
          新密码长度需为 {{ NEW_PASSWORD_MIN }}-{{ NEW_PASSWORD_MAX }} 位，且不能与原密码相同。
        </div>
      </el-alert>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        size="large"
        @keyup.enter="handleSubmit"
      >
        <el-form-item label="原密码" prop="oldPassword">
          <el-input
            v-model="form.oldPassword"
            type="password"
            placeholder="请输入当前密码"
            show-password
            autocomplete="off"
          >
            <template #prefix>
              <el-icon><Key /></el-icon>
            </template>
          </el-input>
        </el-form-item>

        <el-form-item label="新密码" prop="newPassword">
          <el-input
            v-model="form.newPassword"
            type="password"
            :placeholder="`${NEW_PASSWORD_MIN}-${NEW_PASSWORD_MAX} 位，不能与原密码相同`"
            show-password
            autocomplete="off"
          >
            <template #prefix>
              <el-icon><Lock /></el-icon>
            </template>
          </el-input>
        </el-form-item>

        <el-form-item label="确认新密码" prop="confirmPassword">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            placeholder="请再次输入新密码"
            show-password
            autocomplete="off"
          >
            <template #prefix>
              <el-icon><Lock /></el-icon>
            </template>
          </el-input>
        </el-form-item>

        <!-- flex + gap：按钮间距不依赖 Element Plus 的
             `.el-button + .el-button { margin-left: 12px }`（§6.5 连带注意） -->
        <el-form-item label-width="0">
          <div class="change-password-card__actions">
            <el-button
              v-if="!forced"
              class="change-password-card__action"
              @click="handleBack"
            >
              返回
            </el-button>
            <el-button
              v-if="forced"
              class="change-password-card__action"
              @click="handleLogout"
            >
              退出登录
            </el-button>
            <el-button
              type="primary"
              class="change-password-card__action"
              :loading="submitting"
              @click="handleSubmit"
            >
              确认修改
            </el-button>
          </div>
        </el-form-item>
      </el-form>

      <div class="change-password-card__hint text-muted">
        修改成功后当前登录状态会失效，需要用新密码重新登录。
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.change-password-page {
  position: relative;
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1f2d3d 0%, #2b4a6f 55%, #3a7bd5 100%);
  overflow: hidden;
  padding: 16px;
}

.change-password-page__bg {
  position: absolute;
  inset: -40%;
  background: radial-gradient(circle at 30% 30%, rgba(64, 158, 255, 0.28), transparent 55%),
    radial-gradient(circle at 70% 65%, rgba(103, 194, 58, 0.18), transparent 55%);
  pointer-events: none;
}

.change-password-card {
  position: relative;
  width: 460px;
  max-width: calc(100vw - 32px);
  border-radius: 12px;
  padding: 8px 4px;
}

.change-password-card__header {
  text-align: center;
  margin-bottom: 14px;
}

.change-password-card__icon {
  font-size: 32px;
  color: #409eff;
}

.change-password-card__title {
  margin: 8px 0 2px;
  font-size: 20px;
  color: #303133;
}

.change-password-card__subtitle {
  margin: 0;
  font-size: 12px;
  color: #909399;
  letter-spacing: 0.5px;
}

.change-password-card__alert {
  margin-bottom: 16px;
}

.change-password-card__alert-body {
  line-height: 1.7;
  font-size: 13px;
}

.change-password-card__actions {
  display: flex;
  width: 100%;
  gap: 12px;
}

.change-password-card__action {
  flex: 1;
}

.change-password-card__hint {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.6;
  text-align: center;
}
</style>
