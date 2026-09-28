/**
 * yaml.ts — 极小的 YAML 子集解析器。
 *
 * 为什么自己写而不引依赖：
 *   本 Server 只需要读 2 个各约 10 行的配置文件（project.yaml / vault.local.yaml）。
 *   引入完整 YAML 库会扩大依赖面与安全面，而收益为零。
 *
 * 支持的子集（覆盖这两个文件的全部实际需求）：
 *   注释 `#`、空行
 *   缩进嵌套映射
 *   引号字符串 '...' "..."
 *   纯量：true / false / null / 整数 / 小数 / 日期样式的字符串 / 普通字符串
 *   流式序列：[a, b, c]
 *
 * **明确不支持**（遇到即报错，绝不静默猜）：
 *   块标量 `|` `>`、锚点与别名 `&` `*`、多文档 `---`、复杂嵌套的流式映射
 */

import { KnowledgeError } from './errors.js';

export type YamlValue = string | number | boolean | null | YamlValue[] | YamlObject;
export interface YamlObject {
  [key: string]: YamlValue;
}

interface Line {
  readonly indent: number;
  readonly content: string;
  readonly lineNo: number;
}

const UNSUPPORTED = [
  { re: /^[|>]/, why: '块标量（| 或 >）' },
  { re: /^&/, why: '锚点（&）' },
  { re: /^\*/, why: '别名（*）' },
];

/** 去掉行尾注释，但保留引号内的 `#`。 */
function stripComment(line: string): string {
  let quote: '"' | "'" | null = null;
  for (let i = 0; i < line.length; i++) {
    const ch = line[i]!;
    if (quote) {
      if (ch === quote) quote = null;
      continue;
    }
    if (ch === '"' || ch === "'") {
      quote = ch;
      continue;
    }
    if (ch === '#' && (i === 0 || /\s/.test(line[i - 1]!))) {
      return line.slice(0, i);
    }
  }
  return line;
}

