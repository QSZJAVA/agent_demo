const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const extensions = new Set(['.md', '.mmd', '.svg', '.drawio', '.html']);
function walk(directory) {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const file = path.join(directory, entry.name);
    return entry.isDirectory() ? walk(file) : [file];
  });
}
// AGENTS.md defines prohibited terms; test sources and raw historical evidence are not runtime documentation.
const files = [path.join(root, 'README.md'), ...walk(path.join(root, 'docs'))
  .filter(file => extensions.has(path.extname(file))
    || (file.includes(path.join('docs', 'diagrams')) && ['.json', '.cjs'].includes(path.extname(file))))];
const prohibited = /\bmock\b|MockDispatchGateway|mock-intents|start-mock|agent\.llm\.mock|模拟模型|模拟语义|固定语义样本模式/gi;
let violations = [];
for (const file of files) {
  const content = fs.readFileSync(file, 'utf8');
  for (const match of content.matchAll(prohibited)) {
    const line = content.slice(0, match.index).split('\n').length;
    violations.push(`${path.relative(root, file)}:${line}: prohibited documentation term '${match[0]}'`);
  }
}
if (violations.length) {
  process.stderr.write(violations.join('\n') + '\n');
  process.exitCode = 1;
} else {
  console.log(`Real-model documentation check passed (${files.length} files).`);
}
