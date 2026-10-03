-- =====================================================================
-- 演示数据初始化/重置：首次空库完成 Flyway 迁移后初始化；已有库仅在显式 demo.reset-on-startup=true 时重置。
-- 默认关闭重启重置（DEMO_RESET_ON_STARTUP=false），已有业务数据、报表目录和规则在普通重启时保留。
-- 显式重置时，报表数据、报表目录、派单规则恢复为示例状态；
-- 会话、消息、审计、预览 / 清单记录保留，但未完成的预览和清单会被置为过期（数据已重置，旧快照不再可信）。
-- 报表目录与 V2 迁移中的种子保持一致；report_purchase 只建表和写数据、不进目录，
-- 用来演示“新增报表只靠配置”：通过目录管理接口登记后即可被识别、预览和派单。
-- =====================================================================

CREATE TABLE IF NOT EXISTS report_sales
(
    id              BIGINT         NOT NULL AUTO_INCREMENT COMMENT '本表记录主键；数据库自增',
    tenant_id       VARCHAR(64) NOT NULL COMMENT '数据所属租户标识；查询和写入必须限定租户',
    company_code    VARCHAR(10)    NOT NULL COMMENT '业务记录所属公司代码；用于公司权限隔离',
    order_no        VARCHAR(32)    NOT NULL COMMENT '销售订单号',
    product_name    VARCHAR(64) COMMENT '销售产品名称；允许为空，表示尚无该项数据',
    amount          DECIMAL(18, 2) NOT NULL DEFAULT 0.00 COMMENT '业务金额；小数精度2位，币种沿用来源业务账本',
    sale_date       DATE COMMENT '销售发生日期；允许为空，表示尚无该项数据',
    dispatch_status TINYINT        NOT NULL DEFAULT 0 COMMENT '业务派单状态：0未派单，1已派单',
    dispatched_at   DATETIME COMMENT '业务派单完成时间；未派单时为空',
    PRIMARY KEY (id),
    KEY idx_company_status (company_code, dispatch_status),
    KEY idx_tenant_company_status (tenant_id, company_code, dispatch_status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '销售业务报表；本仓库演示业务数据，按租户和公司隔离';

CREATE TABLE IF NOT EXISTS report_receivable
(
    id              BIGINT         NOT NULL AUTO_INCREMENT COMMENT '本表记录主键；数据库自增',
    tenant_id       VARCHAR(64) NOT NULL COMMENT '数据所属租户标识；查询和写入必须限定租户',
    company_code    VARCHAR(10)    NOT NULL COMMENT '业务记录所属公司代码；用于公司权限隔离',
    invoice_no      VARCHAR(32)    NOT NULL COMMENT '应收发票号',
    customer_name   VARCHAR(64) COMMENT '客户名称；允许为空，表示尚无该项数据',
    amount          DECIMAL(18, 2) NOT NULL DEFAULT 0.00 COMMENT '业务金额；小数精度2位，币种沿用来源业务账本',
    due_date        DATE COMMENT '应收到期日期；允许为空，表示尚无该项数据',
    dispatch_status TINYINT        NOT NULL DEFAULT 0 COMMENT '业务派单状态：0未派单，1已派单',
    dispatched_at   DATETIME COMMENT '业务派单完成时间；未派单时为空',
    PRIMARY KEY (id),
    KEY idx_company_status (company_code, dispatch_status),
    KEY idx_tenant_company_status (tenant_id, company_code, dispatch_status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '应收业务报表；本仓库演示业务数据，按租户和公司隔离';

CREATE TABLE IF NOT EXISTS report_expense
(
    id              BIGINT         NOT NULL AUTO_INCREMENT COMMENT '本表记录主键；数据库自增',
    tenant_id       VARCHAR(64) NOT NULL COMMENT '数据所属租户标识；查询和写入必须限定租户',
    company_code    VARCHAR(10)    NOT NULL COMMENT '业务记录所属公司代码；用于公司权限隔离',
    expense_no      VARCHAR(32)    NOT NULL COMMENT '报销单据号',
    expense_type    VARCHAR(32) COMMENT '费用类型；允许为空，表示尚无该项数据',
    amount          DECIMAL(18, 2) NOT NULL DEFAULT 0.00 COMMENT '业务金额；小数精度2位，币种沿用来源业务账本',
    expense_date    DATE COMMENT '费用发生日期；允许为空，表示尚无该项数据',
    dispatch_status TINYINT        NOT NULL DEFAULT 0 COMMENT '业务派单状态：0未派单，1已派单',
    dispatched_at   DATETIME COMMENT '业务派单完成时间；未派单时为空',
    PRIMARY KEY (id),
    KEY idx_company_status (company_code, dispatch_status),
    KEY idx_tenant_company_status (tenant_id, company_code, dispatch_status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '费用业务报表；本仓库演示业务数据，按租户和公司隔离';

CREATE TABLE IF NOT EXISTS report_purchase
(
    id              BIGINT         NOT NULL AUTO_INCREMENT COMMENT '本表记录主键；数据库自增',
    tenant_id       VARCHAR(64) NOT NULL COMMENT '数据所属租户标识；查询和写入必须限定租户',
    company_code    VARCHAR(10)    NOT NULL COMMENT '业务记录所属公司代码；用于公司权限隔离',
    po_no           VARCHAR(32)    NOT NULL COMMENT '采购订单号',
    supplier_name   VARCHAR(64) COMMENT '供应商名称；允许为空，表示尚无该项数据',
    amount          DECIMAL(18, 2) NOT NULL DEFAULT 0.00 COMMENT '业务金额；小数精度2位，币种沿用来源业务账本',
    order_date      DATE COMMENT '采购下单日期；允许为空，表示尚无该项数据',
    dispatch_status TINYINT        NOT NULL DEFAULT 0 COMMENT '业务派单状态：0未派单，1已派单',
    dispatched_at   DATETIME COMMENT '业务派单完成时间；未派单时为空',
    PRIMARY KEY (id),
    KEY idx_company_status (company_code, dispatch_status),
    KEY idx_tenant_company_status (tenant_id, company_code, dispatch_status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '采购业务报表；本仓库演示业务数据，按租户和公司隔离';

TRUNCATE TABLE report_sales;
TRUNCATE TABLE report_receivable;
TRUNCATE TABLE report_expense;
TRUNCATE TABLE report_purchase;

-- 销售报表数据（默认规则：金额 > 20；C 公司单独规则：金额 > 50000）
INSERT INTO report_sales (tenant_id, company_code, order_no, product_name, amount, sale_date)
VALUES ('T001', 'A', 'SO2026001', '服务器', 128000.00, '2026-01-15'),
       ('T001', 'A', 'SO2026002', '交换机', 32000.00, '2026-02-08'),
       ('T001', 'A', 'SO2026007', '云服务', 96000.00, '2026-04-05'),
       ('T001', 'A', 'SO2026008', '网线', 15.00, '2026-04-12'),
       ('T001', 'B', 'SO2026003', '笔记本电脑', 86000.00, '2026-01-22'),
       ('T001', 'B', 'SO2026004', '显示器', 15400.00, '2026-03-11'),
       ('T001', 'B', 'SO2026009', '鼠标', 18.00, '2026-03-20'),
       ('T001', 'C', 'SO2026005', '存储阵列', 256000.00, '2026-02-19'),
       ('T001', 'C', 'SO2026006', '防火墙', 47800.00, '2026-03-27');

-- 应收报表数据（规则：金额 < 20）
INSERT INTO report_receivable (tenant_id, company_code, invoice_no, customer_name, amount, due_date)
VALUES ('T001', 'A', 'INV-2026-0001', '北京某某科技有限公司', 128000.00, '2026-03-15'),
       ('T001', 'A', 'INV-2026-0002', '上海某某信息有限公司', 32000.00, '2026-04-08'),
       ('T001', 'A', 'INV-2026-0007', '天津某某贸易有限公司', 12.50, '2026-05-10'),
       ('T001', 'A', 'INV-2026-0008', '北京某某咨询有限公司', 8.00, '2026-06-01'),
       ('T001', 'B', 'INV-2026-0003', '广州某某电子有限公司', 86000.00, '2026-03-22'),
       ('T001', 'B', 'INV-2026-0004', '深圳某某网络有限公司', 15400.00, '2026-04-11'),
       ('T001', 'B', 'INV-2026-0009', '东莞某某制造有限公司', 19.90, '2026-05-18'),
       ('T001', 'C', 'INV-2026-0005', '杭州某某数据有限公司', 256000.00, '2026-04-19'),
       ('T001', 'C', 'INV-2026-0006', '成都某某软件有限公司', 47800.00, '2026-05-27'),
       ('T001', 'C', 'INV-2026-0010', '武汉某某物流有限公司', 6.60, '2026-06-15');

-- 费用报表数据（规则：金额 > 1000）
INSERT INTO report_expense (tenant_id, company_code, expense_no, expense_type, amount, expense_date)
VALUES ('T001', 'A', 'EXP-2026-0001', '差旅费', 8600.00, '2026-01-09'),
       ('T001', 'A', 'EXP-2026-0002', '业务招待费', 3200.00, '2026-02-14'),
       ('T001', 'A', 'EXP-2026-0007', '办公用品', 680.00, '2026-03-05'),
       ('T001', 'B', 'EXP-2026-0003', '办公用品', 1580.00, '2026-01-18'),
       ('T001', 'B', 'EXP-2026-0004', '市场推广费', 26000.00, '2026-03-02'),
       ('T001', 'B', 'EXP-2026-0008', '快递费', 95.00, '2026-03-16'),
       ('T001', 'C', 'EXP-2026-0005', '培训费', 12500.00, '2026-02-25'),
       ('T001', 'C', 'EXP-2026-0006', '设备维护费', 7300.00, '2026-03-30'),
       ('T001', 'C', 'EXP-2026-0009', '停车费', 350.00, '2026-04-02');

-- 采购报表数据（未登记到报表目录，登记并发布规则后才参与派单）
INSERT INTO report_purchase (tenant_id, company_code, po_no, supplier_name, amount, order_date)
VALUES ('T001', 'A', 'PO-2026-0001', '苏州某某电子有限公司', 56000.00, '2026-02-03'),
       ('T001', 'A', 'PO-2026-0002', '北京某某办公用品有限公司', 800.00, '2026-03-12'),
       ('T001', 'B', 'PO-2026-0003', '深圳某某精密有限公司', 23000.00, '2026-02-21'),
       ('T001', 'C', 'PO-2026-0004', '杭州某某材料有限公司', 9100.00, '2026-04-08');

-- 报表目录恢复为种子状态（与 V2__report_catalog.sql 一致）
DELETE FROM report_alias;
DELETE FROM report_definition;

INSERT INTO report_definition (tenant_id, report_id, report_code, report_name, domain_code, description, query_mode, query_config,
                               dispatch_enabled, status, schema_version, catalog_version, permission_code, sort_order,
                               created_by, created_at, updated_by, updated_at)
VALUES ('T001', 'rpt-sales-order', 'sales', '销售报表', 'sales', '销售订单台账：按订单统计金额与派单状态', 'STANDARD',
        '{"table":"report_sales","idColumn":"id","companyColumn":"company_code","tenantColumn":"tenant_id","docNoColumn":"order_no","docNoLabel":"订单号","labelColumn":"product_name","amountColumn":"amount","dateColumn":"sale_date","statusColumn":"dispatch_status","pendingValue":0,"dispatchedValue":1,"dispatchedAtColumn":"dispatched_at","fields":[{"name":"companyCode","column":"company_code","type":"string","description":"公司代码"},{"name":"orderNo","column":"order_no","type":"string","description":"订单号"},{"name":"productName","column":"product_name","type":"string","description":"产品名称"},{"name":"amount","column":"amount","type":"decimal","description":"订单金额（元）"},{"name":"saleDate","column":"sale_date","type":"date","description":"销售日期，字符串 yyyy-MM-dd"}],"derived":[{"name":"daysSinceSale","kind":"DAYS_SINCE","column":"sale_date","description":"距销售日期的天数（派生）"},{"name":"dispatched","kind":"DISPATCHED","description":"是否已派单（派生，粗筛后恒为 false）"}]}',
        1, 'PUBLISHED', 1, 1, 'report:sales', 10, 'demo', NOW(3), 'demo', NOW(3)),
       ('T001', 'rpt-ar-invoice', 'receivable', '应收报表', 'receivable', '应收发票台账：按发票统计应收金额与到期日', 'STANDARD',
        '{"table":"report_receivable","idColumn":"id","companyColumn":"company_code","tenantColumn":"tenant_id","docNoColumn":"invoice_no","docNoLabel":"发票号","labelColumn":"customer_name","amountColumn":"amount","dateColumn":"due_date","statusColumn":"dispatch_status","pendingValue":0,"dispatchedValue":1,"dispatchedAtColumn":"dispatched_at","fields":[{"name":"companyCode","column":"company_code","type":"string","description":"公司代码"},{"name":"invoiceNo","column":"invoice_no","type":"string","description":"发票号"},{"name":"customerName","column":"customer_name","type":"string","description":"客户名称"},{"name":"amount","column":"amount","type":"decimal","description":"应收金额（元）"},{"name":"dueDate","column":"due_date","type":"date","description":"到期日，字符串 yyyy-MM-dd"}],"derived":[{"name":"daysUntilDue","kind":"DAYS_UNTIL","column":"due_date","description":"距到期日的天数，已过期为负数（派生）"},{"name":"overdue","kind":"IS_PAST","column":"due_date","description":"是否已逾期（派生）"},{"name":"dispatched","kind":"DISPATCHED","description":"是否已派单（派生，粗筛后恒为 false）"}]}',
        1, 'PUBLISHED', 1, 1, 'report:receivable', 20, 'demo', NOW(3), 'demo', NOW(3)),
       ('T001', 'rpt-expense-claim', 'expense', '费用报表', 'expense', '费用报销台账：按报销单统计费用类型与金额', 'STANDARD',
        '{"table":"report_expense","idColumn":"id","companyColumn":"company_code","tenantColumn":"tenant_id","docNoColumn":"expense_no","docNoLabel":"报销单号","labelColumn":"expense_type","amountColumn":"amount","dateColumn":"expense_date","statusColumn":"dispatch_status","pendingValue":0,"dispatchedValue":1,"dispatchedAtColumn":"dispatched_at","fields":[{"name":"companyCode","column":"company_code","type":"string","description":"公司代码"},{"name":"expenseNo","column":"expense_no","type":"string","description":"报销单号"},{"name":"expenseType","column":"expense_type","type":"string","description":"费用类型，如 差旅费 / 业务招待费 / 办公用品"},{"name":"amount","column":"amount","type":"decimal","description":"报销金额（元）"},{"name":"expenseDate","column":"expense_date","type":"date","description":"发生日期，字符串 yyyy-MM-dd"}],"derived":[{"name":"daysSinceExpense","kind":"DAYS_SINCE","column":"expense_date","description":"距发生日期的天数（派生）"},{"name":"dispatched","kind":"DISPATCHED","description":"是否已派单（派生，粗筛后恒为 false）"}]}',
        1, 'PUBLISHED', 1, 1, 'report:expense', 30, 'demo', NOW(3), 'demo', NOW(3));

INSERT INTO report_alias (tenant_id, report_id, alias, alias_type, priority, created_by, created_at)
VALUES ('T001', 'rpt-sales-order', '销售', 'SHORT', 10, 'demo', NOW(3)),
       ('T001', 'rpt-sales-order', '销售台账', 'COLLOQUIAL', 5, 'demo', NOW(3)),
       ('T001', 'rpt-sales-order', '订单销售表', 'COLLOQUIAL', 5, 'demo', NOW(3)),
       ('T001', 'rpt-sales-order', '销售明细', 'COLLOQUIAL', 5, 'demo', NOW(3)),
       ('T001', 'rpt-sales-order', '销售订单', 'COLLOQUIAL', 5, 'demo', NOW(3)),
       ('T001', 'rpt-sales-order', 'sales report', 'ENGLISH', 0, 'demo', NOW(3)),
       ('T001', 'rpt-sales-order', '客户对账', 'DEPARTMENT', 0, 'demo', NOW(3)),
       ('T001', 'rpt-ar-invoice', '应收', 'SHORT', 10, 'demo', NOW(3)),
       ('T001', 'rpt-ar-invoice', '应收发票台账', 'HISTORICAL', 5, 'demo', NOW(3)),
       ('T001', 'rpt-ar-invoice', '应收台账', 'COLLOQUIAL', 5, 'demo', NOW(3)),
       ('T001', 'rpt-ar-invoice', '应收账款', 'COLLOQUIAL', 5, 'demo', NOW(3)),
       ('T001', 'rpt-ar-invoice', 'AR', 'ENGLISH', 0, 'demo', NOW(3)),
       ('T001', 'rpt-ar-invoice', '客户对账', 'DEPARTMENT', 0, 'demo', NOW(3)),
       ('T001', 'rpt-ar-invoice', '应收保表', 'TYPO', 0, 'demo', NOW(3)),
       ('T001', 'rpt-expense-claim', '费用', 'SHORT', 10, 'demo', NOW(3)),
       ('T001', 'rpt-expense-claim', '报销', 'COLLOQUIAL', 5, 'demo', NOW(3)),
       ('T001', 'rpt-expense-claim', '报销单', 'COLLOQUIAL', 5, 'demo', NOW(3)),
       ('T001', 'rpt-expense-claim', '费用报销', 'COLLOQUIAL', 5, 'demo', NOW(3)),
       ('T001', 'rpt-expense-claim', '费用台账', 'COLLOQUIAL', 5, 'demo', NOW(3)),
       ('T001', 'rpt-expense-claim', 'expense report', 'ENGLISH', 0, 'demo', NOW(3));

-- 派单规则恢复为示例状态（可在规则管理页修改）
TRUNCATE TABLE dispatch_rule;
TRUNCATE TABLE dispatch_rule_history;

INSERT INTO dispatch_rule (tenant_id, report_id, company_code, name, description, expression, version, status, created_at, updated_by, updated_at)
VALUES ('T001', 'rpt-sales-order', '*', '销售报表默认规则', '销售订单金额大于 20 元需要派单', 'amount > 20', 1, 'published', NOW(), 'system', NOW()),
       ('T001', 'rpt-sales-order', 'C', 'C 公司销售规则', 'C 公司销售订单金额大于 50000 元才需要派单', 'amount > 50000', 1, 'published', NOW(), 'system', NOW()),
       ('T001', 'rpt-ar-invoice', '*', '应收报表默认规则', '应收发票金额小于 20 元需要派单（小额应收集中催收）', 'amount < 20', 1, 'published', NOW(), 'system', NOW()),
       ('T001', 'rpt-expense-claim', '*', '费用报表默认规则', '报销单金额大于 1000 元需要派单', 'amount > 1000', 1, 'published', NOW(), 'system', NOW());

INSERT INTO dispatch_rule_history (tenant_id, rule_id, report_id, company_code, version, name, expression, description, action, operated_by, operated_at)
SELECT tenant_id, id, report_id, company_code, version, name, expression, description, 'publish', 'system', NOW()
FROM dispatch_rule;

-- 数据已恢复为示例状态，重置前留下的预览和待确认清单不再可信
UPDATE dispatch_preview SET status = 'EXPIRED', status_reason = 'DEMO_RESET', updated_at = NOW(3) WHERE status = 'ACTIVE';
UPDATE dispatch_plan SET status = 'EXPIRED', status_reason = 'DEMO_RESET', updated_at = NOW(3) WHERE status = 'PENDING';
