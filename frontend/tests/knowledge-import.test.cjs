/** 本地资料导入的真实 UTF-8 解码、文件与正文预算回归；不使用浏览器上传或服务器替身冒充业务验收。 */
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
const context = { TextDecoder }
vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../src/utils/knowledgeImport.js'), 'utf8').replace('export async function', 'async function'), context)
const { readKnowledgeFile } = context
const file = (name, content) => {
  const bytes = Buffer.isBuffer(content) ? content : Buffer.from(content, 'utf8')
  return { name, size: bytes.length, arrayBuffer: async () => bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) }
}

test('UTF-8 BOM、Markdown 和 HTML 字符保持为完整文本，不推断来源或执行标记', async () => {
  const result = await readKnowledgeFile(file('业务说明.MD', '\uFEFF# 销售口径\n<script>danger()</script>\n金额不等于已收款。'))
  assert.equal(result.name, '业务说明.MD')
  assert.equal(result.text, '# 销售口径\n<script>danger()</script>\n金额不等于已收款。')
  assert.deepEqual(Object.keys(result).sort(), ['name', 'text'])
})

test('正文 12,000 字符边界保持完整，超长不截断或返回部分正文', async () => {
  assert.equal((await readKnowledgeFile(file('boundary.txt', '文'.repeat(12000)))).text.length, 12000)
  await assert.rejects(readKnowledgeFile(file('oversize.txt', '文'.repeat(12001))), /12,000/)
  assert.equal((await readKnowledgeFile(file('emoji.md', '😀'.repeat(6000)))).text.length, 12000)
  await assert.rejects(readKnowledgeFile(file('emoji.md', '😀'.repeat(6001))), /12,000/)
})

test('超过 64 KiB 的文件在读取前拒绝', async () => {
  let reads = 0
  await assert.rejects(readKnowledgeFile({ name: 'huge.txt', size: 65537, arrayBuffer: async () => { reads++; return new ArrayBuffer(65537) } }), /64 KiB/)
  assert.equal(reads, 0)
})

test('文件扩展名、空内容和非法 UTF-8 均明确拒绝', async () => {
  await assert.rejects(readKnowledgeFile(file('document.html', '<p>正文</p>')), /\.txt 或 \.md/)
  await assert.rejects(readKnowledgeFile(file('empty.txt', '')), /不能为空/)
  await assert.rejects(readKnowledgeFile(file('blank.md', '\uFEFF \t\r\n')), /正文不能为空/)
  await assert.rejects(readKnowledgeFile(file('invalid.txt', Buffer.from([0xc3, 0x28]))), /UTF-8/)
})

test('UTF-8 NUL 和无 BOM UTF-16 中的 NUL 均在回填前拒绝', async () => {
  for (const content of ['订单\u0000金额', Buffer.from('amount is order value', 'utf16le'), Buffer.from([0, 65, 0, 66])]) {
    await assert.rejects(readKnowledgeFile(file('invalid-text.txt', content)), /NUL.*UTF-8/)
  }
})

test('实际读取大小变化和读取失败不能作为成功导入', async () => {
  await assert.rejects(readKnowledgeFile({ name: 'changed.txt', size: 1, arrayBuffer: async () => new ArrayBuffer(65537) }), /大小不一致/)
  await assert.rejects(readKnowledgeFile({ name: 'failed.txt', size: 1, arrayBuffer: async () => { throw new Error('文件已不可读') } }), /不可读/)
})
