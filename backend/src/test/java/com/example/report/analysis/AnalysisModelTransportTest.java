package com.example.report.analysis;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.example.report.common.ApiException;
import com.example.report.common.JsonUtil;
import com.example.report.common.ModelUsage;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 用本机HTTP错误响应验证诊断和单次传输，不将受控端点测试当成真实模型语义验收。 */
class AnalysisModelTransportTest {
    private static final String SCHEMA="{\"type\":\"object\",\"properties\":{\"approved\":{\"type\":\"boolean\"}},\"required\":[\"approved\"],\"additionalProperties\":false}";

    @Test void nonJsonGatewayReplyIsExplicitAndNotRetried() throws Exception {
        var call=invoke(200,"text/html","<html>private-error-page-and-secret-token</html>");
        assertFailure(call,"text/html");var diagnostic=diagnostic(call);
        assertEquals(200,diagnostic.path("httpStatus").asInt());assertEquals("NON_JSON",diagnostic.path("body").path("format").asText());
        assertHidden(call,"private-error-page","secret-token");
    }

    @Test void upstreamErrorStatusIsKeptWithoutReturningErrorPageContents() throws Exception {
        var call=invoke(502,"text/html","<html>private-error-page-and-secret-token</html>");
        assertFailure(call,"HTTP 502");assertEquals(502,diagnostic(call).path("httpStatus").asInt());
        assertHidden(call,"private-error-page","secret-token");
    }

    @Test void jsonErrorKeepsOnlyWhitelistedMachineValuesAndActualWireEvidence() throws Exception {
        var call=invoke(400,"application/json",JsonUtil.toJson(Map.of("error",Map.of("code","invalid_json_schema","type","invalid_request_error",
                "param","response_format.json_schema.schema","message","private-provider-error-message"))));
        assertFailure(call,"HTTP 400");var diagnostic=diagnostic(call);var error=diagnostic.path("body").path("error");
        assertEquals("JSON",diagnostic.path("body").path("format").asText());
        assertEquals("invalid_json_schema",error.path("code").asText());assertEquals("invalid_request_error",error.path("type").asText());
        assertEquals("response_format.json_schema.schema",error.path("param").asText());
        var wire=JsonUtil.strictMapper().readTree(call.wire());var request=diagnostic.path("request");
        assertEquals(call.wire().length,request.path("wireBytes").asInt());assertEquals(hash(call.wire()),request.path("wireSha256").asText());
        assertEquals(hash(JsonUtil.toJson(wire.path("response_format").path("json_schema").path("schema")).getBytes(StandardCharsets.UTF_8)),request.path("schemaSha256").asText());
        var options=request.path("options");assertEquals("json_schema",options.path("responseFormat").asText());assertEquals("custom_schema",options.path("schemaName").asText());
        assertTrue(options.path("strict").asBoolean());assertEquals(4096,options.path("max_tokens").asInt());assertEquals(0.0,options.path("temperature").asDouble());
        assertEquals("false",options.path("stream").asText());assertEquals("disabled",options.path("thinking").asText());assertEquals(0,options.path("toolsCount").asInt());
        assertHidden(call,"private-provider-error-message");
    }

    @Test void unknownMachineValuesAreHashedRegardlessOfTheirShape() throws Exception {
        var values=Map.of("code","private_machine_identifier","type","Bearer private-error-token",
                "param",Map.of("location","https://private.invalid/credential"),"message","private-free-error-message");
        var call=invoke(400,"application/json",JsonUtil.toJson(Map.of("error",values)));
        assertFailure(call,"HTTP 400");var error=diagnostic(call).path("body").path("error");
        for(var field:List.of("code","type","param")) {
            assertEquals("UNKNOWN",error.path(field).asText());assertEquals(hash(JsonUtil.toJson(values.get(field)).getBytes(StandardCharsets.UTF_8)),error.path(field+"Sha256").asText());
        }
        assertEquals("STRING",error.path("codeKind").asText());assertEquals("STRING",error.path("typeKind").asText());assertEquals("OBJECT",error.path("paramKind").asText());
        assertHidden(call,"private_machine_identifier","private-error-token","https://private.invalid/credential","private-free-error-message");
    }

