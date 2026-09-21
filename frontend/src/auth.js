/**
 * 模拟登录态：当前用户 ID 存在 localStorage，切换后刷新页面数据
 */
const KEY = 'demo.currentUserId'

export function getCurrentUserId() {
  return localStorage.getItem(KEY) || 'user1'
}

export function setCurrentUserId(userId) {
  localStorage.setItem(KEY, userId)
}
