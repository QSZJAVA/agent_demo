package com.example.report.agent;

import com.example.report.catalog.TermIndex;
import com.example.report.catalog.TextNormalizer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 服务端兜底用的意图识别：只识别含明确报表范围的纯查询、简短范围纠正、范围追加与范围排除；复合意图仍交给模型处理。
 * 报表说法完全来自报表目录（名称、编码、别名）：先用目录索引找出句子里的报表说法并替换成占位符，
 * 再用与具体报表无关的通用句式匹配。新增报表只需要维护目录，这里的正则不需要改。
 */
final class PreviewIntentDetector {

    /** 报表说法的占位符（Unicode 私用区字符，不会出现在正常输入里） */
    private static final char MARK = '\uE000';
    /** 单个报表范围：占位符，后面可带“报表”二字（别名“应收”+“报表”） */
    private static final String SCOPE = MARK + "(?:报表)?";
    /** 报表范围之间的连接词：应收和费用报表 / 销售、应收 / 应收+费用 */
    private static final String SEPARATOR = "(?:[、,，/+和与跟及]|以及|还有|加上)";
    /** 报表范围序列 */
    private static final String SCOPES = "(?<scopes>" + SCOPE + "(?:" + SEPARATOR + SCOPE + ")*)";
    /** 范围表达式的收尾词：销售报表 / 费用报表的 / 只看应收吧 / 还有费用吗 */
    private static final String TAIL = "的?(?:就行|即可|吧|吗|么|呢)?";
    /** 范围追加词：加上费用报表的 / 还要看应收 / 顺便查一下费用报表 */
    private static final String APPEND_PREFIX =
            "(?:再)?(?:加上|外加|还要|还有|顺便|同时|另外|再看|再查)(?:(?:看|查)(?:一下|下)?|加上)?";

