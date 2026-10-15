/**
 * 历史对话侧边栏 — 全局状态仓库（Zustand）
 * 状态结构：
 *   sessionList: 会话列表
 *   activeSessionId: 当前激活会话
 *   messages: 以 sessionId 分组的消息缓存
 *   messageTotal / loadedPage: 分页加载进度
 * 硬性规则：
 *   1. 切换会话必须先中断当前流式请求（防止多条流并发渲染错乱）
 *   2. 删除会话后若删的是激活会话，自动选中列表第一个
 *   3. 流式结束后以 done 包完整回复覆盖（消除增量偏差）
 */
import { create } from 'zustand';
import * as api from './chatSessionApi';
import { downloadBase64File } from '../utils/file';
import type { ChatMessage, ChatSession, StreamEvent } from './chat';

/** 分页单页大小 */
const PAGE_SIZE = 50;

/** 模块级 AbortController：中断进行中的流式请求（不进 React 状态，避免重渲染） */
let abortController: AbortController | null = null;

interface ChatState {
  /** 会话列表（侧边栏渲染） */
  sessionList: ChatSession[];
  /** 当前激活会话 ID */
  activeSessionId: string | null;
  /** 以 sessionId 分组的消息缓存 */
  messages: Record<string, ChatMessage[]>;
  /** 各会话消息总数（分页用） */
  messageTotal: Record<string, number>;
  /** 各会话已加载页数 */
  loadedPage: Record<string, number>;
  /** 是否正在流式回复 */
  streaming: boolean;
  /** 会话列表加载中 */
  loadingSessions: boolean;
  /** 会话消息加载中 */
  loadingMessages: boolean;

  /** 启动初始化：拉取会话列表并自动选中第一个 */
  init(userId: string): Promise<void>;
  /** 刷新会话列表（流式结束/重命名后调用） */
  refreshSessions(userId: string): Promise<void>;
  /** 新建对话：生成 UUID → 建空会话 → 置为激活 */
  createSession(userId: string): Promise<void>;
  /** 切换会话：先中断流式 → 拉取消息渲染 */
  selectSession(userId: string, sessionId: string): Promise<void>;
  /** 会话重命名 */
  renameSession(userId: string, sessionId: string, title: string): Promise<void>;
  /** 删除会话：本地移除 + 若删的是激活会话自动选中第一个 */
  removeSession(userId: string, sessionId: string): Promise<void>;
  /** 发送消息：追加用户消息 → 流式接收增量 → done 覆盖 → 刷新会话列表 */
  sendMessage(
    userId: string,
    content: string,
    fileId?: string | null,
    display?: { image?: string; fileName?: string },
  ): Promise<void>;
  /** 分页加载更早消息（prepend 到消息列表） */
  loadOlderMessages(sessionId: string): Promise<void>;
  /** 中断当前流式请求 */
  abortStreaming(): void;
}

