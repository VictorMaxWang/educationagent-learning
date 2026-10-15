import { useState } from "react";
import MaimemoReview from "./MaimemoReview";
import MaimemoWordManage from "./MaimemoWordManage";
import MaimemoLookup from "./MaimemoLookup";

type StudyTab = "review" | "library" | "lookup";

/**
 * 📖 单词学习（背单词 + 词库 + 查生词 融合）
 * 三个 Tab：
 *  - 复习：艾宾浩斯间隔复习（翻转/拼写/选择题 + 难词强化）
 *  - 词库：生成 / 导入 / 自定义 / 词库列表（生词本）
 *  - 查词：单查/批量查 + 英/美音 + 一键入库/生词本
 */
export default function MaimemoStudy() {
  const [tab, setTab] = useState<StudyTab>("review");

  return (
    <div className="ms-wrap">
      <div className="ms-tabs">
        <button
          className={`ms-tab${tab === "review" ? " active" : ""}`}
          onClick={() => setTab("review")}
        >
          📖 复习
        </button>
        <button
          className={`ms-tab${tab === "library" ? " active" : ""}`}
          onClick={() => setTab("library")}
        >
          📚 词库
        </button>
        <button
          className={`ms-tab${tab === "lookup" ? " active" : ""}`}
          onClick={() => setTab("lookup")}
        >
          🔍 查词
        </button>
      </div>

      {tab === "review" ? (
        <MaimemoReview />
      ) : tab === "library" ? (
        <MaimemoWordManage />
      ) : (
        <MaimemoLookup />
      )}
    </div>
  );
}