    @Test void jsonBodyWithPlainTextMediaIsClassifiedButNotAccepted() throws Exception {
        var call=invoke(200,"text/plain",completion());
        assertFailure(call,"正文可解析为JSON");assertEquals("JSON",diagnostic(call).path("body").path("format").asText());
        assertEquals("text/plain",diagnostic(call).path("mediaType").asText());assertNull(call.reply());
        assertFalse(call.failure().getMessage().contains("未返回JSON"));
    }

    @Test void unknownMediaTypeIsHashedWithoutLoggingTheHeader() throws Exception {
        var call=invoke(200,"text/x-private-header-token","private-body-value");
        assertFailure(call,"OTHER");var diagnostic=diagnostic(call);
        assertEquals("OTHER",diagnostic.path("mediaType").asText());
        assertEquals(hash("text/x-private-header-token".getBytes(StandardCharsets.UTF_8)),diagnostic.path("mediaSha256").asText());
        assertHidden(call,"private-header-token","private-body-value");
    }

    @Test void normalJsonReplyKeepsNativeUsageWithoutWritingFailureDiagnostics() throws Exception {
        var call=invoke(200,"application/json",completion());
        assertNull(call.failure());assertNotNull(call.reply());assertEquals("{\"approved\":true}",call.reply().json());
        assertEquals(new ModelUsage(120,30,15),call.reply().usage());assertEquals(1,call.calls());assertTrue(call.logs().isEmpty(),call.logs().toString());
        assertHidden(call,"private-test-request","server instructions","local-contract-test","127.0.0.1","server-test-request-id");
    }

    @Test void emptyAndMalformedSuccessBodiesAreRejectedBeforeSdkCanLogThePrompt() throws Exception {
        for(String body:List.of("","null","{}","{\"choices\":null}","{\"choices\":[]}","[]")) {
            var call=invoke(200,"application/json",body);assertFailure(call,"响应结构不完整");
            assertEquals("INVALID_SHAPE",diagnostic(call).path("protocol").asText());
        }
    }

    @Test void largeMalformedSuccessBodiesCannotBypassTheDiagnosticPrefixBudget() throws Exception {
        for(String body:List.of("{}","{\"choices\":null}","{\"choices\":[]}")) {
            var call=invoke(200,"application/json",withPadding(body,32*1024));assertFailure(call,"响应结构不完整");
            var diagnostic=diagnostic(call);assertEquals("INVALID_SHAPE",diagnostic.path("protocol").asText());
            assertEquals(AnalysisModelDiagnostics.MAX_BODY_BYTES,diagnostic.path("body").path("capturedBytes").asInt());
            assertEquals("LIMIT_REACHED",diagnostic.path("body").path("format").asText());assertHidden(call,"private-response-padding");
        }
    }

    @Test void emptyResponsesWithUnsupportedStatusOrMediaNeverReachSdk() throws Exception {
        var noContent=invoke(204,null,"");assertFailure(noContent,"HTTP 204");
        var plain=invoke(200,"text/plain","");assertFailure(plain,"媒体类型");
        for(int status:List.of(302,304)) {
            var call=invoke(status,null,"");assertFailure(call,"非成功HTTP "+status);
            assertEquals("UNEXPECTED_STATUS",diagnostic(call).path("protocol").asText());
        }
    }

    @Test void successfulBodyAboveHardBudgetIsRejectedWithoutAnotherRequest() throws Exception {
        var call=invoke(200,"application/json",withPadding(completion(),AnalysisModelResponseGuard.MAX_RESPONSE_BYTES));
        assertFailure(call,"512KiB");var diagnostic=diagnostic(call);assertEquals("OVER_BUDGET",diagnostic.path("protocol").asText());
        assertEquals(AnalysisModelDiagnostics.MAX_BODY_BYTES,diagnostic.path("body").path("capturedBytes").asInt());assertHidden(call,"private-response-padding");
    }

    @Test void errorBodyAlsoHasAHardBudgetBeforeSdkErrorHandling() throws Exception {
        var call=invoke(502,"text/plain","private-large-error-body"+"x".repeat(AnalysisModelResponseGuard.MAX_RESPONSE_BYTES));assertFailure(call,"512KiB");
        var diagnostic=diagnostic(call);assertEquals("OVER_BUDGET",diagnostic.path("protocol").asText());assertEquals(502,diagnostic.path("httpStatus").asInt());
        assertHidden(call,"private-large-error-body");
    }

