package com.example.report.semantic;

import com.example.report.catalog.*;
import com.example.report.common.*;
import com.example.report.config.AgentProperties;
import com.example.report.support.TestCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.example.report.support.TestCatalog.*;
import static com.example.report.semantic.SemanticIntent.*;

class DomainIntentParserTest {
    final TestCatalog data=new TestCatalog();
    final ReportCatalogService catalog=new ReportCatalogService(data.catalog(),new AgentProperties());
    final SemanticPlanner planner=new SemanticPlanner(catalog);
    final DomainIntentParser parser=new DomainIntentParser();
    final IntentCodec codec=new IntentCodec();
    IntentParser.Context context(String text,DialogueState state) {
        return new IntentParser.Context(state,catalog.dispatchableReports(USER1).stream().map(CatalogEntry::ref).toList(),planner.mentions(USER1,text));
    }
    SemanticIntent parse(String text,DialogueState state) {
        var result=parser.parse(text,context(text,state)).orElseThrow(()->new AssertionError("Grammar did not cover "+text));
        codec.validate(result,text); return result;
    }
    @Test void originalCorpusPassesWithoutModelOrChangingExpectedOutcomes() throws Exception {
        try(var stream=getClass().getResourceAsStream("/semantic/replay-corpus.json")) {
            for(var scenario:JsonUtil.MAPPER.readTree(stream)) {
                var state=new DialogueState();
                for(var turn:scenario.get("turns")) {
                    String text=turn.get("message").asText();var intent=parse(text,state);
                    assertEquals(turn.get("action").asText(),intent.action().name(),text);
                    assertEquals(turn.get("companyOperation").asText(),intent.company().operation().name(),text);
                    assertEquals(turn.get("reportsOperation").asText(),intent.reports().operation().name(),text);
                    assertEquals(turn.path("exclusionsOperation").asText("KEEP"),intent.exclusions().operation().name(),text);
                    String outcome="READY";
                    try { planner.merge(USER1,state,intent);planner.requireAction(state,intent);planner.requireCoverage(state,intent,planner.mentions(USER1,text));planner.validate(USER1,state);state.setEffective(state.getDesired()); }
                    catch(ApiException e) { outcome=e.getCode()==422?"CLARIFY":"REJECTED"; }
                    assertEquals(turn.get("outcome").asText(),outcome,text);
                    assertEquals(turn.get("company").isNull()?null:turn.get("company").asText(),state.getDesired().companyCode(),text);
                    if(turn.has("reportIds")) assertEquals(JsonUtil.MAPPER.convertValue(turn.get("reportIds"),new com.fasterxml.jackson.core.type.TypeReference<Set<String>>(){}),new HashSet<>(state.getDesired().reportIds()),text);
                }
            }
        }
    }
    @ParameterizedTest @ValueSource(strings={"那 B 公司呢？","B公司的呢","公司B呢","看看Ｂ公司的销售报表","A公司销售报表的","切回A公司的销售台账","查询ACME-01公司销售报表"})
    void companyFragmentsAreGroundedAndDoNotDependOnPreviousRefusal(String text) {
        var state=new DialogueState();state.setDesired(new DialogueState.Scope("B",false,List.of(SALES)));
        state.setPhase(DialogueState.Phase.REJECTED);state.setLastReason("当前账号无权查看 B 公司");
        var intent=parse(text,state);
        assertEquals(Action.PREVIEW,intent.action());assertEquals(Operation.REPLACE,intent.company().operation());
        assertEquals(1,intent.company().mentions().size());
    }
    @ParameterizedTest @ValueSource(strings={"查销售报表不要应收报表","先查销售报表再加应收报表","不要只查销售报表","不是B公司而是A公司","查询A公司然后派单","取消清单再查询","查销售报表金额大于一万元","查销售报表顺便删除数据库","不要派单","恢复A公司","查销售报表按日期排序","不要限制A公司","我不想查询销售报表","不要所有报表","不要全部公司","排除全部记录"})
    void incompleteOrAmbiguousGrammarIsNeverPartiallyExecuted(String text) {
        assertTrue(parser.parse(text,context(text,new DialogueState())).isEmpty(),text);
    }
    @Test void multipleCompaniesRequireClarification() {
        assertEquals(Clarify.COMPANY,parse("查A公司和B公司销售报表",new DialogueState()).clarify());
    }
    @Test void catalogExtensionDoesNotRequireSentenceRules() {
        var entry=TestCatalog.entry("new-report","new-code","服务续约台账","report:sales",90,List.of("续约清单"));
        data.add(entry);
        var state=new DialogueState();var intent=parse("查A公司的续约清单",state);
        planner.merge(USER1,state,intent);planner.validate(USER1,state);
        assertEquals(List.of("new-report"),state.getDesired().reportIds());
    }
    @Test void scopedCommandsGeneralizeAcrossEntitySpellingAndPriorPhases() {
        for(var phase:DialogueState.Phase.values()) for(String company:List.of("A公司","a 公司","Ａ公司","公司A"))
            for(String alias:List.of("销售报表","销售台账","订单销售表","应收台账","费用报销")) {
                var state=new DialogueState();state.setPhase(phase);
                state.setDesired(new DialogueState.Scope("B",true,List.of()));
                state.setUnresolvedCompany(true);state.setUnresolvedReports(true);
                String text=company+alias+"的";var intent=parse(text,state);
                planner.merge(USER1,state,intent);planner.requireCoverage(state,intent,planner.mentions(USER1,text));planner.validate(USER1,state);
                assertEquals("A",state.getDesired().companyCode(),text);
                assertEquals(catalog.resolve(USER1,alias).reportIds(),state.getDesired().reportIds(),text);
                assertFalse(state.isUnresolvedCompany());assertFalse(state.isUnresolvedReports());
            }
    }
    @Test void interpreterCallsModelOnlyForUncoveredSyntax() {
        var model=mock(ModelIntentParser.class);var interpreter=new SemanticIntentParser(model);
        String known="B公司呢";
        assertEquals(IntentParser.Source.DOMAIN,interpreter.interpret(known,context(known,new DialogueState())).source());
        verifyNoInteractions(model);
        String unknown="如何使用这个助手";var ctx=context(unknown,new DialogueState());
        var expected=new IntentParser.Interpretation(SemanticIntent.clarify(Clarify.ACTION),IntentParser.Source.MODEL);
        when(model.interpret(unknown,ctx)).thenReturn(expected);
        assertEquals(expected,interpreter.interpret(unknown,ctx));verify(model).interpret(unknown,ctx);
    }
}
