package com.example.report.investigation;

import java.time.Duration;
import java.util.*;

/** 单次调查的实际调用和单调时钟预算；失败和结果复用仍计数，缺失用量不会被合成零成本。 */
public class InvestigationBudget {
    private final InvestigationProperties props;
    private final long deadline;
    private int models,tools,mcp;
    private final List<Map<String,Object>> usages=new ArrayList<>();
    public InvestigationBudget(InvestigationProperties props) {this.props=props;deadline=System.nanoTime()+Duration.ofSeconds(props.getRunTimeoutSeconds()).toNanos();}
    public Duration remaining(int capSeconds) {
        long nanos=deadline-System.nanoTime();if(nanos<=0 || Thread.currentThread().isInterrupted()) throw new InvestigationFailure("BUDGET_EXHAUSTED","调查运行时限已到");
        return Duration.ofNanos(Math.min(nanos,Duration.ofSeconds(capSeconds).toNanos()));
    }
    public void model() {remaining(props.getModelTimeoutSeconds());if(models>=props.getMaxModelCalls()) throw exhausted();models++;}
    public void tool() {remaining(props.getMcpTimeoutSeconds());if(tools>=props.getMaxToolCalls()) throw exhausted();tools++;}
    public void mcp() {remaining(props.getMcpTimeoutSeconds());if(mcp>=props.getMaxMcpCalls()) throw exhausted();mcp++;}
    public int toolsLeft() {return props.getMaxToolCalls()-tools;}
    public void usage(Map<String,Object> usage) {usages.add(usage);}
    public Map<String,Object> summary() {
        var result=new LinkedHashMap<String,Object>();result.put("modelCalls",models);result.put("toolCalls",tools);result.put("mcpCalls",mcp);
        boolean complete=models>0 && usages.size()==models && usages.stream().allMatch(u -> Boolean.TRUE.equals(u.get("usageComplete")));
        result.put("usageComplete",complete);result.put("inputTokens",complete?usages.stream().mapToLong(u -> ((Number)u.get("inputTokens")).longValue()).sum():null);
        result.put("outputTokens",complete?usages.stream().mapToLong(u -> ((Number)u.get("outputTokens")).longValue()).sum():null);
        boolean priced=complete && usages.stream().allMatch(u -> u.get("cost") instanceof java.math.BigDecimal);
        result.put("cost",priced?usages.stream().map(u -> (java.math.BigDecimal)u.get("cost")).reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add):null);result.put("currency",priced?usages.get(0).get("currency"):null);result.put("costComplete",priced);return result;
    }
    private InvestigationFailure exhausted() {return new InvestigationFailure("BUDGET_EXHAUSTED","调查调用预算已达到上限");}
}
