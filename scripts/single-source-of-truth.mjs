#!/usr/bin/env node
/**
 * single-source-of-truth.mjs —— 质量与规模清单的**单一事实源**（REQ-MCP-12 / AC-MCP-12）。
 *
 * 要解决的问题（实测事实）：同一批数字在三处文档里写着 176/9、137/68、13，而本地报告是 186/74/16。
 * 数字不一致不是笔误，是**没有真源**。这个脚本就是真源：所有数字都从真实产物里数出来 ——
 *
 *   测试项数  ← surefire / failsafe 的 TEST-*.xml 报告（不是文档、不是"我记得"）
 *   评测条数  ← scripts/ai-golden-questions.mjs 的 QUESTIONS 与 docs/TEST-助手黄金问题集.md 的表格
 *   工具数    ← Java 侧 @Tool 注解、AiToolRegistry 的 read/write 清单、业务 MCP 的只读白名单
 *   指标清单  ← 第五阶段需求文档 §5.3.1 的 ai.* 指标表
 *
 * 用法（零依赖，Node 20+）：
 *   node scripts/single-source-of-truth.mjs                    # Markdown → stdout
 *   node scripts/single-source-of-truth.mjs --format=json      # JSON → stdout
 *   node scripts/single-source-of-truth.mjs --out=reports/quality-inventory.md
 *   node scripts/single-source-of-truth.mjs --json-out=reports/quality-inventory.json
 *   node scripts/single-source-of-truth.mjs --check            # 只做一致性校验；不一致退出码 1
 *
 * 退出码：0 正常（--check 下表示一致）；1 参数错误或 --check 发现不一致。
 *
 * ⚠️ 报告"是否早于源码"会一并给出：`mvn verify` 之后报告才对应 HEAD。
 *    若 stale=true，引用数字前必须先重跑，否则引用的就是旧代码的成绩。
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..');

const GOLDEN_SCRIPT = path.join(ROOT, 'scripts', 'ai-golden-questions.mjs');
const GOLDEN_DOC = path.join(ROOT, 'docs', 'TEST-助手黄金问题集.md');
const TOOL_DIR = path.join(ROOT, 'guarantee-ai', 'src', 'main', 'java', 'com', 'guarantee', 'ai', 'tool');
const REGISTRY = path.join(TOOL_DIR, 'AiToolRegistry.java');
const MCP_CATALOG = path.join(ROOT, 'tools', 'business-mcp', 'src', 'catalog.ts');
const PHASE5_REQ = path.join(ROOT, 'docs', 'REQ-第五阶段-MCP评测与可观测.md');

// ---------------------------------------------------------------------------
// 文件系统小工具
// ---------------------------------------------------------------------------

const SKIP_DIRS = new Set(['node_modules', '.git', 'dist', 'build', '.vite', '.idea', 'target']);
// 找报告目录时必须能进 target/，因此单独一套跳过规则
const REPORT_SCAN_SKIP = new Set(['node_modules', '.git', 'dist', '.vite', '.idea']);

function walkDirs(dir, predicate, out = []) {
  let entries;
  try {
    entries = fs.readdirSync(dir, { withFileTypes: true });
  } catch {
    return out;
  }
  for (const entry of entries) {
    if (!entry.isDirectory() || REPORT_SCAN_SKIP.has(entry.name)) continue;
    const full = path.join(dir, entry.name);
    if (predicate(entry.name)) out.push(full);
    walkDirs(full, predicate, out);
  }
  return out;
}

function walkFiles(dir, filter, out = []) {
  let entries;
  try {
    entries = fs.readdirSync(dir, { withFileTypes: true });
  } catch {
    return out;
  }
  for (const entry of entries) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      // 构建产物与依赖目录一律不进源码统计（报告扫描走 walkDirs）
      if (SKIP_DIRS.has(entry.name)) continue;
      walkFiles(full, filter, out);
    } else if (filter(full, entry.name)) {
      out.push(full);
    }
  }
  return out;
}

function readText(file) {
  try {
    return fs.readFileSync(file, 'utf8');
  } catch {
    return null;
  }
}

function rel(file) {
  return path.relative(ROOT, file).split(path.sep).join('/');
}

function uniqueSorted(values) {
  return [...new Set(values)].sort();
}

// ---------------------------------------------------------------------------
// 1. 测试项数（surefire / failsafe 报告）
// ---------------------------------------------------------------------------

function parseTestSuiteXml(file) {
  const text = readText(file);
  if (!text) return null;
  const tag = /<testsuite\b[^>]*>/.exec(text);
  if (!tag) return null;
  const attr = (name) => {
    const match = new RegExp(`\\b${name}="([^"]*)"`).exec(tag[0]);
    return match ? Number(match[1]) : 0;
  };
  const nameMatch = /\bname="([^"]*)"/.exec(tag[0]);
  return {
    suite: nameMatch ? nameMatch[1] : path.basename(file, '.xml'),
    tests: attr('tests'),
    failures: attr('failures'),
    errors: attr('errors'),
    skipped: attr('skipped'),
    timeSeconds: attr('time'),
    file: rel(file),
  };
}

function collectReport(kind) {
  const dirs = walkDirs(ROOT, (name) => name === `${kind}-reports`);
  const suites = [];
  let newest = 0;
  for (const dir of dirs) {
    let entries;
    try {
      entries = fs.readdirSync(dir);
    } catch {
      continue;
    }
    for (const entry of entries) {
      if (!/^TEST-.*\.xml$/i.test(entry)) continue;
      const file = path.join(dir, entry);
      const parsed = parseTestSuiteXml(file);
      if (!parsed) continue;
      suites.push(parsed);
      const mtime = fs.statSync(file).mtimeMs;
      if (mtime > newest) newest = mtime;
    }
  }
  const totals = suites.reduce(
    (acc, s) => ({
      tests: acc.tests + s.tests,
      failures: acc.failures + s.failures,
      errors: acc.errors + s.errors,
      skipped: acc.skipped + s.skipped,
      timeSeconds: acc.timeSeconds + s.timeSeconds,
    }),
    { tests: 0, failures: 0, errors: 0, skipped: 0, timeSeconds: 0 },
  );
  return {
    kind,
    reportDirs: dirs.map(rel),
    suites: suites.sort((a, b) => a.suite.localeCompare(b.suite)),
    totals: { ...totals, classes: suites.length, newnessMs: newest },
  };
}

/** 源码最新改动时间：用于判断报告是否对应 HEAD。 */
function newestSourceMtime() {
  const files = walkFiles(
    ROOT,
    (full, name) =>
      name === 'pom.xml' ||
      (/\.(java|kt)$/i.test(name) && /[\\/]src[\\/](main|test)[\\/]/.test(full)),
  );
  let newest = 0;
  for (const file of files) {
    try {
      const mtime = fs.statSync(file).mtimeMs;
      if (mtime > newest) newest = mtime;
    } catch {
      /* ignore */
    }
  }
  return newest;
}

