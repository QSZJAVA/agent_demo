/**
 * 普通 HTTP 请求边界：附加当前会话凭据、解包 Result 响应并统一展示错误。
 * 每个请求保存发送时的会话令牌，迟到的401只能清理对应旧会话，不能覆盖新登录状态。
 */
import axios from 'axios'
import Message from 'element-ui/lib/message'
import { authHeaders, sessionExpired, getSessionToken } from '../auth'

const http = axios.create({
  baseURL: '/api',
  timeout: 30000
})

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
    if (body && typeof body.code !== 'undefined') {
      if (body.code === 0) {
        return body.data
      }
      if (body.code === 401) sessionExpired(response.config?.sessionToken)
      Message.error(body.message || '请求失败')
      return Promise.reject(new Error(body.message || '请求失败'))
    }
    return body
  },
  (error) => {
    if (error.response && error.response.status === 401) sessionExpired(error.config?.sessionToken)
    Message.error(error.message || '网络异常，请确认后端服务已启动')
    return Promise.reject(error)
  }
)

export default http
