/** 静态注释门禁：检查生产类型、实体字段、record组件、前端模块及初始化脚本；数据库真实覆盖由集成回归验证。 */
const fs = require('node:fs')
const path = require('node:path')
const { spawnSync } = require('node:child_process')
const root = path.resolve(__dirname, '..')
const read = file => fs.readFileSync(path.join(root, file), 'utf8')
const walk = dir => fs.readdirSync(path.join(root, dir), { withFileTypes: true })
  .flatMap(entry => entry.isDirectory() ? walk(`${dir}/${entry.name}`) : [`${dir}/${entry.name}`])
const problems = []
let types = 0, entityFields = 0, requestFields = 0, records = 0, modules = 0

/** 仅接受紧邻声明的Javadoc，允许框架注解位于文档与声明之间。 */
function precedingDoc(source, offset) {
  let prefix = source.slice(0, source.lastIndexOf('\n', offset) + 1)
  prefix = prefix.replace(/(?:\s*@[^\n]+\n)+\s*$/, '').trimEnd()
  if (!prefix.endsWith('*/')) return ''
  const start = prefix.lastIndexOf('/**')
  return start < 0 ? '' : prefix.slice(start)
}

/** 泛型内的逗号属于类型参数，不能误当作record组件分隔符。 */
function components(signature) {
  let depth = 0, part = ''
  const parts = []
  for (const char of signature) {
    if (char === '<') depth++
    if (char === '>') depth--
    if (char === ',' && depth === 0) { parts.push(part); part = '' } else part += char
  }
  parts.push(part)
  return parts.map(value => value.trim().match(/\w+$/)?.[0]).filter(Boolean)
}

for (const file of [...walk('backend/src/main/java'), ...walk('business-service/src/main/java')].filter(file => file.endsWith('.java'))) {
  const source = read(file)
  const declaration = source.match(/public\s+(?:final\s+|abstract\s+)?(?:class|interface|record|enum)\s+(\w+)/)
  if (declaration) {
    types++
    if (!precedingDoc(source, declaration.index)) problems.push(`${file}: ${declaration[1]} 缺少职责Javadoc`)
  }
  if (file.includes('/entity/')) {
    for (const field of source.matchAll(/^\s*private\s+[\w<>?, ]+\s+(\w+)\s*(?:=.+)?;/gm)) {
      entityFields++
      const offset = field.index + field[0].search(/\S/)
      if (!precedingDoc(source, offset)) problems.push(`${file}: ${field[1]} 缺少字段Javadoc`)
    }
  }
  // 请求及维护表单都是只声明字段的DTO；避免把服务依赖字段误作业务入参。
  for (const form of source.matchAll(/public static class (\w+(?:Request|Form))\s*\{([\s\S]*?)^\s*\}/gm)) {
    const body = form[2]
    for (const field of body.matchAll(/^\s*private\s+[\w.<>?, ]+\s+(\w+)\s*(?:=.+)?;/gm)) {
      requestFields++
      const offset = field.index + field[0].search(/\S/)
      if (!precedingDoc(body, offset)) problems.push(`${file}: ${form[1]}.${field[1]} 缺少请求字段Javadoc`)
    }
  }
  for (const record of source.matchAll(/\brecord (\w+)(?:<[^>]+>)?\s*\(([\s\S]*?)\)\s*\{/g)) {
    const line = source.slice(source.lastIndexOf('\n', record.index) + 1, record.index)
    if (line.includes('private')) continue
    records++
    const doc = precedingDoc(source, record.index)
    for (const name of components(record[2])) {
      if (!new RegExp(`^\\s*\\*\\s+@param\\s+${name}\\s+\\S`, 'm').test(doc)) problems.push(`${file}: ${record[1]}.${name} 缺少 @param 说明`)
    }
  }
}
for (const file of walk('frontend/src').filter(file => /\.(js|vue)$/.test(file))) {
  const source = read(file), script = file.endsWith('.vue') ? source.split('<script>')[1]?.split('</script>')[0] : source
  modules++
  if (!script?.trimStart().startsWith('/**')) problems.push(`${file}: 缺少模块职责注释`)
}
const specs = JSON.parse(read('backend/src/main/resources/db/migration/V21__schema_comments.json'))
const demo = read('backend/src/main/resources/db/demo/demo-data.sql')
let columns = 0
for (const [name, table] of Object.entries(specs)) {
  if (!table.comment?.trim()) problems.push(`${name}: 表注释为空`)
  for (const [column, comment] of Object.entries(table.columns)) {
    columns++
    if (!comment?.trim() || comment.length > 1024) problems.push(`${name}.${column}: 注释为空或超过MySQL字段注释长度`)
  }
  if (!table.optional) continue
  const block = demo.match(new RegExp(`CREATE TABLE IF NOT EXISTS ${name}\\s*\\([\\s\\S]*?;`))?.[0] || ''
  const sqlLiteral = text => `'${text.replaceAll("'", "''")}'`
  if (!block.includes(`COMMENT ${sqlLiteral(table.comment)}`)) problems.push(`${name}: 初始化表注释与V21不一致`)
  for (const [column, comment] of Object.entries(table.columns)) {
    const line = block.split(/\r?\n/).find(line => new RegExp(`^\\s*${column}\\s+`).test(line)) || ''
    if (!line.includes(`COMMENT ${sqlLiteral(comment)}`)) problems.push(`${name}.${column}: 初始化字段注释与V21不一致`)
  }
}
const dictionary = spawnSync(process.execPath, [path.join(root, 'tools/generate-schema-dictionary.cjs'), '--check'], { encoding: 'utf8' })
if (dictionary.status !== 0) problems.push(dictionary.stderr?.trim() || '表结构字典检查失败')
if (problems.length) {
  console.error(problems.join('\n'))
  process.exit(1)
}
console.log(`注释检查通过：${types} 个生产类型、${entityFields} 个实体字段、${requestFields} 个请求/表单字段、${records} 个record、${modules} 个前端模块；${Object.keys(specs).length} 张表、${columns} 个字段清单。`)
