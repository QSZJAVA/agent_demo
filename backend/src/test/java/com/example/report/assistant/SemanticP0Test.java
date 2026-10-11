package com.example.report.assistant;

import com.example.report.catalog.query.FieldInfo;
import com.example.report.common.*;
import com.example.report.config.AgentProperties;
import com.example.report.permission.CurrentUser;
import com.example.report.semantic.DialogueState;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** P0词典、阶段计量和结构化编辑的确定性边界回归；不调用外部模型、不创建数据库。 */
class SemanticP0Test {
    static final List<FieldInfo> COLUMNS=List.of(new FieldInfo("amount","decimal","金额"),new FieldInfo("date","date","日期"),new FieldInfo("docNo","string","单据号"),new FieldInfo("status","string","状态"));
    static final CurrentUser USER=new CurrentUser("T001","owner","本人",Set.of("A"),Set.of("*"),false);
    static final String REF="a".repeat(32);
    static BusinessQuery.Filter filter(String field,String op,String...values){return new BusinessQuery.Filter(field,op,List.of(values));}
    static BusinessQuery query(BusinessQuery.Filter...filters){return new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of("rpt-sales-order"),"A",filters.length==0?List.of():List.of(new BusinessQuery.Group(List.of(filters))),null,false,1,20,null);}
    static SemanticDictionary.Field field(String name,List<String> aliases,String unit,List<SemanticDictionary.Unit> units,List<SemanticDictionary.Value> values){return new SemanticDictionary.Field(name,aliases,"",unit,"",units,values);}
    static SemanticDictionary.Definition definition(SemanticDictionary.Field...fields){return new SemanticDictionary.Definition("公司A",List.of("甲方"),"Asia/Shanghai",4,List.of(),List.of(fields));}
    static DialogueState state(BusinessQuery q){var s=new DialogueState();s.setBusinessQuery(q);s.setBusinessQueryRef(REF);s.setBusinessColumns(COLUMNS);s.setBusinessPermissionVersion(USER.permissionVersion());s.setAssistantFocus("BUSINESS_QUERY");return s;}

    @Test void dictionaryCalendarUsesOneReferenceAcrossYearAndTimeZones(){
        var cn=definition().calendar(Instant.parse("2026-10-01T00:00:00Z"));assertEquals("2026-10-01",cn.get("today"));assertEquals("2026-09-01",cn.get("previousMonthStart"));assertEquals("2026-04-01",cn.get("fiscalYearStart"));assertEquals(false,cn.get("holidayCalendarComplete"));
        var west=new SemanticDictionary.Definition("",List.of(),"America/Los_Angeles",10,List.of(),List.of()).calendar(Instant.parse("2026-10-01T00:00:00Z"));
        assertEquals("2026-09-30",west.get("today"));assertEquals("2025-10-01",west.get("fiscalYearStart"));assertEquals("2026-08-01",west.get("previousMonthStart"));
    }
    @ParameterizedTest @ValueSource(strings={"0","-1","NaN","1e99999999","0.0000000000000000000000000000001"})
    void unitFactorsMustStayWithinSourceDecimalContract(String value){assertThrows(ApiException.class,()->new SemanticDictionary.Unit("测试单位",value));}
    @Test void dictionaryRejectsUnknownFieldsAndUnitsOnText(){
        assertThrows(ApiException.class,()->definition(field("missing",List.of(),"",List.of(),List.of())).validate(COLUMNS));
        assertThrows(ApiException.class,()->definition(field("docNo",List.of(),"元",List.of(),List.of())).validate(COLUMNS));
        assertDoesNotThrow(()->definition(field("amount",List.of("价款"),"元",List.of(new SemanticDictionary.Unit("万元","10000")),List.of())).validate(COLUMNS));
    }
    @Test void dictionaryRejectsAmbiguousAliasesWithinOneFieldNamespace(){
        assertThrows(ApiException.class,()->definition(field("amount",List.of("金额"),"",List.of(),List.of()),field("date",List.of("金额"),"",List.of(),List.of())));
        assertThrows(ApiException.class,()->definition(field("amount",List.of("docNo"),"",List.of(),List.of())).validate(COLUMNS));
        assertThrows(ApiException.class,()->new SemanticDictionary.Value("x",List.of("Ａ","a")));
        assertThrows(ApiException.class,()->field("status",List.of(),"",List.of(),List.of(new SemanticDictionary.Value("x",List.of("待处理")),new SemanticDictionary.Value("y",List.of("待处理")))));
    }
    @Test void dictionaryEnumTypesAndEquivalentValuesAreValidated(){
        var integers=List.of(new FieldInfo("count","integer","数量"));
        assertThrows(ApiException.class,()->definition(field("count",List.of(),"",List.of(),List.of(new SemanticDictionary.Value("1.1",List.of())))).validate(integers));
        assertThrows(ApiException.class,()->definition(field("amount",List.of(),"",List.of(),List.of(new SemanticDictionary.Value("1.0",List.of()),new SemanticDictionary.Value("1.00",List.of())))).validate(COLUMNS));
    }
    @Test void dictionaryScopeAndFormDoNotCoerceOrAcceptExtraFields(){
        var payload=JsonUtil.toMap(JsonUtil.toJson(new SemanticDictionaryService.Change(0,definition(),"业务口径")));
        assertDoesNotThrow(()->SemanticDictionaryService.decodeChange(payload));payload.put("expectedVersion",0.5);assertThrows(ApiException.class,()->SemanticDictionaryService.decodeChange(payload));
        payload.put("expectedVersion",0);payload.put("admin",true);assertThrows(ApiException.class,()->SemanticDictionaryService.decodeChange(payload));
        assertThrows(ApiException.class,()->new SemanticDictionary.Scope(BusinessQuery.Domain.REPORT,"","rpt-sales-order"));
    }
    @Test void diagnosticCountsStagesWithoutInventingMissingUsageOrPersistingPrompt(){
        var model=new ModelAssistantPlannerTest.Model(ModelAssistantPlannerTest.HELP);var diagnostics=new PlanningDiagnostics();
        var context=new AssistantPlanningContext(List.of(),Map.of(),p->Map.of(),Map.of("referenceTime","2026-10-11"),diagnostics);
        new ModelAssistantPlanner(model,new AgentProperties()).plan("能做什么",new DialogueState(),List.of(),Set.of("A"),context);
        var result=diagnostics.snapshot(REF,"ACCEPTED","dictionary");assertEquals(3,result.get("modelCalls"));assertEquals(0,result.get("repairCalls"));assertEquals(true,result.get("firstPass"));assertNull(result.get("inputTokens"));assertEquals(false,result.get("usageComplete"));assertNull(result.get("cost"));
        assertFalse(JsonUtil.toJson(result).contains("businessDictionary"));assertNotNull(diagnostics.review());
        for(var prompt:model.prompts)assertTrue(JsonUtil.toMap(prompt.getUserMessage().getText()).containsKey("businessDictionary"));
    }
    @Test void diagnosticTransportFailureKeepsOnlyFailureType(){
        var d=new PlanningDiagnostics();assertThrows(IllegalStateException.class,()->d.model("PLAN","fixed","private input",()->{throw new IllegalStateException("credential-never-store-this");}));
        String json=JsonUtil.toJson(d.snapshot(REF,"FAILED",null));assertTrue(json.contains("IllegalStateException"));assertFalse(json.contains("credential-never-store-this"));assertFalse(json.contains("private input"));
    }
    @Test void completeEditorInputSurvivesContextCopiesAndReachesAllThreeStages() throws Exception {
        String message="将完整条件设为金额大于等于30000且小于等于50000，其他范围不变";
        var requested=query(filter("amount","GTE","30000"),filter("amount","LTE","50000"));
        var current=state(query());current.setPreviewId("old-preview");current.setBusinessQueryAfterPreview(true);
        var plan=new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,requested,true,null);
        var model=new ModelAssistantPlannerTest.Model(JsonUtil.toJson(plan)).expect(plan,message,"完整保留用户编辑");
        var context=new AssistantPlanningContext(List.of(Map.of("role","user","content",message)),Map.of(),p->{AssistantInteraction.requireRequestedQuery(p,requested,COLUMNS);return Map.of();})
                .withRequestedQuery(requested).withResolvedClarification(Map.of()).withCatalog(List.of()).beforeCurrentMessage(message);
        assertEquals(requested,context.requestedQuery());assertTrue(context.history().isEmpty());
        assertEquals(plan,new ModelAssistantPlanner(model,new AgentProperties()).plan(message,current,List.of(),Set.of("A"),context));
        assertEquals(3,model.prompts.size());
        for(var prompt:model.prompts){
            var input=JsonUtil.MAPPER.readTree(prompt.getUserMessage().getText());
            assertEquals(JsonUtil.MAPPER.valueToTree(requested),input.path("requestedQuery"));
            if(!"TASK_PURPOSE".equals(input.path("taskStage").asText())){
                assertEquals("PREVIEW",input.at("/dispatchContext/previewPreparation/source").asText());
                assertTrue(input.at("/dispatchContext/previewPreparation/requiresRedisplay").asBoolean());
                assertFalse(input.path("dispatchContext").has("requiresRedisplayBeforePrepare"));
            }
        }
    }
    @Test void oldPreviewRestrictionsRemainScopedWhileFreshQueryReferencesStayUsable(){
        String message="为这条已查询记录准备清单";var current=state(query());
        current.setPreviewId("old-preview");current.setBusinessQueryAfterPreview(true);current.setBusinessTotalCount(1L);
        current.setBusinessReferences(List.of(Map.of("recordId","1","reportId","rpt-sales-order","companyCode","A")));
        var intent=ModelAssistantPlannerTest.intent(com.example.report.semantic.SemanticIntent.Action.PREPARE_DISPATCH);
        var fresh=new DispatchDirective(intent,DispatchDirective.Source.QUERY_ROWS,AssistantReferences.queryRef(current),List.of("row-1"),message);
        var old=new DispatchDirective(intent,DispatchDirective.Source.PREVIEW,AssistantReferences.previewRef(current),List.of(),message);
        assertDoesNotThrow(()->AssistantReferences.validate(message,current,fresh));
        assertThrows(ApiException.class,()->AssistantReferences.validate(message,current,old));
        var facts=JsonUtil.MAPPER.valueToTree(AssistantReferences.queryContext(current));assertTrue(facts.path("available").asBoolean());
        assertFalse(facts.at("/preparation/requiresSeparatePreview").asBoolean());
        assertTrue(facts.at("/preparation/requiresCurrentPermissionAndQualificationCheck").asBoolean());
        assertTrue(facts.at("/preparation/executionRequiresConfirmation").asBoolean());
        current.setBusinessUnresolved(true);assertThrows(ApiException.class,()->AssistantReferences.validate(message,current,fresh));
        assertEquals(false,AssistantReferences.queryContext(current).get("available"));
    }
    @Test void genuineZeroUsageIsKnownButPartialUsageIsNotSummedAsComplete(){
        var usage=new DefaultUsage(0,0,0,Map.of("prompt_tokens",0,"completion_tokens",0));
        var response=new ChatResponse(List.of(new Generation(new AssistantMessage("{}"))),ChatResponseMetadata.builder().usage(usage).model("real-model-name").build());
        var d=new PlanningDiagnostics();d.model("PLAN","fixed","input",()->response);var first=d.snapshot(REF,"ACCEPTED","d");assertEquals(true,first.get("usageComplete"));assertEquals(0L,first.get("inputTokens"));
        d.model("REVIEW","fixed","input",()->new ChatResponse(List.of(new Generation(new AssistantMessage("{}")))));var partial=d.snapshot(REF,"ACCEPTED","d");assertEquals(false,partial.get("usageComplete"));assertNull(partial.get("outputTokens"));
    }
    @Test void editingOnlyAnUpperBoundRetainsLowerBoundAndOtherConditions(){
        var lower=filter("amount","GTE","100000");var upper=filter("amount","LTE","200000");var date=filter("date","GTE","2026-09-01");var before=query(lower,upper,date);var after=query(lower,filter("amount","LTE","150000"),date);
        var d=QueryInterpretation.describe(before,after,COLUMNS,null,REF);assertEquals(1,((List<?>)d.get("added")).size());assertEquals(1,((List<?>)d.get("removed")).size());assertEquals(2,((List<?>)d.get("retained")).size());
        var state=state(before);var edited=AssistantInteraction.editedQuery(USER,state,new AssistantInteraction.QueryEdit(REF,after.conditions()));assertEquals(after,edited);assertEquals(before,state.getBusinessQuery());
        assertTrue(AssistantInteraction.editMessage(state,edited).contains("amount 小于等于 \"200000\""));
        assertDoesNotThrow(()->AssistantInteraction.requireRequestedQuery(new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,after,true,null),edited,COLUMNS));
        assertThrows(ApiException.class,()->AssistantInteraction.requireRequestedQuery(new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,query(filter("amount","LTE","150000")),true,null),edited,COLUMNS));
    }
    @Test void queryComparisonPreservesLogicAndTextIdentityWhileNormalizingExactNumbers(){
        assertEquals(QueryInterpretation.fingerprint(query(filter("amount","EQ","1.00")),COLUMNS),QueryInterpretation.fingerprint(query(filter("amount","EQ","1.0")),COLUMNS));
        assertNotEquals(QueryInterpretation.fingerprint(query(filter("docNo","EQ","001")),COLUMNS),QueryInterpretation.fingerprint(query(filter("docNo","EQ","1")),COLUMNS));
        var a=filter("amount","GTE","10");var b=filter("amount","LTE","20");var and=query(a,b);var or=new BusinessQuery(and.domain(),and.view(),and.reportIds(),and.companyCode(),List.of(new BusinessQuery.Group(List.of(a)),new BusinessQuery.Group(List.of(b))),null,false,1,20,null);
        assertNotEquals(QueryInterpretation.fingerprint(and,COLUMNS),QueryInterpretation.fingerprint(or,COLUMNS));assertEquals(true,QueryInterpretation.describe(and,or,COLUMNS,null,REF).get("groupingChanged"));
        assertEquals(QueryInterpretation.fingerprint(and,COLUMNS),QueryInterpretation.fingerprint(query(b,a),COLUMNS));
    }
    @Test void staleEditorsAndPermissionChangesNeverBecomeNewQueries(){
        var s=state(query());var edit=new AssistantInteraction.QueryEdit(REF,List.of());s.setBusinessQueryRef("b".repeat(32));assertEquals(409,assertThrows(ApiException.class,()->AssistantInteraction.editedQuery(USER,s,edit)).getCode());
        s.setBusinessQueryRef(REF);s.setBusinessUnresolved(true);assertThrows(ApiException.class,()->AssistantInteraction.editedQuery(USER,s,edit));s.setBusinessUnresolved(false);
        var other=new CurrentUser(USER.tenantId(),USER.userId(),"",Set.of("B"),Set.of("*"),false);assertThrows(ApiException.class,()->AssistantInteraction.editedQuery(other,s,edit));
        assertThrows(ApiException.class,()->AssistantInteraction.editedQuery(USER,s,new AssistantInteraction.QueryEdit(REF,List.of(new BusinessQuery.Group(List.of(filter("unknown","EQ","x")))))));
    }
    @Test void clarificationOptionsCannotInventIdentityOrBeUsedOutsideClarification(){
        var options=new ClarificationOptions(ClarificationOptions.Kind.COMPANY,null,null,null,null,List.of("A","B"));var context=AssistantPlanningContext.empty(p->{});
        assertThrows(ApiException.class,()->options.validate(Set.of(),Set.of("A"),context));assertDoesNotThrow(()->options.validate(Set.of(),Set.of("A","B"),context));
        assertThrows(ApiException.class,()->new AssistantPlan(AssistantPlan.Route.HELP,null,false,null,List.of(),null,options));assertThrows(ApiException.class,()->options.answer("C"));
        var s=state(query());s.setPendingClarification(new AssistantInteraction.PendingClarification(REF,"查询甲方销售数据",options,USER.permissionVersion(),"v1"));
        var answer=new AssistantInteraction.ClarificationReply(REF,"A");assertTrue(AssistantInteraction.clarificationMessage(USER,s,answer,"v1").contains("公司代码为 A"));
        assertThrows(ApiException.class,()->AssistantInteraction.clarificationMessage(USER,s,answer,"v2"));
    }
    @Test void nativePlanContractRequiresClarificationFieldAndRejectsUnknownOptions(){
        var plan=JsonUtil.toMap(ModelAssistantPlannerTest.HELP);plan.remove("clarificationOptions");assertThrows(ApiException.class,()->AssistantCodec.plan(JsonUtil.toJson(plan)));
        assertTrue(AssistantSchema.planSchema().contains("clarificationOptions"));
        var options=new ClarificationOptions(ClarificationOptions.Kind.COMPANY,null,null,null,null,List.of("A","B"));var clarify=new AssistantPlan(AssistantPlan.Route.CLARIFY,null,false,"请选择公司",List.of(),null,options);
        var model=new ModelAssistantPlannerTest.Model(JsonUtil.toJson(clarify)).expect(clarify,"查数据","公司有歧义");
        assertThrows(ApiException.class,()->new ModelAssistantPlanner(model,new AgentProperties()).plan("查数据",new DialogueState(),List.of(),Set.of("A")));
    }
    @Test void structuredHttpFormsDoNotCoerceValuesOrIgnoreUnknownFields(){
        assertThrows(ApiException.class,()->AssistantInteraction.decode(Map.of("clarificationRef",REF,"value",1),AssistantInteraction.ClarificationReply.class));
        assertThrows(ApiException.class,()->AssistantInteraction.decode(Map.of("clarificationRef",REF,"value","A","companyCode","B"),AssistantInteraction.ClarificationReply.class));
        assertThrows(ApiException.class,()->AssistantInteraction.decode(Map.of("queryRef",REF,"conditions",List.of(Map.of("allOf",List.of(Map.of("field","amount","operator","EQ","values",List.of(1)))))),AssistantInteraction.QueryEdit.class));
        var q=JsonUtil.toMap(JsonUtil.toJson(query(filter("amount","EQ","1"))));q.put("conditions",List.of(Map.of("allOf",List.of(Map.of("field","amount","operator","EQ","values",List.of(1))))));
        assertThrows(ApiException.class,()->AssistantCodec.query(q));
        assertDoesNotThrow(()->AssistantInteraction.decode(Map.of("queryRef",REF,"conditions",List.of()),AssistantInteraction.QueryEdit.class));
    }
    @Test void explicitChoicesCannotBeDroppedOrSwappedByEitherModelPlan(){
        var options=new ClarificationOptions(ClarificationOptions.Kind.COMPANY,null,null,null,null,List.of("A","B"));
        var selected=new AssistantInteraction.SelectedClarification(new AssistantInteraction.PendingClarification(REF,"查询销售报表",options,USER.permissionVersion(),"v1"),"A");
        var valid=new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,query(),false,null,List.of(),null);
        assertDoesNotThrow(()->AssistantInteraction.requireSelected(valid,selected,Map.of()));
        var q=query();var wrong=new BusinessQuery(q.domain(),q.view(),q.reportIds(),"B",q.conditions(),null,false,1,20,null);
        assertThrows(ApiException.class,()->AssistantInteraction.requireSelected(new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,wrong,false,null,List.of(),null),selected,Map.of()));
        var valueOptions=new ClarificationOptions(ClarificationOptions.Kind.VALUE,BusinessQuery.Domain.REPORT,"A","rpt-sales-order","status",List.of("待处理","已完成"));
        var value=new AssistantInteraction.SelectedClarification(new AssistantInteraction.PendingClarification(REF,"按状态查询",valueOptions,USER.permissionVersion(),"v1"),"待处理");
        assertThrows(ApiException.class,()->AssistantInteraction.requireSelected(valid,value,Map.of()));
        var withValue=new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,query(filter("status","NE","待处理")),false,null,List.of(),null);
        assertDoesNotThrow(()->AssistantInteraction.requireSelected(withValue,value,Map.of()),"选择原值不应把原问题的否定变成肯定");
        var or=new BusinessQuery(q.domain(),q.view(),q.reportIds(),q.companyCode(),List.of(new BusinessQuery.Group(List.of(filter("status","EQ","待处理"))),new BusinessQuery.Group(List.of(filter("amount","GT","1")))),null,false,1,20,null);
        assertThrows(ApiException.class,()->AssistantInteraction.requireSelected(new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,or,false,null,List.of(),null),value,Map.of()));
    }
    @Test void enumCandidatesCannotBeAssembledFromOtherCompaniesOrDomains(){
        var report=mock(com.example.report.catalog.CatalogEntry.class);when(report.reportId()).thenReturn("rpt-sales-order");when(report.fields()).thenReturn(COLUMNS);
        var a=definition(field("status",List.of(),"",List.of(),List.of(new SemanticDictionary.Value("X",List.of()),new SemanticDictionary.Value("Z",List.of()))));
        var b=definition(field("status",List.of(),"",List.of(),List.of(new SemanticDictionary.Value("Y",List.of()))));
        var snapshot=new SemanticDictionaryService.Snapshot(List.of(new SemanticDictionaryService.Entry(new SemanticDictionary.Scope(BusinessQuery.Domain.REPORT,"A","rpt-sales-order"),1,a),new SemanticDictionaryService.Entry(new SemanticDictionary.Scope(BusinessQuery.Domain.REPORT,"B","rpt-sales-order"),1,b)),Instant.now(),"v1");
        var context=new AssistantPlanningContext(List.of(),Map.of(),p->Map.of(),snapshot.context(),new PlanningDiagnostics(),List.of(report));
        var mixed=new ClarificationOptions(ClarificationOptions.Kind.VALUE,BusinessQuery.Domain.REPORT,"A","rpt-sales-order","status",List.of("X","Y"));
        assertThrows(ApiException.class,()->mixed.validate(Set.of("rpt-sales-order"),Set.of("A","B"),context));
        var own=new ClarificationOptions(ClarificationOptions.Kind.VALUE,BusinessQuery.Domain.REPORT,"A","rpt-sales-order","status",List.of("X","Z"));
        assertDoesNotThrow(()->own.validate(Set.of("rpt-sales-order"),Set.of("A","B"),context));
        var otherDomain=new ClarificationOptions(ClarificationOptions.Kind.VALUE,BusinessQuery.Domain.WORK_ORDER,"A","rpt-sales-order","status",List.of("X","Z"));
        assertThrows(ApiException.class,()->otherDomain.validate(Set.of("rpt-sales-order"),Set.of("A","B"),context));
    }
    @Test void completedChoicesAreCarriedAcrossClarificationsAndNeverRequestedAgain(){
        var company=new ClarificationOptions(ClarificationOptions.Kind.COMPANY,null,null,null,null,List.of("A","B"));
        var pending=new AssistantInteraction.PendingClarification(REF,"先选公司，再选字段查询",company,USER.permissionVersion(),"v1");
        var first=new AssistantInteraction.SelectedClarification(pending,"A");
        var repeat=new AssistantPlan(AssistantPlan.Route.CLARIFY,null,false,"请再次选公司",List.of(),null,company);
        assertThrows(ApiException.class,()->AssistantInteraction.requireSelected(repeat,first,Map.of()));
        var field=new ClarificationOptions(ClarificationOptions.Kind.FIELD,BusinessQuery.Domain.REPORT,"A","rpt-sales-order",null,List.of("amount","date"));
        var next=new AssistantPlan(AssistantPlan.Route.CLARIFY,null,false,"请选择字段",List.of(),null,field);
        assertDoesNotThrow(()->AssistantInteraction.requireSelected(next,first,Map.of()));
        var second=new AssistantInteraction.SelectedClarification(new AssistantInteraction.PendingClarification(REF,pending.message(),field,USER.permissionVersion(),"v1",first.choices()),"amount");
        assertEquals(2,second.choices().size());assertEquals(true,second.context().get("selectionCompleted"));
        var wrong=new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of("rpt-sales-order"),"B",List.of(),"amount",false,1,20,null),false,null,List.of(),null);
        assertThrows(ApiException.class,()->AssistantInteraction.requireSelected(wrong,second,Map.of()),"解决字段后也必须保留更早选择的A公司");
        var context=AssistantPlanningContext.empty(p->{}).withResolvedClarification(second.context()).withCatalog(List.of()).beforeCurrentMessage("本轮请求");
        assertEquals(2,((List<?>)context.resolvedClarification().get("resolvedChoices")).size());
    }
}
