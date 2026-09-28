package com.example.report.operations;

/** Hold a potential email/number token across SSE chunks, bounded to 512 characters. */
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
