package com.example.report.investigation;

import com.example.report.common.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** 调查模型的五个只读工具；严格参数白名单、运行内引用、分页和预算，模型不能传入身份或任意请求号。 */
@Service
@ConditionalOnProperty(name="security.enabled", havingValue="true")
public class InvestigationTools {
    private static final String PREFIX="investigation_";
    private static final Map<String,String> DESCRIPTIONS=Map.of(
            PREFIX+"plan_summary","读取绑定清单和所选范围的快照概要，区分整体计数与调查条目数。",
            PREFIX+"plan_items","分页读取调查条目状态、错误码与摘要；只覆盖返回页，使用nextCursor读后续页。",
            PREFIX+"rule_snapshots","读取条目执行时的规则快照；缺失不等于当前规则适用，规则存在不证明失败原因。",
            PREFIX+"execution_events","分页读取所选条目的执行证据，不含聊天文本；截断时不能宣称完整历史。",
            PREFIX+"dispatch_lookup","通过原请求号核对真实业务结果；UNKNOWN或NOT_FOUND均不授权重发。每批最多5条。");
    private final InvestigationEvidenceStore evidence;
    private final InvestigationMcpReader mcp;
    private final InvestigationRepository repository;
    private final InvestigationAccessPolicy access;
    private final InvestigationProperties props;
    public InvestigationTools(InvestigationEvidenceStore evidence,InvestigationMcpReader mcp,InvestigationRepository repository,InvestigationAccessPolicy access,InvestigationProperties props) {
        this.evidence=evidence;this.mcp=mcp;this.repository=repository;this.access=access;this.props=props;
    }
    /** 仅提供Schema定义；框架内执行被禁用，真实调用必须经过应用循环的参数、范围和预算校验。 */
    public static List<ToolCallback> definitions() {
        return DESCRIPTIONS.keySet().stream().sorted().map(name -> (ToolCallback)new ToolCallback() {
            public ToolDefinition getToolDefinition() {return ToolDefinition.builder().name(name).description(DESCRIPTIONS.get(name)).inputSchema(InvestigationJson.canonical(schema(name))).build();}
            public String call(String input) {throw new IllegalStateException("调查工具必须通过受控循环执行");}
        }).toList();
    }
    public static String schemaJson() {return InvestigationJson.canonical(DESCRIPTIONS.keySet().stream().sorted().map(name -> Map.of("name",name,"description",DESCRIPTIONS.get(name),"parameters",schema(name))).toList());}
    private static Map<String,Object> schema(String name) {
        var fields=new LinkedHashMap<String,Object>();var required=new ArrayList<String>();
        if(!name.endsWith("plan_summary") && !name.endsWith("plan_items")) {
            fields.put("itemRefs",Map.of("type","array","minItems",1,"maxItems",name.endsWith("dispatch_lookup")?5:10,"items",Map.of("type","string","pattern","^I[1-9][0-9]*$")));required.add("itemRefs");
        }
        if(name.endsWith("plan_items") || name.endsWith("execution_events")) {
            fields.put("cursor",Map.of("type",List.of("string","null")));fields.put("size",Map.of("type","integer","minimum",1,"maximum",20));required.add("size");
        }
        return Map.of("type","object","properties",fields,"required",required,"additionalProperties",false);
    }
    public static boolean allowed(String name) {return name!=null && DESCRIPTIONS.containsKey(name);}
    /** 单次工具调用；返回结果有界，证据保存成功后才回传引用，禁止返回任意查询原始对象。 */
    public Map<String,Object> execute(InvestigationSession s,String name,JsonNode args) {
        s.check(true); validate(s,name,args);
        Map<String,Object> data;String type;List<String> refs=refs(args);boolean truncated=false;
        switch(name) {
            case PREFIX+"plan_summary" -> {type="PLAN_SNAPSHOT";data=Map.of("status",s.snapshot.get("status"),"executionVersion",s.snapshot.get("executionVersion"),"counts",s.snapshot.get("counts"),"selectedCount",s.items.size(),"snapshotAt",s.snapshot.get("snapshotAt"));}
            case PREFIX+"plan_items" -> {
                type="ITEM_SNAPSHOT";
                var rows=s.items.stream().map(i -> pick(i,List.of("itemRef","docNo","label","companyCode","status","errorCode","errorMessage","attemptCount"))).toList();
                data=page(s,name,args,rows);refs=itemRefs(data);
            }
            case PREFIX+"rule_snapshots" -> {
                type="RULE_SNAPSHOT";var rows=new ArrayList<Map<String,Object>>();
                for(String ref:refs) {
                    var row=new LinkedHashMap<String,Object>();row.put("itemRef",ref);Object rule=s.item(ref).get("rule");
                    if(rule==null) row.put("missing",true);
                    else {
                        @SuppressWarnings("unchecked") var original=(Map<String,Object>)rule;
                        var selected=pick(original,List.of("id","ruleId","version","kind","name","ruleName","expression","description","source"));
                        // 这是模型展示副本，包含expression在内的短文本也必须先脱敏；不能修改执行快照。
                        for(var e:selected.entrySet()) if(e.getValue() instanceof String text) {
                            String safe=com.example.report.operations.SensitiveData.text(text);
                            truncated|=safe.length()>180;
                            e.setValue(safe.length()>180?safe.substring(0,180):safe);
                        }
                        row.put("rule",selected);row.put("missing",false);
                    }
                    rows.add(row);
                }
                data=Map.of("items",rows,"complete",!truncated);
            }
            case PREFIX+"execution_events" -> {
                type="EXECUTION_EVENT";var rows=new ArrayList<Map<String,Object>>();long total=0;
                for(String ref:refs) {
                    var item=s.item(ref);total+=((Number)item.get("eventCount")).longValue();truncated|=Boolean.TRUE.equals(item.get("eventsTruncated"));
                    @SuppressWarnings("unchecked") var events=(List<Map<String,Object>>)item.get("events");
                    for(var event:events) {var row=new LinkedHashMap<>(event);row.put("itemRef",ref);rows.add(row);}
                }
                var paged=new LinkedHashMap<>(page(s,name,args,rows));paged.put("sourceTotal",total);data=paged;
            }
            case PREFIX+"dispatch_lookup" -> {
                // 参数错误可由模型纠正，必须在任何远端读取和计数之前检查整批资格。
                for(String ref:refs) if(s.item(ref).get("requestId")!=null && s.lookupAttempts.getOrDefault(ref,0)>=2)
                    throw new ApiException("同一条目最多核对两次");
                type="MCP_LOOKUP";var rows=new ArrayList<Map<String,Object>>();var evidenceIds=new ArrayList<String>();
                for(String ref:refs) {
                    s.check(true);var item=s.item(ref);var row=new LinkedHashMap<String,Object>();row.put("itemRef",ref);row.put("observedAt",Instant.now().toString());
                    if(item.get("requestId")==null) row.put("error","NO_REQUEST_ID");
                    else {
                        s.budget.mcp();repository.countMcp(s.id,s.token);
                        s.lookupAttempts.merge(ref,1,Integer::sum);
                        try {
                            var actor=access.current(s.actor.tenantId(),s.actor.userId());
                            var result=mcp.lookup(actor,s.snapshot.get("ownerId").toString(),item.get("requestId").toString(),s.budget.remaining(props.getMcpTimeoutSeconds()));
                            row.put("status",result.status().name());row.put("errorCode",result.errorCode()==null?null:InvestigationFacts.bounded(result.errorCode(),64));row.put("message",InvestigationFacts.bounded(Objects.toString(result.message(),""),200));
                            if(!"UNKNOWN".equals(result.status().name())) s.lookupAttempts.put(ref,2);
                        } catch(ApiException error) {
                            if(Set.of(401,403,404).contains(error.getCode())) throw new InvestigationFailure("ACCESS_REVOKED","业务服务拒绝调查读取，请检查当前权限");
                            row.put("error","TOOL_UNAVAILABLE");s.partialReason="TOOL_UNAVAILABLE";
                        } catch(InvestigationFailure error) {
                            if(!"TOOL_UNAVAILABLE".equals(error.reason())) throw error;row.put("error",error.reason());s.partialReason=error.reason();
                        }
                    }
                    // 小体积配置下仅裁剪补充文本，状态枚举与条目身份必须保留；不能先查询再因响应过大丢失事实。
                    var content=Map.<String,Object>of("items",List.of(row),"complete",!row.containsKey("error"));
                    for(String detail:List.of("message","errorCode")) if(JsonUtil.toJson(content).getBytes(StandardCharsets.UTF_8).length>props.getMaxToolResultUtf8Bytes()-256) {
                        row.remove(detail);row.put("detailsTruncated",true);
                    }
                    // 每条事实即时保存；后续条目预算耗尽或来源变化时，报告阶段仍可引用已取得的结果。
                    evidenceIds.add(evidence.add(s,type,List.of(ref),content,false));
                    rows.add(row);
                }
                data=Map.of("items",rows,"complete",rows.stream().noneMatch(row -> row.containsKey("error")));
                if(JsonUtil.toJson(data).getBytes(StandardCharsets.UTF_8).length>props.getMaxToolResultUtf8Bytes()-256)
                    data=Map.of("complete",data.get("complete"),"message","核对结果已保存，完整证据将在报告阶段提供。");
                return Map.of("ok",true,"data",data,"evidenceIds",evidenceIds,"observedAt",Instant.now().toString(),"truncated",false);
            }
            default -> throw new InvestigationFailure("INVALID_TOOL","模型请求了未授权工具");
        }
        if(JsonUtil.toJson(data).getBytes(StandardCharsets.UTF_8).length>props.getMaxToolResultUtf8Bytes()-256) throw new ApiException("工具结果较大，请减少单次条目或分页大小");
        String ref=evidence.add(s,type,refs,data,truncated);
        return Map.of("ok",true,"data",data,"evidenceIds",List.of(ref),"observedAt",Instant.now().toString(),"truncated",truncated);
    }
    /** 只接受Schema中字段和正确JSON类型；任何范围外引用整批拒绝。 */
    private void validate(InvestigationSession s,String name,JsonNode args) {
        if(!allowed(name) || args==null || !args.isObject()) throw new ApiException("工具参数必须是对象");
        @SuppressWarnings("unchecked") var fields=(Map<String,Object>)schema(name).get("properties");
        var keys=new HashSet<String>();args.fieldNames().forEachRemaining(keys::add);if(!fields.keySet().containsAll(keys)) throw new ApiException("工具参数含未允许字段");
        @SuppressWarnings("unchecked") var required=(List<String>)schema(name).get("required");
        if(required.stream().anyMatch(k -> !args.has(k))) throw new ApiException("工具参数缺少必要字段");
        if(args.has("size") && (!args.get("size").isInt() || args.get("size").asInt()<1 || args.get("size").asInt()>20)) throw new ApiException("分页大小须为1～20的整数");
        if(args.hasNonNull("cursor") && (!args.get("cursor").isTextual() || args.get("cursor").asText().length()>256)) throw new ApiException("分页游标无效");
        if(args.has("itemRefs")) {
            var array=args.get("itemRefs");if(!array.isArray() || array.isEmpty() || array.size()>(name.endsWith("dispatch_lookup")?5:10)) throw new ApiException("工具条目数量无效");
            var seen=new HashSet<String>();for(var node:array) {if(!node.isTextual() || !seen.add(node.asText())) throw new ApiException("条目引用须为不同的字符串");s.item(node.asText());}
        }
    }
    private Map<String,Object> page(InvestigationSession s,String name,JsonNode args,List<Map<String,Object>> rows) {
        String binding=Digests.sha256(s.id+"|"+name+"|"+refs(args));int offset=0;
        if(args.hasNonNull("cursor")) {
            String[] parts=args.get("cursor").asText().split(":");
            if(parts.length!=2 || !binding.equals(parts[0]) || !parts[1].matches("[0-9]{1,5}")) throw new ApiException("游标不属于当前运行和工具范围");offset=Integer.parseInt(parts[1]);
        }
        if(offset>rows.size()) throw new ApiException("游标超过事实范围");int end=Math.min(rows.size(),offset+args.get("size").asInt());
        var data=new LinkedHashMap<String,Object>();
        while(true) {
            data.put("items",rows.subList(offset,end));data.put("nextCursor",end<rows.size()?binding+":"+end:null);data.put("complete",end==rows.size());data.put("total",rows.size());
            if(JsonUtil.toJson(data).getBytes(StandardCharsets.UTF_8).length<=props.getMaxToolResultUtf8Bytes()-512) return data;
            if(end<=offset+1) throw new ApiException("单条证据过大，请查看原始追溯");end--;
        }
    }
    private static Map<String,Object> pick(Map<String,Object> source,List<String> fields) {var out=new LinkedHashMap<String,Object>();for(String key:fields) if(source.containsKey(key)) out.put(key,source.get(key));return out;}
    private static List<String> refs(JsonNode args) {if(!args.has("itemRefs")) return List.of();var out=new ArrayList<String>();for(var ref:args.get("itemRefs")) out.add(ref.asText());return out;}
    @SuppressWarnings("unchecked") private static List<String> itemRefs(Map<String,Object> data) {return ((List<Map<String,Object>>)data.get("items")).stream().map(i -> i.get("itemRef").toString()).toList();}
}
