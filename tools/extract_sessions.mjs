import fs from 'node:fs';
import path from 'node:path';

const CLAUDE_ROOT = 'C:/Users/jx/.claude/projects/C--Users-jx';
const OUT_DIR = 'C:/Users/jx/WorkBuddy/2026-11-11-11-26-30/icu-v4-github-export/iteration/session-extracts';
const START_DATE = '2026-11-04';
const END_DATE = '2026-11-25';
const V4_RE = /icu\.apk\.1 v4|独立.{0,3}v4|icu-v4|icu\.apk\.1\.1 v4/i;

function redact(text) {
  return String(text)
    .replace(/ANTHROPIC_AUTH_TOKEN\s*[:=]\s*\S+/g, 'ANTHROPIC_AUTH_TOKEN=***REDACTED***')
    .replace(/\btp-[A-Za-z0-9._-]+/g, '***REDACTED***')
    .replace(/\bttp-[A-Za-z0-9._-]+/g, '***REDACTED***')
    .replace(/(keyPassword|storePassword)\s*=\s*[^,\s]+/gi, '$1=***REDACTED***')
    .replace(/(keystore\s*(?:password|口令)\s*[:=]\s*)\S+/gi, '$1***REDACTED***');
}

function extractText(content) {
  if (typeof content === 'string') {
    return content.trim();
  }
  if (Array.isArray(content)) {
    return content
      .filter((block) => block && block.type === 'text' && typeof block.text === 'string')
      .map((block) => block.text.trim())
      .filter(Boolean)
      .join('\n\n');
  }
  return '';
}

function inRange(timestamp) {
  if (!timestamp || typeof timestamp !== 'string') return false;
  const date = timestamp.slice(0, 10);
  return date >= START_DATE && date <= END_DATE;
}

fs.mkdirSync(OUT_DIR, { recursive: true });

const files = fs
  .readdirSync(CLAUDE_ROOT)
  .filter((name) => name.endsWith('.jsonl'))
  .sort();

const index = [];
for (const name of files) {
  const fullPath = path.join(CLAUDE_ROOT, name);
  const lines = fs.readFileSync(fullPath, 'utf8').split(/\r?\n/).filter(Boolean);
  if (!lines.some((line) => V4_RE.test(line))) continue;

  const entries = [];
  for (const line of lines) {
    let obj;
    try {
      obj = JSON.parse(line);
    } catch {
      continue;
    }
    if (!inRange(obj.timestamp)) continue;

    let role = null;
    let text = '';
    if (obj.type === 'user' && typeof obj.message?.content === 'string') {
      role = '用户';
      text = extractText(obj.message.content);
    } else if (obj.type === 'assistant') {
      text = extractText(obj.message?.content);
      if (text) role = '助手';
    }
    if (!role || !text) continue;
    if (text.startsWith('<system-reminder')) continue;
    entries.push({ timestamp: obj.timestamp, role, text: redact(text) });
  }
  if (!entries.length) continue;

  const date = entries[0].timestamp.slice(0, 10);
  const sessionId = name.replace(/\.jsonl$/, '');
  const outFile = `${date}_${sessionId}.md`;
  const body = [
    `# Claude Code 会话摘录：${date}`,
    '',
    `- 会话 ID：\`${sessionId}\``,
    `- 原始记录：\`C:/Users/jx/.claude/projects/C--Users-jx/${name}\``,
    `- 摘录范围：${START_DATE} ~ ${END_DATE}`,
    '- 内容范围：仅用户文本消息与助手文本回复；不含工具调用、工具结果和思考块。',
    '',
    ...entries.flatMap((e) => [
      `## ${e.timestamp} · ${e.role}`,
      '',
      e.text,
      '',
    ]),
  ].join('\n');
  fs.writeFileSync(path.join(OUT_DIR, outFile), body, 'utf8');
  index.push({ file: outFile, entries: entries.length, sessionId });
}

const indexBody = [
  '# Claude Code 会话摘录索引',
  '',
  `统计范围：${START_DATE} ~ ${END_DATE}`,
  '',
  '本目录只收录与独立 v4 项目相关的 Claude Code 会话文本摘录。原始会话中的工具调用、工具结果、思考块已排除；疑似凭据字段已脱敏。',
  '',
  '| 文件 | 条数 | 会话 ID |',
  '|---|---:|---|',
  ...index.map((i) => `| ${i.file} | ${i.entries} | ${i.sessionId} |`),
  '',
].join('\n');
fs.writeFileSync(path.join(OUT_DIR, 'INDEX.md'), indexBody, 'utf8');
console.log(`SESSIONS=${index.length}`);
console.log(`ENTRIES=${index.reduce((sum, i) => sum + i.entries, 0)}`);
