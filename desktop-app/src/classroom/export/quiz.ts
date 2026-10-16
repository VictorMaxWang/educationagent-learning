/** 题库选项 value 才是答案键（如 A），label 是展示正文，不能用正文参与判分。 */
import type { QuestionType } from '../types';
export const QUESTION_TYPE_LABELS: Record<QuestionType, string> = { single: '单选', multiple: '多选', judge: '判断', fill: '填空', short_answer: '简答' };

export function answerText(value: string | number | boolean | string[] | undefined): string {
  return value === undefined ? '' : Array.isArray(value) ? value.join('、') : typeof value === 'boolean' ? value ? '对' : '错' : String(value);
}
export function canonicalAnswer(value: string | number | boolean | string[] | undefined, type: QuestionType = 'multiple'): string {
  if (type === 'fill' || type === 'short_answer') return answerText(value).normalize('NFKC').replace(/[\s\p{Z}]+/gu, '').toLocaleLowerCase();
  if (type === 'judge') {
    const raw = answerText(value).trim();
    if (/^(对|正确|√|T|true|是)$/i.test(raw)) return '对';
    if (/^(错|错误|×|F|false|否)$/i.test(raw)) return '错';
    return raw;
  }
  const values = Array.isArray(value) ? value : String(value ?? '').split(/[\s,，;；|]+/);
  return [...new Set(values.flatMap(item => /^[A-Za-z]+$/.test(item) ? item.toUpperCase().split('') : [item.trim()]).filter(Boolean))].sort().join('|');
}
