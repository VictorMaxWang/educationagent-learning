/**
 * 历史对话侧边栏 — 统一数据模型
 * 与后端 com.demo.session 模块字段严格对齐，禁止擅自增减字段。
 * 所有时间统一为毫秒时间戳（long），前后端时区一致。
 */

/** 会话元数据（侧边栏列表条目） */
export interface ChatSession {
  /** 会话唯一主键 */
  sessionId: string;
  /** 会话标题 */
  title: string;
  /** 创建时间（epoch 毫秒） */
  createTime: number;
  /** 更新时间（epoch 毫秒） */
  updateTime: number;
}

/** 单条对话消息 */
export interface ChatMessage {
  /** 消息唯一主键 */
  msgId: string;
  /** 关联会话 ID */
  sessionId: string;
  /** 消息角色 */
  role: 'user' | 'assistant';
  /** 消息内容 */
  content: string;
  /** 创建时间（epoch 毫秒） */
  createTime: number;
  /** 消息图片：流式期间为 data URL（用户上传预览 / AI 生成图即时显示）；历史消息为后端相对路径（/chat/images/xxx.png，渲染时经 resolveApiUrl 拼接加载） */
  image?: string;
  /** 【仅本地渲染】AI 语音回复的 data URL（audio/mpeg，语音气泡播放） */
  voice?: string;
  /** 【仅本地渲染】AI 思考过程（LLM reasoning_content，小字灰色展示在答案上方） */
  thinking?: string;
  /** 【仅本地渲染，不属于后端数据模型】附件文件名（用户上传文件时显示 chip） */
  fileName?: string;
}

/** 会话消息分页结果（page=1 为最近一页，list 升序） */
export interface MessagePage {
  total: number;
  page: number;
  size: number;
  list: ChatMessage[];
}

/** NDJSON 流式事件（与后端 StreamItem 字段对齐） */
export interface StreamEvent {
  /** 消息类型：stream_thinking=思考增量（灰色小字）/ stream_content=正文增量（正常） */
  type?: 'stream_thinking' | 'stream_content';
  /** 对应 type 的增量文本 */
  content?: string;
  /** 文本增量（done=false 时逐段追加） */
  delta?: string;
  /** 流结束标记：true 时 delta 为权威完整回复 */
  done?: boolean;
  /** AI 生成的图片 data URL */
  image?: string;
  /** 文件转换产物（触发下载） */
  file?: { name: string; base64: string };
  /** AI 语音回复（TTS MP3，渲染成语音气泡） */
  audio?: { name?: string; mime?: string; base64: string };
}
