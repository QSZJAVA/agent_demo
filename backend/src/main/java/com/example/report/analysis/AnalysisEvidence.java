package com.example.report.analysis;

import com.example.report.common.*;
import com.example.report.operations.SensitiveData;
import java.util.*;

/** 分析及知识回答的证据与引用契约；引用必须来自本轮授权证据中的原文，模型解释另经独立依据复核。 */
public final class AnalysisEvidence {
    private AnalysisEvidence() { }
    private static final java.util.regex.Pattern NUMBERS=java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])[-+]?\\d+(?:\\.\\d+)?(?![A-Za-z0-9_])");
    /**
     * 一个授权且有版本的引用单元，不含可执行指令或永久权限。
     * @param id 本轮唯一来源编号
     * @param kind DATA确定性数据或KNOWLEDGE业务定义/制度
     * @param title 人可读来源名称
     * @param version 来源版本、内容摘要或快照标识
     * @param scope 明确的公司和报表范围
     * @param observedAt 观测时间或来源生效时间说明
     * @param text 可引用的完整片段，超过预算在建立证据前拆分，不能无标记截断
     */
    public record Source(String id,String kind,String title,String version,String scope,String observedAt,String text) {
        public Source {AnalysisContract.text(id,128,"证据编号");AnalysisContract.text(title,300,"来源名称");AnalysisContract.text(version,128,"来源版本");
            AnalysisContract.text(scope,200,"来源范围");AnalysisContract.text(observedAt,200,"来源时间");AnalysisContract.text(text,16000,"来源原文");
            if(!Set.of("DATA","KNOWLEDGE").contains(kind))AnalysisContract.fail("证据类型无效");}
    }
    /**
     * 引用只接受给定证据原文，不接受模型自造链接或版本。
     * @param sourceId 本轮实际来源编号
     * @param quote 来源中的连续原文，保留数值和条件
     */
    public record Citation(String sourceId,String quote) {
        public Citation {AnalysisContract.text(sourceId,128,"引用编号");AnalysisContract.text(quote,2000,"引用原文");}
    }
    /**
     * 一条待复核的说明；确定性金额仍由步骤结果展示。
     * @param text 有依据的业务说明，不把制度描述当作已执行事实
     * @param citations 支撑该说明的一至四个实际原文引用
     */
    public record Claim(String text,List<Citation> citations) {
        public Claim {AnalysisContract.text(text,1500,"回答说明");if(citations==null||citations.isEmpty()||citations.size()>4)AnalysisContract.fail("说明需要一至四份依据");citations=List.copyOf(citations);}
    }
    /**
     * 模型仅选择实际片段编号，不承担长引用的逐字抄写；原文由服务器回填。
     * @param text 待验证的业务说明，最多1500字符
     * @param sourceIds 支持此说明的实际来源编号，一至四个且不能重复
     */
    public record DraftClaim(String text,List<String> sourceIds) {
        public DraftClaim {AnalysisContract.text(text,1500,"回答说明");if(sourceIds==null||sourceIds.isEmpty()||sourceIds.size()>4||new HashSet<>(sourceIds).size()!=sourceIds.size())AnalysisContract.fail("说明必须选择一至四个不同的来源编号");sourceIds=List.copyOf(sourceIds);for(String id:sourceIds)AnalysisContract.text(id,128,"引用编号");}
    }
    /**
     * 模型回答草稿；只有在编号、原文数值和独立语义复核通过后才生成对外Answer。
     * @param status ANSWERED、INSUFFICIENT或CONFLICTING
     * @param claims 待验证说明，最多八项
     * @param limitations 依据不足、冲突和未完成事项，最多八项
     */
    public record Draft(String status,List<DraftClaim> claims,List<String> limitations) {
        public Draft {if(!Set.of("ANSWERED","INSUFFICIENT","CONFLICTING").contains(Objects.toString(status,""))||claims==null||claims.size()>8||limitations==null||limitations.size()>8)AnalysisContract.fail("答案结构无效");claims=List.copyOf(claims);limitations=List.copyOf(limitations);}
    }
    /**
     * 回答区分有依据、依据不足和互相冲突；不允许无引用的肯定结论。
     * @param status ANSWERED、INSUFFICIENT或CONFLICTING
     * @param claims 有证据支持的说明，最多八项
     * @param limitations 未解决问题和适用边界，不作为新增事实
     */
    public record Answer(String status,List<Claim> claims,List<String> limitations) {
        public Answer {if(!Set.of("ANSWERED","INSUFFICIENT","CONFLICTING").contains(Objects.toString(status,""))||claims==null||claims.size()>8||limitations==null||limitations.size()>8)AnalysisContract.fail("答案结构无效");claims=List.copyOf(claims);limitations=List.copyOf(limitations);}
    }

    /** 用本轮来源全文回填不可伪造的引用；缺失编号直接拒绝，不采用近似编号、默认来源或自动补引。 */
    public static Answer resolve(Draft draft,List<Source> sources) {
        var known=new HashMap<String,Source>();for(var source:sources)if(known.put(source.id(),source)!=null)AnalysisContract.fail("本轮证据编号重复");
        var claims=new ArrayList<Claim>();
        for(var claim:draft.claims()){var citations=new ArrayList<Citation>();for(String id:claim.sourceIds()){
            var source=known.get(id);if(source==null)AnalysisContract.fail("说明引用了本轮不存在的来源编号："+id);citations.add(new Citation(id,source.text()));}
            claims.add(new Claim(claim.text(),citations));}
        var answer=new Answer(draft.status(),claims,draft.limitations());validate(answer,sources);return answer;
    }
    /** 长步骤证据拆成可完整引用的片段；每片保留同一版本与范围，不能声称片段等于完整行集合。 */
    public static List<Source> parts(Source source) {
        if(source.text().length()<=2000)return List.of(source);
        var result=new ArrayList<Source>();String text=source.text();int start=0,number=1;
        while(start<text.length()) {
            int end=Math.min(start+1800,text.length());
            if(end<text.length()){int newline=text.lastIndexOf('\n',end);if(newline>start+600)end=newline+1;else if(Character.isHighSurrogate(text.charAt(end-1)))end--;}
            result.add(new Source(source.id()+"-q"+number,source.kind(),source.title()+"（引用片段"+number+"）",source.version(),source.scope(),source.observedAt(),text.substring(start,end)));
            start=end;number++;
        }
        return List.copyOf(result);
    }

    /** 先核对精确引用及金额字面量，再由独立模型验证语义蕴含，不能凭来源编号存在就判定答案正确。 */
    public static void validate(Answer answer,List<Source> sources) {
        if(answer==null||!Set.of("ANSWERED","INSUFFICIENT","CONFLICTING").contains(Objects.toString(answer.status(),""))
                ||answer.claims()==null||answer.claims().size()>8||answer.limitations()==null||answer.limitations().size()>8)AnalysisContract.fail("答案结构无效");
        var known=new HashMap<String,Source>();for(var source:sources)if(known.put(source.id(),source)!=null)AnalysisContract.fail("本轮证据编号重复");
        if("ANSWERED".equals(answer.status())&&(answer.claims().isEmpty()||sources.isEmpty()))AnalysisContract.fail("没有证据不能发布确定性回答");
        if(!"ANSWERED".equals(answer.status())&&answer.limitations().isEmpty())AnalysisContract.fail("依据不足或冲突时必须说明未解决事项");
        for(var limitation:answer.limitations())AnalysisContract.text(limitation,1000,"答案限制");
        for(int index=0;index<answer.claims().size();index++) {var claim=answer.claims().get(index);String path="claims["+index+"]";
            AnalysisContract.text(claim.text(),1500,"回答说明");
            if(claim.citations()==null||claim.citations().isEmpty()||claim.citations().size()>4)AnalysisContract.fail("每条说明必须引用实际来源");
            var quoted=new StringBuilder();
            for(var citation:claim.citations()) {
                var source=known.get(citation.sourceId());AnalysisContract.text(citation.quote(),2000,"引用原文");
                if(source==null)AnalysisContract.fail(path+"引用编号不在本轮授权来源中");
                if(!source.text().contains(citation.quote()))AnalysisContract.fail(path+"引用"+source.id()+"的quote不是text中的连续原文；不能拼接、改写或增加转义字符");
                quoted.append(source.title()).append(' ').append(source.version()).append(' ').append(source.scope()).append(' ').append(source.observedAt()).append(' ').append(citation.quote()).append(' ');
            }
            // 数字事实只准复述引用中出现的完整数值；不能由模型自行相加、换算或生成新的百分比。
            var allowed=numberValues(quoted.toString());
            var found=NUMBERS.matcher(claim.text());while(found.find())if(!allowed.contains(canonicalNumber(found.group())))AnalysisContract.fail(path+"的数字"+found.group()+"没有被该条引用支持；请补充包含该数字的原文引用，或删去这项说明");
        }
    }
    /**
     * 给有界修正提供缺失数字出现在哪些真实片段的候选，不自动修改引用或断言这些片段支持语义。
     * @param draft 刚被拒绝的模型草稿
     * @param sources 本轮已经授权并实际产生的来源，不能另查或扩展范围
     * @return 最多十二项数字缺口，每项最多四个候选编号；仍须模型选择及独立依据复核
     */
    public static List<Map<String,Object>> citationHints(Draft draft,List<Source> sources) {
        var values=new LinkedHashMap<String,Set<String>>();
        for(var source:sources)values.put(source.id(),numberValues(source.title()+" "+source.version()+" "+source.scope()+" "+source.observedAt()+" "+source.text()));
        var result=new ArrayList<Map<String,Object>>();
        for(int index=0;index<draft.claims().size();index++) {
            var claim=draft.claims().get(index);var supported=new HashSet<String>();claim.sourceIds().forEach(id->supported.addAll(values.getOrDefault(id,Set.of())));
            var missing=new LinkedHashSet<String>();var matcher=NUMBERS.matcher(claim.text());while(matcher.find()){String value=canonicalNumber(matcher.group());if(!supported.contains(value))missing.add(value);}
            for(String value:missing){result.add(Map.of("claimIndex",index,"number",value,"candidateSourceIds",values.entrySet().stream().filter(e->e.getValue().contains(value)).map(Map.Entry::getKey).limit(4).toList()));if(result.size()==12)return List.copyOf(result);}
        }
        return List.copyOf(result);
    }
    private static Set<String> numberValues(String text){var result=new HashSet<String>();var matcher=NUMBERS.matcher(text);while(matcher.find())result.add(canonicalNumber(matcher.group()));return result;}
    private static String canonicalNumber(String value){return new java.math.BigDecimal(value).stripTrailingZeros().toPlainString();}
    /** 展示与出站前按共同脱敏规则转换；引用校验使用的也是这份原文，避免脱敏后仍引用未公开的内容。 */
    public static String safeText(String text){return SensitiveData.text(com.example.report.operations.ModelEgressPolicy.redact(text));}
    public static String schema() {
        return schema(List.of());
    }
    /** 原生回答Schema使用本轮实际编号枚举；静态配置摘要仍使用无运行数据的结构版本。 */
    public static String schema(List<Source> sources) {
        var sourceId=sources.isEmpty()?AnalysisContract.string(128):AnalysisContract.enumeration(sources.stream().map(Source::id).distinct().toArray(String[]::new));
        var claim=AnalysisContract.object(AnalysisContract.fields("text",AnalysisContract.string(1500),"sourceIds",AnalysisContract.described(AnalysisContract.array(sourceId,4),"选择一至四个实际来源id；同一条说明中的每个数字都须出现在该条所选片段中。服务器回填原文，不生成quote")));
        return JsonUtil.toJson(AnalysisContract.object(AnalysisContract.fields("status",AnalysisContract.enumeration("ANSWERED","INSUFFICIENT","CONFLICTING"),
                "claims",AnalysisContract.array(claim,8),"limitations",AnalysisContract.array(AnalysisContract.string(1000),8))));
    }
}
