// src/api/request.js
import { API_BASE_URL as BASE_URL } from './baseUrl';

/**
 * 普通一次性接口（会话列表、文件上传等）
 * @param {string} url
 * @param {RequestInit} options
 * @returns {Promise<any>}
 */
export async function fetchRequest(url, options = {}) {
  const resp = await fetch(`${BASE_URL}${url}`, {
    headers: {
      "Content-Type": "application/json",
      ...options.headers,
    },
    ...options,
  });
  if (!resp.ok) {
    // 后端在 4xx/5xx 时返回 {"error": "..."} 或 {"message": "..."}，
    // 透传真实原因（如"文件不存在或已过期，请重新上传"），避免只显示 HTTP 状态码
    let msg = `HTTP ${resp.status}`;
    try {
      const body = await resp.json();
      if (body?.error) msg = body.error;
      else if (body?.message) msg = body.message;
    } catch {
      // 响应体非 JSON，保留状态码提示
    }
    throw new Error(msg);
  }
  return resp.json();
}

/**
 * 把后端返回的相对路径（如 /chat/images/xxx.png）拼上 API 基址，
 * data:/http(s) 绝对地址原样返回（流式实时下发的 data URL、用户本地预览等）。
 */
export function resolveApiUrl(path) {
  if (!path) return path;
  if (path.startsWith("data:") || path.startsWith("http://") || path.startsWith("https://")) return path;
  if (path.startsWith("/")) return `${BASE_URL}${path}`;
  return path;
}

/**
 * NDJSON流式聊天专用接口 /api/desktop/chat/stream 或 /chat/stream
 * @param {string} url
 * @param {object} payload
 * @param {(data: object)=>void} onMessage 每行消息回调
 * @param {AbortSignal} [signal] 取消信号（切换会话时中断进行中的流式请求）
 */
export async function fetchStreamChat(url, payload, onMessage, signal) {
  const resp = await fetch(`${BASE_URL}${url}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify(payload),
    signal,
  });

  if (!resp.ok) throw new Error("流式接口请求失败");
  const reader = resp.body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split("\n");
    buffer = lines.pop();
    for (const line of lines) {
      const trimLine = line.trim();
      if (!trimLine) continue;
      const json = JSON.parse(trimLine);
      onMessage(json);
    }
  }
}
