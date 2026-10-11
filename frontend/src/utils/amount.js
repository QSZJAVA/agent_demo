/**
 * 金额展示与求和的共同边界；当前接口只接受十进制字符串，禁止先转 Number 导致分位丢失。
 * 缺失和无效金额不能冒充零；内部使用整数及小数位数运算，返回值仍为可持久化的字符串。
 */

/** 解析当前金额协议；null 表示缺失，invalid 表示接口提供了非十进制字符串。 */
function parseAmount(value) {
  if (value === null || value === undefined || value === '') return null
  if (typeof value !== 'string' || !/^-?\d+(?:\.\d+)?$/.test(value)) return { invalid: true }
  const negative = value.startsWith('-')
  const [integer, fraction = ''] = (negative ? value.slice(1) : value).split('.')
  return { units: BigInt(`${negative ? '-' : ''}${integer}${fraction}`), scale: fraction.length }
}

/** 将定点整数还原为十进制字符串；不舍入，负零统一为零。 */
function decimalString(units, scale) {
  const negative = units < 0n
  const digits = (negative ? -units : units).toString().padStart(scale + 1, '0')
  const body = scale ? `${digits.slice(0, -scale)}.${digits.slice(-scale)}` : digits
  return `${negative ? '-' : ''}${body}`
}

/** 金额保留全部有效小数并至少显示两位；缺失显示破折号，错误协议明确显示不可用。 */
export function formatAmount(value) {
  const amount = parseAmount(value)
  if (amount === null) return '—'
  if (amount.invalid) return '金额格式异常'
  const [integer, fraction = ''] = decimalString(amount.units, amount.scale).split('.')
  return `${integer.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}.${fraction.padEnd(2, '0')}`
}

/**
 * 对一组当前协议金额做精确求和；同时返回缺失和无效计数，调用方不能把不完整金额标为完整合计。
 * @param {Array<string|null>} values 当前页金额，币种必须已由调用方限定为同一账本。
 * @returns {{amount: string|null, missingCount: number, invalidCount: number}} 已知金额之和；没有有效金额时 amount 为空。
 */
export function sumAmounts(values) {
  let units = 0n, scale = 0, count = 0, missingCount = 0, invalidCount = 0
  for (const value of values) {
    const amount = parseAmount(value)
    if (amount === null) { missingCount++; continue }
    if (amount.invalid) { invalidCount++; continue }
    const nextScale = Math.max(scale, amount.scale)
    units = units * 10n ** BigInt(nextScale - scale) + amount.units * 10n ** BigInt(nextScale - amount.scale)
    scale = nextScale
    count++
  }
  return { amount: count ? decimalString(units, scale) : null, missingCount, invalidCount }
}
