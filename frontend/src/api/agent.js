import http from './http'
import { getCurrentUserId } from '../auth'

/** demo 用户列表（模拟登录） */
export function fetchUsers() {
  return http.get('/auth/users')
}

export function fetchModel() {
  return http.get('/agent/model')
}

/** 会话列表 */
export function fetchConversations(page = 1, size = 50) {
  return http.get('/agent/conversations', { params: { page, size } })
}

/** 历史消息（按 id 正序返回文本与卡片） */
export function fetchMessages(conversationId, beforeId, size = 100) {
  return http.get(`/agent/conversations/${conversationId}/messages`, { params: { beforeId, size } })
}

export function renameConversation(conversationId, title) {
  return http.put(`/agent/conversations/${conversationId}/title`, { title })
}

export function deleteConversation(conversationId) {
  return http.delete(`/agent/conversations/${conversationId}`)
}

/** 预览快照全量记录 */
export function fetchPreview(previewId) {
  return http.get(`/agent/previews/${previewId}`)
}

/** 确认执行待确认清单 */
export function executePlan(planId) {
  return http.post(`/dispatch/plans/${planId}/execute`)
}

export function cancelPlan(planId) {
  return http.post(`/dispatch/plans/${planId}/cancel`)
}

/**
 * 对话（SSE over fetch）。axios 不支持流式响应，这里用原生 fetch 读 ReadableStream。
 * onEvent(type, data) 逐个事件回调；返回 abort 函数。
 */
export function streamChat({ conversationId, message, excludeDocNos }, onEvent) {
  const controller = new AbortController()
  const run = async () => {
    const response = await fetch('/api/agent/chat', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
        'X-User-Id': getCurrentUserId()
      },
      body: JSON.stringify({ conversationId, message, excludeDocNos }),
      signal: controller.signal
    })
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`)
    }
    const contentType = response.headers.get('content-type') || ''
    if (!contentType.includes('text/event-stream')) {
      // 后端在进入流之前就报错时返回的是 JSON Result
      const body = await response.json()
      throw new Error(body.message || '请求失败')
    }
    const reader = response.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    // eslint-disable-next-line no-constant-condition
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let idx
      // SSE 事件以空行分隔
      while ((idx = buffer.indexOf('\n\n')) >= 0) {
        const block = buffer.slice(0, idx)
        buffer = buffer.slice(idx + 2)
        parseBlock(block, onEvent)
      }
    }
    if (buffer.trim()) {
      parseBlock(buffer, onEvent)
    }
  }
  const promise = run()
  return { promise, abort: () => controller.abort() }
}

function parseBlock(block, onEvent) {
  let type = 'message'
  const dataLines = []
  block.split('\n').forEach((line) => {
    if (line.startsWith('event:')) {
      type = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).replace(/^ /, ''))
    }
  })
  if (!dataLines.length) return
  const raw = dataLines.join('\n')
  let data = raw
  try {
    data = JSON.parse(raw)
  } catch (e) {
    // 纯文本
  }
  onEvent(type, data)
}
