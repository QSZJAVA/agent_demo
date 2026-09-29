package com.example.report.semantic;

import com.example.report.catalog.*;
import com.example.report.common.*;
import com.example.report.config.AgentProperties;
import com.example.report.support.TestCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static com.example.report.semantic.SemanticIntent.*;
import static com.example.report.support.TestCatalog.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SemanticV2Test {
    final IntentCodec codec=new IntentCodec();
    final SemanticPlanner planner=new SemanticPlanner(new ReportCatalogService(new TestCatalog().catalog(),new AgentProperties()));
    ScopeChange change(Target target,Operation op,String value) { return new ScopeChange(target,op,List.of(value),value); }
    SemanticIntent intent(Action action,ScopeChange... changes) { return new SemanticIntent(2,action,List.of(changes),List.of(),Clarify.NONE); }

    @ParameterizedTest @ValueSource(strings={"只查销售报表，不要派单","那 B 公司呢？","查销售报表金额大于一万元","如何使用这个助手","不要只查销售报表"})
    void everyFreeTextUtteranceUsesTheSameModelInterpreter(String text) {
        var model=mock(ModelIntentParser.class);
        var context=new IntentParser.Context(new DialogueState(),List.of());
        var expected=new IntentParser.Interpretation(SemanticIntent.clarify(Clarify.ACTION),IntentParser.Source.MODEL);
        when(model.interpret(text,context)).thenReturn(expected);
        assertEquals(expected,new SemanticIntentParser(model).interpret(text,context));
        verify(model).interpret(text,context);
    }
    @Test void noKeywordFallbackWhenModelFails() {
        var model=mock(ModelIntentParser.class);
        when(model.interpret(anyString(),any())).thenThrow(new ApiException(503,"offline"));
        assertThrows(ApiException.class,()->new SemanticIntentParser(model).parse("A公司销售报表的",new IntentParser.Context(new DialogueState(),List.of())));
    }
    @Test void orderedChangesCommitOnlyAfterEveryEntityResolves() {
        var state=new DialogueState();
        planner.merge(USER1,state,intent(Action.PREVIEW,change(Target.REPORTS,Operation.REPLACE,"销售报表")));
        var before=state.getDesired();
        var partial=intent(Action.PREVIEW,change(Target.REPORTS,Operation.ADD,"应收报表"),change(Target.REPORTS,Operation.REMOVE,"不存在的报表"));
        assertThrows(ApiException.class,()->planner.merge(USER1,state,partial));
        assertEquals(before,state.getDesired());assertTrue(state.isUnresolvedReports());
        assertThrows(ApiException.class,()->planner.validate(USER1,state));
        planner.merge(USER1,state,intent(Action.PREVIEW,change(Target.REPORTS,Operation.REPLACE,"销售报表"),change(Target.REPORTS,Operation.ADD,"应收报表"),change(Target.REPORTS,Operation.REMOVE,"销售报表")));
        assertEquals(List.of(RECEIVABLE),state.getDesired().reportIds());
        assertFalse(state.isUnresolvedReports());
    }
    @Test void actionRestrictionsCannotBeContradictedAndDoNotChangeReports() {
        var restriction=new Restriction(Action.PREPARE_DISPATCH,RestrictionScope.THIS_TURN,"不要派单");
        var query=new SemanticIntent(2,Action.PREVIEW,List.of(change(Target.REPORTS,Operation.REPLACE,"销售报表")),List.of(restriction),Clarify.NONE);
        codec.validate(query,"只查销售报表，不要派单");
        var state=new DialogueState();planner.merge(USER1,state,query);
        assertEquals(List.of(SALES),state.getDesired().reportIds());
        var conflict=new SemanticIntent(2,Action.PREPARE_DISPATCH,query.scopeChanges(),query.restrictions(),Clarify.NONE);
        assertThrows(ApiException.class,()->codec.validate(conflict,"销售报表不要派单"));
        assertThrows(ApiException.class,()->planner.merge(USER1,state,conflict));
        assertDoesNotThrow(()->planner.merge(USER1,state,intent(Action.PREPARE_DISPATCH)));
    }
    @Test void rejectsPartialProgramsAndInvalidRestrictionShapes() {
        var invalid=intent(Action.PREVIEW,change(Target.RECORDS,Operation.ADD,"SO2026002"),change(Target.REPORTS,Operation.ADD,"销售报表"));
        assertThrows(ApiException.class,()->codec.validate(invalid,"SO2026002销售报表"));
        var nullChange=new SemanticIntent(2,Action.PREVIEW,Arrays.asList((ScopeChange)null),List.of(),Clarify.NONE);
        assertThrows(ApiException.class,()->codec.validate(nullChange,"查询"));
        var query=intent(Action.PREVIEW,change(Target.REPORTS,Operation.REPLACE,"销售报表"));
        String json=JsonUtil.toJson(query);
        for(String bad:List.of(json.replace("\"version\":2","\"version\":1"),json.replace("\"version\":2","\"version\":2.5"),
                json.replace("\"version\":2","\"version\":\"2\""),json.replace("REPLACE","KEEP"),json.replace("PREVIEW","EXECUTE"),
                json.replace("\"restrictions\":[]","\"restrictions\":[{\"action\":\"PREPARE_DISPATCH\",\"scope\":\"FOREVER\",\"evidence\":\"销售报表\"}]")))
            assertThrows(ApiException.class,()->codec.decode(bad,"销售报表"));
    }
    @Test void clarificationNeverAppliesProposedScopeAndClearCannotHideOmittedReports() {
        var changed=new SemanticIntent(2,Action.CLARIFY,List.of(change(Target.REPORTS,Operation.REPLACE,"销售报表")),List.of(),Clarify.REPORTS);
        assertThrows(ApiException.class,()->codec.validate(changed,"销售报表"));
        var clear=intent(Action.PREVIEW,new ScopeChange(Target.REPORTS,Operation.CLEAR,List.of(),"全部"));
        assertThrows(ApiException.class,()->planner.requireCoverage(new DialogueState(),clear,List.of("销售报表")));
    }
    @Test void storedV1StateUpgradesWithoutChangingScopeOrSelection() {
        var old=Map.of("version",1,"action","PREVIEW","company",Change.keep(),"reports",new Change(Operation.REPLACE,List.of("销售报表"),"销售报表"),"exclusions",Change.keep(),"clarify","NONE");
        String json=JsonUtil.toJson(Map.of("desired",new DialogueState.Scope("B",false,List.of(SALES)),"effective",new DialogueState.Scope("A",false,List.of(SALES)),
                "pendingIntent",old,"excludedRecords",List.of(new com.example.report.dispatch.RecordKey(SALES,"2")),"parserSource","DOMAIN"));
        var state=DialogueStore.decode(json);
        assertEquals("B",state.getDesired().companyCode());assertEquals("A",state.getEffective().companyCode());
        assertEquals(1,state.getExcludedRecords().size());assertEquals(2,state.getPendingIntent().version());
        assertEquals(Target.REPORTS,state.getPendingIntent().scopeChanges().get(0).target());
        assertEquals(state,DialogueStore.decode(JsonUtil.toJson(state)));
        assertThrows(ApiException.class,()->codec.decode(JsonUtil.toJson(old),"销售报表"));
    }
    @Test void deterministicCorpusExercisesBusinessOutcomesButIsNotModelAccuracy() throws Exception {
        var props=new AgentProperties();props.getLlm().setMock(true);
        var parser=new SemanticIntentParser(new ModelIntentParser(mock(org.springframework.ai.chat.model.ChatModel.class),codec,props));
        var evaluation=SemanticEvaluation.run(parser,props);
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target"));
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/semantic-v2-offline-evaluation.json"),
                JsonUtil.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("protocol",2,"parserSource","MOCK",
                        "modelAccuracyMeasured",false,"total",evaluation.expectedTurns(),"passed",evaluation.results().size()-evaluation.failed(),"cases",evaluation.results())));
        assertEquals(0,evaluation.failed(),evaluation.results().toString());
        assertEquals(evaluation.expectedTurns(),evaluation.results().size());
        assertTrue(evaluation.results().stream().allMatch(r->"MOCK".equals(r.get("parserSource"))));
    }
}
