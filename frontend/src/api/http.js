/**
 * 普通 HTTP 请求边界：附加当前会话凭据、解包 Result 响应并统一展示错误。
 * 每个请求保存发送时的会话令牌，迟到的401只能清理对应旧会话，不能覆盖新登录状态。
 * 仅合法 Result 错误信封标记服务器业务结论；网络状态和不完整正文不能让页面误清待核对请求。
 */
import axios from 'axios'
import Message from 'element-ui/lib/message'
import { authHeaders, sessionExpired, getSessionToken } from '../auth'

const http = axios.create({
  baseURL: '/api',
  timeout: 30000
})

/**
 * 解码当前错误信封；失败 data 必须为 null，保留整数业务码和明确的服务器响应标记。
 * 页面可据此区分明确拒绝与传输未知；本方法不推断请求是否曾创建过历史任务。
 */
function serverFailure(body) {
  if (!body || typeof body !== 'object' || Array.isArray(body) || !Number.isInteger(body.code) || body.code <= 0 || typeof body.message !== 'string' || body.data !== null) return null
  const error = new Error(body.message || '请求失败')
  error.code = body.code
  error.serverResponse = true
  return error
}

// 请求头来自当前Demo会话；保存发送时令牌，供迟到的认证失败响应判断归属。
http.interceptors.request.use((config) => {
  config.sessionToken = getSessionToken()
  Object.assign(config.headers, authHeaders())
  return config
})

// 统一解包后端 Result 结构：{ code, message, data }
http.interceptors.response.use(
  (response) => {
    const body = response.data
    if (body && body.code === 0) return body.data
    const error = serverFailure(body)
    if (error) {
      if (error.code === 401) sessionExpired(response.config?.sessionToken)
      Message.error(error.message)
      return Promise.reject(error)
    }
    return Promise.reject(new Error('服务响应不符合当前接口协议'))
  },
  (error) => {
    const verified = serverFailure(error.response?.data)
    if (error.response?.status === 401 || verified?.code === 401) sessionExpired(error.config?.sessionToken)
    const failure = verified || error
    Message.error(failure.message || '网络异常，请确认后端服务已启动')
    return Promise.reject(failure)
  }
)

export default http
