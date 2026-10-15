import { useState } from 'react';
import QuizView from './QuizView';
import MistakeView from './MistakeView';

/**
 * 刷题 + 错题本（合并视图）
 * 与知识中心格式一致：learn-header 标题 + kc-tabs 页内切换。
 * 两子组件保持挂载（display 隐藏非激活），切换时各自状态不丢；子组件用 embedded 模式隐藏重复标题。
 */
export default function QuizMistakeView({ userId }) {
  const [tab, setTab] = useState('quiz');

  return (
    <div className="quiz-container quiz-scroll">
      <div className="learn-header">
        <h3>📝 刷题练习</h3>
      </div>
      <div className="kc-tabs">
        <button
          className={`kc-tab ${tab === 'quiz' ? 'active' : ''}`}
          onClick={() => setTab('quiz')}
        >
          📝 刷题
        </button>
        <button
          className={`kc-tab ${tab === 'mistake' ? 'active' : ''}`}
          onClick={() => setTab('mistake')}
        >
          📕 错题本
        </button>
      </div>
      {/* 两组件保持挂载（display 隐藏非激活），切换状态不丢 */}
      <div style={{ display: tab === 'quiz' ? 'block' : 'none' }}>
        <QuizView userId={userId} embedded />
      </div>
      <div style={{ display: tab === 'mistake' ? 'block' : 'none' }}>
        <MistakeView userId={userId} embedded />
      </div>
    </div>
  );
}
