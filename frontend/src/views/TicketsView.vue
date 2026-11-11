<script setup>
import { computed, ref, watch } from 'vue'
import { api } from '../api.js'
import { session } from '../session.js'
import { PRIORITY, TICKET_ACTIONS, TICKET_STATUS, intentLabel, timeLabel } from '../format.js'

const props = defineProps({ ticketId: String, noticeTick: Number })
const emit = defineEmits(['select', 'open-conversation', 'changed'])

const FILTERS = [
  { value: 'pending', label: '待分配' },
  { value: 'in_progress', label: '处理中' },
  { value: 'resolved', label: '已解决' },
  { value: 'closed', label: '已关闭' },
  { value: '', label: '全部' }
]
const filter = ref('pending')
const mine = ref(false)
const items = ref([])
const nextBeforeId = ref(null)
const listError = ref('')
const loadingList = ref(false)

async function loadList() {
  loadingList.value = true
  listError.value = ''
  try {
    const page = await api.tickets({ status: filter.value, assignee: mine.value ? me.value : '' })
    items.value = page.items
    nextBeforeId.value = page.next_before_id
  } catch (e) {
    listError.value = e.message
  } finally {
    loadingList.value = false
  }
}
async function loadMore() {
  try {
    const page = await api.tickets({
      status: filter.value,
      assignee: mine.value ? me.value : '',
      beforeId: nextBeforeId.value
    })
    items.value = [...items.value, ...page.items]
    nextBeforeId.value = page.next_before_id
  } catch (e) {
    listError.value = e.message
  }
}
const me = computed(() => session.user?.username)
watch([filter, mine], loadList, { immediate: true })

// 别人（或者另一个窗口里的自己）改了工单：刷新列表；正在看的那张也刷新，避免基于过期内容操作
watch(
  () => props.noticeTick,
  async () => {
    loadList()
    if (ticket.value && !busy.value) {
      try {
        const latest = await api.ticket(ticket.value.id)
        if (latest.version !== ticket.value.version) ticket.value = latest
      } catch {
        // 忽略
      }
    }
  }
)

// ---------- 详情 ----------
const ticket = ref(null)
const detailError = ref('')
const actionError = ref('')
const actionDone = ref('')
const note = ref('')
const busy = ref(false)

async function loadTicket(id) {
  ticket.value = null
  detailError.value = ''
  actionError.value = ''
  actionDone.value = ''
  note.value = ''
  if (!id) return
  try {
    ticket.value = await api.ticket(id)
  } catch (e) {
    detailError.value = e.message
  }
}
watch(() => props.ticketId, loadTicket, { immediate: true })

const isMine = computed(() => ticket.value?.assignee && ticket.value.assignee === me.value)

// 每个状态下可以做的操作（和后端状态机一致）；release / resolve 只有当前处理人能做
const available = computed(() => {
  const t = ticket.value
  if (!t) return []
  switch (t.status) {
    case 'pending':
      return [{ action: 'claim', primary: true }, { action: 'close' }]
    case 'in_progress':
      return isMine.value ? [{ action: 'resolve', primary: true }, { action: 'release' }] : []
    case 'resolved':
      return [{ action: 'close', primary: true }, { action: 'reopen' }]
    default:
      return []
  }
})

async function act(action) {
  if (!ticket.value || busy.value) return
  busy.value = true
  actionError.value = ''
  actionDone.value = ''
  try {
    // 带上页面上看到的版本号：如果这期间别人改过这张工单，后端返回 409，不会覆盖别人的操作
    await api.ticketAction(ticket.value.id, action, {
      version: ticket.value.version,
      note: note.value.trim()
    })
    actionDone.value = `已${TICKET_ACTIONS[action]}`
    note.value = ''
    ticket.value = await api.ticket(ticket.value.id)
    emit('changed')
    loadList()
  } catch (e) {
    actionError.value =
      e.status === 409 ? `${e.message}。工单已经刷新为最新状态。` : e.message
    if (e.status === 409 || e.status === 403) {
      try {
        ticket.value = await api.ticket(ticket.value.id)
      } catch {
        // 刷新失败保持原样
      }
      loadList()
      emit('changed')
    }
  } finally {
    busy.value = false
  }
}

function statusTag(status) {
  return { pending: 'tag-alert', in_progress: 'tag-indigo', resolved: 'tag-ok', closed: '' }[status] || ''
}
function priorityTag(p) {
  return p === 'CRITICAL' || p === 'HIGH' ? 'tag-alert' : p === 'MEDIUM' ? 'tag-warn' : ''
}
</script>

