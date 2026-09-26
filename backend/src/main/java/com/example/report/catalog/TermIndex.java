package com.example.report.catalog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 报表说法索引：名称、编码、report_id、别名归一化后建立的词典，外加“全部报表”这类通用说法。
 * 索引按目录快照构建一次；每次解析都带上当前用户可见的 report_id 集合过滤，
 * 不可见报表的名称和别名根本不会参与匹配，用户无法通过报表名称绕过权限。
 */
public final class TermIndex {

    public enum Kind {
        /** 当前展示名称 */
        NAME,
        /** 对外编码 */
        CODE,
        /** 稳定标识 */
        ID,
        /** 别名 */
        ALIAS,
        /** “全部报表”等通用说法 */
        ALL
    }

    /**
     * @param norm     归一化后的说法
     * @param surface  原始写法
     * @param reportId 指向的报表；ALL 为 null
     */
    public record Term(String norm, String surface, String reportId, Kind kind, int priority) {
        boolean exact() {
            return kind == Kind.NAME || kind == Kind.CODE || kind == Kind.ID;
        }
    }

    /** 文本中的一次命中：同一个说法可能指向多张可见报表（歧义） */
    public record Mention(int start, int end, String text, List<Term> terms) {
        public boolean all() {
            return terms.stream().anyMatch(t -> t.kind() == Kind.ALL);
        }

        public List<String> reportIds() {
            int strongest = terms.stream().filter(t -> t.reportId() != null)
                    .mapToInt(t -> t.kind().ordinal()).min().orElse(Integer.MAX_VALUE);
            Set<String> ids = new LinkedHashSet<>();
            terms.stream().filter(t -> t.reportId() != null && t.kind().ordinal() == strongest)
                    .forEach(t -> ids.add(t.reportId()));
            return List.copyOf(ids);
        }

        public boolean exactOnly() {
            return terms.stream().filter(t -> reportIds().contains(t.reportId()))
                    .allMatch(Term::exact);
        }
    }

    /** 构建索引的输入：一张报表的全部说法 */
    public record ReportTerms(String reportId, String reportName, String reportCode, List<AliasTerm> aliases) {
    }

    public record AliasTerm(String alias, int priority) {
    }

    public static final TermIndex EMPTY = build(List.of());

    private final Map<String, List<Term>> byNorm;
    private final Map<Character, List<String>> normsByFirstChar;
    private final List<Term> allTerms;

    private TermIndex(Map<String, List<Term>> byNorm) {
        this.byNorm = byNorm;
        Map<Character, List<String>> first = new HashMap<>();
        for (String norm : byNorm.keySet()) {
            first.computeIfAbsent(norm.charAt(0), c -> new ArrayList<>()).add(norm);
        }
        first.values().forEach(l -> l.sort(Comparator.comparingInt(String::length).reversed()));
        this.normsByFirstChar = first;
        List<Term> terms = new ArrayList<>();
        byNorm.values().forEach(terms::addAll);
        this.allTerms = List.copyOf(terms);
    }

    public static TermIndex build(Collection<ReportTerms> reports) {
        Map<String, Map<String, Term>> byNorm = new LinkedHashMap<>();
        for (ReportTerms r : reports) {
            add(byNorm, r.reportName(), r.reportId(), Kind.NAME, 100);
            add(byNorm, r.reportCode(), r.reportId(), Kind.CODE, 90);
            add(byNorm, r.reportId(), r.reportId(), Kind.ID, 80);
            if (r.aliases() != null) {
                r.aliases().forEach(a -> add(byNorm, a.alias(), r.reportId(), Kind.ALIAS, a.priority()));
            }
        }
        TextNormalizer.ALL_MENTIONS.forEach(w -> add(byNorm, w, null, Kind.ALL, 0));
        Map<String, List<Term>> result = new LinkedHashMap<>();
        byNorm.forEach((norm, terms) -> result.put(norm, List.copyOf(terms.values())));
        return new TermIndex(result);
    }

    /** 同一张报表的同一个说法只保留最强的来源：名称 > 编码 > ID > 别名 */
    private static void add(Map<String, Map<String, Term>> byNorm, String surface, String reportId, Kind kind, int priority) {
        String norm = TextNormalizer.normalize(surface);
        // 单字说法太容易误命中（“销”“表”），不进索引
        if (norm.length() < 2) {
            return;
        }
        Map<String, Term> terms = byNorm.computeIfAbsent(norm, k -> new LinkedHashMap<>());
        String key = reportId == null ? "*" : reportId;
        Term existing = terms.get(key);
        if (existing == null || kind.ordinal() < existing.kind().ordinal()) {
            terms.put(key, new Term(norm, surface, reportId, kind, priority));
        }
    }

    /** 整句正好是某个说法时的命中（仅可见报表） */
    public List<Term> exact(String normalized, Set<String> visible) {
        return visibleTerms(byNorm.get(normalized), visible);
    }

    /**
     * 从左到右做最长匹配，找出文本里提到的全部报表说法（仅可见报表）。
     * 纯英文说法要求两侧不是英文字母或数字，避免 “ar” 命中 “clear”。
     */
    public List<Mention> scan(String normalized, Set<String> visible) {
        List<Mention> mentions = new ArrayList<>();
        int i = 0;
        while (i < normalized.length()) {
            Mention mention = longestAt(normalized, i, visible);
            if (mention == null) {
                i++;
            } else {
                mentions.add(mention);
                i = mention.end();
            }
        }
        return mentions;
    }

    /** 当前可见报表的全部说法（模糊匹配用），不含通用说法 */
    public List<Term> terms(Set<String> visible) {
        return allTerms.stream().filter(t -> t.reportId() != null && visible.contains(t.reportId())).toList();
    }

    private Mention longestAt(String text, int start, Set<String> visible) {
        List<String> norms = normsByFirstChar.get(text.charAt(start));
        if (norms == null) {
            return null;
        }
        for (String norm : norms) {
            if (!text.startsWith(norm, start) || !boundaryOk(text, start, norm)) {
                continue;
            }
            List<Term> terms = visibleTerms(byNorm.get(norm), visible);
            if (!terms.isEmpty()) {
                return new Mention(start, start + norm.length(), text.substring(start, start + norm.length()), terms);
            }
        }
        return null;
    }

    private static boolean boundaryOk(String text, int start, String norm) {
        if (!TextNormalizer.isAsciiWord(norm)) {
            return true;
        }
        int end = start + norm.length();
        boolean leftOk = start == 0 || !TextNormalizer.isAsciiWordChar(text.charAt(start - 1));
        boolean rightOk = end >= text.length() || !TextNormalizer.isAsciiWordChar(text.charAt(end));
        return leftOk && rightOk;
    }

    private static List<Term> visibleTerms(List<Term> terms, Set<String> visible) {
        if (terms == null) {
            return List.of();
        }
        return terms.stream().filter(t -> t.kind() == Kind.ALL || visible.contains(t.reportId())).toList();
    }
}
