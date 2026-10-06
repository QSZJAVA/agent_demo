package com.example.report.config;

import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.api.configuration.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DemoDataResetCallbackTest {
    private final Context context = mock(Context.class);
    private final Connection connection = mock(Connection.class);
    private final Configuration configuration = mock(Configuration.class);
    private final MigrationInfo migration = mock(MigrationInfo.class);
    private final PreparedStatement tables = mock(PreparedStatement.class);
    private final PreparedStatement history = mock(PreparedStatement.class);
    private final ResultSet existingTables = mock(ResultSet.class);
    private final ResultSet appliedMigrations = mock(ResultSet.class);
    private final Statement seed = mock(Statement.class);

    @BeforeEach
    void setUp() throws Exception {
        when(context.getConnection()).thenReturn(connection);
        when(context.getConfiguration()).thenReturn(configuration);
        when(context.getMigrationInfo()).thenReturn(migration);
        when(configuration.getTable()).thenReturn("flyway_schema_history");
        when(migration.getVersion()).thenReturn(MigrationVersion.fromVersion("1"));
        when(connection.prepareStatement(startsWith("SELECT 1 FROM information_schema.tables"))).thenReturn(tables);
        when(connection.prepareStatement(startsWith("SELECT 1 FROM `flyway_schema_history`"))).thenReturn(history);
        when(tables.executeQuery()).thenReturn(existingTables);
        when(history.executeQuery()).thenReturn(appliedMigrations);
        when(connection.createStatement()).thenReturn(seed);
        when(seed.getUpdateCount()).thenReturn(-1);
    }

    @Test
    void firstEmptyDatabaseIsSeededOnlyAfterAllMigrationsComplete() throws Exception {
        var callback = new DemoDataResetCallback(false);
        callback.handle(Event.BEFORE_MIGRATE, context);
        callback.handle(Event.BEFORE_EACH_MIGRATE, context);
        verify(connection, never()).createStatement();
        when(migration.getVersion()).thenReturn(MigrationVersion.fromVersion("17"));
        callback.handle(Event.BEFORE_EACH_MIGRATE, context);
        callback.handle(Event.AFTER_MIGRATE, context);

        ArgumentCaptor<String> statements = ArgumentCaptor.forClass(String.class);
        verify(seed, atLeastOnce()).execute(statements.capture());
        List<String> sql = statements.getAllValues();
        assertTrue(sql.stream().anyMatch(s -> s.startsWith("CREATE TABLE IF NOT EXISTS report_sales")));
        assertTrue(sql.stream().anyMatch(s -> s.startsWith("INSERT INTO report_sales")));
        assertTrue(sql.stream().anyMatch(s -> s.startsWith("INSERT INTO report_definition")));
        assertTrue(sql.stream().anyMatch(s -> s.startsWith("INSERT INTO dispatch_rule ")));
        verify(seed).close();
    }

    @Test
    void ordinaryRestartPreservesExistingDataAndDoesNotReuseInitializationFlag() throws Exception {
        var callback = new DemoDataResetCallback(false);
        callback.handle(Event.BEFORE_MIGRATE, context);
        callback.handle(Event.BEFORE_EACH_MIGRATE, context);
        callback.handle(Event.AFTER_MIGRATE, context);
        clearInvocations(connection, seed);

        callback.handle(Event.BEFORE_MIGRATE, context);
        callback.handle(Event.AFTER_MIGRATE, context);
        verifyNoInteractions(connection, seed);
    }

    @Test
    void existingSchemaWithAnEmptyDirectoryIsNeverConsideredFresh() throws Exception {
        // Any existing application table counts, regardless of its row count. No SELECT
        // against report_definition or destructive script may run, even during baseline V1.
        when(existingTables.next()).thenReturn(true);
        var callback = new DemoDataResetCallback(false);
        callback.handle(Event.BEFORE_MIGRATE, context);
        callback.handle(Event.BEFORE_EACH_MIGRATE, context);
        callback.handle(Event.AFTER_MIGRATE, context);
        verify(tables).setString(1, "flyway_schema_history");
        verifyNoInteractions(history, seed);
        verify(connection, never()).createStatement();
    }

    @Test
    void existingMigrationHistoryIsPreservedEvenIfApplicationTablesAreGone() throws Exception {
        when(appliedMigrations.next()).thenReturn(true);
        var callback = new DemoDataResetCallback(false);
        callback.handle(Event.BEFORE_MIGRATE, context);
        callback.handle(Event.BEFORE_EACH_MIGRATE, context);
        callback.handle(Event.AFTER_MIGRATE, context);
        verifyNoInteractions(seed);
        verify(connection, never()).createStatement();
    }

    @Test
    void explicitResetRunsOnEveryStartupWithoutFreshDatabaseChecks() throws Exception {
        var callback = new DemoDataResetCallback(true);
        for (int startup = 0; startup < 2; startup++) {
            callback.handle(Event.BEFORE_MIGRATE, context);
            callback.handle(Event.AFTER_MIGRATE, context);
        }
        verify(connection, times(2)).createStatement();
        verify(seed, times(2)).execute("TRUNCATE TABLE report_sales");
        verify(seed, times(2)).execute("TRUNCATE TABLE dispatch_rule");
        verifyNoInteractions(tables, history);
    }

    @Test
    void failedInspectionCannotTriggerDataReset() throws Exception {
        when(tables.executeQuery()).thenThrow(new SQLException("unavailable"));
        var callback = new DemoDataResetCallback(false);
        callback.handle(Event.BEFORE_MIGRATE, context);
        assertThrows(IllegalStateException.class, () -> callback.handle(Event.BEFORE_EACH_MIGRATE, context));
        callback.handle(Event.AFTER_MIGRATE, context);
        verify(connection, never()).createStatement();
        verifyNoInteractions(seed);
    }

    @Test
    void failedMigrationAttemptCannotSeedOnLaterRestart() throws Exception {
        var callback = new DemoDataResetCallback(false);
        callback.handle(Event.BEFORE_MIGRATE, context);
        callback.handle(Event.BEFORE_EACH_MIGRATE, context);
        // No AFTER_MIGRATE when a migration fails. The next operation must discard the
        // eligibility from that failed attempt, even when reusing the same callback.
        callback.handle(Event.BEFORE_MIGRATE, context);
        when(migration.getVersion()).thenReturn(MigrationVersion.fromVersion("2"));
        callback.handle(Event.BEFORE_EACH_MIGRATE, context);
        callback.handle(Event.AFTER_MIGRATE, context);
        verify(connection, never()).createStatement();
        verifyNoInteractions(seed);
    }
}