    @Test void chunkedBodyAtHardBudgetIsRejectedWithoutReadingPastIt() throws Exception {
        byte[] bytes=new byte[AnalysisModelResponseGuard.MAX_RESPONSE_BYTES+1];Arrays.fill(bytes,(byte)'x');var stream=new CountedInput(bytes);
        var headers=new HttpHeaders();headers.set("Content-Type","application/json");var diagnostics=new AnalysisModelDiagnostics();
        var replay=diagnostics.response(response(stream,headers));
        var failure=assertThrows(ApiException.class,()->AnalysisModelResponseGuard.inspect(replay,diagnostics));
        assertEquals(503,failure.getCode());assertEquals(AnalysisModelResponseGuard.MAX_RESPONSE_BYTES,stream.readBytes);
        assertEquals("OVER_BUDGET",diagnostics.summary().get("protocol"));
    }

    @Test void completeGuardReplaysOriginalBytesAndPreservesHeadersAndCloseResponsibility() throws Exception {
        byte[] bytes=(" \n"+withPadding(completion(),32*1024)+"\n ").getBytes(StandardCharsets.UTF_8);
        var stream=new CountedInput(bytes);var headers=new HttpHeaders();headers.set("Content-Type","application/json");headers.setContentLength(bytes.length);headers.set("x-ratelimit-limit-requests"," 100 ");
        var original=response(stream,headers);var diagnostics=new AnalysisModelDiagnostics();
        var checked=AnalysisModelResponseGuard.inspect(diagnostics.response(original),diagnostics);
        assertEquals(bytes.length,stream.readBytes);assertSame(headers,checked.getHeaders());assertEquals(HttpStatus.OK,checked.getStatusCode());
        assertArrayEquals(bytes,checked.getBody().readAllBytes());assertEquals("VALID",diagnostics.summary().get("protocol"));checked.close();verify(original).close();
    }

    @Test void metadataThatWouldHideAValidBodyFromSdkIsRejectedBeforehand() throws Exception {
        for(boolean noContentStatus:List.of(true,false)) {
            var headers=new HttpHeaders();headers.set("Content-Type","application/json");if(!noContentStatus)headers.setContentLength(0);
            var original=response(new ByteArrayInputStream(completion().getBytes(StandardCharsets.UTF_8)),headers);
            if(noContentStatus)when(original.getStatusCode()).thenReturn(HttpStatus.NO_CONTENT);
            var diagnostics=new AnalysisModelDiagnostics();var replay=diagnostics.response(original);
            assertThrows(ApiException.class,()->AnalysisModelResponseGuard.inspect(replay,diagnostics));assertEquals("INVALID_SHAPE",diagnostics.summary().get("protocol"));
        }
    }

    @Test void largeNormalReplyKeepsUsageAndValidRateLimitHeaders() throws Exception {
        var call=invoke(200,"application/json",withPadding(completion(),32*1024),Map.of("x-ratelimit-limit-requests","100",
                "x-ratelimit-remaining-requests","0","x-ratelimit-limit-tokens",Long.toString(Long.MAX_VALUE),"x-ratelimit-remaining-tokens","15"));
        assertNull(call.failure());assertNotNull(call.reply());assertEquals("{\"approved\":true}",call.reply().json());
        assertEquals(new ModelUsage(120,30,15),call.reply().usage());assertEquals(1,call.calls());assertTrue(call.logs().isEmpty(),call.logs().toString());
        assertHidden(call,"private-test-request","server instructions","private-response-padding","server-test-request-id");
    }

    @Test void invalidNumericRateLimitHeadersCannotMakeSdkLogTheirOriginalValues() throws Exception {
        for(String name:List.of("x-ratelimit-limit-requests","x-ratelimit-remaining-requests","x-ratelimit-limit-tokens","x-ratelimit-remaining-tokens")) {
            var call=invoke(200,"application/json",completion(),Map.of(name,"private-rate-limit-token"));assertFailure(call,"限额响应头");
            assertEquals("INVALID_HEADERS",diagnostic(call).path("protocol").asText());assertHidden(call,"private-rate-limit-token");
        }
    }

