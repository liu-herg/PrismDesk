<script setup>
import { computed } from 'vue'
import { ms } from '../format.js'

// 把一轮对话的各环节耗时按真实先后关系画在同一条时间轴上：
// 记忆 → [意图识别 ‖ 知识库检索 并行] → 生成 → 校验 → 落库 → 建单。
// 意图和知识库从同一时刻开始，所以并行时两条会叠在同一段时间里。
const props = defineProps({
  timings: { type: Object, default: () => ({}) },
  total: { type: Number, default: 0 },
  degraded: { type: Array, default: () => [] }
})

const STAGES = [
  { key: 'memory', label: '记忆' },
  { key: 'intent', label: '意图', parallel: true },
  { key: 'knowledge', label: '知识库', parallel: true },
  { key: 'generate', label: '生成' },
  { key: 'verify', label: '校验' },
  { key: 'persist', label: '落库' },
  { key: 'ticket', label: '建单' }
]

const layout = computed(() => {
  const t = props.timings || {}
  const has = (k) => t[k] !== undefined && t[k] !== null
  const rows = []
  let cursor = 0
  if (has('memory')) {
    rows.push({ ...STAGES[0], start: 0, dur: t.memory })
    cursor = t.memory
  }
  const groupStart = cursor
  const intent = has('intent') ? t.intent : null
  const knowledge = has('knowledge') ? t.knowledge : null
  if (intent !== null) rows.push({ ...STAGES[1], start: groupStart, dur: intent })
  if (knowledge !== null) rows.push({ ...STAGES[2], start: groupStart, dur: knowledge })
  const groupEnd = groupStart + Math.max(intent || 0, knowledge || 0)
  cursor = groupEnd
  for (const stage of STAGES.slice(3)) {
    if (!has(stage.key)) continue
    rows.push({ ...stage, start: cursor, dur: t[stage.key] })
    cursor += t[stage.key]
  }
  const span = Math.max(props.total || 0, cursor, 1)
  const parallel = intent !== null && knowledge !== null
  return {
    span,
    parallel,
    band: parallel ? { left: (groupStart / span) * 100, width: ((groupEnd - groupStart) / span) * 100 } : null,
    saved: parallel ? Math.min(intent, knowledge) : 0,
    rows: rows.map((r) => ({
      ...r,
      left: (r.start / span) * 100,
      width: Math.max((r.dur / span) * 100, 0.6),
      degraded: props.degraded.includes(r.key)
    }))
  }
})
</script>

<template>
  <div class="spectrum" role="img" :aria-label="`处理过程，总耗时 ${ms(layout.span)}`">
    <div v-for="row in layout.rows" :key="row.key" class="spectrum-row">
      <span>{{ row.label }}</span>
      <div class="spectrum-track">
        <div
          v-if="row.parallel && layout.band"
          class="spectrum-band"
          :style="{ left: layout.band.left + '%', width: layout.band.width + '%' }"
        ></div>
        <div
          class="spectrum-bar"
          :class="{ degraded: row.degraded }"
          :style="{ left: row.left + '%', width: row.width + '%', background: `var(--s-${row.key})` }"
          :title="`${row.label}：${ms(row.dur)}${row.degraded ? '（已降级）' : ''}`"
        ></div>
        <span
          class="spectrum-value"
          :style="row.left + row.width > 70
            ? { right: (100 - row.left) + '%', paddingRight: '6px', paddingLeft: 0 }
            : { left: row.left + row.width + '%' }"
        >{{ ms(row.dur) }}{{ row.degraded ? ' 已降级' : '' }}</span>
      </div>
    </div>
    <div class="spectrum-axis"><span>0</span><span>{{ ms(layout.span) }}</span></div>
    <p v-if="layout.parallel" class="faint">
      虚线框里的意图识别和知识库检索同时进行，比依次执行省下约 {{ ms(layout.saved) }}。
    </p>
  </div>
</template>
