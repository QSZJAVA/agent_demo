/** 本地知识文本导入边界；仅解码管理员选中的 UTF-8 文件并返回正文，不上传、保存、发布或推断资料来源。 */
const MAX_FILE_BYTES = 64 * 1024
const MAX_BODY_CHARACTERS = 12000

/**
 * 读取 .txt/.md 文件并按后端正文契约核对预算；失败时不返回部分正文，调用方负责丢弃过期读取结果。
 * @param {File} file 用户显式选择的本地文件，最多 64 KiB；必须可严格解码为 UTF-8。
 * @returns {Promise<{name: string, text: string}>} 原文件名与完整正文；正文字符数按 JavaScript/Java 的 UTF-16 长度计算。
 */
export async function readKnowledgeFile(file) {
  if (!file || typeof file.name !== 'string' || !/\.(txt|md)$/i.test(file.name)) throw new Error('请选择 .txt 或 .md 文本文件。')
  if (!Number.isSafeInteger(file.size) || file.size < 1 || file.size > MAX_FILE_BYTES) throw new Error('文件不能为空，且不能超过 64 KiB。')
  const buffer = await file.arrayBuffer()
  // File 在读取期间不可变；同时核对实际字节数，避免异常传输对象绕过读取前的大小上限。
  if (buffer.byteLength !== file.size || buffer.byteLength > MAX_FILE_BYTES) throw new Error('读取到的文件大小不一致，请重新选择文件。')
  let text
  try { text = new TextDecoder('utf-8', { fatal: true }).decode(buffer) }
  catch (_) { throw new Error('文件不是有效的 UTF-8 文本，请转换编码后重试。') }
  // NUL 即使能按 UTF-8 解码也不符合正文契约；无 BOM 的部分 UTF-16 文件也会在此被拒绝，不能破坏原有草稿。
  if (text.includes('\u0000')) throw new Error('正文包含 NUL（空字符），请确认文件使用 UTF-8 编码并移除无效字符后重试。')
  if (!text.trim()) throw new Error('正文不能为空或只有空白。')
  if (text.length > MAX_BODY_CHARACTERS) throw new Error('正文超过 12,000 个字符，请整理后重新导入。')
  return { name: file.name, text }
}
