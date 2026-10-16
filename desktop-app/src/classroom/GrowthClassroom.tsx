import { useRef, useState } from 'react';
import PublicEnglishPanel from '../components/PublicEnglishPanel';
import CourseKnowledgeView from '../study/CourseKnowledgeView';
import CodeVisualization from '../practice/CodeVisualization';
import PracticeActivities from '../practice/PracticeActivities';
import GrowthCompanion from './GrowthCompanion';
import { GROWTH_CATEGORY_ICONS, GROWTH_FALLBACK_TOPICS, GROWTH_GRADE_LABELS, GROWTH_GROUPS, type GrowthCategoryId, type GrowthGroupId } from './growth-topics';
import './growth-classroom.css';

type PracticeMode = 'diagram' | 'simulation' | 'visualization3d' | 'game';
type ClassroomItem = { id: string; label: string; description: string; mark: string; category: GrowthCategoryId; domain?: string; practice?: PracticeMode };
const topicItems = (group: GrowthGroupId): ClassroomItem[] => GROWTH_FALLBACK_TOPICS.filter(topic => topic.group === group).map(topic => ({ id: `talk:${topic.id}`, label: topic.label, description: topic.description, mark: GROWTH_CATEGORY_ICONS[topic.id], category: topic.id }));
const ITEMS: Record<GrowthGroupId, ClassroomItem[]> = {
  'mind-communication': [...topicItems('mind-communication'), { id: 'knowledge:psychology', label: '心理知识库', description: '从资料中理解情绪与关系', mark: '阅', category: 'emotions', domain: 'psychology' }],
  'safety-health': [...topicItems('safety-health'), { id: 'knowledge:lifesafety', label: '安全健康知识库', description: '生命教育与安全求助', mark: '阅', category: 'life-safety', domain: 'lifesafety' }, { id: 'knowledge:sexedu', label: '青春期知识库', description: '身体变化、边界与自我保护', mark: '芽', category: 'puberty', domain: 'sexedu' }],
  'language-general': [{ id: 'english', label: '英语练习', description: '生活对话、语法与跟读', mark: 'Aa', category: 'english' }, { id: 'knowledge:tongshi', label: '通识知识库', description: '阅读、数学与知识问答', mark: '阅', category: 'general', domain: 'tongshi' }, ...topicItems('language-general')],
  'practice-creation': [{ id: 'code', label: '代码可视化', description: '四种语言，逐步看懂代码', mark: '</>', category: 'practice' }, { id: 'diagram', label: '交互图解', description: '通分步骤与水循环', mark: '图', category: 'practice', practice: 'diagram' }, { id: 'simulation', label: '仿真实验', description: '面积与分数，改变参数看结果', mark: '验', category: 'practice', practice: 'simulation' }, { id: 'visualization3d', label: '3D 探索', description: '旋转、测量与空间想象', mark: '方', category: 'practice', practice: 'visualization3d' }, { id: 'game', label: '闯关小游戏', description: '在分数配对中练习与反馈', mark: '关', category: 'practice', practice: 'game' }, ...topicItems('practice-creation')],
};

function initialGrade(): number {
  try { const value = Number(localStorage.getItem('educationagent.growth.grade')); return Number.isInteger(value) && value >= 1 && value <= 12 ? value : 5; } catch { return 5; }
}
function initialGroup(): GrowthGroupId {
  try { const value = localStorage.getItem('educationagent.growth.group'); return GROWTH_GROUPS.find(group => group.id === value)?.id || 'mind-communication'; } catch { return 'mind-communication'; }
}

