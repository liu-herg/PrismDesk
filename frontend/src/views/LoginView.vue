<script setup>
import { ref } from 'vue'
import { api } from '../api.js'
import { applyLogin } from '../session.js'

const DEMO = [
  { username: 'u1001', name: '张敏', role: '客户', hint: '和客服对话' },
  { username: 'agent-01', name: '坐席小周', role: '坐席', hint: '处理转人工工单' },
  { username: 'agent-02', name: '坐席小林', role: '坐席', hint: '另开窗口演示抢单' },
  { username: 'admin', name: '管理员', role: '管理员', hint: '知识库、运行状态、账号' }
]

const mode = ref('login')
const username = ref('')
const password = ref('')
const displayName = ref('')
const busy = ref(false)
const error = ref('')

function useDemo(account) {
  mode.value = 'login'
  username.value = account.username
  password.value = 'demo123'
  error.value = ''
}

async function submit() {
  busy.value = true
  error.value = ''
  try {
    const result =
      mode.value === 'login'
        ? await api.login(username.value.trim(), password.value)
        : await api.register(username.value.trim(), password.value, displayName.value.trim())
    applyLogin(result)
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="login">
    <section class="login-intro" aria-hidden="false">
      <svg class="login-prism" viewBox="0 0 320 200" aria-hidden="true">
        <path d="M18 182 92 22 166 182z" fill="var(--indigo)" opacity="0.92" />
        <path d="M0 102 L104 102" stroke="var(--line)" stroke-width="3" />
        <g stroke-width="5" stroke-linecap="round">
          <path d="M128 92 L318 54" stroke="var(--s-intent)" />
          <path d="M131 101 L318 92" stroke="var(--s-knowledge)" />
          <path d="M134 110 L318 130" stroke="var(--s-generate)" />
          <path d="M137 119 L318 168" stroke="var(--s-verify)" />
        </g>
      </svg>
      <h1>PrismDesk 智能客服台</h1>
      <p>一个问题进来，同时拆成意图识别、知识库检索、回答生成和校验几条线并行处理；解决不了的自动转成工单，交给人工坐席。</p>
    </section>

    <section class="login-card">
      <div class="segmented" role="tablist" style="padding: 0; border: 0; margin-bottom: 18px">
        <button role="tab" :aria-pressed="String(mode === 'login')" @click="mode = 'login'">登录</button>
        <button role="tab" :aria-pressed="String(mode === 'register')" @click="mode = 'register'">注册客户账号</button>
      </div>

      <form style="display: grid; gap: 12px" @submit.prevent="submit">
        <label>
          <span class="label">用户名</span>
          <input v-model="username" class="field" autocomplete="username" required />
        </label>
        <label v-if="mode === 'register'">
          <span class="label">怎么称呼你（可选）</span>
          <input v-model="displayName" class="field" autocomplete="nickname" />
        </label>
        <label>
          <span class="label">密码</span>
          <input
            v-model="password"
            class="field"
            type="password"
            :autocomplete="mode === 'login' ? 'current-password' : 'new-password'"
            required
          />
        </label>
        <p v-if="mode === 'register'" class="faint">用户名 3 到 32 位字母、数字、下划线或短横线；密码至少 6 位。坐席账号由管理员创建。</p>
        <p v-if="error" class="notice" style="margin: 0">{{ error }}</p>
        <button class="btn btn-primary" style="height: 38px; justify-content: center" :disabled="busy">
          {{ busy ? '请稍候…' : mode === 'login' ? '登录' : '注册并登录' }}
        </button>
      </form>

      <div class="demo-accounts">
        <h3>演示账号</h3>
        <p class="faint" style="margin-bottom: 8px">密码都是 demo123，点一下自动填入。</p>
        <button v-for="a in DEMO" :key="a.username" class="demo-account" type="button" @click="useDemo(a)">
          <span>
            <strong>{{ a.name }}</strong>
            <span class="faint">{{ a.username }}</span>
          </span>
          <span class="faint">{{ a.role }}，{{ a.hint }}</span>
        </button>
      </div>
    </section>
  </div>
</template>
