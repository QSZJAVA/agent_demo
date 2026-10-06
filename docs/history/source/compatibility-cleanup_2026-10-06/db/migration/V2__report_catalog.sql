-- =====================================================================
-- V2 报表目录（P0-01）与旧报表编码映射（P0-02）
-- report_id：稳定、不可变的内部标识，创建后不因改名、改编码而变化，跨环境保持一致（种子脚本、规则迁移都引用它）
-- report_code：对外 / 接口编码，允许随接口演进调整
-- query_config：STANDARD 模式下的配置化查询（表、主键、单据号、金额、日期、状态、事实字段），新增标准报表只需配置
-- =====================================================================

CREATE TABLE report_definition
(
    report_id        VARCHAR(64)  NOT NULL COMMENT '稳定业务标识，创建后不可修改',
    report_code      VARCHAR(128) NOT NULL COMMENT '对外 / 接口编码',
    report_name      VARCHAR(256) NOT NULL COMMENT '当前展示名称',
    domain_code      VARCHAR(64)  NOT NULL COMMENT '业务域：sales / receivable / expense / purchase ...',
    description      VARCHAR(512)          COMMENT '报表说明，展示在报表选择卡片上',
    query_mode       VARCHAR(32)  NOT NULL COMMENT 'STANDARD 配置化查询 / ADAPTER 专用适配器',
    query_config     JSON         NOT NULL COMMENT 'STANDARD：表与字段映射；ADAPTER：{"adapter": "适配器编码"}',
    dispatch_enabled TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否允许通过 Agent 发起派单',
    status           VARCHAR(16)  NOT NULL COMMENT 'DRAFT / PUBLISHED / DISABLED',
    schema_version   INT          NOT NULL DEFAULT 1 COMMENT '事实字段与结果结构版本',
    catalog_version  BIGINT       NOT NULL DEFAULT 1 COMMENT '定义每变更一次加 1，预览快照记录它，变化后旧预览不能执行',
    permission_code  VARCHAR(128) NOT NULL COMMENT '访问该报表需要的权限码',
    sort_order       INT          NOT NULL DEFAULT 0 COMMENT '展示与汇总顺序',
    effective_from   DATETIME(3)           COMMENT '生效开始，空表示立即生效',
    effective_to     DATETIME(3)           COMMENT '生效结束（不含），空表示长期有效',
    owner_user_id    VARCHAR(64)           COMMENT '业务负责人',
    created_by       VARCHAR(64),
    created_at       DATETIME(3)  NOT NULL,
    updated_by       VARCHAR(64),
    updated_at       DATETIME(3)  NOT NULL,
    PRIMARY KEY (report_id),
    UNIQUE KEY uk_report_code (report_code),
    KEY idx_report_status (status, dispatch_enabled)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '报表目录';

CREATE TABLE report_alias
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    report_id  VARCHAR(64)  NOT NULL,
    alias      VARCHAR(256) NOT NULL COMMENT '简称、口语、英文名、历史名称、部门俗称、常见错别字',
    alias_type VARCHAR(32)  NOT NULL COMMENT 'SHORT / COLLOQUIAL / ENGLISH / HISTORICAL / DEPARTMENT / TYPO',
    priority   INT          NOT NULL DEFAULT 0 COMMENT '候选排序用，越大越靠前',
    status     VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED',
    created_by VARCHAR(64),
    created_at DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_report_alias (report_id, alias),
    KEY idx_alias_search (alias, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '报表别名';

CREATE TABLE report_code_mapping
(
    legacy_code VARCHAR(64)  NOT NULL COMMENT 'Demo 时期的 reportType：sales / receivable / expense',
    report_id   VARCHAR(64)  NOT NULL,
    description VARCHAR(255),
    created_at  DATETIME(3)  NOT NULL,
    PRIMARY KEY (legacy_code),
    KEY idx_mapping_report (report_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '旧报表编码到 report_id 的映射，只增不改，保证旧规则与审计可追溯';

INSERT INTO report_definition (report_id, report_code, report_name, domain_code, description, query_mode, query_config,
                               dispatch_enabled, status, schema_version, catalog_version, permission_code, sort_order,
                               created_by, created_at, updated_by, updated_at)
VALUES ('rpt-sales-order', 'sales', '销售报表', 'sales', '销售订单台账：按订单统计金额与派单状态', 'STANDARD',
        '{"table":"report_sales","idColumn":"id","companyColumn":"company_code","docNoColumn":"order_no","docNoLabel":"订单号","labelColumn":"product_name","amountColumn":"amount","dateColumn":"sale_date","statusColumn":"dispatch_status","pendingValue":0,"dispatchedValue":1,"dispatchedAtColumn":"dispatched_at","fields":[{"name":"companyCode","column":"company_code","type":"string","description":"公司代码"},{"name":"orderNo","column":"order_no","type":"string","description":"订单号"},{"name":"productName","column":"product_name","type":"string","description":"产品名称"},{"name":"amount","column":"amount","type":"decimal","description":"订单金额（元）"},{"name":"saleDate","column":"sale_date","type":"date","description":"销售日期，字符串 yyyy-MM-dd"}],"derived":[{"name":"daysSinceSale","kind":"DAYS_SINCE","column":"sale_date","description":"距销售日期的天数（派生）"},{"name":"dispatched","kind":"DISPATCHED","description":"是否已派单（派生，粗筛后恒为 false）"}]}',
        1, 'PUBLISHED', 1, 1, 'report:sales', 10, 'migration', NOW(3), 'migration', NOW(3)),
       ('rpt-ar-invoice', 'receivable', '应收报表', 'receivable', '应收发票台账：按发票统计应收金额与到期日', 'STANDARD',
        '{"table":"report_receivable","idColumn":"id","companyColumn":"company_code","docNoColumn":"invoice_no","docNoLabel":"发票号","labelColumn":"customer_name","amountColumn":"amount","dateColumn":"due_date","statusColumn":"dispatch_status","pendingValue":0,"dispatchedValue":1,"dispatchedAtColumn":"dispatched_at","fields":[{"name":"companyCode","column":"company_code","type":"string","description":"公司代码"},{"name":"invoiceNo","column":"invoice_no","type":"string","description":"发票号"},{"name":"customerName","column":"customer_name","type":"string","description":"客户名称"},{"name":"counterpartyId","column":"customer_id","type":"string","description":"交易对方稳定标识"},{"name":"counterpartyName","column":"customer_name","type":"string","description":"交易对方完整名称"},{"name":"counterpartyAliases","column":"customer_aliases","type":"string","description":"交易对方别名JSON数组"},{"name":"amount","column":"amount","type":"decimal","description":"应收金额（元）"},{"name":"dueDate","column":"due_date","type":"date","description":"到期日，字符串 yyyy-MM-dd"}],"derived":[{"name":"daysUntilDue","kind":"DAYS_UNTIL","column":"due_date","description":"距到期日的天数，已过期为负数（派生）"},{"name":"overdue","kind":"IS_PAST","column":"due_date","description":"是否已逾期（派生）"},{"name":"dispatched","kind":"DISPATCHED","description":"是否已派单（派生，粗筛后恒为 false）"}]}',
        1, 'PUBLISHED', 1, 1, 'report:receivable', 20, 'migration', NOW(3), 'migration', NOW(3)),
       ('rpt-expense-claim', 'expense', '费用报表', 'expense', '费用报销台账：按报销单统计费用类型与金额', 'STANDARD',
        '{"table":"report_expense","idColumn":"id","companyColumn":"company_code","docNoColumn":"expense_no","docNoLabel":"报销单号","labelColumn":"expense_type","amountColumn":"amount","dateColumn":"expense_date","statusColumn":"dispatch_status","pendingValue":0,"dispatchedValue":1,"dispatchedAtColumn":"dispatched_at","fields":[{"name":"companyCode","column":"company_code","type":"string","description":"公司代码"},{"name":"expenseNo","column":"expense_no","type":"string","description":"报销单号"},{"name":"expenseType","column":"expense_type","type":"string","description":"费用类型，如 差旅费 / 业务招待费 / 办公用品"},{"name":"amount","column":"amount","type":"decimal","description":"报销金额（元）"},{"name":"expenseDate","column":"expense_date","type":"date","description":"发生日期，字符串 yyyy-MM-dd"}],"derived":[{"name":"daysSinceExpense","kind":"DAYS_SINCE","column":"expense_date","description":"距发生日期的天数（派生）"},{"name":"dispatched","kind":"DISPATCHED","description":"是否已派单（派生，粗筛后恒为 false）"}]}',
        1, 'PUBLISHED', 1, 1, 'report:expense', 30, 'migration', NOW(3), 'migration', NOW(3));

-- 别名：同一个说法可以指向多张报表（例如“客户对账”），解析时必须让用户选择
INSERT INTO report_alias (report_id, alias, alias_type, priority, created_by, created_at)
VALUES ('rpt-sales-order', '销售', 'SHORT', 10, 'migration', NOW(3)),
       ('rpt-sales-order', '销售台账', 'COLLOQUIAL', 5, 'migration', NOW(3)),
       ('rpt-sales-order', '订单销售表', 'COLLOQUIAL', 5, 'migration', NOW(3)),
       ('rpt-sales-order', '销售明细', 'COLLOQUIAL', 5, 'migration', NOW(3)),
       ('rpt-sales-order', '销售订单', 'COLLOQUIAL', 5, 'migration', NOW(3)),
       ('rpt-sales-order', 'sales report', 'ENGLISH', 0, 'migration', NOW(3)),
       ('rpt-sales-order', '客户对账', 'DEPARTMENT', 0, 'migration', NOW(3)),
       ('rpt-ar-invoice', '应收', 'SHORT', 10, 'migration', NOW(3)),
       ('rpt-ar-invoice', '应收发票台账', 'HISTORICAL', 5, 'migration', NOW(3)),
       ('rpt-ar-invoice', '应收台账', 'COLLOQUIAL', 5, 'migration', NOW(3)),
       ('rpt-ar-invoice', '应收账款', 'COLLOQUIAL', 5, 'migration', NOW(3)),
       ('rpt-ar-invoice', 'AR', 'ENGLISH', 0, 'migration', NOW(3)),
       ('rpt-ar-invoice', '客户对账', 'DEPARTMENT', 0, 'migration', NOW(3)),
       ('rpt-ar-invoice', '应收保表', 'TYPO', 0, 'migration', NOW(3)),
       ('rpt-expense-claim', '费用', 'SHORT', 10, 'migration', NOW(3)),
       ('rpt-expense-claim', '报销', 'COLLOQUIAL', 5, 'migration', NOW(3)),
       ('rpt-expense-claim', '报销单', 'COLLOQUIAL', 5, 'migration', NOW(3)),
       ('rpt-expense-claim', '费用报销', 'COLLOQUIAL', 5, 'migration', NOW(3)),
       ('rpt-expense-claim', '费用台账', 'COLLOQUIAL', 5, 'migration', NOW(3)),
       ('rpt-expense-claim', 'expense report', 'ENGLISH', 0, 'migration', NOW(3));

INSERT INTO report_code_mapping (legacy_code, report_id, description, created_at)
VALUES ('sales', 'rpt-sales-order', 'Demo 枚举 ReportType.SALES', NOW(3)),
       ('receivable', 'rpt-ar-invoice', 'Demo 枚举 ReportType.RECEIVABLE', NOW(3)),
       ('expense', 'rpt-expense-claim', 'Demo 枚举 ReportType.EXPENSE', NOW(3));