/** 大类组织学习目的，侧栏组织可操作内容；聊天在工具切换时保持挂载，保留尚未发送的想法。 */
export default function GrowthClassroom() {
  const [groupId, setGroupId] = useState<GrowthGroupId>(initialGroup);
  const [grade, setGrade] = useState(initialGrade);
  const [selected, setSelected] = useState<Partial<Record<GrowthGroupId, string>>>({});
  const [interactiveContext, setInteractiveContext] = useState('');
  const gradeMenu = useRef<HTMLDetailsElement | null>(null);
  const questionArea = useRef<HTMLDivElement | null>(null);
  const group = GROWTH_GROUPS.find(item => item.id === groupId)!;
  const items = ITEMS[groupId];
  const item = items.find(value => value.id === selected[groupId]) || items[0];
  const hasTool = !!item.domain || !!item.practice || item.id === 'english' || item.id === 'code';
  const chooseGroup = (value: GrowthGroupId) => { setGroupId(value); setInteractiveContext(''); try { localStorage.setItem('educationagent.growth.group', value); } catch { /* 保存偏好失败不阻断课堂 */ } };
  const chooseGrade = (value: number) => { setGrade(value); if (gradeMenu.current) gradeMenu.current.open = false; try { localStorage.setItem('educationagent.growth.grade', String(value)); } catch { /* 同上 */ } };
  const chooseItem = (value: ClassroomItem) => { setSelected(previous => ({ ...previous, [groupId]: value.id })); setInteractiveContext(''); };
  const context = interactiveContext || (hasTool ? `当前成长课堂：${group.label}；内容：${item.label}。` : '');

  return <section className="growth-classroom" aria-label="成长课堂">
    <header className="gcl-header"><div><span className="gcl-eyebrow">晓陶学伴 · 教学做合一</span><h1>每一种成长，都有自己的课堂。</h1><p>先听一听，再想一想，亲手试一试。把生活里的好奇，变成自己的收获。</p></div>
      <details className="gcl-grade-picker" ref={gradeMenu} onKeyDown={event => { if (event.key === 'Escape' && gradeMenu.current) { gradeMenu.current.open = false; gradeMenu.current.querySelector('summary')?.focus(); } }}><summary><span className="gcl-grade-mark" aria-hidden="true">学</span><span><small>我的年级</small><strong>{GROWTH_GRADE_LABELS[grade - 1]}</strong></span><span aria-hidden="true">⌄</span></summary><div className="gcl-grade-popover" aria-label="选择年级">{[{ label: '小学', start: 0, end: 6 }, { label: '初中', start: 6, end: 9 }, { label: '高中', start: 9, end: 12 }].map(stage => <div key={stage.label}><h2>{stage.label}</h2><div>{GROWTH_GRADE_LABELS.slice(stage.start, stage.end).map((label, index) => <button key={label} aria-pressed={grade === index + stage.start + 1} onClick={() => chooseGrade(index + stage.start + 1)}>{label}{grade === index + stage.start + 1 && <span aria-hidden="true">✓</span>}</button>)}</div></div>)}</div></details>
    </header>
    <nav className="gcl-groups" aria-label="成长大类">{GROWTH_GROUPS.map(value => <button key={value.id} aria-pressed={groupId === value.id} className={groupId === value.id ? 'active' : ''} onClick={() => chooseGroup(value.id)}><span className="gcl-group-mark" aria-hidden="true">{value.mark}</span><span><strong>{value.label}</strong><small>{value.description}</small></span><span className="gcl-group-arrow" aria-hidden="true">↗</span></button>)}</nav>
    <div className="gcl-layout"><aside className="gcl-sidebar"><div className="gcl-sidebar-heading"><span>{group.mark}</span><div><h2>{group.label}</h2><p>选一条你感兴趣的小路</p></div></div><nav aria-label={`${group.label}内容`}>{items.map(value => <button key={value.id} className={item.id === value.id ? 'active' : ''} aria-current={item.id === value.id ? 'page' : undefined} onClick={() => chooseItem(value)}><span className="gcl-item-mark" aria-hidden="true">{value.mark}</span><span><strong>{value.label}</strong><small>{value.description}</small></span>{item.id === value.id && <span className="gcl-item-dot" aria-hidden="true" />}</button>)}</nav><div className="gcl-sidebar-note"><span aria-hidden="true">✦</span><p>不用急着知道答案。观察、提问和尝试，本身就是成长。</p></div></aside>
      <div className="gcl-main">
        {hasTool && <section className="gcl-tool" aria-label={item.label}><header className="gcl-tool-heading"><div><span>{group.label}</span><h2>{item.label}</h2><p>{item.description}</p></div><button onClick={() => { questionArea.current?.scrollIntoView({ behavior: 'smooth', block: 'start' }); questionArea.current?.querySelector('textarea')?.focus({ preventScroll: true }); }}>问问小陶 <span aria-hidden="true">↘</span></button></header><div className="gcl-embedded-tool">
          {item.domain && <CourseKnowledgeView key={item.domain} domainId={item.domain} learnerId={undefined} />}
          {item.id === 'english' && <PublicEnglishPanel />}
          {item.id === 'code' && <CodeVisualization onContextChange={setInteractiveContext} />}
          {item.practice && <PracticeActivities key={item.practice} mode={item.practice} onContextChange={setInteractiveContext} />}
        </div></section>}
        <div className="gcl-question" ref={questionArea}><GrowthCompanion key={`${groupId}:${grade}`} group={groupId} category={item.category} grade={grade} interactiveContext={context} embedded /></div>
      </div>
    </div>
  </section>;
}
