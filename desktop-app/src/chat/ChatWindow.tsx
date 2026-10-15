/**
 * 聊天主窗口（右侧面板）
 * 内含：消息列表 + 输入区（文件/语音/发送）。历史会话列表已移至左侧导航栏下半部分。
 * 消息渲染保留原 App.jsx 能力：markdown、图片、文件 chip、空态建议卡片。
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import { useChatStore } from './chatStore';
import { getVoiceMode, setVoiceMode } from './chatSessionApi';
import { uploadFile } from '../api/chatApi';
import { resolveApiUrl } from '../api/request';
import { compressImage, fileToBase64 } from '../utils/file';
import { renderMarkdown } from './markdown';
import ImageLightbox from './ImageLightbox';
import VoiceBubble from './VoiceBubble';
import type { ChatMessage } from './chat';

/** 兜底复制：剪贴板 API 不可用（非安全上下文）时用临时 textarea + execCommand */
function fallbackCopy(text: string, done: () => void) {
  try {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    document.execCommand('copy');
    document.body.removeChild(ta);
    done();
  } catch {
    /* 忽略复制失败 */
  }
}

/** 对话空态建议池（Agent 功能 + MCP 服务）：每次随机取几条展示，点击填入输入框（不自动发送） */
const SUGGESTIONS_POOL = [
  // —— Agent 核心功能 ——
  '🌤 明天杭州天气怎么样',
  '🚄 查一下北京到上海的高铁',
  '🗺️ 从西湖到灵隐寺怎么走',
  '🎨 帮我画一张星空下的猫',
  '🖼️ 把这张图改成动漫风格',
  '📸 截个图',
  '📊 用我发的 Excel 生成柱状图',
  '🌐 搜索一下最近的科技新闻',
  '📄 帮我读一下这个链接的内容',
  '📷 给我看一张大熊猫的真实照片',
  '📂 读取 D:\\a.txt 的内容',
  '🔄 把这个文件转成pdf',
  '🧮 算一下 25*48+137',
  '⏰ 15分钟后提醒我喝水',
  '📧 帮我发一封邮件',
  '📅 每天早上8点推送日报',
  '📢 用语音回复我',
  '🎵 把声音换成男声',
  '💬 切换到精简模式',
  '📚 把这个文件存到我的知识库',
  // —— MCP 服务 ——
  '🚕 打车去杭州东站',
  '✈️ 查北京到上海的机票',
  '🏨 帮我查一下杭州的酒店',
  '🎫 帮我订一张高铁票',
  '📝 帮我投个简历',
  '📱 帮我运营小红书',
  '🍔 给我点一份麦当劳',
  '📋 帮我生成一个待办事项清单',
  '📄 帮我生成一个Word文档',
];

/** 每次随机取 9~12 条建议（保证排成 3 行） */
function pickRandomSuggestions() {
  const shuffled = [...SUGGESTIONS_POOL].sort(() => Math.random() - 0.5);
  const count = 9 + Math.floor(Math.random() * 4); // 9~12 条
  return shuffled.slice(0, count);
}

/** 稳定空数组引用，避免 zustand 选择器每次返回新引用导致无限重渲染 */
const EMPTY_MESSAGES: ChatMessage[] = [];

/** 浏览器语音识别 API 最小化类型（webkit 前缀在 TS DOM lib 中缺失） */
interface SpeechRecognitionLike {
  continuous: boolean;
  interimResults: boolean;
  lang: string;
  onresult: ((e: { results: ArrayLike<{ 0: { transcript: string } }> }) => void) | null;
  onend: (() => void) | null;
  onerror: ((e: { error: string }) => void) | null;
  start(): void;
  stop(): void;
}

function createSpeechRecognition(): SpeechRecognitionLike | null {
  const w = window as unknown as {
    SpeechRecognition?: new () => SpeechRecognitionLike;
    webkitSpeechRecognition?: new () => SpeechRecognitionLike;
  };
  const Ctor = w.SpeechRecognition ?? w.webkitSpeechRecognition;
  return Ctor ? new Ctor() : null;
}

interface ChatWindowProps {
  userId: string;
}

interface PendingFile {
  fileId: string;
  kind: 'image' | 'file';
  dataUrl: string | null;
  fileName: string;
}

