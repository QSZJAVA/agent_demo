package com.example.report.agent;

import com.example.report.report.ReportType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 只识别含明确报表范围的纯查询、简短范围纠正、范围追加与范围排除；复合意图仍交给模型处理。
 *
 * @param reportTypes 本次要查询（或要排除）的报表类型（空列表表示全部报表）
 * @param companyCode 用户明确说出的公司代码，null 表示当前用户默认可见范围
 * @param append      true 表示"在当前范围上追加"（例如"加上费用报表的"），由服务端与上一轮预览范围合并
 * @param remove      true 表示"从当前范围中排除"（例如"应收的也删掉"），由服务端从上一轮预览范围中减去
 */
record ReportPreviewRequest(List<String> reportTypes, String companyCode, boolean append, boolean remove) {

    /** 单个报表范围词 */
    private static final String SCOPE =
            "(?:销售(?:报表)?|应收(?:报表)?|费用(?:报表)?|全部报表|所有报表|sales|receivable|expense|all)";
    /** 报表范围之间的连接词：应收和费用报表 / 销售、应收 / 应收+费用 */
    private static final String SEPARATOR = "(?:\\s*(?:[、,，/+和与跟及]|以及|还有|加上)\\s*)";
    /** 报表范围序列，内容交给 types() 拆成报表类型列表 */
    private static final String SCOPES = "(?<scopes>" + SCOPE + "(?:" + SEPARATOR + SCOPE + ")*)";
    /** 范围表达式的收尾词：销售报表 / 费用报表的 / 只看应收吧 / 还有费用吗 */
    private static final String TAIL = "的?(?:就行|即可|吧|吗|么|呢)?";
    /** 范围追加词：加上费用报表的 / 还要看应收 / 顺便查一下费用报表 */
    private static final String APPEND_PREFIX =
            "(?:再)?(?:加上|外加|还要|还有|顺便|同时|另外|再看|再查)(?:(?:看|查)(?:一下|下)?|加上)?";

    private static final Pattern COMPANY = Pattern.compile("(?:(?:我|我的)?(?<prefix>[A-Za-z][A-Za-z0-9_-]{0,31})公司|公司(?<suffix>[A-Za-z][A-Za-z0-9_-]{0,31}))");
    private static final Pattern BROAD_QUERY = Pattern.compile(
            "(?:请|麻烦)?(?:(?:帮我|给我)?(?:查(?:询|一下|下)?|看(?:看|一下|下)?|列(?:出|一下)?|预览)(?:一下|下)?)?"
                    + "(?:(?:我|当前)的?)?(?:有哪些|啥|什么)(?:是)?(?:可以|能|需要|待)?派单(?:的)?(?:记录|单据)?(?:吧)?");
    private static final Pattern CORRECTION = Pattern.compile(
            "(?:我(?:说|指)(?:的)?(?:是)?|只(?:看|要|查)|仅(?:看|查)|改(?:成|为)|换(?:成|为)|切换(?:到|成|为))"
                    + SCOPES + TAIL);
    private static final Pattern APPEND = Pattern.compile(APPEND_PREFIX + SCOPES + TAIL);
    /** 追问式追加：费用报表呢 / 那应收呢 / 也看看费用呢 —— 表示"在刚才的范围上再看看这张报表" */
    private static final Pattern APPEND_FOLLOW_UP = Pattern.compile(
            "(?:那|那么)?(?:也|再)?" + SCOPES + "(?:呢|呐|吗|么|怎么样|咋样)");
    /** 排除词：删掉 / 去掉 / 移除 / 不要 / 排除 / 不用 */
    private static final String REMOVE_WORD = "(?:删(?:掉|除|去)|去掉|移除|不要|排除|不用)";
    /** 后置语序：应收的也删掉 / 把销售报表去掉 / 排除费用报表 */
    private static final Pattern REMOVE_AFTER = Pattern.compile(
            "(?:把|将)?(?:这|那)?(?:个|些)?" + SCOPES + "(?:的)?" + "(?:也|都)?" + REMOVE_WORD + TAIL);
    /** 前置语序：删掉应收报表 / 不要应收的 / 去掉费用 */
    private static final Pattern REMOVE_BEFORE = Pattern.compile(REMOVE_WORD + SCOPES + TAIL);
    private static final Pattern QUERY = Pattern.compile(
            "(?:请|麻烦)?(?:(?:帮我|给我)?(?:查(?:询|一下|下)?|看(?:看|一下|下)?|列(?:出|一下)?|预览)(?:一下|下)?)?"
                    + "(?:(?:我|当前)的?)?" + SCOPES
                    + "(?:(?:中|里|的)?(?:有)?(?:哪些|啥|什么)(?:是)?(?:可以|能|需要|待)?派单(?:的)?(?:记录|单据)?"
                    + "|的?(?:可|可以|能|需要|待)派单的?(?:记录|单据)?)?(?:吧)?");
    /** 后置追问动词：应收报表再查下 / 应收报表重查一下 / 销售报表再看看 / 应收报表刷新一下 */
    private static final String FOLLOW_UP_VERB =
            "(?:再|重新|重|又)?(?:查(?:询)?|看(?:看)?|刷新|载入|预览|拉)(?:一下|下|一次|一遍)?";
    /**
     * 报表范围 + 追问动词。用户用"应收报表再查下"这类口语化追问时，QUERY 等模式都匹配不上，
     * 服务端就不会兜底刷新预览，只剩模型自由发挥——它很容易只说一句"已重查"却不调用工具。
     */
    private static final Pattern FOLLOW_UP_QUERY = Pattern.compile(SCOPES + FOLLOW_UP_VERB + TAIL);
    private static final Pattern SCOPE_WORD = Pattern.compile(SCOPE);

