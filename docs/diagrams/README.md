# 最终演示版流程图

当前图表基线包含 V1 派单规划、通用业务查询和固定演示工单，使用 `real,mcp`、`agent.semantic.mode=active` 和真实模型解析来源 MODEL。

- [十二页 HTML 阅读器](demo-final/index.html)
- [Mermaid 全文](demo-final/流程图.md)
- [可编辑 draw.io](demo-final/demo-final.drawio)
- [图表数据](demo-final/flows.json)
- SVG：[系统](demo-final/01.svg)、[模型解析](demo-final/02.svg)、[字段与实体](demo-final/03.svg)、[快照恢复](demo-final/04.svg)、[确认执行](demo-final/05.svg)、[核对重试](demo-final/06.svg)、[异常调查](demo-final/07.svg)、[演示验收](demo-final/08.svg)
- SVG：[通用查询](demo-final/09.svg)、[工单进度与总结](demo-final/10.svg)
- SVG：[手工派单与记录详情](demo-final/11.svg)、[运营治理](demo-final/12.svg)

2026-10-09 按当前代码同步统一任务契约、独立复核、字段词义证据、冻结期望、目标条数核对和来源边界。汇总不提供可派单记录引用；被业务查询打断的候选须先重新展示，查询与候选引用分别校验。完整任务不再进入第二次派单解释，发布查询卡片前再次核验权限；九条演示路径仍只适用于完整未派单基线。当前页面操作见[工作台指南](../工作台操作与运营治理.md)，验证范围及未完成事项见[接续清单](../review/统一任务编排接续清单_2026-10-09.md)，当前图表不代表整体验收通过；此前逐图依据保留在[2026-10-07 核对记录](../review/界面优化与文档流程核对_2026-10-07.md)。

生成：`node docs/diagrams/build-semantic-flows.cjs`。一致性检查：`node tools/check-demo-baseline.cjs`。旧图及其日期保存在 [历史目录](../history/README.md)，不作为当前操作说明。