    @Test void trailingJsonDataIsRejectedByTheCompleteResponseProtocol() throws Exception {
        var call=invoke(200,"application/json",completion()+"{\"private-extra-response\":true}");assertFailure(call,"正文不符合JSON协议");
        assertEquals("INVALID_JSON",diagnostic(call).path("protocol").asText());assertHidden(call,"private-extra-response");
    }

    @Test void responseProtocolAndBudgetsParticipateInConfigurationEvidence() {
        var model=new AnalysisOpenAiModel("http://127.0.0.1","local-contract-test","local-test-model","/v1/chat/completions");
        var configuration=model.configuration();assertEquals(AnalysisModelResponseGuard.PROTOCOL_VERSION,configuration.get("responseProtocolVersion"));
        assertEquals(AnalysisModelResponseGuard.MAX_RESPONSE_BYTES,configuration.get("responseMaxBytes"));
        assertEquals(AnalysisModelDiagnostics.MAX_BODY_BYTES,configuration.get("diagnosticMaxBytes"));
        assertFalse(JsonUtil.toJson(configuration).contains("local-contract-test"));
    }

    @Test void boundedPeekReplaysEveryByteAndPreservesResponseOwnership() throws Exception {
        byte[] bytes=new byte[AnalysisModelDiagnostics.MAX_BODY_BYTES+37];Arrays.fill(bytes,(byte)'x');
        var stream=new CountedInput(bytes);var headers=new HttpHeaders();headers.set("Content-Type","application/json");
        var original=response(stream,headers);var diagnostics=new AnalysisModelDiagnostics();var replay=diagnostics.response(original);
        assertEquals(AnalysisModelDiagnostics.MAX_BODY_BYTES,stream.readBytes);assertEquals("LIMIT_REACHED",bodyFormat(diagnostics));
        assertSame(headers,replay.getHeaders());assertEquals(original.getStatusCode(),replay.getStatusCode());assertEquals(original.getStatusText(),replay.getStatusText());
        assertArrayEquals(bytes,replay.getBody().readAllBytes());assertEquals(bytes.length,stream.readBytes);
        replay.close();verify(original).close();
    }

    @Test void exactBudgetWithoutDeclaredLengthDoesNotClaimCompleteJson() throws Exception {
        byte[] bytes=exactBudgetJson();var stream=new CountedInput(bytes);var diagnostics=new AnalysisModelDiagnostics();
        var replay=diagnostics.response(response(stream,new HttpHeaders()));
        assertEquals(bytes.length,stream.readBytes);assertEquals("LIMIT_REACHED",bodyFormat(diagnostics));assertArrayEquals(bytes,replay.getBody().readAllBytes());
    }

    @Test void exactBudgetWithDeclaredLengthCanBeClassifiedWithoutExtraRead() throws Exception {
        byte[] bytes=exactBudgetJson();var stream=new CountedInput(bytes);var headers=new HttpHeaders();headers.setContentLength(bytes.length);
        var diagnostics=new AnalysisModelDiagnostics();var replay=diagnostics.response(response(stream,headers));
        assertEquals(bytes.length,stream.readBytes);assertEquals("JSON",bodyFormat(diagnostics));assertArrayEquals(bytes,replay.getBody().readAllBytes());
    }

    @Test void invalidDeclaredLengthCannotIntroduceAnotherTransportFailure() throws Exception {
        byte[] bytes=exactBudgetJson();var headers=new HttpHeaders();headers.set("Content-Length","invalid-length");
        var diagnostics=new AnalysisModelDiagnostics();var replay=diagnostics.response(response(new ByteArrayInputStream(bytes),headers));
        assertEquals("LIMIT_REACHED",bodyFormat(diagnostics));assertArrayEquals(bytes,replay.getBody().readAllBytes());
    }

    @Test void peekReadFailureIsReplayedAfterItsOriginalPrefix() throws Exception {
        byte[] prefix="{\"partial\":".getBytes(StandardCharsets.UTF_8);var expected=new IOException("private-stream-error");
        var stream=new InputStream() {
            private int offset;
            @Override public int read() throws IOException {if(offset<prefix.length)return prefix[offset++]&0xff;throw expected;}
            @Override public int read(byte[] bytes,int offset,int length) throws IOException {
                if(length==0)return 0;if(this.offset>=prefix.length)throw expected;
                int count=Math.min(length,prefix.length-this.offset);System.arraycopy(prefix,this.offset,bytes,offset,count);this.offset+=count;return count;
            }
        };
        var diagnostics=new AnalysisModelDiagnostics();var replay=diagnostics.response(response(stream,new HttpHeaders()));
        assertEquals("READ_FAILED",bodyFormat(diagnostics));assertArrayEquals(prefix,replay.getBody().readNBytes(prefix.length));
        assertSame(expected,assertThrows(IOException.class,()->replay.getBody().read()));assertFalse(JsonUtil.toJson(diagnostics.summary()).contains("private-stream-error"));
    }

