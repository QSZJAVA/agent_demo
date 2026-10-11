package com.example.report.workspace;

import com.example.report.assistant.BusinessQuery;
import com.example.report.common.JsonUtil;
import java.math.BigDecimal;
import java.util.*;

/** 两份完整查询快照的精确差异；条件或口径变化时只描述集合差异，不把它解释成业务增长。 */
public final class QueryComparison {
    private QueryComparison() { }
    /** 统计所有变更，最多返回100项详情；详情截断与完整计数分别标记。 */
    public static Map<String,Object> compare(QuerySnapshotStore.Run before,List<Map<String,Object>> oldRows,QuerySnapshotStore.Run after,List<Map<String,Object>> newRows) {
        var old=index(oldRows);var next=index(newRows);var keys=new TreeSet<>(old.keySet());keys.addAll(next.keySet());
        int added=0,removed=0,changed=0;var details=new ArrayList<Map<String,Object>>();
        var numeric=new HashSet<String>();before.result().columns().stream().filter(f->Set.of("decimal","integer","long").contains(f.type())).forEach(f->numeric.add(f.name()));
        for(String key:keys) {
            var left=old.get(key);var right=next.get(key);String kind;var fields=new TreeSet<String>();
            if(left==null){added++;kind="ADDED";}else if(right==null){removed++;kind="REMOVED";}
            else {fields.addAll(left.keySet());fields.addAll(right.keySet());fields.removeIf(f->same(left.get(f),right.get(f),numeric.contains(f)));
                if(fields.isEmpty())continue;changed++;kind="CHANGED";}
            if(details.size()<100){var detail=new LinkedHashMap<String,Object>();detail.put("rowKey",key);detail.put("kind",kind);detail.put("fields",fields);detail.put("before",left);detail.put("after",right);details.add(detail);}
        }
        var currencies=new TreeSet<>(before.result().summary().amountsByCurrency().keySet());currencies.addAll(after.result().summary().amountsByCurrency().keySet());var delta=new TreeMap<String,String>();
        for(String currency:currencies)delta.put(currency,new BigDecimal(after.result().summary().amountsByCurrency().getOrDefault(currency,"0")).subtract(new BigDecimal(before.result().summary().amountsByCurrency().getOrDefault(currency,"0"))).toPlainString());
        var response=new LinkedHashMap<String,Object>();response.put("before",before.result());response.put("after",after.result());response.put("sameDefinition",before.definitionHash().equals(after.definitionHash())
                && before.companies().equals(after.companies()) && scope(before.result().query()).equals(scope(after.result().query())));
        response.put("added",added);response.put("removed",removed);response.put("changed",changed);response.put("details",details);response.put("detailsTruncated",added+removed+changed>100);response.put("amountDeltaByCurrency",delta);
        return response;
    }
    private static Object scope(BusinessQuery q) {
        var groups=q.conditions().stream().map(g->g.allOf().stream().map(f->JsonUtil.toJson(f)).sorted().toList()).map(JsonUtil::toJson).sorted().toList();
        return Arrays.asList(q.domain(),q.reportIds().stream().sorted().toList(),q.companyCode(),groups);
    }
    private static Map<String,Map<String,Object>> index(List<Map<String,Object>> rows){var result=new HashMap<String,Map<String,Object>>();rows.forEach(row->result.put(row.get("rowKey").toString(),row));return result;}
    private static boolean same(Object left,Object right,boolean numeric){if(left==null || right==null)return left==right;if(numeric)return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString()))==0;return Objects.equals(left,right);}
}