export const useChatStore = create<ChatState>((set, get) => ({
  sessionList: [],
  activeSessionId: null,
  messages: {},
  messageTotal: {},
  loadedPage: {},
  streaming: false,
  loadingSessions: false,
  loadingMessages: false,

  init: async (userId) => {
    set({ loadingSessions: true });
    try {
      const list = await api.listSessions(userId);
      set({ sessionList: list, loadingSessions: false });
      // 启动自动选中第一个会话（若有）
      if (list.length > 0 && !get().activeSessionId) {
        await get().selectSession(userId, list[0].sessionId);
      }
    } catch (err) {
      console.error('会话列表加载失败', err);
      set({ loadingSessions: false });
    }
  },

  refreshSessions: async (userId) => {
    try {
      const list = await api.listSessions(userId);
      set({ sessionList: list });
    } catch (err) {
      console.error('会话列表刷新失败', err);
    }
  },

  createSession: async (userId) => {
    get().abortStreaming(); // 新建对话同样中断进行中的流
    const sessionId = crypto.randomUUID();
    try {
      const s = await api.createSession(userId, sessionId);
      set((st) => ({
        sessionList: [s, ...st.sessionList],
        activeSessionId: sessionId,
        messages: { ...st.messages, [sessionId]: [] },
        messageTotal: { ...st.messageTotal, [sessionId]: 0 },
      }));
    } catch (err) {
      console.error('新建会话失败', err);
    }
  },

  selectSession: async (_userId, sessionId) => {
    get().abortStreaming(); // 硬性规范：切换会话必须清空当前流式请求
    set({ activeSessionId: sessionId });

    // 已缓存则直接切换，避免重复请求
    if (get().messages[sessionId]) return;

    set({ loadingMessages: true });
    try {
      const page = await api.getMessages(sessionId, 1, PAGE_SIZE);
      set((st) => ({
        messages: { ...st.messages, [sessionId]: page.list },
        messageTotal: { ...st.messageTotal, [sessionId]: page.total },
        loadedPage: { ...st.loadedPage, [sessionId]: 1 },
      }));
    } catch (err) {
      console.error('会话消息加载失败', err);
    } finally {
      set({ loadingMessages: false });
    }
  },

  renameSession: async (_userId, sessionId, title) => {
    try {
      await api.renameSession(sessionId, title);
      set((st) => ({
        sessionList: st.sessionList.map((s) =>
          s.sessionId === sessionId ? { ...s, title, updateTime: Date.now() } : s,
        ),
      }));
    } catch (err) {
      console.error('会话重命名失败', err);
    }
  },

  removeSession: async (userId, sessionId) => {
    get().abortStreaming();
    try {
      await api.deleteSession(sessionId);
    } catch (err) {
      console.error('会话删除失败', err);
      return;
    }

    const st = get();
    const newList = st.sessionList.filter((s) => s.sessionId !== sessionId);
    const newMessages = { ...st.messages };
    delete newMessages[sessionId];
    const newTotal = { ...st.messageTotal };
    delete newTotal[sessionId];
    const newLoaded = { ...st.loadedPage };
    delete newLoaded[sessionId];

    // 若删除的是激活会话，自动选中列表第一个
    const nextActive =
      st.activeSessionId === sessionId ? (newList[0]?.sessionId ?? null) : st.activeSessionId;

    set({
      sessionList: newList,
      messages: newMessages,
      messageTotal: newTotal,
      loadedPage: newLoaded,
      activeSessionId: nextActive,
    });

    // 自动选中的新会话若未加载消息，触发加载
    if (nextActive && !newMessages[nextActive]) {
      await get().selectSession(userId, nextActive);
    }
  },

  sendMessage: async (userId, content, fileId, display) => {
    if (get().streaming) return;
    if (!content.trim() && !fileId) return;

    // 无激活会话时先自动新建
    let sessionId = get().activeSessionId;
    if (!sessionId) {
      await get().createSession(userId);
      sessionId = get().activeSessionId;
      if (!sessionId) return;
    }

    const now = Date.now();
    const userMsg: ChatMessage = {
      msgId: crypto.randomUUID(),
      sessionId,
      role: 'user',
      content,
      createTime: now,
      // 本地渲染附加字段（不属于后端数据模型）
      image: display?.image,
      fileName: display?.fileName,
    };
    const placeholder: ChatMessage = {
      msgId: crypto.randomUUID(),
      sessionId,
      role: 'assistant',
      content: '',
      createTime: now,
    };
    const placeholderMsgId = placeholder.msgId;

    // 追加用户消息 + assistant 占位
    set((st) => ({
      messages: {
        ...st.messages,
        [sessionId]: [...(st.messages[sessionId] ?? []), userMsg, placeholder],
      },
      messageTotal: {
        ...st.messageTotal,
        [sessionId]: (st.messageTotal[sessionId] ?? 0) + 2,
      },
      streaming: true,
    }));

    abortController = new AbortController();
    try {
      await api.streamChat(
        { sessionId, userId, content, fileId: fileId ?? null },
        (evt: StreamEvent) => {
          set((st) => {
            const list = st.messages[sessionId] ?? [];
            const lastIndex = list.length - 1;
            if (lastIndex < 0) return st;
            const updated = [...list];
            const last = updated[lastIndex];
            if (evt.type === 'stream_thinking') {
              // 思考增量（灰色小字）：先于正文流到达
              updated[lastIndex] = { ...last, thinking: (last.thinking || '') + (evt.content || '') };
            } else if (evt.type === 'stream_content') {
              // 正文增量（正常字体）：思考流结束后开始
              updated[lastIndex] = { ...last, content: last.content + (evt.content || '') };
            } else if (evt.image) {
              // AI 生成的图片（文生图/图生图/截图/图表）：附加到当前 AI 消息
              updated[lastIndex] = { ...last, image: evt.image };
            } else if (evt.audio) {
              // AI 语音回复（TTS）：渲染成语音气泡
              const mime = evt.audio.mime || 'audio/mpeg';
              updated[lastIndex] = { ...last, voice: `data:${mime};base64,${evt.audio.base64}` };
            } else if (evt.file) {
              // 文件转换产物（转成pdf等）：触发浏览器下载
              downloadBase64File(evt.file.base64, evt.file.name);
            } else if (evt.done) {
              // 流式结束：以完整回复为准整段覆盖（消除增量偏差/违规重试残留）
              if (evt.delta) updated[lastIndex] = { ...last, content: evt.delta };
            } else if (evt.delta) {
              // 真流式增量：逐段追加（不可变更新，避免 StrictMode 双调用 delta 翻倍）
              updated[lastIndex] = { ...last, content: last.content + evt.delta };
            }
            return { ...st, messages: { ...st.messages, [sessionId]: updated } };
          });
        },
        abortController.signal,
      );
    } catch (err) {
      const aborted =
        err instanceof DOMException
          ? err.name === 'AbortError'
          : (err as Error | undefined)?.name === 'AbortError';
      if (aborted) {
        // 切换会话/删除导致的中断：移除空占位消息，避免残留"思考中"气泡
        set((st) => {
          const list = st.messages[sessionId] ?? [];
          const updated = list.filter((m) => m.msgId !== placeholderMsgId);
          return {
            ...st,
            messages: { ...st.messages, [sessionId]: updated },
            messageTotal: {
              ...st.messageTotal,
              [sessionId]: Math.max(0, (st.messageTotal[sessionId] ?? 1) - 1),
            },
          };
        });
      } else {
        console.error('请求异常：', err);
        set((st) => {
          const list = st.messages[sessionId] ?? [];
          const updated = [...list];
          const lastIndex = updated.length - 1;
          if (lastIndex >= 0) {
            updated[lastIndex] = {
              ...updated[lastIndex],
              content: '😢 请求失败，请检查后端服务是否启动！',
            };
          }
          return { ...st, messages: { ...st.messages, [sessionId]: updated } };
        });
      }
    } finally {
      abortController = null;
      set({ streaming: false });
      // 流式结束后刷新会话列表（标题生成/updateTime 变化）
      await get().refreshSessions(userId);
    }
  },

  loadOlderMessages: async (sessionId) => {
    const st = get();
    const total = st.messageTotal[sessionId] ?? 0;
    const loaded = (st.messages[sessionId] ?? []).length;
    if (total <= loaded) return; // 没有更多

    const nextPage = (st.loadedPage[sessionId] ?? 0) + 1;
    try {
      const res = await api.getMessages(sessionId, nextPage, PAGE_SIZE);
      if (res.list.length > 0) {
        set((s) => ({
          messages: {
            ...s.messages,
            [sessionId]: [...res.list, ...(s.messages[sessionId] ?? [])],
          },
          loadedPage: { ...s.loadedPage, [sessionId]: nextPage },
        }));
      }
    } catch (err) {
      console.error('加载更早消息失败', err);
    }
  },

  abortStreaming: () => {
    abortController?.abort();
    abortController = null;
    set({ streaming: false });
  },
}));
