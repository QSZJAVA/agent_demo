package com.example.report.config;

import com.example.report.common.JsonUtil;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DuplicateKeyException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 独立MySQL空库验证最新基础表与调查表的全部注释，不要求旧数据升级；不调用模型或外部ERP。 */
@EnabledIfEnvironmentVariable(named="TRACE_IT",matches="true")
class SchemaCommentsDatabaseTest {
    static String env(String key,String fallback) { return System.getenv().getOrDefault(key,fallback); }
    interface DatabaseCheck { void run(DriverManagerDataSource ds, JdbcTemplate jdbc) throws Exception; }
    /** 独占 UUID 数据库；删除前同时校验名称前缀和连接的当前数据库。 */
    private void database(DatabaseCheck check) throws Exception {
        String schema="schema_comments_it_"+UUID.randomUUID().toString().replace("-","");
        var ds=new DriverManagerDataSource("jdbc:mysql://"+env("TRACE_DB_HOST","127.0.0.1")+":"+env("TRACE_DB_PORT","3306")+"/"+schema+"?createDatabaseIfNotExist=true&serverTimezone=Asia/Shanghai",env("TRACE_DB_USER","root"),env("TRACE_DB_PASSWORD",""));
        var jdbc=new JdbcTemplate(ds);
        try {
            check.run(ds,jdbc);
        } finally {
            if(schema.matches("schema_comments_it_[a-f0-9]{32}") && schema.equals(jdbc.queryForObject("SELECT DATABASE()",String.class))) jdbc.execute("DROP DATABASE `"+schema+"`");
        }
    }

    @Test void freshDatabaseAndAfterMigrateBusinessTablesHaveCompleteComments() throws Exception {
        database((ds,jdbc) -> {
            Flyway.configure().dataSource(ds).load().migrate();
            assertComments(jdbc);
        });
    }

    private static void assertComments(JdbcTemplate jdbc) throws Exception {
        JsonNode specs;
        try(var stream=SchemaCommentsDatabaseTest.class.getResourceAsStream("/db/schema-comments.json")) {
            assertNotNull(stream); specs=JsonUtil.MAPPER.readTree(stream);
        }
        assertEquals(38,specs.size()); int checked=0;
        var names=specs.fieldNames();
        while(names.hasNext()) {
            String table=names.next(); JsonNode spec=specs.get(table);
            assertEquals(spec.get("comment").asText(),jdbc.queryForObject("SELECT TABLE_COMMENT FROM information_schema.tables WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?",String.class,table),table);
            var fields=spec.get("columns").fields();
            while(fields.hasNext()) {
                var field=fields.next(); assertFalse(field.getValue().asText().isBlank());
                assertEquals(field.getValue().asText(),jdbc.queryForObject("SELECT COLUMN_COMMENT FROM information_schema.columns WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME=?",String.class,table,field.getKey()),table+"."+field.getKey()); checked++;
            }
        }
        assertEquals(456,checked);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME NOT IN ('flyway_schema_history','external_business') AND COLUMN_COMMENT=''",Integer.class));
    }
}
