package com.example.report.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 流式文本掩码：把模型写进回复里的内部标识（预览编号 / previewId）拦下来。
 *
 * 背景：模型有时会复述预览编号，甚至凭空编造一个 32 位十六进制串（系统里根本没有这个快照），
 * 而预览已经由界面卡片完整展示，用户看到这种编号只会被误导。
 *
 * 做法：识别到关键词后把紧随其后的少量字符暂存，凑满 32 位十六进制就整体替换为指路文案；
 * 只要出现非十六进制字符就立刻原样放行。暂存上限很小，对流式输出几乎没有感知延迟。
 */
class PreviewIdMask {

    /** 关键词：模型提及内部标识时的常见写法（末尾几个字符可能是下一个片段才补齐的） */
    private static final String[] KEYWORDS = {
            "预览编号", "预览ID", "previewId", "preview id", "preview_id", "preview-id"
    };
    private static final Pattern KEYWORD = Pattern.compile("(?i)预览\\s*编号|预览\\s*id|preview[ _-]?id");
    /** 编号两侧可能出现的包装字符：反引号、引号、冒号、空白（含换行）、markdown 强调符 */
    private static final String WRAPPERS = " \t\r\n:：`'\"“”‘’*";
    /** 关键词之后最多暂存这么多字符，超出就放行，避免拖住正常输出 */
    private static final int HOLD_LIMIT = 48;
    private static final int ID_LENGTH = 32;
    /** 替换文案：用户需要的是"去哪看"，而不是一个内部编号 */
    static final String REPLACEMENT = "（见下方卡片）";

    /** 尚未确认安全的尾部文本；holding 为 true 时它一定以关键词开头 */
    private final StringBuilder hold = new StringBuilder();
    private final Set<String> maskedIds = new LinkedHashSet<>();
    private boolean holding;

    /**
     * 喂入一个流式片段，返回本次可以安全输出的文本。
     * 返回空表示这个片段还在暂存区里（例如编号只写了一半），继续等下一个片段。
     */
    List<String> feed(String delta) {
        List<String> out = new ArrayList<>();
        if (delta != null && !delta.isEmpty()) {
            hold.append(delta);
        }
        while (true) {
            if (!holding) {
                Matcher keyword = KEYWORD.matcher(hold);
                if (!keyword.find()) {
                    // 没有关键词：放行，但保留可能是关键词前缀的尾部（例如"...预览编"）
                    int keep = tailKeywordPrefix(hold);
                    int safe = hold.length() - keep;
                    if (safe <= 0) {
                        break;
                    }
                    out.add(hold.substring(0, safe));
                    hold.delete(0, safe);
                    break;
                }
                if (keyword.start() > 0) {
                    out.add(hold.substring(0, keyword.start()));
                    hold.delete(0, keyword.start());
                }
                holding = true;
            }

            Matcher keyword = KEYWORD.matcher(hold);
            if (!keyword.lookingAt()) {
                // 兜底：状态与内容不一致时直接放行，绝不吞掉正常文本
                out.add(hold.toString());
                hold.setLength(0);
                holding = false;
                break;
            }
            int cursor = keyword.end();
            while (cursor < hold.length() && WRAPPERS.indexOf(hold.charAt(cursor)) >= 0) {
                cursor++;
            }
            int hexEnd = cursor;
            while (hexEnd < hold.length() && isHex(hold.charAt(hexEnd))) {
                hexEnd++;
            }
            if (hexEnd - cursor >= ID_LENGTH) {
                maskedIds.add(hold.substring(cursor, cursor + ID_LENGTH).toLowerCase(Locale.ROOT));
                int end = cursor + ID_LENGTH;
                while (end < hold.length() && WRAPPERS.indexOf(hold.charAt(end)) >= 0) {
                    end++;
                }
                String rest = hold.substring(end);
                hold.setLength(0);
                hold.append(rest);
                holding = false;
                out.add(REPLACEMENT);
                continue;
            }
            if (hexEnd < hold.length()) {
                // 出现非十六进制字符且没凑够 32 位，确认不是编号 → 原样放行
                out.add(hold.toString());
                hold.setLength(0);
                holding = false;
                break;
            }
            // 目前只有包装符和十六进制字符，可能是被流式截断的编号；暂存上限到了就放行
            if (hold.length() - keyword.end() > HOLD_LIMIT) {
                out.add(hold.toString());
                hold.setLength(0);
                holding = false;
                break;
            }
            break;
        }
        return out;
    }

    /** 流结束时把暂存内容原样放行，避免吞掉模型真正想说的内容 */
    List<String> flush() {
        if (hold.length() == 0) {
            return List.of();
        }
        String text = hold.toString();
        hold.setLength(0);
        holding = false;
        return List.of(text);
    }

    /** 本次被掩掉的编号（小写），供服务端判断模型是不是在复述/编造编号 */
    Set<String> maskedIds() {
        return Set.copyOf(maskedIds);
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** 文本末尾正好是某个关键词的前缀时返回该前缀长度，否则 0 */
    private static int tailKeywordPrefix(CharSequence text) {
        int best = 0;
        for (String keyword : KEYWORDS) {
            int max = Math.min(keyword.length() - 1, text.length());
            for (int len = max; len > 0; len--) {
                if (matchesIgnoreCase(text, text.length() - len, keyword, len)) {
                    best = Math.max(best, len);
                    break;
                }
            }
        }
        return best;
    }

    private static boolean matchesIgnoreCase(CharSequence text, int offset, String keyword, int len) {
        for (int i = 0; i < len; i++) {
            if (Character.toLowerCase(text.charAt(offset + i)) != Character.toLowerCase(keyword.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
