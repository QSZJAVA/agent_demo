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

/** 会话里全部预览 / 清单卡片的当前状态（以服务端为准，刷新页面、换设备后一致） */
export function fetchCardStates(conversationId) {
  return http.get(`/agent/conversations/${conversationId}/card-states`)
}

/** 在报表选择卡片上选定报表后创建预览（服务端重新按权限校验） */
export function createPreview({ conversationId, reportIds, companyCode, excludeDocNos, scopeMode }) {
  return http.post('/dispatch/previews', { conversationId, reportIds, companyCode, excludeDocNos, scopeMode })
}

export function fetchPreviewItems(previewId, page = 1, size = 50) {
  return http.get(`/dispatch/previews/${previewId}/items`, { params: { page, size } })
}

export function startPreviewJob(request) {
  return http.post('/dispatch/previews/jobs', request)
}

export function fetchPreviewJob(jobId) {
  return http.get(`/dispatch/previews/jobs/${jobId}`)
}

export function fetchLatestPreviewJob(conversationId) {
  return http.get('/dispatch/previews/jobs', { params: { conversationId } })
}

export function cancelPreviewJob(jobId) {
  return http.post(`/dispatch/previews/jobs/${jobId}/cancel`)
}

export function fetchPreview(previewId) {
  return http.get(`/dispatch/previews/${previewId}`)
}

export function fetchPlanItems(planId, page = 1, size = 50) {
  return http.get(`/dispatch/plans/${planId}/items`, { params: { page, size } })
}

/** 确认执行待确认清单；重复确认返回第一次的结果 */
export function confirmPlan(planId) {
  return http.post(`/dispatch/plans/${planId}/confirm`)
}

export function retryFailedPlan(planId) {
  return http.post(`/dispatch/plans/${planId}/retry-failed`)
}

export function reconcilePlan(planId) {
  return http.post(`/dispatch/plans/${planId}/reconcile`)
}

export function cancelPlan(planId) {
  return http.post(`/dispatch/plans/${planId}/cancel`)
}

/**
 * 对话（SSE over fetch）。axios 不支持流式响应，这里用原生 fetch 读 ReadableStream。
 * onEvent(type, data) 逐个事件回调；返回 abort 函数。
 * excludeDocNos 是 previewId 那张预览卡片上取消勾选的单据号，服务端只在派单用的正是这张预览时才采用。
 */
export function streamChat({ conversationId, message, excludeDocNos, excludedRecords, previewId }, onEvent) {
  const controller = new AbortController()
  const run = async () => {
    const response = await fetch('/api/agent/chat', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
        'X-User-Id': getCurrentUserId()
      },
      body: JSON.stringify({ conversationId, message, excludeDocNos, excludedRecords, previewId }),
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
    let requestId = null
    let lastSeq = -1
    let completed = false
    const deliver = (block) => {
      const event = parseBlock(block)
      if (!event) return
      if (event.id) {
        const separator = event.id.lastIndexOf(':')
        const currentRequest = event.id.slice(0, separator)
        const seq = Number(event.id.slice(separator + 1))
        if (separator < 1 || !Number.isSafeInteger(seq) || seq < 0) throw new Error('事件序号无效，请刷新会话')
        if (requestId && requestId !== currentRequest) throw new Error('事件请求编号变化，请刷新会话')
        requestId = currentRequest
        if (seq <= lastSeq) return
        if (seq !== lastSeq + 1) throw new Error('事件缺失，请刷新会话')
        lastSeq = seq
      }
      if (event.type === 'done') completed = true
      onEvent(event.type, event.data)
    }
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
        deliver(block)
      }
    }
    if (buffer.trim()) {
      deliver(buffer)
    }
    if (!completed) throw new Error('连接中断，正在恢复会话结果')
  }
  const promise = run()
  return { promise, abort: () => controller.abort() }
}

function parseBlock(block) {
  let type = 'message'
  let id = null
  const dataLines = []
  block.split('\n').forEach((line) => {
    if (line.startsWith('event:')) {
      type = line.slice(6).trim()
    } else if (line.startsWith('id:')) {
      id = line.slice(3).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).replace(/^ /, ''))
    }
  })
  if (!dataLines.length) return null
  const raw = dataLines.join('\n')
  let data = raw
  try {
    data = JSON.parse(raw)
  } catch (e) {
    // 纯文本
  }
  return { id, type, data }
}
