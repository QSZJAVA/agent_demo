package com.example.report.semantic;

import com.example.report.catalog.TextNormalizer;
import java.util.*;
import java.util.regex.Pattern;
import static com.example.report.semantic.SemanticIntent.*;

/**
 * Closed, compositional command grammar. Every input character must be consumed.
 * Entity terminals come from the catalog; company/document patterns describe identifiers,
 * never whole utterances. Unknown syntax is delegated, not discarded or executed partially.
 */
public final class DomainIntentParser {
    private enum Kind { REPORT, COMPANY, DOCUMENT, QUERY, SEND, CANCEL, RESULT, RULE,
        ADD, REMOVE, ONLY, RESTORE, ALL, COMPANY_SET, REPORT_SET, RECORD_SET, PLAN, FILTER, NEGATE,
        CAN, PARTICLE, SEQUENCE, EXPLAIN }
    private record Token(Kind kind,String text,int start,int end) { }
    private static final Map<String,Kind> WORDS=new LinkedHashMap<>();
    private static final Pattern COMPANY=Pattern.compile("[a-z][a-z0-9_-]*公司|公司[a-z][a-z0-9_-]*");
    private static final Pattern DOCUMENT=Pattern.compile("[a-z]{1,12}[0-9]{2,}[a-z0-9_-]*");
    static {
        words(Kind.QUERY,"查询","查一下","查下","查一查","看看","看一下","查看","看下","预览","重查","刷新","查","看");
        words(Kind.SEND,"派单","派掉","派","生成");
        words(Kind.CANCEL,"取消","撤销","作废");
        words(Kind.RESULT,"执行结果","派单结果","处理结果","结果","执行情况");
        words(Kind.RULE,"派单规则","规则");
        words(Kind.EXPLAIN,"解释","说明","为什么");
        words(Kind.ADD,"加上","加进来","加入","追加","增加","加","另外","还有","也要");
        words(Kind.REMOVE,"不要","去掉","移除","排除","删掉","删除","不想","别","不派");
        words(Kind.ONLY,"只","仅","仅仅","只要","改为","改成","换成","切换到","切换成","切到","切回");
        words(Kind.RESTORE,"恢复","重新勾选","重新选中");
        words(Kind.ALL,"全部","所有");
        words(Kind.COMPANY_SET,"公司"); words(Kind.REPORT_SET,"报表");
        words(Kind.RECORD_SET,"记录","单据","数据"); words(Kind.PLAN,"待确认派单清单","待确认清单","派单清单","清单");
        words(Kind.FILTER,"限制","筛选","过滤条件","范围");
        words(Kind.NEGATE,"不","不要只","不是","没有","不能","不用","无需","不必","别只");
        words(Kind.CAN,"可以","能","能够","可","有哪些","有什么","有没有","有吗");
        words(Kind.SEQUENCE,"然后","之后","而是","或者","或","还是","但是","并且","并");
        words(Kind.PARTICLE,"帮我","帮忙","麻烦","请","先","现在","这次","当前","暂时","我","我们","给","把","想","要","需要","指的是","说的是","就是",
                "那","这些","这份","这条","这","它","它们","又","也","再","了","掉","的","呢","吧","吗","一下","就行",
                "剩下","剩余","其余","能看的","名下","有","在","和","与","及","以及","、",",","，","。","?","？","!","！",";","；",":","：");
    }
    private static void words(Kind kind,String... words) { for(String word:words) WORDS.put(TextNormalizer.normalize(word),kind); }

