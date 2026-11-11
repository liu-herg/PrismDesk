<script setup>
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { api } from '../api.js'
import { session } from '../session.js'
import { STAGE_LABELS, agentLabel, intentLabel, ms, percent, timeLabel } from '../format.js'
import StageTrace from '../components/StageTrace.vue'

// mode = customer：客户和客服对话（流式）；mode = staff：坐席 / 管理员只读查看某个客户的对话，每条回复可以看处理过程
const props = defineProps({
  mode: { type: String, default: 'customer' },
  convId: String,
  customerId: String,
  lastNotice: Object
})
const emit = defineEmits(['open-conversation', 'open-customer', 'open-ticket'])

const isCustomer = computed(() => props.mode === 'customer')
const owner = computed(() => (isCustomer.value ? session.user.username : props.customerId || ''))

const STARTERS = [
  '我想申请退款，订单号是 #12345',
  '订单显示已发货，但物流三天没更新了',
  '登录时一直提示 401 错误怎么办',
  '问题拖了一周还没解决，我要投诉，请转人工'
]

// ---------- 会话列表 ----------
const conversations = ref([])
const listError = ref('')
async function loadConversations() {
  listError.value = ''
  conversations.value = []
  if (!owner.value) return
  try {
    conversations.value = (await api.conversations(isCustomer.value ? undefined : owner.value)).items
  } catch (e) {
    listError.value = e.message
  }
}
watch(owner, loadConversations, { immediate: true })

// 坐席：选择要查看的客户
const customerInput = ref(props.customerId || '')
watch(() => props.customerId, (v) => (customerInput.value = v || ''))

// ---------- 消息 ----------
const messages = ref([])
const nextBeforeId = ref(null)
const threadError = ref('')
const loadingThread = ref(false)
const selectedKey = ref(null)
const scroller = ref(null)

function fromHistory(m) {
  return {
    key: `h${m.id}`,
    role: m.role,
    content: m.content,
    at: m.created_at,
    intent: m.intent,
    agentType: m.agent_type,
    escalated: m.escalated,
    latencyMs: m.trace?.total_ms ?? m.latency_ms,
    ticketId: m.trace?.ticket_id,
    detail: m.trace
  }
}

async function loadThread(convId) {
  messages.value = []
  nextBeforeId.value = null
  selectedKey.value = null
  threadError.value = ''
  if (!convId) return
  loadingThread.value = true
  try {
    const page = await api.messages(convId)
    messages.value = page.items.map(fromHistory)
    nextBeforeId.value = page.next_before_id
    const lastAssistant = [...messages.value].reverse().find((m) => m.role === 'assistant')
    selectedKey.value = lastAssistant?.key ?? null
    scrollToBottom()
  } catch (e) {
    threadError.value = e.status === 404 ? '这个会话不存在，或者不属于当前账号。' : e.message
  } finally {
    loadingThread.value = false
  }
}

async function loadEarlier() {
  if (!nextBeforeId.value) return
  try {
    const page = await api.messages(props.convId, nextBeforeId.value)
    messages.value = [...page.items.map(fromHistory), ...messages.value]
    nextBeforeId.value = page.next_before_id
  } catch (e) {
    threadError.value = e.message
  }
}

// 新会话发出第一条消息后地址会换成服务端分配的会话 ID，这时消息已经在页面上了，不要清空重载
let keepThreadFor = null
watch(
  () => props.convId,
  (convId) => {
    if (convId && convId === keepThreadFor) {
      keepThreadFor = null
      return
    }
    loadThread(convId)
  },
  { immediate: true }
)

// 坐席接手 / 解决了当前会话的工单：在对话里插一条提示
watch(
  () => props.lastNotice,
  (n) => {
    if (!n || !isCustomer.value || n.conversation_id !== props.convId) return
    const text = {
      claim: `${n.operator_name || '客服'} 已接手你的问题`,
      resolve: '客服已处理完你的问题',
      release: '正在为你重新分配客服',
      reopen: '你的问题已重新打开'
    }[n.action]
    if (text) {
      messages.value.push({ key: `n${Date.now()}`, role: 'system', content: text, at: n.at })
      scrollToBottom()
    }
  }
)

// ---------- 发送（流式） ----------
const draft = ref('')
const sending = ref(false)
const sendError = ref('')
const steps = ref([])
const elapsed = ref(0)
let tick
onUnmounted(() => clearInterval(tick))

