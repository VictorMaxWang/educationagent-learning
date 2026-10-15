/** 题库选项 value 才是答案键（如 A），label 是展示正文，不能用正文参与判分。 */
export function canonicalAnswer(value: string | string[] | undefined): string {
  const values = Array.isArray(value) ? value : String(value || '').split(/[\s,，;；|]+/);
  return [...new Set(values.flatMap(item => /^[A-Za-z]+$/.test(item) ? item.toUpperCase().split('') : [item.trim()]).filter(Boolean))].sort().join('|');
}
