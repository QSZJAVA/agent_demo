/** 按需注册组件的集成边界：模板使用的现有 Element UI 控件必须实际注册，避免生产构建静默输出空标签。 */
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path')
const root = path.join(__dirname, '../src')
function vueFiles(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap(entry => {
    const file = path.join(dir, entry.name)
    return entry.isDirectory() ? vueFiles(file) : (entry.name.endsWith('.vue') ? [file] : [])
  })
}
test('every Element UI template component is imported and registered by the application', () => {
  const main = fs.readFileSync(path.join(root, 'main.js'), 'utf8')
  const registrations = new Set(main.match(/const components = \[([\s\S]*?)\]/)[1].split(',').map(value => value.trim()))
  const imports = new Map([...main.matchAll(/import (\w+) from 'element-ui\/lib\/([\w-]+)'/g)].map(match => [`el-${match[2]}`, match[1]]))
  for (const file of vueFiles(root)) {
    const source = fs.readFileSync(file, 'utf8')
    for (const [, tag] of source.matchAll(/<(el-[a-z-]+)(?=[\s/>])/g)) {
      assert.ok(imports.has(tag) && registrations.has(imports.get(tag)), `${path.relative(root, file)} requires registered ${tag}`)
    }
  }
})
