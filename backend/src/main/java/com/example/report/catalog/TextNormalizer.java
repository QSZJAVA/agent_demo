package com.example.report.catalog;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 报表解析用的文本归一化与通用词表。这里只有与具体报表无关的通用词（动词、代词、语气词、泛称），
 * 报表名称和别名全部来自目录数据，新增报表不需要改这里。
 */
public final class TextNormalizer {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** 在句子中出现即表示“全部报表”的说法 */
    static final Set<String> ALL_MENTIONS = Set.of("全部报表", "所有报表", "全部的报表", "所有的报表", "all");
    /** 整句只有这些词时也表示全部报表（“全部”出现在句子中间多半是“全部派掉”，不能当成范围） */
    static final Set<String> ALL_WHOLE = Set.of("全部", "所有", "全部的", "所有的", "*", "全部报表的", "所有报表的");

    /** 报表泛称：不单独构成报表名，但模糊匹配时保留（“销兽报表”要靠“报表”二字对上“销售报表”） */
    static final List<String> GENERIC_WORDS = sortedByLengthDesc(List.of("报表", "台账", "明细", "报告", "清单", "表"));

    /** 查询语句里的通用词：模糊匹配和“未识别部分”分析前去掉 */
    static final List<String> QUERY_WORDS = sortedByLengthDesc(List.of(
            "帮我查一下", "帮我查询", "帮我看看", "帮我看下", "帮我查", "给我查", "请帮我", "麻烦",
            "查一下", "查询", "查下", "看一下", "看看", "看下", "列一下", "列出", "预览", "一下", "一遍", "一次",
            "有哪些", "有什么", "有啥", "哪些", "什么", "是否", "有没有", "可以", "能够", "需要",
            "待派单", "可派单", "派单", "派一下", "记录", "单据", "数据", "当前", "我们", "我的", "公司",
            "还要", "加上", "另外", "顺便", "再看", "再查", "重新", "刷新", "以及", "还有",
            "只看", "只要", "只查", "仅看", "仅查", "我说", "我指", "的是", "换成", "改成", "切换到", "切换成",
            "删掉", "删除", "去掉", "移除", "排除", "不要", "不用", "全部", "所有", "一共", "多少",
            "查", "看", "列", "的", "我", "吧", "呢", "吗", "么", "呀", "啊", "了", "和", "与", "跟", "及",
            "也", "都", "再", "请", "只", "仅", "就", "把", "将", "有", "能", "派", "中", "里",
            "、", ",", "，", "。", "？", "?", "!", "！", "+", "/", ";", "；", ":", "：", "\"", "“", "”", "'", "（", "）", "(", ")"));

    private TextNormalizer() {
    }

    /** NFKC（全角转半角）+ 小写 + 去掉全部空白 */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String n = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return WHITESPACE.matcher(n).replaceAll("");
    }

    /** 去掉通用查询词后剩下的片段（按原顺序），泛称保留 */
    static List<String> stripQueryWords(String normalized) {
        return chunks(normalized, QUERY_WORDS);
    }

    /**
     * 一句话去掉通用查询词后还剩的内容；只剩泛称（“报表”）或什么都不剩时返回空串，表示没有指定报表
     */
    public static String meaningfulRemainder(String text) {
        String rest = String.join("", stripQueryWords(normalize(text)));
        return rest.isEmpty() || isGenericOnly(rest) ? "" : rest;
    }

    /** 去掉通用查询词和泛称后剩下的片段，用来发现用户说了但目录里对不上的内容 */
    static List<String> stripAllCommonWords(String normalized) {
        List<String> words = new java.util.ArrayList<>(QUERY_WORDS);
        words.addAll(GENERIC_WORDS);
        return chunks(normalized, sortedByLengthDesc(words));
    }

    static boolean isGenericOnly(String normalized) {
        return !normalized.isEmpty() && chunks(normalized, GENERIC_WORDS).isEmpty();
    }

    static boolean isAsciiAlnum(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    /** 英文词的组成字符：字母、数字、下划线（report_sales 里的 sales 不是一个独立的词） */
    static boolean isAsciiWordChar(char c) {
        return isAsciiAlnum(c) || c == '_';
    }

    static boolean isAsciiWord(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!isAsciiAlnum(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static List<String> chunks(String normalized, List<String> words) {
        String s = normalized == null ? "" : normalized;
        for (String w : words) {
            s = s.replace(w, " ");
        }
        return java.util.Arrays.stream(s.trim().split(" +")).filter(p -> !p.isBlank()).toList();
    }

    private static List<String> sortedByLengthDesc(List<String> words) {
        return words.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();
    }
}
