/** 生成通用语义任务编排方案的 draw.io 流程图；图中待实施能力不作为运行或验收证据。 */
const fs = require('node:fs')
const path = require('node:path')
const root = path.resolve(__dirname, '..')
const target = path.join(root, 'docs/diagrams/semantic-task/semantic-task.drawio')
const escape = value => String(value).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;')
const nodes = [
  ['title', '通用语义任务编排方案 · 待实施设计\n真实模型 MODEL / 认证 HTTP MCP / 2026-10-08', 80, 20, 1260, 70, 'text;html=0;fontSize=24;align=center;fontStyle=1;'],
  ['input', '用户本轮要求\n询问、操作、禁止、条件与指代', 510, 125, 340, 80],
  ['context', '授权目录与可靠上下文\n字段含义、近期对话、对象引用\n来源、范围、版本、展示完整性', 80, 270, 330, 120],
  ['plan', '统一模型规划 MODEL\n动作 + 对象 + 条件 + 数量\n本轮依据 + 步骤依赖', 510, 270, 340, 120],
  ['review', '语义复核 MODEL\n是否遗漏、错指、误解否定\n有界修正，不执行业务', 1010, 270, 330, 120],
  ['guard', '业务契约与当前事实校验\n权限、类型、引用绑定、完整性\n规则、状态、租约与版本', 510, 465, 340, 110],
  ['clarify', '具体澄清或失败说明\n只询问缺失信息\n保留可靠对象及已完成步骤', 1010, 465, 330, 110],
  ['read', '明确只读动作\n查询、统计、详情、资格核验', 80, 650, 330, 90],
  ['prepare', '明确准备派单\n引用目标 → 重新核验\n生成准确的待确认清单', 510, 650, 340, 110],
  ['confirm', '用户核对并确认\n确认绑定清单与版本', 510, 845, 340, 90],
  ['execute', '受控执行\n确认、权限、幂等、租约\n来源及执行版本再次校验', 510, 1010, 340, 110],
  ['evidence', '持久化事实与任务进度\n每步对象、范围、结果及依据\n可恢复、可核对、可追溯', 1010, 820, 330, 120],
  ['boundary', '对象引用可延续；资格与执行授权必须按当前业务状态核验。\n真实模型调用不代表已经完成外部 ERP 或生产部署验收。', 80, 1190, 1260, 75, 'text;html=0;fontSize=16;align=center;']
]
const edges = [
  ['input', 'plan', ''], ['context', 'plan', '提供授权事实'], ['plan', 'review', '计划与依据'],
  ['review', 'plan', '发现偏差：有界修正'], ['review', 'guard', '语义复核通过'], ['guard', 'clarify', '对象不明、条件矛盾或前置失败'],
  ['guard', 'read', '只读'], ['guard', 'prepare', '准备清单'], ['read', 'evidence', '保存结果与引用'],
  ['prepare', 'confirm', '待确认'], ['confirm', 'execute', '用户确认'], ['execute', 'evidence', '执行结果'], ['clarify', 'evidence', '记录具体缺项']
]
const cells = ['<mxCell id="0"/>', '<mxCell id="1" parent="0"/>']
for (const [id, label, x, y, width, height, style] of nodes) cells.push(`<mxCell id="${id}" value="${escape(label)}" style="${style || 'rounded=1;whiteSpace=wrap;html=0;fillColor=#edf4fb;strokeColor=#6488ad;fontSize=16;fontFamily=Microsoft YaHei;spacing=12;'}" vertex="1" parent="1"><mxGeometry x="${x}" y="${y}" width="${width}" height="${height}" as="geometry"/></mxCell>`)
edges.forEach(([from, to, label], i) => cells.push(`<mxCell id="edge-${i}" value="${escape(label)}" style="edgeStyle=orthogonalEdgeStyle;rounded=0;html=0;endArrow=block;fontSize=13;strokeColor=#5c7087;" edge="1" parent="1" source="${from}" target="${to}"><mxGeometry relative="1" as="geometry"/></mxCell>`))
fs.mkdirSync(path.dirname(target), { recursive: true })
fs.writeFileSync(target, `<?xml version="1.0" encoding="UTF-8"?>\n<mxfile host="app.diagrams.net"><diagram id="semantic-task" name="通用任务编排方案"><mxGraphModel page="1" pageWidth="1420" pageHeight="1320"><root>${cells.join('\n')}</root></mxGraphModel></diagram></mxfile>\n`, 'utf8')
console.log('已生成 docs/diagrams/semantic-task/semantic-task.drawio（待实施方案）')
