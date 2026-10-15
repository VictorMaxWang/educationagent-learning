/**
 * 会话列表单项
 * 展示标题 + 相对时间；hover 出现操作菜单（重命名/删除）
 */
import type { ChatSession } from './chat';

interface ChatItemProps {
  session: ChatSession;
  active: boolean;
  onSelect: (sessionId: string) => void;
  onRename: (sessionId: string) => void;
  onDelete: (sessionId: string) => void;
}

/** 相对时间：今天显示 HH:mm，昨天显示"昨天"，更早显示"X月X日" */
function formatTime(ts: number): string {
  const d = new Date(ts);
  const now = new Date();
  if (d.toDateString() === now.toDateString()) {
    return `${d.getHours()}:${String(d.getMinutes()).padStart(2, '0')}`;
  }
  const yesterday = new Date(now);
  yesterday.setDate(now.getDate() - 1);
  if (d.toDateString() === yesterday.toDateString()) return '昨天';
  return `${d.getMonth() + 1}月${d.getDate()}日`;
}

export default function ChatItem({ session, active, onSelect, onRename, onDelete }: ChatItemProps) {
  return (
    <div
      className={`conv-item${active ? ' conv-item-active' : ''}`}
      onClick={() => onSelect(session.sessionId)}
      title={session.title || '新对话'}
    >
      <span className="conv-item-title">{session.title || '新对话'}</span>
      <span className="conv-item-time">{formatTime(session.updateTime)}</span>
      {/* hover 操作菜单（点击不冒泡到选中） */}
      <span className="conv-item-ops" onClick={(e) => e.stopPropagation()}>
        <button className="conv-op" title="重命名" onClick={() => onRename(session.sessionId)}>
          ✎
        </button>
        <button className="conv-op" title="删除" onClick={() => onDelete(session.sessionId)}>
          🗑
        </button>
      </span>
    </div>
  );
}