export default function ChatWindow({ userId }: ChatWindowProps) {
  const sessionList = useChatStore((s) => s.sessionList);
  const activeSessionId = useChatStore((s) => s.activeSessionId);
  const messages = useChatStore((s) =>
    s.activeSessionId ? (s.messages[s.activeSessionId] ?? EMPTY_MESSAGES) : EMPTY_MESSAGES,
  );
  const messageTotal = useChatStore((s) =>
    s.activeSessionId ? (s.messageTotal[s.activeSessionId] ?? 0) : 0,
  );
  const streaming = useChatStore((s) => s.streaming);
  const loadingMessages = useChatStore((s) => s.loadingMessages);
  const init = useChatStore((s) => s.init);
  const sendMessage = useChatStore((s) => s.sendMessage);
  const createSession = useChatStore((s) => s.createSession);
  const loadOlderMessages = useChatStore((s) => s.loadOlderMessages);

  const [inputText, setInputText] = useState('');
  const [suggestions, setSuggestions] = useState<string[]>(() => pickRandomSuggestions());
  const [pendingFile, setPendingFile] = useState<PendingFile | null>(null);
  const [isVoiceRecording, setIsVoiceRecording] = useState(false);
  const [dragOver, setDragOver] = useState(false); // 拖拽文件到聊天面板
  const [previewSrc, setPreviewSrc] = useState<string | null>(null); // 图片放大查看
  const [voiceEnabled, setVoiceEnabled] = useState(false); // 语音播报开关（顶部听筒按钮）

  const chatBoxRef = useRef<HTMLDivElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const recognitionRef = useRef<SpeechRecognitionLike | null>(null);

  const activeSession = sessionList.find((s) => s.sessionId === activeSessionId) ?? null;

  // 启动初始化：拉取会话列表渲染侧边栏
  useEffect(() => {
    init(userId);
  }, [init, userId]);

  // 语音播报开关：读取后端持久化状态；流式结束后同步（LLM 通过「用语音回复」等命令改的状态）
  const loadVoiceMode = useCallback(async () => {
    try {
      const res = await getVoiceMode(userId);
      setVoiceEnabled(!!res?.enabled);
    } catch {
      /* 忽略：接口失败保持当前状态 */
    }
  }, [userId]);

  useEffect(() => {
    loadVoiceMode();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [userId]);

  useEffect(() => {
    if (!streaming) loadVoiceMode();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [streaming]);

  // 代码块复制：事件委托监听 .md-code-copy 点击，把对应 <pre><code> 文本复制到剪贴板
  useEffect(() => {
    const box = chatBoxRef.current;
    if (!box) return;
    const onClick = (e: MouseEvent) => {
      const btn = (e.target as HTMLElement).closest<HTMLButtonElement>('.md-code-copy');
      if (!btn) return;
      const code = btn.closest('.md-code')?.querySelector('pre code')?.textContent;
      if (!code) return;
      const feedback = () => {
        const old = btn.textContent;
        btn.textContent = '✓ 已复制';
        setTimeout(() => { btn.textContent = old; }, 1200);
      };
      if (navigator.clipboard?.writeText) {
        navigator.clipboard.writeText(code).then(feedback).catch(() => fallbackCopy(code, feedback));
      } else {
        fallbackCopy(code, feedback);
      }
    };
    box.addEventListener('click', onClick);
    return () => box.removeEventListener('click', onClick);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const toggleVoice = async () => {
    const next = !voiceEnabled;
    setVoiceEnabled(next); // 乐观更新
    try {
      await setVoiceMode(userId, next);
    } catch {
      setVoiceEnabled(!next); // 失败回滚
    }
  };

  // 聊天滚动到底部
  useEffect(() => {
    if (chatBoxRef.current) {
      chatBoxRef.current.scrollTop = chatBoxRef.current.scrollHeight;
    }
  }, [messages]);

  // 空态每次出现（新建对话/切换会话）重新随机一批建议
  // 用 activeSessionId 而非 messages.length：从空会话新建时 length 仍是 0 不触发
  useEffect(() => {
    if (messages.length === 0) setSuggestions(pickRandomSuggestions());
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeSessionId]);

  // ======================== 语音识别 ========================
  const toggleVoiceRecord = useCallback(() => {
    if (!recognitionRef.current) {
      const recog = createSpeechRecognition();
      if (!recog) {
        alert('当前环境不支持语音识别！');
        return;
      }
      recognitionRef.current = recog;
      recog.continuous = true; // 持续识别
      recog.interimResults = true; // 返回中间临时结果
      recog.lang = 'zh-CN';
      recog.onresult = (e) => {
        let transcript = '';
        for (let i = 0; i < e.results.length; i++) {
          transcript += e.results[i][0].transcript;
        }
        setInputText(transcript);
      };
      recog.onend = () => setIsVoiceRecording(false);
      recog.onerror = (err) => {
        console.error('语音识别错误：', err.error);
        setIsVoiceRecording(false);
        alert(`语音识别失败：${err.error}`);
      };
    }

    if (isVoiceRecording) {
      recognitionRef.current.stop();
      setIsVoiceRecording(false);
    } else {
      recognitionRef.current.start();
      setIsVoiceRecording(true);
    }
  }, [isVoiceRecording]);

  // ======================== 文件选择/拖拽上传 ========================
  const handleFile = async (file: File) => {
    try {
      if (file.type.startsWith('image/')) {
        const { mime, data } = await compressImage(file);
        const res = await uploadFile({ fileName: file.name, mime, data });
        if (res && res.fileId) {
          setPendingFile({
            fileId: res.fileId,
            kind: 'image',
            dataUrl: `data:${mime};base64,${data}`,
            fileName: file.name,
          });
        } else {
          alert('上传失败：' + (res?.error || '未知错误'));
        }
      } else {
        const data = await fileToBase64(file);
        const mime = file.type || 'application/octet-stream';
        const res = await uploadFile({ fileName: file.name, mime, data });
        if (res && res.fileId) {
          setPendingFile({ fileId: res.fileId, kind: 'file', dataUrl: null, fileName: file.name });
        } else {
          alert('上传失败：' + (res?.error || '未知错误'));
        }
      }
    } catch (err) {
      alert('上传失败：' + (err as Error).message);
    }
  };

  const handleFileChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    e.target.value = ''; // 允许重复选同一个
    if (file) await handleFile(file);
  };

  const handlePickImage = () => {
    if (streaming) return;
    fileInputRef.current?.click();
  };

  // 拖拽上传：整个聊天面板作为投放区
  const handleDragOver = (e: React.DragEvent) => {
    e.preventDefault();
    if (streaming) return;
    setDragOver(true);
  };
  const handleDragLeave = () => setDragOver(false);
  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setDragOver(false);
    if (streaming) return;
    const file = e.dataTransfer?.files?.[0];
    if (file) handleFile(file);
  };

  // 全局拦截：文件拖到投放区之外时不触发浏览器"打开文件"跳页
  useEffect(() => {
    const prevent = (e: DragEvent) => e.preventDefault();
    window.addEventListener('dragover', prevent);
    window.addEventListener('drop', prevent);
    return () => {
      window.removeEventListener('dragover', prevent);
      window.removeEventListener('drop', prevent);
    };
  }, []);

  // ======================== 发送 ========================
  const handleSend = async () => {
    const content = inputText.trim();
    if (!content && !pendingFile) return;

    // 本地渲染附加字段：图片预览 / 文件名
    const display = pendingFile
      ? {
          image: pendingFile.kind === 'image' ? (pendingFile.dataUrl ?? undefined) : undefined,
          fileName: pendingFile.kind === 'file' ? pendingFile.fileName : undefined,
        }
      : undefined;

    setInputText('');
    setPendingFile(null);
    await sendMessage(userId, content, pendingFile?.fileId ?? null, display);
  };

  const onKeyEnter = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  const handleLoadOlder = () => {
    if (activeSessionId) loadOlderMessages(activeSessionId);
  };

  // ======================== 渲染 ========================
  return (
    <div className="conv-wrap">
      {/* 聊天面板（历史会话已移至左侧导航栏下半部分） */}
      <div
        className="conv-chat"
        onDragOver={handleDragOver}
        onDragLeave={handleDragLeave}
        onDrop={handleDrop}
      >
        <div className="chat-header">
          <div className="chat-header-titles">
            <h1 className="chat-title" title={activeSession?.title || '对话'}>
              {activeSession?.title || '对话'}
            </h1>
            <p className="chat-subtitle">AI 生成可能有误 注意核实</p>
          </div>
          {/* 新建对话 */}
          <button className="chat-new-btn" onClick={() => createSession(userId)} title="新建对话">
            <span>＋</span> 新建对话
          </button>
          {/* 语音播报听筒开关 */}
          <button
            className={`voice-toggle${voiceEnabled ? ' on' : ''}`}
            onClick={toggleVoice}
            title={voiceEnabled ? '关闭语音播报' : '开启语音播报'}
          >
            <span className="voice-toggle-icon">{voiceEnabled ? '🔊' : '🔇'}</span>
          </button>
        </div>

        {/* 消息列表 */}
        <div className="chat-box" ref={chatBoxRef}>
          {loadingMessages && messages.length === 0 ? (
            <div className="chat-empty">
              <div className="chat-empty-title">⏳ 加载会话历史...</div>
            </div>
          ) : messages.length === 0 ? (
            <div className="chat-empty">
              <div className="chat-empty-title">✨ 直接问我，我能帮你：</div>
              <div className="chat-suggestions">
                {suggestions.map((s) => (
                  <button key={s} className="chat-suggestion" onClick={() => setInputText(s)}>
                    {s}
                  </button>
                ))}
              </div>
            </div>
          ) : (
            <>
              {messageTotal > messages.length && (
                <button className="chat-load-older" onClick={handleLoadOlder}>
                  ↑ 加载更早消息
                </button>
              )}
              {messages.map((item) => (
                <div key={item.msgId} className={item.role === 'user' ? 'msg-user' : 'msg-ai'}>
                  {item.image && (
                    <img
                      className="msg-img"
                      src={resolveApiUrl(item.image)}
                      alt="图片"
                      title="点击放大查看"
                      onClick={() => setPreviewSrc(resolveApiUrl(item.image))}
                    />
                  )}
                  {item.fileName && <div className="msg-file-chip">📄 {item.fileName}</div>}
                  {item.role === 'user' ? (
                    // 纯图片/文件消息（无文字）不渲染气泡，避免图片下方出现空气泡
                    item.content ? <div className="msg-content">{item.content}</div> : null
                  ) : (
                    <>
                      {/* LLM 思考过程（小字灰色，替换"思考中..."占位） */}
                      {item.thinking && <div className="msg-thinking">💭 {item.thinking}</div>}
                      {item.content ? (
                        <div
                          className="msg-content md-render"
                          dangerouslySetInnerHTML={{ __html: renderMarkdown(item.content) }}
                        />
                      ) : item.thinking ? null : (
                        <div className="msg-content md-render">✍️ 思考中...</div>
                      )}
                    </>
                  )}
                  {/* AI 语音回复气泡 */}
                  {item.voice && <VoiceBubble src={item.voice} />}
                </div>
              ))}
            </>
          )}
        </div>

        {/* 悬浮输入区（豆包样式：底部悬浮卡片 + 渐变背景） */}
        <div className="input-dock">
          {pendingFile && (
            <div className="pending-img-wrap">
              {pendingFile.kind === 'image' ? (
                <img className="pending-img" src={pendingFile.dataUrl ?? undefined} alt="待发送图片" />
              ) : (
                <div className="pending-file-chip">📄 {pendingFile.fileName}</div>
              )}
              <button className="pending-img-clear" onClick={() => setPendingFile(null)}>
                ✕
              </button>
            </div>
          )}

          <div className="input-area">
            <input
              type="file"
              ref={fileInputRef}
              style={{ display: 'none' }}
              onChange={handleFileChange}
            />
            <button
              className="input-icon-btn"
              onClick={handlePickImage}
              disabled={streaming}
              title="发送文件"
            >
              📎
            </button>

            <textarea
              value={inputText}
              onChange={(e) => setInputText(e.target.value)}
              onKeyDown={onKeyEnter}
              placeholder="💭 输入你的问题，也可以点击麦克风语音输入..."
              disabled={streaming}
              rows={1}
            />

            <button
              className={`input-icon-btn${isVoiceRecording ? ' btn-recording' : ''}`}
              onClick={toggleVoiceRecord}
              title={isVoiceRecording ? '停止收音' : '语音输入'}
            >
              {isVoiceRecording ? '🔴' : '🎤'}
            </button>

            <button onClick={handleSend} disabled={streaming}>
              {streaming ? '✨ 思考中' : '发送'}
            </button>
          </div>
        </div>

        {/* 拖拽文件投放提示层 */}
        {dragOver && (
          <div className="chat-drop-overlay">
            <div className="chat-drop-box">📎 松开以发送文件</div>
          </div>
        )}
      </div>

      {/* 图片放大查看（点击 msg-img 打开，ESC/空白/✕ 关闭） */}
      <ImageLightbox src={previewSrc} onClose={() => setPreviewSrc(null)} />
    </div>
  );
}