    @Test void failureToAcquireBodyIsRecordedWithoutExposingItsMessage() throws Exception {
        var original=response(new ByteArrayInputStream(new byte[0]),new HttpHeaders());var expected=new IOException("private-get-body-error");
        when(original.getBody()).thenThrow(expected);var diagnostics=new AnalysisModelDiagnostics();
        assertSame(expected,assertThrows(IOException.class,()->diagnostics.response(original)));assertEquals("READ_FAILED",bodyFormat(diagnostics));
        assertFalse(JsonUtil.toJson(diagnostics.summary()).contains("private-get-body-error"));
    }

    @Test void nestedTransportFailureKeepsKnownCauseTypesWithoutTheirMessages() {
        var timeout=new java.net.http.HttpConnectTimeoutException("https://private.invalid/connect?credential=private-network-token");
        var wrapped=new org.springframework.web.client.ResourceAccessException("Bearer private-wrapper-token",new IOException("private-io-message",timeout));
        var failure=failureDetails(wrapped);assertEquals("CONNECT_TIMEOUT",failure.path("classification").asText());assertEquals("COMPLETE",failure.path("chainEnd").asText());
        assertEquals(3,failure.path("causes").size());assertEquals("org.springframework.web.client.ResourceAccessException",failure.path("causes").get(0).path("type").asText());
        assertEquals("java.net.http.HttpConnectTimeoutException",failure.path("causes").get(2).path("type").asText());
        String json=JsonUtil.toJson(failure);for(String value:List.of("https://private.invalid","private-network-token","private-wrapper-token","private-io-message"))assertFalse(json.contains(value));
    }

    @Test void networkClassificationUsesSpecificTypesBeforeTheirBroaderParents() {
        assertNetwork("TIMEOUT",new java.net.SocketTimeoutException("private-timeout"));
        assertNetwork("TIMEOUT",new java.net.http.HttpTimeoutException("private-http-timeout"));
        assertNetwork("TIMEOUT",new java.util.concurrent.TimeoutException("private-future-timeout"));
        assertNetwork("INTERRUPTED",new java.io.InterruptedIOException("private-interruption"));
        assertNetwork("INTERRUPTED",new InterruptedException("private-interrupted"));
        assertNetwork("INTERRUPTED",new java.nio.channels.ClosedByInterruptException());
        assertNetwork("CANCELLED",new java.util.concurrent.CancellationException("private-cancellation"));
        assertNetwork("ADDRESS_RESOLUTION",new java.net.UnknownHostException("private-host.invalid"));
        assertNetwork("ADDRESS_RESOLUTION",new java.nio.channels.UnresolvedAddressException());
        assertNetwork("TLS",new javax.net.ssl.SSLHandshakeException("private-certificate-detail"));
        assertNetwork("EOF",new java.io.EOFException("private-premature-end"));
        assertNetwork("CONNECT_FAILURE",new java.net.ConnectException("private-connection"));
        assertNetwork("CONNECT_FAILURE",new java.net.NoRouteToHostException("private-address"));
        assertNetwork("HTTP_PROTOCOL",new java.net.ProtocolException("private-protocol"));
        assertNetwork("CHANNEL_CLOSED",new java.nio.channels.AsynchronousCloseException());
    }

    @Test void wrappedDnsAndTlsFailuresKeepTheMoreSpecificEvidenceAndCauseOrder() {
        var connect=new java.net.ConnectException("private-connection-wrapper");connect.initCause(new java.net.UnknownHostException("private-host.invalid"));
        var dns=failureDetails(connect);assertEquals("ADDRESS_RESOLUTION",dns.path("classification").asText());
        assertEquals("CONNECT_FAILURE",dns.path("causes").get(0).path("category").asText());assertEquals("ADDRESS_RESOLUTION",dns.path("causes").get(1).path("category").asText());
        var tls=new javax.net.ssl.SSLException("private-handshake",new java.io.EOFException("private-eof"));
        var details=failureDetails(tls);assertEquals("TLS",details.path("classification").asText());
        assertEquals("TLS",details.path("causes").get(0).path("category").asText());assertEquals("EOF",details.path("causes").get(1).path("category").asText());
    }

