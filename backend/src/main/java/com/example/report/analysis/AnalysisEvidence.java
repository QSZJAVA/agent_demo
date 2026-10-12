package com.example.report.analysis;

import com.example.report.common.*;
import com.example.report.operations.SensitiveData;
import java.time.*;
import java.util.*;
import java.util.regex.*;

/** 分析及知识回答的证据与引用契约；引用必须来自本轮授权证据中的原文，模型解释另经独立依据复核。 */
public final class AnalysisEvidence {
    /** 引用与检索正文边界的协议版本；进入执行和知识指纹，不恢复旧格式来源。 */
    public static final String SOURCE_VERSION="evidence-source-4";
    private AnalysisEvidence() { }
    private static final Pattern NUMBERS=Pattern.compile("(?<![A-Za-z0-9_])[-+]?\\d+(?:\\.\\d+)?(?![A-Za-z0-9_])");
    private static final String CALENDAR_DATE="(?:\\d{4}-\\d{1,2}-\\d{1,2}|\\d{4}/\\d{1,2}/\\d{1,2}|\\d{4}年\\d{1,2}月\\d{1,2}日)";
    private static final String CALENDAR_MONTH="\\d{4}(?:-\\d{1,2}|/\\d{1,2}|年\\d{1,2}月)";
    private static final String MONTH_DAY="\\d{1,2}月\\d{1,2}日";
    private static final String TEMPORAL_START="(?<![A-Za-z0-9_.+/-])(?<!\\d:)";
    private static final String TEMPORAL_END="(?![A-Za-z0-9_+/-]|[.:]\\d)";
    private static final String RANGE_JOIN="[ \\t]*(?:至|到|[~～—–－-])[ \\t]*";
    private static final String CLOCK="(?:\\d{1,2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?|\\d{1,2}[时点]\\d{1,2}分(?:\\d{1,2}(?:\\.\\d+)?秒)?)(?:[Zz]|[+-]\\d{2}:\\d{2})?";
    /** 显式端点和局部省略共用区间语法；省略末端须有日/月单位，完整时间戳留给时间解析，不能拆成日期及独立时钟。 */
    private static final Pattern CALENDAR_RANGES=Pattern.compile(TEMPORAL_START+"(?:"
            +"(?:(?<rangeDate>"+CALENDAR_DATE+")|(?<rangeDay>"+MONTH_DAY+"))"+RANGE_JOIN+"(?:(?<endDate>"+CALENDAR_DATE+")|(?<endDay>(?:\\d{1,2}月)?\\d{1,2}日))"
            +"|(?<rangeMonth>"+CALENDAR_MONTH+")"+RANGE_JOIN+"(?:(?<endFullMonth>"+CALENDAR_MONTH+")|(?<endMonth>\\d{1,2}月)))"+TEMPORAL_END+"(?![ \\t]+"+CLOCK+")");
    /** 先识别完整时间字面量，不能把ISO的T/Z当普通标识符边界，也不能从时间组件取得金额依据。 */
    private static final Pattern TEMPORALS=Pattern.compile(TEMPORAL_START+"(?:"
            +"(?<date>"+CALENDAR_DATE+")(?:(?:[Tt]|[ \\t]+|(?<=日))(?<time>"+CLOCK+"))?"
            +"|(?<clock>"+CLOCK+")|(?<month>"+CALENDAR_MONTH+")|(?<day>"+MONTH_DAY+")|(?<year>\\d{4}年))"+TEMPORAL_END);
    private static final Pattern OFFSET=Pattern.compile("([Zz]|[+-]\\d{2}:\\d{2})$");
    /**
     * 一个授权且有版本的引用单元，不含可执行指令或永久权限。
     * @param id 本轮唯一来源编号
     * @param originId 原始资料或数据步骤的稳定编号，所有引用切片共享，用于统计独立来源而非猜测编号后缀
     * @param kind DATA确定性业务数据或检索执行事实，KNOWLEDGE业务定义/制度
     * @param title 人可读来源名称
     * @param version 来源版本、内容摘要或快照标识
     * @param scope 明确的公司和报表范围
     * @param observedAt 观测时间或来源生效时间说明
     * @param text 可引用的完整片段，超过预算在建立证据前拆分，不能无标记截断
     * @param contentOffset 可参与词项召回的正文起点，UTF-16从0开始；前缀仅为引用元数据，全文均可引用；等于长度时没有正文
     */
    public record Source(String id,String originId,String kind,String title,String version,String scope,String observedAt,String text,int contentOffset) {
        public Source {AnalysisContract.text(id,128,"证据编号");AnalysisContract.text(originId,128,"原始来源编号");AnalysisContract.text(title,300,"来源名称");AnalysisContract.text(version,128,"来源版本");
            AnalysisContract.text(scope,200,"来源范围");AnalysisContract.text(observedAt,200,"来源时间");AnalysisContract.text(text,16000,"来源原文");
            if(!Set.of("DATA","KNOWLEDGE").contains(kind)||contentOffset<0||contentOffset>text.length()
                    ||contentOffset>0&&contentOffset<text.length()&&Character.isHighSurrogate(text.charAt(contentOffset-1))&&Character.isLowSurrogate(text.charAt(contentOffset)))AnalysisContract.fail("证据类型或正文位置无效");}
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
            result.add(new Source(source.id()+"-q"+number,source.originId(),source.kind(),source.title()+"（引用片段"+number+"）",source.version(),source.scope(),source.observedAt(),text.substring(start,end),Math.min(end-start,Math.max(0,source.contentOffset()-start))));
            start=end;number++;
        }
        return List.copyOf(result);
    }

    /** 先核对精确引用、金额及完整日期时间，再由独立模型验证语义蕴含，不能凭来源编号存在就判定答案正确。 */
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
            var allowed=new HashSet<ReferenceValue>();
            for(var citation:claim.citations()) {
                var source=known.get(citation.sourceId());AnalysisContract.text(citation.quote(),2000,"引用原文");
                if(source==null)AnalysisContract.fail(path+"引用编号不在本轮授权来源中");
                if(!source.text().contains(citation.quote()))AnalysisContract.fail(path+"引用"+source.id()+"的quote不是text中的连续原文；不能拼接、改写或增加转义字符");
                allowed.addAll(sourceValues(source,citation.quote()));
            }
            // 日期与金额分别核对；允许同值、降精度及同段日历省略，不能跨来源重组日期或借时间组件证明金额。
            for(var value:referenceValues(claim.text(),false))if(!allowed.contains(value))AnalysisContract.fail(path+"的"+value.kind().label+value.value()+"没有被该条引用支持；请补充包含该值的原文引用，或删去这项说明");
        }
    }
    /**
     * 给有界修正提供缺失数值或日期时间出现在哪些真实片段的候选，不自动修改引用或断言这些片段支持语义。
     * @param draft 刚被拒绝的模型草稿
     * @param sources 本轮已经授权并实际产生的来源，不能另查或扩展范围
     * @return 最多十二项字面量缺口，kind区分数字及时间类型，number保留规范值，每项最多四个候选编号；仍须模型选择及独立依据复核
     */
    public static List<Map<String,Object>> citationHints(Draft draft,List<Source> sources) {
        var values=new LinkedHashMap<String,Set<ReferenceValue>>();
        for(var source:sources)values.put(source.id(),sourceValues(source,source.text()));
        var result=new ArrayList<Map<String,Object>>();
        for(int index=0;index<draft.claims().size();index++) {
            var claim=draft.claims().get(index);var supported=new HashSet<ReferenceValue>();claim.sourceIds().forEach(id->supported.addAll(values.getOrDefault(id,Set.of())));
            var missing=referenceValues(claim.text(),false);missing.removeAll(supported);
            for(var value:missing){result.add(Map.of("claimIndex",index,"kind",value.kind().name(),"number",value.value(),"candidateSourceIds",values.entrySet().stream().filter(e->e.getValue().contains(value)).map(Map.Entry::getKey).limit(4).toList()));if(result.size()==12)return List.copyOf(result);}
        }
        return List.copyOf(result);
    }
    /** 同一来源的各字段分别取值，不能把前一字段的日期和后一字段的时间拼成原文不存在的时点。 */
    private static Set<ReferenceValue> sourceValues(Source source,String quote) {
        var result=new HashSet<ReferenceValue>();
        for(String text:List.of(source.title(),source.version(),source.scope(),source.observedAt(),quote))result.addAll(referenceValues(text,true));
        return result;
    }

    /**
     * 验证和修正提示共用的抽取契约；先处理有显式单位的局部日历区间与完整日期时间，再核对剩余精确数字。
     * @param text 当前说明或来源的单个字段，不拼接跨来源、跨元数据的时间
     * @param source true时仅提供原文明确支持的日期/时间精度投影；false时返回说明实际声称的完整值
     * @return 独立区分数字、日历与时钟的规范值；非法时间仅在说明侧保留为必然不受支持的值
     */
    private static LinkedHashSet<ReferenceValue> referenceValues(String text,boolean source) {
        var result=new LinkedHashSet<ReferenceValue>();char[] numericText=text.toCharArray();var range=CALENDAR_RANGES.matcher(text);
        while(range.find()) {
            Arrays.fill(numericText,range.start(),range.end(),' ');
            try {for(var parsed:calendarRangeValues(range))if(source)result.addAll(parsed.supported());else result.add(parsed.required());}
            catch(DateTimeException|NumberFormatException invalid){if(!source)result.add(new ReferenceValue(ReferenceKind.INVALID_TEMPORAL,range.group()));}
        }
        // 先完整消费日历区间，短横线不能阻断完整端点，错误末端也不能退化成日数；不同字段、句子或来源之间从不继承上下文。
        var temporal=TEMPORALS.matcher(new String(numericText));
        while(temporal.find()) {
            // 屏蔽整个时间片段，而非只补回T/Z两侧的数字，避免日期被重组或成为金额、计数的依据。
            Arrays.fill(numericText,temporal.start(),temporal.end(),' ');
            try {var parsed=temporalValues(temporal);if(source)result.addAll(parsed.supported());else result.add(parsed.required());}
            catch(DateTimeException|NumberFormatException invalid){if(!source)result.add(new ReferenceValue(ReferenceKind.INVALID_TEMPORAL,temporal.group()));}
        }
        var numbers=NUMBERS.matcher(new String(numericText));while(numbers.find())result.add(new ReferenceValue(ReferenceKind.NUMBER,canonicalNumber(numbers.group())));
        return result;
    }

    /** 完整日历值先经java.time验证；同一时间戳允许只说明日期或已有时钟精度，不允许自行换算时区。 */
    private static TemporalValues temporalValues(Matcher match) {
        var supported=new LinkedHashSet<ReferenceValue>();String dateText=match.group("date");
        if(dateText!=null) {
            var date=parseDate(dateText);var calendar=dateValues(date);supported.addAll(calendar.supported());
            if(match.group("time")==null)return calendar;
            var clock=clockValues(match.group("time"));
            for(String time:clock.supported()) {supported.add(new ReferenceValue(ReferenceKind.TIME,time));supported.add(new ReferenceValue(ReferenceKind.DATETIME,date+"T"+time));}
            return new TemporalValues(new ReferenceValue(ReferenceKind.DATETIME,date+"T"+clock.required()),supported);
        }
        if(match.group("clock")!=null) {
            var clock=clockValues(match.group("clock"));for(String time:clock.supported())supported.add(new ReferenceValue(ReferenceKind.TIME,time));
            return new TemporalValues(new ReferenceValue(ReferenceKind.TIME,clock.required()),supported);
        }
        if(match.group("month")!=null)return monthValues(parseMonth(match.group("month")));
        if(match.group("day")!=null)return dayValues(parseMonthDay(match.group("day")));
        var required=new ReferenceValue(ReferenceKind.YEAR,Year.of(Integer.parseInt(match.group("year").replace("年",""))).toString());
        supported.add(required);return new TemporalValues(required,supported);
    }

    /**
     * 同一日历区间只向省略末端继承前端明确提供的年月；显式完整端点保留各自年份，只生成端点类型值，不生成日数或金额。
     * 同精度末端早于前端则拒绝，不能自动进位；原文未写年份时也不借系统日期、另一端或其他来源补年。
     */
    private static List<TemporalValues> calendarRangeValues(Matcher match) {
        if(match.group("endDate")!=null) {
            var finish=parseDate(match.group("endDate"));
            if(match.group("rangeDate")!=null) {
                var start=parseDate(match.group("rangeDate"));if(finish.isBefore(start))throw new DateTimeException("日期区间末端不能早于前端");
                return List.of(dateValues(start),dateValues(finish));
            }
            // 前端只有月日时不能从完整末端倒推年份；分别校验原文明确写出的两种精度。
            return List.of(dayValues(parseMonthDay(match.group("rangeDay"))),dateValues(finish));
        }
        if(match.group("endDay")!=null) {
            String[] end=match.group("endDay").replace('月','-').replace("日","").split("-");int day=Integer.parseInt(end[end.length-1]);
            if(match.group("rangeDate")!=null) {
                var start=parseDate(match.group("rangeDate"));int month=end.length==2?Integer.parseInt(end[0]):start.getMonthValue();
                var finish=LocalDate.of(start.getYear(),month,day);if(finish.isBefore(start))throw new DateTimeException("省略日期不能自动跨月或跨年");
                return List.of(dateValues(start),dateValues(finish));
            }
            var start=parseMonthDay(match.group("rangeDay"));int month=end.length==2?Integer.parseInt(end[0]):start.getMonthValue();
            var finish=MonthDay.of(month,day);if(finish.compareTo(start)<0)throw new DateTimeException("省略月日不能自动跨月或跨年");
            return List.of(dayValues(start),dayValues(finish));
        }
        var start=parseMonth(match.group("rangeMonth"));
        var finish=match.group("endFullMonth")!=null?parseMonth(match.group("endFullMonth")):YearMonth.of(start.getYear(),Integer.parseInt(match.group("endMonth").replace("月","")));
        if(finish.isBefore(start))throw new DateTimeException("月份区间末端不能早于前端，也不能自动跨年");return List.of(monthValues(start),monthValues(finish));
    }

    private static LocalDate parseDate(String text) {
        String[] parts=text.replace('年','-').replace('月','-').replace("日","").replace('/','-').split("-");
        return LocalDate.of(Integer.parseInt(parts[0]),Integer.parseInt(parts[1]),Integer.parseInt(parts[2]));
    }
    private static YearMonth parseMonth(String text) {
        String[] parts=text.replace('年','-').replace("月","").replace('/','-').split("-");return YearMonth.of(Integer.parseInt(parts[0]),Integer.parseInt(parts[1]));
    }
    private static MonthDay parseMonthDay(String text) {
        String[] parts=text.replace('月','-').replace("日","").split("-");return MonthDay.of(Integer.parseInt(parts[0]),Integer.parseInt(parts[1]));
    }
    /** 完整日期的投影均保留日历类型，绝不为数字、天数或金额提供组件值。 */
    private static TemporalValues dateValues(LocalDate date) {
        var required=new ReferenceValue(ReferenceKind.DATE,date.toString());
        return new TemporalValues(required,Set.of(required,new ReferenceValue(ReferenceKind.MONTH,YearMonth.from(date).toString()),
                new ReferenceValue(ReferenceKind.MONTH_DAY,MonthDay.from(date).toString()),new ReferenceValue(ReferenceKind.YEAR,Year.of(date.getYear()).toString())));
    }
    private static TemporalValues monthValues(YearMonth month) {
        var required=new ReferenceValue(ReferenceKind.MONTH,month.toString());return new TemporalValues(required,Set.of(required,new ReferenceValue(ReferenceKind.YEAR,Year.of(month.getYear()).toString())));
    }
    private static TemporalValues dayValues(MonthDay day) {
        var required=new ReferenceValue(ReferenceKind.MONTH_DAY,day.toString());return new TemporalValues(required,Set.of(required));
    }

    /** 时钟保留明确的时区与精度；省略后缀可复述本地钟面，不能增加非零精度、舍入或换算为别的时区。 */
    private static ClockValues clockValues(String value) {
        var offsetMatch=OFFSET.matcher(value);String offset="";
        if(offsetMatch.find()){offset=ZoneOffset.of(offsetMatch.group().toUpperCase(Locale.ROOT)).getId();value=value.substring(0,offsetMatch.start());}
        value=value.replace('时',':').replace('点',':').replace('分',':').replace("秒","");if(value.endsWith(":"))value=value.substring(0,value.length()-1);
        String[] parts=value.split(":");String[] second=parts.length==3?parts[2].split("\\."):new String[]{"0"};String fraction=second.length==2?second[1]:"";
        if(fraction.length()>9)throw new DateTimeException("时间精度超过纳秒");
        int nano=fraction.isEmpty()?0:Integer.parseInt((fraction+"000000000").substring(0,9));
        var time=LocalTime.of(Integer.parseInt(parts[0]),Integer.parseInt(parts[1]),Integer.parseInt(second[0]),nano);
        var plain=new LinkedHashSet<String>();String minute=String.format(Locale.ROOT,"%02d:%02d",time.getHour(),time.getMinute());plain.add(minute);String full=minute;
        if(parts.length==3) {
            String seconds=minute+String.format(Locale.ROOT,":%02d",time.getSecond());plain.add(seconds);full=seconds;
            for(int length=1;length<=fraction.length();length++) {String digits=fraction.substring(0,length).replaceFirst("0+$","");full=seconds+(digits.isEmpty()?"":"."+digits);plain.add(full);}
        }
        var supported=new LinkedHashSet<>(plain);if(!offset.isEmpty())for(String text:plain)supported.add(text+offset);
        return new ClockValues(full+offset,supported);
    }

    /** 字面量类型隔离金额与时间；INVALID_TEMPORAL不会由任何来源提供支持。 */
    private enum ReferenceKind {
        NUMBER("数字"),DATE("日期"),MONTH("年月"),MONTH_DAY("月日"),YEAR("年份"),TIME("时间"),DATETIME("日期时间"),INVALID_TEMPORAL("无效日期时间");
        final String label;ReferenceKind(String label){this.label=label;}
    }
    /**
     * 一项按类型隔离的引用事实，不能跨金额和时间种类匹配。
     * @param kind 数字或时间的独立类型
     * @param value 精确十进制或经校验的时间规范值
     */
    private record ReferenceValue(ReferenceKind kind,String value) { }
    /**
     * 一段时间原文的精确声明与允许复述的精度范围。
     * @param required 说明中实际声称的完整时间值
     * @param supported 该时间原文支持的同值格式和精度投影
     */
    private record TemporalValues(ReferenceValue required,Set<ReferenceValue> supported) { }
    /**
     * 一个经有效性检查的时钟值，不附加来源没有提供的日期或时区。
     * @param required 带原文时区和精度的时钟值
     * @param supported 原时区及未标时区的精度投影，不做时区换算
     */
    private record ClockValues(String required,Set<String> supported) { }
    private static String canonicalNumber(String value){return new java.math.BigDecimal(value).stripTrailingZeros().toPlainString();}
    /** 展示与出站前按共同脱敏规则转换；引用校验使用的也是这份原文，避免脱敏后仍引用未公开的内容。 */
    public static String safeText(String text){return SensitiveData.text(com.example.report.operations.ModelEgressPolicy.redact(text));}
    public static String schema() {
        return schema(List.of());
    }
    /** 原生回答Schema使用本轮实际编号枚举；静态配置摘要仍使用无运行数据的结构版本。 */
    public static String schema(List<Source> sources) {
        var sourceId=sources.isEmpty()?AnalysisContract.string(128):AnalysisContract.enumeration(sources.stream().map(Source::id).distinct().toArray(String[]::new));
        var claim=AnalysisContract.object(AnalysisContract.fields("text",AnalysisContract.string(1500),"sourceIds",AnalysisContract.described(AnalysisContract.array(sourceId,4),"选择一至四个实际来源id；说明中的数字及完整日期时间须由所选来源支持，不能拼接或换算出新值。服务器回填原文，不生成quote")));
        return JsonUtil.toJson(AnalysisContract.object(AnalysisContract.fields("status",AnalysisContract.enumeration("ANSWERED","INSUFFICIENT","CONFLICTING"),
                "claims",AnalysisContract.array(claim,8),"limitations",AnalysisContract.array(AnalysisContract.string(1000),8))));
    }
}
