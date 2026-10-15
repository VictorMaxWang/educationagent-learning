import { useState, useRef, useEffect } from "react";

/**
 * 右上角成员下拉切换（通用）
 */
export default function MemberDropdown({ members, activeMemberId, activeMember, onSwitch, onAdd }) {
  const [open, setOpen] = useState(false);
  const ref = useRef(null);

  useEffect(() => {
    const handler = (e) => { if (ref.current && !ref.current.contains(e.target)) setOpen(false); };
    document.addEventListener("mousedown", handler);
    return () => document.removeEventListener("mousedown", handler);
  }, []);

  return (
    <div className="member-dropdown" ref={ref}>
      <button className="member-dropdown-trigger" onClick={() => setOpen(!open)}>
        <span className="member-dropdown-name">{activeMember?.name || "本人"}</span>
        <span className={`member-dropdown-arrow${open ? " open" : ""}`}>▾</span>
      </button>
      {open && (
        <div className="member-dropdown-menu">
          {members.map(m => (
            <div key={m.id}
              className={`member-dropdown-item${m.id === activeMemberId ? " active" : ""}`}
              onClick={() => { onSwitch(m.id); setOpen(false); }}>
              <span className="member-capsule-avatar">{m.gender === "女" ? "👩" : "👨"}</span>
              <span className="member-capsule-name">{m.name}</span>
            </div>
          ))}
          <div className="member-dropdown-item member-dropdown-add" onClick={() => { onAdd(); setOpen(false); }}>
            <span className="member-capsule-avatar">＋</span>
            <span className="member-capsule-name">添加成员</span>
          </div>
        </div>
      )}
    </div>
  );
}
