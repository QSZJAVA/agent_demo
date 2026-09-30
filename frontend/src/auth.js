/**
 * 模拟登录态：当前用户 ID 存在 localStorage，切换后刷新页面数据
 */
const KEY = 'demo.currentUserId'
const SESSION = 'report.sessionToken'
const SESSION_USER = 'report.sessionUser'

export function getSessionToken() { return sessionStorage.getItem(SESSION) }
export function saveSession(token, user) { sessionStorage.setItem(SESSION, token); sessionStorage.setItem(SESSION_USER, user.userId); setCurrentUserId(user.userId) }
export function clearSession() { sessionStorage.removeItem(SESSION); sessionStorage.removeItem(SESSION_USER) }
export function authHeaders() {
  const token = getSessionToken()
  return token ? { Authorization: `Bearer ${token}` } : { 'X-User-Id': getCurrentUserId() }
}
export function sessionExpired(expectedToken) {
  if (arguments.length && getSessionToken() !== expectedToken) return false
  clearSession()
  window.dispatchEvent(new Event('session-expired'))
  return true
}

export function getCurrentUserId() {
  return (getSessionToken() && sessionStorage.getItem(SESSION_USER)) || localStorage.getItem(KEY) || 'user1'
}

export function setCurrentUserId(userId) {
  localStorage.setItem(KEY, userId)
}