    @Test void messageKeywordsCannotTurnGenericSocketOrIoFailuresIntoSpecificNetworkCauses() {
        for(String message:List.of("Connection reset","HTTP connect timed out","UnknownHostException at private-host.invalid","SSL handshake failed")) {
            assertNetwork("SOCKET_IO",new java.net.SocketException(message));assertNetwork("IO_ERROR",new IOException(message));
        }
        assertNetwork("CONNECT_FAILURE",new java.net.ConnectException("HTTP connect timed out"));
    }

    @Test void unknownClassNamesAreHashedAndNeverReadOrPrintedByTheRealFailureLogger() throws Exception {
        var failure=new PrivateCredentialTransportException(new java.net.http.HttpConnectTimeoutException("private-leaf-token https://private.invalid"));
        var details=failureDetails(failure);var root=details.path("causes").get(0);
        assertEquals("OTHER",root.path("type").asText());assertEquals("IO_ERROR",root.path("category").asText());
        assertEquals(hash(failure.getClass().getName().getBytes(StandardCharsets.UTF_8)),root.path("typeSha256").asText());
        assertEquals("CONNECT_TIMEOUT",details.path("classification").asText());
        var logs=recordFailureLogs(failure);assertEquals(1,logs.size());String all=String.join("\n",logs);assertTrue(all.contains("type=OTHER"));
        for(String value:List.of("PrivateCredentialTransportException","private-leaf-token","private-top-token","https://private.invalid","server-test-request-id"))assertFalse(all.contains(value));
        assertTrue(all.contains("java.net.http.HttpConnectTimeoutException"));assertTrue(all.contains("CONNECT_TIMEOUT"));
    }

    @Test void causeDepthIsBoundedAndDoesNotInspectHiddenCauses() {
        for(int length:List.of(AnalysisModelDiagnostics.MAX_CAUSES,AnalysisModelDiagnostics.MAX_CAUSES+3)) {
            var reads=new AtomicInteger();Throwable cause=null;
            for(int index=0;index<length;index++)cause=new CountingCause(cause,reads);
            var failure=failureDetails(cause);assertEquals(AnalysisModelDiagnostics.MAX_CAUSES,failure.path("causes").size());
            assertEquals(AnalysisModelDiagnostics.MAX_CAUSES,reads.get());
            assertEquals(length==AnalysisModelDiagnostics.MAX_CAUSES?"COMPLETE":"DEPTH_LIMIT",failure.path("chainEnd").asText());
        }
    }

    @Test void causeCyclesTerminateByIdentityAndSuppressedFailuresAreNotTraversed() {
        var first=new IOException("private-cycle-first");var second=new RuntimeException("private-cycle-second",first);first.initCause(second);
        first.addSuppressed(new javax.net.ssl.SSLException("private-suppressed-tls"));var failure=failureDetails(first);
        assertEquals(2,failure.path("causes").size());assertEquals("CYCLE",failure.path("chainEnd").asText());assertEquals("IO_ERROR",failure.path("classification").asText());
        String json=JsonUtil.toJson(failure);assertFalse(json.contains("private-cycle"));assertFalse(json.contains("private-suppressed"));assertFalse(json.contains("TLS"));
    }

    @Test void failingCauseAccessorCannotReplaceTheOriginalFailureOrExposeItsMessage() {
        var broken=new RuntimeException("private-top-message") {@Override public synchronized Throwable getCause(){throw new IllegalStateException("private-cause-accessor-token");}};
        var diagnostics=new AnalysisModelDiagnostics();assertDoesNotThrow(()->diagnostics.failure(broken));
        var failure=JsonUtil.MAPPER.valueToTree(diagnostics.summary()).path("failure");assertEquals("UNREADABLE",failure.path("chainEnd").asText());
        assertEquals(1,failure.path("causes").size());assertFalse(JsonUtil.toJson(failure).contains("private-"));
    }

