package com.example.report.operations;

/**
 * 跨 SSE 文本分片暂存可能的邮箱或号码，识别完整片段后脱敏再输出。
 * 暂存上限512字符，流结束必须 flush，避免分片边界绕过脱敏或丢失尾段。
 */
public final class SensitiveTextStream {
    private final StringBuilder token=new StringBuilder();
    private boolean suppressed;
    public String feed(String chunk) {
        StringBuilder out=new StringBuilder();
        for(char c:chunk.toCharArray()) {
            if(c<128 && (Character.isLetterOrDigit(c)||"._%+-@".indexOf(c)>=0)) {
                if(!suppressed) {
                    token.append(c);
                    if(token.length()>512) { token.setLength(0);out.append("[长文本已脱敏]");suppressed=true; }
                }
            } else { out.append(flush());out.append(c); }
        }
        return out.toString();
    }
    public String flush() {
        String result=SensitiveData.text(token.toString());token.setLength(0);suppressed=false;return result;
    }
}
