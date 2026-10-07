# 最终演示版流程图

当前图表基线包含 V1 派单规划、通用业务查询和固定演示工单，使用 `real,mcp`、`agent.semantic.mode=active` 和真实模型解析来源 MODEL。

- [十页 HTML 阅读器](demo-final/index.html)
- [Mermaid 全文](demo-final/流程图.md)
- [可编辑 draw.io](demo-final/demo-final.drawio)
- [图表数据](demo-final/flows.json)
- SVG：[系统](demo-final/01.svg)、[模型解析](demo-final/02.svg)、[字段与实体](demo-final/03.svg)、[快照恢复](demo-final/04.svg)、[确认执行](demo-final/05.svg)、[核对重试](demo-final/06.svg)、[异常调查](demo-final/07.svg)、[演示验收](demo-final/08.svg)
- SVG：[通用查询](demo-final/09.svg)、[工单进度与总结](demo-final/10.svg)

生成：`node docs/diagrams/build-semantic-flows.cjs`。一致性检查：`node tools/check-demo-baseline.cjs`。旧图及其日期保存在 [历史目录](../history/README.md)，不作为当前操作说明。
