/** 金额协议、精确求和及三类业务组件的回归；不以浮点近似或合成数据代替真实链路验收。 */
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
const amounts = {}
vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../src/utils/amount.js'), 'utf8').replace(/export function/g, 'function'), amounts)
const { formatAmount, sumAmounts } = amounts

test('金额显示保留分位、负数和全部精度，不舍入合法的大额值', () => {
  const cases = [
    ['90071992547409.93', '90,071,992,547,409.93'],
    ['9999999999999999.99', '9,999,999,999,999,999.99'],
    ['-90071992547409.93', '-90,071,992,547,409.93'],
    ['0', '0.00'], ['0.01', '0.01'], ['-0.00', '0.00'],
    ['1.005', '1.005'], ['0.0000001', '0.0000001']
  ]
  for (const [input, expected] of cases) assert.equal(formatAmount(input), expected)
})

test('缺失金额与零区分，数字或科学计数法不能绕过当前字符串契约', () => {
  for (const value of [null, undefined, '']) assert.equal(formatAmount(value), '—')
  for (const value of [0, 1.25, NaN, Infinity, '1e3', '1,000', 'abc', {}, ' 1.00 ']) {
    assert.equal(formatAmount(value), '金额格式异常')
  }
})

test('精确求和覆盖大额加分、小数相加、负数和不同小数位数', () => {
  const cases = [
    [['90071992547409.93', '0.01'], '90071992547409.94'],
    [['9999999999999999.99', '9999999999999999.99'], '19999999999999999.98'],
    [['0.10', '0.20'], '0.30'],
    [['-90071992547409.93', '0.01'], '-90071992547409.92'],
    [['0.0009', '0.01', '-1.005'], '-0.9941']
  ]
  for (const [values, expected] of cases) {
    const result = sumAmounts(values)
    assert.equal(result.amount, expected)
    assert.equal(result.missingCount, 0)
    assert.equal(result.invalidCount, 0)
  }
})

test('无效和缺失金额带独立计数，不会静默计作零或完整合计', () => {
  const result = sumAmounts(['1.00', null, '', 2.5, 'wrong'])
  assert.equal(result.amount, '1.00')
  assert.equal(result.missingCount, 2)
  assert.equal(result.invalidCount, 2)
  assert.equal(sumAmounts([]).amount, null)
  assert.equal(sumAmounts([null]).amount, null)
})

test('144 组正负分位边界按独立整数分账本核对求和', () => {
  const cents = [-999999999999999999n, -9007199254740993n, -10001n, -101n, -1n, 0n, 1n, 99n, 100n, 10001n, 9007199254740993n, 999999999999999999n]
  const text = value => {
    const sign = value < 0n ? '-' : '', n = value < 0n ? -value : value
    return `${sign}${n / 100n}.${String(n % 100n).padStart(2, '0')}`
  }
  for (const a of cents) for (const b of cents) {
    assert.equal(sumAmounts([text(a), text(b)]).amount, text(a + b))
  }
})

function component(name) {
  const source = fs.readFileSync(path.join(__dirname, '../src/components', name + '.vue'), 'utf8')
    .match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/import[\s\S]*?from\s+['"][^'"]+['"]/g, '').replace('export default', 'result =')
  const context = { result: null, DispatchTrace: {}, formatAmount, sumAmounts }
  vm.runInNewContext(source, context)
  return context.result
}

test('普通报表、候选预览及清单对 HTTP 和历史 JSON 回读金额保持一致', () => {
  const record = JSON.parse(JSON.stringify({ amount: '90071992547409.93' }))
  for (const name of ['ReportTable', 'agent/PreviewCard', 'agent/PlanCard']) {
    const c = component(name)
    assert.equal(c.methods.formatAmount(record.amount), '90,071,992,547,409.93')
    assert.equal(c.methods.formatAmount(null), '—')
  }
})

test('报表合计展示精确总额，空页和未提供金额不会显示伪造的零合计', () => {
  const c = component('ReportTable')
  const state = { columns: [{ prop: 'amount', isAmount: true }], data: [], formatAmount }
  const summary = () => c.methods.summaryMethod.call(state, { columns: [{}, {}, {}] })[2]
  assert.equal(summary(), '—')
  state.data = [{ amount: '90071992547409.93' }, { amount: '0.01' }]
  assert.equal(summary(), '90,071,992,547,409.94')
  state.data = [{ amount: '1.00' }, { amount: null }]
  assert.equal(summary(), '金额不完整（1 条未提供）')
  state.data = [{ amount: 1.25 }]
  assert.equal(summary(), '金额格式异常')
})
