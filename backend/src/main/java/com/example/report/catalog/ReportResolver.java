package com.example.report.catalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 通用报表解析：把用户对报表的说法解析成当前用户可见目录内的 report_id。
 * 顺序：整句 / 句中说法最长匹配（名称、编码、ID 优先于别名）→ 模糊匹配（编辑距离、二元组、包含关系）。
 * 同一说法指向多张报表、或模糊匹配得分接近时一律返回 AMBIGUOUS，由用户选择，绝不自动挑一个。
 * 本类不做查询，只做解析；输入的候选范围必须已经按权限过滤。
 */
public final class ReportResolver {

    /** 形如单据号的片段（含数字的英文数字串）不是报表说法，不提示“未识别” */
    private static final Pattern DOC_NO_LIKE = Pattern.compile("[a-z0-9-]*\\d[a-z0-9-]*");

    private final double fuzzyThreshold;
    private final double ambiguityMargin;

    public ReportResolver(double fuzzyThreshold, double ambiguityMargin) {
        this.fuzzyThreshold = fuzzyThreshold;
        this.ambiguityMargin = ambiguityMargin;
    }

    /**
     * @param query   用户对报表的说法，可以是一个词，也可以是整句话；为空表示全部报表
     * @param visible 当前用户可见且允许派单的报表，按目录顺序
     */
    public ResolveResult resolve(String query, TermIndex index, List<ReportRef> visible) {
        if (visible == null || visible.isEmpty()) {
            return ResolveResult.none(query, true);
        }
        Map<String, ReportRef> byId = new LinkedHashMap<>();
        visible.forEach(r -> byId.put(r.reportId(), r));
        String normalized = TextNormalizer.normalize(query);
        if (normalized.isEmpty() || TextNormalizer.ALL_WHOLE.contains(normalized)) {
            return all(query, visible, List.of());
        }
        List<TermIndex.Mention> mentions = index.scan(normalized, byId.keySet());
        if (!mentions.isEmpty()) {
            return fromMentions(query, normalized, mentions, byId, visible);
        }
        return fuzzy(query, normalized, index, byId, visible);
    }

    private ResolveResult fromMentions(String query, String normalized, List<TermIndex.Mention> mentions,
                                       Map<String, ReportRef> byId, List<ReportRef> visible) {
        List<String> matched = mentions.stream().map(TermIndex.Mention::text).toList();
        if (mentions.stream().anyMatch(TermIndex.Mention::all)) {
            return all(query, visible, matched);
        }
        Set<String> unique = new LinkedHashSet<>();
        Set<String> ambiguous = new LinkedHashSet<>();
        Set<String> ordered = new LinkedHashSet<>();
        boolean exact = true;
        for (TermIndex.Mention m : mentions) {
            List<String> ids = m.reportIds();
            ordered.addAll(ids);
            if (ids.size() == 1) {
                unique.add(ids.get(0));
            } else {
                ambiguous.addAll(ids);
            }
            exact &= m.exactOnly();
        }
        List<String> unrecognized = unrecognized(normalized, mentions);
        if (!ambiguous.isEmpty()) {
            List<ReportRef> candidates = ordered.stream().map(byId::get).toList();
            List<String> preselected = unique.stream().filter(id -> !ambiguous.contains(id)).toList();
            return new ResolveResult(MatchType.AMBIGUOUS, query, null, candidates, preselected, matched, unrecognized, false);
        }
        List<ReportRef> reports = unique.stream().map(byId::get).toList();
        return new ResolveResult(exact ? MatchType.EXACT : MatchType.ALIAS, query, reports, null, null, matched,
                unrecognized, false);
    }

