package com.example.report.investigation;

import com.example.report.common.JsonUtil;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.report.investigation.InvestigationTestSupport.*;

/** 报告结构与事实支持回归；不用合法JSON或存在引用替代业务正确性判断。 */
class InvestigationProtocolTest {
    final InvestigationProperties props=new InvestigationProperties();
    final InvestigationReportValidator validator=new InvestigationReportValidator();
    InvestigationSession session() {
        var s=InvestigationTestSupport.session(List.of(item("I1","UNKNOWN","TRANSPORT_TIMEOUT")),props);
        s.evidence.put("E1",new InvestigationEvidenceStore.Evidence("ITEM_SNAPSHOT",List.of("I1"),Map.of("items",List.of(Map.of("itemRef","I1","status","UNKNOWN"))),false));return s;
    }
    @Test void unknownIsNotBusinessFailure() {assertThrows(InvestigationFailure.class,() -> validator.validate(report("I1","BUSINESS_REJECTED","VERIFIED",List.of("E1")),"stop",session()));}
    @Test void inventedEvidenceIsRejected() {assertThrows(InvestigationFailure.class,() -> validator.validate(report("I1","RESULT_UNKNOWN","VERIFIED",List.of("E999")),"stop",session()));}
    @Test void evidenceForAnotherItemIsRejected() {var s=session();s.evidence.put("E1",new InvestigationEvidenceStore.Evidence("ITEM_SNAPSHOT",List.of("I2"),Map.of(),false));assertThrows(InvestigationFailure.class,() -> validator.validate(report("I1","RESULT_UNKNOWN","VERIFIED",List.of("E1")),"stop",s));}
    @Test void unknownSupportedByCurrentFactIsAccepted() {assertEquals("以下结论仅描述调查时取得的证据。本次调查只读，未执行修复、重发或状态核对写入。",validator.validate(report("I1","RESULT_UNKNOWN","VERIFIED",List.of("E1")),"stop",session()).get("summary"));}
    @Test void remoteSuccessNeedsRemoteEvidenceAndUnresolvedLocalState() {
        var s=session();String output=report("I1","REMOTE_SUCCESS_LOCAL_UNRESOLVED","VERIFIED",List.of("E1","E2"));
        assertThrows(InvestigationFailure.class,() -> validator.validate(output,"stop",s));
        s.evidence.put("E2",new InvestigationEvidenceStore.Evidence("MCP_LOOKUP",List.of("I1"),Map.of("items",List.of(Map.of("itemRef","I1","status","SUCCESS"))),false));assertNotNull(validator.validate(output,"stop",s));
    }
    @Test void twoRemoteObservationsCannotStandInForLocalUnresolvedEvidence() {
        var s=session();s.evidence.clear();
        s.evidence.put("E1",new InvestigationEvidenceStore.Evidence("MCP_LOOKUP",List.of("I1"),Map.of("items",List.of(Map.of("itemRef","I1","status","UNKNOWN"))),false));
        s.evidence.put("E2",new InvestigationEvidenceStore.Evidence("MCP_LOOKUP",List.of("I1"),Map.of("items",List.of(Map.of("itemRef","I1","status","SUCCESS"))),false));
        assertThrows(InvestigationFailure.class,() -> validator.validate(report("I1","REMOTE_SUCCESS_LOCAL_UNRESOLVED","VERIFIED",List.of("E1","E2")),"stop",s));
    }
    @Test void successCitationRepairNamesBothObservedSourcesWithoutDowngradingTheKnownFact() {
        var s=session();s.evidence.put("E5",new InvestigationEvidenceStore.Evidence("MCP_LOOKUP",List.of("I1"),Map.of("items",List.of(Map.of("itemRef","I1","status","SUCCESS"))),false));
        s.evidence.put("E4",new InvestigationEvidenceStore.Evidence("MCP_LOOKUP",List.of("I2"),Map.of("items",List.of(Map.of("itemRef","I2","status","SUCCESS"))),false));
        for(String reason:List.of("REMOTE_SUCCESS_LOCAL_UNRESOLVED","RESULT_UNKNOWN")) {
            var failure=assertThrows(InvestigationFailure.class,() -> validator.validate(report("I1",reason,"VERIFIED",List.of("E5")),"stop",s));
            assertTrue(failure.getMessage().contains("证据组合[E1, E5]"));assertFalse(failure.getMessage().contains("E4"));
            assertFalse(failure.getMessage().contains("UNKNOWN应报告RESULT_UNKNOWN"));
        }
        assertNotNull(validator.validate(report("I1","REMOTE_SUCCESS_LOCAL_UNRESOLVED","VERIFIED",List.of("E1","E5")),"stop",s));
    }
    @Test void citationRepairCannotInventMissingLocalEvidence() {
        var s=session();s.evidence.clear();
        s.evidence.put("E5",new InvestigationEvidenceStore.Evidence("MCP_LOOKUP",List.of("I1"),Map.of("items",List.of(Map.of("itemRef","I1","status","SUCCESS"))),false));
        var failure=assertThrows(InvestigationFailure.class,() -> validator.validate(report("I1","REMOTE_SUCCESS_LOCAL_UNRESOLVED","VERIFIED",List.of("E5")),"stop",s));
        assertTrue(failure.getMessage().contains("尚无完整支持引用"));assertFalse(failure.getMessage().contains("证据组合"));
    }
    @Test void successMessageCannotOverrideUnknownRemoteStatusAndFeedbackNamesObservedFact() {
        var s=session();s.evidence.put("E2",new InvestigationEvidenceStore.Evidence("MCP_LOOKUP",List.of("I1"),Map.of("items",List.of(Map.of("itemRef","I1","status","UNKNOWN","message","派单成功"))),false));
        var failure=assertThrows(InvestigationFailure.class,() -> validator.validate(report("I1","REMOTE_SUCCESS_LOCAL_UNRESOLVED","VERIFIED",List.of("E1","E2")),"stop",s));
        assertTrue(failure.getMessage().contains("E2:UNKNOWN"));assertTrue(failure.getMessage().contains("RESULT_UNKNOWN"));
        assertNotNull(validator.validate(report("I1","RESULT_UNKNOWN","INSUFFICIENT",List.of("E1","E2")),"stop",s));
    }
    @Test void oldSkippedEventCannotClassifyCurrentUnknownAttemptAsSkipped() {
        var s=session();s.evidence.put("E2",new InvestigationEvidenceStore.Evidence("EXECUTION_EVENT",List.of("I1"),Map.of("items",List.of(Map.of("itemRef","I1","outcome","SKIPPED","attemptCount",0))),false));
        assertThrows(InvestigationFailure.class,() -> validator.validate(report("I1","PRECHECK_SKIPPED","VERIFIED",List.of("E2")),"stop",s));
    }
    @Test void missingRuleCannotHideObservedRemoteSuccessOrBusinessFailure() {
        for(String remote:List.of("SUCCESS","FAILED","NOT_FOUND")) {
            var s=session();s.evidence.put("E2",new InvestigationEvidenceStore.Evidence("MCP_LOOKUP",List.of("I1"),Map.of("items",List.of(Map.of("itemRef","I1","status",remote))),false));
            s.evidence.put("E3",new InvestigationEvidenceStore.Evidence("RULE_SNAPSHOT",List.of("I1"),Map.of("items",List.of(Map.of("itemRef","I1","missing",true))),false));
            assertThrows(InvestigationFailure.class,() -> validator.validate(report("I1","EVIDENCE_MISSING","INSUFFICIENT",List.of("E3")),"stop",s));
            assertThrows(InvestigationFailure.class,() -> validator.validate(report("I1","UNDETERMINED","INSUFFICIENT",List.of()),"stop",s));
        }
    }
    @Test void missingRuleCannotEraseObservedCurrentFailureOrSkip() {
        for(String status:List.of("FAILED","SKIPPED")) {
            var s=InvestigationTestSupport.session(List.of(item("I1",status,"BUSINESS_REJECTED")),props);
            s.evidence.put("E1",new InvestigationEvidenceStore.Evidence("ITEM_SNAPSHOT",List.of("I1"),Map.of("items",s.items),false));
            s.evidence.put("E2",new InvestigationEvidenceStore.Evidence("RULE_SNAPSHOT",List.of("I1"),Map.of("items",List.of(Map.of("itemRef","I1","missing",true))),false));
            assertThrows(InvestigationFailure.class,() -> validator.validate(report("I1","EVIDENCE_MISSING","INSUFFICIENT",List.of("E2")),"stop",s));
        }
    }
    @Test void directRemoteSuccessCannotBeDowngradedBecauseItsCauseIsUnknown() {
        var s=session();s.evidence.put("E2",new InvestigationEvidenceStore.Evidence("MCP_LOOKUP",List.of("I1"),Map.of("items",List.of(Map.of("itemRef","I1","status","SUCCESS"))),false));
        var failure=assertThrows(InvestigationFailure.class,() -> validator.validate(report("I1","REMOTE_SUCCESS_LOCAL_UNRESOLVED","INSUFFICIENT",List.of("E1","E2")),"stop",s));
        assertTrue(failure.getMessage().contains("certainty设为VERIFIED"));
        assertNotNull(validator.validate(report("I1","REMOTE_SUCCESS_LOCAL_UNRESOLVED","VERIFIED",List.of("E1","E2")),"stop",s));
    }
    @Test void partialAndTrailingJsonAreRejected() {var s=session();assertThrows(InvestigationFailure.class,() -> validator.validate(report("I1","RESULT_UNKNOWN","VERIFIED",List.of("E1")),"length",s));assertThrows(InvestigationFailure.class,() -> validator.validate(report("I1","RESULT_UNKNOWN","VERIFIED",List.of("E1"))+"{}","stop",s));}
    @Test void unknownFieldsAndMissingItemsAreRejected() {
        var s=session();var value=JsonUtil.toMap(report("I1","RESULT_UNKNOWN","VERIFIED",List.of("E1")));value.put("execute",true);assertThrows(InvestigationFailure.class,() -> validator.validate(JsonUtil.toJson(value),"stop",s));
        var two=InvestigationTestSupport.session(List.of(item("I1","UNKNOWN",null),item("I2","FAILED","RULE_REJECTED")),props);assertThrows(InvestigationFailure.class,() -> validator.validate(report("I1","UNDETERMINED","INSUFFICIENT",List.of()),"stop",two));
    }
    @Test void unsupportedBudgetConfigurationFailsEarly() {props.setMaxModelCalls(6);assertThrows(IllegalArgumentException.class,props::validate);}
    @Test void toolSchemaContainsOnlyFiveReadToolsAndNoIdentityFields() {String schema=InvestigationTools.schemaJson();assertEquals(5,InvestigationTools.definitions().size());assertFalse(schema.contains("tenantId"));assertFalse(schema.contains("requestId"));assertFalse(InvestigationTools.allowed("dispatch_submit"));}
    @Test void observedRemoteSuccessCannotBeHiddenByCitingOnlyLocalUnknown() {
        var s=session();s.evidence.put("E2",new InvestigationEvidenceStore.Evidence("MCP_LOOKUP",List.of("I1"),Map.of("items",List.of(Map.of("itemRef","I1","status","SUCCESS"))),false));
        assertThrows(InvestigationFailure.class,() -> validator.validate(report("I1","RESULT_UNKNOWN","INSUFFICIENT",List.of("E1")),"stop",s));
    }
    @Test void missingRequestMustBeReflectedInsteadOfCherryPickingUnknownState() {
        var s=session();s.evidence.put("E2",new InvestigationEvidenceStore.Evidence("MCP_LOOKUP",List.of("I1"),Map.of("items",List.of(Map.of("itemRef","I1","error","NO_REQUEST_ID"))),false));
        assertThrows(InvestigationFailure.class,() -> validator.validate(report("I1","RESULT_UNKNOWN","VERIFIED",List.of("E1")),"stop",s));
        assertNotNull(validator.validate(report("I1","EVIDENCE_MISSING","INSUFFICIENT",List.of("E2")),"stop",s));
    }
    @Test void objectOrderingCannotChangeSourceFingerprintOrContractHashes() {
        var a=new LinkedHashMap<String,Object>();a.put("status","UNKNOWN");a.put("counts",Map.of("total",1,"failed",1));
        var b=new LinkedHashMap<String,Object>();b.put("counts",Map.of("failed",1,"total",1));b.put("status","UNKNOWN");
        assertEquals(InvestigationFacts.fingerprint(a),InvestigationFacts.fingerprint(b));
        assertEquals(InvestigationJson.hash(a),InvestigationJson.hash(b));
    }
    @Test void remoteFailedMustBeReportedAndCanRecommendSettlingLocalUnknown() {
        var s=session();s.evidence.put("E2",new InvestigationEvidenceStore.Evidence("MCP_LOOKUP",List.of("I1"),Map.of("items",List.of(Map.of("itemRef","I1","status","FAILED","errorCode","RECORD_CHANGED"))),false));
        assertTrue(assertThrows(InvestigationFailure.class,() -> validator.validate(report("I1","RESULT_UNKNOWN","INSUFFICIENT",List.of("E1","E2")),"stop",s)).getMessage().contains("BUSINESS_REJECTED"));
        var value=JsonUtil.toMap(report("I1","BUSINESS_REJECTED","VERIFIED",List.of("E2")));
        @SuppressWarnings("unchecked") var findings=(List<Map<String,Object>>)value.get("findings");findings.get(0).put("nextStep","USE_EXISTING_RECONCILE");
        assertNotNull(validator.validate(JsonUtil.toJson(value),"stop",s));
    }
    @Test void hypothesisWithoutObservedClueIsRejected() {
        assertThrows(InvestigationFailure.class,() -> validator.validate(report("I1","UNDETERMINED","HYPOTHESIS",List.of()),"stop",session()));
    }
    @Test void everyRuleTextIsMaskedBeforeToolResultAndEvidenceWithoutChangingSnapshot() throws Exception {
        for(int length:List.of(40,180,181,220)) {
            String secret="13800138000 test@example.com ",original=secret+"中".repeat(length-secret.length());
            var rule=new LinkedHashMap<String,Object>();for(String key:List.of("name","ruleName","description","expression","source")) rule.put(key,original);
            var row=item("I1","SKIPPED","RULE_CHANGED");row.put("rule",rule);var s=InvestigationTestSupport.session(List.of(row),props);
            var result=tools(repository(),props,Map.of()).execute(s,"investigation_rule_snapshots",JsonUtil.MAPPER.readTree("{\"itemRefs\":[\"I1\"]}"));
            String output=JsonUtil.toJson(result),saved=JsonUtil.toJson(s.evidence);
            for(String raw:List.of("13800138000","test@example.com")) {assertFalse(output.contains(raw));assertFalse(saved.contains(raw));}
            assertTrue(output.contains("已脱敏"));assertEquals(com.example.report.operations.SensitiveData.text(original).length()>180,result.get("truncated"));
            for(Object value:rule.values()) assertEquals(original,value);
        }
        for(int length:List.of(179,180,181)) {
            var row=item("I1","SKIPPED",null);row.put("rule",Map.of("expression","中".repeat(length)));
            var s=InvestigationTestSupport.session(List.of(row),props);
            assertEquals(length>180,tools(repository(),props,Map.of()).execute(s,"investigation_rule_snapshots",JsonUtil.MAPPER.readTree("{\"itemRefs\":[\"I1\"]}")).get("truncated"));
        }
    }
    @Test @SuppressWarnings("unchecked") void freeTextClaimsAreRejectedAtAllModelReportEntrances() {
        var s=session();String claim="派单已经成功，系统已经自动重新发送，无需核对";
        for(String location:List.of("summary","explanation","message")) {
            var value=JsonUtil.toMap(report("I1","RESULT_UNKNOWN","VERIFIED",List.of("E1")));
            if(location.equals("summary")) value.put("summary",claim);
            if(location.equals("explanation")) ((List<Map<String,Object>>)value.get("findings")).get(0).put("explanation",claim);
            if(location.equals("message")) value.put("unresolved",List.of(Map.of("itemRef","I1","topics",List.of("REMOTE_RESULT"),"message",claim)));
            assertThrows(InvestigationFailure.class,() -> validator.validate(JsonUtil.toJson(value),"stop",s));
        }
        var value=JsonUtil.toMap(report("I1","RESULT_UNKNOWN","VERIFIED",List.of("E1")));
        value.put("unresolved",List.of(Map.of("itemRef","I1","topics",List.of("REMOTE_RESULT"))));
        assertEquals("仍需通过原清单入口核查业务结果。",((List<Map<String,Object>>)validator.validate(JsonUtil.toJson(value),"stop",s).get("unresolved")).get(0).get("message"));
        value.put("unresolved",List.of(Map.of("itemRef","I1","topics",List.of("AUTO_RESEND"))));
        assertThrows(InvestigationFailure.class,() -> validator.validate(JsonUtil.toJson(value),"stop",s));
    }
}
