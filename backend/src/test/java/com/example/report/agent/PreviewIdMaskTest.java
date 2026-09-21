package com.example.report.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预览编号掩码：模型复述或编造的内部标识不能出现在用户可见文本里，
 * 同时必须保证正常内容一字不差地放行（含逐字符流式输出）。
 */
class PreviewIdMaskTest {

    /** 真实模型是逐字流式输出的，所以两种喂法都要覆盖 */
    private static String apply(String text, boolean charByChar) {
        PreviewIdMask mask = new PreviewIdMask();
        StringBuilder out = new StringBuilder();
        if (charByChar) {
            for (char c : text.toCharArray()) {
                mask.feed(String.valueOf(c)).forEach(out::append);
            }
        } else {
            mask.feed(text).forEach(out::append);
        }
        mask.flush().forEach(out::append);
        return out.toString();
    }

    @Test
    void plainTextPassesThroughUnchanged() {
        String text = "已重新查询应收报表，共 2 条可派单记录。最新预览见卡片。";
        assertEquals(text, apply(text, false));
        assertEquals(text, apply(text, true));
    }

    @Test
    void fabricatedPreviewIdIsReplacedInBothModes() {
        String text = "已重查应收报表：0 条可派单记录。预览编号 `63c4f869bd97fa63adc47590d5ad3ae9`。两张报表现在都是空的。";
        for (boolean charByChar : new boolean[]{false, true}) {
            String masked = apply(text, charByChar);
            assertFalse(masked.contains("63c4f869bd97fa63adc47590d5ad3ae9"));
            assertFalse(masked.contains("预览编号"));
            assertTrue(masked.contains(PreviewIdMask.REPLACEMENT));
            // 编号后面的正文必须保留
            assertTrue(masked.contains("两张报表现在都是空的"));
        }
    }

    @Test
    void maskedIdsAreRecordedForLaterVerification() {
        PreviewIdMask mask = new PreviewIdMask();
        mask.feed("预览编号 5E41AC89CF1D4E0CB23A1D65EC9A7E17 已生成").forEach(s -> {
        });
        assertEquals(java.util.Set.of("5e41ac89cf1d4e0cb23a1d65ec9a7e17"), mask.maskedIds());
    }

    @Test
    void previewIdKeywordWithoutNumberIsKept() {
        String text = "预览编号见卡片。";
        assertEquals(text, apply(text, false));
        assertEquals(text, apply(text, true));
    }

    @Test
    void shortHexTailIsNotTreatedAsAnId() {
        String text = "预览编号 abc，仅供参考。";
        assertEquals(text, apply(text, true));
    }

    @Test
    void previewIdEnglishKeywordIsMaskedToo() {
        String text = "previewId: 5e41ac89cf1d4e0cb23a1d65ec9a7e17";
        String masked = apply(text, true);
        assertFalse(masked.contains("5e41ac89cf1d4e0cb23a1d65ec9a7e17"));
        assertTrue(masked.contains(PreviewIdMask.REPLACEMENT));
    }

    @Test
    void flushReleasesAnIncompleteTailInsteadOfSwallowingIt() {
        PreviewIdMask mask = new PreviewIdMask();
        StringBuilder out = new StringBuilder();
        mask.feed("预览编号 63c4").forEach(out::append);
        assertEquals("", out.toString(), "不足 32 位时先暂存，等后续片段");
        mask.flush().forEach(out::append);
        assertEquals("预览编号 63c4", out.toString(), "流结束时必须把暂存内容放行");
    }

    @Test
    void plainHexTextIsNotMaskedWithoutAKeyword() {
        String text = "订单号 SO2026002，金额 12.50，编号 63c4f869bd97fa63adc47590d5ad3ae9 无需展示。";
        assertEquals(text, apply(text, true));
    }
}
