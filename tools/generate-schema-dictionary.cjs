/** 从当前基础与调查注释清单生成字典；只验收最新初始化结果，不提供旧数据转换。 */
const fs = require('node:fs')
const path = require('node:path')
const root = path.resolve(__dirname, '..')
const source = path.join(root, 'backend/src/main/resources/db/migration/V21__schema_comments.json')
const target = path.join(root, 'docs/db/表结构字典.md')
const specs = { ...JSON.parse(fs.readFileSync(source, 'utf8')),
  ...JSON.parse(fs.readFileSync(path.join(root, 'backend/src/main/resources/investigation/schema-comments.json'), 'utf8')) }
const escape = text => text.replaceAll('|', '\\|').replaceAll('\n', ' ')
const count = Object.values(specs).reduce((sum, table) => sum + Object.keys(table.columns).length, 0)
const lines = [
  '# 表结构字典', '',
  `基于当前基础表与调查表注释清单，覆盖 ${Object.keys(specs).length} 张仓库维护的表、${count} 个字段。本文由 \`node tools/generate-schema-dictionary.cjs\` 生成。`, '',
  '当前业务链路以 `real,mcp`、`agent.semantic.mode=active`、真实模型接口和带认证的 HTTP MCP 业务服务为基线。当前 Demo 账号存储仅描述既有实现，真实外部认证系统接入待完成；真实模型调用及本字典不代表外部 ERP 或生产部署已经验收。', '',
  '当前基础表注释由 V21 写入，调查表由 V22 初始化并携带完整注释；表结构验收以最新空库初始化为基线，不要求历史数据升级。外部系统维护的业务表、用户自定义扩展字段和 Flyway 管理表不属于此清单。调查新增时间为UTC，业务字段的既有时区语义逐字段说明。', '',
  '字段类型、长度、精度、默认值和索引以当前数据库的 `SHOW CREATE TABLE` 为准，本文记录业务语义。时间字段为数据库日期时间；耗时字段以毫秒计，版本和计数的语义逐字段说明。', ''
]
for (const [name, table] of Object.entries(specs)) {
  lines.push(`## ${name}`, '', table.comment + '。', '', '| 字段 | 数据库注释 |', '| --- | --- |')
  for (const [column, comment] of Object.entries(table.columns)) lines.push(`| \`${column}\` | ${escape(comment)} |`)
  lines.push('')
}
const text = lines.join('\n')
if (process.argv.includes('--check')) {
  if (!fs.existsSync(target) || fs.readFileSync(target, 'utf8') !== text) {
    console.error('表结构字典与注释清单不一致，请运行 node tools/generate-schema-dictionary.cjs')
    process.exit(1)
  }
} else {
  fs.mkdirSync(path.dirname(target), { recursive: true })
  fs.writeFileSync(target, text)
}
console.log(`表结构字典：${Object.keys(specs).length} 张表，${count} 个字段`)
