package com.example.report.semantic;

import com.example.report.catalog.*;
import com.example.report.common.ApiException;
import com.example.report.dispatch.PreviewService;
import com.example.report.permission.CurrentUser;
import org.springframework.stereotype.Component;
import java.util.*;
import static com.example.report.semantic.SemanticIntent.Operation.*;
import static com.example.report.semantic.SemanticIntent.Target.*;

/**
 * 将已校验的模型意图关联到可见目录并合并范围修改，不负责整句语义解析。
 * 所有修改先在草稿状态求值；只有每一步都能唯一解析才更新期望范围，失败不能部分生效。
 */
@Component
public class SemanticPlanner {
    private final ReportCatalogService catalog;
    public SemanticPlanner(ReportCatalogService catalog) { this.catalog=catalog; }
    /** 拒绝本轮动作与禁止条件冲突；需要澄清时保留未解决标记供统一规划复核，不自行推断后续动作。 */
    public void requireAction(DialogueState state,SemanticIntent intent) {
        if (intent.forbids(intent.action())) throw new ApiException(422,"本次动作与禁止条件冲突，请明确本轮操作");
        if (intent.action()!=SemanticIntent.Action.CLARIFY) {
            // 整轮失败不等于丢失已绑定对象。统一任务已复核本轮动作与来源；具体范围/记录歧义仍在正式求值时拒绝。
            return;
        }
        state.setUnresolvedRequest(true);
        // CLARIFY中的候选修改仅供证据保存，尚未归并；不能仅因候选包含范围修改就污染既有范围。
        // 仅明确的实体歧义设置范围标记；整轮未完成由统一规划结合来源引用复核能否继续。
        if (intent.clarify()==SemanticIntent.Clarify.COMPANY) state.setUnresolvedCompany(true);
        if (intent.clarify()==SemanticIntent.Clarify.REPORTS) state.setUnresolvedReports(true);
        if (intent.clarify()==SemanticIntent.Clarify.RECORDS) state.setUnresolvedRecords(true);
        throw new ApiException(422,switch(intent.clarify()) {
            case COMPANY -> "请明确要查询的一家公司，或说明查询全部可见公司";
            case REPORTS -> "请说明要查询的完整报表名称";
            case RECORDS -> "请说明单据号，或在预览表格中选择记录";
            default -> intent.unsupportedConditions().isEmpty()?"本轮操作尚未确定，未应用任何修改。请明确要处理的对象及本轮操作。"
                    :"本轮条件暂无法执行："+String.join("、",intent.unsupportedConditions())+"。未应用本轮修改；可重新说明已配置字段条件或指定单据。";
        });
    }
    /** 仅扫描用户可派单目录中的实体候选；词条命中本身不决定追加、移除或派单动作。*/
    public List<String> mentions(CurrentUser user,String message) {
        return catalog.terms().scan(TextNormalizer.normalize(message),catalog.dispatchableIds(user)).stream()
                .filter(m -> !m.all()).map(TermIndex.Mention::text).distinct().toList();
    }
    /** 要求范围修改或记录报表限定覆盖本轮目录实体；遗漏时澄清，不强制把定位限定变成范围修改。 */
    public void requireCoverage(DialogueState state,SemanticIntent intent,List<String> mentions) {
        if (!Set.of(SemanticIntent.Action.PREVIEW,SemanticIntent.Action.PREPARE_DISPATCH,SemanticIntent.Action.EXPLAIN_RULES).contains(intent.action())) return;
        if (!hasReportCoverage(intent,mentions)) {
            state.setUnresolvedReports(true);
            throw new ApiException(422,"本次提到的报表未被完整识别，请明确要查询的报表名称和追加、移除或替换操作");
        }
    }
    static boolean hasReportCoverage(SemanticIntent intent,List<String> mentions) {
        if (!Set.of(SemanticIntent.Action.PREVIEW,SemanticIntent.Action.PREPARE_DISPATCH,SemanticIntent.Action.EXPLAIN_RULES).contains(intent.action())) return true;
        // 操作已表达的实体不要求再输出一遍角色；归并省略的实体可用最终约束解释，记录限定也能覆盖。
        var captured=java.util.stream.Stream.concat(intent.reportConstraints().stream().map(SemanticIntent.ReportConstraint::mention),
                intent.scopeChanges().stream().flatMap(c -> (c.target()==REPORTS?c.mentions():c.reportMentions()).stream()))
                .map(TextNormalizer::normalize).toList();
        return mentions.stream().map(TextNormalizer::normalize).allMatch(m -> captured.stream().anyMatch(c -> c.contains(m) || m.contains(c)));
    }
    /** 在独立草稿中检查计划自相矛盾，供有界模型修正；不写会话，不把权限拒绝或未知实体转为模型改写指令。 */
    public void validateModelDraft(CurrentUser user,DialogueState state,SemanticIntent intent) {
        if(!Set.of(SemanticIntent.Action.PREVIEW,SemanticIntent.Action.PREPARE_DISPATCH,SemanticIntent.Action.EXPLAIN_RULES).contains(intent.action())) return;
        var draft=new DialogueState();draft.setDesired(state.getDesired());
        try {merge(user,draft,intent);}
        catch(IntentCodec.InvalidOutput inconsistency) {throw inconsistency;}
        catch(ApiException businessRefusal) { /* 业务拒绝由正式流程处理，模型不能替用户换成可访问的实体。 */ }
    }
    /** 按协议顺序合并公司和报表范围；记录排除在最终事实快照上另行处理，失败保留原范围并标记待澄清。*/
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
            validateReportRoles(user,state,draft,intent);
        } catch (RuntimeException error) {
            // Preserve the requested scope, but prevent ellipsis from using an older effective preview.
            if(intent.changes(COMPANY)) state.setUnresolvedCompany(true);
            if(intent.changes(REPORTS) || intent.reportConstraints().stream().anyMatch(c->c.role()==SemanticIntent.ReportRole.INCLUDED || c.role()==SemanticIntent.ReportRole.EXCLUDED)) state.setUnresolvedReports(true);
            state.setUnresolvedRequest(true);
            throw error;
        }
        state.setDesired(draft.getDesired());
        state.setUnresolvedCompany(draft.isUnresolvedCompany());
        state.setUnresolvedReports(draft.isUnresolvedReports());
    }
    /** 在提交草稿前校验最终报表集合满足实体角色；等价操作无需重复，错误角色不能放宽范围。 */
    private void validateReportRoles(CurrentUser user,DialogueState previous,DialogueState draft,SemanticIntent intent) {
        if(!Set.of(SemanticIntent.Action.PREVIEW,SemanticIntent.Action.PREPARE_DISPATCH,SemanticIntent.Action.EXPLAIN_RULES).contains(intent.action())) return;
        // 记录所属报表必须在读取新预览之前确定，不能让泛称“全部报表”通过草稿后才在选择阶段失败。
        // 泛称属于协议角色错误，可交给模型修正；未知或无权实体仍为业务拒绝，不诱导模型换成其他报表。
        for(var change:intent.scopeChanges())if(change.target()==RECORDS)for(String mention:change.reportMentions()) {
            var concrete=catalog.resolve(user,mention);
            if(concrete.matchType()==MatchType.ALL)
                throw new IntentCodec.InvalidOutput("","RECORD_SCOPE_MUST_NAME_REPORT：reportMentions仅填写具体所属报表；所有/全部报表是范围泛称，不能作为单张报表名称。作用于当前完整范围时reportMentions为空；重查全部报表仍由REPORTS ALL_AUTHORIZED表达，恢复全部选择保留RESTORE_ALL，不得删除重置要求。");
            if(!concrete.resolved() || concrete.matchType()==MatchType.FUZZY || !concrete.unrecognized().isEmpty())
                throw new ApiException(422,"记录所属报表尚未确定，请说明具体报表名称");
            concrete.reportIds().forEach(id->catalog.requireDispatchable(user,id));
        }
        var ids=draft.getDesired().allReports()?catalog.dispatchableIds(user):new HashSet<>(draft.getDesired().reportIds());
        for(var constraint:intent.reportConstraints()) {
            if(constraint.role()==SemanticIntent.ReportRole.UNCHANGED_OTHERS) {
                // “其余”是明确操作范围的补集，不是目录名称；只有范围固定且每个记录操作有具名边界才能证明其余不变。
                if(intent.changes(COMPANY) || intent.changes(REPORTS) || !intent.changes(RECORDS))
                    throw new IntentCodec.InvalidOutput("","UNCHANGED_OTHERS_REQUIRES_FIXED_SCOPE_AND_NAMED_RECORD_OPERATIONS：保留其他报表时不得修改查询范围；整类取消选择使用RECORDS、selectorKind=ALL并在reportMentions指定该报表，不用REPORTS REMOVE代替勾选操作。");
                for(var change:intent.scopeChanges())if(change.target()==RECORDS) {
                    if(change.reportMentions().isEmpty())throw new IntentCodec.InvalidOutput("","UNCHANGED_OTHERS_FORBIDS_UNSCOPED_RECORD_OPERATIONS");
                    for(String mention:change.reportMentions()) {
                        var target=catalog.resolve(user,mention);
                        if(!target.resolved())throw new ApiException(422,"记录所属报表尚未确定");
                        target.reportIds().forEach(id->catalog.requireDispatchable(user,id));
                    }
                }
                continue;
            }
            var resolved=catalog.resolve(user,constraint.mention());
            if(!resolved.resolved() || resolved.matchType()==MatchType.FUZZY || resolved.matchType()==MatchType.ALL || !resolved.unrecognized().isEmpty())
                throw new ApiException(422,"报表含义尚未确定，请说明完整报表名称");
            resolved.reportIds().forEach(id -> catalog.requireDispatchable(user,id));
            if(constraint.role()==SemanticIntent.ReportRole.UNCHANGED) {
                var before=previous.getDesired().allReports()?catalog.dispatchableIds(user):new HashSet<>(previous.getDesired().reportIds());
                if(!Objects.equals(previous.getDesired().companyCode(),draft.getDesired().companyCode())
                        || resolved.reportIds().stream().anyMatch(id->before.contains(id)!=ids.contains(id)))
                    throw new IntentCodec.InvalidOutput("","UNCHANGED_REPORT_SCOPE_MUST_STAY_UNCHANGED");
                // 未限定报表的记录操作会覆盖最终范围，不能绕过显式保持不变的报表。
                for(var change:intent.scopeChanges()) if(change.target()==RECORDS) {
                    Set<String> affected=new HashSet<>();
                    if(change.reportMentions().isEmpty())affected.addAll(ids);
                    else for(String mention:change.reportMentions()) {
                        var target=catalog.resolve(user,mention);
                        if(!target.resolved())throw new ApiException(422,"记录所属报表尚未确定");
                        affected.addAll(target.reportIds());
                    }
                    if(resolved.reportIds().stream().anyMatch(affected::contains))
                        throw new IntentCodec.InvalidOutput("","UNCHANGED_REPORT_MUST_NOT_HAVE_RECORD_OPERATIONS");
                }
                continue;
            }
            boolean included=ids.containsAll(resolved.reportIds());
            if(constraint.role()==SemanticIntent.ReportRole.EXCLUDED ? resolved.reportIds().stream().anyMatch(ids::contains) : !included)
                throw new IntentCodec.InvalidOutput("","REPORT_ROLE_CONFLICTS_WITH_RESULT_SCOPE");
        }
        if(intent.changes(REPORTS)) for(var change:intent.scopeChanges()) if(change.target()==RECORDS)
            for(String mention:change.reportMentions()) {
                var resolved=catalog.resolve(user,mention);
                if(resolved.resolved() && !ids.containsAll(resolved.reportIds()))
                    throw new IntentCodec.InvalidOutput("","RECORD_QUALIFIER_OUTSIDE_RESULT_SCOPE_CHECK_UNNECESSARY_REPORT_REPLACEMENT");
            }
    }
    private void mergeChange(CurrentUser user,DialogueState state,SemanticIntent.ScopeChange scoped) {
        var previous = state.getDesired();
        String company = previous.companyCode();
        var companyChange=scoped.target()==COMPANY?scoped.change():SemanticIntent.Change.keep();
        if (companyChange.operation() == ALL_AUTHORIZED) { company=null; state.setUnresolvedCompany(false); }
        if (companyChange.operation() == REPLACE) {
            // 公司原词只进行统一表示转换，随后按当前用户的实际授权代码校验，不能退回其他可访问公司。
            company = companyCode(companyChange.mentions().get(0));
            if (company.isBlank()) {
                state.setUnresolvedCompany(true);
                throw new ApiException(422,"请提供具体公司代码或名称，不能只写“公司”");
            }
            state.setUnresolvedCompany(false);
        }
        state.setDesired(new DialogueState.Scope(company, previous.allReports(), previous.reportIds()));
        var change = scoped.target()==REPORTS?scoped.change():SemanticIntent.Change.keep();
        if (change.operation() == ALL_AUTHORIZED) {
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
                    String reason=choices.isEmpty() ? "未找到“"+mention+"”对应的可用报表，请说明完整报表名称"
                            : "“"+mention+"”需要确认，请明确要查询的报表："+String.join("、",choices);
                    throw new com.example.report.common.ModelContractViolation(reason,"REPORTS的具名实体未唯一匹配目录。若原文表达全部授权报表，应使用operation=ALL_AUTHORIZED、mentions=[]；全部范围不能作为REPLACE的具名报表。若确实指定某张报表，继续核对原词和授权目录；不猜测或缩小请求范围。");
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
    /** 查询或建单前校验期望范围的当前授权；不能因为期望范围失败而退回最近成功范围继续执行。 */
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
        // 公司后缀明确标出助词边界；前缀后的“的”仅对拉丁代码处理，中文名称末尾可能本身就是“的”，不能截断。
        code=code.replaceFirst("公司\\s*的$","公司").replaceFirst("^(公司\\s*[A-Za-z0-9_-]+)的$","$1")
                .replaceFirst("(?i)(\\s+company)[’']s$","$1")
                .replaceFirst("(?i)^(company\\s+.+)[’']s$","$1");
        // 公司代码可带中英文类别称谓；只剥离边界称谓，后续仍按实际授权代码精确校验，不做名称模糊匹配。
        code=code.replaceFirst("(?i)^company\\s+","").replaceFirst("^公司\\s*","").replaceFirst("(?i)\\s+company$","");
        if (code.endsWith("公司")) code=code.substring(0,code.length()-2).trim();
        return code.toUpperCase(Locale.ROOT);
    }
}
