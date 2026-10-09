package com.example.report.assistant;

import com.example.report.catalog.CatalogEntry;
import com.example.report.catalog.query.FieldInfo;
import com.example.report.common.JsonUtil;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 字段词义线索的权限、类型、边界及最小披露检查；不以合成命中证明模型理解正确。 */
class BusinessTermEvidenceTest {
    private CatalogEntry report(String id,FieldInfo... fields) {
        var report=mock(CatalogEntry.class);when(report.reportId()).thenReturn(id);when(report.fields()).thenReturn(List.of(fields));return report;
    }
    @Test void declaredBusinessValuesAreGroundingWithoutDraftOrTargetRows() {
        var reports=List.of(report("r",new FieldInfo("itemName","string","业务项目"),new FieldInfo("docNo","string","单据号")));
        var facts=Map.<String,Object>of("query",Map.of("view","LIST"),"totalCount",2,"rows",List.of(
                Map.of("reportId","r","recordId","42","docNo","D42","itemName","检测仪"),
                Map.of("reportId","r","recordId","43","docNo","D43","itemName","检测仪")));
        var result=BusinessTermEvidence.extract("找一下检测仪的数据 D42",facts,reports);
        assertEquals(List.of(new BusinessTermEvidence.Term("r","itemName","检测仪")),result.matches());assertFalse(result.truncated());
        String encoded=JsonUtil.toJson(result);assertFalse(encoded.contains("D42"));assertFalse(encoded.contains("recordId"));
        assertFalse(encoded.contains("LIST"));assertFalse(encoded.contains("totalCount"));
    }
    @Test void undeclaredFieldsInvisibleReportsAndNumericSubstringsAreNotBusinessTerms() {
        var reports=List.of(report("allowed",new FieldInfo("label","string","名称"),new FieldInfo("amount","decimal","金额")));
        var facts=Map.<String,Object>of("rows",List.of(
                Map.of("reportId","allowed","label","设备","password","秘密","amount","680"),
                Map.of("reportId","hidden","label","隐藏设备")));
        assertEquals(List.of(new BusinessTermEvidence.Term("allowed","label","设备")),
                BusinessTermEvidence.extract("设备 秘密 6800 隐藏设备",facts,reports).matches());
    }
    @Test void asciiNamesMustBeCompleteWordsAndNonMatchingDataDoesNotLeak() {
        var reports=List.of(report("r",new FieldInfo("name","string","名称")));
        var facts=Map.<String,Object>of("rows",List.of(Map.of("reportId","r","name","PART12"),Map.of("reportId","r","name","不可披露")));
        assertTrue(BusinessTermEvidence.extract("查询PART123",facts,reports).matches().isEmpty());
        assertEquals("PART12",BusinessTermEvidence.extract("查询PART12。",facts,reports).matches().get(0).value());
    }
    @Test void missingAndTruncatedEvidenceNeverClaimsACompleteVocabulary() {
        var reports=List.of(report("r",new FieldInfo("name","string","名称")));
        assertTrue(BusinessTermEvidence.extract("未知对象",Map.of(),reports).matches().isEmpty());
        var rows=new ArrayList<Map<String,String>>();var message=new StringBuilder();
        for(int i=0;i<70;i++){String value="ITEM"+String.format("%03d",i);message.append(value).append(' ');rows.add(Map.of("reportId","r","name",value));}
        var result=BusinessTermEvidence.extract(message.toString(),Map.of("rows",rows),reports);
        assertEquals(64,result.matches().size());assertTrue(result.truncated());
    }
}
