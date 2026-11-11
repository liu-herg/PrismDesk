// 显示用的小工具

export function timeLabel(value) {
  if (!value) return ''
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return String(value)
  const now = new Date()
  const hm = `${pad(d.getHours())}:${pad(d.getMinutes())}`
  if (d.toDateString() === now.toDateString()) return hm
  return `${d.getMonth() + 1}月${d.getDate()}日 ${hm}`
}

export function ms(value) {
  if (value === undefined || value === null) return '—'
  const n = Number(value)
  return n >= 1000 ? `${(n / 1000).toFixed(n >= 10000 ? 0 : 1)} 秒` : `${Math.round(n)} 毫秒`
}

export function percent(value) {
  if (value === undefined || value === null || Number.isNaN(Number(value))) return '—'
  return `${Math.round(Number(value) * 100)}%`
}

const pad = (n) => String(n).padStart(2, '0')

export const INTENT_LABELS = {
  query: '咨询',
  complaint: '投诉',
  request: '办理请求',
  greeting: '打招呼',
  escalation: '升级处理',
  technical: '技术问题',
  billing: '账单',
  account: '账号',
  feedback: '反馈',
  order_status: '订单状态',
  logistics: '物流',
  refund: '退款',
  invoice: '发票',
  payment_issue: '支付问题',
  account_security: '账号安全',
  technical_login: '登录问题',
  technical_crash: '应用闪退',
  human_handoff: '要求人工',
  other: '其他'
}

export const AGENT_LABELS = {
  general: '通用 Agent',
  technical: '技术 Agent',
  billing: '账单 Agent',
  escalation: '升级 Agent'
}

export const intentLabel = (v) => INTENT_LABELS[String(v || '').toLowerCase()] || v || '—'
export const agentLabel = (v) => AGENT_LABELS[String(v || '').toLowerCase()] || v || '—'

export const TICKET_STATUS = {
  pending: '待分配',
  in_progress: '处理中',
  resolved: '已解决',
  closed: '已关闭'
}

export const TICKET_ACTIONS = {
  create: '创建',
  claim: '认领',
  release: '退回',
  resolve: '解决',
  close: '关闭',
  reopen: '重新打开'
}

export const PRIORITY = {
  LOW: '低',
  MEDIUM: '中',
  HIGH: '高',
  CRITICAL: '紧急'
}

export function readSetting(key, fallback) {
  try {
    return localStorage.getItem(`prismdesk.${key}`) || fallback
  } catch {
    return fallback
  }
}

export function writeSetting(key, value) {
  try {
    localStorage.setItem(`prismdesk.${key}`, value)
  } catch {
    // 隐私模式等情况下不可用，忽略
  }
}

export const ROLE_LABELS = {
  CUSTOMER: '客户',
  AGENT: '坐席',
  ADMIN: '管理员'
}

// 流式对话里各环节的说法（面向客户）
export const STAGE_LABELS = {
  memory: '读取上下文',
  intent: '理解你的问题',
  knowledge: '查找相关资料',
  generate: '组织回答',
  verify: '检查回答',
  persist: '保存记录',
  ticket: '转接人工'
}
