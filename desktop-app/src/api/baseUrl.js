/** 手机不能把 localhost 当作电脑；网页默认走同源代理，Tauri 仍直连本机后端。 */
export function selectApiBaseUrl(configured = '', location, isTauri = false) {
  const value = configured.trim().replace(/\/$/, '');
  if (isTauri) return value || 'http://localhost:8888';
  if (!value) return '';
  try {
    const target = new URL(value);
    const loopback = ['localhost', '127.0.0.1', '[::1]'].includes(target.hostname);
    if (loopback && location) return '';
    if (location?.protocol === 'https:' && target.protocol === 'http:') return '';
  } catch {
    // 保留显式配置的相对前缀，例如 /backend。
  }
  return value;
}

export const API_BASE_URL = selectApiBaseUrl(
  import.meta.env?.VITE_API_BASE_URL || '',
  typeof window === 'undefined' ? undefined : window.location,
  typeof window !== 'undefined' && Boolean(window.__TAURI__ || window.__TAURI_INTERNALS__),
);
