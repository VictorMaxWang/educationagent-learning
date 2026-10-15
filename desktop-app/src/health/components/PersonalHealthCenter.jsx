import { useState } from "react";
import BodyProfile from "./BodyProfile.jsx";
import MedicineReminder from "./MedicineReminder.jsx";
import WeightFatlossTab from "./WeightFatlossTab.jsx";
import MemberDropdown from "./MemberDropdown.jsx";
import { loadMembers, createMember, setActiveMember, getActiveMember } from "../utils/members.js";

const TABS = [
  { key: "body", label: "📐 体态体征档案" },
  { key: "weight", label: "📈 体重趋势&减脂计划" },
  { key: "medicine", label: "💊 用药提醒管理" },
];

export default function PersonalHealthCenter() {
  const [members, setMembers] = useState(() => loadMembers());
  const [activeMemberId, setActiveMemberId] = useState(() => getActiveMember().id);
  const [tab, setTab] = useState("body");

  const activeMember = members.find(m => m.id === activeMemberId) || members[0] || { id: "default", name: "本人" };

  const switchMember = (id) => {
    setActiveMemberId(id);
    setActiveMember(id);
  };

  const addMember = () => {
    const name = window.prompt("输入成员昵称（如：妈妈 / 老爸）");
    if (!name || !name.trim()) return;
    createMember(name.trim());
    setMembers(loadMembers());
  };

  const refreshMembers = () => {
    setMembers(loadMembers());
    setActiveMemberId(getActiveMember().id);
  };

  return (
    <div className="feature-panel" style={{ overflow: "hidden", flex: 1, display: "flex", flexDirection: "column" }}>
      {/* ─── Tab + 右上角成员下拉 ─── */}
      <div style={{ padding: "12px 24px 0", flexShrink: 0, display: "flex", alignItems: "center", justifyContent: "space-between" }}>
        <div className="hc-cats" style={{ padding: 0 }}>
          {TABS.map(t => (
            <button key={t.key}
              className={`hc-cat${tab === t.key ? " active" : ""}`}
              onClick={() => setTab(t.key)}>
              {t.label}
            </button>
          ))}
        </div>
        <MemberDropdown
          members={members}
          activeMemberId={activeMemberId}
          activeMember={activeMember}
          onSwitch={switchMember}
          onAdd={addMember}
        />
      </div>

      {/* ── Tab 内容 ── */}
      <div style={{ flex: 1, overflow: "hidden", display: "flex", flexDirection: "column" }}>
        {tab === "body" && (
          <BodyProfile
            members={members}
            activeMemberId={activeMemberId}
            onSwitchMember={switchMember}
            onMembersChange={refreshMembers}
            onClose={() => {}}
          />
        )}
        {tab === "weight" && (
          <div style={{ flex: 1, overflowY: "auto", padding: "0 24px 20px" }}>
            <WeightFatlossTab activeMemberId={activeMemberId} />
          </div>
        )}
        {tab === "medicine" && (
          <MedicineReminder
            onClose={() => {}}
            activeMember={activeMember}
            members={members}
            activeMemberId={activeMemberId}
            onSwitchMember={switchMember}
            onAddMember={addMember}
          />
        )}
      </div>
    </div>
  );
}
