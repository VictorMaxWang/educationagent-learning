import { useCallback, useEffect, useRef, useState } from 'react';
import ChatWindow from './chat/ChatWindow';
import { useChatStore } from './chat/chatStore';
import SettingsView from './components/SettingsView';
import TravelView from './travel/TravelView';
import MapView from './travel/MapView';
import HealthView from './health/HealthView';
import PetView from './pet/PetView';
import LifeView from './life/LifeView';
import { NAV_WIDTH_RANGE, useUiStore } from './store/uiStore';
import { useResize } from './utils/useResize';
import QuizMistakeView from './study/QuizMistakeView';
import KnowledgeCenterView from './study/KnowledgeCenterView';
import FileConvertView from './tools/FileConvertView';
import EnglishView from './study/EnglishView';
import PublicEnglishPanel from './components/PublicEnglishPanel';
import CourseKnowledgeView from './study/CourseKnowledgeView';
import TeacherWorkbenchView from './edu/TeacherWorkbenchView';
import StudentReportView from './edu/StudentReportView';
import StudentHomeworkView from './edu/StudentHomeworkView';
import MaimemoInvoice from './tools/MaimemoInvoice';
import WatermarkView from './tools/WatermarkView';
import LawRagView from './tools/LawRagView';
import CodeRunnerView from './code/CodeRunnerView';
import KittyMusicPlayer from './components/KittyMusicPlayer';
import RouterEvalView from './ops/RouterEvalView';
import MetricsView from './ops/MetricsView';
import MemoryMapView from './ops/MemoryMapView';
import ClassroomView from './classroom/ClassroomView';
import VoiceOrb from './components/VoiceOrb';
import LocalMusicPanel from './components/LocalMusicPanel';
import BrowserCodePlayground from './components/BrowserCodePlayground';
import TeamResearchLabs from './research/TeamResearchLabs';
import { audioCoordinator, installMediaFocusBridge } from './classroom/AudioCoordinator';
import './App.css';

// 公网课堂按已核准功能列“更多工具”；本机完整侧栏保留，不以隐藏导航替代服务端权限。
const PUBLIC_CLASSROOM = import.meta.env.VITE_PUBLIC_CLASSROOM === 'true';
const PUBLIC_TOOL_KEYS = new Set(['quiz', 'english', 'tongshi', 'psychology', 'lifesafety', 'sexedu', 'watermark', 'browsercode', 'researchlabs']);

// 侧栏板块图标（用户提供的 PNG），目前 7 张都是透明底单色 → 一律 mono: true
// mono=true 的单色图标走 CSS mask，由 currentColor 上色，7 套主题 / hover / 选中态自动跟随；
// 直接用 <img> 的话在深色侧栏（默认 #1A1D2E）上会看不见。
// mono=false 是留给彩色图标的：那种图套 mask 会被压成纯色剪影，只能当 <img> 用。
import icoChat from './assets/nav-icons/chat.png';
import icoStudy from './assets/nav-icons/study.png';
import icoTools from './assets/nav-icons/tools.png';
import icoTravel from './assets/nav-icons/travel.png';
import icoHealth from './assets/nav-icons/health.png';
import icoLife from './assets/nav-icons/life.png';
import icoOps from './assets/nav-icons/ops.png';

// 生成/获取桌面端用户 ID（localStorage 持久化，用于后端对话记忆/会话隔离）
function getDesktopUserId() {
  let uid = localStorage.getItem('desktopUserId');
  if (!uid) {
    uid = 'user_' + Math.random().toString(36).slice(2, 10);
    localStorage.setItem('desktopUserId', uid);
  }
  return uid;
}