function onStage(data) {
  const existing = steps.value.find((s) => s.stage === data.stage)
  if (data.state === 'started') {
    if (existing) existing.state = 'running'
    else steps.value.push({ stage: data.stage, state: 'running', ms: null })
  } else if (existing) {
    existing.state = existing.state === 'degraded' ? 'degraded' : 'done'
    existing.ms = (existing.ms || 0) + (data.ms || 0)
  }
  scrollToBottom()
}

async function send(text = draft.value) {
  const message = text.trim()
  if (!message || sending.value) return
  sendError.value = ''
  sending.value = true
  steps.value = []
  elapsed.value = 0
  const startedAt = Date.now()
  tick = setInterval(() => (elapsed.value = Math.floor((Date.now() - startedAt) / 1000)), 250)
  const userKey = `u${startedAt}`
  messages.value.push({ key: userKey, role: 'user', content: message, at: new Date().toISOString() })
  draft.value = ''
  scrollToBottom()

  let reply = null
  let failure = null
  try {
    await api.chatStream(props.convId, message, (name, data) => {
      if (name === 'stage') onStage(data)
      else if (name === 'degraded') {
        const s = steps.value.find((x) => x.stage === data.stage)
        if (s) s.state = 'degraded'
      } else if (name === 'reply') reply = data
      else if (name === 'error') failure = data
    })
  } catch (e) {
    failure = { status: e.status, message: e.message }
  } finally {
    clearInterval(tick)
    sending.value = false
  }

  if (reply) {
    const item = {
      key: `a${startedAt}`,
      role: 'assistant',
      content: reply.response,
      at: new Date().toISOString(),
      intent: reply.intent,
      agentType: reply.agent_type,
      escalated: reply.escalated,
      latencyMs: reply.total_ms,
      ticketId: reply.ticket_id,
      detail: reply
    }
    messages.value.push(item)
    if (reply.escalated) {
      messages.value.push({
        key: `s${startedAt}`,
        role: 'system',
        content: reply.ticket_id ? `已为你转接人工客服（工单 #${reply.ticket_id}），客服接手后会通知你` : '已为你转接人工客服',
        at: item.at
      })
    }
    if (!props.convId) {
      keepThreadFor = reply.conv_id
      emit('open-conversation', reply.conv_id)
    }
    loadConversations()
    scrollToBottom()
  } else {
    sendError.value = failure?.message || '连接中断了。回复可能已经生成，稍后刷新会话看看。'
    messages.value = messages.value.filter((m) => m.key !== userKey)
    draft.value = message
  }
}

function onKeydown(e) {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    send()
  }
}

function scrollToBottom() {
  nextTick(() => {
    if (scroller.value) scroller.value.scrollTop = scroller.value.scrollHeight
  })
}

const selected = computed(() => messages.value.find((m) => m.key === selectedKey.value) || null)
const current = computed(() => conversations.value.find((c) => c.conv_id === props.convId))

function selectMessage(m) {
  if (m.role === 'assistant' && !isCustomer.value) selectedKey.value = m.key
}
</script>

