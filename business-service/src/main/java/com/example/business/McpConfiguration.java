package com.example.business;

import com.example.report.common.ApiException;
import com.example.report.permission.CurrentUser;
import com.example.report.rule.Candidate;
import com.example.report.security.IdentityStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.*;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;

@Configuration
public class McpConfiguration {
    private final ObjectMapper json;
    private final IdentityStore identities;
    private final BusinessQueries queries;
    private final BusinessDispatch dispatch;
    public McpConfiguration(ObjectMapper json,IdentityStore identities,BusinessQueries queries,BusinessDispatch dispatch) {
        this.json=json.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        this.identities=identities;this.queries=queries;this.dispatch=dispatch;
    }
    @Bean public HttpServletStatelessServerTransport mcpTransport() {
        return HttpServletStatelessServerTransport.builder().jsonMapper(new JacksonMcpJsonMapper(json)).messageEndpoint("/mcp").build();
    }
    @Bean public ServletRegistrationBean<HttpServletStatelessServerTransport> mcpServlet(HttpServletStatelessServerTransport transport) {
        return new ServletRegistrationBean<>(transport,"/mcp");
    }
    @Bean(destroyMethod="close") public McpStatelessSyncServer mcpServer(HttpServletStatelessServerTransport transport) {
        var builder=McpServer.sync(transport).serverInfo("report-business-service","1.0.0")
                .jsonMapper(new JacksonMcpJsonMapper(json)).requestTimeout(Duration.ofSeconds(70))
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build());
        builder.tools(tool("report_catalog","列出操作者有权访问的报表及字段",true,props(),List.of(),a->queries.catalog(user(a))));
        builder.tools(tool("report_page","分页查询销售、应收、费用报表（含已派单状态）",true,
                props("reportCode",str(),"page",integer(1,100000),"size",integer(1,200)),List.of("reportCode","page","size"),
                a->queries.page(user(a),string(a,"reportCode"),integer(a,"page"),integer(a,"size"))));
        builder.tools(tool("report_records","有界查询待派单事实，支持游标、ID 复核和管理员试算",true,
                props("reportId",str(),"mode",Map.of("type","string","enum",List.of("page","cursor","ids","pendingIds","dryRun")),
                        "companies",array(100),"afterId",str(),"offset",integer(0,1000000),"size",integer(1,500),"recordIds",array(500)),
                List.of("reportId","mode","offset","size"),a->queries.records(user(a),string(a,"reportId"),string(a,"mode"),
                        a.containsKey("companies")?new HashSet<>(strings(a,"companies")):null,a.containsKey("afterId")?string(a,"afterId"):null,
                        integer(a,"offset"),integer(a,"size"),a.containsKey("recordIds")?strings(a,"recordIds"):null)));
        builder.tools(tool("dispatch_submit","提交已明确确认的清单条目；校验持久化确认、执行版本、权限、规则及幂等请求号",false,
                props("requestId",str(),"reportId",str(),"record",candidateSchema(),"enforceRules",Map.of("type","boolean"),"executionVersion",integer(1,Long.MAX_VALUE)),
                List.of("requestId","reportId","record","enforceRules","executionVersion"),a->dispatch.submit(user(a),string(a,"requestId"),string(a,"reportId"),
                        json.convertValue(a.get("record"),Candidate.class),(Boolean)a.get("enforceRules"),((Number)a.get("executionVersion")).longValue())));
        builder.tools(tool("dispatch_lookup","按稳定请求号查询派单结果；超时先核对，不得盲目重发",true,
                props("requestId",str(),"requestOperatorId",str()),List.of("requestId"),a->a.containsKey("requestOperatorId")
                        ?dispatch.lookupForOperator(user(a),string(a,"requestOperatorId"),string(a,"requestId"))
                        :dispatch.lookup(user(a),string(a,"requestId"))));
        // Internal configuration probe. Restricted to the authenticated orchestration service, not a model tool callback.
        builder.tools(tool("report_probe","服务端发布报表配置前检查表与字段，不返回业务记录",true,
                props("queryMode",str(),"queryConfig",Map.of("type","string","maxLength",65536)),List.of("queryMode","queryConfig"),
                a->queries.probe(string(a,"queryMode"),string(a,"queryConfig"))));
        return builder.build();
    }
    private CurrentUser user(Map<String,Object> a) {return identities.resolve(string(a,"tenantId"),string(a,"operatorId"));}
    private McpStatelessServerFeatures.SyncToolSpecification tool(String name,String description,boolean readOnly,
            Map<String,Object> properties,List<String> required,Function<Map<String,Object>,Object> action) {
        var all=new LinkedHashMap<>(properties); var req=new ArrayList<>(required);
        if(!name.equals("report_probe")) {all.put("tenantId",str());all.put("operatorId",str());req.add("tenantId");req.add("operatorId");}
        var schema=new McpSchema.JsonSchema("object",all,req,false,null,null);
        var tool=McpSchema.Tool.builder().name(name).description(description).inputSchema(schema)
                .annotations(new McpSchema.ToolAnnotations(name,readOnly,!readOnly,true,false,false)).build();
        return new McpStatelessServerFeatures.SyncToolSpecification(tool,(context,request)->{
            try {
                var args=request.arguments();
                if(args==null || !all.keySet().containsAll(args.keySet()) || !args.keySet().containsAll(req)) throw new ApiException("工具参数不符合契约");
                return result(Map.of("data",action.apply(args)),false);
            } catch(ApiException e) {return result(Map.of("code",e.getCode(),"message",e.getMessage()),true);}
            catch(IllegalArgumentException|ClassCastException e) {return result(Map.of("code",400,"message","工具参数类型不合法"),true);}
            catch(Exception e) {
                org.slf4j.LoggerFactory.getLogger(getClass()).error("MCP tool {} failed ({})",name,e.getClass().getSimpleName());
                return result(Map.of("code",503,"message","业务服务暂不可用，请按请求号核对派单结果"),true);
            }
        });
    }
    private McpSchema.CallToolResult result(Object value,boolean error) {
        try{return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(json.writeValueAsString(value))),error,value,null);}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    private static String string(Map<String,Object> a,String key) {if(!(a.get(key) instanceof String s) || s.isBlank() || s.length()>65536) throw new ApiException("缺少参数："+key);return s;}
    private static int integer(Map<String,Object> a,String key) {if(!(a.get(key) instanceof Number n) || n.doubleValue()!=n.intValue()) throw new ApiException("参数需为整数："+key);return n.intValue();}
    private List<String> strings(Map<String,Object> a,String key) {return json.convertValue(a.get(key),new TypeReference<>() {});}
    private static Map<String,Object> props(Object... pairs) {Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m;}
    private static Map<String,Object> str(){return Map.of("type","string","minLength",1,"maxLength",160);}
    private static Map<String,Object> integer(long min,long max){return Map.of("type","integer","minimum",min,"maximum",max);}
    private static Map<String,Object> array(int max){return Map.of("type","array","items",str(),"maxItems",max);}
    private static Map<String,Object> candidateSchema(){
        Map<String,Object> p=new LinkedHashMap<>();
        for(String k:List.of("reportId","reportName","recordId","docNo","companyCode","label","date","ruleName","ruleDescription")) p.put(k,Map.of("type",List.of("string","null"),"maxLength",2000));
        for(String k:List.of("amount","ruleId","ruleVersion","catalogVersion")) p.put(k,Map.of("type",List.of("number","null")));
        return Map.of("type","object","properties",p,"additionalProperties",false,"required",List.of("reportId","recordId","companyCode","catalogVersion"));
    }
}
