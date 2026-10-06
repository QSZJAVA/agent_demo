-- 当前V1完整空库结构；不执行历史数据迁移或字段补齐。

CREATE TABLE `agent_conversation` (
  `id` varchar(32) NOT NULL COMMENT '服务端生成的会话主键',
  `tenant_id` varchar(64) NOT NULL COMMENT '数据所属租户标识；查询和写入必须限定租户',
  `user_id` varchar(64) NOT NULL COMMENT '记录所属用户标识，结合租户确定数据归属',
  `title` varchar(64) DEFAULT NULL COMMENT '会话标题，默认使用首条用户消息摘要；允许为空，表示尚无该项数据',
  `model` varchar(64) DEFAULT NULL COMMENT '实际使用的模型名称；允许为空，表示尚无该项数据',
  `message_count` int NOT NULL DEFAULT '0' COMMENT '会话消息计数',
  `last_message_at` datetime(3) DEFAULT NULL COMMENT '最近一条消息的时间；无消息时为空',
  `status` varchar(16) NOT NULL DEFAULT 'active' COMMENT '会话状态：active可用，deleted已删除',
  `created_at` datetime(3) NOT NULL COMMENT '记录创建时间',
  `updated_at` datetime(3) NOT NULL COMMENT '记录最后更新时间',
  `preview_request_version` bigint NOT NULL DEFAULT '0' COMMENT '会话内预览请求序号；新请求递增，旧任务不得激活快照',
  PRIMARY KEY (`id`),
  KEY `idx_owner_last` (`tenant_id`,`user_id`,`status`,`last_message_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户会话元数据；按租户和用户隔离，消息与语义状态分别持久化';

CREATE TABLE `agent_investigation_queue` (
  `tenant_id` varchar(64) COLLATE utf8mb4_bin NOT NULL COMMENT '对应租户的队列互斥标识，插入后通过行锁串行检查准入',
  PRIMARY KEY (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='调查任务按租户进行事务准入的互斥行；不存业务结果，限制并发提交绕过在途配额，随专用 Demo 基线创建。';

CREATE TABLE `agent_investigation_run` (
  `id` varchar(64) COLLATE utf8mb4_bin NOT NULL COMMENT '服务端生成的调查运行标识',
  `tenant_id` varchar(64) COLLATE utf8mb4_bin NOT NULL COMMENT '数据所属租户，与当前创建人身份一致',
  `actor_id` varchar(64) COLLATE utf8mb4_bin NOT NULL COMMENT '发起调查的账号标识，结合租户定位',
  `plan_id` varchar(64) COLLATE utf8mb4_bin NOT NULL COMMENT '关联 dispatch_plan.id，本次唯一目标清单',
  `plan_owner_id` varchar(64) COLLATE utf8mb4_bin NOT NULL COMMENT '清单原操作者，由服务端读取，用于受控代核对',
  `idempotency_key` varchar(128) COLLATE utf8mb4_bin NOT NULL COMMENT '创建请求稳定键，同用户同键只能对应相同规范化负载',
  `payload_hash` char(64) COLLATE utf8mb4_bin NOT NULL COMMENT '规范化提交负载 SHA-256，不含凭据',
  `request_json` json NOT NULL COMMENT '当前请求结构：用户问题、目标条目 ID；问题为脱敏后的允许内容',
  `scope_hash` char(64) COLLATE utf8mb4_bin NOT NULL COMMENT '有序目标条目集合和清单身份的摘要',
  `active_key` char(64) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '在途时为租户、创建人、清单的摘要；终态为空，释放在途唯一约束',
  `expected_execution_version` bigint NOT NULL COMMENT '提交时绑定的清单执行轮次，禁止借用后来版本',
  `source_fingerprint` char(64) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '已冻结本地事实摘要；尚未取得快照时为空',
  `snapshot_json` json DEFAULT NULL COMMENT '当前格式的条目、规则、事件和范围快照；排队阶段为空',
  `status` varchar(24) COLLATE utf8mb4_bin NOT NULL DEFAULT 'QUEUED' COMMENT '调查任务状态：QUEUED排队、RUNNING运行、COMPLETED完成、PARTIAL部分完成、FAILED失败、CANCELLED取消、INTERRUPTED中断',
  `stop_reason` varchar(40) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '结束原因编码；运行中未结束时为空',
  `claim_token` varchar(64) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '当前工作线程认领令牌；未认领或终态为空',
  `row_version` bigint NOT NULL DEFAULT '0' COMMENT '条件更新版本，成功更新后递增',
  `lease_until` datetime(3) DEFAULT NULL COMMENT '当前执行租约 UTC 截止时间，未认领或终态为空',
  `queue_expires_at` datetime(3) NOT NULL COMMENT '排队准入有效期 UTC 截止时间',
  `deadline_at` datetime(3) DEFAULT NULL COMMENT '执行总预算 UTC 截止时间；未开始为空',
  `model_name` varchar(128) COLLATE utf8mb4_bin NOT NULL COMMENT '本次实际选定模型标识，不保证供应商持续可用',
  `config_json` json NOT NULL COMMENT '非敏感端点标识、模型参数、预算、工具契约与提示词哈希；不含密钥',
  `model_calls` int NOT NULL DEFAULT '0' COMMENT '已尝试模型请求次数，含失败及报告修复',
  `tool_calls` int NOT NULL DEFAULT '0' COMMENT '已处理模型工具请求数，含参数错误与结果复用',
  `mcp_calls` int NOT NULL DEFAULT '0' COMMENT '已尝试远端 MCP 工具调用次数，批量逐项计数',
  `usage_json` json DEFAULT NULL COMMENT '输入/输出/缓存 token、完整性、费用及币种；缺失用量为 null',
  `report_json` json DEFAULT NULL COMMENT '已通过校验的当前报告结构；尚无合法报告时为空',
  `message` varchar(512) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '可展示的状态或失败摘要，不含秘密和原始异常响应',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间，UTC',
  `started_at` datetime(3) DEFAULT NULL COMMENT '首次认领执行时间，尚未开始为空',
  `finished_at` datetime(3) DEFAULT NULL COMMENT '任务终止时间，尚未结束为空',
  `updated_at` datetime(3) NOT NULL COMMENT '最后一次持久状态变更时间，UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_run_tenant` (`tenant_id`,`id`),
  UNIQUE KEY `uk_run_key` (`tenant_id`,`actor_id`,`idempotency_key`),
  UNIQUE KEY `uk_run_active` (`active_key`),
  KEY `ix_run_queue` (`status`,`queue_expires_at`,`created_at`),
  KEY `ix_run_owner` (`tenant_id`,`actor_id`,`plan_id`,`created_at`),
  KEY `ix_run_lease` (`status`,`lease_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='按租户和创建人归属的异常调查任务；保存绑定清单、当前执行快照、运行状态、预算与报告，终态按保留策略清理，不承载派单执行授权。';

CREATE TABLE `agent_message` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '本表记录主键；数据库自增',
  `conversation_id` varchar(32) NOT NULL COMMENT '关联 agent_conversation.id 的会话标识',
  `user_id` varchar(64) NOT NULL COMMENT '记录所属用户标识，结合租户确定数据归属',
  `role` varchar(16) NOT NULL COMMENT '消息角色：user、assistant、tool_call、tool_result、card',
  `content` mediumtext COMMENT '文本或工具摘要；结构化卡片内容保存于payload；允许为空，表示尚无该项数据',
  `card_type` varchar(16) DEFAULT NULL COMMENT '卡片类型：preview、plan、result；非卡片消息为空',
  `payload` json DEFAULT NULL COMMENT '卡片JSON，含预览、待确认清单或执行结果；允许为空，表示尚无该项数据',
  `tool_name` varchar(64) DEFAULT NULL COMMENT '工具调用名称；普通文本消息为空',
  `preview_id` varchar(32) DEFAULT NULL COMMENT '关联 dispatch_preview.id 的预览快照标识；允许为空，表示尚无该项数据',
  `plan_id` varchar(32) DEFAULT NULL COMMENT '关联 dispatch_plan.id 的派单清单标识；允许为空，表示尚无该项数据',
  `model` varchar(64) DEFAULT NULL COMMENT '实际使用的模型名称；允许为空，表示尚无该项数据',
  `prompt_tokens` int DEFAULT NULL COMMENT '模型输入token数；接口未返回用量时为空',
  `completion_tokens` int DEFAULT NULL COMMENT '模型输出token数；接口未返回用量时为空',
  `latency_ms` int DEFAULT NULL COMMENT '本次处理耗时，单位毫秒；允许为空，表示尚无该项数据',
  `created_at` datetime(3) NOT NULL COMMENT '记录创建时间',
  `tenant_id` varchar(64) NOT NULL COMMENT '数据所属租户标识；查询和写入必须限定租户',
  `evidence_id` bigint DEFAULT NULL COMMENT '关联trace_event.id的证据标识；普通消息为空，唯一索引用于防止重复补写',
  `trace_id` varchar(64) DEFAULT NULL COMMENT '请求链路标识，关联应用日志；允许为空，表示尚无该项数据',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_message_evidence` (`evidence_id`),
  KEY `idx_conv` (`conversation_id`,`id`),
  KEY `idx_user_time` (`user_id`,`created_at`),
  KEY `idx_preview` (`preview_id`),
  KEY `idx_message_tenant` (`tenant_id`,`conversation_id`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话消息与业务卡片；按会话顺序保存，证据消息可幂等补写';

CREATE TABLE `app_session` (
  `token_hash` char(64) NOT NULL COMMENT '会话令牌的SHA-256摘要；不保存原始令牌',
  `tenant_id` varchar(64) NOT NULL COMMENT '数据所属租户标识；查询和写入必须限定租户',
  `user_id` varchar(64) NOT NULL COMMENT '记录所属用户标识，结合租户确定数据归属',
  `expires_at` datetime NOT NULL COMMENT '有效期截止时间；到期后不得继续认领或使用',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  PRIMARY KEY (`token_hash`),
  KEY `idx_session_user` (`tenant_id`,`user_id`),
  KEY `idx_session_expiry` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='当前Demo账号会话；真实外部认证系统接入待完成';

CREATE TABLE `app_user` (
  `tenant_id` varchar(64) NOT NULL COMMENT '数据所属租户标识；查询和写入必须限定租户',
  `user_id` varchar(64) NOT NULL COMMENT '记录所属用户标识，结合租户确定数据归属',
  `display_name` varchar(128) NOT NULL COMMENT '用户展示名称',
  `password_hash` varchar(256) NOT NULL COMMENT '密码派生摘要；不得保存明文密码',
  `companies_json` text NOT NULL COMMENT '允许访问的公司代码JSON数组',
  `permissions_json` text NOT NULL COMMENT '允许访问的报表权限码JSON数组',
  `is_admin` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否具有Demo管理权限：0否，1是',
  `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '账号是否启用：0禁用，1启用',
  PRIMARY KEY (`tenant_id`,`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='当前Demo用户及业务授权；真实外部认证系统接入待完成';

CREATE TABLE `business_dispatch_request` (
  `tenant_id` varchar(64) NOT NULL COMMENT '数据所属租户标识；查询和写入必须限定租户',
  `request_id` varchar(160) NOT NULL COMMENT '租户内唯一幂等请求号；清单条目发送后保持稳定',
  `operator_id` varchar(64) NOT NULL COMMENT '实际执行派单的用户标识',
  `payload_hash` char(64) NOT NULL COMMENT '租户、用户、报表、候选记录和规则开关的SHA-256负载摘要',
  `report_id` varchar(64) NOT NULL COMMENT '关联 report_definition.report_id 的稳定报表标识',
  `record_id` varchar(128) NOT NULL COMMENT '来源业务表记录标识，以字符串保留原主键',
  `status` varchar(20) NOT NULL COMMENT '业务受理状态：PROCESSING处理中、SUCCESS成功、FAILED失败',
  `error_code` varchar(64) DEFAULT NULL COMMENT '失败原因业务编码；允许为空，表示尚无该项数据',
  `message` varchar(500) DEFAULT NULL COMMENT '操作结果或失败原因摘要；允许为空，表示尚无该项数据',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录最后更新时间',
  PRIMARY KEY (`tenant_id`,`request_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='HTTP MCP业务服务的派单幂等账本；业务写入与结果在同一事务保存';

CREATE TABLE `business_metric` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '本表记录主键；数据库自增',
  `tenant_id` varchar(64) NOT NULL COMMENT '数据所属租户标识；查询和写入必须限定租户',
  `operation` varchar(32) NOT NULL COMMENT '被统计的业务操作名称',
  `report_id` varchar(64) NOT NULL COMMENT '操作涉及的报表标识；汇总操作使用*',
  `version` varchar(128) NOT NULL COMMENT '该次操作记录的版本或指纹；无适用版本时为占位值',
  `outcome` varchar(32) NOT NULL COMMENT '业务执行结果编码',
  `duration_ms` bigint NOT NULL COMMENT '操作耗时，单位毫秒',
  `created_at` datetime(3) NOT NULL COMMENT '记录创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_metric_scope` (`tenant_id`,`operation`,`created_at`),
  KEY `idx_metric_retention` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='按租户记录业务操作结果和耗时；按保留策略清理';

CREATE TABLE `catalog_revision` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '本表记录主键；数据库自增',
  `tenant_id` varchar(64) NOT NULL COMMENT '数据所属租户标识；查询和写入必须限定租户',
  `report_id` varchar(64) NOT NULL COMMENT '关联 report_definition.report_id 的稳定报表标识',
  `catalog_version` bigint NOT NULL COMMENT '该报表目录版本号；定义变化后旧快照需重新验证',
  `definition_json` json NOT NULL COMMENT '该版本报表定义的完整JSON快照',
  `aliases_json` json NOT NULL COMMENT '该版本报表别名JSON数组快照',
  `created_by` varchar(64) NOT NULL COMMENT '创建操作的用户标识',
  `created_at` datetime(3) NOT NULL COMMENT '记录创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_catalog_revision` (`tenant_id`,`report_id`,`catalog_version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户报表目录与别名的不可变版本快照；供审计和回滚使用';

CREATE TABLE `conversation_erasure` (
  `conversation_id` varchar(32) NOT NULL COMMENT '关联 agent_conversation.id 的会话标识',
  `tenant_id` varchar(64) NOT NULL COMMENT '数据所属租户标识；查询和写入必须限定租户',
  `requested_by` varchar(64) NOT NULL COMMENT '申请清理会话的用户标识',
  `requested_at` datetime(3) NOT NULL COMMENT '会话清理申请时间',
  `status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT '清理状态：PENDING待处理、COMPLETED已完成',
  `completed_at` datetime(3) DEFAULT NULL COMMENT '清理完成时间；未完成时为空',
  PRIMARY KEY (`conversation_id`),
  KEY `idx_erasure_pending` (`status`,`requested_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话删除墓碑与异步清理进度；防止在途工作恢复已删除内容';

CREATE TABLE `dispatch_audit` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '本表记录主键；数据库自增',
  `tenant_id` varchar(64) NOT NULL COMMENT '数据所属租户标识；查询和写入必须限定租户',
  `user_id` varchar(64) NOT NULL COMMENT '记录所属用户标识，结合租户确定数据归属',
  `conversation_id` varchar(32) DEFAULT NULL COMMENT '关联 agent_conversation.id 的会话标识；允许为空，表示尚无该项数据',
  `preview_id` varchar(32) DEFAULT NULL COMMENT '关联 dispatch_preview.id 的预览快照标识；允许为空，表示尚无该项数据',
  `plan_id` varchar(32) DEFAULT NULL COMMENT '关联 dispatch_plan.id 的派单清单标识；允许为空，表示尚无该项数据',
  `plan_item_id` bigint DEFAULT NULL COMMENT '关联 dispatch_plan_item.id 的清单条目标识；允许为空，表示尚无该项数据',
  `source` varchar(16) NOT NULL COMMENT '派单入口来源：agent自动清单、manual人工选择',
  `report_id` varchar(64) NOT NULL COMMENT '关联 report_definition.report_id 的稳定报表标识',
  `report_name` varchar(256) DEFAULT NULL COMMENT '记录生成时的报表名称快照；允许为空，表示尚无该项数据',
  `record_id` varchar(128) DEFAULT NULL COMMENT '来源业务表记录标识，以字符串保留原主键；允许为空，表示尚无该项数据',
  `doc_no` varchar(128) DEFAULT NULL COMMENT '来源业务单据号，用于展示和人工核对；允许为空，表示尚无该项数据',
  `company_code` varchar(64) DEFAULT NULL COMMENT '业务记录所属公司代码；用于公司权限隔离；允许为空，表示尚无该项数据',
  `amount` decimal(18,2) DEFAULT NULL COMMENT '业务金额；小数精度2位，币种沿用来源业务账本；允许为空，表示尚无该项数据',
  `rule_id` bigint DEFAULT NULL COMMENT '关联 dispatch_rule.id 的命中规则标识；允许为空，表示尚无该项数据',
  `rule_name` varchar(64) DEFAULT NULL COMMENT '命中规则名称快照；允许为空，表示尚无该项数据',
  `rule_version` int DEFAULT NULL COMMENT '命中规则的整数版本号；允许为空，表示尚无该项数据',
  `catalog_version` bigint DEFAULT NULL COMMENT '该报表目录版本号；定义变化后旧快照需重新验证；允许为空，表示尚无该项数据',
  `rule_fingerprint` varchar(128) DEFAULT NULL COMMENT '预览范围内生效规则版本的聚合指纹；允许为空，表示尚无该项数据',
  `permission_version` varchar(128) DEFAULT NULL COMMENT '用户公司与报表权限的版本指纹，用于识别授权变化；允许为空，表示尚无该项数据',
  `success` tinyint(1) NOT NULL COMMENT '是否成功：1成功，0失败或跳过；详细结果见outcome',
  `outcome` varchar(16) NOT NULL COMMENT '结果：SUCCESS成功、FAILED失败、SKIPPED复核未通过、UNKNOWN结果待核对',
  `error_code` varchar(64) DEFAULT NULL COMMENT '失败原因业务编码；允许为空，表示尚无该项数据',
  `external_request_id` varchar(128) DEFAULT NULL COMMENT '发往业务服务的稳定幂等请求号；核对和重试沿用此号；允许为空，表示尚无该项数据',
  `message` varchar(1024) DEFAULT NULL COMMENT '操作结果或失败原因摘要；允许为空，表示尚无该项数据',
  `trace_id` varchar(64) DEFAULT NULL COMMENT '请求链路标识，关联应用日志；允许为空，表示尚无该项数据',
  `created_at` datetime(3) NOT NULL COMMENT '记录创建时间',
  `evidence_id` bigint DEFAULT NULL COMMENT '关联trace_event.id的不可变证据标识；历史记录可为空',
  `execution_version` bigint DEFAULT NULL COMMENT '清单执行轮次；每次认领执行或重试递增，旧轮次不得回写；允许为空，表示尚无该项数据',
  `attempt_count` int DEFAULT NULL COMMENT '条目派单尝试次数；每次准备发送时递增；允许为空，表示尚无该项数据',
  `phase` varchar(32) DEFAULT NULL COMMENT '产生证据时的业务阶段，例如发送或结果核对；允许为空，表示尚无该项数据',
  `rule_snapshot` json DEFAULT NULL COMMENT '规则快照JSON，含标识、版本、表达式及来源；无规则的手工派单为空；命中规则时为空表示当前证据缺失',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_audit_evidence` (`evidence_id`),
  KEY `idx_user_time` (`user_id`,`created_at`),
  KEY `idx_conv` (`conversation_id`),
  KEY `idx_plan` (`plan_id`),
  KEY `idx_audit_tenant_user` (`tenant_id`,`user_id`,`created_at`),
  KEY `idx_audit_report` (`report_id`,`created_at`),
  KEY `idx_audit_preview` (`preview_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='逐条派单结果审计；保存业务快照并与持久化证据事件关联';

CREATE TABLE `dispatch_gateway_request` (
  `tenant_id` varchar(64) NOT NULL COMMENT '数据所属租户标识；查询和写入必须限定租户',
  `request_id` varchar(128) NOT NULL COMMENT '租户内唯一的派单幂等请求号',
  `report_id` varchar(64) NOT NULL COMMENT '关联 report_definition.report_id 的稳定报表标识',
  `record_id` varchar(128) NOT NULL COMMENT '来源业务表记录标识，以字符串保留原主键',
  `status` varchar(16) NOT NULL COMMENT '网关结果状态：SUCCESS成功、FAILED失败',
  `error_code` varchar(64) DEFAULT NULL COMMENT '失败原因业务编码；允许为空，表示尚无该项数据',
  `message` varchar(512) DEFAULT NULL COMMENT '操作结果或失败原因摘要；允许为空，表示尚无该项数据',
  `created_at` datetime NOT NULL COMMENT '记录创建时间',
  PRIMARY KEY (`tenant_id`,`request_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='本地派单网关的幂等结果账本；按租户和请求号隔离';

CREATE TABLE `dispatch_job` (
  `id` varchar(64) NOT NULL COMMENT '服务端生成的异步派单任务标识',
  `tenant_id` varchar(64) NOT NULL COMMENT '数据所属租户标识；查询和写入必须限定租户',
  `user_id` varchar(64) NOT NULL COMMENT '记录所属用户标识，结合租户确定数据归属',
  `plan_id` varchar(64) DEFAULT NULL COMMENT '关联 dispatch_plan.id 的派单清单标识；允许为空，表示尚无该项数据',
  `action` varchar(32) NOT NULL COMMENT '动作：CONFIRM、RETRY_FAILED、RECONCILE、DIRECT、OP_RETRY_FAILED、OP_RECONCILE',
  `expected_version` bigint DEFAULT NULL COMMENT '提交时冻结的清单执行版本；DIRECT尚无清单时为空',
  `idempotency_key` varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '用户操作幂等键；同租户同用户同键只能对应相同负载',
  `payload_json` mediumtext NOT NULL COMMENT '规范化任务请求JSON，含清单或人工记录范围及运维原因',
  `trace_id` varchar(64) DEFAULT NULL COMMENT '请求链路标识，关联应用日志；允许为空，表示尚无该项数据',
  `status` varchar(16) NOT NULL DEFAULT 'QUEUED' COMMENT '任务状态：QUEUED排队、RUNNING执行中、SUCCEEDED完成、FAILED失败',
  `claim_token` varchar(64) DEFAULT NULL COMMENT '本次认领令牌；条件更新防止旧工作线程写入新任务；允许为空，表示尚无该项数据',
  `lease_until` datetime(3) DEFAULT NULL COMMENT '执行租约截止时间；超时标失败并提示核对，不自动重发；允许为空，表示尚无该项数据',
  `expires_at` datetime(3) NOT NULL COMMENT '排队截止时间；提交后10分钟，超时尚未启动的任务置失败',
  `result_json` mediumtext COMMENT '脱敏业务结果JSON；未完成或超过结果保留期时为空',
  `message` varchar(512) DEFAULT NULL COMMENT '操作结果或失败原因摘要；允许为空，表示尚无该项数据',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录创建时间',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录最后更新时间',
  `active_plan` varchar(64) GENERATED ALWAYS AS ((case when (`status` in (_utf8mb4'QUEUED',_utf8mb4'RUNNING')) then `plan_id` else NULL end)) STORED COMMENT '生成列：QUEUED或RUNNING时为plan_id，否则NULL；唯一索引限制清单在途任务',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dispatch_job_request` (`tenant_id`,`user_id`,`idempotency_key`),
  UNIQUE KEY `uk_dispatch_job_active_plan` (`active_plan`),
  KEY `idx_dispatch_job_queue` (`status`,`created_at`),
  KEY `idx_dispatch_job_tenant_queue` (`tenant_id`,`status`,`user_id`),
  KEY `idx_dispatch_job_lease` (`status`,`lease_until`),
  KEY `idx_dispatch_job_owner` (`tenant_id`,`user_id`,`plan_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='持久化派单命令队列；仅排队任务可认领，中断执行不得自动重发';

CREATE TABLE `dispatch_job_queue` (
  `tenant_id` varchar(64) NOT NULL COMMENT '数据所属租户标识；查询和写入必须限定租户',
  PRIMARY KEY (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='派单任务入队的租户锁行；串行化租户容量统计与写入';

CREATE TABLE `dispatch_plan` (
  `id` varchar(32) NOT NULL COMMENT '服务端生成的派单清单主键',
  `preview_id` varchar(32) NOT NULL COMMENT '关联 dispatch_preview.id 的预览快照标识',
  `tenant_id` varchar(64) NOT NULL COMMENT '数据所属租户标识；查询和写入必须限定租户',
  `user_id` varchar(64) NOT NULL COMMENT '记录所属用户标识，结合租户确定数据归属',
  `conversation_id` varchar(32) DEFAULT NULL COMMENT '关联 agent_conversation.id 的会话标识；允许为空，表示尚无该项数据',
  `status` varchar(16) NOT NULL COMMENT '清单状态：PENDING待确认、EXECUTING执行、REVIEW_REQUIRED待核对、EXECUTED结束、CANCELLED取消、EXPIRED失效',
  `status_reason` varchar(32) DEFAULT NULL COMMENT '当前状态的原因编码，供展示和恢复判断；允许为空，表示尚无该项数据',
  `exclude_json` json DEFAULT NULL COMMENT '用户排除的单据号JSON数组；无排除时可为空',
  `item_count` int NOT NULL COMMENT '清单内条目总数',
  `success_count` int NOT NULL DEFAULT '0' COMMENT '已成功派单的条目数',
  `failed_count` int NOT NULL DEFAULT '0' COMMENT '当前执行汇总中的非成功条目数；具体状态与可重试数量须读取条目',
  `idempotency_key` varchar(128) NOT NULL COMMENT '创建清单幂等键；同租户同键只生成一份清单',
  `created_at` datetime(3) NOT NULL COMMENT '记录创建时间',
  `expires_at` datetime(3) NOT NULL COMMENT '有效期截止时间；到期后不得继续认领或使用',
  `confirmed_at` datetime(3) DEFAULT NULL COMMENT '用户显式确认的时间；未确认时为空',
  `confirmed_by` varchar(64) DEFAULT NULL COMMENT '显式确认清单的用户标识；未确认时为空',
  `finished_at` datetime(3) DEFAULT NULL COMMENT '执行结束时间；尚未结束时为空',
  `updated_at` datetime(3) NOT NULL COMMENT '记录最后更新时间',
  `pending_guard` varchar(32) GENERATED ALWAYS AS ((case when (`status` = _utf8mb4'PENDING') then `conversation_id` end)) STORED COMMENT '生成列：PENDING时为conversation_id，否则NULL；限制同会话只有一份待确认清单',
  `execution_version` bigint NOT NULL DEFAULT '0' COMMENT '清单执行轮次；每次认领执行或重试递增，旧轮次不得回写',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_plan_idempotency` (`tenant_id`,`idempotency_key`),
  UNIQUE KEY `uk_plan_pending` (`pending_guard`),
  KEY `idx_plan_owner` (`tenant_id`,`user_id`,`conversation_id`,`status`),
  KEY `idx_plan_preview` (`preview_id`),
  KEY `idx_ops_plan` (`tenant_id`,`updated_at`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='待确认派单清单与执行轮次；显式确认后才允许业务写入';

CREATE TABLE `dispatch_plan_item` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '本表记录主键；数据库自增',
  `plan_id` varchar(32) NOT NULL COMMENT '关联 dispatch_plan.id 的派单清单标识',
  `seq` int NOT NULL COMMENT '所属快照或清单内的展示顺序',
  `report_id` varchar(64) NOT NULL COMMENT '关联 report_definition.report_id 的稳定报表标识',
  `report_name` varchar(256) NOT NULL COMMENT '记录生成时的报表名称快照',
  `catalog_version` bigint NOT NULL COMMENT '该报表目录版本号；定义变化后旧快照需重新验证',
  `record_id` varchar(128) NOT NULL COMMENT '来源业务表记录标识，以字符串保留原主键',
  `doc_no` varchar(128) DEFAULT NULL COMMENT '来源业务单据号，用于展示和人工核对；允许为空，表示尚无该项数据',
  `company_code` varchar(64) DEFAULT NULL COMMENT '业务记录所属公司代码；用于公司权限隔离；允许为空，表示尚无该项数据',
  `label` varchar(512) DEFAULT NULL COMMENT '业务记录展示摘要快照；允许为空，表示尚无该项数据',
  `counterparty_json` json DEFAULT NULL COMMENT '交易对方实体快照JSON，结构为id、name、aliases；标识在所属租户和公司内稳定；空表示来源未提供客户实体',
  `fields_json` json NOT NULL COMMENT '已配置标量字段快照JSON数组，元素为name、type、value；value为空表示来源空值；随预览或清单保留，不重新读取来源',
  `amount` decimal(18,2) DEFAULT NULL COMMENT '业务金额；小数精度2位，币种沿用来源业务账本；允许为空，表示尚无该项数据',
  `biz_date` date DEFAULT NULL COMMENT '业务发生日期；按来源报表日期字段取值；允许为空，表示尚无该项数据',
  `rule_id` bigint DEFAULT NULL COMMENT '关联 dispatch_rule.id 的命中规则标识；允许为空，表示尚无该项数据',
  `rule_name` varchar(64) DEFAULT NULL COMMENT '命中规则名称快照；允许为空，表示尚无该项数据',
  `rule_version` int DEFAULT NULL COMMENT '命中规则的整数版本号；允许为空，表示尚无该项数据',
  `status` varchar(16) NOT NULL COMMENT '条目状态：PENDING待执行、SUCCESS成功、FAILED失败、SKIPPED未发送、UNKNOWN发送后结果不明',
  `attempt_count` int NOT NULL DEFAULT '0' COMMENT '条目派单尝试次数；每次准备发送时递增',
  `external_request_id` varchar(128) DEFAULT NULL COMMENT '发往业务服务的稳定幂等请求号；核对和重试沿用此号；允许为空，表示尚无该项数据',
  `error_code` varchar(64) DEFAULT NULL COMMENT '失败原因业务编码；允许为空，表示尚无该项数据',
  `error_message` varchar(1024) DEFAULT NULL COMMENT '失败详情摘要；供人工核对，不包含凭据；允许为空，表示尚无该项数据',
  `updated_at` datetime(3) NOT NULL COMMENT '记录最后更新时间',
  `rule_snapshot` json DEFAULT NULL COMMENT '规则快照JSON，含标识、版本、表达式及来源；无规则的手工派单为空；命中规则时为空表示当前证据缺失',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_plan_record` (`plan_id`,`report_id`,`record_id`),
  KEY `idx_plan_seq` (`plan_id`,`seq`),
  KEY `idx_item_retry` (`status`,`updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='派单清单条目与逐次执行结果；UNKNOWN需核对后才能决定重试';

CREATE TABLE `dispatch_preview` (
  `id` varchar(32) NOT NULL COMMENT '本表记录主键',
  `tenant_id` varchar(64) NOT NULL COMMENT '数据所属租户标识；查询和写入必须限定租户',
  `user_id` varchar(64) NOT NULL COMMENT '记录所属用户标识，结合租户确定数据归属',
  `conversation_id` varchar(32) DEFAULT NULL COMMENT '关联 agent_conversation.id 的会话标识；允许为空，表示尚无该项数据',
  `source` varchar(16) NOT NULL COMMENT '预览入口来源，含semantic、agent、manual、selection、api',
  `report_ids` json NOT NULL COMMENT '范围内稳定报表标识JSON数组，按目录顺序',
  `company_codes` json NOT NULL COMMENT '预览生效的公司代码JSON数组',
  `query_json` json NOT NULL COMMENT '统一预览命令JSON，含操作、报表范围、筛选、排除及范围修改方式',
  `summary_json` json DEFAULT NULL COMMENT '预览汇总JSON；升级前快照可为空',
  `catalog_version` varchar(128) NOT NULL COMMENT '范围内报表目录版本的聚合指纹',
  `rule_version` varchar(128) NOT NULL COMMENT '范围内生效规则的聚合版本指纹',
  `permission_version` varchar(128) NOT NULL COMMENT '用户公司与报表权限的版本指纹，用于识别授权变化',
  `status` varchar(16) NOT NULL COMMENT '快照状态：BUILDING构建中、ACTIVE有效、SUPERSEDED被替代、EXPIRED失效、CONSUMED已执行',
  `status_reason` varchar(32) DEFAULT NULL COMMENT '当前状态的原因编码，供展示和恢复判断；允许为空，表示尚无该项数据',
  `total_count` int NOT NULL DEFAULT '0' COMMENT '统计记录总数',
  `total_amount` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '候选记录金额合计；小数精度2位，币种沿用来源账本',
  `expires_at` datetime(3) NOT NULL COMMENT '有效期截止时间；到期后不得继续认领或使用',
  `created_at` datetime(3) NOT NULL COMMENT '记录创建时间',
  `updated_at` datetime(3) NOT NULL COMMENT '记录最后更新时间',
  `active_guard` varchar(32) GENERATED ALWAYS AS ((case when (`status` = _utf8mb4'ACTIVE') then `conversation_id` end)) STORED COMMENT '生成列：ACTIVE时为conversation_id，否则NULL；限制同会话只有一份有效快照',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_preview_active` (`active_guard`),
  KEY `idx_preview_owner` (`tenant_id`,`user_id`,`conversation_id`,`created_at`),
  KEY `idx_preview_conv` (`conversation_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='权限、目录与规则绑定的预览快照；新请求可替代旧快照';

CREATE TABLE `dispatch_preview_item` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '本表记录主键；数据库自增',
  `preview_id` varchar(32) NOT NULL COMMENT '关联 dispatch_preview.id 的预览快照标识',
  `seq` int NOT NULL COMMENT '所属快照或清单内的展示顺序',
  `report_id` varchar(64) NOT NULL COMMENT '关联 report_definition.report_id 的稳定报表标识',
  `report_name` varchar(256) NOT NULL COMMENT '记录生成时的报表名称快照',
  `catalog_version` bigint NOT NULL COMMENT '该报表目录版本号；定义变化后旧快照需重新验证',
  `record_id` varchar(128) NOT NULL COMMENT '来源业务表记录标识，以字符串保留原主键',
  `doc_no` varchar(128) DEFAULT NULL COMMENT '来源业务单据号，用于展示和人工核对；允许为空，表示尚无该项数据',
  `company_code` varchar(64) DEFAULT NULL COMMENT '业务记录所属公司代码；用于公司权限隔离；允许为空，表示尚无该项数据',
  `label` varchar(512) DEFAULT NULL COMMENT '业务记录展示摘要快照；允许为空，表示尚无该项数据',
  `counterparty_json` json DEFAULT NULL COMMENT '交易对方实体快照JSON，结构为id、name、aliases；标识在所属租户和公司内稳定；空表示来源未提供客户实体',
  `fields_json` json NOT NULL COMMENT '已配置标量字段快照JSON数组，元素为name、type、value；value为空表示来源空值；随预览或清单保留，不重新读取来源',
  `amount` decimal(18,2) DEFAULT NULL COMMENT '业务金额；小数精度2位，币种沿用来源业务账本；允许为空，表示尚无该项数据',
  `biz_date` date DEFAULT NULL COMMENT '业务发生日期；按来源报表日期字段取值；允许为空，表示尚无该项数据',
  `rule_id` bigint DEFAULT NULL COMMENT '关联 dispatch_rule.id 的命中规则标识；允许为空，表示尚无该项数据',
  `rule_name` varchar(64) DEFAULT NULL COMMENT '命中规则名称快照；允许为空，表示尚无该项数据',
  `rule_version` int DEFAULT NULL COMMENT '命中规则的整数版本号；允许为空，表示尚无该项数据',
  `rule_description` varchar(512) DEFAULT NULL COMMENT '命中规则说明快照；允许为空，表示尚无该项数据',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_preview_record` (`preview_id`,`report_id`,`record_id`),
  KEY `idx_preview_seq` (`preview_id`,`seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='预览业务事实和规则命中快照；不因来源数据后续变化而改写';

CREATE TABLE `dispatch_preview_job` (
  `id` varchar(32) NOT NULL COMMENT '服务端生成的预览任务主键',
  `tenant_id` varchar(64) NOT NULL COMMENT '数据所属租户标识；查询和写入必须限定租户',
  `user_id` varchar(64) NOT NULL COMMENT '记录所属用户标识，结合租户确定数据归属',
  `conversation_id` varchar(32) DEFAULT NULL COMMENT '关联 agent_conversation.id 的会话标识；允许为空，表示尚无该项数据',
  `status` varchar(16) NOT NULL COMMENT '任务状态：QUEUED排队、RUNNING运行、SUCCEEDED成功、FAILED失败、CANCELLED取消',
  `stage` varchar(32) NOT NULL COMMENT '处理阶段：WAITING、SCANNING、DONE，或QUEUE、RESOLVE、QUERY、TIMEOUT、INTERRUPTED、CANCELLED',
  `scanned_rows` int NOT NULL DEFAULT '0' COMMENT '已扫描的来源业务记录数，用于进度展示',
  `message` varchar(512) DEFAULT NULL COMMENT '操作结果或失败原因摘要；允许为空，表示尚无该项数据',
  `preview_id` varchar(32) DEFAULT NULL COMMENT '关联 dispatch_preview.id 的预览快照标识；允许为空，表示尚无该项数据',
  `created_at` datetime NOT NULL COMMENT '记录创建时间',
  `updated_at` datetime NOT NULL COMMENT '记录最后更新时间',
  `request_version` bigint NOT NULL DEFAULT '0' COMMENT '提交时取得的会话预览请求序号；后续请求使旧任务失效',
  PRIMARY KEY (`id`),
  KEY `idx_job_owner` (`tenant_id`,`user_id`,`created_at`),
  KEY `idx_job_conversation` (`tenant_id`,`user_id`,`conversation_id`,`created_at`),
  KEY `idx_job_request_order` (`tenant_id`,`user_id`,`conversation_id`,`request_version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='长查询预览任务的状态与进度；跨实例可读，重启中断任务标失败';

CREATE TABLE `dispatch_rule` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '本表记录主键；数据库自增',
  `report_id` varchar(64) NOT NULL COMMENT '关联 report_definition.report_id 的稳定报表标识',
  `company_code` varchar(64) NOT NULL DEFAULT '*' COMMENT '规则公司范围；*表示通配，具体公司规则优先',
  `name` varchar(64) NOT NULL COMMENT '规则展示名称',
  `description` varchar(512) DEFAULT NULL COMMENT '供用户理解的规则说明；允许为空，表示尚无该项数据',
  `expression` text NOT NULL COMMENT 'Aviator规则表达式，只在已验证的业务事实字段上求值',
  `version` int NOT NULL COMMENT '规则整数版本；同租户、报表、公司范围内唯一',
  `status` varchar(16) NOT NULL COMMENT '规则状态：draft草稿、published已发布、disabled停用',
  `effective_from` datetime DEFAULT NULL COMMENT '生效开始时间，含此时刻；空表示不限制开始时间',
  `effective_to` datetime DEFAULT NULL COMMENT '生效结束时间，不含此时刻；空表示不限制结束时间',
  `created_at` datetime NOT NULL COMMENT '记录创建时间',
  `updated_by` varchar(64) DEFAULT NULL COMMENT '最后修改操作的用户标识；允许为空，表示尚无该项数据',
  `updated_at` datetime DEFAULT NULL COMMENT '记录最后更新时间；允许为空，表示尚无该项数据',
  `tenant_id` varchar(64) NOT NULL COMMENT '数据所属租户标识；查询和写入必须限定租户',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rule_report` (`tenant_id`,`report_id`,`company_code`,`version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户报表与公司范围的版本化派单规则；发布后按生效区间求值';

CREATE TABLE `dispatch_rule_history` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '本表记录主键；数据库自增',
  `rule_id` bigint NOT NULL COMMENT '关联 dispatch_rule.id 的命中规则标识',
  `report_id` varchar(64) NOT NULL COMMENT '关联 report_definition.report_id 的稳定报表标识',
  `company_code` varchar(64) NOT NULL COMMENT '该历史规则的公司范围；*表示通配',
  `version` int NOT NULL COMMENT '操作涉及的规则整数版本',
  `name` varchar(64) DEFAULT NULL COMMENT '操作时的规则名称快照；允许为空，表示尚无该项数据',
  `expression` text NOT NULL COMMENT '操作时的Aviator规则表达式快照',
  `description` varchar(512) DEFAULT NULL COMMENT '操作时的规则说明快照；允许为空，表示尚无该项数据',
  `action` varchar(16) NOT NULL COMMENT '规则变更类型：publish发布、disable停用、rollback回滚',
  `operated_by` varchar(64) DEFAULT NULL COMMENT '执行规则变更的用户标识；允许为空，表示尚无该项数据',
  `operated_at` datetime DEFAULT NULL COMMENT '规则变更操作时间；允许为空，表示尚无该项数据',
  `tenant_id` varchar(64) NOT NULL COMMENT '数据所属租户标识；查询和写入必须限定租户',
  PRIMARY KEY (`id`),
  KEY `idx_scope` (`report_id`,`company_code`,`id`),
  KEY `idx_history_tenant` (`tenant_id`,`report_id`,`company_code`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='规则发布、停用与回滚的历史快照；保留当时表达式和版本';

CREATE TABLE `operations_audit` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '本表记录主键；数据库自增',
  `tenant_id` varchar(64) NOT NULL COMMENT '数据所属租户标识；查询和写入必须限定租户',
  `actor_id` varchar(64) NOT NULL COMMENT '发起运维操作的管理员用户标识',
  `action` varchar(64) NOT NULL COMMENT '运维动作名称',
  `resource_id` varchar(128) NOT NULL COMMENT '被操作的资源标识，例如清单或报表',
  `outcome` varchar(32) NOT NULL COMMENT '业务执行结果编码',
  `reason` varchar(512) DEFAULT NULL COMMENT '运维操作原因，保存前脱敏；允许为空，表示尚无该项数据',
  `created_at` datetime(3) NOT NULL COMMENT '记录创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_ops_audit` (`tenant_id`,`created_at`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户管理员运维操作审计；记录对象、原因和结果';

CREATE TABLE `operations_policy` (
  `tenant_id` varchar(64) NOT NULL COMMENT '数据所属租户标识；查询和写入必须限定租户',
  `policy_key` varchar(128) NOT NULL COMMENT '租户内策略类型标识',
  `version` bigint NOT NULL COMMENT '策略乐观锁版本；修改时递增',
  `payload` json NOT NULL COMMENT '策略配置JSON，结构随policy_key确定',
  `updated_by` varchar(64) NOT NULL COMMENT '最后修改操作的用户标识',
  `updated_at` datetime(3) NOT NULL COMMENT '记录最后更新时间',
  PRIMARY KEY (`tenant_id`,`policy_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户当前运维策略；配额、保留期等配置通过版本条件更新';

CREATE TABLE `operations_policy_revision` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '本表记录主键；数据库自增',
  `tenant_id` varchar(64) NOT NULL COMMENT '数据所属租户标识；查询和写入必须限定租户',
  `policy_key` varchar(128) NOT NULL COMMENT '租户内策略类型标识',
  `version` bigint NOT NULL COMMENT '保存的策略历史版本号',
  `payload` json NOT NULL COMMENT '该版本策略配置JSON快照',
  `created_by` varchar(64) NOT NULL COMMENT '创建操作的用户标识',
  `created_at` datetime(3) NOT NULL COMMENT '记录创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_policy_revision` (`tenant_id`,`policy_key`,`version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户运维策略的不可变历史版本；供审计和回滚';

CREATE TABLE `report_alias` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '本表记录主键；数据库自增',
  `report_id` varchar(64) NOT NULL COMMENT '关联 report_definition.report_id 的稳定报表标识',
  `alias` varchar(256) NOT NULL COMMENT '报表简称、口语名、英文名或历史名称',
  `alias_type` varchar(32) NOT NULL COMMENT '别名类型：SHORT、COLLOQUIAL、ENGLISH、HISTORICAL、DEPARTMENT、TYPO',
  `priority` int NOT NULL DEFAULT '0' COMMENT '候选排序优先级；数值越大越靠前',
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '别名状态：ACTIVE启用、DISABLED停用',
  `created_by` varchar(64) DEFAULT NULL COMMENT '创建操作的用户标识；允许为空，表示尚无该项数据',
  `created_at` datetime(3) NOT NULL COMMENT '记录创建时间',
  `tenant_id` varchar(64) NOT NULL COMMENT '数据所属租户标识；查询和写入必须限定租户',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_report_alias` (`report_id`,`alias`),
  KEY `idx_alias_search` (`alias`,`status`),
  KEY `idx_alias_tenant` (`tenant_id`,`report_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户报表别名；参与可见报表的名称解析和候选排序';

CREATE TABLE `report_definition` (
  `report_id` varchar(64) NOT NULL COMMENT '稳定报表主键；创建后不得因改名或改编码变化',
  `report_code` varchar(128) NOT NULL COMMENT '租户内唯一的对外接口编码',
  `report_name` varchar(256) NOT NULL COMMENT '当前报表展示名称',
  `domain_code` varchar(64) NOT NULL COMMENT '业务域编码，例如sales、receivable、expense、purchase',
  `description` varchar(512) DEFAULT NULL COMMENT '报表业务用途说明；允许为空，表示尚无该项数据',
  `query_mode` varchar(32) NOT NULL COMMENT '查询模式：STANDARD字段映射、ADAPTER专用适配器',
  `query_config` json NOT NULL COMMENT '查询配置JSON；STANDARD含表及字段映射，ADAPTER含adapter编码',
  `dispatch_enabled` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否允许派单：0否，1是；仍需发布状态与用户授权',
  `status` varchar(16) NOT NULL COMMENT '目录状态：DRAFT草稿、PUBLISHED已发布、DISABLED停用',
  `schema_version` int NOT NULL DEFAULT '1' COMMENT '业务事实字段及结果结构版本号',
  `catalog_version` bigint NOT NULL DEFAULT '1' COMMENT '该报表目录版本号；定义变化后旧快照需重新验证',
  `permission_code` varchar(128) NOT NULL COMMENT '访问该报表需要的业务权限码',
  `sort_order` int NOT NULL DEFAULT '0' COMMENT '报表展示及汇总排序值',
  `effective_from` datetime(3) DEFAULT NULL COMMENT '生效开始时间，含此时刻；空表示不限制开始时间',
  `effective_to` datetime(3) DEFAULT NULL COMMENT '生效结束时间，不含此时刻；空表示不限制结束时间',
  `owner_user_id` varchar(64) DEFAULT NULL COMMENT '报表业务负责人用户标识；允许为空，表示尚无该项数据',
  `created_by` varchar(64) DEFAULT NULL COMMENT '创建操作的用户标识；允许为空，表示尚无该项数据',
  `created_at` datetime(3) NOT NULL COMMENT '记录创建时间',
  `updated_by` varchar(64) DEFAULT NULL COMMENT '最后修改操作的用户标识；允许为空，表示尚无该项数据',
  `updated_at` datetime(3) NOT NULL COMMENT '记录最后更新时间',
  `tenant_id` varchar(64) NOT NULL COMMENT '数据所属租户标识；查询和写入必须限定租户',
  PRIMARY KEY (`report_id`),
  UNIQUE KEY `uk_report_code` (`tenant_id`,`report_code`),
  KEY `idx_report_status` (`status`,`dispatch_enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户报表目录；定义查询映射、授权、发布版本和派单能力';

CREATE TABLE `report_expense` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '本表记录主键；数据库自增',
  `tenant_id` varchar(64) NOT NULL COMMENT '数据所属租户标识；查询和写入必须限定租户',
  `company_code` varchar(10) NOT NULL COMMENT '业务记录所属公司代码；用于公司权限隔离',
  `expense_no` varchar(32) NOT NULL COMMENT '报销单据号',
  `expense_type` varchar(32) DEFAULT NULL COMMENT '费用类型；允许为空，表示尚无该项数据',
  `amount` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '业务金额；小数精度2位，币种沿用来源业务账本',
  `expense_date` date DEFAULT NULL COMMENT '费用发生日期；允许为空，表示尚无该项数据',
  `dispatch_status` tinyint NOT NULL DEFAULT '0' COMMENT '业务派单状态：0未派单，1已派单',
  `dispatched_at` datetime DEFAULT NULL COMMENT '业务派单完成时间；未派单时为空',
  PRIMARY KEY (`id`),
  KEY `idx_company_status` (`company_code`,`dispatch_status`),
  KEY `idx_tenant_company_status` (`tenant_id`,`company_code`,`dispatch_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='费用业务报表；本仓库演示业务数据，按租户和公司隔离';

CREATE TABLE `report_purchase` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '本表记录主键；数据库自增',
  `tenant_id` varchar(64) NOT NULL COMMENT '数据所属租户标识；查询和写入必须限定租户',
  `company_code` varchar(10) NOT NULL COMMENT '业务记录所属公司代码；用于公司权限隔离',
  `po_no` varchar(32) NOT NULL COMMENT '采购订单号',
  `supplier_name` varchar(64) DEFAULT NULL COMMENT '供应商名称；允许为空，表示尚无该项数据',
  `amount` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '业务金额；小数精度2位，币种沿用来源业务账本',
  `order_date` date DEFAULT NULL COMMENT '采购下单日期；允许为空，表示尚无该项数据',
  `dispatch_status` tinyint NOT NULL DEFAULT '0' COMMENT '业务派单状态：0未派单，1已派单',
  `dispatched_at` datetime DEFAULT NULL COMMENT '业务派单完成时间；未派单时为空',
  PRIMARY KEY (`id`),
  KEY `idx_company_status` (`company_code`,`dispatch_status`),
  KEY `idx_tenant_company_status` (`tenant_id`,`company_code`,`dispatch_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购业务报表；本仓库演示业务数据，按租户和公司隔离';

CREATE TABLE `report_receivable` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '本表记录主键；数据库自增',
  `tenant_id` varchar(64) NOT NULL COMMENT '数据所属租户标识；查询和写入必须限定租户',
  `company_code` varchar(10) NOT NULL COMMENT '业务记录所属公司代码；用于公司权限隔离',
  `invoice_no` varchar(32) NOT NULL COMMENT '应收发票号',
  `customer_name` varchar(64) DEFAULT NULL COMMENT '客户名称；允许为空，表示尚无该项数据',
  `customer_id` varchar(128) DEFAULT NULL COMMENT '来源客户稳定标识；在所属租户和公司内唯一；空表示未关联客户',
  `customer_aliases` json DEFAULT NULL COMMENT '来源维护的客户别名JSON字符串数组；空表示没有别名，不按相似名称自动推断',
  `amount` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '业务金额；小数精度2位，币种沿用来源业务账本',
  `due_date` date DEFAULT NULL COMMENT '应收到期日期；允许为空，表示尚无该项数据',
  `dispatch_status` tinyint NOT NULL DEFAULT '0' COMMENT '业务派单状态：0未派单，1已派单',
  `dispatched_at` datetime DEFAULT NULL COMMENT '业务派单完成时间；未派单时为空',
  PRIMARY KEY (`id`),
  KEY `idx_company_status` (`company_code`,`dispatch_status`),
  KEY `idx_tenant_company_status` (`tenant_id`,`company_code`,`dispatch_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='应收业务报表；本仓库演示业务数据，按租户和公司隔离';

CREATE TABLE `report_sales` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '本表记录主键；数据库自增',
  `tenant_id` varchar(64) NOT NULL COMMENT '数据所属租户标识；查询和写入必须限定租户',
  `company_code` varchar(10) NOT NULL COMMENT '业务记录所属公司代码；用于公司权限隔离',
  `order_no` varchar(32) NOT NULL COMMENT '销售订单号',
  `product_name` varchar(64) DEFAULT NULL COMMENT '销售产品名称；允许为空，表示尚无该项数据',
  `amount` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '业务金额；小数精度2位，币种沿用来源业务账本',
  `sale_date` date DEFAULT NULL COMMENT '销售发生日期；允许为空，表示尚无该项数据',
  `dispatch_status` tinyint NOT NULL DEFAULT '0' COMMENT '业务派单状态：0未派单，1已派单',
  `dispatched_at` datetime DEFAULT NULL COMMENT '业务派单完成时间；未派单时为空',
  PRIMARY KEY (`id`),
  KEY `idx_company_status` (`company_code`,`dispatch_status`),
  KEY `idx_tenant_company_status` (`tenant_id`,`company_code`,`dispatch_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='销售业务报表；本仓库演示业务数据，按租户和公司隔离';

CREATE TABLE `resolver_evaluation_run` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '本表记录主键；数据库自增',
  `tenant_id` varchar(64) NOT NULL COMMENT '数据所属租户标识；查询和写入必须限定租户',
  `fingerprint` varchar(64) NOT NULL COMMENT '评估所用目录及别名配置的版本指纹',
  `total_count` int NOT NULL COMMENT '评估样本总数',
  `passed_count` int NOT NULL COMMENT '符合预期的评估样本数',
  `result_json` json NOT NULL COMMENT '评估逐例结果及统计JSON',
  `created_at` datetime(3) NOT NULL COMMENT '记录创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_evaluation_version` (`tenant_id`,`fingerprint`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户报表名称解析的版本化评估结果；不代表模型或外部业务验收';

CREATE TABLE `resource_quota_lease` (
  `token` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '每次配额申请的唯一租约令牌',
  `scope_key` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联resource_quota_scope.scope_key的租户操作范围摘要',
  `user_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '记录所属用户标识，结合租户确定数据归属',
  `expires_at` datetime(6) NOT NULL COMMENT '配额租约截止时间；仅未过期租约可续租',
  PRIMARY KEY (`token`),
  KEY `idx_quota_scope_expiry` (`scope_key`,`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='并发任务的数据库配额租约；独立事务保存，避免Redis丢键超额';

CREATE TABLE `resource_quota_scope` (
  `scope_key` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '租户与操作名称JSON的SHA-256摘要，区分配额范围',
  PRIMARY KEY (`scope_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='并发配额范围锁行；串行化同租户同操作的容量检查';

CREATE TABLE `semantic_dialogue` (
  `conversation_id` varchar(32) NOT NULL COMMENT '关联 agent_conversation.id 的会话标识',
  `tenant_id` varchar(64) NOT NULL COMMENT '数据所属租户标识；查询和写入必须限定租户',
  `user_id` varchar(64) NOT NULL COMMENT '记录所属用户标识，结合租户确定数据归属',
  `version` bigint NOT NULL DEFAULT '0' COMMENT '状态乐观锁版本；每次保存递增',
  `state_json` json NOT NULL COMMENT 'DialogueState状态JSON，含期望范围、生效范围、选择及当前快照清单',
  `lease_token` varchar(32) DEFAULT NULL COMMENT '当前对话轮次独占租约令牌；未被认领时为空',
  `lease_until` datetime(3) DEFAULT NULL COMMENT '对话轮次租约截止时间；过期后旧轮次不得保存状态；允许为空，表示尚无该项数据',
  `updated_at` datetime(3) NOT NULL COMMENT '记录最后更新时间',
  PRIMARY KEY (`conversation_id`),
  KEY `idx_semantic_owner` (`tenant_id`,`user_id`,`updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话的权威语义状态；用版本和租约防止并行轮次覆盖';

CREATE TABLE `semantic_turn` (
  `request_id` varchar(32) NOT NULL COMMENT '服务端生成的对话轮次主键',
  `conversation_id` varchar(32) NOT NULL COMMENT '关联 agent_conversation.id 的会话标识',
  `tenant_id` varchar(64) NOT NULL COMMENT '数据所属租户标识；查询和写入必须限定租户',
  `user_id` varchar(64) NOT NULL COMMENT '记录所属用户标识，结合租户确定数据归属',
  `mode` varchar(16) NOT NULL COMMENT '处理模式标识；当前真实模型链路使用active',
  `state_version` bigint NOT NULL COMMENT '保存本轮证据时的会话状态版本',
  `utterance` varchar(2000) NOT NULL COMMENT '本轮用户输入，脱敏后最多2000字符',
  `intent_json` json DEFAULT NULL COMMENT '通过校验的SemanticIntent JSON；解析未成功时为空',
  `outcome` varchar(32) NOT NULL COMMENT '对话阶段或结果，取DialogueState.Phase名称',
  `reason` varchar(512) DEFAULT NULL COMMENT '澄清、拒绝或失败原因，脱敏后最多512字符；允许为空，表示尚无该项数据',
  `model` varchar(128) DEFAULT NULL COMMENT '实际使用的模型名称；允许为空，表示尚无该项数据',
  `latency_ms` bigint NOT NULL COMMENT '本次处理耗时，单位毫秒',
  `created_at` datetime(3) NOT NULL COMMENT '记录创建时间',
  PRIMARY KEY (`request_id`),
  KEY `idx_semantic_turn_conversation` (`tenant_id`,`conversation_id`,`created_at`),
  KEY `idx_semantic_turn_outcome` (`tenant_id`,`mode`,`outcome`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='语义对话逐轮解析与业务处理证据；按会话记录并保存脱敏内容';

CREATE TABLE `trace_event` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '本表记录主键；数据库自增',
  `event_key` varchar(191) NOT NULL COMMENT '跨补写重试保持不变的事件幂等键',
  `tenant_id` varchar(64) NOT NULL COMMENT '数据所属租户标识；查询和写入必须限定租户',
  `user_id` varchar(64) NOT NULL COMMENT '记录所属用户标识，结合租户确定数据归属',
  `conversation_id` varchar(32) DEFAULT NULL COMMENT '关联 agent_conversation.id 的会话标识；允许为空，表示尚无该项数据',
  `preview_id` varchar(32) DEFAULT NULL COMMENT '关联 dispatch_preview.id 的预览快照标识；允许为空，表示尚无该项数据',
  `plan_id` varchar(32) DEFAULT NULL COMMENT '关联 dispatch_plan.id 的派单清单标识；允许为空，表示尚无该项数据',
  `event_type` varchar(32) NOT NULL COMMENT '业务证据事件类型',
  `plan_item_id` bigint DEFAULT NULL COMMENT '关联 dispatch_plan_item.id 的清单条目标识；允许为空，表示尚无该项数据',
  `execution_version` bigint DEFAULT NULL COMMENT '清单执行轮次；每次认领执行或重试递增，旧轮次不得回写；允许为空，表示尚无该项数据',
  `attempt_count` int DEFAULT NULL COMMENT '条目派单尝试次数；每次准备发送时递增；允许为空，表示尚无该项数据',
  `phase` varchar(32) DEFAULT NULL COMMENT '证据产生的业务处理阶段；允许为空，表示尚无该项数据',
  `outcome` varchar(16) DEFAULT NULL COMMENT '业务执行结果编码；允许为空，表示尚无该项数据',
  `payload` json NOT NULL COMMENT '事件证据JSON，含对应业务状态或逐条结果快照',
  `created_at` datetime(3) NOT NULL COMMENT '记录创建时间',
  `delivery_status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT '投递状态：PENDING待补写、DELIVERED已补写',
  `delivery_attempts` int NOT NULL DEFAULT '0' COMMENT '证据展示表补写尝试次数',
  `next_attempt_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '下次允许补写的时间；失败后按退避策略调整',
  `delivered_at` datetime(3) DEFAULT NULL COMMENT '补写成功时间；未成功时为空',
  `last_error` varchar(1024) DEFAULT NULL COMMENT '最近补写失败的脱敏错误摘要；允许为空，表示尚无该项数据',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_trace_event` (`event_key`),
  KEY `idx_trace_delivery` (`delivery_status`,`next_attempt_at`,`id`),
  KEY `idx_trace_plan` (`tenant_id`,`plan_id`,`id`),
  KEY `idx_trace_item` (`plan_id`,`plan_item_id`,`attempt_count`,`outcome`),
  KEY `idx_trace_conversation` (`tenant_id`,`conversation_id`,`event_type`,`delivery_status`,`id`),
  KEY `idx_trace_preview` (`tenant_id`,`preview_id`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='不可变追溯事件及持久化补写队列；业务事务原子保存证据';

CREATE TABLE `agent_investigation_evidence` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '证据记录标识，JSON 使用字符串',
  `run_id` varchar(64) COLLATE utf8mb4_bin NOT NULL COMMENT '所属调查运行',
  `tenant_id` varchar(64) COLLATE utf8mb4_bin NOT NULL COMMENT '所属租户',
  `evidence_ref` varchar(32) COLLATE utf8mb4_bin NOT NULL COMMENT '运行内引用，如 E1；模型只使用该引用',
  `source_type` varchar(32) COLLATE utf8mb4_bin NOT NULL COMMENT '第 7 节证据来源枚举',
  `item_refs_json` json NOT NULL COMMENT '证据适用的条目引用数组；清单整体证据使用空数组',
  `source_ref_json` json NOT NULL COMMENT '服务端来源定位信息，含清单版本、条目/事件标识；敏感内部字段不默认下发',
  `content_json` json NOT NULL COMMENT '用于验证和展示的脱敏事实，结构随来源类型固定',
  `content_hash` char(64) COLLATE utf8mb4_bin NOT NULL COMMENT '规范化证据内容 SHA-256，用于一致性检查，不代替语义正确性',
  `observed_at` datetime(3) NOT NULL COMMENT '事实的观察时间，UTC；远端查询以实际返回时点记录',
  `truncated` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否仅覆盖部分来源内容；true 时不能宣称完整证据',
  `created_at` datetime(3) NOT NULL COMMENT '证据持久化 UTC 时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_evidence_ref` (`run_id`,`evidence_ref`),
  KEY `ix_evidence_tenant` (`tenant_id`,`run_id`),
  CONSTRAINT `fk_evidence_run` FOREIGN KEY (`tenant_id`, `run_id`) REFERENCES `agent_investigation_run` (`tenant_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='调查报告可引用的脱敏事实快照，绑定运行和条目，记录来源及时点；只供查询和报告校验，随所属运行清理。';

CREATE TABLE `agent_investigation_step` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '步骤记录标识，JSON 使用字符串',
  `run_id` varchar(64) COLLATE utf8mb4_bin NOT NULL COMMENT '关联调查运行',
  `tenant_id` varchar(64) COLLATE utf8mb4_bin NOT NULL COMMENT '冗余所属租户，查询仍强制限定',
  `seq` int NOT NULL COMMENT '运行内单调递增序号，用于页面增量读取',
  `kind` varchar(24) COLLATE utf8mb4_bin NOT NULL COMMENT 'MODEL、TOOL、VALIDATION、CONTROL',
  `status` varchar(24) COLLATE utf8mb4_bin NOT NULL COMMENT 'STARTED、SUCCEEDED、FAILED、SKIPPED',
  `tool_call_id` varchar(128) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '原模型工具调用标识，非工具步骤为空',
  `tool_name` varchar(80) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '白名单工具名，非工具步骤为空',
  `arguments_json` json DEFAULT NULL COMMENT '脱敏且有界的模型参数，无参数时为空',
  `result_json` json DEFAULT NULL COMMENT '有界结果概要、完整性和证据引用；不复制全部原始记录',
  `error_code` varchar(64) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '受控失败类别；成功时为空',
  `usage_json` json DEFAULT NULL COMMENT '本次模型用量及缺失标识，其他步骤为空',
  `duration_ms` bigint DEFAULT NULL COMMENT '单调时钟计算的耗时，单位毫秒；未结束为空',
  `started_at` datetime(3) NOT NULL COMMENT '步骤开始 UTC 时间',
  `finished_at` datetime(3) DEFAULT NULL COMMENT '步骤结束 UTC 时间；执行中为空',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_step_seq` (`run_id`,`seq`),
  KEY `ix_step_tenant` (`tenant_id`,`run_id`,`seq`),
  CONSTRAINT `fk_step_run` FOREIGN KEY (`tenant_id`, `run_id`) REFERENCES `agent_investigation_run` (`tenant_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='调查运行的可观察步骤，按所属运行继承租户和用户范围；保存模型调用、工具请求、预算及校验结果，不保存模型内部思考，随运行清理。';


-- 当前专用Demo初始数据；仅随空库V1初始化写入，不复位运行中的业务状态。

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

INSERT INTO report_receivable (tenant_id, company_code, invoice_no, customer_id, customer_name, customer_aliases, amount, due_date)
VALUES ('T001', 'A', 'INV-2026-0001', 'CUST-001', '北京某某科技有限公司', '["北京某某科技"]', 128000.00, '2026-03-15'),
       ('T001', 'A', 'INV-2026-0002', 'CUST-002', '上海某某信息有限公司', '["上海某某信息"]', 32000.00, '2026-04-08'),
       ('T001', 'A', 'INV-2026-0007', 'CUST-003', '天津某某贸易有限公司', '["天津某某贸易"]', 12.50, '2026-05-10'),
       ('T001', 'A', 'INV-2026-0008', 'CUST-004', '北京某某咨询有限公司', '["北京某某咨询"]', 8.00, '2026-06-01'),
       ('T001', 'B', 'INV-2026-0003', 'CUST-005', '广州某某电子有限公司', '["广州某某电子"]', 86000.00, '2026-03-22'),
       ('T001', 'B', 'INV-2026-0004', 'CUST-006', '深圳某某网络有限公司', '["深圳某某网络"]', 15400.00, '2026-04-11'),
       ('T001', 'B', 'INV-2026-0009', 'CUST-007', '东莞某某制造有限公司', '["东莞某某制造"]', 19.90, '2026-05-18'),
       ('T001', 'C', 'INV-2026-0005', 'CUST-008', '杭州某某数据有限公司', '["杭州某某数据"]', 256000.00, '2026-04-19'),
       ('T001', 'C', 'INV-2026-0006', 'CUST-009', '成都某某软件有限公司', '["成都某某软件"]', 47800.00, '2026-05-27'),
       ('T001', 'C', 'INV-2026-0010', 'CUST-010', '武汉某某物流有限公司', '["武汉某某物流"]', 6.60, '2026-06-15');

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

INSERT INTO report_purchase (tenant_id, company_code, po_no, supplier_name, amount, order_date)
VALUES ('T001', 'A', 'PO-2026-0001', '苏州某某电子有限公司', 56000.00, '2026-02-03'),
       ('T001', 'A', 'PO-2026-0002', '北京某某办公用品有限公司', 800.00, '2026-03-12'),
       ('T001', 'B', 'PO-2026-0003', '深圳某某精密有限公司', 23000.00, '2026-02-21'),
       ('T001', 'C', 'PO-2026-0004', '杭州某某材料有限公司', 9100.00, '2026-04-08');

INSERT INTO report_definition (tenant_id, report_id, report_code, report_name, domain_code, description, query_mode, query_config,
                               dispatch_enabled, status, schema_version, catalog_version, permission_code, sort_order,
                               created_by, created_at, updated_by, updated_at)
VALUES ('T001', 'rpt-sales-order', 'sales', '销售报表', 'sales', '销售订单台账：按订单统计金额与派单状态', 'STANDARD',
        '{"table":"report_sales","idColumn":"id","companyColumn":"company_code","tenantColumn":"tenant_id","docNoColumn":"order_no","docNoLabel":"订单号","labelColumn":"product_name","amountColumn":"amount","dateColumn":"sale_date","statusColumn":"dispatch_status","pendingValue":0,"dispatchedValue":1,"dispatchedAtColumn":"dispatched_at","fields":[{"name":"companyCode","column":"company_code","type":"string","description":"公司代码"},{"name":"orderNo","column":"order_no","type":"string","description":"订单号"},{"name":"productName","column":"product_name","type":"string","description":"产品名称"},{"name":"amount","column":"amount","type":"decimal","description":"订单金额（元）"},{"name":"saleDate","column":"sale_date","type":"date","description":"销售日期，字符串 yyyy-MM-dd"}],"derived":[{"name":"daysSinceSale","kind":"DAYS_SINCE","column":"sale_date","description":"距销售日期的天数（派生）"},{"name":"dispatched","kind":"DISPATCHED","description":"是否已派单（派生，粗筛后恒为 false）"}]}',
        1, 'PUBLISHED', 1, 1, 'report:sales', 10, 'demo', NOW(3), 'demo', NOW(3)),
       ('T001', 'rpt-ar-invoice', 'receivable', '应收报表', 'receivable', '应收发票台账：按发票统计应收金额与到期日', 'STANDARD',
        '{"table":"report_receivable","idColumn":"id","companyColumn":"company_code","tenantColumn":"tenant_id","docNoColumn":"invoice_no","docNoLabel":"发票号","labelColumn":"customer_name","amountColumn":"amount","dateColumn":"due_date","statusColumn":"dispatch_status","pendingValue":0,"dispatchedValue":1,"dispatchedAtColumn":"dispatched_at","fields":[{"name":"companyCode","column":"company_code","type":"string","description":"公司代码"},{"name":"invoiceNo","column":"invoice_no","type":"string","description":"发票号"},{"name":"customerName","column":"customer_name","type":"string","description":"客户名称"},{"name":"counterpartyId","column":"customer_id","type":"string","description":"交易对方稳定标识"},{"name":"counterpartyName","column":"customer_name","type":"string","description":"交易对方完整名称"},{"name":"counterpartyAliases","column":"customer_aliases","type":"string","description":"交易对方别名JSON数组"},{"name":"amount","column":"amount","type":"decimal","description":"应收金额（元）"},{"name":"dueDate","column":"due_date","type":"date","description":"到期日，字符串 yyyy-MM-dd"}],"derived":[{"name":"daysUntilDue","kind":"DAYS_UNTIL","column":"due_date","description":"距到期日的天数，已过期为负数（派生）"},{"name":"overdue","kind":"IS_PAST","column":"due_date","description":"是否已逾期（派生）"},{"name":"dispatched","kind":"DISPATCHED","description":"是否已派单（派生，粗筛后恒为 false）"}]}',
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

INSERT INTO dispatch_rule (tenant_id, report_id, company_code, name, description, expression, version, status, created_at, updated_by, updated_at)
VALUES ('T001', 'rpt-sales-order', '*', '销售报表默认规则', '销售订单金额大于 20 元需要派单', 'amount > 20', 1, 'published', NOW(), 'system', NOW()),
       ('T001', 'rpt-sales-order', 'C', 'C 公司销售规则', 'C 公司销售订单金额大于 50000 元才需要派单', 'amount > 50000', 1, 'published', NOW(), 'system', NOW()),
       ('T001', 'rpt-ar-invoice', '*', '应收报表默认规则', '应收发票金额小于 20 元需要派单（小额应收集中催收）', 'amount < 20', 1, 'published', NOW(), 'system', NOW()),
       ('T001', 'rpt-expense-claim', '*', '费用报表默认规则', '报销单金额大于 1000 元需要派单', 'amount > 1000', 1, 'published', NOW(), 'system', NOW());

INSERT INTO dispatch_rule_history (tenant_id, rule_id, report_id, company_code, version, name, expression, description, action, operated_by, operated_at)
SELECT tenant_id, id, report_id, company_code, version, name, expression, description, 'publish', 'system', NOW()
FROM dispatch_rule;
