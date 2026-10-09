package com.example.report.assistant;

import com.example.report.catalog.*;
import com.example.report.catalog.query.*;
import com.example.report.common.*;
import com.example.report.config.AgentProperties;
import com.example.report.semantic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.openai.*;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import static com.example.report.support.TestCatalog.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 显式启用的真实模型统一规划/复核回放；目录和只读事实为合成数据，不连接数据库、HTTP MCP或执行派单。 */
@EnabledIfEnvironmentVariable(named="ASSISTANT_LIVE_REVIEW",matches="true")
class AssistantLiveReviewTest {
    private final List<Map<String,Object>> evidence=new ArrayList<>();
    private final List<Map<String,Object>> modelCalls=new ArrayList<>();
    private ModelAssistantPlanner planner;
    private List<CatalogEntry> reports;

    @Test void queryEligibilityAndExactDispatchUseTheSameReviewedTask() throws Exception {
        String model=Objects.requireNonNull(System.getenv("LLM_MODEL"),"必须配置实际模型");
        var http=new SimpleClientHttpRequestFactory();http.setConnectTimeout(10000);http.setReadTimeout(60000);
        var api=OpenAiApi.builder().baseUrl(Objects.requireNonNull(System.getenv("LLM_BASE_URL")))
                .apiKey(Objects.requireNonNull(System.getenv("LLM_API_KEY"))).completionsPath("/v1/chat/completions")
                .restClientBuilder(RestClient.builder().requestFactory(http)).build();
        var chat=OpenAiChatModel.builder().openAiApi(api).retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                .defaultOptions(OpenAiChatOptions.builder().model(model).build()).build();
        // 旁路保存真实入参和结果，区分规划错误、复核结构错误与程序差异；不替换任何模型响应。
        org.springframework.ai.chat.model.ChatModel recorded=new org.springframework.ai.chat.model.ChatModel() {
            @Override public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions(){return chat.getDefaultOptions();}
            @Override public org.springframework.ai.chat.model.ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
                var call=new LinkedHashMap<String,Object>();call.put("input",JsonUtil.toMap(prompt.getUserMessage().getText()));modelCalls.add(call);
                var response=chat.call(prompt);call.put("output",com.example.report.operations.SensitiveData.text(response.getResult().getOutput().getText()));return response;
            }
        };
        var props=new AgentProperties();props.getSemantic().setModel(model);
        props.getSemantic().setThinkingEnabled(Boolean.parseBoolean(System.getenv().getOrDefault("SEMANTIC_THINKING_ENABLED","false")));
        planner=new ModelAssistantPlanner(recorded,props);
        var entry=mock(CatalogEntry.class);when(entry.reportId()).thenReturn(SALES);when(entry.reportName()).thenReturn("销售报表");
        when(entry.dispatchEnabled()).thenReturn(true);when(entry.ref()).thenReturn(new ReportRef(SALES,"销售报表","sales","销售产品与金额",List.of("销售")));
        when(entry.fields()).thenReturn(List.of(new FieldInfo("productName","string","产品名称"),new FieldInfo("amount","decimal","金额，人民币元")));
        reports=List.of(entry);
        run("查一下销售报表服务器的数据",new DialogueState(),p->{
            assertEquals(AssistantPlan.Route.BUSINESS_QUERY,p.route());assertEquals(BusinessQuery.View.LIST,p.query().view());
            assertTrue(p.query().conditions().stream().flatMap(g->g.allOf().stream()).anyMatch(f->f.field().equals("productName") && f.operator().equals("EQ") && f.values().equals(List.of("服务器"))));
        });
        // 未在合成事实中出现的同类对象也必须保留条件；不能只从样本行推测用户的实际限定。
        for(String product:List.of("打印机","传感器"))run("我想看看"+product+"的销售数据",new DialogueState(),p->{
            assertEquals(AssistantPlan.Route.BUSINESS_QUERY,p.route());
            assertTrue(p.query().conditions().stream().flatMap(g->g.allOf().stream())
                    .anyMatch(f->f.field().equals("productName") && f.operator().equals("EQ") && f.values().equals(List.of(product))));
        });
        run("这条数据符合派单条件吗",queried(1,1),this::eligibility);
        run("帮我派单这条",queried(1,1),p->exact(p,queried(1,1),"1"));
        run("不用派单，只核验这条是否符合规则",queried(1,1),this::eligibility);
        run("这条符合派单条件吗",queried(2,2),p->assertEquals(AssistantPlan.Route.CLARIFY,p.route()));
        run("把上次查询的所有记录都准备清单",queried(2,60),p->assertEquals(AssistantPlan.Route.CLARIFY,p.route()));
        run("只处理刚展示的第二条，生成待确认清单",queried(2,60),p->exact(p,queried(2,60),"2"));
        // 澄清只阻止依赖未完成查询；本轮补齐编号后可结合真实对话重建完整只读核验。
        var clarified=queried(2,2);clarified.setBusinessUnresolved(true);clarified.setAssistantRoute("CLARIFY");
        clarified.setPhase(DialogueState.Phase.CLARIFY);clarified.setLastReason("请提供要核验的单据号");
        run("我指的是单据 SO2026002",clarified,List.of(
                Map.of("role","user","content","查询A公司的销售报表"),
                Map.of("role","assistant","content","本次查询匹配2条记录，已展示全部2条。"),
                Map.of("role","user","content","这条数据符合派单条件吗"),
                Map.of("role","assistant","content","当前有多条记录，请提供要核验的单据号。")),Map.of(),p->{
            assertEquals(AssistantPlan.Route.BUSINESS_QUERY,p.route());assertEquals(BusinessQuery.View.ELIGIBILITY,p.query().view());
            assertFalse(p.followUp());assertEquals(List.of(SALES),p.query().reportIds());assertEquals("A",p.query().companyCode());
            assertEquals(List.of(new BusinessQuery.Group(List.of(new BusinessQuery.Filter("docNo","EQ",List.of("SO2026002"))))),p.query().conditions());
        });
        // 页面建立并取消的新清单仍可查看；复核不能把终态误判为需要重新刷新候选。
        var cancelled=queried(1,1);cancelled.setAssistantFocus("DISPATCH");cancelled.setAssistantRoute("DISPATCH");
        cancelled.setPhase(DialogueState.Phase.READY);cancelled.setPreviewId("browser-preview");cancelled.setPlanId("browser-current-plan");
        run("查看当前清单",cancelled,List.of(
                Map.of("role","user","content","帮我派单这条"),
                Map.of("role","assistant","content","已生成待确认清单，共1条，请核对并确认。")),
                Map.of("sourceRef",AssistantReferences.planRef(cancelled),"itemCount",1,"planStatus","CANCELLED"),p->{
            assertEquals(AssistantPlan.Route.DISPATCH,p.route());assertEquals(SemanticIntent.Action.SHOW_RESULT,p.dispatch().intent().action());
            assertEquals(DispatchDirective.Source.PLAN,p.dispatch().source());assertEquals(AssistantReferences.planRef(cancelled),p.dispatch().sourceRef());
        });
        run("重新核对当前候选",cancelled,List.of(
                Map.of("role","user","content","查看当前清单"),
                Map.of("role","assistant","content","当前清单状态：已取消；共1条，成功0条，失败0条。")),
                Map.of("sourceRef",AssistantReferences.planRef(cancelled),"itemCount",1,"planStatus","CANCELLED"),p->{
            assertEquals(AssistantPlan.Route.DISPATCH,p.route());assertEquals(SemanticIntent.Action.PREVIEW,p.dispatch().intent().action());
            assertEquals(DispatchDirective.Source.PREVIEW,p.dispatch().source());assertEquals(AssistantReferences.previewRef(cancelled),p.dispatch().sourceRef());
        });
        var output=Map.of("model",model,"thinkingEnabled",props.getSemantic().isThinkingEnabled(),"scope","真实模型规划与独立复核；合成目录及事实；未运行数据库、HTTP MCP、浏览器或业务执行",
                "recordedAt",java.time.OffsetDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).toString(),"cases",evidence);
        Files.createDirectories(Path.of("target"));Files.writeString(Path.of("target/assistant-live-review.json"),JsonUtil.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(output));
        assertEquals(12,evidence.size());assertEquals(0,evidence.stream().filter(e->!Boolean.TRUE.equals(e.get("passed"))).count(),"见target/assistant-live-review.json");
    }

    /** 用固定预期逐条评分；失败继续留证，绝不从模型产出的对象集合反推正确答案。 */
    private void run(String message,DialogueState state,Consumer<AssistantPlan> verify) {
        run(message,state,List.of(),Map.of(),verify);
    }
    /** 浏览器发现的恢复边界用有限对话及独立终态事实复现；事实为合成输入，不能当作浏览器执行证据。 */
    private void run(String message,DialogueState state,List<Map<String,String>> history,Map<String,Object> planEvidence,Consumer<AssistantPlan> verify) {
        var result=new LinkedHashMap<String,Object>();result.put("message",message);int firstCall=modelCalls.size();
        try {
            var plan=planner.plan(message,state,reports,Set.of("A"),new AssistantPlanningContext(history,Map.of(),draft->{
                if(draft.dispatch()!=null) {
                    if(!planEvidence.isEmpty()) {
                        if(draft.dispatch().source()==DispatchDirective.Source.PLAN)return planEvidence;
                        if(draft.dispatch().source()==DispatchDirective.Source.PREVIEW)return Map.of(
                                "sourceRef",AssistantReferences.previewRef(state),"excludedCount",0,
                                "scope",Map.of("companyCode","A","allReports",false,"reportIds",List.of(SALES)),
                                "selectionAfter",Map.of("complete",true,"totalCount",1,"selectedCount",1,"selectedRows",List.of(reference(1))));
                    }
                    var targets=AssistantReferences.resolveQuery(state,draft.dispatch());
                    return Map.of("targetRecords",targets,"targetCount",targets.size(),"qualification","PREPARATION_WILL_RECHECK_CURRENT_RULES_AND_STATUS");
                }
                if(draft.query()==null)return Map.of();
                var columns=BusinessFields.forDomain(BusinessQuery.Domain.REPORT,reports.get(0).fields());
                var rows=new ArrayList<Map<String,Object>>();
                for(int i=1;i<=2;i++) {
                    var row=new LinkedHashMap<String,Object>(reference(i));row.put("rowKey",SALES+":"+i);row.put("amount",i==1?"128000":"96000");row.put("status","未派单");
                    if(draft.query().view()==BusinessQuery.View.ELIGIBILITY)row.put("eligibility",new DispatchEligibility(true,"合成事实满足规则","1","金额规则",1,"金额大于20元",List.of()));
                    rows.add(row);
                }
                return BusinessAssistantService.queryEvidence(BusinessQueryEngine.execute(draft.query(),columns,rows,"合成只读事实"));
            }));
            result.put("plan",plan);verify.accept(plan);result.put("passed",true);
        } catch(Exception|AssertionError failure) {
            result.put("passed",false);result.put("error",com.example.report.operations.SensitiveData.text(Objects.toString(failure.getMessage(),failure.getClass().getSimpleName())));
        }
        result.put("modelCalls",List.copyOf(modelCalls.subList(firstCall,modelCalls.size())));evidence.add(result);
    }
    private void eligibility(AssistantPlan plan) {
        assertEquals(AssistantPlan.Route.BUSINESS_QUERY,plan.route());assertEquals(BusinessQuery.View.ELIGIBILITY,plan.query().view());
        assertEquals(List.of(SALES),plan.query().reportIds());assertEquals("A",plan.query().companyCode());assertTrue(plan.followUp());
    }
    private void exact(AssistantPlan plan,DialogueState state,String id) {
        assertEquals(AssistantPlan.Route.DISPATCH,plan.route());assertEquals(SemanticIntent.Action.PREPARE_DISPATCH,plan.dispatch().intent().action());
        assertEquals(List.of(id),AssistantReferences.resolveQuery(state,plan.dispatch()).stream().map(t->t.key().recordId()).toList());
    }
    private DialogueState queried(int displayed,int total) {
        var state=new DialogueState();state.setAssistantFocus("BUSINESS_QUERY");state.setBusinessPermissionVersion("synthetic-permission");
        state.setBusinessQuery(new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(SALES),"A",List.of(),null,false,1,20,null));
        state.setBusinessReferences(java.util.stream.IntStream.rangeClosed(1,displayed).mapToObj(this::reference).toList());state.setBusinessTotalCount((long)total);return state;
    }
    private Map<String,String> reference(int index) {
        return Map.of("reportId",SALES,"recordId",String.valueOf(index),"docNo","SO202600"+index,"companyCode","A","displayIndex",String.valueOf(index),"productName",index==1?"服务器":"交换机");
    }
}