/** 会话相对时间：今天显示 HH:mm，昨天"昨天"，更早"X月X日" */
function formatSessionTime(ts) {
  if (!ts) return '';
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

// 英语学习子菜单（词库生成 + 查生词 已并入「单词学习」）
const ENGLISH_ITEMS = [
  ['review', '单词学习'],
  ['listening', '听力训练'],
  ['speaking', '口语练习'],
  ['grammar', '语法专项'],
];

// 萌宠日常子功能（原"宠物记录"拆分为 3 个：档案 / 健康 / 日常）
const PET_ITEMS = [
  { key: 'pet', group: 'pet', module: 'profile', label: '宠物档案' },
  { key: 'pet', group: 'pet', module: 'care', label: '日常养护' },
];

// 健康养护子功能（2 个聚合页：个人健康档案中心 / 饮食诊疗服务中心）
const HEALTH_ITEMS = [
  { key: 'health', group: 'health', module: 'personal', label: '健康档案' },
  { key: 'health', group: 'health', module: 'diet', label: '饮食诊疗' },
];

// 旅行规划子功能（旅游计划 / 按天穿搭 / 酒店查询 / 周边餐馆查询 / 实时地图）
const TRAVEL_ITEMS = [
  { key: 'travel', group: 'travel', module: 'plan', label: '旅游计划' },
  { key: 'travel', group: 'travel', module: 'outfit', label: '按天穿搭' },
  { key: 'travel', group: 'travel', module: 'hotel', label: '酒店查询' },
  { key: 'travel', group: 'travel', module: 'food', label: '周边餐馆查询' },
  { key: 'map', label: '实时地图' },
];

// 导航栏风琴式分组：大标题直接就是这些词，组头带表情、子项不带
// 侧栏图标渲染：mono 走 mask 继承 currentColor，彩色走 <img>，未配图的板块退回 emoji
function NavIcon({ ico }) {
  if (ico && typeof ico === 'object') {
    return ico.mono ? (
      <span
        className="nav-ico nav-ico-mask"
        style={{ '--nav-ico-src': `url(${ico.src})` }}
        aria-hidden="true"
      />
    ) : (
      <img className="nav-ico nav-ico-img" src={ico.src} alt="" aria-hidden="true" />
    );
  }
  return <span className="nav-ico">{ico}</span>;
}

const NAV_GROUPS = [
  { key: 'classroom', ico: '禾', title: 'educationagent 学习课堂', direct: true },
  {
    key: 'chat',
    ico: { src: icoChat, mono: true },
    title: '智能对话',
    direct: true, // 无子项，点击直接打开右侧对话框
  },
  {
    key: 'study',
    ico: { src: icoStudy, mono: true },
    title: '在线学习',
    items: [
      { key: 'quiz', label: '刷题练习' },
      { key: 'knowledge', label: '知识中心' },
      { key: 'code', label: '代码练习' },
      { key: 'english', label: '英语学习', children: true },
      { key: 'tongshi', label: '通识课程' },
      { key: 'psychology', label: '心理学课程' },
    ],
  },
  {
    // AI 普惠教育（2026-10 新增）：把"人工智能 + 教育"的定位直接摆进导航，
    // 而不是把两个新知识库塞进"在线学习"里当第 7、8 个子项——评委要看的就是这一块。
    // 图标先用 emoji（nav-icons/ 里没有对应素材，NavIcon 会自动退回 emoji，与「萌宠日常」同款）
    key: 'edu',
    ico: '🎓',
    title: 'AI 普惠教育',
    items: [
      { key: 'lifesafety', label: '生命安全与健康教育' },
      { key: 'sexedu', label: '青春期性教育' },
      { key: 'homework', label: '班级作业' },
      { key: 'myreport', label: '我的学习周报' },
      { key: 'teacher', label: '教师工作台' },
    ],
  },
  {
    key: 'tools',
    ico: { src: icoTools, mono: true },
    title: '办公工具',
    items: [
      { key: 'convert', label: '格式转换' },
      { key: 'invoice', label: '发票识别' },
      { key: 'watermark', label: '图片水印' },
      { key: 'lawrag', label: '法律检索' },
    ],
  },
  {
    key: 'travel',
    ico: { src: icoTravel, mono: true },
    title: '旅行规划',
    items: TRAVEL_ITEMS,
  },
  {
    key: 'health',
    ico: { src: icoHealth, mono: true },
    title: '健康养护',
    items: HEALTH_ITEMS,
  },
  {
    key: 'pet',
    ico: '🐾',
    title: '萌宠日常',
    items: PET_ITEMS,
  },
  {
    key: 'life',
    ico: { src: icoLife, mono: true },
    title: '生活企划',
    items: [
      { key: 'xhs', label: '小红书' },
    ],
  },
  {
    key: 'ops',
    ico: { src: icoOps, mono: true },
    title: '运维观测',
    items: [
      { key: 'routereval', label: '路由评测' },
      { key: 'metrics', label: '指标监控' },
      { key: 'memorymap', label: '记忆脑图' },
    ],
  },
];

// 视图 → 所属分组
const VIEW_GROUP = {
  classroom: 'classroom',
  browsercode: 'study', researchlabs: 'study',
  chat: 'chat',
  quiz: 'study', knowledge: 'study', english: 'study', code: 'study',
  tongshi: 'study', psychology: 'study',
  lifesafety: 'edu', sexedu: 'edu', teacher: 'edu', myreport: 'edu', homework: 'edu',
  convert: 'tools', invoice: 'tools', watermark: 'tools', lawrag: 'tools',
  travel: 'travel', map: 'travel',
  health: 'health',
  pet: 'pet',
  xhs: 'life',
  routereval: 'ops', metrics: 'ops', memorymap: 'ops',
};

function App() {
  const [activeView, setActiveViewState] = useState('classroom');
  const activeViewRef = useRef(activeView); activeViewRef.current = activeView;
  const [toolsOpen, setToolsOpen] = useState(false);
  const [musicOpen, setMusicOpen] = useState(false);
  const [originalMusicRequested, setOriginalMusicRequested] = useState(false);
  const setActiveView = useCallback((next) => {
    if (PUBLIC_CLASSROOM && next !== 'classroom' && !PUBLIC_TOOL_KEYS.has(next)) return;
    if (next === activeViewRef.current) return;
    if (!window.dispatchEvent(new CustomEvent('educationagent:before-navigation', { cancelable: true }))) return;
    audioCoordinator.stopAll(); activeViewRef.current = next; setActiveViewState(next);
  }, []);
  const [classroomNavOpen, setClassroomNavOpen] = useState(false);
  const musicMount = useRef(null);
  const attachMusic = useCallback((node) => {
    // 卸载前暂停原播放器，防止脱离 DOM 的音频继续与课堂旁白重叠；不改音乐偏好。
    if (!node) musicMount.current?.querySelectorAll('audio').forEach(audio => audio.pause());
    musicMount.current = node;
  }, []);
  const [englishOpen, setEnglishOpen] = useState(false); // 英语学习子目录展开
  const [englishModule, setEnglishModule] = useState('review'); // 英语学习当前模块
  const [petModule, setPetModule] = useState('profile'); // 萌宠日常当前子功能（宠物档案/日常养护）
  const [healthModule, setHealthModule] = useState('personal'); // 健康养护当前子功能（健康档案/饮食诊疗）
  const [travelModule, setTravelModule] = useState('plan'); // 旅行规划当前子功能（旅游计划/按天穿搭）
  const [openGroups, setOpenGroups] = useState(['chat']); // 同时可展开多个分组
  const [settingsOpen, setSettingsOpen] = useState(false); // 设置面板
  useEffect(() => installMediaFocusBridge(document), []);

  // 微信课程深链使用现有应用外壳，其他工具继续使用原导航和用户身份。
  useEffect(() => {
    const openLinkedClassroom = () => {
      if (window.location.hash.startsWith('#/classroom')) setActiveView('classroom');
    };
    window.addEventListener('hashchange', openLinkedClassroom);
    return () => window.removeEventListener('hashchange', openLinkedClassroom);
  }, []);
  useEffect(() => { setClassroomNavOpen(false); }, [activeView]);

  // 布局状态（uiStore，persist 到 localStorage）
  const navMode = useUiStore((s) => s.navMode);
  const navWidth = useUiStore((s) => s.navWidth);
  const theme = useUiStore((s) => s.theme);
  const nickname = useUiStore((s) => s.nickname);
  const avatar = useUiStore((s) => s.avatar);
  const toggleNavMode = useUiStore((s) => s.toggleNavMode);
  const hideNav = useUiStore((s) => s.hideNav);
  const showNav = useUiStore((s) => s.showNav);
  const setNavMode = useUiStore((s) => s.setNavMode);
  const eduTeacherToken = useUiStore((s) => s.eduTeacherToken);

  // 未登录时把「教师工作台」显示为「教师登录」——**不能直接隐藏**：
  // 隐藏了就没有登录入口，形成"要登录才能看到登录页"的死循环。
  // 登录后自动变回「教师工作台」，并在页面里显示当前班级身份。
  //
  // 这只是产品行为，**不是安全边界**：教师接口在后端按 token 校验（EduAuthInterceptor），
  // 就算有人改前端把入口点出来，没有 token 也拿不到任何班级数据。
  const navGroups = NAV_GROUPS.map((g) =>
    g.key === 'edu'
      ? {
          ...g,
          items: (g.items || []).map((it) =>
            it.key === 'teacher'
              ? { ...it, label: eduTeacherToken ? '教师工作台' : '教师登录' }
              : it
          ),
        }
      : g
  );
  const setNavWidth = useUiStore((s) => s.setNavWidth);

  const userId = getDesktopUserId();
  const isRail = navMode === 'rail';
  const isHidden = navMode === 'hidden';

  // 侧栏历史对话（chatStore 会话列表）
  const sessionList = useChatStore((s) => s.sessionList);
  const activeSessionId = useChatStore((s) => s.activeSessionId);
  const createChat = useChatStore((s) => s.createSession);
  const selectChat = useChatStore((s) => s.selectSession);
  const removeChat = useChatStore((s) => s.removeSession);
  const renameChat = useChatStore((s) => s.renameSession);

  // 通用会话由 ChatWindow 首次挂载时加载，学习首页不预先请求无关聊天服务。

  const openChat = (id) => {
    setActiveView('chat');
    selectChat(userId, id);
  };
  const newChat = () => {
    setActiveView('chat');
    createChat(userId);
  };
  const deleteChat = (id) => {
    if (window.confirm('确定删除该会话吗？将同时清除其中全部消息。')) removeChat(userId, id);
  };
  const renameChatHandler = (id) => {
    const current = sessionList.find((s) => s.sessionId === id);
    const title = window.prompt('重命名会话', current?.title ?? '');
    if (title != null && title.trim()) renameChat(userId, id, title.trim());
  };
  /** 侧栏顶部 🎵 → 切换音乐播放器显示/隐藏（KittyMusicPlayer 监听 music-toggle 事件） */
  const toggleMusic = () => {
    if (activeViewRef.current === 'classroom' && !originalMusicRequested) {
      setOriginalMusicRequested(true);
      requestAnimationFrame(() => { if (localStorage.getItem('music_player_hidden') === '1') window.dispatchEvent(new CustomEvent('music-toggle')); });
      return;
    }
    window.dispatchEvent(new CustomEvent('music-toggle'));
  };
  const openMoreTools = () => { audioCoordinator.stopAll(); setToolsOpen(true); };
  const chooseTool = (item) => {
    setActiveView(item.key);
    if (item.group === 'english') setEnglishModule(item.module);
    if (item.group === 'pet') setPetModule(item.module);
    if (item.group === 'health') setHealthModule(item.module);
    if (item.group === 'travel') setTravelModule(item.module);
    setToolsOpen(false);
  };
  const moreGroups = NAV_GROUPS.filter(group => group.key !== 'classroom').map(group => ({
    ...group,
    items: (group.direct ? [{ key: group.key, label: group.title }] : group.items || []).flatMap(item => item.key === 'english'
      ? PUBLIC_CLASSROOM ? [{ key: 'english', label: '英语口语与语法' }]
        : ENGLISH_ITEMS.map(([module, label]) => ({ key: 'english', group: 'english', module, label })) : [item]).filter(item => !PUBLIC_CLASSROOM || PUBLIC_TOOL_KEYS.has(item.key)),
  })).filter(group => group.items.length);
  moreGroups.unshift({ key: 'explore', title: '学习实验', items: [{ key: 'browsercode', label: 'JavaScript 浏览器练习' }, { key: 'researchlabs', label: '团队成果实验室' }] });
  const activeToolLabel = moreGroups.flatMap(group => group.items).find(item => item.key === activeView && (!item.module || item.module === (item.group === 'english' ? englishModule : item.group === 'travel' ? travelModule : item.group === 'health' ? healthModule : petModule)))?.label || '学习工具';

  // 导航栏手动伸缩（仅 full 模式可拖拽）
  const onNavResize = useResize(
    (w) => setNavWidth(w),
    [NAV_WIDTH_RANGE.min, NAV_WIDTH_RANGE.max],
    () => useUiStore.getState().navWidth,
  );

  /** 风琴分组展开/收起（支持多组同时展开） */
  const toggleGroup = (key) => {
    setOpenGroups((prev) => (prev.includes(key) ? prev.filter((k) => k !== key) : [...prev, key]));
  };
  /** 确保某组展开 */
  const openGroup = (key) => {
    setOpenGroups((prev) => (prev.includes(key) ? prev : [...prev, key]));
  };

  // 单个导航项（子项不带表情，纯文字；带 module 的子项会联动切换对应功能组的模块）
  const renderNavItem = (item) => {
    const curModule =
      item.group === 'pet' ? petModule : item.group === 'health' ? healthModule : item.group === 'travel' ? travelModule : null;
    const active = item.module ? activeView === item.key && curModule === item.module : activeView === item.key;
    return (
      <button
        key={item.key + (item.module || '')}
        className={`nav-item${active ? ' active' : ''}`}
        onClick={() => {
          setActiveView(item.key);
          if (item.module) {
            if (item.group === 'pet') setPetModule(item.module);
            else if (item.group === 'health') setHealthModule(item.module);
            else if (item.group === 'travel') setTravelModule(item.module);
          }
        }}
      >
        <span className="nav-label">{item.label}</span>
        {item.children && <span className="nav-arrow">{englishOpen ? '▾' : '▸'}</span>}
      </button>
    );
  };

  // 一个分组的完整风琴（full 模式）
  const renderGroup = (g) => {
    // 直接打开型分组（智能对话）：点击直达右侧对话框，无子项无箭头
    if (g.direct) {
      const active = activeView === g.key;
      return (
        <button
          key={g.key}
          className={`nav-group-header nav-group-direct${active ? ' active' : ''}`}
          onClick={() => setActiveView(g.key)}
          title={g.title}
        >
          <NavIcon ico={g.ico} />
          <span className="nav-label">{g.title}</span>
        </button>
      );
    }

    const open = openGroups.includes(g.key);
    const groupActive = VIEW_GROUP[activeView] === g.key;
    return (
      <div key={g.key} className="nav-group">
        <button
          className={`nav-group-header${groupActive ? ' nav-group-active' : ''}`}
          onClick={() => toggleGroup(g.key)}
          title={g.title}
        >
          <NavIcon ico={g.ico} />
          <span className="nav-label">{g.title}</span>
          <span className="nav-arrow">{open ? '▾' : '▸'}</span>
        </button>

        {open && (
          <div className="nav-group-items">
            {g.items.map((item) => {
              if (item.children) {
                // 英语学习：带二级折叠
                return (
                  <div key={item.key}>
                    <button
                      className={`nav-item${activeView === 'english' ? ' active' : ''}`}
                      onClick={() => {
                        setActiveView('english');
                        setEnglishOpen((v) => !v);
                      }}
                    >
                      <span className="nav-label">英语学习</span>
                      <span className="nav-arrow">{englishOpen ? '▾' : '▸'}</span>
                    </button>
                    {englishOpen && (
                      <div className="nav-sub-items">
                        {ENGLISH_ITEMS.map(([key, label]) => (
                          <button
                            key={key}
                            className={`nav-item nav-sub${activeView === 'english' && englishModule === key ? ' active' : ''}`}
                            onClick={() => {
                              setActiveView('english');
                              setEnglishModule(key);
                            }}
                          >
                            <span className="nav-label">{label}</span>
                          </button>
                        ))}
                      </div>
                    )}
                  </div>
                );
              }
              return renderNavItem(item);
            })}
          </div>
        )}
      </div>
    );
  };

  return (
    <div className={`app-container${activeView === 'classroom' ? ' app-classroom' : ''}${classroomNavOpen ? ' classroom-nav-open' : ''}`} data-theme={theme}>
      {!PUBLIC_CLASSROOM && activeView === 'classroom' && classroomNavOpen && <button className="cr-nav-backdrop" aria-label="关闭工具导航" onClick={() => setClassroomNavOpen(false)} />}
      {/* ===== 左侧导航栏（三态：full / rail / hidden） ===== */}
      {!PUBLIC_CLASSROOM && (isHidden ? (
        // 隐藏态：贴边只剩一个小标签，点击恢复
        <div className="nav-edge-tab" onClick={showNav} title="展开导航">
          <span className="nav-edge-avatar">
            {avatar ? (
              <img src={avatar} alt="头像" />
            ) : (
              <span>{nickname ? nickname[0] : '✦'}</span>
            )}
          </span>
          <span className="nav-edge-ops">
            <button
              onClick={(e) => {
                e.stopPropagation();
                setSettingsOpen(true);
              }}
              title="设置"
            >
              ⚙
            </button>
          </span>
        </div>
      ) : (
        <>
          <aside
            className={`sidebar${isRail ? ' sidebar-rail' : ''}`}
            style={isRail ? undefined : { width: navWidth }}
          >
            {/* 顶部：用户昵称行（头像/昵称 + 音乐 + 设置）+ 折叠/隐藏按钮 */}
            <div className="sidebar-top">
              <div className="sidebar-top-bar">
                <button className="user-chip" onClick={() => setSettingsOpen(true)} title="设置">
                  <span className="user-avatar">
                    {avatar ? (
                      <img src={avatar} alt="头像" />
                    ) : (
                      <span>{nickname ? nickname[0] : '✦'}</span>
                    )}
                  </span>
                  <span className="user-name">{nickname || '未设置昵称'}</span>
                </button>
                <div className="sidebar-top-icons">
                  <button className="sidebar-icon-btn" onClick={toggleMusic} title="音乐播放器">
                    🎵
                  </button>
                  <button className="sidebar-icon-btn" onClick={() => setSettingsOpen(true)} title="设置">
                    ⚙
                  </button>
                </div>
              </div>
              <div className="sidebar-controls">
                <button
                  className="nav-collapse-btn"
                  onClick={toggleNavMode}
                  title={isRail ? '展开导航' : '折叠导航'}
                >
                  {isRail ? '▶' : '◀'}
                </button>
                <button className="nav-hide-btn" onClick={hideNav} title="收缩到底">
                  »
                </button>
              </div>
            </div>

            {/* 导航：6 组风琴式下拉框（可多组同时展开；rail 模式只显示组图标） */}
            <nav className="sidebar-nav">
              {isRail
                ? navGroups.map((g) => (
                    <button
                      key={g.key}
                      className={`nav-item${VIEW_GROUP[activeView] === g.key ? ' active' : ''}`}
                      onClick={() => {
                        setNavMode('full');
                        if (g.direct) setActiveView(g.key);
                        else openGroup(g.key);
                      }}
                      title={g.title}
                    >
                      <NavIcon ico={g.ico} />
                    </button>
                  ))
                : navGroups.map((g) => renderGroup(g))}
            </nav>

            {/* 历史对话：侧栏下半部分，独立滑动（rail 模式隐藏） */}
            {!isRail && (
              <div className="sidebar-history">
                <div className="sidebar-history-head">
                  <span className="sidebar-history-title">历史对话</span>
                  <button className="sidebar-history-new" onClick={newChat} title="新建对话">＋</button>
                </div>
                <div className="sidebar-history-list">
                  {sessionList.length === 0 ? (
                    <div className="sidebar-history-empty">暂无会话，点击 ＋ 新建</div>
                  ) : (
                    sessionList.map((s) => (
                      <div
                        key={s.sessionId}
                        className={`sidebar-history-item${s.sessionId === activeSessionId ? ' active' : ''}`}
                        onClick={() => openChat(s.sessionId)}
                        title={s.title || '新对话'}
                      >
                        <span className="sidebar-history-text">{s.title || '新对话'}</span>
                        <span className="sidebar-history-time">{formatSessionTime(s.updateTime)}</span>
                        {/* hover 操作：重命名 / 删除（点击不冒泡到选中） */}
                        <span className="sidebar-history-ops" onClick={(e) => e.stopPropagation()}>
                          <button className="sidebar-history-op" title="重命名" onClick={() => renameChatHandler(s.sessionId)}>
                            ✎
                          </button>
                          <button className="sidebar-history-op" title="删除" onClick={() => deleteChat(s.sessionId)}>
                            🗑
                          </button>
                        </span>
                      </div>
                    ))
                  )}
                </div>
              </div>
            )}

          </aside>

          {/* 手动伸缩把手（仅 full 模式） */}
          {!isRail && (
            <div className="resize-handle" onMouseDown={onNavResize} title="拖拽调整宽度" />
          )}
        </>
      ))}

      {/* ===== 主内容区 ===== */}
      <main className="main-content">
        {activeView !== 'classroom' && <header className="legacy-tools-bar"><button onClick={() => setActiveView('classroom')}>← 返回学习课堂</button><strong>{activeToolLabel}</strong><div><button onClick={openMoreTools}>更多工具</button><button onClick={() => { audioCoordinator.stopAll(); setSettingsOpen(true); }}>设置与主题</button></div></header>}
        {activeView === 'classroom' && <ClassroomView onOpenNavigation={openMoreTools} />}
        {activeView === 'browsercode' && <BrowserCodePlayground />}
        {activeView === 'researchlabs' && <TeamResearchLabs />}
        {activeView === 'chat' && <ChatWindow userId={userId} />}
        {activeView === 'quiz' && <QuizMistakeView userId={userId} />}
        {activeView === 'knowledge' && <KnowledgeCenterView userId={userId} />}
        {activeView === 'code' && <CodeRunnerView />}
        {activeView === 'english' && (PUBLIC_CLASSROOM ? <PublicEnglishPanel /> : <EnglishView module={englishModule} />)}
        {activeView === 'tongshi' && <CourseKnowledgeView domainId="tongshi" />}
        {activeView === 'psychology' && <CourseKnowledgeView domainId="psychology" />}
        {activeView === 'lifesafety' && <CourseKnowledgeView domainId="lifesafety" />}
        {activeView === 'sexedu' && <CourseKnowledgeView domainId="sexedu" />}
        {activeView === 'teacher' && <TeacherWorkbenchView />}
        {activeView === 'myreport' && <StudentReportView />}
        {activeView === 'homework' && <StudentHomeworkView />}
        {activeView === 'convert' && <FileConvertView />}
        {activeView === 'invoice' && <MaimemoInvoice />}
        {activeView === 'watermark' && <WatermarkView />}
        {activeView === 'lawrag' && <LawRagView />}
        {activeView === 'travel' && <TravelView module={travelModule} />}
        {activeView === 'map' && <MapView />}
        {activeView === 'health' && <HealthView module={healthModule} />}
        {activeView === 'pet' && <PetView userId={userId} module={petModule} />}
        {activeView === 'xhs' && <LifeView />}
        {activeView === 'routereval' && <RouterEvalView />}
        {activeView === 'metrics' && <MetricsView />}
        {activeView === 'memorymap' && <MemoryMapView />}
      </main>

      {/* ===== 设置面板 ===== */}
      {settingsOpen && <SettingsView onClose={() => setSettingsOpen(false)} />}

      {toolsOpen && <div className="more-tools-overlay" onClick={() => setToolsOpen(false)}><section className="more-tools-panel" role="dialog" aria-modal="true" aria-label="更多工具" onClick={event => event.stopPropagation()} onKeyDown={event => { if (event.key === 'Escape') setToolsOpen(false); }}><header><div><span>保留熟悉的工具，学习仍在中央</span><h2>更多工具</h2></div><button aria-label="关闭更多工具" onClick={() => setToolsOpen(false)}>×</button></header><div className="more-tools-content">{moreGroups.map(group => <section key={group.key}><h3>{group.title}</h3><div>{group.items.map(item => <button key={item.key + (item.module || '')} onClick={() => chooseTool(item)}><span>{item.label}</span><i aria-hidden="true">↗</i></button>)}</div></section>)}</div><footer><button onClick={() => { setToolsOpen(false); setSettingsOpen(true); }}>设置 · 昵称 · 主题</button><button onClick={() => { setToolsOpen(false); setMusicOpen(true); }}>音乐与休息 ♪</button>{PUBLIC_CLASSROOM && <small>其他原有工具保留在本机版本。</small>}</footer></section></div>}
      {musicOpen && <LocalMusicPanel onClose={() => setMusicOpen(false)} onOpenOriginal={!PUBLIC_CLASSROOM ? () => { setMusicOpen(false); setOriginalMusicRequested(true); requestAnimationFrame(() => { if (localStorage.getItem('music_player_hidden') === '1') toggleMusic(); }); } : undefined} />}

      {/* 课堂使用自己的授课音频，其他工具页保留原音乐播放器。 */}
      {!PUBLIC_CLASSROOM && (activeView !== 'classroom' || originalMusicRequested) && <div ref={attachMusic}><KittyMusicPlayer /></div>}
      <VoiceOrb suspended={toolsOpen || musicOpen || settingsOpen} />
    </div>
  );
}

export default App;
