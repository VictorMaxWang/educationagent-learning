/** 非安全用途的稳定内容标识：普通运行状态改变不应清空学生对同一页内容的自述。 */
export function sceneContentRevision(content: unknown): number {
  const canonical = (value: unknown): string => {
    if (Array.isArray(value)) return '[' + value.map(canonical).join(',') + ']';
    if (value && typeof value === 'object') return '{' + Object.keys(value).sort().map(key => JSON.stringify(key) + ':' + canonical((value as Record<string, unknown>)[key])).join(',') + '}';
    return JSON.stringify(value) ?? 'null';
  };
  const text = canonical(content);
  let a = 2166136261, b = 3339675911;
  for (let i = 0; i < text.length; i++) { const ch = text.charCodeAt(i); a = Math.imul(a ^ ch, 16777619); b = Math.imul(b ^ ch, 2246822519); }
  // 两路散列合成 JavaScript 可精确表达的正整数；不用于身份认证或密码校验。
  return (a >>> 0) * 2097152 + ((b >>> 0) & 2097151);
}
