package com.example.report.dispatch;

import com.example.report.common.ApiException;
import com.example.report.rule.Candidate;
import com.example.report.support.DispatchHarness;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import static com.example.report.support.TestCatalog.*;
import static com.example.report.support.DispatchHarness.candidate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 扫描晚期失败时保护旧预览和待确认清单，分批持久化的部分结果必须清除。 */
class PreviewCompletenessTest {
    @ParameterizedTest @ValueSource(booleans={false,true})
    void incompleteScanCannotReplacePreviewOrExpirePendingPlan(boolean large) {
        var h=new DispatchHarness().put(SALES,candidate(SALES,"old","SO-old","A","原记录"));
        var command=new PreviewCommand(null,"api",null,List.of(SALES),null,null);
        String previous=h.previews.preview(USER1,"complete",command).snapshot().preview().getId();
        String plan=h.plans.create(USER1,"complete",previous,List.of(),null).plan().getId();
        var failure=new ApiException(422,"来源字段或规则计算异常，查询结果不完整");
        if(large) {
            h.props.getPreview().setMaxItems(1);
            h.put(SALES,candidate(SALES,"1","SO1","A","首条"),candidate(SALES,"2","SO2","A","第二条"));
            doAnswer(call->{
                Consumer<Candidate> sink=call.getArgument(4);
                for(int i=0;i<501;i++)sink.accept(candidate(SALES,Integer.toString(i),"SO"+i,"A","有效记录"));
                throw failure;
            }).when(h.candidates).scanCandidates(anyString(),anySet(),anyList(),any(IntConsumer.class),any(Consumer.class));
        } else {
            when(h.candidates.findCandidates(anyString(),anySet(),anyList(),anyInt(),any(IntConsumer.class))).thenThrow(failure);
        }
        assertSame(failure,assertThrows(ApiException.class,()->h.previews.preview(USER1,"complete",command)));
        assertEquals(previous,h.previews.latest(USER1,"complete").orElseThrow().getId());
        assertEquals("ACTIVE",h.store.previews().find(previous).orElseThrow().getStatus());
        assertEquals(List.of(plan),h.store.plans().pending("complete").stream().map(p->p.getId()).toList());
        assertEquals(1,h.previews.getOwned(USER1,previous).items().size());
    }
}