    public Optional<SemanticIntent> parse(String message,IntentParser.Context context) {
        if(message==null || message.length()>1000) return Optional.empty();
        var tokens=tokenize(message,context);
        if(tokens==null || tokens.isEmpty()) return Optional.empty();
        // Coordination, double negation and mixed set operations need the semantic model.
        if(has(tokens,Kind.SEQUENCE,Kind.NEGATE)) return Optional.empty();
        List<String> companies=entities(tokens,Kind.COMPANY), reports=entities(tokens,Kind.REPORT), documents=entities(tokens,Kind.DOCUMENT);
        if(companies.size()>1) return Optional.of(SemanticIntent.clarify(Clarify.COMPANY));
        boolean allCompanies=has(tokens,Kind.ALL)&&has(tokens,Kind.COMPANY_SET);
        boolean allReports=has(tokens,Kind.ALL)&&has(tokens,Kind.REPORT_SET);
        boolean allRecords=has(tokens,Kind.ALL)&&has(tokens,Kind.RECORD_SET);
        boolean remove=has(tokens,Kind.REMOVE), add=has(tokens,Kind.ADD), restore=has(tokens,Kind.RESTORE);
        boolean cancel=has(tokens,Kind.CANCEL), send=has(tokens,Kind.SEND);
        boolean query=has(tokens,Kind.QUERY,Kind.CAN);
        boolean filterClear=(remove || cancel)&&has(tokens,Kind.FILTER);
        if(remove&&has(tokens,Kind.ALL)&&!filterClear) return Optional.empty();
        if(has(tokens,Kind.FILTER) && !filterClear) return Optional.empty();
        if(filterClear && (!companies.isEmpty() || !reports.isEmpty() || !documents.isEmpty())) return Optional.empty();
        if(remove && !filterClear && !reports.isEmpty() && tokens.stream().anyMatch(t->t.kind()==Kind.QUERY &&
                tokens.stream().anyMatch(r->r.kind()==Kind.REMOVE && r.start()<t.start()))) return Optional.empty();
        if((allCompanies&&!companies.isEmpty()) || (allReports&&!reports.isEmpty())
                || (add&&remove) || (add&&restore) || (remove&&restore)) return Optional.empty();
        // A single operator cannot be assigned to two different entity sets unambiguously.
        if((add||remove||restore)&&!reports.isEmpty()&&!documents.isEmpty()) return Optional.empty();
        if(operatorSplitsEntityGroup(tokens,Kind.REPORT) || operatorSplitsEntityGroup(tokens,Kind.DOCUMENT)) return Optional.empty();
        if(remove && has(tokens,Kind.ONLY) && tokens.stream().anyMatch(t->t.kind()==Kind.ONLY &&
                tokens.stream().anyMatch(r->r.kind()==Kind.REMOVE && r.start()<t.start()))) return Optional.empty();
        if((add||remove||restore)&&!companies.isEmpty()&&reports.isEmpty()&&documents.isEmpty()&&!filterClear)
            return Optional.empty();

        Change company=allCompanies ? clear(message) : companies.isEmpty()?Change.keep():change(Operation.REPLACE,companies,message);
        if(filterClear && has(tokens,Kind.COMPANY_SET) && companies.isEmpty()) company=clear(message);
        Change report=allReports ? clear(message) : reports.isEmpty()?Change.keep():change(
                remove&&!filterClear?Operation.REMOVE:add?Operation.ADD:Operation.REPLACE,reports,message);
        if(filterClear && has(tokens,Kind.REPORT_SET) && reports.isEmpty()) report=clear(message);
        Change exclusions=Change.keep();
        if(!documents.isEmpty()) {
            if(!remove&&!restore) return Optional.empty();
            exclusions=change(restore?Operation.REMOVE:Operation.ADD,documents,message);
        } else if(allRecords && restore) exclusions=clear(message);
        else if(restore) return Optional.empty();

        boolean changed=company.operation()!=Operation.KEEP || report.operation()!=Operation.KEEP || exclusions.operation()!=Operation.KEEP;
        if(cancel&&query&&!filterClear) return Optional.empty();
        Action action;
        if(has(tokens,Kind.RESULT)) {
            if(changed || cancel || restore || remove || add) return Optional.empty();
            action=Action.SHOW_RESULT;
        } else if(has(tokens,Kind.RULE)) {
            if(send||cancel||remove||add||restore) return Optional.empty();
            action=Action.EXPLAIN_RULES;
        } else if(cancel && has(tokens,Kind.PLAN) && !changed && !send) {
            action=Action.CANCEL_PLAN;
        } else {
            if(cancel&&!filterClear || has(tokens,Kind.EXPLAIN)) return Optional.empty();
            // Negative dispatch with an explicit preview request never cancels or executes a plan.
            boolean negativeSend=send && remove && reports.isEmpty() && documents.isEmpty() && !filterClear;
            if(negativeSend&&!query) return Optional.empty();
            if(send&&!query&&!negativeSend && (!remove || !documents.isEmpty() || has(tokens,Kind.ONLY))) action=Action.PREPARE_DISPATCH;
            else if(query || changed || negativeSend) action=Action.PREVIEW;
            else return Optional.empty();
        }
        return Optional.of(new SemanticIntent(1,action,company,report,exclusions,Clarify.NONE));
    }
    private static List<Token> tokenize(String message,IntentParser.Context context) {
        String input=TextNormalizer.normalize(message);
        Set<String> reports=new HashSet<>(context.mentionedReportTerms());
        context.reports().forEach(r->reports.add(TextNormalizer.normalize(r.reportName())));
        List<Token> tokens=new ArrayList<>();
        for(int offset=0;offset<input.length();) {
            Token best=null;
            for(String report:reports) if(!report.isBlank()&&input.startsWith(report,offset)&&boundary(input,offset,report))
                best=longer(best,new Token(Kind.REPORT,report,offset,offset+report.length()));
            for(var entry:WORDS.entrySet()) if(input.startsWith(entry.getKey(),offset))
                best=longer(best,new Token(entry.getValue(),entry.getKey(),offset,offset+entry.getKey().length()));
            var company=COMPANY.matcher(input).region(offset,input.length());
            if(company.lookingAt()) {
                String surface=company.group();
                String value=surface.startsWith("公司")?surface.substring(2):surface.substring(0,surface.length()-2);
                best=longer(best,new Token(Kind.COMPANY,value,offset,company.end()));
            }
            var document=DOCUMENT.matcher(input).region(offset,input.length());
            if(document.lookingAt()) best=longer(best,new Token(Kind.DOCUMENT,document.group(),offset,document.end()));
            if(best==null) return null;
            tokens.add(best);offset=best.end();
        }
        return tokens;
    }
    private static boolean boundary(String input,int offset,String term) {
        if(!term.matches("[a-z0-9_-]+")) return true;
        int end=offset+term.length();
        return (offset==0 || !ascii(input.charAt(offset-1))) && (end==input.length()||!ascii(input.charAt(end)));
    }
    private static boolean ascii(char c) { return c>='a'&&c<='z'||c>='0'&&c<='9'||c=='_'||c=='-'; }
    private static boolean operatorSplitsEntityGroup(List<Token> tokens,Kind kind) {
        var group=tokens.stream().filter(t->t.kind()==kind).toList();
        if(group.size()<2) return false;
        int first=group.get(0).start(),last=group.get(group.size()-1).end();
        return tokens.stream().anyMatch(t->Set.of(Kind.ADD,Kind.REMOVE,Kind.ONLY,Kind.RESTORE).contains(t.kind()) && t.start()>first && t.end()<last);
    }
    private static Token longer(Token left,Token right) { return left==null||right.end()>left.end()?right:left; }
    private static boolean has(List<Token> tokens,Kind... kinds) { var set=Set.of(kinds);return tokens.stream().anyMatch(t->set.contains(t.kind())); }
    private static List<String> entities(List<Token> tokens,Kind kind) { return tokens.stream().filter(t->t.kind()==kind).map(Token::text).distinct().toList(); }
    private static Change change(Operation operation,List<String> mentions,String message) { return new Change(operation,mentions,message); }
    private static Change clear(String message) { return change(Operation.CLEAR,List.of(),message); }
}