    private static final Pattern COMPANY = Pattern.compile("(?:(?:我|我的)?(?<prefix>[a-z][a-z0-9_-]{0,31})公司|公司(?<suffix>[a-z][a-z0-9_-]{0,31}))(?:的)?");
    /** 已有查询后的公司追问，例如“我在 B 公司有吗”；不把权限说明或派单命令当作查询。 */
    private static final Pattern COMPANY_FOLLOW_UP = Pattern.compile(
            "(?:那|那么)?(?:我(?:在)?)?(?:还有|有|有没有)(?:吗|么|呢)?");
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
                    + "|的?(?:可|可以|能|需要|待)派单的?(?:记录|单据)?)?" + TAIL);
    /** 后置追问动词：应收报表再查下 / 应收报表重查一下 / 销售报表再看看 / 应收报表刷新一下 */
    private static final String FOLLOW_UP_VERB =
            "(?:再|重新|重|又)?(?:查(?:询)?|看(?:看)?|刷新|载入|预览|拉)(?:一下|下|一次|一遍)?";
    /**
     * 报表范围 + 追问动词。用户用"应收报表再查下"这类口语化追问时，QUERY 等模式都匹配不上，
     * 服务端就不会兜底刷新预览，只剩模型自由发挥——它很容易只说一句"已重查"却不调用工具。
     */
    private static final Pattern FOLLOW_UP_QUERY = Pattern.compile(SCOPES + FOLLOW_UP_VERB + TAIL);

    private PreviewIntentDetector() {
    }

    /**
     * @param reportQuery 句中报表说法（交给报表解析服务），null 表示全部报表
     * @param reportIds   说法对应的可见报表（同一说法指向多张报表时都在里面，由解析服务判定为歧义）
     * @param companyCode 用户明确说出的公司代码，null 表示当前用户默认可见范围
     * @param append      true 表示"在当前范围上追加"（例如"加上费用报表的"），由服务端与上一轮预览范围合并
     * @param remove      true 表示"从当前范围中排除"（例如"应收的也删掉"），由服务端从上一轮预览范围中减去
     */
    record PreviewIntent(String reportQuery, List<String> reportIds, String companyCode, boolean append, boolean remove) {
        PreviewIntent {
            reportIds = reportIds == null ? List.of() : List.copyOf(reportIds);
        }
    }

    /**
     * @param terms   报表目录的说法索引
     * @param visible 当前用户可派单的报表；不可见报表的说法不会被识别
     */
    static Optional<PreviewIntent> detect(String message, TermIndex terms, Set<String> visible) {
        if (message == null) {
            return Optional.empty();
        }
        String text = TextNormalizer.normalize(message).replaceAll("[。！？!?]+$", "");
        if (text.isEmpty()) {
            return Optional.empty();
        }
        String companyCode = companyCode(text);
        String scopeText = companyCode == null ? text : COMPANY.matcher(text).replaceAll("");
        // 公司代码已从 scopeText 中剥离，报表范围与追加词仍然可以出现在公司代码两侧
        List<TermIndex.Mention> mentions = terms.scan(scopeText, visible);
        String tokens = tokenize(scopeText, mentions);

        Matcher followUp = APPEND_FOLLOW_UP.matcher(tokens);
        if (followUp.matches()) {
            return intent(followUp, tokens, mentions, companyCode, true, false, false);
        }
        Matcher append = APPEND.matcher(tokens);
        if (append.matches()) {
            Optional<PreviewIntent> intent = intent(append, tokens, mentions, companyCode, true, false, true);
            if (intent.isPresent()) {
                return intent;
            }
        }
        for (Pattern remove : List.of(REMOVE_AFTER, REMOVE_BEFORE)) {
            Matcher m = remove.matcher(tokens);
            if (m.matches()) {
                Optional<PreviewIntent> intent = intent(m, tokens, mentions, companyCode, false, true, true);
                if (intent.isPresent()) {
                    return intent;
                }
            }
        }
        for (Pattern replace : List.of(CORRECTION, QUERY, FOLLOW_UP_QUERY)) {
            Matcher m = replace.matcher(tokens);
            if (m.matches()) {
                return intent(m, tokens, mentions, companyCode, false, false, false);
            }
        }
        if (companyCode == null || !(BROAD_QUERY.matcher(tokens).matches()
                || COMPANY_FOLLOW_UP.matcher(tokens).matches())) {
            return Optional.empty();
        }
        return Optional.of(new PreviewIntent(null, List.of(), companyCode, false, false));
    }

    /** 报表说法替换成占位符 */
    private static String tokenize(String text, List<TermIndex.Mention> mentions) {
        StringBuilder sb = new StringBuilder();
        int cursor = 0;
        for (TermIndex.Mention m : mentions) {
            sb.append(text, cursor, m.start()).append(MARK);
            cursor = m.end();
        }
        return sb.append(text.substring(cursor)).toString();
    }

    /**
     * 取出句式中 scopes 分组覆盖的报表说法。出现“全部报表”就按全部报表处理；
     * requireSpecific 时（追加 / 排除）全部报表没有意义，视为不匹配，交给后面的句式或模型。
     */
    private static Optional<PreviewIntent> intent(Matcher matcher, String tokens, List<TermIndex.Mention> mentions,
                                                  String companyCode, boolean append, boolean remove, boolean requireSpecific) {
        int first = count(tokens, 0, matcher.start("scopes"));
        int n = count(tokens, matcher.start("scopes"), matcher.end("scopes"));
        List<TermIndex.Mention> used = mentions.subList(first, first + n);
        if (used.stream().anyMatch(TermIndex.Mention::all)) {
            return requireSpecific ? Optional.empty()
                    : Optional.of(new PreviewIntent(null, List.of(), companyCode, append, remove));
        }
        Set<String> ids = new LinkedHashSet<>();
        used.forEach(m -> ids.addAll(m.reportIds()));
        String query = used.stream().map(TermIndex.Mention::text).collect(Collectors.joining("、"));
        return Optional.of(new PreviewIntent(query, new ArrayList<>(ids), companyCode, append, remove));
    }

    private static int count(String tokens, int from, int to) {
        int n = 0;
        for (int i = from; i < to; i++) {
            if (tokens.charAt(i) == MARK) {
                n++;
            }
        }
        return n;
    }

    private static String companyCode(String text) {
        Matcher matcher = COMPANY.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String code = matcher.group("prefix") != null ? matcher.group("prefix") : matcher.group("suffix");
        // 多公司、否定后切换等复合说法交给模型处理，不能只采用第一家公司。
        return code == null || matcher.find() ? null : code.toUpperCase(Locale.ROOT);
    }
}