<template>
  <div class="chat" :class="{ 'chat-customer': isCustomer }">
    <!-- 会话列表 -->
    <section class="pane" aria-label="会话列表">
      <div class="pane-head">
        <h2>{{ isCustomer ? '我的咨询' : '对话记录' }}</h2>
        <button v-if="isCustomer" class="btn btn-small" @click="emit('open-conversation', '')">新咨询</button>
      </div>
      <form v-if="!isCustomer" class="customer-picker" @submit.prevent="emit('open-customer', customerInput.trim())">
        <input v-model="customerInput" class="field" placeholder="客户用户名，比如 u1001" aria-label="客户用户名" />
        <button class="btn btn-small" :disabled="!customerInput.trim()">查看</button>
      </form>
      <div class="pane-body">
        <p v-if="listError" class="notice">{{ listError }}</p>
        <div v-else-if="!owner" class="empty">
          <p>输入客户的用户名，查看他的对话。</p>
          <p class="faint">也可以从工单页点"查看对话"直接跳过来。</p>
        </div>
        <div v-else-if="!conversations.length" class="empty">
          <p>{{ isCustomer ? '还没有咨询记录。' : `客户 ${owner} 还没有对话。` }}</p>
        </div>
        <button
          v-for="c in conversations"
          :key="c.conv_id"
          class="list-item"
          :aria-current="c.conv_id === convId ? 'true' : undefined"
          @click="emit('open-conversation', c.conv_id, owner)"
        >
          <div class="row">
            <span class="title">{{ c.title || '（无标题）' }}</span>
            <span class="faint">{{ timeLabel(c.last_message_at) }}</span>
          </div>
          <div class="faint">{{ c.message_count }} 条消息</div>
        </button>
      </div>
    </section>

    <!-- 消息 -->
    <section class="thread" aria-label="对话">
      <header class="thread-head">
        <div style="min-width: 0">
          <h2>{{ convId ? current?.title || '对话' : isCustomer ? '新咨询' : '对话记录' }}</h2>
          <div class="faint">{{ convId ? `会话 ${convId}` : isCustomer ? '发出第一条消息后开始' : '从左侧选一个对话' }}</div>
        </div>
        <span v-if="!isCustomer && owner" class="tag">客户 {{ owner }}</span>
      </header>

      <div ref="scroller" class="messages">
        <button v-if="nextBeforeId" class="btn btn-small btn-quiet" style="align-self: center" @click="loadEarlier">
          加载更早的消息
        </button>
        <p v-if="threadError" class="notice" style="margin: 0">{{ threadError }}</p>

        <div v-if="isCustomer && !convId && !messages.length" class="empty" style="margin: auto">
          <h1>{{ session.user.display_name }}，遇到什么问题了？</h1>
          <p class="muted">描述一下情况，客服会马上回复；需要人工处理时会自动为你转接。</p>
          <div class="starters">
            <button v-for="s in STARTERS" :key="s" class="starter" @click="send(s)">{{ s }}</button>
          </div>
        </div>
        <p v-else-if="loadingThread" class="faint" style="margin: auto">正在加载消息…</p>

        <template v-for="m in messages" :key="m.key">
          <div v-if="m.role === 'system'" class="system-note">{{ m.content }}</div>
          <div
            v-else
            class="msg"
            :class="m.role === 'user' ? 'msg-user' : 'msg-assistant'"
            :aria-selected="!isCustomer && m.role === 'assistant' ? String(m.key === selectedKey) : undefined"
          >
            <div
              class="bubble"
              :class="{ selectable: !isCustomer && m.role === 'assistant' }"
              :tabindex="!isCustomer && m.role === 'assistant' ? 0 : undefined"
              @click="selectMessage(m)"
              @keydown.enter="selectMessage(m)"
            >{{ m.content }}</div>
            <div class="msg-meta">
              <span class="faint">{{ timeLabel(m.at) }}</span>
              <template v-if="m.role === 'assistant' && !isCustomer">
                <span v-if="m.intent" class="tag">{{ intentLabel(m.intent) }}</span>
                <span v-if="m.agentType" class="tag">{{ agentLabel(m.agentType) }}</span>
                <span v-if="m.latencyMs" class="tag">{{ ms(m.latencyMs) }}</span>
                <button v-if="m.ticketId" class="tag tag-alert" @click="emit('open-ticket', m.ticketId)">工单 #{{ m.ticketId }}</button>
                <span v-else-if="m.escalated" class="tag tag-alert">已转人工</span>
              </template>
            </div>
          </div>
        </template>

        <div v-if="sending" class="msg msg-assistant msg-pending">
          <div class="bubble">
            <div class="progress" aria-live="polite">
              <div v-if="!steps.length" class="progress-step running">已收到，正在处理</div>
              <div
                v-for="s in steps"
                :key="s.stage"
                class="progress-step"
                :class="s.state === 'running' ? 'running' : s.state"
              >
                {{ STAGE_LABELS[s.stage] || s.stage }}
                <span class="faint">{{ s.state === 'running' ? '' : s.state === 'degraded' ? '已跳过' : ms(s.ms) }}</span>
              </div>
            </div>
          </div>
          <div class="msg-meta"><span class="faint">已用 {{ elapsed }} 秒</span></div>
        </div>
      </div>

      <form v-if="isCustomer" class="composer" @submit.prevent="send()">
        <p v-if="sendError" class="notice" style="margin: 0 0 10px">{{ sendError }}</p>
        <div class="composer-row">
          <textarea
            v-model="draft"
            class="field"
            rows="1"
            placeholder="描述你遇到的问题"
            aria-label="消息"
            @keydown="onKeydown"
          ></textarea>
          <button class="btn btn-primary" type="submit" :disabled="sending || !draft.trim()">发送</button>
        </div>
        <p class="faint">Enter 发送，Shift + Enter 换行</p>
      </form>
      <div v-else-if="convId" class="composer faint">只读查看。点一条客服回复，右边会显示它是怎么生成的。</div>
    </section>

    <!-- 处理详情（只给坐席和管理员看） -->
    <aside v-if="!isCustomer" class="inspector" aria-label="处理详情">
      <div class="pane-head"><h2>这条回复是怎么来的</h2></div>
      <div v-if="!selected" class="empty">
        <p>点一条客服回复，查看意图识别、路由和各环节耗时。</p>
      </div>

      <template v-else-if="selected.detail">
        <div class="section">
          <h3>意图识别</h3>
          <div style="display: flex; justify-content: space-between; margin-bottom: 6px">
            <span>{{ intentLabel(selected.detail.intent) }}</span>
            <span class="muted">置信度 {{ percent(selected.detail.intent_confidence) }}</span>
          </div>
          <div class="meter"><i :style="{ width: percent(selected.detail.intent_confidence) }"></i></div>
        </div>

        <div class="section">
          <h3>交给谁回答</h3>
          <dl class="facts">
            <dt>主 Agent</dt>
            <dd>{{ agentLabel(selected.detail.primary_agent || selected.detail.agent_type) }}</dd>
            <template v-if="selected.detail.supporting_agents?.length">
              <dt>协助</dt>
              <dd>{{ selected.detail.supporting_agents.map(agentLabel).join('、') }}</dd>
            </template>
            <template v-if="selected.detail.routing_reason">
              <dt>原因</dt>
              <dd>{{ selected.detail.routing_reason }}</dd>
            </template>
          </dl>
        </div>

        <div class="section">
          <h3>处理过程</h3>
          <StageTrace
            :timings="selected.detail.stage_timings_ms || {}"
            :total="selected.detail.total_ms || 0"
            :degraded="selected.detail.degraded_stages || []"
          />
          <p v-if="selected.detail.degraded_stages?.length" class="notice" style="margin: 10px 0 0">
            {{ selected.detail.degraded_stages.map((s) => STAGE_LABELS[s] || s).join('、') }} 超时或出错，已按降级结果继续，回复没有被耽误。
          </p>
        </div>

        <div class="section">
          <h3>回答检查</h3>
          <div class="msg-meta">
            <span class="tag" :class="selected.detail.knowledge_used ? 'tag-indigo' : ''">
              {{ selected.detail.knowledge_used ? '用到了知识库' : '没有用知识库' }}
            </span>
            <span class="tag" :class="selected.detail.verified ? 'tag-ok' : 'tag-warn'">
              {{ selected.detail.verified ? '校验通过' : '校验未通过' }}
            </span>
            <span class="tag" :class="selected.detail.grounded ? 'tag-ok' : 'tag-warn'">
              {{ selected.detail.grounded ? '有依据' : '缺少依据' }}
            </span>
          </div>
        </div>

        <div v-if="selected.detail.escalated" class="section">
          <h3>转人工</h3>
          <p v-if="selected.detail.ticket_id">
            已创建工单
            <button class="tag tag-alert" @click="emit('open-ticket', selected.detail.ticket_id)">#{{ selected.detail.ticket_id }}</button>
          </p>
          <p v-else class="muted">需要人工处理，但工单没有创建成功，查看后端日志。</p>
        </div>

        <div class="section">
          <dl class="facts">
            <dt>请求 ID</dt>
            <dd>{{ selected.detail.request_id }}</dd>
            <dt>总耗时</dt>
            <dd>{{ ms(selected.detail.total_ms) }}</dd>
          </dl>
        </div>
      </template>

      <template v-else>
        <div class="section">
          <dl class="facts">
            <dt>意图</dt>
            <dd>{{ intentLabel(selected.intent) }}</dd>
            <dt>回答者</dt>
            <dd>{{ agentLabel(selected.agentType) }}</dd>
            <dt>耗时</dt>
            <dd>{{ ms(selected.latencyMs) }}</dd>
          </dl>
        </div>
        <p class="faint" style="padding: 0 16px 16px">这条回复产生于处理轨迹功能上线之前，只保存了意图、回答者和耗时。</p>
      </template>
    </aside>
  </div>
</template>
