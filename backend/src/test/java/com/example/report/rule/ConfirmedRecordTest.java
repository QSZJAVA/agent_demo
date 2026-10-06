package com.example.report.rule;
import com.example.report.catalog.query.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
/** 覆盖多种字段和表示方式的确认事实边界；不依赖真实客户字段或固定问法。 */
class ConfirmedRecordTest {
    Candidate frozen(String name,String type,String value) {
        return new Candidate("r","报表","key","D1","A","摘要",new BigDecimal("50.00"),LocalDate.of(2026,1,1),1L,"规则",1,"x",1L,null,List.of(new FieldFact(name,type,value)));
    }
    FactRow current(String name,Object value) {var facts=new HashMap<String,Object>();facts.put(name,value);return new FactRow("key","D1","A","摘要",new BigDecimal("50"),LocalDate.of(2026,1,1),facts);}
    @ParameterizedTest @CsvSource({"creditBand,decimal,96000,96000.00","units,long,3,3","rank,integer,0,0","shipOn,date,2026-02-01,2026-02-01","approved,boolean,false,false","description,string,任意客户,任意客户"})
    void equivalentCurrentValuesPreserveConfirmation(String name,String type,String oldValue,String newValue) {
        assertTrue(ConfirmedRecord.matches(frozen(name,type,oldValue),current(name,newValue),List.of(new FieldInfo(name,type,"字段"))));
    }
    @ParameterizedTest @CsvSource({"creditBand,decimal,50000,128000","units,long,3,4","rank,integer,0,1","shipOn,date,2026-02-01,2026-02-02","approved,boolean,false,true","description,string,甲客户,乙客户"})
    void anyConfirmedFieldChangeRequiresNewConfirmation(String name,String type,String oldValue,String newValue) {
        assertFalse(ConfirmedRecord.matches(frozen(name,type,oldValue),current(name,newValue),List.of(new FieldInfo(name,type,"字段"))));
    }
    @Test void nullMissingAndDuplicateFieldsCannotBeConflated() {
        var spec=List.of(new FieldInfo("v","string","值"));
        assertTrue(ConfirmedRecord.matches(frozen("v","string",null),current("v",null),spec));
        assertFalse(ConfirmedRecord.matches(frozen("v","string",null),current("v","new"),spec));
        assertFalse(ConfirmedRecord.matches(frozen("v","string","old"),current("v",null),spec));
        assertFalse(ConfirmedRecord.matches(frozen("v","string","old"),current("other","old"),spec));
        assertFalse(ConfirmedRecord.matches(frozen("v","string","old"),current("v","old"),List.of(spec.get(0),spec.get(0))));
    }
}
