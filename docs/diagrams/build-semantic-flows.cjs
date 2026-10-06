// 最终演示基线图表生成器：一个数据源同步生成HTML、SVG、Mermaid与draw.io。
const fs=require('node:fs'),path=require('node:path');
function writeOutput(file, content) {
  if(process.argv.includes('--check')) {
    if(!fs.existsSync(file) || fs.readFileSync(file,'utf8')!==content) throw Error('Stale generated artifact: '+file);
  } else fs.writeFileSync(file,content);
}
const out=path.join(__dirname,'demo-final');
fs.mkdirSync(out,{recursive:true});
const charts=[
  {
    "name": "01 系统与信任边界",
    "note": "最终演示基线：real,mcp · active · V1；业务字段来自授权目录。",
    "nodes": [
      {
        "id": "ui",
        "col": 1,
        "row": 0,
        "kind": "ui",
        "title": "浏览器登录与报表工作台",
        "lines": [
          "用户会话与业务权限"
        ]
      },
      {
        "id": "api",
        "col": 1,
        "row": 1,
        "kind": "agent",
        "title": "Agent REST / SSE :8080",
        "lines": [
          "会话、目录、规则、预览与清单"
        ]
      },
      {
        "id": "model",
        "col": 0,
        "row": 2,
        "kind": "model",
        "title": "真实模型 · MODEL",
        "lines": [
          "仅输出意图或调查工具提议"
        ]
      },
      {
        "id": "semantic",
        "col": 1,
        "row": 2,
        "kind": "agent",
        "title": "V1 语义与确定性规划",
        "lines": [
          "字段类型、原文证据、集合运算"
        ]
      },
      {
        "id": "investigate",
        "col": 2,
        "row": 2,
        "kind": "agent",
        "title": "独立异常调查入口",
        "lines": [
          "有界上下文与六个只读工具"
        ]
      },
      {
        "id": "state",
        "col": 1,
        "row": 3,
        "kind": "store",
        "title": "MySQL + Redis",
        "lines": [
          "单一 V1 空库基线 · 38 表 / 456 字段",
          "状态、字段快照、租约、版本"
        ]
      },
      {
        "id": "mcp",
        "col": 1,
        "row": 4,
        "kind": "business",
        "title": "HTTP MCP :8090",
        "lines": [
          "独立服务凭据及用户授权"
        ]
      },
      {
        "id": "source",
        "col": 1,
        "row": 5,
        "kind": "store",
        "title": "受控来源事实与业务事务",
        "lines": [
          "本仓库示例表；外部ERP待接入"
        ]
      }
    ],
    "edges": [
      [
        "ui",
        "api"
      ],
      [
        "api",
        "semantic"
      ],
      [
        "api",
        "investigate"
      ],
      [
        "semantic",
        "model"
      ],
      [
        "investigate",
        "model"
      ],
      [
        "semantic",
        "state"
      ],
      [
        "investigate",
        "state"
      ],
      [
        "semantic",
        "mcp"
      ],
      [
        "investigate",
        "mcp",
        "只读"
      ],
      [
        "mcp",
        "source"
      ],
      [
        "mcp",
        "state",
        "确认与版本复核"
      ]
    ]
  },
  {
    "name": "02 模型解析与校验",
    "note": "模型不生成SQL或执行请求；脱敏草稿加汇总诊断辅助唯一一次结构修正。",
    "nodes": [
      {
        "id": "in",
        "col": 1,
        "row": 0,
        "kind": "ui",
        "title": "本轮用户原话",
        "lines": [
          "查询、记录选择或生成清单"
        ]
      },
      {
        "id": "context",
        "col": 1,
        "row": 1,
        "kind": "agent",
        "title": "构造有界受控上下文",
        "lines": [
          "当前状态、授权报表及字段元数据"
        ]
      },
      {
        "id": "model",
        "col": 1,
        "row": 2,
        "kind": "model",
        "title": "ModelIntentParser → MODEL",
        "lines": [
          "V1 Schema、稳定规则与抽象对照",
          "明确凭据阻断、上下文统一脱敏"
        ]
      },
      {
        "id": "codec",
        "col": 1,
        "row": 3,
        "kind": "guard",
        "title": "IntentCodec 校验",
        "lines": [
          "结构、类型、原文证据、禁止条件"
        ]
      },
      {
        "id": "retry",
        "col": 0,
        "row": 3,
        "kind": "model",
        "title": "最多一次修正",
        "lines": [
          "将具体校验错误交回模型"
        ]
      },
      {
        "id": "draft",
        "col": 1,
        "row": 4,
        "kind": "guard",
        "title": "SemanticPlanner 草稿校验",
        "lines": [
          "覆盖、实体角色与范围一致性"
        ]
      },
      {
        "id": "fail",
        "col": 2,
        "row": 4,
        "kind": "error",
        "title": "澄清 / 拒绝 / 失败",
        "lines": [
          "不提交部分选择、不自动建单"
        ]
      },
      {
        "id": "apply",
        "col": 1,
        "row": 5,
        "kind": "agent",
        "title": "确定性业务流程",
        "lines": [
          "范围 → 快照 → 选择 → 待确认清单"
        ]
      }
    ],
    "edges": [
      [
        "in",
        "context"
      ],
      [
        "context",
        "model"
      ],
      [
        "model",
        "codec"
      ],
      [
        "codec",
        "retry",
        "首次失败"
      ],
      [
        "retry",
        "model"
      ],
      [
        "codec",
        "draft",
        "合格"
      ],
      [
        "draft",
        "retry",
        "首次矛盾"
      ],
      [
        "codec",
        "fail",
        "再次失败"
      ],
      [
        "draft",
        "fail",
        "业务拒绝"
      ],
      [
        "draft",
        "apply",
        "合格"
      ]
    ]
  },
  {
    "name": "03 配置字段与实体选择",
    "note": "筛选只在完整授权快照内执行；报表限定不等于替换查询范围。",
    "nodes": [
      {
        "id": "change",
        "col": 1,
        "row": 0,
        "kind": "agent",
        "title": "RECORDS 操作",
        "lines": [
          "EXCLUDE / RESTORE / KEEP_ONLY等"
        ]
      },
      {
        "id": "scope",
        "col": 1,
        "row": 1,
        "kind": "guard",
        "title": "按 reportMentions 限定记录",
        "lines": [
          "校验报表权限及当前预览"
        ]
      },
      {
        "id": "fields",
        "col": 0,
        "row": 2,
        "kind": "agent",
        "title": "FIELDS 条件编译",
        "lines": [
          "目录字段白名单、类型、允许操作"
        ]
      },
      {
        "id": "entity",
        "col": 2,
        "row": 2,
        "kind": "agent",
        "title": "DOCUMENT / DESCRIPTION",
        "lines": [
          "或 COUNTERPARTY 稳定客户关联"
        ]
      },
      {
        "id": "eval",
        "col": 0,
        "row": 3,
        "kind": "guard",
        "title": "有界AND / OR条件求值",
        "lines": [
          "精确十进制、ISO日期、文本和布尔"
        ]
      },
      {
        "id": "unique",
        "col": 2,
        "row": 3,
        "kind": "guard",
        "title": "唯一实体与数量校验",
        "lines": [
          "ALL不能跨同名客户合并"
        ]
      },
      {
        "id": "complete",
        "col": 1,
        "row": 4,
        "kind": "guard",
        "title": "完整快照及整轮原子性",
        "lines": [
          "任一项失败不提交部分排除"
        ]
      },
      {
        "id": "commit",
        "col": 1,
        "row": 5,
        "kind": "store",
        "title": "更新复合记录键集合",
        "lines": [
          "限定报表之外的选择保持"
        ]
      },
      {
        "id": "refuse",
        "col": 2,
        "row": 5,
        "kind": "error",
        "title": "未知字段 / 歧义 / 缺失事实",
        "lines": [
          "明确提示并保留恢复路径"
        ]
      }
    ],
    "edges": [
      [
        "change",
        "scope"
      ],
      [
        "scope",
        "fields",
        "字段条件"
      ],
      [
        "scope",
        "entity",
        "实体选择"
      ],
      [
        "fields",
        "eval"
      ],
      [
        "entity",
        "unique"
      ],
      [
        "eval",
        "complete"
      ],
      [
        "unique",
        "complete"
      ],
      [
        "complete",
        "commit",
        "全部成功"
      ],
      [
        "complete",
        "refuse",
        "失败"
      ]
    ]
  },
  {
    "name": "04 预览、字段快照与恢复",
    "note": "当前版本运行期状态可恢复；旧版本数据不转换。",
    "nodes": [
      {
        "id": "desired",
        "col": 1,
        "row": 0,
        "kind": "agent",
        "title": "desired 与 effective 分开",
        "lines": [
          "最近请求范围 / 最近成功范围"
        ]
      },
      {
        "id": "reuse",
        "col": 1,
        "row": 1,
        "kind": "guard",
        "title": "范围相同且预览有效？",
        "lines": [
          "纯记录操作复用原预览"
        ]
      },
      {
        "id": "query",
        "col": 0,
        "row": 2,
        "kind": "business",
        "title": "按权限与规则查询",
        "lines": [
          "每页500条；总量10万、120秒",
          "异常整次失败，保留旧预览/清单"
        ]
      },
      {
        "id": "snapshot",
        "col": 1,
        "row": 3,
        "kind": "store",
        "title": "冻结预览",
        "lines": [
          "客户实体 + FieldFact字段快照"
        ]
      },
      {
        "id": "selection",
        "col": 1,
        "row": 4,
        "kind": "guard",
        "title": "选择绑定预览ID与记录键",
        "lines": [
          "预览权限校验 + 手动选择CAS保存",
          "只保留须排他限定；不动须UNCHANGED"
        ]
      },
      {
        "id": "pending",
        "col": 2,
        "row": 3,
        "kind": "error",
        "title": "未完成请求独立标记",
        "lines": [
          "不污染已确定公司与报表"
        ]
      },
      {
        "id": "resume",
        "col": 2,
        "row": 4,
        "kind": "agent",
        "title": "明确新操作可继续",
        "lines": [
          "含糊的直接建单仍被阻止"
        ]
      },
      {
        "id": "reload",
        "col": 1,
        "row": 5,
        "kind": "ui",
        "title": "刷新 / 重启 / 会话恢复",
        "lines": [
          "重读权威状态，不重新猜选择"
        ]
      }
    ],
    "edges": [
      [
        "desired",
        "reuse"
      ],
      [
        "reuse",
        "query",
        "需新查询"
      ],
      [
        "query",
        "snapshot"
      ],
      [
        "reuse",
        "selection",
        "可复用"
      ],
      [
        "snapshot",
        "selection"
      ],
      [
        "selection",
        "reload",
        "成功"
      ],
      [
        "selection",
        "pending",
        "失败"
      ],
      [
        "pending",
        "resume"
      ],
      [
        "resume",
        "selection",
        "明确纠正"
      ]
    ]
  },
  {
    "name": "05 确认与持久执行任务",
    "note": "自然语言只生成PENDING清单；用户确认通过独立REST任务入口。",
    "nodes": [
      {
        "id": "plan",
        "col": 1,
        "row": 0,
        "kind": "store",
        "title": "生成待确认清单",
        "lines": [
          "复制字段与客户快照、固定排除项"
        ]
      },
      {
        "id": "confirm",
        "col": 1,
        "row": 1,
        "kind": "ui",
        "title": "用户核对并点击确认",
        "lines": [
          "持久任务 + 稳定幂等键"
        ]
      },
      {
        "id": "gate",
        "col": 1,
        "row": 2,
        "kind": "guard",
        "title": "归属、权限、TTL、版本",
        "lines": [
          "按租户认领并冻结执行版本"
        ]
      },
      {
        "id": "intent",
        "col": 1,
        "row": 3,
        "kind": "store",
        "title": "逐条持久化发送意图",
        "lines": [
          "稳定requestId、UNKNOWN及审计"
        ]
      },
      {
        "id": "mcp",
        "col": 1,
        "row": 4,
        "kind": "business",
        "title": "dispatch_submit",
        "lines": [
          "服务认证与用户当前权限"
        ]
      },
      {
        "id": "lock",
        "col": 1,
        "row": 5,
        "kind": "guard",
        "title": "业务事务与锁定复核",
        "lines": [
          "行锁内复核全部确认字段及版本",
          "字段变化要求重新查询确认"
        ]
      },
      {
        "id": "write",
        "col": 1,
        "row": 6,
        "kind": "store",
        "title": "原子提交状态与请求结果",
        "lines": [
          "相同请求号及负载可重放"
        ]
      },
      {
        "id": "result",
        "col": 1,
        "row": 7,
        "kind": "ui",
        "title": "持久结果回到页面",
        "lines": [
          "刷新与断线不导致重新派单"
        ]
      }
    ],
    "edges": [
      [
        "plan",
        "confirm"
      ],
      [
        "confirm",
        "gate"
      ],
      [
        "gate",
        "intent"
      ],
      [
        "intent",
        "mcp"
      ],
      [
        "mcp",
        "lock"
      ],
      [
        "lock",
        "write"
      ],
      [
        "write",
        "result"
      ]
    ]
  },
  {
    "name": "06 未知结果、核对与重试",
    "note": "超时不等于失败；不得换新请求号绕过不确定结果。",
    "nodes": [
      {
        "id": "result",
        "col": 1,
        "row": 0,
        "kind": "guard",
        "title": "读取条目持久结果",
        "lines": []
      },
      {
        "id": "success",
        "col": 0,
        "row": 1,
        "kind": "store",
        "title": "SUCCESS",
        "lines": [
          "重放原结果，不重复写入"
        ]
      },
      {
        "id": "unknown",
        "col": 1,
        "row": 1,
        "kind": "store",
        "title": "UNKNOWN / REVIEW_REQUIRED",
        "lines": [
          "先按原请求号核对"
        ]
      },
      {
        "id": "failed",
        "col": 2,
        "row": 1,
        "kind": "store",
        "title": "明确FAILED",
        "lines": [
          "用户显式请求重试"
        ]
      },
      {
        "id": "lookup",
        "col": 1,
        "row": 2,
        "kind": "business",
        "title": "dispatch_lookup",
        "lines": [
          "当前权限与原操作者检查"
        ]
      },
      {
        "id": "retry",
        "col": 2,
        "row": 3,
        "kind": "guard",
        "title": "复核当前规则和执行权",
        "lines": [
          "沿用稳定请求号与冻结版本"
        ]
      },
      {
        "id": "remain",
        "col": 0,
        "row": 3,
        "kind": "error",
        "title": "仍不确定或权限不足",
        "lines": [
          "保留证据，不能自动重发"
        ]
      },
      {
        "id": "execute",
        "col": 1,
        "row": 4,
        "kind": "business",
        "title": "受控执行 / 结果归档",
        "lines": [
          "幂等与版本保护继续生效"
        ]
      }
    ],
    "edges": [
      [
        "result",
        "success"
      ],
      [
        "result",
        "unknown"
      ],
      [
        "result",
        "failed"
      ],
      [
        "unknown",
        "lookup"
      ],
      [
        "lookup",
        "success",
        "已成功"
      ],
      [
        "lookup",
        "remain",
        "仍不确定"
      ],
      [
        "lookup",
        "retry",
        "可确认重试"
      ],
      [
        "failed",
        "retry"
      ],
      [
        "retry",
        "execute"
      ]
    ]
  },
  {
    "name": "07 异常调查与证据",
    "note": "调查为独立只读流程，不修改派单选择、清单或来源数据。",
    "nodes": [
      {
        "id": "ui",
        "col": 1,
        "row": 0,
        "kind": "ui",
        "title": "用户显式发起异常调查",
        "lines": []
      },
      {
        "id": "run",
        "col": 1,
        "row": 1,
        "kind": "store",
        "title": "登记调查运行与租约",
        "lines": [
          "任务预算、取消及恢复"
        ]
      },
      {
        "id": "context",
        "col": 1,
        "row": 2,
        "kind": "agent",
        "title": "有界上下文与证据索引",
        "lines": [
          "当前授权目录、步骤与已验证证据"
        ]
      },
      {
        "id": "model",
        "col": 1,
        "row": 3,
        "kind": "model",
        "title": "真实模型提出只读工具请求",
        "lines": [
          "应用控制执行循环与调用预算"
        ]
      },
      {
        "id": "tools",
        "col": 1,
        "row": 4,
        "kind": "guard",
        "title": "参数与权限校验后调用",
        "lines": [
          "六个受控只读工具 / HTTP MCP",
          "出站消息统一脱敏，配对ID保留"
        ]
      },
      {
        "id": "facts",
        "col": 1,
        "row": 5,
        "kind": "store",
        "title": "保存步骤、事实与引用",
        "lines": [
          "缺失事实不能编造为结论"
        ]
      },
      {
        "id": "report",
        "col": 1,
        "row": 6,
        "kind": "ui",
        "title": "证据支持的调查报告",
        "lines": [
          "覆盖不足明确说明，不触发派单"
        ]
      }
    ],
    "edges": [
      [
        "ui",
        "run"
      ],
      [
        "run",
        "context"
      ],
      [
        "context",
        "model"
      ],
      [
        "model",
        "tools"
      ],
      [
        "tools",
        "facts"
      ],
      [
        "facts",
        "context",
        "下一步"
      ],
      [
        "facts",
        "report",
        "结束或预算边界"
      ]
    ]
  },
  {
    "name": "08 最终演示与验收路径",
    "note": "固定功能基线与真实证据范围；最终演示版不等于生产验收。",
    "nodes": [
      {
        "id": "manifest",
        "col": 1,
        "row": 0,
        "kind": "store",
        "title": "demo-baseline.json",
        "lines": [
          "V1 / real,mcp / active",
          "原生 JSON Schema"
        ]
      },
      {
        "id": "start",
        "col": 1,
        "row": 1,
        "kind": "agent",
        "title": "start-app 或 start-mcp",
        "lines": [
          "业务服务就绪后再启动Agent"
        ]
      },
      {
        "id": "prepare",
        "col": 1,
        "row": 2,
        "kind": "guard",
        "title": "prepare-demo 一次准备",
        "lines": [
          "核实专用库、幂等补充数据及账号"
        ]
      },
      {
        "id": "preview",
        "col": 1,
        "row": 3,
        "kind": "ui",
        "title": "授权A公司查询九条记录",
        "lines": [
          "销售3、应收4、费用2"
        ]
      },
      {
        "id": "amount",
        "col": 1,
        "row": 4,
        "kind": "agent",
        "title": "销售金额大于96000不要",
        "lines": [
          "仅排除128000，保留等号边界"
        ]
      },
      {
        "id": "document",
        "col": 1,
        "row": 5,
        "kind": "agent",
        "title": "再排除SO2026002",
        "lines": [
          "保持三类报表，剩余七条"
        ]
      },
      {
        "id": "verify",
        "col": 1,
        "row": 6,
        "kind": "guard",
        "title": "14步真实HTTP回放与浏览器",
        "lines": [
          "重启恢复、字段快照及待确认清单"
        ]
      },
      {
        "id": "limits",
        "col": 1,
        "row": 7,
        "kind": "error",
        "title": "证据边界如实保留",
        "lines": [
          "外部ERP、正式认证与生产验收待完成"
        ]
      }
    ],
    "edges": [
      [
        "manifest",
        "start"
      ],
      [
        "start",
        "prepare"
      ],
      [
        "prepare",
        "preview"
      ],
      [
        "preview",
        "amount"
      ],
      [
        "amount",
        "document"
      ],
      [
        "document",
        "verify"
      ],
      [
        "verify",
        "limits"
      ]
    ]
  }
];
const colors={ui:['#eaf2ff','#386bc1'],agent:['#edf5ff','#3778a8'],model:['#f3edff','#8751bf'],business:['#e8f7ef','#268255'],guard:['#fff4dc','#b58022'],error:['#fff0ef','#ba534e'],store:['#edf1f5','#60768b']};
const esc=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&apos;'}[c]));
function wrap(s,max=33){let chunks=[],line='',count=0;for(const ch of s){const weight=ch.charCodeAt(0)>255?1.7:1;if(count+weight>max){chunks.push(line);line='';count=0;}line+=ch;count+=weight;}if(line)chunks.push(line);return chunks;}
function geom(node){return {x:60+node.col*410,y:135+node.row*165,w:340,h:110};}
function points(a,b){
  const A=geom(a),B=geom(b);
  if(a.col===b.col && b.row===a.row+1)return [[A.x+A.w/2,A.y+A.h],[B.x+B.w/2,B.y]];
  if(a.row===b.row && Math.abs(a.col-b.col)>1){
    // 跨越整列时从节点上方绕行，避免穿过中间节点。
    const sx=A.x+A.w/2,ex=B.x+B.w/2,lane=A.y-25;
    return [[sx,A.y],[sx,lane],[ex,lane],[ex,B.y]];
  }
  if(a.col!==b.col && b.row>a.row+1){
    const right=b.col>a.col,sx=A.x+A.w/2+(right?65:-65),sy=A.y+A.h-(a.kind==='guard'?65*A.h/A.w:0);
    const lane=right?B.x-24:B.x+B.w+24,ey=B.y+B.h/2,ex=right?B.x:B.x+B.w;
    return [[sx,sy],[sx,A.y+A.h+27],[lane,A.y+A.h+27],[lane,ey],[ex,ey]];
  }
  if(a.col!==b.col && b.row>a.row){
    // 菱形分支必须落在斜边上，不能从包围矩形底边悬空起线。
    const sx=A.x+A.w/2+(b.col>a.col?65:-65),sy=A.y+A.h-(a.kind==='guard'?65*A.h/A.w:0),ex=B.x+B.w/2,ey=B.y,mid=(A.y+A.h+ey)/2;
    return [[sx,sy],[sx,mid],[ex,mid],[ex,ey]];
  }
  if(a.col===b.col){const lane=A.x-28;return [[A.x,A.y+A.h/2],[lane,A.y+A.h/2],[lane,B.y+B.h/2],[B.x,B.y+B.h/2]];}
  const right=b.col>a.col,sx=right?A.x+A.w:A.x,ex=right?B.x:B.x+B.w,sy=A.y+A.h/2,ey=B.y+B.h/2,lane=right?sx+35:sx-35;
  return [[sx,sy],[lane,sy],[lane,ey],[ex,ey]];
}
function render(chart,index){const W=1340,H=300+Math.max(...chart.nodes.map(x=>x.row))*165;const byId=Object.fromEntries(chart.nodes.map(x=>[x.id,x]));let parts=[`<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}" role="img" aria-label="${esc(chart.name)}"><defs><marker id="arrow${index}" markerWidth="10" markerHeight="8" refX="9" refY="4" orient="auto"><path d="M0,0 L10,4 L0,8 Z" fill="#657891"/></marker></defs><rect width="100%" height="100%" fill="#fff"/><g font-family="Microsoft YaHei,PingFang SC,Arial,sans-serif"><text x="60" y="44" font-size="26" font-weight="700" fill="#15334b">${esc(chart.name)}</text><text x="60" y="79" font-size="14" fill="#53697e">${esc(chart.note)}</text>`];
for(const [from,to,label=''] of chart.edges){if(!byId[from]||!byId[to])throw Error('Unknown edge');const p=points(byId[from],byId[to]);const d=p.map((v,i)=>(i?'L':'M')+v.join(',')).join(' ');parts.push(`<path d="${d}" fill="none" stroke="#657891" stroke-width="1.7" marker-end="url(#arrow${index})"/>`);if(label){const A=p.length>2?p[1]:p[0],B=p.length>2?p[2]:p[1],x=(A[0]+B[0])/2,y=(A[1]+B[1])/2-8;const width=label.length*13+10;parts.push(`<rect x="${x-width/2}" y="${y-14}" width="${width}" height="20" fill="white" rx="3"/><text x="${x}" y="${y}" font-size="12" text-anchor="middle" fill="#53697e">${esc(label)}</text>`);}}
for(const node of chart.nodes){const g=geom(node),[fill,stroke]=colors[node.kind];if(node.kind==='guard')parts.push(`<polygon points="${g.x+g.w/2},${g.y} ${g.x+g.w},${g.y+g.h/2} ${g.x+g.w/2},${g.y+g.h} ${g.x},${g.y+g.h/2}" fill="${fill}" stroke="${stroke}" stroke-width="1.5"/>`);else parts.push(`<rect x="${g.x}" y="${g.y}" width="${g.w}" height="${g.h}" rx="12" fill="${fill}" stroke="${stroke}" stroke-width="1.5"/>`);const texts=[...wrap(node.title),...node.lines.flatMap(s=>wrap(s))];if(texts.length>5)throw Error('Node text too long: '+node.id);texts.forEach((line,i)=>parts.push(`<text x="${g.x+g.w/2}" y="${g.y+g.h/2-(texts.length-1)*10+i*20+5}" text-anchor="middle" font-size="${i===0?15:13}" font-weight="${i===0?700:400}" fill="#1e354c">${esc(line)}</text>`));}
parts.push(`<text x="60" y="${H-26}" font-size="12" fill="#64748b">2026-10-06 · 依据当前工作区源码 · 模型解析 / 确定性业务 / 实际派单边界分离</text></g></svg>`);return parts.join('');}
function mermaid(chart){return 'flowchart TD\n'+chart.nodes.map(node=>{const text=[node.title,...node.lines].join('<br/>').replaceAll('"','&quot;');return `  ${node.id}${node.kind==='guard'?'{"'+text+'"}':'["'+text+'"]'}:::${node.kind}`;}).join('\n')+'\n'+chart.edges.map(([a,b,label])=>`  ${a} -->${label?'|"'+label+'"|':''} ${b}`).join('\n')+'\n'+Object.entries(colors).map(([k,[f,s]])=>`  classDef ${k} fill:${f},stroke:${s},color:#1e354c`).join('\n');}
const svgs=charts.map(render);
charts.forEach((chart,i)=>{writeOutput(path.join(out,`0${i+1}.svg`),svgs[i]);writeOutput(path.join(out,`0${i+1}.mmd`),mermaid(chart));});
let xml='<mxfile host="app.diagrams.net" modified="2026-10-06T00:00:00.000Z" agent="Codex" version="26.0.0">';
charts.forEach((chart,index)=>{xml+=`<diagram id="semantic-${index+1}" name="${esc(chart.name)}"><mxGraphModel dx="1340" dy="2000" grid="1" gridSize="10" page="0"><root><mxCell id="0"/><mxCell id="1" parent="0"/>`;for(const node of chart.nodes){const g=geom(node),[fill,stroke]=colors[node.kind],value=[`<b>${node.title}</b>`,...node.lines].join('<br>');xml+=`<mxCell id="${node.id}" value="${esc(value)}" style="${node.kind==='guard'?'rhombus;':'rounded=1;'}whiteSpace=wrap;html=1;fillColor=${fill};strokeColor=${stroke};fontColor=#1e354c;fontFamily=Microsoft YaHei;fontSize=14;" vertex="1" parent="1"><mxGeometry x="${g.x}" y="${g.y}" width="${g.w}" height="${g.h}" as="geometry"/></mxCell>`;}chart.edges.forEach(([a,b,label=''],i)=>{xml+=`<mxCell id="e${i}" value="${esc(label)}" style="edgeStyle=orthogonalEdgeStyle;rounded=0;html=1;endArrow=block;endFill=1;strokeColor=#657891;fontFamily=Microsoft YaHei;fontSize=12;" edge="1" parent="1" source="${a}" target="${b}"><mxGeometry relative="1" as="geometry"/></mxCell>`;});xml+='</root></mxGraphModel></diagram>';});xml+='</mxfile>';writeOutput(path.join(out,'demo-final.drawio'),xml);
writeOutput(path.join(out,'flows.json'),JSON.stringify(charts,null,2));
const html=`<!doctype html><html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>用户输入与语义 V1 完整流程</title><style>*{box-sizing:border-box}body{margin:0;font-family:"Microsoft YaHei",sans-serif;color:#1d354a;background:#f1f5f9}header{padding:24px 32px;background:#102a43;color:white}header h1{font-size:25px;margin:0 0 10px}header p{margin:0;color:#c3d4e7;font-size:14px}.bar{position:sticky;top:0;z-index:3;display:flex;gap:8px;flex-wrap:wrap;background:white;padding:12px 20px;border-bottom:1px solid #d7e2ed}button,a{font:inherit}button{cursor:pointer;border:1px solid #c5d5e5;background:#fff;border-radius:7px;padding:8px 11px;color:#214b70}button.active{background:#214b70;color:white}.zoom{padding:10px 24px;display:flex;align-items:center;gap:10px;background:#e8eef5}main{padding:22px;overflow:auto}.chart{background:white;max-width:1800px;margin:auto;box-shadow:0 2px 12px #102a4310}.chart svg{display:block;width:100%;height:auto}.hidden{display:none}small{color:#52687a}a{color:#27649d}.legend{display:flex;gap:14px;flex-wrap:wrap;margin-left:auto;font-size:12px}.legend span{padding:4px 7px;border-radius:4px}.summary{padding:14px 24px;background:#fff;border-bottom:1px solid #d7e2ed;line-height:1.7;font-size:14px}</style></head><body><header><h1>用户输入 → 结构化语义 → 权威状态 → 业务执行</h1><p>当前运行：active 语义 V1 · deepseek-v4.1-flash · native JSON Schema · MCP 双服务 · 2026-10-06</p></header><div class="summary">模型只解析本轮动作和范围变化；原文证据、实体映射、权限、规则、版本和实际派单由服务端负责。<br>八页可切换、缩放；下方节点颜色区分责任边界。金额、日期、文本和布尔筛选来自配置；未配置能力、排序和条数限制仍需澄清。<a href="demo-final.drawio" download>下载可编辑 draw.io</a> · <a href="../../语义V1配置字段筛选.md">详细说明与 JSON 示例</a></div><nav class="bar">${charts.map((c,i)=>`<button data-page="${i}" class="${i===0?'active':''}">${c.name}</button>`).join('')}</nav><div class="zoom"><button id="less">缩小</button><button id="fit">适配宽度</button><button id="more">放大</button><small id="percent">100%</small><div class="legend">${Object.entries({ui:'前端',agent:'Agent 服务',model:'模型',business:'MCP 业务',guard:'校验/判断',store:'持久状态',error:'拒绝/异常'}).map(([k,t])=>`<span style="background:${colors[k][0]};color:${colors[k][1]}">${t}</span>`).join('')}</div></div><main>${svgs.map((s,i)=>`<section class="chart ${i?'hidden':''}" data-chart="${i}">${s}</section>`).join('')}</main><script>let selected=0,zoom=1;const sections=[...document.querySelectorAll('[data-chart]')];function update(){sections.forEach((s,i)=>{s.classList.toggle('hidden',i!==selected);s.style.width=(100*zoom)+'%';s.style.maxWidth=zoom>1?'none':'1800px'});document.querySelectorAll('[data-page]').forEach(b=>b.classList.toggle('active',Number(b.dataset.page)===selected));document.querySelector('#percent').textContent=Math.round(zoom*100)+'%'}document.querySelectorAll('[data-page]').forEach(b=>b.onclick=()=>{selected=Number(b.dataset.page);zoom=1;update();window.scrollTo({top:0})});document.querySelector('#less').onclick=()=>{zoom=Math.max(.35,zoom-.15);update()};document.querySelector('#more').onclick=()=>{zoom=Math.min(2.5,zoom+.15);update()};document.querySelector('#fit').onclick=()=>{zoom=1;update()};</script></body></html>`;
writeOutput(path.join(out,'index.html'),html);
const fence=String.fromCharCode(96).repeat(3);
const sections=charts.map((chart,i)=>`## ${chart.name}\n\n${chart.note}\n\n${fence}mermaid\n${mermaid(chart)}\n${fence}\n`);
writeOutput(path.join(out,'流程图.md'),'# 当前用户输入和语义 V1 的完整流程图\n\n2026-10-06，按当前工作区代码绘制。配合 [详细说明](../../语义V1配置字段筛选.md) 阅读。\n\n'+sections.join('\n'));
console.log(`Generated ${charts.length} SVG/Mermaid pages, editable draw.io and standalone HTML in ${out}`);
