/** 普通 HTTP 错误边界回归；仅合法 Result 错误信封携带服务器业务码，传输状态和不完整正文不能充当提交结论。 */
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')

function boundary() {
  let success, failure
  const messages = [], expired = []
  const context = {
    axios: { create: () => ({ interceptors: { request: { use() {} }, response: { use: (ok, fail) => { success = ok; failure = fail } } } }) },
    Message: { error: message => messages.push(message) }, authHeaders: () => ({}), getSessionToken: () => 'request-session', sessionExpired: token => expired.push(token)
  }
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../src/api/http.js'), 'utf8').replace(/^import .*$/gm, '').replace('export default http', ''), context)
  return { success, failure, messages, expired }
}

test('legitimate Result errors preserve numeric business codes and an explicit server response marker', async () => {
  const { success, failure } = boundary()
  for (const code of [403, 404, 409, 410, 422, 429, 503]) {
    const data = { code, message: '服务器拒绝或失败', data: null }
    for (const response of [() => success({ data }), () => failure({ response: { status: code, data }, message: 'HTTP 请求失败' })]) {
      await assert.rejects(response(), error => error.code === code && error.serverResponse === true && error.message === data.message)
    }
  }
})

test('HTTP status and malformed Result bodies never become verified server rejection markers', async () => {
  for (const data of [null, '<html>bad gateway</html>', { code: '422', message: 'wrong type', data: null }, { code: 422, message: 'missing data' }, { code: 422, message: [], data: null }, { code: 422, message: 'unexpected payload', data: {} }]) {
    const { success, failure } = boundary()
    const original = Object.assign(new Error('网络或协议失败'), { code: 422, response: { status: 422, data } })
    await assert.rejects(failure(original), error => error === original && error.serverResponse !== true)
    await assert.rejects(success({ data }), error => error.serverResponse !== true)
  }
})

test('network failures preserve their original identity without fabricating a server response', async () => {
  const { failure } = boundary(), original = Object.assign(new Error('timeout'), { code: 'ECONNABORTED' })
  await assert.rejects(failure(original), error => error === original && error.serverResponse !== true)
})
