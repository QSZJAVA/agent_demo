package com.example.report.analysis;

import com.example.report.common.ApiException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 独立核对原文区间、完整覆盖、自然边界与重叠，不以分块实现本身推断正确结果。 */
class KnowledgeChunksTest {
    @Test void emptyAndWhitespaceArePreservedWithoutInventedContent() {
        assertEquals(List.of(),KnowledgeChunks.split("",1800));
        String text=" \t\r\n\n  ";var chunks=KnowledgeChunks.split(text,1800);
        assertEquals(1,chunks.size());assertEquals(text,chunks.get(0).text());
        assertEquals(1,chunks.get(0).startLine());assertEquals(3,chunks.get(0).endLine());
    }

    @Test void rejectsNullAndBudgetsThatCannotContainOneUnicodeCodePoint() {
        assertThrows(ApiException.class,()->KnowledgeChunks.split(null,1800));
        for(int budget:new int[]{Integer.MIN_VALUE,-1,0,1})assertThrows(ApiException.class,()->KnowledgeChunks.split("正文",budget));
    }

    @Test void paragraphBoundaryKeepsBothSeparatorsAndDoesNotTrimFollowingText() {
        String paragraph="甲".repeat(83)+"\r\n \t\r\n";String text=paragraph+"  乙".repeat(70);
        var chunks=KnowledgeChunks.split(text,100);
        assertEquals(paragraph.length(),chunks.get(0).end());assertTrue(chunks.get(0).text().endsWith("\r\n \t\r\n"));
        assertCoverage(text,chunks,100);
    }

    @Test void sentenceBoundaryIncludesItsClosingQuote() {
        String sentence="甲".repeat(84)+"规定。\"";String text=sentence+"乙".repeat(140);
        var chunks=KnowledgeChunks.split(text,100);
        assertEquals(sentence.length(),chunks.get(0).end());assertCoverage(text,chunks,100);
    }

    @Test void decimalAndIdentifierDotsDoNotSplitASentence() {
        String text="甲".repeat(79)+"123.45 tax.example.org "+"乙".repeat(100);
        var chunks=KnowledgeChunks.split(text,100);
        assertEquals(100,chunks.get(0).end());assertCoverage(text,chunks,100);
    }

    @Test void conditionsAndTheirFollowingExceptionShareActualOverlappingContext() {
        String condition="仅限完成事前申请的员工适用。";
        String exception="但是未提供实际住宿凭证的，不适用上述补助。";
        String text="制度正文".repeat(28)+condition+exception+"后续细则".repeat(30);
        int budget=("制度正文".repeat(28)+condition).length();var chunks=KnowledgeChunks.split(text,budget);
        assertEquals(budget,chunks.get(0).end());
        assertTrue(chunks.get(1).text().contains(condition+exception));assertCoverage(text,chunks,budget);
    }

    @Test void longSentenceCutsDeterministicallyWithAtMost160CharactersOfOverlap() {
        String text="规".repeat(12000);var chunks=KnowledgeChunks.split(text,1800);
        assertEquals(8,chunks.size());
        for(int index=1;index<chunks.size();index++)assertEquals(160,chunks.get(index-1).end()-chunks.get(index).start());
        assertCoverage(text,chunks,1800);assertEquals(chunks,KnowledgeChunks.split(text,1800));
        assertThrows(UnsupportedOperationException.class,()->chunks.add(chunks.get(0)));
    }

    @Test void allDocumentBudgetFitsTheScopeAtRealisticCitationSizes() {
        String text=("正文条例".repeat(23)+"。\r\n\r\n").repeat(130).substring(0,12000);
        var chunks=KnowledgeChunks.split(text,1400);
        assertTrue(chunks.size()*100<1600,"100份最大长度资料须为目录、词典与规则留出片段预算");
        assertCoverage(text,chunks,1400);
    }

    @Test void crlfAndEmojiNeverSplitAndCoordinatesUseOriginalLines() {
        String text="第一行😀\r\n第二行😃\n第三行\r第四行😀\r\n".repeat(10);
        for(int budget:new int[]{2,3,7,9,16,33,100})assertCoverage(text,KnowledgeChunks.split(text,budget),budget);
        var shortLines=KnowledgeChunks.split("a\r\nb\r\nc",3);
        assertEquals(new KnowledgeChunks.Chunk(1,0,3,1,1,"a\r\n"),shortLines.get(0));
        assertEquals(new KnowledgeChunks.Chunk(2,3,6,2,2,"b\r\n"),shortLines.get(1));
        assertEquals(new KnowledgeChunks.Chunk(3,6,7,3,3,"c"),shortLines.get(2));
    }

    @Test void overlapStartsCannotSplitSurrogatePairsOrCrlf() {
        for(String pair:List.of("😀","\r\n")) {
            String text="甲".repeat(157)+pair+"乙".repeat(20)+"。"+"丙".repeat(100);
            var chunks=KnowledgeChunks.split(text,180);
            assertEquals(180,chunks.get(0).end());
            assertEquals(159,chunks.get(1).start());assertCoverage(text,chunks,180);
        }
    }

    @Test void hugeCallerBudgetCannotOverflowOffsets() {
        String text="正文😀\r\n 完整保留 ";var chunks=KnowledgeChunks.split(text,Integer.MAX_VALUE);
        assertEquals(1,chunks.size());assertCoverage(text,chunks,Integer.MAX_VALUE);
    }

    /** 根据原文逐字符扫描行号并去除区间重叠后重建，覆盖边界算法不调用生产代码的内部工具。 */
    private static void assertCoverage(String text,List<KnowledgeChunks.Chunk> chunks,int budget) {
        int covered=0;var rebuilt=new StringBuilder();KnowledgeChunks.Chunk previous=null;
        for(int index=0;index<chunks.size();index++) {
            var chunk=chunks.get(index);assertEquals(index+1,chunk.index());
            assertTrue(chunk.start()<=covered);assertTrue(chunk.end()>covered);assertTrue(chunk.text().length()<=budget);
            assertEquals(text.substring(chunk.start(),chunk.end()),chunk.text());
            assertEquals(lineAt(text,chunk.start()),chunk.startLine());assertEquals(lineAt(text,chunk.end()-1),chunk.endLine());
            for(int position:new int[]{chunk.start(),chunk.end()})if(position>0&&position<text.length()) {
                assertFalse(Character.isHighSurrogate(text.charAt(position-1))&&Character.isLowSurrogate(text.charAt(position)));
                assertFalse(text.charAt(position-1)=='\r'&&text.charAt(position)=='\n');
            }
            if(previous==null)assertEquals(0,chunk.start());
            else {assertTrue(chunk.start()>previous.start());assertTrue(previous.end()-chunk.start()<=160);}
            rebuilt.append(chunk.text().substring(covered-chunk.start()));covered=chunk.end();previous=chunk;
        }
        assertEquals(text.length(),covered);assertEquals(text,rebuilt.toString());
    }

    private static int lineAt(String text,int position) {
        int line=1;
        for(int at=0;at<position;at++) {
            char value=text.charAt(at);
            if(value=='\n')line++;
            else if(value=='\r'&&(at+1>=text.length()||text.charAt(at+1)!='\n'))line++;
        }
        return line;
    }
}
