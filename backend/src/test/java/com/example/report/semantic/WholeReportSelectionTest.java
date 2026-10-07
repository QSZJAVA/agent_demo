package com.example.report.semantic;

import com.example.report.catalog.ReportCatalogService;
import com.example.report.config.AgentProperties;
import com.example.report.dispatch.RecordKey;
import com.example.report.support.TestCatalog;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.example.report.semantic.SemanticIntent.*;
import static com.example.report.support.TestCatalog.*;
import static com.example.report.support.DispatchHarness.candidate;
import static org.junit.jupiter.api.Assertions.*;

/** 整类勾选只影响当前授权快照内指定报表，逆操作和重复操作不得清除其他报表的排除记录。 */
class WholeReportSelectionTest {
    @Test void companyTypeLabelsNormalizeWithoutFuzzyNameOrIdentifierRewriting() {
        for(String label:List.of("A公司","公司 A","company a","A company"))assertEquals("A",SemanticPlanner.companyCode(label));
        assertEquals("COMPANY-A",SemanticPlanner.companyCode("COMPANY-A"));
        assertEquals("ACMECOMPANY",SemanticPlanner.companyCode("AcmeCompany"));
    }
    final ReportCatalogService catalog=new ReportCatalogService(new TestCatalog().catalog(),new AgentProperties());
    @Test void scopedWholeReportExclusionAndRestorePreserveOtherSelections() {
        var rows=List.of(candidate(SALES,"s1","S1","A","销售一"),candidate(SALES,"s2","S2","A","销售二"),candidate(EXPENSE,"e1","E1","A","费用"));
        var previous=List.of(new RecordKey(EXPENSE,"e1"));
        var exclude=new ScopeChange(Target.RECORDS,Operation.EXCLUDE,List.of(),"销售全部不选",List.of("销售"),SelectorKind.ALL,Quantifier.ALL);
        var selected=SelectionResolver.apply(rows,previous,exclude,catalog,USER1);
        assertEquals(Set.of(new RecordKey(SALES,"s1"),new RecordKey(SALES,"s2"),new RecordKey(EXPENSE,"e1")),Set.copyOf(selected));
        assertEquals(selected,SelectionResolver.apply(rows,selected,exclude,catalog,USER1));
        var restore=new ScopeChange(Target.RECORDS,Operation.RESTORE,List.of(),"销售全部恢复",List.of("销售"),SelectorKind.ALL,Quantifier.ALL);
        assertEquals(previous,SelectionResolver.apply(rows,selected,restore,catalog,USER1));
    }
    @Test void wholeScopeSelectionNeedsExplicitAllCardinalityAndNoFabricatedSelector() {
        var codec=new IntentCodec();var good=new ScopeChange(Target.RECORDS,Operation.EXCLUDE,List.of(),"全部先不选",List.of(),SelectorKind.ALL,Quantifier.ALL);
        assertDoesNotThrow(()->codec.validate(new SemanticIntent(1,Action.PREVIEW,List.of(good),List.of(),Clarify.NONE),good.evidence()));
        var singular=new ScopeChange(Target.RECORDS,Operation.EXCLUDE,List.of(),"这一条不选",List.of(),SelectorKind.ALL,Quantifier.ONE);
        assertThrows(IntentCodec.InvalidOutput.class,()->codec.validate(new SemanticIntent(1,Action.PREVIEW,List.of(singular),List.of(),Clarify.NONE),singular.evidence()));
        var fabricated=new ScopeChange(Target.RECORDS,Operation.EXCLUDE,List.of("未知对象"),"未知对象全部不选",List.of(),SelectorKind.ALL,Quantifier.ALL);
        assertThrows(IntentCodec.InvalidOutput.class,()->codec.validate(new SemanticIntent(1,Action.PREVIEW,List.of(fabricated),List.of(),Clarify.NONE),fabricated.evidence()));
    }
}
