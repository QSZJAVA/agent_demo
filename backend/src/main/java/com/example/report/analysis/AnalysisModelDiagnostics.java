package com.example.report.analysis;

import com.example.report.common.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpResponse;
import java.io.*;
import java.security.*;
import java.util.*;

/**
 * 单次模型传输的失败诊断，只保留请求摘要、固定选项和有界响应分类。
 * 不记录正文、自由错误消息、地址、请求标识或认证信息；响应最多预读16KiB，并按原顺序回放给SDK。
 */
final class AnalysisModelDiagnostics {
    static final int MAX_BODY_BYTES=16*1024;
    static final int MAX_CAUSES=8;
    /** 只输出已知框架和网络异常的固定类名；自定义类型也可能带私有标识，只保留其类型摘要。 */
    private static final Set<String> SAFE_CAUSE_TYPES=Set.of("java.lang.Exception","java.lang.RuntimeException","java.lang.IllegalArgumentException",
            "java.lang.IllegalStateException","java.lang.InterruptedException","java.io.IOException","java.io.InterruptedIOException",
            "java.io.EOFException","java.io.UncheckedIOException","java.net.SocketException","java.net.SocketTimeoutException",
            "java.net.ConnectException","java.net.NoRouteToHostException","java.net.UnknownHostException","java.net.ProtocolException",
            "java.net.http.HttpTimeoutException","java.net.http.HttpConnectTimeoutException","java.nio.channels.UnresolvedAddressException",
            "java.nio.channels.ClosedChannelException","java.nio.channels.AsynchronousCloseException","java.nio.channels.ClosedByInterruptException",
            "javax.net.ssl.SSLException","javax.net.ssl.SSLHandshakeException","javax.net.ssl.SSLPeerUnverifiedException","javax.net.ssl.SSLProtocolException",
            "java.security.cert.CertificateException","java.security.cert.CertificateExpiredException","java.security.cert.CertificateNotYetValidException",
            "java.util.concurrent.CompletionException","java.util.concurrent.ExecutionException","java.util.concurrent.TimeoutException",
            "java.util.concurrent.CancellationException","java.lang.reflect.InvocationTargetException","sun.net.ConnectionResetException",
            "org.springframework.web.client.ResourceAccessException","org.springframework.web.client.RestClientException",
            "org.springframework.web.client.UnknownContentTypeException","org.springframework.ai.retry.TransientAiException",
            "org.springframework.ai.retry.NonTransientAiException","com.example.report.common.ApiException");
    private static final Set<String> ERROR_CODES=Set.of("invalid_json_schema","invalid_request_error","invalid_parameter","invalid_value",
            "unsupported_parameter","unsupported_value","model_not_found","context_length_exceeded","rate_limit_exceeded","insufficient_quota",
            "invalid_api_key","permission_denied","content_filter","content_policy_violation","invalid_prompt","invalid_response_format",
            "server_error","service_unavailable","timeout","InvalidParameter","InvalidParameterError","DataInspectionFailed","InvalidApiKey",
            "AccessDenied","Throttling","InternalError");
    private static final Set<String> ERROR_TYPES=Set.of("invalid_request_error","authentication_error","permission_error","rate_limit_error",
            "server_error","api_error","not_found_error","unprocessable_entity_error","timeout_error");
    private static final Set<String> ERROR_PARAMS=Set.of("model","messages","messages[0].content","messages[1].content","max_tokens",
            "max_completion_tokens","temperature","top_p","stream","stream_options","thinking","tools","tool_choice","parallel_tool_calls",
            "n","seed","response_format","response_format.type","response_format.json_schema","response_format.json_schema.name",
            "response_format.json_schema.schema","response_format.json_schema.strict");
    private static final Set<String> MEDIA_TYPES=Set.of("application/json","application/problem+json","text/plain","text/html",
            "text/event-stream","application/octet-stream");
    private final Map<String,Object> request=new LinkedHashMap<>();
    private int status;
    private String media="UNKNOWN",mediaHash;
    private AnalysisModelResponseGuard.Outcome protocol=AnalysisModelResponseGuard.Outcome.NOT_CHECKED;
    private String failureType="UNOBSERVED";
    private Map<String,Object> failureDetails=Map.of("classification","UNOBSERVED","causes",List.of(),"chainEnd","NOT_OBSERVED","maxCauses",MAX_CAUSES);
    private boolean jsonMedia,completeBody,readFailed;
    private byte[] bodyPrefix=new byte[0];

