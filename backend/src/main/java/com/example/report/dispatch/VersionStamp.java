package com.example.report.dispatch;

import java.util.Map;

/**
 * 预览时刻的版本戳：报表目录、生效规则、用户权限。生成清单、确认执行时重新计算比对，任何一项变化都不能再执行。
 *
 * @param catalogVersions 范围内每张报表的目录版本（明细，写进 query_json 便于审计）
 * @param catalogVersion 目录版本或聚合指纹，用于发现预览后定义变更
 * @param ruleVersion 预览范围内生效规则的聚合版本指纹
 * @param permissionVersion 当前用户业务授权版本指纹
 */
public record VersionStamp(String catalogVersion, Map<String, Long> catalogVersions, String ruleVersion,
                           String permissionVersion) {
}
