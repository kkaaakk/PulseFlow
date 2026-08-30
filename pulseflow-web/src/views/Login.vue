<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowRight, Lightning } from '@element-plus/icons-vue'

import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const loading = ref(false)
const form = reactive({ operatorId: 1024, password: 'pulseflow-local' })

const submit = async () => {
  if (!form.operatorId || !form.password) {
    ElMessage.warning('请输入 Operator ID 和本地访问口令')
    return
  }
  loading.value = true
  try {
    await auth.login(form.operatorId, form.password)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/dashboard'
    await router.replace(redirect)
  } catch (error) {
    const message = error instanceof Error ? error.message : '登录失败，请检查服务状态'
    ElMessage.error(message)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <section class="login-aside">
      <div class="login-brand">
        <span class="brand-mark"><el-icon><Lightning /></el-icon></span>
        <span>PulseFlow</span>
      </div>
      <div class="login-copy">
        <h1>让每一次行为，<br />都成为下一步动作。</h1>
        <p>从事件、画像到 Campaign 和归因，在同一个运营工作台里看见完整链路。</p>
        <div class="login-flow" aria-hidden="true"><span /><span /><span /></div>
      </div>
    </section>
    <section class="login-form-wrap">
      <div class="login-form">
        <h2>登录 PulseFlow</h2>
        <p class="login-form-intro">使用本地 Operator 身份进入智能运营控制台。</p>
        <el-form label-position="top" @submit.prevent="submit">
          <el-form-item label="Operator ID">
            <el-input v-model.number="form.operatorId" size="large" type="number" autocomplete="username" placeholder="1024" />
          </el-form-item>
          <el-form-item label="本地访问口令">
            <el-input v-model="form.password" size="large" type="password" show-password autocomplete="current-password" placeholder="请输入访问口令" />
          </el-form-item>
          <button class="primary-button" type="submit" :disabled="loading">
            <span>{{ loading ? '登录中…' : '进入控制台' }}</span>
            <el-icon v-if="!loading"><ArrowRight /></el-icon>
          </button>
        </el-form>
        <div class="local-hint">本地演示默认身份：Operator ID <strong>1024</strong>，访问口令 <strong>pulseflow-local</strong>。真实部署请通过环境变量替换。</div>
      </div>
    </section>
  </div>
</template>
