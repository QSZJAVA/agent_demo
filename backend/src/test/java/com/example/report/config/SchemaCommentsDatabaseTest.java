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

/** 独立 MySQL 库验证新库与 V20 存量升级的注释、元数据、索引和数据；不调用模型或外部 ERP。 */
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
            Flyway.configure().dataSource(ds).callbacks(new DemoDataResetCallback(false)).load().migrate();
            assertComments(jdbc);
        });
    }

    @Test void upgradePreservesDefinitionsIndexesDefaultsGeneratedColumnsAndData() throws Exception {
        database((ds,jdbc) -> {
            Flyway.configure().dataSource(ds).target("20").callbacks(new DemoDataResetCallback(false)).load().migrate();
            // 默认值内的 COMMENT、引号、反斜杠不能被当成注释属性；扩展字段也不能被改写。
            jdbc.execute("ALTER TABLE business_metric ADD extension_value VARCHAR(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'COMMENT ''kept'' \\\\value' COMMENT '保留扩展字段'");
            jdbc.update("INSERT INTO business_metric(tenant_id,operation,report_id,version,outcome,duration_ms,created_at) VALUES ('T001','COMMENT_TEST','*','1','SUCCESS',17,NOW(3))");
            jdbc.update("INSERT INTO dispatch_job(id,tenant_id,user_id,plan_id,action,expected_version,idempotency_key,payload_json,expires_at) VALUES ('comment-job','T001','tester','comment-plan','CONFIRM',5,'comment-key-00001','{}',TIMESTAMPADD(SECOND,600,NOW(3)))");
            jdbc.execute("CREATE TABLE external_business(id INT PRIMARY KEY COMMENT '外部主键') COMMENT='外部系统维护的表'");
            String externalBefore=jdbc.queryForMap("SHOW CREATE TABLE external_business").get("Create Table").toString();
            var columnsBefore=columns(jdbc); var indexesBefore=indexes(jdbc);
            var tablesBefore=tables(jdbc); var dataBefore=data(jdbc);
            var flyway=Flyway.configure().dataSource(ds).callbacks(new DemoDataResetCallback(false)).load();
            flyway.migrate(); flyway.validate();
            assertEquals(columnsBefore,columns(jdbc),"列定义除注释外不得改变");
            assertEquals(indexesBefore,indexes(jdbc),"索引与唯一约束不得改变");
            assertEquals(tablesBefore,tables(jdbc),"存储引擎和表字符集不得改变");
            assertEquals(dataBefore,data(jdbc),"存量数据和生成列结果不得改变");
            assertEquals(externalBefore,jdbc.queryForMap("SHOW CREATE TABLE external_business").get("Create Table"));
            assertComments(jdbc);
            assertEquals("comment-plan",jdbc.queryForObject("SELECT active_plan FROM dispatch_job WHERE id='comment-job'",String.class));
            assertThrows(DuplicateKeyException.class,()->jdbc.update("INSERT INTO dispatch_job(id,tenant_id,user_id,plan_id,action,idempotency_key,payload_json,expires_at) VALUES ('another-job','T001','tester','comment-plan','CONFIRM','comment-key-00002','{}',NOW(3))"));
        });
    }

    private static List<Map<String,Object>> columns(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT TABLE_NAME,COLUMN_NAME,ORDINAL_POSITION,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT,EXTRA,CHARACTER_SET_NAME,COLLATION_NAME,NUMERIC_PRECISION,NUMERIC_SCALE,DATETIME_PRECISION,GENERATION_EXPRESSION FROM information_schema.columns WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME<>'flyway_schema_history' ORDER BY TABLE_NAME,ORDINAL_POSITION");
    }
    private static List<Map<String,Object>> indexes(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT TABLE_NAME,INDEX_NAME,NON_UNIQUE,SEQ_IN_INDEX,COLUMN_NAME,COLLATION,SUB_PART,NULLABLE,INDEX_TYPE,IS_VISIBLE,EXPRESSION FROM information_schema.statistics WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME<>'flyway_schema_history' ORDER BY TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX");
    }
    private static List<Map<String,Object>> tables(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT TABLE_NAME,ENGINE,TABLE_COLLATION,AUTO_INCREMENT FROM information_schema.tables WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME<>'flyway_schema_history' ORDER BY TABLE_NAME");
    }
    private static Map<String,String> data(JdbcTemplate jdbc) {
        var snapshots=new TreeMap<String,String>();
        for(var table:tables(jdbc)) {
            String name=table.get("TABLE_NAME").toString();
            // 按序列化后的整行排序，保留重复数据且不依赖 ALTER 前后的物理行顺序。
            snapshots.put(name,JsonUtil.toJson(jdbc.queryForList("SELECT * FROM `"+name+"`").stream().map(JsonUtil::toJson).sorted().toList()));
        }
        return snapshots;
    }
    private static void assertComments(JdbcTemplate jdbc) throws Exception {
        JsonNode specs;
        try(var stream=SchemaCommentsDatabaseTest.class.getResourceAsStream("/db/migration/V21__schema_comments.json")) {
            assertNotNull(stream); specs=JsonUtil.MAPPER.readTree(stream);
        }
        assertEquals(35,specs.size()); int checked=0;
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
        assertEquals(398,checked);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME NOT IN ('flyway_schema_history','external_business') AND COLUMN_COMMENT=''",Integer.class));
    }
}
