// PrismDesk 后端接口。前端和后端同源部署，路径直接写相对地址。
import { clearSession, currentRefreshToken, freshAccessToken, refreshSession, session } from './session.js'

export class ApiError extends Error {
  constructor(status, message) {
    super(message)
    this.status = status
  }
}

const FALLBACK = {
  400: '请求参数有误',
  401: '登录已过期，请重新登录',
  403: '当前账号没有权限执行这个操作',
  404: '要找的内容不存在',
  409: '状态已经变化（可能被别人处理了），刷新后再试',
  429: '操作太频繁，请稍后再试',
  500: '服务端出错了，查看后端日志了解原因',
  502: '后端服务暂时不可用',
  503: '后端服务暂时不可用'
}

async function send(path, { method = 'GET', body, form, accept, auth = true, signal } = {}) {
  const headers = {}
  if (accept) headers.Accept = accept
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (auth) {
    const token = await freshAccessToken()
    if (token) headers.Authorization = `Bearer ${token}`
  }
  try {
    return await fetch(path, { method, headers, body: form || (body !== undefined ? JSON.stringify(body) : undefined), signal })
  } catch (e) {
    if (e.name === 'AbortError') throw e
    throw new ApiError(0, '连不上后端服务，请确认 PrismDesk 已经启动')
  }
}

async function errorOf(response) {
  const text = await response.text()
  let data = null
  try {
    data = text ? JSON.parse(text) : null
  } catch {
    data = text
  }
  const fromServer = data && typeof data === 'object' ? data.message : ''
  return new ApiError(response.status, fromServer || FALLBACK[response.status] || `请求失败（HTTP ${response.status}）`)
}

/** 普通 JSON 请求。遇到 401 先刷新一次令牌再重试；还是 401 就退出登录。 */
async function request(path, options = {}) {
  let response = await send(path, options)
  if (response.status === 401 && options.auth !== false && (await refreshSession())) {
    response = await send(path, options)
  }
  if (response.status === 401 && options.auth !== false) clearSession()
  if (!response.ok) throw await errorOf(response)
  const text = await response.text()
  return text ? JSON.parse(text) : null
}

/**
 * 读取 SSE 流（用 fetch 而不是 EventSource：EventSource 不能带 Authorization 头，
 * 只能把令牌放进 URL，会出现在服务器日志和浏览器历史里）。
 */
async function stream(path, options, onEvent) {
  let response = await send(path, { ...options, accept: 'text/event-stream' })
  if (response.status === 401 && (await refreshSession())) {
    response = await send(path, { ...options, accept: 'text/event-stream' })
  }
  if (response.status === 401) clearSession()
  if (!response.ok) throw await errorOf(response)

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  for (;;) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
    let index
    while ((index = buffer.indexOf('\n\n')) >= 0) {
      const block = buffer.slice(0, index)
      buffer = buffer.slice(index + 2)
      let name = 'message'
      const data = []
      for (const line of block.split('\n')) {
        if (line.startsWith(':')) continue // 心跳注释
        const colon = line.indexOf(':')
        const field = colon < 0 ? line : line.slice(0, colon)
        let content = colon < 0 ? '' : line.slice(colon + 1)
        if (content.startsWith(' ')) content = content.slice(1)
        if (field === 'event') name = content
        else if (field === 'data') data.push(content)
      }
      if (!data.length) continue
      let payload = data.join('\n')
      try {
        payload = JSON.parse(payload)
      } catch {
        // 不是 JSON 就原样给出
      }
      onEvent(name, payload)
    }
  }
}

const qs = (params) => {
  const p = new URLSearchParams()
  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') p.set(k, String(v))
  })
  const s = p.toString()
  return s ? `?${s}` : ''
}

export const api = {
  // 登录
  login: (username, password) => request('/auth/login', { method: 'POST', body: { username, password }, auth: false }),
  register: (username, password, displayName) =>
    request('/auth/register', { method: 'POST', body: { username, password, display_name: displayName }, auth: false }),
  logout: () =>
    request('/auth/logout', { method: 'POST', body: { refresh_token: currentRefreshToken() } }).catch(() => null),

  // 对话
  chatStream: (convId, message, onEvent) =>
    stream('/chat/stream', { method: 'POST', body: { message, conv_id: convId || undefined } }, onEvent),
  conversations: (userId, size = 50) => request(`/conversations${qs({ user_id: userId, size })}`),
  messages: (convId, beforeId, size = 40) =>
    request(`/conversations/${encodeURIComponent(convId)}/messages${qs({ before_id: beforeId, size })}`),

  // 实时通知
  notifications: (onEvent, signal) => stream('/notifications/stream', { signal }, onEvent),

  // 工单
  tickets: ({ status, assignee, beforeId, size = 50 } = {}) =>
    request(`/tickets${qs({ status, assignee, before_id: beforeId, size })}`),
  ticket: (id) => request(`/tickets/${id}`),
  ticketAction: (id, action, { version, note }) =>
    request(`/tickets/${id}/${action}`, { method: 'POST', body: { version, note: note || undefined } }),

  // 知识库
  knowledgeStats: () => request('/knowledge/stats'),
  search: (query, topK = 5) => request(`/search${qs({ query, topK })}`, { method: 'POST' }),
  addDocuments: (documents) => request('/knowledge/add', { method: 'POST', body: { documents } }),
  upload: (file) => {
    const form = new FormData()
    form.append('file', file)
    return request('/knowledge/upload', { method: 'POST', form })
  },

  // 运行状态
  health: () => request('/health', { auth: false }),
  monitor: () => request('/monitor'),

  // 账号管理
  users: () => request('/admin/users'),
  createUser: (user) => request('/admin/users', { method: 'POST', body: user }),
  setUserEnabled: (id, enabled) => request(`/admin/users/${id}`, { method: 'PATCH', body: { enabled } })
}

export { session }
