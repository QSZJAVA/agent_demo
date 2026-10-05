package com.example.report.investigation;

import java.util.*;

/** 由已验证结构化事实生成报告正文；模型不能通过自由文本声称成功、修复或重发，原始证据仍可独立查看。 */
public final class InvestigationReportText {
    private InvestigationReportText() { }
    public static final String SUMMARY="以下结论仅描述调查时取得的证据。本次调查只读，未执行修复、重发或状态核对写入。";
    public static final Map<String,String> TOPICS=Map.of(
            "RULE_CAUSALITY","仍需核查规则与异常的因果关系。",
            "EVENT_HISTORY","仍需核查执行事件是否完整。",
            "REMOTE_RESULT","仍需通过原清单入口核查业务结果。",
            "MANUAL_REVIEW","仍需人工核查相关事实。");

    /** 入参须已通过报告校验；仅创建展示副本，不改动快照、证据或业务状态。 */
    @SuppressWarnings("unchecked")
    public static Map<String,Object> render(Map<String,Object> structured) {
        var report=new LinkedHashMap<>(structured);report.put("summary",SUMMARY);
        report.put("findings",((List<Map<String,Object>>)structured.get("findings")).stream().map(f -> {
            var view=new LinkedHashMap<>(f);
            String qualifier=switch(f.get("certainty").toString()) {
                case "VERIFIED" -> "证据支持：";
                case "HYPOTHESIS" -> "待验证线索：";
                default -> "证据有限：";
            };
            view.put("explanation",qualifier+switch(f.get("reasonCode").toString()) {
                case "BUSINESS_REJECTED" -> "业务结果明确失败；具体原因请查看引用证据。";
                case "PRECHECK_SKIPPED" -> "执行前复核跳过；规则存在本身不能证明因果关系。";
                case "RESULT_UNKNOWN" -> "业务结果仍未知，不能据此认定失败或允许重发。";
                case "REMOTE_SUCCESS_LOCAL_UNRESOLVED" -> "本次远端核对成功，本地仍待核对；本调查未修改本地状态。";
                case "REQUEST_NOT_FOUND" -> "本次未查到原请求，不能据此认定未发送或允许重发。";
                case "EVIDENCE_MISSING" -> "必要证据缺失，尚不能据此作出完整判断。";
                default -> "尚不能确定异常原因。";
            });return view;
        }).toList());
        report.put("unresolved",((List<Map<String,Object>>)structured.get("unresolved")).stream().map(u -> {
            var view=new LinkedHashMap<>(u);
            view.put("message",String.join("",((List<String>)u.get("topics")).stream().map(TOPICS::get).toList()));return view;
        }).toList());
        return report;
    }
}
