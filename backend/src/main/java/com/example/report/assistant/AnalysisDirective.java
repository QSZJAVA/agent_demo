package com.example.report.assistant;

import com.example.report.analysis.AnalysisContract;
import com.example.report.common.ApiException;

/**
 * 统一聊天交给有限分析的受限指令；问题始终由外层绑定本轮原文，模型不能改写问题或提供实际快照标识。
 * @param companyCode 当前目录授权的单一公司代码；提交前由只读预检再次核对
 * @param reportId 当前目录授权的单一报表标识，不能跨报表扩展来源
 * @param mode 明确读取模式；知识路由只能KNOWLEDGE_ONLY，分析路由为CURRENT_DATA或SNAPSHOT_ONLY
 * @param sourceRef 本轮服务器提供的analysis-source-及24位小写十六进制引用；快照模式必填、知识模式为空、当前数据模式可空
 */
public record AnalysisDirective(String companyCode,String reportId,AnalysisContract.Mode mode,String sourceRef) {
    public AnalysisDirective {
        if(companyCode==null||companyCode.isBlank()||companyCode.length()>32||companyCode.indexOf('\0')>=0
                ||reportId==null||!reportId.matches("[a-z][a-z0-9-]{2,63}"))throw new ApiException(422,"分析指令必须指定单一有效公司和报表");
        if(mode==null)throw new ApiException(422,"分析指令必须明确读取模式");
        if(sourceRef!=null&&!sourceRef.matches("analysis-source-[a-f0-9]{24}"))throw new ApiException(422,"分析只能引用本轮服务器给定的查询来源");
        mode.requireSource(sourceRef!=null);
    }
}