<template>
  <div class="tickets">
    <section class="pane" aria-label="工单列表">
      <div class="pane-head">
        <h2>工单</h2>
        <label class="faint" style="display: flex; align-items: center; gap: 6px; cursor: pointer">
          <input v-model="mine" type="checkbox" /> 只看我的
        </label>
      </div>
      <div class="segmented" role="group" aria-label="按状态筛选">
        <button
          v-for="f in FILTERS"
          :key="f.value"
          :aria-pressed="String(filter === f.value)"
          @click="filter = f.value"
        >{{ f.label }}</button>
      </div>
      <div class="pane-body">
        <p v-if="listError" class="notice">{{ listError }}</p>
        <div v-else-if="!loadingList && !items.length" class="empty">
          <p>没有{{ FILTERS.find((f) => f.value === filter)?.label }}的工单。</p>
          <p class="faint">客户在对话里要求人工时会自动建单，这里会实时出现。</p>
        </div>
        <button
          v-for="t in items"
          :key="t.id"
          class="list-item"
          :aria-current="String(t.id) === String(ticketId) ? 'true' : undefined"
          @click="emit('select', t.id)"
        >
          <div class="row">
            <span class="title">#{{ t.id }} {{ t.reason }}</span>
            <span class="tag" :class="priorityTag(t.priority)">{{ PRIORITY[t.priority] || t.priority }}</span>
          </div>
          <div class="row faint">
            <span>{{ TICKET_STATUS[t.status] }}{{ t.assignee ? `，${t.assignee} 处理` : '' }}</span>
            <span>{{ timeLabel(t.created_at) }}</span>
          </div>
        </button>
        <div v-if="nextBeforeId" style="padding: 12px 16px">
          <button class="btn btn-small" @click="loadMore">加载更多</button>
        </div>
      </div>
    </section>

    <section class="ticket-detail" aria-label="工单详情">
      <div v-if="!ticketId" class="empty" style="height: 100%">
        <p>从左边选一张工单。</p>
        <p class="faint">演示抢单：另开一个浏览器窗口（或无痕窗口）登录 agent-02，两边同时认领同一张工单。</p>
      </div>
      <p v-else-if="detailError" class="notice">{{ detailError }}</p>

      <div v-else-if="ticket" class="wrap">
        <div class="ticket-title">
          <h1>工单 #{{ ticket.id }}</h1>
          <span class="tag" :class="statusTag(ticket.status)">{{ TICKET_STATUS[ticket.status] }}</span>
          <span class="tag" :class="priorityTag(ticket.priority)">紧急程度：{{ PRIORITY[ticket.priority] || ticket.priority }}</span>
        </div>
        <p class="muted">{{ ticket.reason }}</p>

        <div class="summary">{{ ticket.summary }}</div>

        <dl class="facts">
          <dt>客户</dt>
          <dd>{{ ticket.user_id }}</dd>
          <dt>会话</dt>
          <dd>
            <button
              class="btn btn-small"
              @click="emit('open-conversation', { userId: ticket.user_id, convId: ticket.conv_id })"
            >查看对话</button>
          </dd>
          <dt>意图</dt>
          <dd>{{ intentLabel(ticket.intent) }}</dd>
          <dt>处理人</dt>
          <dd>{{ ticket.assignee || '还没有人认领' }}</dd>
          <dt>版本</dt>
          <dd>v{{ ticket.version }}<span class="faint">（每次状态变化加 1，提交操作时会校验）</span></dd>
          <dt>创建时间</dt>
          <dd>{{ timeLabel(ticket.created_at) }}</dd>
        </dl>

        <div class="card">
          <h3 style="margin-bottom: 10px">处理</h3>
          <template v-if="available.length">
            <textarea v-model="note" class="field" rows="2" placeholder="备注（可选），会写进操作记录"></textarea>
            <div class="actions" style="margin-top: 10px">
              <button
                v-for="a in available"
                :key="a.action"
                class="btn"
                :class="{ 'btn-primary': a.primary, 'btn-danger': a.action === 'close' && !a.primary }"
                :disabled="busy"
                @click="act(a.action)"
              >{{ TICKET_ACTIONS[a.action] }}</button>
              <span class="faint">以 {{ session.user.display_name }} 的身份操作</span>
            </div>
          </template>
          <p v-else-if="ticket.status === 'in_progress'" class="muted">
            {{ ticket.assignee }} 正在处理，只有处理人能退回或解决。
          </p>
          <p v-else class="muted">工单已关闭，不能再操作。</p>
          <p v-if="actionError" class="notice" style="margin: 10px 0 0">{{ actionError }}</p>
          <p v-if="actionDone" class="notice notice-ok" style="margin: 10px 0 0">{{ actionDone }}</p>
        </div>

        <div class="card">
          <h3 style="margin-bottom: 12px">操作记录</h3>
          <ol class="timeline">
            <li v-for="(e, i) in ticket.events" :key="i">
              <div>
                <strong>{{ e.operator }}</strong> {{ TICKET_ACTIONS[e.action] || e.action }}
                <span class="faint">
                  {{ e.from ? `${TICKET_STATUS[e.from]} → ` : '' }}{{ TICKET_STATUS[e.to] }}，{{ timeLabel(e.at) }}
                </span>
              </div>
              <p v-if="e.note" class="muted">{{ e.note }}</p>
            </li>
          </ol>
        </div>
      </div>
    </section>
  </div>
</template>
