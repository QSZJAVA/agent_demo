package com.example.report.analysis;

import com.example.report.assistant.*;
import com.example.report.common.ApiException;
import com.example.report.permission.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 只读试查的返回时版本保护回归；模拟读取间变化，不能将已失效的候选作为成功结果交付。 */
class KnowledgePreviewTest {
    static final CurrentUser ADMIN=new CurrentUser("T001","reader","管理员",Set.of("A"),Set.of("report:sales"),true);
    static final KnowledgeService.SearchRequest REQUEST=new KnowledgeService.SearchRequest("A","rpt-sales-order","tax");
    static KnowledgeService.Snapshot snapshot(String fingerprint) {
        var entry=new SemanticDictionaryService.Entry(new SemanticDictionary.Scope(BusinessQuery.Domain.REPORT,"A","rpt-sales-order"),0,SemanticDictionary.Definition.empty());
        return new KnowledgeService.Snapshot(null,entry,List.of(new AnalysisEvidence.Source("doc","doc","KNOWLEDGE","Tax","1","A / rpt-sales-order","有效","tax applies")),fingerprint,Instant.parse("2026-10-11T17:00:00Z"));
    }
    static KnowledgeService service() {
        var permissions=mock(PermissionService.class);when(permissions.resolve(ADMIN.userId())).thenReturn(ADMIN);
        return spy(new KnowledgeService(null,permissions,null,null,null,null));
    }
    @Test void aPublicationBetweenReadsRejectsThePreparedPreview() {
        var service=service();doReturn(snapshot("before"),snapshot("after")).when(service).snapshot(ADMIN,"A","rpt-sales-order");
        assertEquals(409,assertThrows(ApiException.class,()->service.preview(ADMIN,REQUEST)).getCode());
    }
    @Test void permissionFailureDuringRecheckDoesNotReturnPreviouslyPreparedSources() {
        var service=service();doReturn(snapshot("same")).doThrow(ApiException.forbidden("权限已变化")).when(service).snapshot(ADMIN,"A","rpt-sales-order");
        assertEquals(403,assertThrows(ApiException.class,()->service.preview(ADMIN,REQUEST)).getCode());
    }
    @Test void unchangedSourcesReturnTheirOriginalObservationAndVersion() {
        var service=service();var before=snapshot("same");doReturn(before).when(service).snapshot(ADMIN,"A","rpt-sales-order");
        var result=service.preview(ADMIN,REQUEST);assertEquals(before.referenceTime(),result.referenceTime());assertEquals("same",result.fingerprint());assertEquals(1,result.result().matched());
    }
}