    private ResolveResult fuzzy(String query, String normalized, TermIndex index, Map<String, ReportRef> byId,
                                List<ReportRef> visible) {
        String stripped = String.join("", TextNormalizer.stripQueryWords(normalized));
        if (stripped.isEmpty() || TextNormalizer.isGenericOnly(stripped)) {
            // “查一下我有哪些可以派单”“查我能派单的报表”：没有指定报表，就是全部
            return all(query, visible, List.of());
        }
        Map<String, Double> scores = new HashMap<>();
        Map<String, String> bestTerm = new HashMap<>();
        for (TermIndex.Term term : index.terms(byId.keySet())) {
            double score = Math.max(similarity(stripped, term.norm()), similarity(normalized, term.norm()));
            if (score > scores.getOrDefault(term.reportId(), 0.0)) {
                scores.put(term.reportId(), score);
                bestTerm.put(term.reportId(), term.surface());
            }
        }
        // 稳定排序：同分时保持目录顺序
        List<ReportRef> ranked = visible.stream()
                .filter(r -> scores.getOrDefault(r.reportId(), 0.0) >= fuzzyThreshold)
                .sorted(Comparator.comparingDouble((ReportRef r) -> scores.get(r.reportId())).reversed())
                .toList();
        if (ranked.isEmpty()) {
            return ResolveResult.none(query, false);
        }
        double top = scores.get(ranked.get(0).reportId());
        List<ReportRef> close = ranked.stream()
                .filter(r -> top - scores.get(r.reportId()) < ambiguityMargin)
                .toList();
        List<String> matched = close.stream().map(r -> bestTerm.get(r.reportId())).toList();
        if (close.size() == 1) {
            return new ResolveResult(MatchType.FUZZY, query, close, null, null, matched, null, false);
        }
        return new ResolveResult(MatchType.AMBIGUOUS, query, null, close, null, matched, null, false);
    }

    private static ResolveResult all(String query, List<ReportRef> visible, List<String> matched) {
        return new ResolveResult(MatchType.ALL, query, visible, null, null, matched, null, false);
    }

    /** 去掉命中的说法、通用查询词和泛称后还剩下的片段 */
    private static List<String> unrecognized(String normalized, List<TermIndex.Mention> mentions) {
        StringBuilder rest = new StringBuilder(normalized);
        for (int i = mentions.size() - 1; i >= 0; i--) {
            TermIndex.Mention m = mentions.get(i);
            rest.replace(m.start(), m.end(), " ");
        }
        List<String> result = new ArrayList<>();
        for (String chunk : TextNormalizer.stripAllCommonWords(rest.toString())) {
            if (chunk.length() >= 2 && !DOC_NO_LIKE.matcher(chunk).matches()) {
                result.add(chunk);
            }
        }
        return result;
    }

    /** 0~1 的相似度：编辑距离、二元组 Dice、包含关系取最大*/
    static double similarity(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        if (a.equals(b)) {
            return 1;
        }
        double lev = 1.0 - (double) levenshtein(a, b) / Math.max(a.length(), b.length());
        double containment = 0;
        if (containable(a) && b.contains(a)) {
            containment = 0.6 + 0.4 * a.length() / b.length();
        } else if (containable(b) && a.contains(b)) {
            containment = 0.6 + 0.4 * b.length() / a.length();
        }
        return Math.max(lev, Math.max(dice(a, b), containment));
    }

    /** 被包含的一方至少两个字；纯英文要四个字母以上，否则 “ar” 会包含在大量单词里 */
    private static boolean containable(String s) {
        return s.length() >= 2 && (!TextNormalizer.isAsciiWord(s) || s.length() >= 4);
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length()];
    }

    private static double dice(String a, String b) {
        if (a.length() < 2 || b.length() < 2) {
            return 0;
        }
        Map<String, Integer> grams = new HashMap<>();
        for (int i = 0; i + 1 < a.length(); i++) {
            grams.merge(a.substring(i, i + 2), 1, Integer::sum);
        }
        int intersection = 0;
        for (int i = 0; i + 1 < b.length(); i++) {
            String g = b.substring(i, i + 2);
            Integer n = grams.get(g);
            if (n != null && n > 0) {
                intersection++;
                grams.put(g, n - 1);
            }
        }
        return 2.0 * intersection / ((a.length() - 1) + (b.length() - 1));
    }
}