    /**
     * 从实际出站字节取摘要及固定选项，不能用业务层输入hash冒充SDK最终请求hash。
     * @param bytes SDK已经完成序列化的请求；仅本次方法内读取，不保留原字节或任意业务字段
     */
    void request(byte[] bytes) {
        request.put("wireBytes",bytes.length);request.put("wireSha256",hash(bytes));
        if(bytes.length>128*1024){request.put("shape","OVER_BUDGET");return;}
        try {
            var root=JsonUtil.strictMapper().readTree(bytes);request.put("shape",root!=null&&root.isObject()?"OBJECT":"OTHER");
            if(root==null||!root.isObject())return;
            var format=root.path("response_format");var schema=format.path("json_schema");
            request.put("schemaSha256",hash(JsonUtil.toJson(schema.path("schema")).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            var options=new LinkedHashMap<String,Object>();
            for(String name:List.of("max_tokens","max_completion_tokens","temperature","top_p","n"))number(options,name,root.path(name));
            bool(options,"stream",root.path("stream"));bool(options,"strict",schema.path("strict"));
            machine(options,"responseFormat",format.path("type"),Set.of("json_schema","json_object","text"));
            machine(options,"schemaName",schema.path("name"),Set.of("custom_schema"));
            machine(options,"thinking",root.path("thinking").path("type"),Set.of("enabled","disabled"));
            options.put("toolsCount",root.path("tools").isArray()?root.path("tools").size():root.path("tools").isMissingNode()?0:-1);
            request.put("options",options);
            var messages=new ArrayList<Map<String,Object>>();
            if(root.path("messages").isArray())for(var message:root.path("messages")) {
                var item=new LinkedHashMap<String,Object>();machine(item,"role",message.path("role"),Set.of("system","user","assistant","tool","developer"));
                var content=message.path("content");item.put("contentBytes",(content.isTextual()?content.textValue():JsonUtil.toJson(content)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length);messages.add(item);
            }
            request.put("messages",messages);
        } catch(Exception invalid){request.put("shape","UNREADABLE");}
    }

    /**
     * 有界预读用于失败时区分错误协议；成功响应不输出诊断。读取异常和已读字节均回放，不能因诊断改变SDK的响应或用量。
     * @param response 实际HTTP响应，关闭责任仍由SDK承担
     * @return 状态和头部保持原样、正文从首字节重新开始的响应包装
     * @throws IOException 获取响应状态或正文流失败时保留传输错误
     */
    ClientHttpResponse response(ClientHttpResponse response) throws IOException {
        status=response.getStatusCode().value();
        String value=Objects.toString(response.getHeaders().getFirst("Content-Type"),"").split(";",2)[0].trim().toLowerCase(Locale.ROOT);
        jsonMedia=value.equals("application/json")||value.startsWith("application/")&&value.endsWith("+json");
        media=value.isEmpty()?"UNKNOWN":MEDIA_TYPES.contains(value)?value:jsonMedia?"application/*+json":"OTHER";
        if(media.equals("OTHER"))mediaHash=hash(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        InputStream stream;
        try{stream=response.getBody();}catch(IOException unavailable){readFailed=true;throw unavailable;}
        byte[] prefix=new byte[MAX_BODY_BYTES];int size=0;IOException failure=null;
        try {
            while(size<MAX_BODY_BYTES) {
                int count=stream.read(prefix,size,MAX_BODY_BYTES-size);
                if(count<0){completeBody=true;break;}
                if(count==0){int single=stream.read();if(single<0){completeBody=true;break;}prefix[size++]=(byte)single;}
                else size+=count;
            }
        } catch(IOException unavailable){readFailed=true;failure=unavailable;}
        bodyPrefix=Arrays.copyOf(prefix,size);
        // 正好达到上限仍没有读取第16385字节；只有HTTP明确声明同长度时，才把该前缀视为完整正文。
        if(!readFailed&&size==MAX_BODY_BYTES)try{completeBody=response.getHeaders().getContentLength()==size;}
        catch(RuntimeException invalidLength){completeBody=false;}
        return new ReplayedResponse(response,new ReplayedBody(bodyPrefix,stream,failure));
    }

    /** 失败日志的唯一出口数据，全为固定标签、数值或摘要；不返回任何自由正文或响应头值。 */
    Map<String,Object> summary() {
        var result=new LinkedHashMap<String,Object>();result.put("request",Collections.unmodifiableMap(new LinkedHashMap<>(request)));
        result.put("httpStatus",status);result.put("mediaType",media);if(mediaHash!=null)result.put("mediaSha256",mediaHash);
        result.put("body",body());result.put("protocol",protocol.name());result.put("failure",failureDetails);return Collections.unmodifiableMap(result);
    }

    /**
     * 仅以异常类型诊断当前调用，最多检查八个cause并按对象身份检测循环；不读取消息、堆栈或suppressed树。
     * @param failure 原始失败，只在本次方法内遍历，不保存Throwable、地址、请求标识或自由文本
     */
    void failure(Throwable failure) {
        var causes=new ArrayList<Map<String,Object>>();var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        var categories=new LinkedHashSet<String>();NetworkFailure classification=NetworkFailure.UNKNOWN;String end="COMPLETE";failureType="UNOBSERVED";
        Throwable current=failure;
        while(current!=null) {
            if(seen.contains(current)){end="CYCLE";break;}
            if(causes.size()==MAX_CAUSES){end="DEPTH_LIMIT";break;}
            seen.add(current);String name=current.getClass().getName();var category=networkFailure(current);var item=new LinkedHashMap<String,Object>();
            item.put("type",SAFE_CAUSE_TYPES.contains(name)?name:"OTHER");item.put("category",category.name());
            if(!SAFE_CAUSE_TYPES.contains(name))item.put("typeSha256",hash(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            if(causes.isEmpty())failureType=SAFE_CAUSE_TYPES.contains(name)?name.substring(name.lastIndexOf('.')+1):"OTHER";causes.add(Collections.unmodifiableMap(item));
            if(category!=NetworkFailure.UNKNOWN)categories.add(category.name());
            if(category.priority>classification.priority)classification=category;
            // getCause可被自定义异常覆盖；读取失败也只给固定标签，不能让诊断覆盖原始业务错误。
            try{current=current.getCause();}catch(RuntimeException unreadable){end="UNREADABLE";break;}
        }
        failureDetails=Map.of("classification",classification.name(),"categories",List.copyOf(categories),"causes",List.copyOf(causes),"chainEnd",end,"maxCauses",MAX_CAUSES);
    }

    /** 日志前缀也使用同一类名白名单，不能在JSON以外泄露未知异常的原始类名。 */
    String failureType(){return failureType;}

    /**
     * 更具体的类型优先于宽泛父类；固定分类仅说明类型证据，不证明请求未发出或具备重试条件。
     * 普通ConnectException不能排除连接超时，SocketException也不能仅凭消息推断重置。
     */
    private static NetworkFailure networkFailure(Throwable failure) {
        if(failure instanceof java.net.http.HttpConnectTimeoutException)return NetworkFailure.CONNECT_TIMEOUT;
        if(failure instanceof java.net.http.HttpTimeoutException||failure instanceof java.net.SocketTimeoutException||failure instanceof java.util.concurrent.TimeoutException)return NetworkFailure.TIMEOUT;
        if(failure instanceof java.util.concurrent.CancellationException)return NetworkFailure.CANCELLED;
        if(failure instanceof InterruptedException||failure instanceof java.nio.channels.ClosedByInterruptException||failure instanceof InterruptedIOException)return NetworkFailure.INTERRUPTED;
        if(failure instanceof java.net.UnknownHostException||failure instanceof java.nio.channels.UnresolvedAddressException)return NetworkFailure.ADDRESS_RESOLUTION;
        if(failure instanceof javax.net.ssl.SSLException)return NetworkFailure.TLS;
        if(failure.getClass().getName().equals("sun.net.ConnectionResetException"))return NetworkFailure.CONNECTION_RESET;
        if(failure instanceof EOFException)return NetworkFailure.EOF;
        if(failure instanceof java.net.ConnectException||failure instanceof java.net.NoRouteToHostException)return NetworkFailure.CONNECT_FAILURE;
        if(failure instanceof java.net.ProtocolException)return NetworkFailure.HTTP_PROTOCOL;
        if(failure instanceof java.net.SocketException)return NetworkFailure.SOCKET_IO;
        if(failure instanceof java.nio.channels.ClosedChannelException)return NetworkFailure.CHANNEL_CLOSED;
        if(failure instanceof IOException||failure instanceof UncheckedIOException)return NetworkFailure.IO_ERROR;
        return NetworkFailure.UNKNOWN;
    }

    /** 选择链内更明确的类型分类，同时保留各层原始顺序；优先级不能替代对实际故障根因的核对。 */
    private enum NetworkFailure {
        UNKNOWN(0),IO_ERROR(1),SOCKET_IO(2),CHANNEL_CLOSED(2),HTTP_PROTOCOL(3),CONNECT_FAILURE(4),EOF(4),CONNECTION_RESET(5),
        ADDRESS_RESOLUTION(6),TLS(7),INTERRUPTED(8),CANCELLED(9),TIMEOUT(10),CONNECT_TIMEOUT(11);
        final int priority;NetworkFailure(int priority){this.priority=priority;}
    }

    int status(){return status;}
    boolean jsonMedia(){return jsonMedia;}
    String media(){return media;}

    /** 完整响应校验只回传固定标签；诊断层仍仅持有16KiB前缀，不保存完整原文。 */
    void protocol(AnalysisModelResponseGuard.Outcome value){protocol=Objects.requireNonNull(value);}

    /** 媒体类型错误与正文JSON语法分开说明；有界前缀不能证明超大正文是否合法。 */
    String mediaFailureMessage() {
        String format=Objects.toString(body().get("format"));
        String detail=switch(format) {
            case "JSON" -> "正文可解析为JSON，但响应媒体类型不符合协议";
            case "NON_JSON" -> "有界读取的完整正文未能解析为JSON";
            case "EMPTY" -> "响应正文为空";
            default -> "正文格式尚未确认";
        };
        return "真实模型接口响应媒体类型不符合JSON协议（"+media+"）；"+detail+"，本轮调用未完成，请核对端点响应";
    }

    private Map<String,Object> body() {
        var result=new LinkedHashMap<String,Object>();result.put("capturedBytes",bodyPrefix.length);result.put("complete",completeBody&&!readFailed);
        result.put("prefixSha256",hash(bodyPrefix));
        if(status==0){result.put("format","UNOBSERVED");return result;}
        if(readFailed){result.put("format","READ_FAILED");return result;}
        if(!completeBody){result.put("format","LIMIT_REACHED");return result;}
        if(bodyPrefix.length==0){result.put("format","EMPTY");return result;}
        try {
            var root=JsonUtil.strictMapper().readTree(bodyPrefix);
            if(root==null){result.put("format","NON_JSON");return result;}
            result.put("format","JSON");var error=root.path("error");
            if(error.isMissingNode())error=root;
            if(error.isObject()) {
                var values=new LinkedHashMap<String,Object>();machine(values,"code",error.path("code"),ERROR_CODES);
                machine(values,"type",error.path("type"),ERROR_TYPES);machine(values,"param",error.path("param"),ERROR_PARAMS);result.put("error",values);
            }
        } catch(Exception invalid){result.put("format","NON_JSON");}
        return result;
    }

    /** 未知机器字段不能仅凭字符格式通过；看似合法的自定义错误值也只保存类型及摘要。 */
    private static void machine(Map<String,Object> target,String name,JsonNode value,Set<String> allowed) {
        if(value.isMissingNode()||value.isNull()){target.put(name,"ABSENT");return;}
        if(value.isTextual()&&allowed.contains(value.textValue())){target.put(name,value.textValue());return;}
        target.put(name,"UNKNOWN");target.put(name+"Kind",value.getNodeType().name());
        target.put(name+"Sha256",hash(JsonUtil.toJson(value).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    private static void number(Map<String,Object> target,String name,JsonNode value) {
        if(value.isMissingNode()||value.isNull())target.put(name,"ABSENT");
        else if(value.isNumber()&&value.doubleValue()>=0&&value.doubleValue()<=1_000_000)target.put(name,value.numberValue());
        else target.put(name,"INVALID");
    }
    private static void bool(Map<String,Object> target,String name,JsonNode value){target.put(name,value.isBoolean()?value.booleanValue():value.isMissingNode()||value.isNull()?"ABSENT":"INVALID");}
    private static String hash(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256不可用",impossible);}
    }

    /** 只替换正文流，保留状态、媒体类型和关闭责任，不把错误响应转换为成功响应。 */
    private static final class ReplayedResponse implements ClientHttpResponse {
        private final ClientHttpResponse original;private final InputStream body;
        private ReplayedResponse(ClientHttpResponse original,InputStream body){this.original=original;this.body=body;}
        @Override public HttpStatusCode getStatusCode() throws IOException{return original.getStatusCode();}
        @Override public String getStatusText() throws IOException{return original.getStatusText();}
        @Override public HttpHeaders getHeaders(){return original.getHeaders();}
        @Override public InputStream getBody(){return body;}
        @Override public void close(){original.close();}
    }

    /** 已读前缀先回放，再交还原始流；预读遇到的IOException在同一字节位置重现，不能被诊断吞掉。 */
    private static final class ReplayedBody extends InputStream {
        private final byte[] prefix;private final InputStream original;private final IOException failure;private int position;
        private ReplayedBody(byte[] prefix,InputStream original,IOException failure){this.prefix=prefix;this.original=original;this.failure=failure;}
        @Override public int read() throws IOException {if(position<prefix.length)return prefix[position++]&0xff;if(failure!=null)throw failure;return original.read();}
        @Override public int read(byte[] bytes,int offset,int length) throws IOException {
            Objects.checkFromIndexSize(offset,length,bytes.length);if(length==0)return 0;
            if(position<prefix.length){int count=Math.min(length,prefix.length-position);System.arraycopy(prefix,position,bytes,offset,count);position+=count;return count;}
            if(failure!=null)throw failure;return original.read(bytes,offset,length);
        }
        @Override public void close() throws IOException {original.close();}
    }
}
