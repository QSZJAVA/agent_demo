package com.example.report.analysis;

import com.example.report.common.ApiException;
import com.example.report.common.JsonUtil;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 真实模型HTTP响应的共同协议边界：先限制完整正文，成功响应再检查SDK必需结构，然后把原字节交给SDK。
 * SDK对空响应或缺失choices会输出整个Prompt，因此畸形响应必须在进入SDK前拒绝，不能仅检查诊断前缀。
 */
final class AnalysisModelResponseGuard {
    static final int MAX_RESPONSE_BYTES=512*1024;
    static final String PROTOCOL_VERSION="openai-chat-response-v1";
    private static final List<String> NUMERIC_RATE_HEADERS=List.of("x-ratelimit-limit-requests","x-ratelimit-remaining-requests",
            "x-ratelimit-limit-tokens","x-ratelimit-remaining-tokens");

    /** 固定协议结果供诊断使用，禁止把自由异常消息或响应值放入日志标签。 */
    enum Outcome { NOT_CHECKED,HTTP_ERROR,UNEXPECTED_STATUS,INVALID_MEDIA,OVER_BUDGET,READ_FAILED,INVALID_JSON,INVALID_SHAPE,INVALID_HEADERS,VALID }

    private AnalysisModelResponseGuard() { }

    /**
     * 完整校验SDK会当作成功处理的响应；错误HTTP保持原错误路径，任何正文均不得超过硬预算。
     * @param response 已由诊断层回放前缀的响应，状态、头和关闭责任不变
     * @param diagnostics 只接受固定结果标签，不接收完整响应正文
     * @return 正文逐字回放的响应；合法JSON及原生token用量保留
     * @throws IOException 原始正文读取失败，保持传输错误
     * @throws ApiException 媒体类型、体积或结构不符合当前协议时安全拒绝，不含上游自由文本
     */
    static ClientHttpResponse inspect(ClientHttpResponse response,AnalysisModelDiagnostics diagnostics) throws IOException {
        var status=response.getStatusCode();
        boolean httpError=status.isError();
        if(!httpError&&!status.is2xxSuccessful())throw reject(diagnostics,Outcome.UNEXPECTED_STATUS,"真实模型接口返回非成功HTTP "+status.value()+"，本轮调用未完成");
        if(status.value()==204)throw reject(diagnostics,Outcome.INVALID_SHAPE,"真实模型接口返回无正文的HTTP 204，本轮调用未完成");
        if(!httpError&&!diagnostics.jsonMedia())throw reject(diagnostics,Outcome.INVALID_MEDIA,diagnostics.mediaFailureMessage());
        // SDK解析可选限额数字头失败时会输出原始值；在相同解析边界提前拒绝，不能依赖全局禁日志掩盖泄露。
        if(!httpError)for(String name:NUMERIC_RATE_HEADERS) {
            String value=response.getHeaders().getFirst(name);
            if(value!=null&&!value.isBlank())try{Long.parseLong(value.trim());}
            catch(NumberFormatException invalid){throw reject(diagnostics,Outcome.INVALID_HEADERS,"真实模型接口的限额响应头不符合协议，本轮调用未完成");}
        }
        long declaredLength=-1;
        try{declaredLength=response.getHeaders().getContentLength();}catch(RuntimeException invalidLength){ }
        // RestClient即使拿到正文，也会把204或声明长度为0的响应当作空体；必须在SDK打印Prompt前拒绝。
        if(!httpError&&declaredLength==0)throw reject(diagnostics,Outcome.INVALID_SHAPE,"真实模型接口返回的Chat Completions响应结构不完整，本轮调用未完成");
        if(declaredLength>MAX_RESPONSE_BYTES)throw overBudget(diagnostics);
        byte[] bytes;
        try {
            var input=response.getBody();var output=new ByteArrayOutputStream();byte[] buffer=new byte[8192];boolean complete=false;
            while(output.size()<MAX_RESPONSE_BYTES) {
                int count=input.read(buffer,0,Math.min(buffer.length,MAX_RESPONSE_BYTES-output.size()));
                if(count<0){complete=true;break;}
                if(count==0){int single=input.read();if(single<0){complete=true;break;}output.write(single);}
                else output.write(buffer,0,count);
            }
            // 未声明长度且达到硬上限时无法证明正文完整，按预算失败处理，不读取第524289字节探测。
            if(!complete&&declaredLength!=output.size())throw overBudget(diagnostics);
            bytes=output.toByteArray();
        } catch(IOException unavailable){diagnostics.protocol(Outcome.READ_FAILED);throw unavailable;}
        // SDK的错误处理器也会完整读取正文；统一限制错误响应，避免诊断有界但后续错误处理无界。
        if(httpError){diagnostics.protocol(Outcome.HTTP_ERROR);return new CheckedResponse(response,new ByteArrayInputStream(bytes));}
        com.fasterxml.jackson.databind.JsonNode root;
        try{root=JsonUtil.strictMapper().readTree(bytes);}
        catch(IOException|RuntimeException invalid){throw reject(diagnostics,Outcome.INVALID_JSON,"真实模型接口响应正文不符合JSON协议，本轮调用未完成");}
        if(root==null||!root.isObject()||!root.path("choices").isArray()||root.path("choices").isEmpty())
            throw reject(diagnostics,Outcome.INVALID_SHAPE,"真实模型接口返回的Chat Completions响应结构不完整，本轮调用未完成");
        diagnostics.protocol(Outcome.VALID);
        return new CheckedResponse(response,new ByteArrayInputStream(bytes));
    }

    private static ApiException overBudget(AnalysisModelDiagnostics diagnostics) {
        return reject(diagnostics,Outcome.OVER_BUDGET,"真实模型接口响应达到512KiB读取预算，本轮调用未完成，请缩小输出");
    }
    private static ApiException reject(AnalysisModelDiagnostics diagnostics,Outcome outcome,String message) {
        diagnostics.protocol(outcome);return new ApiException(503,message);
    }

    /** 成功校验只更换读取位置，HTTP状态、响应头、原始JSON和资源归属均保持。 */
    private static final class CheckedResponse implements ClientHttpResponse {
        private final ClientHttpResponse original;
        private final InputStream body;
        private CheckedResponse(ClientHttpResponse original,InputStream body){this.original=original;this.body=body;}
        @Override public HttpStatusCode getStatusCode() throws IOException{return original.getStatusCode();}
        @Override public String getStatusText() throws IOException{return original.getStatusText();}
        @Override public HttpHeaders getHeaders(){return original.getHeaders();}
        @Override public InputStream getBody(){return body;}
        @Override public void close(){original.close();}
    }
}
