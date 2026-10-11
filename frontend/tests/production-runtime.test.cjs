/** 使用真实 Vite 生产配置构建应用与探针，验证运行时唯一性及表格同类 store 的跨模块响应性。 */
const { test, before, after } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs/promises'), path = require('node:path'), os = require('node:os')
const { pathToFileURL } = require('node:url')

const frontendRoot = path.resolve(__dirname, '..')
const temporaryRoot = path.resolve(os.tmpdir())
let outputDirectory, productionModules, probe

before(async () => {
  const { build } = await import('vite')
  const entry = path.join(__dirname, 'fixtures/production-store-entry.js')
  const bundle = await build({
    root: frontendRoot,
    configFile: path.join(frontendRoot, 'vite.config.mjs'),
    logLevel: 'silent',
    // 探针通过导出函数观测产物，保留入口导出；应用仍使用同一生产解析、插件和分块配置。
    build: { write: false, rollupOptions: { preserveEntrySignatures: 'strict', input: { app: path.join(frontendRoot, 'index.html'), probe: entry } } }
  })
  const chunks = (Array.isArray(bundle) ? bundle.flatMap(result => result.output) : bundle.output)
    .filter(output => output.type === 'chunk')
  productionModules = [...new Set(chunks.flatMap(chunk => Object.keys(chunk.modules)))]
  outputDirectory = await fs.mkdtemp(path.join(temporaryRoot, 'report-demo-production-runtime-'))
  await fs.writeFile(path.join(outputDirectory, 'package.json'), '{"type":"module"}')
  for (const chunk of chunks) {
    const target = path.resolve(outputDirectory, chunk.fileName)
    assert.ok(target.startsWith(outputDirectory + path.sep), '构建产物必须位于当前临时目录')
    await fs.mkdir(path.dirname(target), { recursive: true })
    await fs.writeFile(target, chunk.code)
  }
  const probeChunk = chunks.find(chunk => chunk.isEntry && path.resolve(chunk.facadeModuleId) === entry)
  assert.ok(probeChunk, '真实生产构建必须生成响应性探针入口')
  probe = await import(pathToFileURL(path.join(outputDirectory, probeChunk.fileName)).href)
})

after(async () => {
  if (!outputDirectory) return
  // 只清理本测试创建的直接临时子目录，不能把递归删除扩展到项目或其他临时数据。
  const resolved = path.resolve(outputDirectory)
  assert.equal(path.dirname(resolved), temporaryRoot)
  assert.ok(path.basename(resolved).startsWith('report-demo-production-runtime-'))
  await fs.rm(resolved, { recursive: true, force: true })
})

test('真实生产应用图中只存在一份 Vue 运行时', () => {
  const runtimes = productionModules.filter(id => !id.startsWith('\0') && /[/\\]vue[/\\]dist[/\\]vue\.runtime\.(?:esm|common\.(?:prod|dev))\.js$/.test(id))
  assert.equal(runtimes.length, 1, `发现多个 Vue 运行时：${runtimes.join(', ')}`)
})

test('生产 ESM 页面能观察 CommonJS store 新增的表头和数据', async () => {
  assert.deepEqual(await probe.observeStoreChanges(), {
    before: { columns: 0, rows: 0 },
    after: { columns: 1, rows: 1 }
  })
  assert.equal(probe.sameRuntime, true)
})
