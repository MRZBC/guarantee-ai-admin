<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const formRef = ref<FormInstance>()
const loading = ref(false)

const form = reactive({
  username: '',
  password: ''
})

const rules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 32, message: '用户名长度为 3-32 个字符', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 32, message: '密码长度为 6-32 个字符', trigger: 'blur' }
  ]
}

/**
 * 登录。
 *
 * <p><b>登录后必须按 `mustChangePassword` 分流（§6.2b 第 1 条）</b>：被标记的账号在改密完成前
 * 除改密/登出/读自己外的一切请求都会被服务端闸门拒为 403/1006。若这里仍跳 dashboard，
 * 用户会先看到一个"加载失败"的空首页再被弹回来——体验上是"系统坏了"。
 * 注意分流要**先于** `redirect` 查询参数：那个 redirect 指向的业务页此刻必然访问不了。</p>
 */
async function handleLogin(): Promise<void> {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    const user = await userStore.login({ username: form.username, password: form.password })
    if (userStore.mustChangePassword) {
      ElMessage.warning('首次登录需先修改初始密码')
      await router.replace('/change-password')
      return
    }
    ElMessage.success(`欢迎回来，${user.realName || user.username}`)
    const redirect = route.query.redirect
    const target = typeof redirect === 'string' && redirect.startsWith('/') ? redirect : '/dashboard'
    await router.push(target)
  } catch {
    // 失败提示已由响应拦截器统一处理
  } finally {
    loading.value = false
  }
}

function fillDemoAccount(): void {
  form.username = 'admin'
  form.password = 'Admin@123'
}
</script>

<template>
  <div class="login-page">
    <div class="login-page__bg" />
    <el-card class="login-card" shadow="always">
      <div class="login-card__header">
        <el-icon class="login-card__icon"><Shield /></el-icon>
        <h1 class="login-card__title">担保业务管理平台</h1>
        <p class="login-card__subtitle">Guarantee Business Administration</p>
      </div>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        size="large"
        @keyup.enter="handleLogin"
      >
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="请输入用户名" clearable>
            <template #prefix>
              <el-icon><User /></el-icon>
            </template>
          </el-input>
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            show-password
          >
            <template #prefix>
              <el-icon><Lock /></el-icon>
            </template>
          </el-input>
        </el-form-item>

        <el-form-item>
          <el-button
            type="primary"
            class="login-card__submit"
            :loading="loading"
            @click="handleLogin"
          >
            登 录
          </el-button>
        </el-form-item>
      </el-form>

      <el-alert type="info" :closable="false" class="login-card__demo">
        <template #default>
          <div class="login-card__demo-line">
            演示账号：<b>admin</b> / <b>Admin@123</b>
            <el-button link type="primary" size="small" @click="fillDemoAccount">
              一键填充
            </el-button>
          </div>
          <div class="login-card__demo-line text-muted">
            登录后可在右下角使用「业务分析助手」提问业务数据。
          </div>
        </template>
      </el-alert>
    </el-card>
  </div>
</template>

<style scoped>
.login-page {
  position: relative;
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1f2d3d 0%, #2b4a6f 55%, #3a7bd5 100%);
  overflow: hidden;
}

.login-page__bg {
  position: absolute;
  inset: -40%;
  background: radial-gradient(circle at 30% 30%, rgba(64, 158, 255, 0.28), transparent 55%),
    radial-gradient(circle at 70% 65%, rgba(103, 194, 58, 0.18), transparent 55%);
  pointer-events: none;
}

.login-card {
  position: relative;
  width: 420px;
  max-width: calc(100vw - 32px);
  border-radius: 12px;
  padding: 8px 4px;
}

.login-card__header {
  text-align: center;
  margin-bottom: 18px;
}

.login-card__icon {
  font-size: 34px;
  color: #409eff;
}

.login-card__title {
  margin: 8px 0 2px;
  font-size: 20px;
  color: #303133;
}

.login-card__subtitle {
  margin: 0;
  font-size: 12px;
  color: #909399;
  letter-spacing: 1px;
}

.login-card__submit {
  width: 100%;
}

.login-card__demo {
  margin-top: 4px;
}

.login-card__demo-line {
  line-height: 1.8;
  font-size: 13px;
}
</style>
