package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

/** Upgrade existing demo business tables; new demo tables are created by demo-data.sql. */
public class V7__Demo_report_tenants extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        for (String table : List.of("report_sales", "report_receivable", "report_expense", "report_purchase")) {
            try (PreparedStatement query = context.getConnection().prepareStatement(
                    "SELECT COUNT(*), SUM(column_name = 'tenant_id') FROM information_schema.columns "
                            + "WHERE table_schema = DATABASE() AND table_name = ?")) {
                query.setString(1, table);
                try (ResultSet rs = query.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) > 0 && rs.getInt(2) == 0) {
                        try (Statement ddl = context.getConnection().createStatement()) {
                            ddl.execute("ALTER TABLE `" + table + "` ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'T001', "
                                    + "ADD KEY idx_tenant_company_status (tenant_id, company_code, dispatch_status)");
                            ddl.execute("ALTER TABLE `" + table + "` ALTER COLUMN tenant_id DROP DEFAULT");
                        }
                    }
                }
            }
        }
    }
}
