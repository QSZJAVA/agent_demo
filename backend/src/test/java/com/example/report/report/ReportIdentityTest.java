package com.example.report.report;
import com.example.report.common.*;
import com.example.report.entity.*;
import com.example.report.operations.SensitiveData;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class ReportIdentityTest {
    @Test void everyReportKeepsAdjacentLargeIdsAsStringsThroughResponseRedaction() {
        var sales=new SalesReport(); sales.setId(9007199254740993L);
        var adjacent=new SalesReport(); adjacent.setId(9007199254740992L);
        var receivable=new ReceivableReport();receivable.setId(Long.MAX_VALUE);
        var expense=new ExpenseReport();expense.setId(9007199254740993L);
        var node=SensitiveData.value(Result.ok(List.of(sales,adjacent,receivable,expense)));
        assertTrue(node.at("/data/0/id").isTextual());
        assertEquals("9007199254740993",node.at("/data/0/id").asText());
        assertEquals("9007199254740992",node.at("/data/1/id").asText());
        assertEquals(Long.toString(Long.MAX_VALUE),node.at("/data/2/id").asText());
        assertEquals("9007199254740993",node.at("/data/3/id").asText());
    }
}
