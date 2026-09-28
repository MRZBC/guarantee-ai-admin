/**
 * fields.ts — 「语义字段 → Markdown 小节」的共享类型。
 *
 * 单独成文件是为了避免 decisions.ts / wiki.ts 与 markdown.ts 之间产生循环依赖。
 */

import type { SectionSpec } from './markdown.js';

export interface FieldSpec extends Omit<SectionSpec, 'before'> {
  /** 是否为必填小节（新建页面时至少要写这些） */
  readonly required?: boolean;
  /** 新建小节时插在哪个 field 之前 */
  readonly before?: string | null;
}
