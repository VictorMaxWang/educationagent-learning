/**
 * 历史对话侧边栏 — 后端 REST API 封装
 * 对接后端 com.demo.session.controller.ChatSessionController（/chat/**）
 */
import { fetchRequest, fetchStreamChat } from '../api/request';
import type { ChatSession, MessagePage, StreamEvent } from './chat';

/** 获取用户全部会话列表（按 updateTime 倒序） */
export function listSessions(userId: string): Promise<ChatSession[]> {
  return fetchRequest(`/chat/sessions?userId=${encodeURIComponent(userId)}`) as Promise<ChatSession[]>;
}

/** 创建/确保会话存在（幂等），前端"新建对话"生成 UUID 后调用 */
export function createSession(userId: string, sessionId: string, title?: string): Promise<ChatSession> {
  return fetchRequest(`/chat/session?userId=${encodeURIComponent(userId)}`, {
    method: 'POST',
    body: JSON.stringify({ sessionId, title }),
  }) as Promise<ChatSession>;
}

/** 获取会话历史消息（分页，page=1 为最近一页，list 升序） */
export function getMessages(sessionId: string, page = 1, size = 50): Promise<MessagePage> {
  return fetchRequest(
    `/chat/session/${encodeURIComponent(sessionId)}/messages?page=${page}&size=${size}`,
  ) as Promise<MessagePage>;
}

/** 删除会话（级联删消息 + 清理后端缓存） */
export function deleteSession(sessionId: string): Promise<{ success: boolean }> {
  return fetchRequest(`/chat/session/${encodeURIComponent(sessionId)}`, {
    method: 'DELETE',
  }) as Promise<{ success: boolean }>;
}

/** 会话重命名 */
export function renameSession(sessionId: string, title: string): Promise<{ success: boolean }> {
  return fetchRequest(`/chat/session/${encodeURIComponent(sessionId)}/rename`, {
    method: 'POST',
    body: JSON.stringify({ title }),
  }) as Promise<{ success: boolean }>;
}

/** 读取语音播报开关状态（桌面端听筒按钮） */
export function getVoiceMode(userId: string): Promise<{ enabled: boolean }> {
  return fetchRequest(`/chat/voice-mode?userId=${encodeURIComponent(userId)}`) as Promise<{ enabled: boolean }>;
}

/** 设置语音播报开关（on → 全局语音模式，off → 关闭） */
export function setVoiceMode(
  userId: string,
  enabled: boolean,
): Promise<{ success: boolean; enabled: boolean }> {
  return fetchRequest('/chat/voice-mode', {
    method: 'POST',
    body: JSON.stringify({ userId, enabled }),
  }) as Promise<{ success: boolean; enabled: boolean }>;
}

/**
 * NDJSON 流式对话（会话模式）。
 * @param signal 取消信号——切换会话/删除会话时中断进行中的流式请求
 */
export function streamChat(
  payload: { sessionId: string; userId: string; content: string; fileId?: string | null },
  onMessage: (evt: StreamEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  return fetchStreamChat('/chat/stream', payload, onMessage, signal);
}
