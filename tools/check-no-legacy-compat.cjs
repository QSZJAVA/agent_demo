/** 当前运行目录的兼容逻辑门禁；历史评审和源码归档不参与运行检查。 */
const fs = require('node:fs'), path = require('node:path');
const root = path.resolve(__dirname, '..');
const walk = dir => fs.readdirSync(dir, {withFileTypes: true}).flatMap(e => e.isDirectory() ? walk(path.join(dir, e.name)) : [path.join(dir, e.name)]);
const errors = [];
const forbidden = /report_code_mapping|legacy_report_type|evidence_version|excludeDocNos|requireVisibleBy(?:IdOr)?LegacyCode|reportIdForLegacyCode|RedisChatMemoryRepository|agent:memory:|require-confirm|demo\.reset-on-startup|DEMO_RESET|baseline-on-migrate:\s*true|\bLEGACY\b|markDispatched\(/;
for (const dir of ['backend/src/main', 'business-service/src/main', 'frontend/src']) {
  for (const file of walk(path.join(root, dir)).filter(p => /\.(java|json|sql|ya?ml|js|vue)$/.test(p))) {
    const match = fs.readFileSync(file, 'utf8').match(forbidden);
    if (match) errors.push(`${path.relative(root, file)}: retired compatibility token ${match[0]}`);
  }
}
const migrations = fs.readdirSync(path.join(root, 'backend/src/main/resources/db/migration'));
if (migrations.length !== 1 || migrations[0] !== 'V1__baseline.sql') errors.push('Only the current V1 empty-database baseline is allowed');
for (const file of ['backend/src/main/java/db/migration', 'backend/src/main/java/com/example/report/config/DemoDataResetCallback.java']) {
  if (fs.existsSync(path.join(root, file)) && (!fs.statSync(path.join(root, file)).isDirectory() || fs.readdirSync(path.join(root, file)).length)) errors.push(`Retired migration/reset source: ${file}`);
}
if (errors.length) { console.error(errors.join('\n')); process.exitCode = 1; }
else console.log('Current source compatibility check passed; one V1 baseline, no retired protocols or database adapters.');
