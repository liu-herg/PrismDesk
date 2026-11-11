<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import LoginView from './views/LoginView.vue'
import ChatView from './views/ChatView.vue'
import TicketsView from './views/TicketsView.vue'
import KnowledgeView from './views/KnowledgeView.vue'
import StatusView from './views/StatusView.vue'
import UsersView from './views/UsersView.vue'
import { api } from './api.js'
import { refreshSession, session, clearSession } from './session.js'
import { ROLE_LABELS, TICKET_ACTIONS } from './format.js'

// ---------- 恢复登录 ----------
onMounted(async () => {
  await refreshSession()
  session.ready = true
})

// ---------- 路由（hash）：#/<页面>/<参数1>/<参数2> ----------
const NAVS = {
  CUSTOMER: [{ view: 'chat', label: '咨询客服' }],
  AGENT: [
    { view: 'tickets', label: '工单' },
    { view: 'history', label: '对话记录' },
    { view: 'knowledge', label: '知识库' }
  ],
  ADMIN: [
    { view: 'tickets', label: '工单' },
    { view: 'history', label: '对话记录' },
    { view: 'knowledge', label: '知识库' },
    { view: 'status', label: '运行状态' },
    { view: 'users', label: '账号' }
  ]
}
const nav = computed(() => (session.user ? NAVS[session.user.role] || [] : []))

const hash = ref(location.hash)
const onHash = () => (hash.value = location.hash)
onMounted(() => window.addEventListener('hashchange', onHash))
onUnmounted(() => window.removeEventListener('hashchange', onHash))

