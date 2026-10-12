package com.example.report.analysis;

import com.example.report.common.ApiException;
import java.util.*;

/**
 * 对已经脱敏的知识正文确定性分块，优先保留段落、句子和换行边界，并以有限重叠保留相邻条件。
 * 所有片段都是输入的连续原文，不裁剪空白、不合成标题或摘要；授权、生效版本和总片段预算由调用方负责。
 */
public final class KnowledgeChunks {
    /** 分块规则的执行版本，调用方须纳入知识或运行指纹，不能静默改变旧任务的引用边界。 */
    public static final String VERSION = "paragraph-sentence-overlap-v1";
    private static final int MAX_OVERLAP = 160;

    private KnowledgeChunks() { }

    /**
     * 一个带来源位置的原文片段；位置均指向调用本组件时的同一份已脱敏输入。
     * @param index 从1开始的稳定片段序号
     * @param start 从0开始的Java UTF-16起点，包含该位置
     * @param end 从0开始的Java UTF-16终点，不包含该位置
     * @param startLine 起点所在行，从1开始；CRLF视为一次换行
     * @param endLine 最后一个所含字符所在行，从1开始；末尾换行符仍归属其原行
     * @param text 输入中[start,end)的完整连续原文，保留所有空白和换行
     */
    public record Chunk(int index,int start,int end,int startLine,int endLine,String text) {
        public Chunk {
            if(index<1||start<0||end<=start||startLine<1||endLine<startLine||text==null||end-start!=text.length())
                throw new ApiException(422,"知识片段位置无效");
        }
    }

    /**
     * 按段落、句子和行边界顺序寻找接近预算的切点；超长句直接切开但不拆代理对或CRLF。
     * 相邻片段最多重叠160个UTF-16字符，小预算按八分之一缩减；重叠只复制实际原文，不保证覆盖整份制度的所有上下文。
     * 无数据库、模型、权限或其他副作用；相同输入和预算得到相同结果，空字符串返回空集合。
     * @param text 已按出站与展示规则脱敏的正文，不得为null，允许空字符串及纯空白
     * @param maxChars 单片原文的UTF-16字符上限，至少为2；调用方须先扣除引用头部预算
     * @return 按原文顺序排列的不可变片段列表，首片从0开始、末片到输入末尾，重叠之外没有遗漏
     * @throws ApiException 正文为null或预算小于2时返回参数错误
     */
    public static List<Chunk> split(String text,int maxChars) {
        if(text==null||maxChars<2)throw new ApiException(422,"知识分块正文或字符预算无效");
        if(text.isEmpty())return List.of();
        var lines=lineStarts(text);var result=new ArrayList<Chunk>();int start=0;
        while(start<text.length()) {
            // 先比较剩余长度，避免用户预算过大时start+maxChars发生整数溢出。
            int hardEnd=maxChars>=text.length()-start?text.length():start+maxChars;
            hardEnd=boundaryBefore(text,hardEnd);
            int end=hardEnd==text.length()?hardEnd:preferredEnd(text,start,hardEnd);
            result.add(new Chunk(result.size()+1,start,end,lineAt(lines,start),lineAt(lines,end-1),text.substring(start,end)));
            if(end==text.length())break;
            int overlap=Math.min(MAX_OVERLAP,maxChars/8);
            // 向后复制的原文仍受单片预算约束；起点只向前对齐，不能为了完整代理对而超过重叠上限。
            start=boundaryAfter(text,end-overlap);
        }
        return List.copyOf(result);
    }

    /** 只在当前预算末四分之一中寻找软边界，避免密集短段落产生过多碎片或让重叠阻止进度。 */
    private static int preferredEnd(String text,int start,int hardEnd) {
        int span=hardEnd-start,lower=start+span-span/4;
        int paragraph=-1,sentence=-1,line=-1;
        for(int at=hardEnd;at>=lower;at--) {
            if(!isBoundary(text,at))continue;
            if(line<0&&isLineEnd(text,at))line=at;
            if(paragraph<0&&isParagraphEnd(text,at))paragraph=at;
            if(sentence<0&&isSentenceEnd(text,at))sentence=at;
        }
        return paragraph>=0?paragraph:sentence>=0?sentence:line>=0?line:hardEnd;
    }

    /** 空行及其水平空白构成段落分隔；普通单换行作为次级边界保留。 */
    private static boolean isParagraphEnd(String text,int at) {
        if(!isLineEnd(text,at))return false;
        int before=at-1;if(text.charAt(before)=='\n'&&before>0&&text.charAt(before-1)=='\r')before--;
        for(before--;before>=0;before--) {
            char value=text.charAt(before);
            if(value=='\r'||value=='\n')return true;
            if(value!=' '&&value!='\t')return false;
        }
        return false;
    }

    /** 句末可带中文或英文闭引号；英文点号仅在词界处生效，避免把小数、缩写或域名中间当作句末。 */
    private static boolean isSentenceEnd(String text,int at) {
        // 一组闭引号只在末尾回溯一次；长串引号不能让每个候选边界重复扫描整段正文。
        if(at<text.length()&&"\"'”’）)]}」』".indexOf(text.charAt(at))>=0)return false;
        int before=at-1;
        while(before>=0&&"\"'”’）)]}」』".indexOf(text.charAt(before))>=0)before--;
        if(before<0)return false;
        char value=text.charAt(before);
        if("。！？!?；;".indexOf(value)>=0)return true;
        return value=='.'&&(at==text.length()||Character.isWhitespace(text.charAt(at)));
    }

    private static boolean isLineEnd(String text,int at) {
        return at>0&&(text.charAt(at-1)=='\n'||text.charAt(at-1)=='\r'&&(at==text.length()||text.charAt(at)!='\n'));
    }

    /** 原文位置不落在合法UTF-16代理对或CRLF的中间；孤立代理字符仍按原文保留。 */
    private static boolean isBoundary(String text,int at) {
        return at<=0||at>=text.length()||!(Character.isHighSurrogate(text.charAt(at-1))&&Character.isLowSurrogate(text.charAt(at)))
                &&!(text.charAt(at-1)=='\r'&&text.charAt(at)=='\n');
    }

    private static int boundaryBefore(String text,int at){return isBoundary(text,at)?at:at-1;}
    private static int boundaryAfter(String text,int at){return isBoundary(text,at)?at:at+1;}

    /** 行号从原始输入一次建立，重叠部分仍使用同一坐标，不按每个片段重新计数。 */
    private static List<Integer> lineStarts(String text) {
        var result=new ArrayList<Integer>();result.add(0);
        for(int at=0;at<text.length();at++) {
            char value=text.charAt(at);
            if(value=='\r'){if(at+1<text.length()&&text.charAt(at+1)=='\n')at++;result.add(at+1);}
            else if(value=='\n')result.add(at+1);
        }
        return result;
    }

    private static int lineAt(List<Integer> lines,int offset) {
        int position=Collections.binarySearch(lines,offset);return position>=0?position+1:-position-1;
    }
}
