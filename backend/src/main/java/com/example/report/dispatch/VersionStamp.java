package com.example.report.dispatch;

import java.util.Map;

/**
 * 预览时刻的版本戳：报表目录、生效规则、用户权限。生成清单、确认执行时重新计算比对，任何一项变化都不能再执行。
 *
 * @param catalogVersions 范围内每张报表的目录版本（明细，写进 query_json 便于审计）
 */
public record VersionStamp(String catalogVersion, Map<String, Long> catalogVersions, String ruleVersion,
                           String permissionVersion) {
}
