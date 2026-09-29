package com.example.report.semantic;

import com.example.report.catalog.*;
import com.example.report.common.ApiException;
import com.example.report.dispatch.PreviewService;
import com.example.report.permission.CurrentUser;
import org.springframework.stereotype.Component;
import java.util.*;
import static com.example.report.semantic.SemanticIntent.Operation.*;
import static com.example.report.semantic.SemanticIntent.Target.*;

/** Entity linking and delta reduction. This class never interprets whole sentences. */
@Component
public class SemanticPlanner {
    private final ReportCatalogService catalog;
    public SemanticPlanner(ReportCatalogService catalog) { this.catalog=catalog; }
    public void requireAction(DialogueState state,SemanticIntent intent) {
        if (intent.forbids(intent.action())) throw new ApiException(422,"本次动作与禁止条件冲突，请明确本轮操作");
        if (intent.action()!=SemanticIntent.Action.CLARIFY) return;
        if (Set.of(SemanticIntent.Clarify.COMPANY,SemanticIntent.Clarify.ACTION).contains(intent.clarify())) state.setUnresolvedCompany(true);
        if (Set.of(SemanticIntent.Clarify.REPORTS,SemanticIntent.Clarify.ACTION).contains(intent.clarify())) state.setUnresolvedReports(true);
        if (intent.clarify()==SemanticIntent.Clarify.RECORDS) state.setUnresolvedRecords(true);
        throw new ApiException(422,switch(intent.clarify()) {
            case COMPANY -> "请明确要查询的一家公司，或说明查询全部可见公司";
            case REPORTS -> "请说明要查询的完整报表名称";
            case RECORDS -> "请说明单据号，或在预览表格中选择记录";
            default -> "请明确本次操作及公司、报表范围，例如：查询 A 公司销售报表";
        });
    }
    public List<String> mentions(CurrentUser user,String message) {
        return catalog.terms().scan(TextNormalizer.normalize(message),catalog.dispatchableIds(user)).stream()
                .filter(m -> !m.all()).map(TermIndex.Mention::text).distinct().toList();
    }
    /** A coverage gate rejects omitted catalog entities; it never invents or executes a replacement intent. */
    public void requireCoverage(DialogueState state,SemanticIntent intent,List<String> mentions) {
        if (!Set.of(SemanticIntent.Action.PREVIEW,SemanticIntent.Action.PREPARE_DISPATCH,SemanticIntent.Action.EXPLAIN_RULES).contains(intent.action())) return;
        if (!hasReportCoverage(intent,mentions)) {
            state.setUnresolvedReports(true);
            throw new ApiException(422,"本次提到的报表未被完整识别，请明确要查询的报表名称和追加、移除或替换操作");
        }
    }
    static boolean hasReportCoverage(SemanticIntent intent,List<String> mentions) {
        if (!Set.of(SemanticIntent.Action.PREVIEW,SemanticIntent.Action.PREPARE_DISPATCH,SemanticIntent.Action.EXPLAIN_RULES).contains(intent.action())) return true;
        var captured=intent.changesFor(REPORTS).stream().flatMap(c -> c.mentions().stream()).map(TextNormalizer::normalize).toList();
        return mentions.stream().map(TextNormalizer::normalize).allMatch(m -> captured.stream().anyMatch(c -> c.contains(m) || m.contains(c)));
    }
    public void merge(CurrentUser user, DialogueState state, SemanticIntent intent) {
        requireAction(state,intent);
        // Commit scope only once all operations resolve; a later invalid operation cannot apply a partial program.
        var draft=new DialogueState();
        draft.setDesired(state.getDesired());
        draft.setUnresolvedCompany(state.isUnresolvedCompany());
        draft.setUnresolvedReports(state.isUnresolvedReports());
        try {
            for (var scoped:intent.scopeChanges()) {
                if(scoped.target()==RECORDS) continue;
                mergeChange(user,draft,scoped);
            }
        } catch (RuntimeException error) {
            // Preserve the requested scope, but prevent ellipsis from using an older effective preview.
            if(intent.changes(COMPANY)) state.setUnresolvedCompany(true);
            if(intent.changes(REPORTS)) state.setUnresolvedReports(true);
            throw error;
        }
        state.setDesired(draft.getDesired());
        state.setUnresolvedCompany(draft.isUnresolvedCompany());
        state.setUnresolvedReports(draft.isUnresolvedReports());
    }
    private void mergeChange(CurrentUser user,DialogueState state,SemanticIntent.ScopeChange scoped) {
        var previous = state.getDesired();
        String company = previous.companyCode();
        var companyChange=scoped.target()==COMPANY?scoped.change():SemanticIntent.Change.keep();
        if (companyChange.operation() == CLEAR) { company=null; state.setUnresolvedCompany(false); }
        if (companyChange.operation() == REPLACE) {
            // Company codes currently are the authoritative company identifiers in PermissionService.
            company = companyCode(companyChange.mentions().get(0));
            if (company.isBlank()) {
                state.setUnresolvedCompany(true);
                throw new ApiException(422,"请提供具体公司代码或名称，不能只写“公司”");
            }
            state.setUnresolvedCompany(false);
        }
        state.setDesired(new DialogueState.Scope(company, previous.allReports(), previous.reportIds()));
        var change = scoped.target()==REPORTS?scoped.change():SemanticIntent.Change.keep();
        if (change.operation() == CLEAR) {
            state.setDesired(new DialogueState.Scope(company, true, List.of()));
            state.setUnresolvedReports(false);
        } else if (change.operation() != KEEP) {
            if (state.isUnresolvedReports() && (change.operation()==ADD || change.operation()==REMOVE))
                throw new ApiException(422,"上次报表范围尚未确定，请直接说明要保留的全部报表名称");
            state.setUnresolvedReports(true);
            LinkedHashSet<String> mentioned = new LinkedHashSet<>();
            for (String mention : change.mentions()) {
                var result = catalog.resolve(user, mention);
                if (!result.resolved() || result.matchType()==MatchType.FUZZY || result.matchType()==MatchType.ALL || !result.unrecognized().isEmpty()) {
                    var choices = java.util.stream.Stream.concat(result.reports().stream(),result.candidates().stream()).map(ReportRef::reportName).distinct().toList();
                    throw new ApiException(422, choices.isEmpty() ? "未找到“"+mention+"”对应的可用报表，请说明完整报表名称"
                            : "“"+mention+"”需要确认，请明确要查询的报表："+String.join("、",choices));
                }
                mentioned.addAll(result.reportIds());
            }
            LinkedHashSet<String> ids = new LinkedHashSet<>(previous.allReports()
                    ? catalog.dispatchableReports(user).stream().map(CatalogEntry::reportId).toList() : previous.reportIds());
            if (change.operation()==REPLACE) ids.clear();
            if (change.operation()==REMOVE) ids.removeAll(mentioned); else ids.addAll(mentioned);
            state.setDesired(new DialogueState.Scope(company, false, List.copyOf(ids)));
            state.setUnresolvedReports(false);
        }
    }
    public void validate(CurrentUser user, DialogueState state) {
        if (state.isUnresolvedCompany()) throw new ApiException(422,"公司范围尚未确定，请明确一家公司或说明查询全部可见公司");
        // Validate desired, never fall back to the last successful/effective scope.
        PreviewService.resolveCompanies(user, state.getDesired().companyCode());
        if (state.isUnresolvedReports()) throw new ApiException(422,"上次报表范围尚未确定，请重新说明报表名称");
        if (!state.getDesired().allReports()) {
            if (state.getDesired().reportIds().isEmpty()) throw new ApiException(422,"排除后没有可查询报表，请说明要保留的报表");
            state.getDesired().reportIds().forEach(id -> catalog.requireDispatchable(user,id));
        }
    }
    static String companyCode(String mention) {
        String code = java.text.Normalizer.normalize(mention, java.text.Normalizer.Form.NFKC).trim();
        if (code.endsWith("公司")) code=code.substring(0,code.length()-2).trim();
        return code.toUpperCase(Locale.ROOT);
    }
}
