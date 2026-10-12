package com.example.report.analysis;

import com.example.report.common.ApiException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 来源召回、精确引用及制度表单边界的纯逻辑回归，不据此声称模型答案已经真实验收。 */
class AnalysisEvidenceTest {
    static AnalysisEvidence.Source source(String id,String title,String text){return new AnalysisEvidence.Source(id,id,"KNOWLEDGE",title,"2","A / rpt-sales-order","2026-09-01生效",text,0);}
    static final AnalysisEvidence.Source SOURCE=source("s1","销售口径","销售金额合计为90071992547409.93元；不等于已收款。");
    static AnalysisEvidence.Answer answer(String text,String id,String quote){return new AnalysisEvidence.Answer("ANSWERED",List.of(new AnalysisEvidence.Claim(text,List.of(new AnalysisEvidence.Citation(id,quote)))),List.of());}
    @Test void acceptsExactGroundedDecimal(){assertDoesNotThrow(()->AnalysisEvidence.validate(answer("合计90071992547409.93元。","s1",SOURCE.text()),List.of(SOURCE)));}
    @Test void serverResolvesOnlySelectedActualSourceIntoExactQuote(){var draft=new AnalysisEvidence.Draft("ANSWERED",List.of(new AnalysisEvidence.DraftClaim("销售金额不等于已收款。",List.of("s1"))),List.of());
        var resolved=AnalysisEvidence.resolve(draft,List.of(SOURCE));assertEquals(SOURCE.text(),resolved.claims().get(0).citations().get(0).quote());
        assertThrows(ApiException.class,()->AnalysisEvidence.resolve(draft,List.of()));}
    @Test void sourcePartsPreserveEveryCharacterAndRemainWithinCitationLimit(){var original=source("long","长资料","行文本和数字90071992547409.93。\n".repeat(250));var parts=AnalysisEvidence.parts(original);
        assertTrue(parts.size()>1);assertTrue(parts.stream().allMatch(s->s.text().length()<=2000));assertEquals(original.text(),parts.stream().map(AnalysisEvidence.Source::text).collect(java.util.stream.Collectors.joining()));assertEquals(parts.size(),parts.stream().map(AnalysisEvidence.Source::id).distinct().count());}
    @Test void allowsActualVersionMetadataButDoesNotTreatItAsBodyQuote(){assertDoesNotThrow(()->AnalysisEvidence.validate(answer("依据版本2，销售金额不等于已收款。","s1",SOURCE.text()),List.of(SOURCE)));
        assertThrows(ApiException.class,()->AnalysisEvidence.validate(answer("依据版本3。","s1",SOURCE.text()),List.of(SOURCE)));
        assertThrows(ApiException.class,()->AnalysisEvidence.validate(answer("依据版本2。","s1","2026-09-01生效"),List.of(SOURCE)));}
    @Test void rejectsInventedSource(){assertThrows(ApiException.class,()->AnalysisEvidence.validate(answer("说明","missing",SOURCE.text()),List.of(SOURCE)));}
    @Test void rejectsModifiedQuote(){assertThrows(ApiException.class,()->AnalysisEvidence.validate(answer("说明","s1","销售金额等于已收款"),List.of(SOURCE)));}
    @Test void rejectsUncomputedNumber(){assertThrows(ApiException.class,()->AnalysisEvidence.validate(answer("合计90071992547409.94元。","s1",SOURCE.text()),List.of(SOURCE)));}
    @Test void isoObservedAtSupportsTheSameCalendarDateWithoutBorrowingTimeDigits() {
        var source=datedSource("observed","2026-10-12T15:55:57.409Z","完整结果共119条；金额合计7140.19元。");
        for(String claim:List.of("截至2026-10-12，共119条，金额7140.19元。","截至2026年10月12日，共119条。","观察日期2026/10/12。",
                "观察年月为2026年10月。","观察年份2026年，月日为10月12日。","Observed on 2026-10-12.")) {
            assertDoesNotThrow(()->AnalysisEvidence.validate(answer(claim,source.id(),source.text()),List.of(source)),claim);
            assertEquals(List.of(),AnalysisEvidence.citationHints(draft(claim,source.id()),List.of(source)),claim);
        }
    }
    @Test void completeClockSupportsEquivalentFormatsAndOnlyExistingPrecision() {
        var source=datedSource("clock","2026-10-12T04:05:06.876543210+08:00","当前观察记录。");
        for(String claim:List.of("观察时间2026-10-12T04:05:06.876543210+08:00。","观察时间2026年10月12日04时05分06.876543210秒+08:00。",
                "观察时间2026-10-12 04:05。","时间04:05:06.8765432+08:00。","时间04:05:06。","时间04时05分。"))
            assertDoesNotThrow(()->AnalysisEvidence.validate(answer(claim,source.id(),source.text()),List.of(source)),claim);
        var utc=datedSource("utc","2026-10-12T04:05:06.120Z","当前观察记录。");
        assertDoesNotThrow(()->AnalysisEvidence.resolve(draft("观察时间2026-10-12T04:05:06.1200+00:00。",utc.id()),List.of(utc)));
        assertThrows(ApiException.class,()->AnalysisEvidence.resolve(draft("时间04:05:06.8765431+08:00。",source.id()),List.of(source)));
        var negativeOffset=datedSource("negative-offset","2026-10-12T04:05:06-05:00","当前观察记录。");
        assertDoesNotThrow(()->AnalysisEvidence.resolve(draft("时间04:05:06-05:00。",negativeOffset.id()),List.of(negativeOffset)));
    }
    @Test void punctuationAroundDateAndClockLabelsDoesNotChangeGrounding() {
        var source=datedSource("punctuation","2026-10-12T04:05:06Z","当前观察记录。");
        for(String claim:List.of("观察日期:2026-10-12。","观察日期：2026-10-12。","2026-10-12: 观察结果已完成。",
                "时间:04:05:06Z。","时间：04:05:06Z。","04:05:06Z: 观察结果已完成。")) {
            assertDoesNotThrow(()->AnalysisEvidence.resolve(draft(claim,source.id()),List.of(source)),claim);
            assertEquals(List.of(),AnalysisEvidence.citationHints(draft(claim,source.id()),List.of(source)),claim);
        }
    }
    @Test void minutePrecisionDoesNotInventSecondsOrADate() {
        var source=datedSource("minute","2026-10-12T04:05+08:00","当前观察记录。");
        assertDoesNotThrow(()->AnalysisEvidence.resolve(draft("时间04:05+08:00。",source.id()),List.of(source)));
        for(String claim:List.of("时间04:05:00+08:00。","时间04:05:01+08:00。","观察时间2026-10-12T04:05:00+08:00。"))
            assertThrows(ApiException.class,()->AnalysisEvidence.resolve(draft(claim,source.id()),List.of(source)),claim);
        var date=datedSource("date","2026-10-12","当前观察记录。");
        assertThrows(ApiException.class,()->AnalysisEvidence.resolve(draft("观察时间2026-10-12T04:05。",date.id()),List.of(date)));
    }
    @Test void calendarComponentsCannotBeRearrangedIntoAnotherDate() {
        var source=datedSource("day","2026-10-12T12:10:20Z","另有金额12元和10元。");
        for(String claim:List.of("观察日期2026-12-10。","观察日期2026年10月13日。","观察日期2026-11-12。"))
            assertThrows(ApiException.class,()->AnalysisEvidence.resolve(draft(claim,source.id()),List.of(source)),claim);
    }
    @Test void clockComponentsAndOffsetsCannotBeChangedOrConverted() {
        var source=datedSource("clock","2026-10-12T04:05:06+08:00","当前观察记录。");
        for(String claim:List.of("时间04:06:05+08:00。","时间04:05:06Z。","观察时间2026-10-11T20:05:06Z。","观察时间2026-10-12T04:05:07+08:00。"))
            assertThrows(ApiException.class,()->AnalysisEvidence.resolve(draft(claim,source.id()),List.of(source)),claim);
        var local=datedSource("local","2026-10-12T04:05:06","当前观察记录。");
        assertThrows(ApiException.class,()->AnalysisEvidence.resolve(draft("时间04:05:06Z。",local.id()),List.of(local)));
    }
    @Test void separateSourcesOrMetadataFieldsCannotFabricateACombinedTimestamp() {
        var earlier=datedSource("earlier","2026-10-12T04:05:06Z","原始观察。");var later=datedSource("later","2026-10-13T07:08:09Z","后续观察。");
        var mixed=new AnalysisEvidence.Draft("ANSWERED",List.of(new AnalysisEvidence.DraftClaim("观察时间2026-10-12T07:08:09Z。",List.of(earlier.id(),later.id()))),List.of());
        assertThrows(ApiException.class,()->AnalysisEvidence.resolve(mixed,List.of(earlier,later)));
        assertEquals(List.of(),AnalysisEvidence.citationHints(mixed,List.of(earlier,later)).get(0).get("candidateSourceIds"));
        var fields=datedSource("fields","2026-10-12","04:05:06Z 是另一项维护时间。");
        assertThrows(ApiException.class,()->AnalysisEvidence.resolve(draft("观察时间2026-10-12T04:05:06Z。",fields.id()),List.of(fields)));
    }
    @Test void invalidCalendarOrClockIsNeverMadeValidByRepeatingTheSourceTypo() {
        for(String value:List.of("2026-02-30","2026-13-01","2026-10-12T25:05:06Z","2026-10-12T04:61:06Z",
                "2026-10-12T04:05:06.8765432109Z","2026-10-12T04:05:06+19:00")) {
            var source=datedSource("invalid","当前有效","原文记录"+value+"。");
            var draft=draft("观察时间"+value+"。",source.id());assertThrows(ApiException.class,()->AnalysisEvidence.resolve(draft,List.of(source)),value);
            var hints=AnalysisEvidence.citationHints(draft,List.of(source));assertEquals("INVALID_TEMPORAL",hints.get(0).get("kind"));assertEquals(List.of(),hints.get(0).get("candidateSourceIds"));
        }
    }
    @Test void calendarAndClockComponentsCannotProvideAmountOrCountEvidence() {
        var source=datedSource("date","2026-10-12T04:05:06.12+08:00","当前观察记录。");
        for(String claim:List.of("金额12元。","金额2026元。","数量10条。","金额6.12元。","金额8元。"))
            assertThrows(ApiException.class,()->AnalysisEvidence.resolve(draft(claim,source.id()),List.of(source)),claim);
        var withAmount=datedSource("amount",source.observedAt(),"金额为12.00元。");
        assertDoesNotThrow(()->AnalysisEvidence.resolve(draft("观察日期2026-10-12，金额12元。",withAmount.id()),List.of(withAmount)));
    }
    @Test void signedZeroAndLargeAmountsKeepExactDecimalGrounding() {
        var negative=datedSource("negative","2026-10-12T04:05:06Z","净额-12.50元。");
        assertDoesNotThrow(()->AnalysisEvidence.resolve(draft("净额-12.500元。",negative.id()),List.of(negative)));
        assertThrows(ApiException.class,()->AnalysisEvidence.resolve(draft("净额+12.50元。",negative.id()),List.of(negative)));
        var positive=datedSource("positive",negative.observedAt(),"净额+12.50元。");
        assertDoesNotThrow(()->AnalysisEvidence.resolve(draft("净额12.500元。",positive.id()),List.of(positive)));
        assertThrows(ApiException.class,()->AnalysisEvidence.resolve(draft("净额-12.50元。",positive.id()),List.of(positive)));
        var zero=datedSource("zero",negative.observedAt(),"净额0.00元。");assertDoesNotThrow(()->AnalysisEvidence.resolve(draft("净额0元。",zero.id()),List.of(zero)));
        var precise=datedSource("precise",negative.observedAt(),"净额90071992547409.93元。");
        assertDoesNotThrow(()->AnalysisEvidence.resolve(draft("净额90071992547409.930元。",precise.id()),List.of(precise)));
        assertThrows(ApiException.class,()->AnalysisEvidence.resolve(draft("净额90071992547409.94元。",precise.id()),List.of(precise)));
    }
    @Test void identifiersStillCannotSupplyEmbeddedNumbers() {
        var source=datedSource("ids","当前有效","编号 v12、A13B、record_14、a15b、97bc945393ab4ddb8001e072da63425c。");
        for(String value:List.of("12","13","14","15"))assertThrows(ApiException.class,()->AnalysisEvidence.resolve(draft("金额"+value+"元。",source.id()),List.of(source)));
    }
    @Test void dateHintsChooseTheCompleteDateAndNeverAutoChangeTheCitation() {
        var wrong=datedSource("wrong","2026-12-10T04:05:06Z","当前观察记录。");var correct=datedSource("correct","2026-10-12T04:05:06Z","当前观察记录。");
        var missing=draft("观察日期2026年10月12日。",wrong.id());var sources=List.of(wrong,correct);
        var hints=AnalysisEvidence.citationHints(missing,sources);assertEquals(1,hints.size());assertEquals("DATE",hints.get(0).get("kind"));
        assertEquals("2026-10-12",hints.get(0).get("number"));assertEquals(List.of(correct.id()),hints.get(0).get("candidateSourceIds"));
        assertEquals(List.of(wrong.id()),missing.claims().get(0).sourceIds());assertThrows(ApiException.class,()->AnalysisEvidence.resolve(missing,sources));
        var repaired=draft(missing.claims().get(0).text(),correct.id());assertEquals(List.of(),AnalysisEvidence.citationHints(repaired,sources));assertDoesNotThrow(()->AnalysisEvidence.resolve(repaired,sources));
    }
    @Test void dateInBodyMustRemainWithinTheExactQuotedText() {
        var source=datedSource("body","当前有效","公布日期2026-10-12；金额99.99元。");
        assertDoesNotThrow(()->AnalysisEvidence.validate(answer("公布日期2026年10月12日。",source.id(),source.text()),List.of(source)));
        assertThrows(ApiException.class,()->AnalysisEvidence.validate(answer("公布日期2026年10月12日。",source.id(),"金额99.99元。"),List.of(source)));
        assertThrows(ApiException.class,()->AnalysisEvidence.validate(answer("公布日期2026年10月12日。",source.id(),"公布日期2026年10月12日；金额99.99元。"),List.of(source)));
    }
    @Test void realComparisonCorrectionAcceptsElidedDatesButStillRejectsInventedCountDelta() {
        var sources=comparisonSources();
        // 来自2026-10-13真实comparison第二次ANSWER；只验证引用门禁，独立语义复核和真实重跑由专项验收负责。
        var corrected=new AnalysisEvidence.Draft("ANSWERED",List.of(
                new AnalysisEvidence.DraftClaim("2026年9月1日至14日的前半月查询共64条记录，金额合计3640.64 CNY；页面展示部分明细，可查看完整明细。",List.of("step-1-q1","step-1-q5")),
                new AnalysisEvidence.DraftClaim("2026年9月15日至30日的后半月查询共56条记录，金额合计3500.56 CNY；页面展示部分明细，可查看完整明细。",List.of("step-2-q1","step-2-q5")),
                new AnalysisEvidence.DraftClaim("对比两个查询结果：之前查询64条、之后查询56条，新增56条、移出64条、字段变化0条；后半月减前半月的金额差额为-140.08 CNY。",List.of("step-3-q1","step-3-q5")),
                new AnalysisEvidence.DraftClaim("两次查询条件不同，条件差异只描述两个集合的差异，不能据此断言业务增长或删除；两次观测时间分别为2026-10-12T17:01:10.713Z和2026-10-12T17:01:10.758Z，不是跨查询全局事务。",List.of("step-3-q1","step-3-q2"))),
                List.of("两个查询分别为不同日期范围，结果差异仅反映两个集合的对比，不代表销售趋势、增长或下降，也不推断原因。",
                        "对比的行明细存在截断，页面仅展示部分明细，可查看完整明细；但记录数与金额差额来自完整计数，未截断。"));
        assertDoesNotThrow(()->AnalysisEvidence.resolve(corrected,sources));assertEquals(List.of(),AnalysisEvidence.citationHints(corrected,sources));
        // 首次ANSWER同时自行计算了8条差额；日期修复不能把尚未出现在确定性来源中的计算当作已授权事实。
        var first=new AnalysisEvidence.Draft("ANSWERED",List.of(
                new AnalysisEvidence.DraftClaim("2026年9月1日至14日的前半月查询共64条记录，金额合计3640.64 CNY。",List.of("step-1-q1","step-1-q5")),
                new AnalysisEvidence.DraftClaim("2026年9月15日至30日的后半月查询共56条记录，金额合计3500.56 CNY。",List.of("step-2-q1","step-2-q5")),
                new AnalysisEvidence.DraftClaim("对比两个查询结果：后半月相对前半月减少8条记录，金额差额为后半月减前半月 -140.08 CNY。",List.of("step-3-q1")),
                new AnalysisEvidence.DraftClaim("两次查询条件不同，条件差异只描述两个集合的差异，不能据此推断业务增长或删除；查询结果均来自各自观测时间点的当前记录。",List.of("step-3-q1","step-3-q2"))),List.of());
        var failure=assertThrows(ApiException.class,()->AnalysisEvidence.resolve(first,sources));assertTrue(failure.getMessage().contains("claims[2]的数字8"),failure.getMessage());
    }
    @Test void localCalendarRangesSupportChineseConnectorsAndHyphens() {
        var source=datedSource("range","当前有效","查询从2026-09-01至2026-09-14。");
        for(String claim:List.of("查询2026年9月1日至14日。","查询2026年9月1日到14日。","查询2026-09-01 - 14日。",
                "查询2026/09/01～14日。","查询9月1日至14日。","查询9月1日-14日。","查询9月1日—14日。")) {
            assertDoesNotThrow(()->AnalysisEvidence.resolve(draft(claim,source.id()),List.of(source)),claim);
            assertEquals(List.of(),AnalysisEvidence.citationHints(draft(claim,source.id()),List.of(source)),claim);
        }
    }
    @Test void explicitRangeEndpointsPreserveTheirOwnYearsAndNeverFallBackToNumbers() {
        var source=datedSource("explicit-range","当前有效","日期为2026-09-01、2026-09-14、2026-12-28、2027-01-03；月份为2026-09、2026-10、2026-12、2027-01。");
        for(String claim:List.of("查询2026年9月1日-2026年9月14日。","查询2026-09-01-2026-09-14。","查询2026/9/1–2026/9/14。",
                "查询2026年12月28日-2027年1月3日。","查询2026年9月-2026年10月。","查询2026-09-2026-10。","查询2026年12月-2027年1月。")) {
            assertDoesNotThrow(()->AnalysisEvidence.resolve(draft(claim,source.id()),List.of(source)),claim);
            assertEquals(List.of(),AnalysisEvidence.citationHints(draft(claim,source.id()),List.of(source)),claim);
        }
        for(String claim:List.of("查询2026年9月1日-2026年9月31日。","查询2026-09-01-2026-09-31。",
                "查询2026年9月-2026年13月。","查询2026-09-2026-13。","查询2026-09-14-2026-09-01。","查询2026-10-2026-09。")) {
            var missing=draft(claim,source.id());assertThrows(ApiException.class,()->AnalysisEvidence.resolve(missing,List.of(source)),claim);
            assertEquals("INVALID_TEMPORAL",AnalysisEvidence.citationHints(missing,List.of(source)).get(0).get("kind"),claim);
        }
        var rangeSource=datedSource("range-source","当前有效","查询2026-09-01-2026-09-14。");
        assertDoesNotThrow(()->AnalysisEvidence.resolve(draft("末端为2026年9月14日。",rangeSource.id()),List.of(rangeSource)));
    }
    @Test void elidedDateEndKeepsItsMonthAndYearInValidationAndHints() {
        var wrong=datedSource("wrong-range","当前有效","查询涉及2026-09-01、2026-10-14、2025-09-14。");
        var correct=datedSource("correct-range","当前有效","查询末端为2026-09-14。");var claim=draft("查询2026年9月1日至14日。",wrong.id());
        assertThrows(ApiException.class,()->AnalysisEvidence.resolve(claim,List.of(wrong,correct)));
        var hints=AnalysisEvidence.citationHints(claim,List.of(wrong,correct));assertEquals(1,hints.size());assertEquals("DATE",hints.get(0).get("kind"));
        assertEquals("2026-09-14",hints.get(0).get("number"));assertEquals(List.of(correct.id()),hints.get(0).get("candidateSourceIds"));
        var wrongMonth=datedSource("wrong-month","当前有效","查询涉及2026-09-01、2026-10-14。");var noYear=draft("查询9月1日至14日。",wrongMonth.id());
        assertThrows(ApiException.class,()->AnalysisEvidence.resolve(noYear,List.of(wrongMonth)));
        var noYearHint=AnalysisEvidence.citationHints(noYear,List.of(wrongMonth,correct)).get(0);assertEquals("MONTH_DAY",noYearHint.get("kind"));
        assertEquals("--09-14",noYearHint.get("number"));assertEquals(List.of(correct.id()),noYearHint.get("candidateSourceIds"));
    }
    @Test void elidedMonthEndKeepsTheExplicitYearAndDoesNotRollForward() {
        var source=datedSource("months","当前有效","查询月份为2026-09、2026-10、2026-12、2027-01。");
        for(String claim:List.of("查询2026年9月至10月。","查询2026-09到10月。","查询2026年9月-10月。","查询2026年12月至2027年1月。"))
            assertDoesNotThrow(()->AnalysisEvidence.resolve(draft(claim,source.id()),List.of(source)),claim);
        for(String claim:List.of("查询2026年12月至1月。","查询2026年9月至13月。"))assertThrows(ApiException.class,()->AnalysisEvidence.resolve(draft(claim,source.id()),List.of(source)),claim);
        var otherYear=datedSource("other-year","当前有效","查询月份为2026-09、2025-10。");
        var missing=draft("查询2026年9月至10月。",otherYear.id());assertThrows(ApiException.class,()->AnalysisEvidence.resolve(missing,List.of(otherYear)));
        var hint=AnalysisEvidence.citationHints(missing,List.of(otherYear,source)).get(0);assertEquals("MONTH",hint.get("kind"));assertEquals("2026-10",hint.get("number"));
        assertEquals(List.of(source.id()),hint.get("candidateSourceIds"));
    }
    @Test void elidedRangesRejectInvalidOrImplicitlyRolledCalendarEnds() {
        var source=datedSource("calendar","当前有效","日期为2026-09-01、2026-09-28、2026-10-03、2026-12-28、2027-01-03、2026-02-01、2024-02-01、2024-02-29。");
        for(String claim:List.of("查询2026年9月28日至3日。","查询9月28日至3日。","查询2026年9月1日至31日。",
                "查询2026年2月1日至30日。","查询2026年12月28日至1月3日。","查询2月1日至30日。")) {
            var draft=draft(claim,source.id());assertThrows(ApiException.class,()->AnalysisEvidence.resolve(draft,List.of(source)),claim);
            assertEquals("INVALID_TEMPORAL",AnalysisEvidence.citationHints(draft,List.of(source)).get(0).get("kind"));
        }
        for(String claim:List.of("查询2026年9月28日至10月3日。","查询9月28日至10月3日。","查询2026年12月28日至2027年1月3日。","查询2024年2月1日至29日。"))
            assertDoesNotThrow(()->AnalysisEvidence.resolve(draft(claim,source.id()),List.of(source)),claim);
    }
    @Test void calendarRangesNeverSupplyAmountCountOrDurationNumbers() {
        for(String range:List.of("查询从2026年9月1日至14日。","查询2026-09-01-2026-09-14。","查询2026年9月-2026年10月。")) {
            var source=datedSource("range","当前有效",range);
            for(String claim:List.of("金额14元。","金额2026元。","数量14条。","期限14天。","持续14日。","14日内完成。","持续9个月。",
                    "查询2026年9月1日至14日，共14天。","查询2026年9月1日至14。"))
                assertThrows(ApiException.class,()->AnalysisEvidence.resolve(draft(claim,source.id()),List.of(source)),claim);
        }
        var duration=datedSource("duration","当前有效","实际耗时14天。");assertDoesNotThrow(()->AnalysisEvidence.resolve(draft("实际耗时14天。",duration.id()),List.of(duration)));
    }
    @Test void rangeContextNeverCrossesFieldsStatementsOrOtherSources() {
        var fields=datedSource("fields","2026年9月1日","至14日。");
        assertThrows(ApiException.class,()->AnalysisEvidence.resolve(draft("查询2026年9月1日至14日。",fields.id()),List.of(fields)));
        var noYear=datedSource("no-year","2026-10-13T00:00:00Z","查询9月1日至14日。");
        assertThrows(ApiException.class,()->AnalysisEvidence.resolve(draft("查询2026年9月1日至14日。",noYear.id()),List.of(noYear)));
        var source=datedSource("range","当前有效","查询2026-09-01至2026-09-14。");
        assertThrows(ApiException.class,()->AnalysisEvidence.resolve(draft("查询2026年9月1日；至14日。",source.id()),List.of(source)));
        assertThrows(ApiException.class,()->AnalysisEvidence.resolve(draft("查询2026年9月1日\n至14日。",source.id()),List.of(source)));
        var startHasNoYear=datedSource("no-start-year","当前有效","查询9月1日-2026年9月14日。");
        assertDoesNotThrow(()->AnalysisEvidence.resolve(draft("查询9月1日-2026年9月14日。",startHasNoYear.id()),List.of(startHasNoYear)));
        assertThrows(ApiException.class,()->AnalysisEvidence.resolve(draft("查询2026年9月1日至14日。",startHasNoYear.id()),List.of(startHasNoYear)));
        // 区间不能吞掉完整时间戳的日期部分，再借另一日期的时钟拼出原文没有的末端时点。
        var clockElsewhere=datedSource("other-clock","2026-10-13T12:34:56Z",source.text());
        var timestamp=draft("查询2026年9月1日至2026年9月14日 12:34:56Z。",clockElsewhere.id());
        assertThrows(ApiException.class,()->AnalysisEvidence.resolve(timestamp,List.of(clockElsewhere)));
        var timestampHint=AnalysisEvidence.citationHints(timestamp,List.of(clockElsewhere)).get(0);
        assertEquals("DATETIME",timestampHint.get("kind"));assertEquals("2026-09-14T12:34:56Z",timestampHint.get("number"));
    }
    @Test void anElidedSourceRangeSupportsExactEndpointsButNeverRewrittenQuotes() {
        var source=datedSource("range","当前有效","查询2026年9月1日至14日；金额99.99元。");
        assertDoesNotThrow(()->AnalysisEvidence.validate(answer("末端为2026年9月14日，金额99.99元。",source.id(),source.text()),List.of(source)));
        assertThrows(ApiException.class,()->AnalysisEvidence.validate(answer("末端为2026年9月14日。",source.id(),"金额99.99元。"),List.of(source)));
        assertThrows(ApiException.class,()->AnalysisEvidence.validate(answer("末端为2026年9月14日。",source.id(),"查询2026年9月1日至2026年9月14日；金额99.99元。"),List.of(source)));
    }
    @Test void fullAndSelectedSetsNeedTheirOwnNumericalCitations(){
        var full=source("full","查询全集","全部5条记录合计30.15元。");var selected=source("selected","排名选集","选中2条记录合计20.10元。");
        var supported=new AnalysisEvidence.Draft("ANSWERED",List.of(new AnalysisEvidence.DraftClaim("全部5条合计30.15元；选中2条合计20.10元。",List.of("full","selected"))),List.of());
        assertDoesNotThrow(()->AnalysisEvidence.resolve(supported,List.of(full,selected)));
        var missing=new AnalysisEvidence.Draft("ANSWERED",List.of(new AnalysisEvidence.DraftClaim("全部5条合计30.15元；选中2条合计20.10元。",List.of("selected"))),List.of());
        assertThrows(ApiException.class,()->AnalysisEvidence.resolve(missing,List.of(full,selected)));
        var hints=AnalysisEvidence.citationHints(missing,List.of(full,selected));assertEquals(2,hints.size());
        assertTrue(hints.stream().allMatch(h->h.get("candidateSourceIds").equals(List.of("full"))));
        // 提示没有修改原草稿，更不等于已经引用或通过验证。
        assertEquals(List.of("selected"),missing.claims().get(0).sourceIds());assertThrows(ApiException.class,()->AnalysisEvidence.resolve(missing,List.of(full,selected)));
    }
    @Test void missingNumberHintNeverFabricatesASource(){var draft=new AnalysisEvidence.Draft("ANSWERED",List.of(new AnalysisEvidence.DraftClaim("金额为999.99元。",List.of("s1"))),List.of());
        var hints=AnalysisEvidence.citationHints(draft,List.of(SOURCE));assertEquals(List.of(),hints.get(0).get("candidateSourceIds"));}
    @Test void rejectsUnsupportedDefinitiveAnswer(){assertThrows(ApiException.class,()->AnalysisEvidence.validate(new AnalysisEvidence.Answer("ANSWERED",List.of(),List.of()),List.of()));}
    @Test void allowsExplicitInsufficiency(){assertDoesNotThrow(()->AnalysisEvidence.validate(new AnalysisEvidence.Answer("INSUFFICIENT",List.of(),List.of("没有出差制度依据")),List.of()));}
    @Test void conflictNeedsAnExplanation(){assertThrows(ApiException.class,()->AnalysisEvidence.validate(new AnalysisEvidence.Answer("CONFLICTING",List.of(),List.of()),List.of(SOURCE)));}
    @Test void retrievalHasNoUnrelatedDefault(){assertTrue(KnowledgeSearch.search("火星轨道",List.of(SOURCE),com.example.report.assistant.SemanticDictionary.Definition.empty(),12).sources().isEmpty());}
    @Test void retrievalFindsBusinessDefinition(){assertEquals("s1",KnowledgeSearch.search("销售金额指什么",List.of(SOURCE,source("s2","报销","出差酒店标准")),com.example.report.assistant.SemanticDictionary.Definition.empty(),12).sources().get(0).id());}
    @Test void retrievalMakesTruncationExplicit(){var sources=new ArrayList<AnalysisEvidence.Source>();for(int i=0;i<15;i++)sources.add(source("s"+i,"销售口径","销售金额定义"));var result=KnowledgeSearch.search("销售金额",sources,com.example.report.assistant.SemanticDictionary.Definition.empty(),12);assertEquals(15,result.matched());assertEquals(12,result.sources().size());assertTrue(result.truncated());}
    @Test void knowledgeCannotFetchCredentialUrls(){assertThrows(ApiException.class,()->new KnowledgeService.Content("A","rpt-sales-order","口径","正文","手册","https://user:fixture@example.com/manual","2026-09-01",null));}
    @Test void knowledgeRequiresValidExclusiveEndDate(){assertThrows(ApiException.class,()->new KnowledgeService.Content("A","rpt-sales-order","口径","正文","手册",null,"2026-09-01","2026-09-01"));}
    @Test void knowledgeEffectivenessIsBounded(){var content=new KnowledgeService.Content("A","rpt-sales-order","口径","正文","手册",null,"2026-09-01","2026-10-01");assertTrue(content.effective(java.time.LocalDate.parse("2026-09-01")));assertFalse(content.effective(java.time.LocalDate.parse("2026-10-01")));}
    /** 时间回归使用无独立数字的元数据，避免标题或版本中的巧合数字掩盖引用缺口。 */
    private static AnalysisEvidence.Source datedSource(String id,String observedAt,String text) {return new AnalysisEvidence.Source(id,id,"DATA","观察结果","v1","A / rpt-sales-order",observedAt,text,0);}
    private static AnalysisEvidence.Draft draft(String text,String sourceId) {return new AnalysisEvidence.Draft("ANSWERED",List.of(new AnalysisEvidence.DraftClaim(text,List.of(sourceId))),List.of());}
    /** 保留真实comparison所选来源的日期端点、计数、差额及观测时刻；不引入模型自行算出的8条差额。 */
    private static List<AnalysisEvidence.Source> comparisonSources() {
        String before="2026-10-12T17:01:10.713Z",after="2026-10-12T17:01:10.758Z",comparison="2026-10-12T17:01:04.598390900Z";
        return List.of(datedSource("step-1-q1",before,"查询销售日期GTE 2026-09-01、LTE 2026-09-14；完整结果共64条记录；金额合计3640.64 CNY。"),
                datedSource("step-1-q5",before,"完整计数64条，金额3640.64 CNY。"),datedSource("step-2-q1",after,"查询销售日期GTE 2026-09-15、LTE 2026-09-30；完整结果共56条记录；金额合计3500.56 CNY。"),
                datedSource("step-2-q5",after,"完整计数56条，金额3500.56 CNY。"),datedSource("step-3-q1",comparison,"之前查询共64条；之后查询共56条。新增56条，移出64条，字段变化0条。之后减之前的金额差额：-140.08 CNY。之前观测时间："+before+"；之后观测时间："+after+"；不是跨查询全局事务。"),
                datedSource("step-3-q2",comparison,"之后查询日期GTE 2026-09-15、LTE 2026-09-30；之后观测时间："+after+"。"),datedSource("step-3-q5",comparison,"行明细预览存在截断；完整金额差额-140.08 CNY。"));
    }
}
