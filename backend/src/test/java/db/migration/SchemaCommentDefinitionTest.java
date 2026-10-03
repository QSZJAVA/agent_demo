package db.migration;

import org.junit.jupiter.api.Test;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

/** 字段定义解析回归：注释关键字出现在数据字符串中时必须保留。 */
class SchemaCommentDefinitionTest {
    @Test void checksumIsStableAcrossWindowsAndLinuxLineEndings() {
        String json="{\n  \"comment\": \"字段含义\"\n}\n";
        int checksum=V21__Schema_comments.checksum(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(checksum,V21__Schema_comments.checksum(json.replace("\n","\r\n").getBytes(StandardCharsets.UTF_8)));
        assertNotEquals(checksum,V21__Schema_comments.checksum(json.replace("字段含义","其他含义").getBytes(StandardCharsets.UTF_8)));
    }
    @Test void preservesCommentWordsAndEscapedQuotesInDefault() throws Exception {
        String definition="varchar(80) NOT NULL DEFAULT 'COMMENT \\'kept\\'' COMMENT 'old'";
        assertEquals("varchar(80) NOT NULL DEFAULT 'COMMENT \\'kept\\''",V21__Schema_comments.withoutComment(definition,true));
    }
    @Test void handlesNoBackslashEscapesAndDoubledQuotes() throws Exception {
        String definition="varchar(80) DEFAULT 'COMMENT ''kept'' \\value' COMMENT 'old''note'";
        assertEquals("varchar(80) DEFAULT 'COMMENT ''kept'' \\value'",V21__Schema_comments.withoutComment(definition,false));
    }
    @Test void preservesGenerationAndDefaultExpressions() throws Exception {
        String generated="varchar(32) GENERATED ALWAYS AS ((case when (`status` = 'COMMENT') then `plan_id` else NULL end)) STORED";
        assertEquals(generated,V21__Schema_comments.withoutComment(generated+" COMMENT '说明'",true));
        assertEquals("datetime(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)",V21__Schema_comments.withoutComment("datetime(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '说明'",true));
    }
    @Test void rejectsUnclosedQuotesInsteadOfApplyingDamagedDefinition() {
        assertThrows(SQLException.class,()->V21__Schema_comments.withoutComment("varchar(32) DEFAULT 'unfinished",true));
    }
}