const route = computed(() => {
  const [view = '', a = '', b = ''] = hash.value.replace(/^#\/?/, '').split('/').map(decodeURIComponent)
  const allowed = nav.value.map((n) => n.view)
  return { view: allowed.includes(view) ? view : allowed[0], a, b }
})
function go(view, ...params) {
  const parts = [view, ...params.filter((p) => p !== undefined && p !== null && p !== '')].map((p) => encodeURIComponent(p))
  location.hash = `#/${parts.join('/')}`
}

// ---------- 实时通知 ----------
const live = ref(false)
const toasts = ref([])
const noticeTick = ref(0) // 每收到一条工单通知加 1，页面据此刷新
const lastNotice = ref(null)
const pendingCount = ref(0)
let controller = null
let retryTimer = null

async function connect() {
  disconnect()
  if (!session.user) return
  controller = new AbortController()
  const mine = controller
  try {
    await api.notifications((name, data) => {
      if (name === 'ready') live.value = true
      if (name === 'ticket') onNotice(data)
    }, mine.signal)
  } catch {
    // 断开了：下面统一重连
  }
  live.value = false
  if (mine === controller && session.user) {
    // 服务端 30 分钟主动断开、或者后端重启：几秒后重连（重连时会用上最新的访问令牌）
    retryTimer = setTimeout(connect, 3000)
  }
}
function disconnect() {
  clearTimeout(retryTimer)
  if (controller) controller.abort()
  controller = null
  live.value = false
}

function onNotice(n) {
  noticeTick.value++
  lastNotice.value = n
  refreshPending()
  const me = session.user?.username
  if (n.operator === me) return // 自己的操作不用提醒自己
  let text
  if (session.user.role === 'CUSTOMER') {
    text = {
      claim: `${n.operator_name || '客服'} 已接手你的问题，会尽快在这里回复你`,
      resolve: '你的问题已处理完毕',
      release: '你的问题正在重新分配客服',
      reopen: '你的问题已重新打开',
      close: '工单已关闭'
    }[n.action]
  } else if (n.type === 'ticket.created') {
    text = `新工单 #${n.ticket_id}：${n.reason || '客户要求人工'}`
  } else {
    text = `工单 #${n.ticket_id} 被 ${n.operator_name || n.operator} ${TICKET_ACTIONS[n.action] || n.action}`
  }
  if (!text) return
  const toast = { id: `${n.ticket_id}-${Date.now()}`, text, ticketId: n.ticket_id, alert: n.type === 'ticket.created' }
  toasts.value = [...toasts.value.slice(-2), toast]
  setTimeout(() => (toasts.value = toasts.value.filter((t) => t.id !== toast.id)), 8000)
}

async function refreshPending() {
  if (!session.user || session.user.role === 'CUSTOMER') return
  try {
    pendingCount.value = (await api.tickets({ status: 'pending', size: 100 })).items.length
  } catch {
    pendingCount.value = 0
  }
}

watch(
  () => session.user?.username,
  (name) => {
    if (name) {
      connect()
      refreshPending()
    } else {
      disconnect()
      toasts.value = []
    }
  }
)
onUnmounted(disconnect)

async function logout() {
  await api.logout()
  clearSession()
  location.hash = ''
}
</script>

<template>
  <div v-if="!session.ready" class="empty" style="height: 100%">正在加载…</div>

  <LoginView v-else-if="!session.user" />

  <div v-else class="shell">
    <nav class="rail" aria-label="主导航">
      <div class="brand">
        <svg width="28" height="28" viewBox="0 0 32 32" aria-hidden="true">
          <path d="M3 27 13.5 5 24 27z" fill="var(--indigo)" />
          <path d="M18 15h11" stroke="var(--s-intent)" stroke-width="2" />
          <path d="M19 18.5h10" stroke="var(--s-knowledge)" stroke-width="2" />
          <path d="M20.5 22h8.5" stroke="var(--s-generate)" stroke-width="2" />
          <path d="M22 25.5h7" stroke="var(--s-verify)" stroke-width="2" />
        </svg>
        <div>
          <strong>PrismDesk</strong>
          <span>{{ session.user.role === 'CUSTOMER' ? '在线客服' : '客服工作台' }}</span>
        </div>
      </div>
      <button
        v-for="item in nav"
        :key="item.view"
        class="nav-item"
        :aria-current="route.view === item.view ? 'page' : undefined"
        @click="go(item.view)"
      >
        <span>{{ item.label }}</span>
        <span v-if="item.view === 'tickets' && pendingCount" class="nav-count" :title="`${pendingCount} 张工单待分配`">
          {{ pendingCount }}
        </span>
      </button>

      <div class="whoami">
        <div class="row who">
          <strong>{{ session.user.display_name }}</strong>
          <span class="tag">{{ ROLE_LABELS[session.user.role] }}</span>
        </div>
        <span class="faint who">{{ session.user.username }}</span>
        <span class="live" :class="{ on: live }">{{ live ? '实时通知已连接' : '实时通知连接中…' }}</span>
        <button class="btn btn-small" @click="logout">退出登录</button>
      </div>
    </nav>

    <main class="stage">
      <ChatView
        v-if="route.view === 'chat'"
        mode="customer"
        :conv-id="route.a"
        :last-notice="lastNotice"
        @open-conversation="(id) => go('chat', id)"
      />
      <ChatView
        v-else-if="route.view === 'history'"
        mode="staff"
        :customer-id="route.a"
        :conv-id="route.b"
        @open-conversation="(id, customer) => go('history', customer || route.a, id)"
        @open-customer="(customer) => go('history', customer)"
        @open-ticket="(id) => go('tickets', id)"
      />
      <TicketsView
        v-else-if="route.view === 'tickets'"
        :ticket-id="route.a"
        :notice-tick="noticeTick"
        @select="(id) => go('tickets', id)"
        @open-conversation="({ userId, convId }) => go('history', userId, convId)"
        @changed="refreshPending"
      />
      <KnowledgeView v-else-if="route.view === 'knowledge'" :can-edit="session.user.role === 'ADMIN'" />
      <StatusView v-else-if="route.view === 'status'" />
      <UsersView v-else-if="route.view === 'users'" />
    </main>

    <div class="toasts" aria-live="polite">
      <div v-for="t in toasts" :key="t.id" class="toast" :class="{ alert: t.alert }">
        <div>{{ t.text }}</div>
        <button
          v-if="session.user.role !== 'CUSTOMER'"
          class="btn btn-small"
          @click="go('tickets', t.ticketId); toasts = toasts.filter((x) => x.id !== t.id)"
        >查看工单</button>
      </div>
    </div>
  </div>
</template>