function collectTests() {
  const unit = collectReport('surefire');
  const integration = collectReport('failsafe');
  const newestReport = Math.max(unit.totals.newnessMs, integration.totals.newnessMs);
  const newestSource = newestSourceMtime();
  const hasReports = unit.totals.classes + integration.totals.classes > 0;
  return {
    unit,
    integration,
    total: {
      tests: unit.totals.tests + integration.totals.tests,
      failures: unit.totals.failures + integration.totals.failures,
      errors: unit.totals.errors + integration.totals.errors,
      skipped: unit.totals.skipped + integration.totals.skipped,
      classes: unit.totals.classes + integration.totals.classes,
    },
    newestReportAt: newestReport > 0 ? new Date(newestReport).toISOString() : null,
    newestSourceAt: newestSource > 0 ? new Date(newestSource).toISOString() : null,
    stale: hasReports && newestReport > 0 && newestSource > newestReport,
  };
}

// ---------------------------------------------------------------------------
// 2. 评测条数
// ---------------------------------------------------------------------------

function collectEvaluation() {
  const scriptText = readText(GOLDEN_SCRIPT) ?? '';
  const docText = readText(GOLDEN_DOC) ?? '';

  const scriptIds = uniqueSorted([...scriptText.matchAll(/\bid:\s*['"](GQ-\d+)['"]/g)].map((m) => m[1]));
  const docIds = uniqueSorted([...docText.matchAll(/^\|\s*(GQ-\d+)\s*\|/gm)].map((m) => m[1]));

  const sameSet = (a, b) => a.length === b.length && a.every((value, index) => value === b[index]);

  return {
    script: { file: rel(GOLDEN_SCRIPT), count: scriptIds.length, ids: scriptIds },
    doc: { file: rel(GOLDEN_DOC), count: docIds.length, ids: docIds },
    consistent: sameSet(scriptIds, docIds),
    onlyInScript: scriptIds.filter((id) => !docIds.includes(id)),
    onlyInDoc: docIds.filter((id) => !scriptIds.includes(id)),
  };
}

// ---------------------------------------------------------------------------
// 3. 工具清单
// ---------------------------------------------------------------------------

function collectTools() {
  const toolFiles = walkFiles(TOOL_DIR, (_full, name) => /\.java$/.test(name));
  const javaToolNames = [];
  for (const file of toolFiles) {
    const text = readText(file) ?? '';
    for (const match of text.matchAll(/@Tool\(\s*name\s*=\s*"([A-Za-z0-9_]+)"/g)) {
      javaToolNames.push(match[1]);
    }
  }
  const javaNames = uniqueSorted(javaToolNames);

  const registryText = readText(REGISTRY) ?? '';
  const readClasses = [...registryText.matchAll(/\breadTools\.add\(/g)].length;
  const writeClasses = [...registryText.matchAll(/\bwriteTools\.add\(/g)].length;

  const catalogText = readText(MCP_CATALOG) ?? '';
  const mcpNames = uniqueSorted(
    [...catalogText.matchAll(/backendName:\s*'([A-Za-z0-9_]+)'/g)].map((m) => m[1]),
  );

  const missingInJava = mcpNames.filter((name) => !javaNames.includes(name));
  const writeLikeInMcp = mcpNames.filter((name) => /propose/i.test(name));

  return {
    java: { dir: rel(TOOL_DIR), count: javaNames.length, names: javaNames },
    registry: { file: rel(REGISTRY), readClasses, writeClasses },
    mcp: {
      file: rel(MCP_CATALOG),
      count: mcpNames.length,
      names: mcpNames,
      isSubsetOfJavaTool: missingInJava.length === 0,
      missingInJava,
      writeToolsExposed: writeLikeInMcp,
    },
    // 只读方法数：全部 @Tool 方法减去写包里的 @Tool 方法（OrderSummaryTool 一个类提供 2 个只读方法）
    readOnlyMethods: javaNames.length - countWriteMethods(),
  };
}

/** 写工具的 @Tool 方法数（从 write 包统计；MCP 永不暴露）。 */
function countWriteMethods() {
  const writeDir = path.join(TOOL_DIR, 'write');
  const files = walkFiles(writeDir, (_full, name) => /\.java$/.test(name));
  const names = [];
  for (const file of files) {
    const text = readText(file) ?? '';
    for (const match of text.matchAll(/@Tool\(\s*name\s*=\s*"([A-Za-z0-9_]+)"/g)) names.push(match[1]);
  }
  return uniqueSorted(names).length;
}

// ---------------------------------------------------------------------------
// 4. 指标清单（需求 §5.3.1）
// ---------------------------------------------------------------------------

function collectMetrics() {
  const text = readText(PHASE5_REQ) ?? '';
  const names = uniqueSorted([...text.matchAll(/^\|\s*`(ai\.[a-z0-9.]+)`\s*\|/gm)].map((m) => m[1]));
  return { file: rel(PHASE5_REQ), count: names.length, names };
}

// ---------------------------------------------------------------------------
// 输出
// ---------------------------------------------------------------------------

function collectAll() {
  return {
    generatedAt: new Date().toISOString(),
    generator: 'scripts/single-source-of-truth.mjs',
    requirement: 'REQ-MCP-12 / AC-MCP-12',
    tests: collectTests(),
    evaluation: collectEvaluation(),
    tools: collectTools(),
    metrics: collectMetrics(),
  };
}

function fmtMs(iso) {
  if (!iso) return '（未知）';
  return new Date(iso).toISOString().replace('T', ' ').slice(0, 19) + ' UTC';
}

function renderMarkdown(data) {
  const l = [];
  l.push('# 质量与规模清单（单一事实源，自动生成）');
  l.push('');
  l.push('> 生成脚本：`node scripts/single-source-of-truth.mjs`');
  l.push(`> 生成时间：${data.generatedAt}`);
  l.push('> 需求：REQ-MCP-12 / AC-MCP-12。**本文件里的数字禁止手写**，文档只引用脚本输出。');
  l.push('');
  l.push('## 1. 测试项数（来自真实报告）');
  l.push('');
  l.push('| 类别 | 用例数 | 失败 | 错误 | 跳过 | 测试类 | 报告目录 |');
  l.push('|---|---|---|---|---|---|---|');
  const row = (label, r) =>
    `| ${label} | ${r.totals.tests} | ${r.totals.failures} | ${r.totals.errors} | ${r.totals.skipped} | ${r.totals.classes} | ${r.reportDirs.join('、') || '（未找到）'} |`;
  l.push(row('单测（surefire）', data.tests.unit));
  l.push(row('集成（failsafe）', data.tests.integration));
  l.push(
    `| **合计** | **${data.tests.total.tests}** | **${data.tests.total.failures}** | **${data.tests.total.errors}** | **${data.tests.total.skipped}** | **${data.tests.total.classes}** | — |`,
  );
  l.push('');
  l.push(`- 报告最新时间：${fmtMs(data.tests.newestReportAt)}`);
  l.push(`- 源码最新改动：${fmtMs(data.tests.newestSourceAt)}`);
  l.push(
    `- 是否早于源码（stale）：**${data.tests.stale ? '是 —— 引用前必须先跑 mvn verify' : '否'}**`,
  );
  l.push('');
  l.push('## 2. 评测集条数');
  l.push('');
  l.push('| 来源 | 条数 | id 范围 |');
  l.push('|---|---|---|');
  const range = (ids) => (ids.length === 0 ? '（空）' : `${ids[0]} … ${ids[ids.length - 1]}`);
  l.push(`| 脚本 \`${data.evaluation.script.file}\` | ${data.evaluation.script.count} | ${range(data.evaluation.script.ids)} |`);
  l.push(`| 文档 \`${data.evaluation.doc.file}\` | ${data.evaluation.doc.count} | ${range(data.evaluation.doc.ids)} |`);
  l.push('');
  l.push(`- 两边一致：**${data.evaluation.consistent ? '是' : '否'}**`);
  if (!data.evaluation.consistent) {
    if (data.evaluation.onlyInScript.length) l.push(`  - 仅脚本有：${data.evaluation.onlyInScript.join(', ')}`);
    if (data.evaluation.onlyInDoc.length) l.push(`  - 仅文档有：${data.evaluation.onlyInDoc.join(', ')}`);
  }
  l.push('');
  l.push('## 3. 工具清单');
  l.push('');
  l.push('| 来源 | 数量 | 文件 |');
  l.push('|---|---|---|');
  l.push(`| Java \`@Tool\` 方法（含写工具） | ${data.tools.java.count} | \`${data.tools.java.dir}\` |`);
  l.push(`| Java 只读 \`@Tool\` 方法 | ${data.tools.readOnlyMethods} | \`${data.tools.java.dir}\` |`);
  l.push(`| \`AiToolRegistry\` READ 类 | ${data.tools.registry.readClasses} | \`${data.tools.registry.file}\` |`);
  l.push(`| \`AiToolRegistry\` WRITE 类 | ${data.tools.registry.writeClasses} | \`${data.tools.registry.file}\` |`);
  l.push(`| 业务 MCP 暴露（只读白名单） | ${data.tools.mcp.count} | \`${data.tools.mcp.file}\` |`);
  l.push('');
  l.push(`- MCP 白名单 ⊆ Java \`@Tool\`：**${data.tools.mcp.isSubsetOfJavaTool ? '是' : '否'}**`);
  if (!data.tools.mcp.isSubsetOfJavaTool) {
    l.push(`  - Java 侧缺失：${data.tools.mcp.missingInJava.join(', ')}`);
  }
  l.push(
    `- MCP 是否暴露写工具：**${data.tools.mcp.writeToolsExposed.length === 0 ? '否（符合 REQ-MCP-04）' : `是（违规）：${data.tools.mcp.writeToolsExposed.join(', ')}`}**`,
  );
  l.push('');
  l.push(`### READ 工具名（${data.tools.mcp.count} 个暴露给外部 Agent）`);
  l.push('');
  l.push(data.tools.mcp.names.map((name) => `\`${name}\``).join('、'));
  l.push('');
  l.push('### Java \`@Tool\` 全量');
  l.push('');
  l.push(data.tools.java.names.map((name) => `\`${name}\``).join('、'));
  l.push('');
  l.push('## 4. 指标清单（需求 §5.3.1）');
  l.push('');
  l.push(`来源：\`${data.metrics.file}\`，共 **${data.metrics.count}** 项（代码落地前以需求为准）：`);
  l.push('');
  l.push(data.metrics.names.map((name) => `- \`${name}\``).join('\n'));
  l.push('');
  return l.join('\n');
}

// ---------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------

function parseArgs(argv) {
  const options = { format: 'markdown', out: null, jsonOut: null, check: false };
  for (const arg of argv) {
    if (arg === '--check') options.check = true;
    else if (arg === '--help' || arg === '-h') options.help = true;
    else if (arg.startsWith('--format=')) options.format = arg.slice('--format='.length);
    else if (arg.startsWith('--out=')) options.out = arg.slice('--out='.length);
    else if (arg.startsWith('--json-out=')) options.jsonOut = arg.slice('--json-out='.length);
    else {
      options.error = `未知参数：${arg}`;
    }
  }
  if (options.format !== 'markdown' && options.format !== 'json') {
    options.error = `--format 只支持 markdown | json，当前：${options.format}`;
  }
  return options;
}

function writeFile(relPath, content) {
  const target = path.isAbsolute(relPath) ? relPath : path.join(ROOT, relPath);
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.writeFileSync(target, content, 'utf8');
  return target;
}

function consistencyProblems(data) {
  const problems = [];
  if (!data.evaluation.consistent) {
    problems.push(
      `评测条数不一致：脚本 ${data.evaluation.script.count} 条 / 文档 ${data.evaluation.doc.count} 条`,
    );
  }
  if (!data.tools.mcp.isSubsetOfJavaTool) {
    problems.push(`MCP 白名单含 Java 侧不存在的工具：${data.tools.mcp.missingInJava.join(', ')}`);
  }
  if (data.tools.mcp.count !== data.tools.readOnlyMethods) {
    problems.push(
      `MCP 白名单数量（${data.tools.mcp.count}）与 Java 只读 @Tool 方法数（${data.tools.readOnlyMethods}）不一致`,
    );
  }
  if (data.tools.mcp.writeToolsExposed.length > 0) {
    problems.push(`MCP 暴露了写工具：${data.tools.mcp.writeToolsExposed.join(', ')}`);
  }
  if (data.tests.stale) {
    problems.push('测试报告早于源码最新改动：数字对应旧代码，先跑 mvn verify');
  }
  return problems;
}

function main() {
  const options = parseArgs(process.argv.slice(2));
  if (options.error) {
    process.stderr.write(`${options.error}\n用法：node scripts/single-source-of-truth.mjs [--format=markdown|json] [--out=<file>] [--json-out=<file>] [--check]\n`);
    process.exit(1);
  }
  if (options.help) {
    process.stdout.write(
      '用法：node scripts/single-source-of-truth.mjs [--format=markdown|json] [--out=<file>] [--json-out=<file>] [--check]\n',
    );
    process.exit(0);
  }

  const data = collectAll();
  const problems = consistencyProblems(data);

  if (options.check) {
    if (problems.length === 0) {
      process.stdout.write('✅ 单一事实源校验通过：评测条数、MCP 白名单、测试报告新鲜度均一致\n');
      process.exit(0);
    }
    process.stderr.write(`❌ 单一事实源校验失败（${problems.length} 项）：\n`);
    for (const problem of problems) process.stderr.write(`   - ${problem}\n`);
    process.exit(1);
  }

  const markdown = renderMarkdown(data);
  const json = JSON.stringify(data, null, 2);

  if (options.format === 'json') process.stdout.write(json + '\n');
  else process.stdout.write(markdown + '\n');

  if (options.out) {
    const written = writeFile(options.out, markdown);
    process.stderr.write(`已写入 ${rel(written)}\n`);
  }
  if (options.jsonOut) {
    const written = writeFile(options.jsonOut, json + '\n');
    process.stderr.write(`已写入 ${rel(written)}\n`);
  }

  if (options.out || options.jsonOut) {
    // 写文件时顺带把一致性结论报给调用者（但不改变退出码，--check 才是门禁）
    if (problems.length > 0) {
      process.stderr.write(`⚠️ 一致性提示（${problems.length} 项）：\n`);
      for (const problem of problems) process.stderr.write(`   - ${problem}\n`);
    }
  }
}

main();
