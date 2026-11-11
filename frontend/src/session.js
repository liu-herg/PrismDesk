// 登录状态。
// 访问令牌只放内存（刷新页面就没了，靠刷新令牌换回来）；刷新令牌放 localStorage，关掉浏览器再打开不用重新登录。
// 取舍：localStorage 能被页面上的脚本读到，如果站点有 XSS 漏洞刷新令牌会泄露；
// 更稳妥的做法是放进 HttpOnly Cookie，但那样要处理 CSRF。本地演示项目选了更简单的方案，并在后端做了单次使用 + 可吊销。
import { reactive } from 'vue'

const REFRESH_KEY = 'prismdesk.refreshToken'

export const session = reactive({
  user: null, // { username, display_name, role }
  accessToken: '',
  accessExpiresAt: 0, // Unix 秒
  ready: false // 启动时恢复登录的尝试是否已经结束
})

function readRefresh() {
  try {
    return localStorage.getItem(REFRESH_KEY) || ''
  } catch {
    return ''
  }
}

function writeRefresh(token) {
  try {
    if (token) localStorage.setItem(REFRESH_KEY, token)
    else localStorage.removeItem(REFRESH_KEY)
  } catch {
    // 隐私模式下不可用：只是刷新页面后需要重新登录
  }
}

export function applyLogin(result) {
  session.user = result.user
  session.accessToken = result.access_token
  session.accessExpiresAt = result.access_expires_at
  writeRefresh(result.refresh_token)
}

export function clearSession() {
  session.user = null
  session.accessToken = ''
  session.accessExpiresAt = 0
  writeRefresh('')
}

// 同一时刻只发一个刷新请求：几个接口同时发现令牌过期时，共用这一次刷新（刷新令牌是单次使用的，并发刷新只有一个会成功）
let refreshing = null

export function refreshSession() {
  if (refreshing) return refreshing
  const token = readRefresh()
  if (!token) return Promise.resolve(false)
  refreshing = fetch('/auth/refresh', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refresh_token: token })
  })
    .then(async (res) => {
      if (!res.ok) {
        // 只有明确被拒（令牌失效）才清空；网络错误时保留，下次再试
        if (res.status === 401 || res.status === 403) clearSession()
        return false
      }
      applyLogin(await res.json())
      return true
    })
    .catch(() => false)
    .finally(() => {
      refreshing = null
    })
  return refreshing
}

/** 访问令牌还有不到 60 秒就过期时，先换一个新的。 */
export async function freshAccessToken() {
  if (session.accessToken && session.accessExpiresAt - Date.now() / 1000 > 60) return session.accessToken
  const ok = await refreshSession()
  return ok ? session.accessToken : ''
}

export function currentRefreshToken() {
  return readRefresh()
}

export const isStaff = () => session.user && (session.user.role === 'AGENT' || session.user.role === 'ADMIN')
export const isAdmin = () => session.user && session.user.role === 'ADMIN'
