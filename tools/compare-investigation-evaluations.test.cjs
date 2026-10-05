/** 基线比较的离线反例；在临时目录生成最小归档，通过实际CLI检查路径、时区、缺失配置及题次配对。 */
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs'), os = require('node:os'), path = require('node:path')
const { execFileSync } = require('node:child_process')
const contract = require('../backend/src/test/resources/investigation/evaluation-controls.json')
const fixture = () => ({
  manifest: { ...Object.fromEntries(contract.manifestControls.map(key => [key, 'same'])), configuration: Object.fromEntries([...contract.configurationControls, ...contract.comparisonVariables].map(key => [key, 'same'])) },
  summary: { status: 'COMPLETED', completionRate: 1 },
  runs: [{ caseId: 'one', repeat: 1, factsHash: 'same', passed: true, durationMs: 10 }]
})
const compare = (before, after) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'investigation-compare-'))
  try {
    for (const [name, data] of [['before', before], ['after', after]]) {
      fs.mkdirSync(path.join(root, name))
      for (const key of ['manifest', 'summary']) fs.writeFileSync(path.join(root, name, `${key}.json`), JSON.stringify(data[key]))
      fs.writeFileSync(path.join(root, name, 'runs.jsonl'), data.runs.map(run => JSON.stringify(run)).join('\n'))
    }
    const output = path.join(root, 'comparison.json')
    execFileSync(process.execPath, [path.join(__dirname, 'compare-investigation-evaluations.cjs'), path.join(root, 'before'), path.join(root, 'after'), output])
    return JSON.parse(fs.readFileSync(output, 'utf8'))
  } finally { fs.rmSync(root, { recursive: true, force: true }) }
}
test('相同配置与事实可比，代码及提示变更明确列为实验变量', () => {
  const after = fixture()
  for (const field of contract.comparisonVariables) after.manifest.configuration[field] = 'changed'
  assert.equal(compare(fixture(), after).comparable, true)
})
test('不同请求路径和业务时区禁止直接比较', () => {
  for (const field of ['completionsPath', 'businessTimezone']) {
    const after = fixture(); after.manifest.configuration[field] = 'different'
    const result = compare(fixture(), after)
    assert.equal(result.comparable, false); assert.deepEqual(result.changedControls.map(c => c.field), [field])
  }
})
test('双方缺失或空控制变量仍然不可比', () => {
  for (const field of contract.configurationControls) for (const missing of [undefined, null]) {
    const before = fixture(), after = fixture()
    before.manifest.configuration[field] = missing; after.manifest.configuration[field] = missing
    assert.equal(compare(before, after).comparable, false, field)
  }
})
test('空批次、重复题次、不同事实和未完成批次都不可比', () => {
  for (const change of [data => { data.runs = [] }, data => { data.runs.push({ ...data.runs[0] }) }, data => { data.runs[0].factsHash = 'changed' }, data => { data.summary.status = 'BLOCKED' }]) {
    const after = fixture(); change(after); assert.equal(compare(fixture(), after).comparable, false)
  }
  const empty = fixture(); empty.runs = []; assert.equal(compare(empty, empty).comparable, false)
  const duplicate = fixture(); duplicate.runs.push({ ...duplicate.runs[0] }); assert.equal(compare(duplicate, duplicate).comparable, false)
})
