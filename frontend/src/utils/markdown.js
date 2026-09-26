/**
 * 极简 Markdown 渲染：只覆盖对话里实际会出现的语法
 * （标题 / 列表 / 引用 / 代码块 / 表格 / 加粗 / 斜体 / 行内代码 / 链接）。
 * 按原始文本切块，行内先转义再拼标签，模型输出里的原始 HTML 只会按文本显示，不会被执行。
 */

const ESCAPES = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }

export function escapeHtml(text) {
  return String(text == null ? '' : text).replace(/[&<>"']/g, (ch) => ESCAPES[ch])
}

/** 行内语法：先转义，再替换成标签 */
function inline(raw) {
  return escapeHtml(raw)
    .replace(/`([^`\n]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>')
    .replace(/(^|[^*\w])\*([^*\n]+)\*(?!\*)/g, '$1<em>$2</em>')
    .replace(/\[([^\]\n]+)\]\((https?:\/\/[^)\s]+)\)/g, '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>')
}

/** 这些行会被块级规则接管，段落遇到它们要断开 */
const BLOCK_START = /^(?:```|#{1,6}\s|>|\s*[-*+]\s|\s*\d+[.)]\s|\s*(?:-{3,}|\*{3,}|_{3,})\s*$)/

/** 表格分隔行：只由 | - : 和空格组成且至少有一个 - */
const isTableDelimiter = (line) => /^[\s|:-]+$/.test(line) && line.includes('-')

function splitRow(line) {
  let text = line.trim()
  if (text.startsWith('|')) text = text.slice(1)
  if (text.endsWith('|')) text = text.slice(0, -1)
  return text.split('|').map((cell) => cell.trim())
}

export function renderMarkdown(source) {
  const lines = String(source == null ? '' : source).replace(/\r\n?/g, '\n').split('\n')
  const html = []
  let listTag = null
  let i = 0

  const closeList = () => {
    if (listTag) {
      html.push(`</${listTag}>`)
      listTag = null
    }
  }
  const openList = (tag) => {
    if (listTag !== tag) {
      closeList()
      html.push(`<${tag}>`)
      listTag = tag
    }
  }

  while (i < lines.length) {
    const line = lines[i]

    // 代码块
    if (/^\s*```/.test(line)) {
      closeList()
      const body = []
      i += 1
      while (i < lines.length && !/^\s*```/.test(lines[i])) body.push(lines[i++])
      i += 1
      html.push(`<pre><code>${escapeHtml(body.join('\n'))}</code></pre>`)
      continue
    }

    // 表格：当前行有 | 且下一行是分隔行
    if (line.includes('|') && i + 1 < lines.length && isTableDelimiter(lines[i + 1])) {
      closeList()
      const head = splitRow(line).map((cell) => `<th>${inline(cell)}</th>`).join('')
      i += 2
      const rows = []
      while (i < lines.length && lines[i].trim() && lines[i].includes('|')) {
        rows.push(splitRow(lines[i++]))
      }
      const body = rows
        .map((cells) => `<tr>${cells.map((cell) => `<td>${inline(cell)}</td>`).join('')}</tr>`)
        .join('')
      html.push(`<table><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table>`)
      continue
    }

    const heading = /^(#{1,6})\s+(.*)$/.exec(line)
    if (heading) {
      closeList()
      // 对话气泡里不需要 h1 那么大，整体降三级
      const level = Math.min(heading[1].length + 2, 6)
      html.push(`<h${level}>${inline(heading[2].trim())}</h${level}>`)
      i += 1
      continue
    }

    if (/^\s*(?:-{3,}|\*{3,}|_{3,})\s*$/.test(line)) {
      closeList()
      html.push('<hr>')
      i += 1
      continue
    }

    if (/^\s*>/.test(line)) {
      closeList()
      const body = []
      while (i < lines.length && /^\s*>/.test(lines[i])) {
        body.push(lines[i++].replace(/^\s*>\s?/, ''))
      }
      html.push(`<blockquote>${body.map(inline).join('<br>')}</blockquote>`)
      continue
    }

    const bullet = /^\s*[-*+]\s+(.*)$/.exec(line)
    if (bullet) {
      openList('ul')
      html.push(`<li>${inline(bullet[1])}</li>`)
      i += 1
      continue
    }

    const ordered = /^\s*\d+[.)]\s+(.*)$/.exec(line)
    if (ordered) {
      openList('ol')
      html.push(`<li>${inline(ordered[1])}</li>`)
      i += 1
      continue
    }

    if (!line.trim()) {
      closeList()
      i += 1
      continue
    }

    // 段落：连续的非块级行合并，单个换行按 <br> 处理。
    // 当前行无条件吃掉：它可能带着块级前缀却没被上面的规则接住（例如行内含 U+2028），不吃掉 i 就不前进，会死循环
    const parts = [lines[i++]]
    while (i < lines.length && lines[i].trim() && !BLOCK_START.test(lines[i])) {
      parts.push(lines[i++])
    }
    closeList()
    html.push(`<p>${parts.map(inline).join('<br>')}</p>`)
  }

  closeList()
  return html.join('')
}
