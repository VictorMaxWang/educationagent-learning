import { useEffect, useState } from "react";
import MaimemoOutfit from "./MaimemoOutfit";
import {
  getOutfitSessions,
  createOutfitSession,
  deleteOutfitSession,
} from "./outfitStorage";
import "./travel.css";

/**
 * ✈️ 旅行规划（出行模块）
 * 包装 MaimemoOutfit：会话（行程）管理 + 侧栏子标题直达 旅游计划/按天穿搭。
 * 行程数据全部 localStorage 持久化（desktop-outfit-*）。
 */
export default function TravelView({ module = "plan" }: { module?: "plan" | "outfit" | "hotel" | "food" }) {
  const [currentOutfitId, setCurrentOutfitId] = useState<string>("");
  const [, setTick] = useState(0); // 触发会话列表刷新

  // 启动时确保至少有一个行程会话
  useEffect(() => {
    if (!getOutfitSessions().length) {
      createOutfitSession();
    }
    setCurrentOutfitId((cur) => {
      const list = getOutfitSessions();
      return cur && list.some((s) => s.sessionId === cur) ? cur : (list[0]?.sessionId ?? "");
    });
  }, []);

  const sessions = getOutfitSessions();
  const refresh = () => setTick((t) => t + 1);

  const newTrip = () => {
    const s = createOutfitSession();
    setCurrentOutfitId(s.sessionId);
    refresh();
  };

  const removeTrip = () => {
    if (!currentOutfitId) return;
    if (!window.confirm("删除当前行程？行程数据不可恢复。")) return;
    deleteOutfitSession(currentOutfitId);
    const rest = getOutfitSessions();
    setCurrentOutfitId(rest[0]?.sessionId ?? "");
    refresh();
  };

  return (
    <div className="quiz-container quiz-scroll trv-root">
      {/* 行程会话栏 */}
      <div className="trv-session-bar">
        <select
          className="trv-session-select"
          value={currentOutfitId}
          onChange={(e) => setCurrentOutfitId(e.target.value)}
          title="切换行程"
        >
          {sessions.length === 0 && <option value="">暂无行程</option>}
          {sessions.map((s) => (
            <option key={s.sessionId} value={s.sessionId}>
              {s.title}
            </option>
          ))}
        </select>
        <button className="btn send-btn" onClick={newTrip}>
          ＋ 新建行程
        </button>
        {currentOutfitId && (
          <button className="btn trv-del-btn" onClick={removeTrip}>
            删除
          </button>
        )}
      </div>

      {currentOutfitId ? (
        <MaimemoOutfit
          currentOutfitId={currentOutfitId}
          onSessionsChanged={refresh}
          subview={module}
          onSubviewChange={() => {}}
        />
      ) : (
        <div className="trv-empty">
          <div className="trv-empty-icon">✈️</div>
          <div>还没有行程，点击「＋ 新建行程」开始规划</div>
        </div>
      )}
    </div>
  );
}
