package com.example.report.config;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 演示数据重置（demo.reset-on-startup=true 时启用）：每次 Flyway 迁移完成后执行 db/demo/demo-data.sql，
 * 把报表演示数据、报表目录、派单规则恢复为示例状态。挂在 Flyway 的 afterMigrate 上，
 * 保证它一定在表结构就绪之后、规则缓存和目录缓存加载之前执行。生产环境必须关闭。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "demo", name = "reset-on-startup", havingValue = "true")
public class DemoDataResetCallback implements Callback {

    private static final String SCRIPT = "db/demo/demo-data.sql";

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.AFTER_MIGRATE;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        ScriptUtils.executeSqlScript(context.getConnection(),
                new EncodedResource(new ClassPathResource(SCRIPT), StandardCharsets.UTF_8));
        log.warn("演示数据、报表目录、派单规则已重置为示例状态（demo.reset-on-startup=true），生产环境请设置 DEMO_RESET_ON_STARTUP=false");
    }

    @Override
    public String getCallbackName() {
        return "demoDataReset";
    }
}
