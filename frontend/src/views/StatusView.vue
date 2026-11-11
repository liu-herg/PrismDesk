<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../api.js'
import { agentLabel, ms, percent } from '../format.js'

const health = ref(null)
const monitor = ref(null)
const error = ref('')
const loading = ref(false)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [h, m] = await Promise.all([api.health(), api.monitor()])
    health.value = h
    monitor.value = m
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}
onMounted(load)

// agent 统计的键形如 billing_0：类型 + 实例序号
const agents = computed(() =>
  Object.entries(monitor.value?.agent_stats || health.value?.agents || {})
    .map(([key, s]) => {
      const i = key.lastIndexOf('_')
      return { key, type: key.slice(0, i), index: key.slice(i + 1), ...s }
    })
    .sort((a, b) => a.key.localeCompare(b.key))
)
const tools = computed(() => Object.entries(monitor.value?.tool_stats || {}))
const CIRCUIT = { closed: '正常', open: '已熔断', half_open: '试探恢复中' }
</script>

<template>
  <div class="page">
    <div class="wrap">
      <div class="page-head">
        <div>
          <h1>运行状态</h1>
          <p class="muted">
            <span class="status-dot" :class="{ bad: error || health?.status !== 'ok' }"></span>
            {{ error ? '后端连不上' : health?.status === 'ok' ? '后端运行正常' : '正在检查…' }}
          </p>
        </div>
        <button class="btn" :disabled="loading" @click="load">{{ loading ? '刷新中…' : '刷新' }}</button>
      </div>
      <p v-if="error" class="notice" style="margin: 0 0 16px">{{ error }}</p>

      <section class="card" style="margin-top: 0">
        <h2 style="margin-bottom: 4px">各 Agent 的表现</h2>
        <p class="faint" style="margin-bottom: 10px">路由分会根据成功率和耗时自动调整，表现差的实例分到的请求会变少。</p>
        <div style="overflow-x: auto">
          <table class="table">
            <thead>
              <tr>
                <th>Agent</th>
                <th class="num">处理次数</th>
                <th class="num">成功率</th>
                <th class="num">平均耗时</th>
                <th class="num">路由分</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="a in agents" :key="a.key">
                <td>{{ agentLabel(a.type) }} <span class="faint">实例 {{ Number(a.index) + 1 }}</span></td>
                <td class="num">{{ a.total }}</td>
                <td class="num">{{ a.total ? percent(a.success_rate) : '—' }}</td>
                <td class="num">{{ a.total ? ms(a.avg_ms) : '—' }}</td>
                <td class="num">{{ a.routing_score ?? '—' }}</td>
              </tr>
              <tr v-if="!agents.length">
                <td colspan="5" class="muted">还没有数据</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <div class="grid-2">
        <section class="card">
          <h2 style="margin-bottom: 10px">知识库检索</h2>
          <dl v-for="[name, t] in tools" :key="name" class="facts">
            <dt>调用次数</dt>
            <dd>{{ t.total }}</dd>
            <dt>成功率</dt>
            <dd>{{ t.total ? percent(t.success_rate) : '—' }}</dd>
            <dt>平均耗时</dt>
            <dd>{{ t.total ? ms(t.avg_latency_ms) : '—' }}</dd>
            <dt>熔断器</dt>
            <dd>
              <span class="tag" :class="t.circuit_state === 'closed' ? 'tag-ok' : 'tag-alert'">
                {{ CIRCUIT[t.circuit_state] || t.circuit_state }}
              </span>
            </dd>
          </dl>
        </section>

        <section class="card">
          <h2 style="margin-bottom: 10px">告警与建议</h2>
          <p v-if="!monitor?.active_alerts?.length && !monitor?.suggestions?.length" class="muted">目前没有告警。</p>
          <div v-for="(a, i) in monitor?.active_alerts || []" :key="'a' + i" class="result">
            <span class="tag tag-alert">{{ a.metric }}</span>
            <p>当前 {{ a.value }}，阈值 {{ a.threshold }}</p>
          </div>
          <div v-for="(s, i) in monitor?.suggestions || []" :key="'s' + i" class="result">
            <strong>{{ s.title }}</strong>
            <p>{{ s.action }}</p>
          </div>
        </section>
      </div>
    </div>
  </div>
</template>
