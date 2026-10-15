import "./health.css";
import PersonalHealthCenter from "./components/PersonalHealthCenter.jsx";
import DietTreatmentCenter from "./components/DietTreatmentCenter.jsx";

type HealthModule = "personal" | "diet";

const MODULE_LABEL: Record<HealthModule, string> = {
  personal: "健康档案",
  diet: "饮食诊疗",
};

/**
 * 💪 健康养护 — 2 个聚合页：个人健康档案中心 / 饮食诊疗服务中心
 * 每个聚合页内部自带 Tab 切换 + 成员选择。
 */
export default function HealthView({ module = "personal" }: { module?: HealthModule }) {
  return (
    <div className="quiz-container quiz-scroll">
      <div className="hp-wrap hp-combined">
        <div className="hp-head">
          <h2>💪 健康养护</h2>
          <span className="hp-sub">{MODULE_LABEL[module]}</span>
        </div>

        {module === "personal" ? (
          <PersonalHealthCenter />
        ) : (
          <DietTreatmentCenter />
        )}
      </div>
    </div>
  );
}
