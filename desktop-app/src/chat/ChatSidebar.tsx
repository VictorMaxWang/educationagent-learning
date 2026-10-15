/**
 * 历史对话侧边栏
 * 新建对话按钮 + 会话列表渲染 + 点击切换 + 单条删除/重命名（hover 操作菜单）
 * 顶部提供折叠按钮，可把会话列表收进左侧（露出小标签）
 */
import { useChatStore } from './chatStore';
import ChatItem from './ChatItem';

interface ChatSidebarProps {
  userId: string;
  /** 点击折叠按钮回调（父组件把会话栏收起，露出边缘标签） */
  onCollapse?: () => void;
  /** 手动伸缩后的宽度（px），由父组件拖拽控制 */
  width?: number;
}

export default function ChatSidebar({ userId, onCollapse, width }: ChatSidebarProps) {
  const sessionList = useChatStore((s) => s.sessionList);
  const activeSessionId = useChatStore((s) => s.activeSessionId);
  const loadingSessions = useChatStore((s) => s.loadingSessions);
  const createSession = useChatStore((s) => s.createSession);
  const selectSession = useChatStore((s) => s.selectSession);
  const renameSession = useChatStore((s) => s.renameSession);
  const removeSession = useChatStore((s) => s.removeSession);

  /** 重命名：prompt 输入新标题 */
  const handleRename = (sessionId: string) => {
    const current = sessionList.find((s) => s.sessionId === sessionId);
    const title = window.prompt('重命名会话', current?.title ?? '');
    if (title != null && title.trim()) {
      renameSession(userId, sessionId, title.trim());
    }
  };

  /** 删除：确认后调用后端删除接口，store 本地同步移除 */
  const handleDelete = (sessionId: string) => {
    if (window.confirm('确定删除该会话吗？将同时清除其中全部消息。')) {
      removeSession(userId, sessionId);
    }
  };

  return (
    <aside className="conv-sidebar" style={width ? { width } : undefined}>
      <div className="conv-sidebar-header">
        <span className="conv-sidebar-title">历史会话</span>
        {onCollapse && (
          <button className="conv-collapse-btn" onClick={onCollapse} title="隐藏会话列表">
            ◀
          </button>
        )}
      </div>
      <button className="conv-new" onClick={() => createSession(userId)}>
        ＋ 新建对话
      </button>

      <div className="conv-list">
        {loadingSessions && sessionList.length === 0 ? (
          <div className="conv-loading">加载中...</div>
        ) : sessionList.length === 0 ? (
          <div className="conv-empty">暂无会话，点击上方新建</div>
        ) : (
          sessionList.map((s) => (
            <ChatItem
              key={s.sessionId}
              session={s}
              active={s.sessionId === activeSessionId}
              onSelect={(id) => selectSession(userId, id)}
              onRename={handleRename}
              onDelete={handleDelete}
            />
          ))
        )}
      </div>
    </aside>
  );
}