    private static void assertNetwork(String expected,Throwable failure){assertEquals(expected,failureDetails(failure).path("classification").asText());}
    private static JsonNode failureDetails(Throwable failure) {
        var diagnostics=new AnalysisModelDiagnostics();diagnostics.failure(failure);return JsonUtil.MAPPER.valueToTree(diagnostics.summary()).path("failure");
    }
    /** 直接进入生产失败日志出口，并捕获根日志及Throwable代理，防止只检查JSON而漏掉日志前缀或异常参数。 */
    private static List<String> recordFailureLogs(Exception failure) throws Exception {
        var root=(Logger)LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);var appender=new ListAppender<ILoggingEvent>();appender.start();root.addAppender(appender);
        try {
            var model=new AnalysisOpenAiModel("http://127.0.0.1","local-contract-test","local-test-model","/v1/chat/completions");
            var method=AnalysisOpenAiModel.class.getDeclaredMethod("recordFailure",Exception.class,AnalysisModelDiagnostics.class);method.setAccessible(true);
            method.invoke(model,failure,new AnalysisModelDiagnostics());return appender.list.stream().map(AnalysisModelTransportTest::completeLog).toList();
        } finally {root.detachAppender(appender);appender.stop();}
    }

    /** 使用受控回环端点捕获最终出站字节及本模块日志；每次调用独立资源，完成后仅关闭测试HTTP服务。 */
    private static Call invoke(int status,String media,String content) throws Exception {
        return invoke(status,media,content,Map.of());
    }
    private static Call invoke(int status,String media,String content,Map<String,String> headers) throws Exception {
        var count=new AtomicInteger();var wire=new AtomicReference<byte[]>();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{
            count.incrementAndGet();wire.set(exchange.getRequestBody().readAllBytes());byte[] body=content.getBytes(StandardCharsets.UTF_8);
            if(media!=null)exchange.getResponseHeaders().set("Content-Type",media);exchange.getResponseHeaders().set("X-Request-ID","server-test-request-id");
            headers.forEach((key,value)->exchange.getResponseHeaders().set(key,value));
            try{exchange.sendResponseHeaders(status,body.length==0?-1:body.length);if(body.length>0)exchange.getResponseBody().write(body);}finally{exchange.close();}
        });
        var logger=(Logger)LoggerFactory.getLogger(AnalysisOpenAiModel.class);var root=(Logger)LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        var appender=new ListAppender<ILoggingEvent>();var transportAppender=new ListAppender<ILoggingEvent>();appender.start();transportAppender.start();
        logger.addAppender(appender);root.addAppender(transportAppender);server.start();
        try {
            var model=new AnalysisOpenAiModel("http://127.0.0.1:"+server.getAddress().getPort(),"local-contract-test","local-test-model","/v1/chat/completions");
            AnalysisModel.Reply reply=null;ApiException failure=null;
            try{reply=model.call("server instructions",Map.of("question","private-test-request"),SCHEMA,Duration.ofSeconds(5));}
            catch(ApiException unavailable){failure=unavailable;}
            return new Call(reply,failure,appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList(),
                    transportAppender.list.stream().map(AnalysisModelTransportTest::completeLog).toList(),count.get(),wire.get());
        } finally {logger.detachAppender(appender);root.detachAppender(transportAppender);appender.stop();transportAppender.stop();server.stop(0);}
    }

    /** 固定失败出口必须保持一次传输；检查诊断与用户提示均不包含提示词、端点、凭据或请求标识。 */
    private static void assertFailure(Call call,String expected) {
        assertNull(call.reply());assertNotNull(call.failure());assertEquals(503,call.failure().getCode());assertEquals(1,call.calls());
        assertTrue(call.failure().getMessage().contains(expected),call.failure().getMessage());
        assertHidden(call,"private-test-request","server instructions","local-contract-test","127.0.0.1","server-test-request-id");
    }
    private static void assertHidden(Call call,String... values) {
        String all=String.join("\n",call.logs())+String.join("\n",call.transportLogs())+(call.failure()==null?"":call.failure().getMessage());
        for(String value:values)assertFalse(all.contains(value),"不应记录自由文本："+value);
    }
    private static JsonNode diagnostic(Call call) throws Exception {
        assertEquals(1,call.logs().size(),call.logs().toString());String log=call.logs().get(0);int start=log.indexOf("diagnostic=");assertTrue(start>=0,log);
        return JsonUtil.strictMapper().readTree(log.substring(start+"diagnostic=".length()));
    }
    /** SDK若把自由正文放入异常堆栈而非消息模板，根日志断言也必须发现。 */
    private static String completeLog(ILoggingEvent event) {
        return event.getFormattedMessage()+(event.getThrowableProxy()==null?"":"\n"+ThrowableProxyUtil.asString(event.getThrowableProxy()));
    }
    private static String completion() {
        return JsonUtil.toJson(Map.of("id","synthetic-completion","object","chat.completion","created",1,"model","local-test-model",
                "choices",List.of(Map.of("index",0,"message",Map.of("role","assistant","content","{\"approved\":true}"),"finish_reason","stop")),
                "usage",Map.of("prompt_tokens",120,"completion_tokens",30,"total_tokens",150,"prompt_tokens_details",Map.of("cached_tokens",15))));
    }
    private static String withPadding(String json,int length) throws Exception {
        var root=(com.fasterxml.jackson.databind.node.ObjectNode)JsonUtil.strictMapper().readTree(json);root.put("system_fingerprint","private-response-padding"+"x".repeat(length));
        return JsonUtil.toJson(root);
    }
    private static ClientHttpResponse response(InputStream stream,HttpHeaders headers) throws IOException {
        var response=mock(ClientHttpResponse.class);when(response.getStatusCode()).thenReturn(HttpStatus.OK);when(response.getStatusText()).thenReturn("OK");
        when(response.getHeaders()).thenReturn(headers);when(response.getBody()).thenReturn(stream);return response;
    }
    private static String bodyFormat(AnalysisModelDiagnostics diagnostics) {return JsonUtil.MAPPER.valueToTree(diagnostics.summary()).path("body").path("format").asText();}
    private static byte[] exactBudgetJson(){return ("{\"v\":\""+"x".repeat(AnalysisModelDiagnostics.MAX_BODY_BYTES-8)+"\"}").getBytes(StandardCharsets.UTF_8);}
    private static String hash(byte[] bytes) throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}

    /**
     * 一次受控HTTP调用的证据；不涉及真实模型端点或真实密钥。
     * @param reply 调用成功时的结构化结果，否则为空
     * @param failure 调用失败时的安全异常，否则为空
     * @param logs 仅当前模型适配器输出的诊断日志
     * @param transportLogs 当前调用的根日志，用于验证SDK不记录提示词或自由响应字段
     * @param calls 服务端实际收到的请求次数
     * @param wire 服务端收到的实际请求字节，仅测试内存中使用
     */
    private record Call(AnalysisModel.Reply reply,ApiException failure,List<String> logs,List<String> transportLogs,int calls,byte[] wire) { }

    /** 统计真正消耗的底层字节，区分诊断预读与交还SDK后的读取。 */
    private static final class CountedInput extends ByteArrayInputStream {
        private int readBytes;
        private CountedInput(byte[] bytes){super(bytes);}
        @Override public synchronized int read(){int value=super.read();if(value>=0)readBytes++;return value;}
        @Override public synchronized int read(byte[] bytes,int offset,int length){int count=super.read(bytes,offset,length);if(count>0)readBytes+=count;return count;}
    }

    /** 模拟包含私有标识的自定义类型；自由消息和toString访问均直接失败，保证生产日志只读取类型及cause。 */
    private static final class PrivateCredentialTransportException extends IOException {
        private PrivateCredentialTransportException(Throwable cause){super("private-top-token server-test-request-id",cause);}
        @Override public String getMessage(){throw new AssertionError("禁止诊断读取自由异常消息");}
        @Override public String toString(){throw new AssertionError("禁止诊断渲染异常原文");}
    }

    /** 计数每一层cause读取，验证深链在固定上限终止，且不调用自定义equals或hashCode。 */
    private static final class CountingCause extends RuntimeException {
        private final Throwable next;
        private final AtomicInteger reads;
        private CountingCause(Throwable next,AtomicInteger reads){this.next=next;this.reads=reads;}
        @Override public synchronized Throwable getCause(){reads.incrementAndGet();return next;}
        @Override public boolean equals(Object other){throw new AssertionError("必须按对象身份检测循环");}
        @Override public int hashCode(){throw new AssertionError("必须按对象身份检测循环");}
    }
}