    ReportPreviewRequest {
        reportTypes = reportTypes == null ? List.of() : List.copyOf(reportTypes);
    }

    static Optional<ReportPreviewRequest> resolve(String message) {
        if (message == null) return Optional.empty();
        String text = message.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "")
                .replaceAll("[。！？!?]+$", "");
        if (text.isEmpty()) return Optional.empty();
        String companyCode = companyCode(text);
        String scopeText = companyCode == null ? text : COMPANY.matcher(text).replaceAll("");

        // 公司代码已从 scopeText 中剥离，报表范围与追加词仍然可以出现在公司代码两侧
        Matcher followUp = APPEND_FOLLOW_UP.matcher(scopeText);
        if (followUp.matches()) {
            return Optional.of(new ReportPreviewRequest(types(followUp.group("scopes")), companyCode, true, false));
        }
        Matcher append = APPEND.matcher(scopeText);
        if (append.matches()) {
            List<String> types = types(append.group("scopes"));
            if (!types.isEmpty()) {
                return Optional.of(new ReportPreviewRequest(types, companyCode, true, false));
            }
        }
        Matcher removeAfter = REMOVE_AFTER.matcher(scopeText);
        if (removeAfter.matches()) {
            List<String> types = types(removeAfter.group("scopes"));
            if (!types.isEmpty()) {
                return Optional.of(new ReportPreviewRequest(types, companyCode, false, true));
            }
        }
        Matcher removeBefore = REMOVE_BEFORE.matcher(scopeText);
        if (removeBefore.matches()) {
            List<String> types = types(removeBefore.group("scopes"));
            if (!types.isEmpty()) {
                return Optional.of(new ReportPreviewRequest(types, companyCode, false, true));
            }
        }
        Matcher correction = CORRECTION.matcher(scopeText);
        if (correction.matches()) {
            return Optional.of(new ReportPreviewRequest(types(correction.group("scopes")), companyCode, false, false));
        }
        Matcher query = QUERY.matcher(scopeText);
        if (query.matches()) {
            return Optional.of(new ReportPreviewRequest(types(query.group("scopes")), companyCode, false, false));
        }
        Matcher followUpQuery = FOLLOW_UP_QUERY.matcher(scopeText);
        if (followUpQuery.matches()) {
            return Optional.of(new ReportPreviewRequest(types(followUpQuery.group("scopes")), companyCode, false, false));
        }
        if (companyCode == null || !BROAD_QUERY.matcher(scopeText).matches()) return Optional.empty();
        return Optional.of(new ReportPreviewRequest(List.of(), companyCode, false, false));
    }

    /** 报表范围的展示名，用于服务端生成的回复文案 */
    static String displayName(List<String> reportTypes) {
        if (reportTypes == null || reportTypes.isEmpty()) {
            return "全部报表";
        }
        return reportTypes.stream()
                .map(code -> ReportType.fromCode(code).label())
                .collect(Collectors.joining(" + "));
    }

    private static List<String> types(String scopes) {
        List<String> result = new ArrayList<>();
        if (scopes == null || scopes.isEmpty()) {
            return result;
        }
        Matcher matcher = SCOPE_WORD.matcher(scopes);
        while (matcher.find()) {
            String code = codeOf(matcher.group());
            if (code == null) {
                // 出现"全部报表"就按全部报表处理
                return List.of();
            }
            if (!result.contains(code)) {
                result.add(code);
            }
        }
        return result;
    }

    private static String codeOf(String word) {
        if (word.startsWith("销售") || word.equals("sales")) return "sales";
        if (word.startsWith("应收") || word.equals("receivable")) return "receivable";
        if (word.startsWith("费用") || word.equals("expense")) return "expense";
        return null;
    }

    private static String companyCode(String text) {
        Matcher matcher = COMPANY.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String code = matcher.group("prefix") != null ? matcher.group("prefix") : matcher.group("suffix");
        return code == null ? null : code.toUpperCase(Locale.ROOT);
    }
}
