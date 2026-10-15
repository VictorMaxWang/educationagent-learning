import MaimemoStudy from './english/MaimemoStudy';
import MaimemoListening from './english/MaimemoListening';
import MaimemoSpeaking from './english/MaimemoSpeaking';
import GrammarView from './english/GrammarView';

/**
 * 英语学习页：侧栏子目录选择后，只展示对应模块（目录在侧栏负责导航）。
 */
function EnglishView({ module = 'review' }) {
  let content;
  switch (module) {
    // 词库生成 + 背单词 + 查生词 融合为「单词学习」（内部 复习/词库/查词 三个大 Tab）
    case 'review':
    default:
      content = <MaimemoStudy />;
      break;
    case 'listening': content = <MaimemoListening />; break;
    case 'speaking': content = <MaimemoSpeaking />; break;
    case 'grammar': content = <GrammarView />; break;
  }
  return <div className="quiz-container quiz-scroll">{content}</div>;
}

export default EnglishView;