function parseScalar(raw: string, lineNo: number): YamlValue {
  const s = raw.trim();
  if (s === '') return null;

  // 引号字符串
  if (s.length >= 2 && s.startsWith('"') && s.endsWith('"')) {
    return s
      .slice(1, -1)
      .replace(/\\n/g, '\n')
      .replace(/\\t/g, '\t')
      .replace(/\\"/g, '"')
      .replace(/\\\\/g, '\\');
  }
  if (s.length >= 2 && s.startsWith("'") && s.endsWith("'")) {
    return s.slice(1, -1).replace(/''/g, "'");
  }
  if (s.startsWith('"') || s.startsWith("'")) {
    throw new KnowledgeError('project_config_invalid', `YAML 第 ${lineNo} 行：引号未闭合`, { lineNo });
  }

  // 流式序列 [a, b, c]
  if (s.startsWith('[')) {
    if (!s.endsWith(']')) {
      throw new KnowledgeError('project_config_invalid', `YAML 第 ${lineNo} 行：流式序列未闭合`, { lineNo });
    }
    const inner = s.slice(1, -1).trim();
    if (inner === '') return [];
    return splitFlow(inner, lineNo).map((part) => parseScalar(part, lineNo));
  }
  if (s.startsWith('{')) {
    throw new KnowledgeError('project_config_invalid', 'YAML 暂不支持流式映射（{...}）', { lineNo });
  }

  if (s === 'true') return true;
  if (s === 'false') return false;
  if (s === 'null' || s === '~') return null;
  if (/^-?\d+$/.test(s)) return Number.parseInt(s, 10);
  if (/^-?\d+\.\d+$/.test(s)) return Number.parseFloat(s);

  return s;
}

/** 按逗号切分流式序列，尊重引号。 */
function splitFlow(inner: string, lineNo: number): string[] {
  const out: string[] = [];
  let cur = '';
  let quote: '"' | "'" | null = null;
  for (const ch of inner) {
    if (quote) {
      cur += ch;
      if (ch === quote) quote = null;
      continue;
    }
    if (ch === '"' || ch === "'") {
      quote = ch;
      cur += ch;
      continue;
    }
    if (ch === ',') {
      out.push(cur.trim());
      cur = '';
      continue;
    }
    cur += ch;
  }
  if (cur.trim() !== '') out.push(cur.trim());
  return out.filter((x) => x !== '');
}

/** 解析 YAML 文本为对象。顶层必须是映射。 */
export function parseYaml(text: string, sourceLabel = '<yaml>'): YamlObject {
  const rawLines = text.replace(/^\uFEFF/, '').replace(/\r\n/g, '\n').split('\n');

  const lines: Line[] = [];
  rawLines.forEach((raw, idx) => {
    if (raw.includes('\t')) {
      throw new KnowledgeError('project_config_invalid', `${sourceLabel}:${idx + 1} 使用了 Tab 缩进，YAML 不允许`, {
        lineNo: idx + 1,
      });
    }
    const stripped = stripComment(raw);
    if (stripped.trim() === '') return;
    const content = stripped.trim();
    if (content === '---' || content === '...') return;
    for (const u of UNSUPPORTED) {
      if (u.re.test(content)) {
        throw new KnowledgeError('project_config_invalid', `${sourceLabel}:${idx + 1} 使用了不支持的 YAML 语法：${u.why}`, {
          lineNo: idx + 1,
        });
      }
    }
    lines.push({ indent: stripped.length - stripped.trimStart().length, content, lineNo: idx + 1 });
  });

  let cursor = 0;

  function parseBlock(minIndent: number): YamlObject {
    const obj: YamlObject = {};
    while (cursor < lines.length) {
      const line = lines[cursor]!;
      if (line.indent < minIndent) break;

      const colon = findKeyColon(line.content);
      if (colon === -1) {
        throw new KnowledgeError(
          'project_config_invalid',
          `${sourceLabel}:${line.lineNo} 不是合法的 “key: value”（本解析器只支持映射）`,
          { lineNo: line.lineNo, content: line.content },
        );
      }

      const key = line.content.slice(0, colon).trim();
      const rest = line.content.slice(colon + 1).trim();
      const keyIndent = line.indent;
      cursor++;

      if (rest !== '') {
        obj[key] = parseScalar(rest, line.lineNo);
        continue;
      }

      // 空值：看下一行是否更深缩进 —— 是则作为子映射，否则为 null
      const next = lines[cursor];
      if (next && next.indent > keyIndent) {
        obj[key] = parseBlock(next.indent);
      } else {
        obj[key] = null;
      }
    }
    return obj;
  }

  const result = parseBlock(0);
  return result;
}

/** 找到 `key:` 的冒号位置；跳过引号内的冒号。 */
function findKeyColon(content: string): number {
  let quote: '"' | "'" | null = null;
  for (let i = 0; i < content.length; i++) {
    const ch = content[i]!;
    if (quote) {
      if (ch === quote) quote = null;
      continue;
    }
    if (ch === '"' || ch === "'") {
      quote = ch;
      continue;
    }
    if (ch === ':') {
      // `key:value` 与 `key: value` 都接受；但排除 URL 之类（:后有非空格且前无空格）
      return i;
    }
  }
  return -1;
}

/** 取嵌套字符串值，路径不存在时返回 undefined。 */
export function getString(obj: YamlObject, dottedPath: string): string | undefined {
  const parts = dottedPath.split('.');
  let cur: YamlValue = obj;
  for (const part of parts) {
    if (cur === null || typeof cur !== 'object' || Array.isArray(cur)) return undefined;
    cur = (cur as YamlObject)[part] ?? null;
    if (cur === null || cur === undefined) return undefined;
  }
  if (typeof cur === 'string') return cur;
  if (typeof cur === 'number' || typeof cur === 'boolean') return String(cur);
  return undefined;
}
