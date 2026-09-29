package com.example.report.config;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 仅首次空库迁移后初始化演示数据；已有数据库必须显式设置 demo.reset-on-startup=true 才会重置。
 * 空库判定在 V1 执行前完成，不以报表目录或规则是否有数据作为依据。
 */
@Slf4j
@Component
public class DemoDataResetCallback implements Callback {

    private static final String SCRIPT = "db/demo/demo-data.sql";
    private final boolean resetOnStartup;
    private boolean initializeFreshDatabase;

    public DemoDataResetCallback(@Value("${demo.reset-on-startup:false}") boolean resetOnStartup) {
        this.resetOnStartup = resetOnStartup;
    }

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.BEFORE_MIGRATE || event == Event.BEFORE_EACH_MIGRATE
                || event == Event.AFTER_MIGRATE;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        if (event == Event.BEFORE_MIGRATE) {
            initializeFreshDatabase = false;
            return;
        }
        if (event == Event.BEFORE_EACH_MIGRATE) {
            // Flyway creates its history table before BEFORE_MIGRATE. Check at V1 while
            // holding the migration lock, so only the instance that migrates V1 can seed.
            if (!resetOnStartup && context.getMigrationInfo() != null
                    && MigrationVersion.fromVersion("1").equals(context.getMigrationInfo().getVersion())) {
                initializeFreshDatabase = isFreshDatabase(context);
            }
            return;
        }
        if (event != Event.AFTER_MIGRATE || (!resetOnStartup && !initializeFreshDatabase)) return;
        initializeFreshDatabase = false;
        ScriptUtils.executeSqlScript(context.getConnection(),
                new EncodedResource(new ClassPathResource(SCRIPT), StandardCharsets.UTF_8));
        if (resetOnStartup) {
            log.warn("演示数据、报表目录、派单规则已重置为示例状态（demo.reset-on-startup=true），生产环境请设置 DEMO_RESET_ON_STARTUP=false");
        } else {
            log.info("首次空库迁移完成，已初始化演示业务表、报表目录与规则；后续启动保留已有数据");
        }
    }

    private boolean isFreshDatabase(Context context) {
        Connection connection = context.getConnection();
        String historyTable = context.getConfiguration().getTable();
        try (PreparedStatement tables = connection.prepareStatement(
                "SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name <> ? LIMIT 1")) {
            tables.setString(1, historyTable);
            try (ResultSet existing = tables.executeQuery()) {
                if (existing.next()) return false;
            }
            // SCHEMA is Flyway's own schema-creation marker. Any baseline, migration or
            // failed migration means this is an existing database and must be preserved.
            String quotedHistory = "`" + historyTable.replace("`", "``") + "`";
            try (PreparedStatement history = connection.prepareStatement(
                    "SELECT 1 FROM " + quotedHistory + " WHERE type <> 'SCHEMA' LIMIT 1");
                 ResultSet applied = history.executeQuery()) {
                return !applied.next();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("无法确认数据库是否首次初始化，已停止演示数据初始化", failure);
        }
    }

    @Override
    public String getCallbackName() {
        return "demoDataReset";
    }
}
