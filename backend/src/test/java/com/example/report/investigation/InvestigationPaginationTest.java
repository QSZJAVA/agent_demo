package com.example.report.investigation;

import com.example.report.common.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.report.investigation.InvestigationTestSupport.*;

/** 分页响应的真实UTF-8边界回归；覆盖小预算、空事件、完整游标与无法容纳的单条记录。 */
class InvestigationPaginationTest {
    @Test void acceptedMinimumBudgetAllowsSmallItemAndEmptyEvents() {
        for(int limit:List.of(512,600,8192)) {
            var props=new InvestigationProperties();props.setMaxToolResultUtf8Bytes(limit);props.validate();
            var row=item("I1","UNKNOWN",null);row.put("events",List.of());row.put("eventCount",0L);
            var s=session(List.of(row),props);var tools=tools(repository(),props,Map.of());
            var items=tools.execute(s,"investigation_plan_items",JsonUtil.MAPPER.valueToTree(Map.of("size",1)));
            assertTrue(bytes(items)<=limit);assertEquals(1,rows(items).size());
            var events=tools.execute(s,"investigation_execution_events",JsonUtil.MAPPER.valueToTree(Map.of("size",1,"itemRefs",List.of("I1"))));
            assertTrue(bytes(events)<=limit);assertTrue(rows(events).isEmpty());assertEquals(0L,((Map<?,?>)events.get("data")).get("sourceTotal"));
            assertEquals(2,s.evidence.size());
        }
    }
    @Test void pagingCountsUtf8EscapingCursorAndMetadataWithoutSkippingRows() {
        var props=new InvestigationProperties();props.setMaxToolResultUtf8Bytes(512);var row=item("I1","UNKNOWN",null);
        var entries=new ArrayList<Map<String,Object>>();
        for(int i=0;i<7;i++) entries.add(Map.of("eventId",Integer.toString(i),"outcome","UNKNOWN","message","中\\\"文".repeat(6)));
        row.put("events",entries);row.put("eventCount",100L);row.put("eventsTruncated",true);
        var s=session(List.of(row),props);var tools=tools(repository(),props,Map.of());var found=new ArrayList<String>();String cursor=null;int pages=0;
        do {
            var args=new LinkedHashMap<String,Object>();args.put("itemRefs",List.of("I1"));args.put("size",20);args.put("cursor",cursor);
            var result=tools.execute(s,"investigation_execution_events",JsonUtil.MAPPER.valueToTree(args));
            assertTrue(bytes(result)<=512);assertEquals(true,result.get("truncated"));
            var data=(Map<?,?>)result.get("data");assertEquals(100L,data.get("sourceTotal"));
            var page=rows(result);assertFalse(page.isEmpty());page.forEach(value -> found.add(value.get("eventId").toString()));
            cursor=(String)data.get("nextCursor");assertEquals(cursor==null,data.get("complete"));assertTrue(++pages<=7);
        } while(cursor!=null);
        assertEquals(List.of("0","1","2","3","4","5","6"),found);assertTrue(pages>1);
    }
    @Test void oversizedSingleRowIsRejectedBeforeCreatingEvidence() {
        var props=new InvestigationProperties();props.setMaxToolResultUtf8Bytes(512);var row=item("I1","UNKNOWN",null);row.put("label","长文本".repeat(200));
        var s=session(List.of(row),props);var tools=tools(repository(),props,Map.of());
        assertThrows(ApiException.class,() -> tools.execute(s,"investigation_plan_items",JsonUtil.MAPPER.valueToTree(Map.of("size",1))));assertTrue(s.evidence.isEmpty());
    }
    private static int bytes(Object value) {return JsonUtil.toJson(value).getBytes(StandardCharsets.UTF_8).length;}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Map<String,Object> result) {return (List<Map<String,Object>>)((Map<?,?>)result.get("data")).get("items");}
}
