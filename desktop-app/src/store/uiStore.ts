/**
 * 应用 UI 布局状态仓库（Zustand + persist）
 * 持久化到 localStorage（key: agent-nexus-ui），刷新/重启不丢失。
 * 内容：
 *   navMode: 左侧导航栏三态（full 全量 / rail 仅图标 / hidden 贴边只剩标签）
 *   navWidth / convWidth: 导航栏 / 会话栏手动伸缩后的宽度
 *   convCollapsed: 聊天会话列表是否折叠（露出左侧小标签）
 *   设置：昵称 / 头像(base64) / 主题色
 */
import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';

/** 主题色 key，与 App.css 中 .app-container[data-theme=xxx] 对应 */
export type ThemeKey = 'cream' | 'indigo' | 'doubao' | 'pink' | 'blue' | 'sage' | 'paleyellow';

/** 主题色板（用于设置面板渲染色块） */
export const THEMES: { key: ThemeKey; label: string; color: string }[] = [
  { key: 'cream', label: '奶油暖调', color: '#FECBCA' },
  { key: 'indigo', label: '经典蓝', color: '#4F46E5' },
  { key: 'doubao', label: '白灰', color: '#9CA3AF' },
  { key: 'pink', label: '嫩粉', color: '#F7D7E1' },
  { key: 'blue', label: '雾蓝', color: '#91B8D8' },
  { key: 'sage', label: '抹茶', color: '#A8C9A3' },
  { key: 'paleyellow', label: '奶油黄', color: '#FFF2C8' },
];

/** 导航栏三态 */
export type NavMode = 'full' | 'rail' | 'hidden';

/** 导航栏 / 会话栏宽度边界 */
export const NAV_WIDTH_RANGE = { min: 140, max: 320, def: 244 };
export const CONV_WIDTH_RANGE = { min: 180, max: 420, def: 256 };

interface UiState {
  navMode: NavMode;
  navWidth: number;
  convWidth: number;
  convCollapsed: boolean;
  nickname: string;
  avatar: string; // base64 data URL，空串表示未设置
  theme: ThemeKey;

  /**
   * 教师登录会话（AI 普惠教育 · 教师助教工作台）。
   *
   * 只存"登录凭证 + 显示用的身份信息"，不存密码。
   * ⚠️ 前端隐藏导航**不是安全边界**——教师接口在后端按 token 校验（见 EduAuthInterceptor），
   * 这里的作用只是"学生账号看不到教师工作台"这个产品行为。
   */
  eduTeacherToken: string;
  eduTeacherUsername: string;
  eduTeacherClassId: string;
  eduTeacherClassName: string;

  /** 登录成功后写入会话（由教师工作台的登录表单调用）。 */
  setEduTeacher(p: { token: string; username: string; classId: string; className: string }): void;
  /** 退出登录：清空会话，导航里的教师工作台同时消失。 */
  clearEduTeacher(): void;

  setNavMode(mode: NavMode): void;
  /** full ↔ rail 切换 */
  toggleNavMode(): void;
  /** 隐藏到贴边标签 */
  hideNav(): void;
  /** 从贴边标签恢复 */
  showNav(): void;
  setNavWidth(width: number): void;
  setConvWidth(width: number): void;
  toggleConv(): void;
  setConvCollapsed(collapsed: boolean): void;
  setNickname(name: string): void;
  setAvatar(dataUrl: string): void;
  setTheme(theme: ThemeKey): void;
}

/** 校验持久化值：主题只允许 4 个预设，宽度限定在合法区间 */
function sanitize(p: unknown): Partial<UiState> {
  const raw = (p ?? {}) as Record<string, unknown>;
  const out: Record<string, unknown> = {};
  const theme = raw.theme;
  out.theme = THEMES.some((t) => t.key === theme) ? theme : 'cream';
  const navMode = raw.navMode;
  out.navMode = navMode === 'rail' || navMode === 'hidden' ? navMode : 'full';
  out.convCollapsed = raw.convCollapsed === true;
  out.nickname = typeof raw.nickname === 'string' ? raw.nickname : '';
  out.avatar = typeof raw.avatar === 'string' ? raw.avatar : '';
  out.navWidth = clampNum(raw.navWidth, NAV_WIDTH_RANGE.min, NAV_WIDTH_RANGE.max, NAV_WIDTH_RANGE.def);
  out.convWidth = clampNum(raw.convWidth, CONV_WIDTH_RANGE.min, CONV_WIDTH_RANGE.max, CONV_WIDTH_RANGE.def);
  out.eduTeacherToken = typeof raw.eduTeacherToken === 'string' ? raw.eduTeacherToken : '';
  out.eduTeacherUsername = typeof raw.eduTeacherUsername === 'string' ? raw.eduTeacherUsername : '';
  out.eduTeacherClassId = typeof raw.eduTeacherClassId === 'string' ? raw.eduTeacherClassId : '';
  out.eduTeacherClassName = typeof raw.eduTeacherClassName === 'string' ? raw.eduTeacherClassName : '';
  return out as Partial<UiState>;
}

function clampNum(v: unknown, min: number, max: number, def: number): number {
  const n = typeof v === 'number' ? v : def;
  return Math.min(max, Math.max(min, n));
}

export const useUiStore = create<UiState>()(
  persist(
    (set) => ({
      navMode: 'full',
      navWidth: NAV_WIDTH_RANGE.def,
      convWidth: CONV_WIDTH_RANGE.def,
      convCollapsed: false,
      nickname: '',
      avatar: '',
      theme: 'cream',
      eduTeacherToken: '',
      eduTeacherUsername: '',
      eduTeacherClassId: '',
      eduTeacherClassName: '',

      setEduTeacher: (p) => set({
        eduTeacherToken: p.token || '',
        eduTeacherUsername: p.username || '',
        eduTeacherClassId: p.classId || '',
        eduTeacherClassName: p.className || '',
      }),
      clearEduTeacher: () => set({
        eduTeacherToken: '',
        eduTeacherUsername: '',
        eduTeacherClassId: '',
        eduTeacherClassName: '',
      }),

      setNavMode: (mode) => set({ navMode: mode }),
      toggleNavMode: () =>
        set((s) => ({ navMode: s.navMode === 'full' ? 'rail' : 'full' })),
      hideNav: () => set({ navMode: 'hidden' }),
      showNav: () => set({ navMode: 'rail' }),
      setNavWidth: (width) => set({ navWidth: clampNum(width, NAV_WIDTH_RANGE.min, NAV_WIDTH_RANGE.max, NAV_WIDTH_RANGE.def) }),
      setConvWidth: (width) => set({ convWidth: clampNum(width, CONV_WIDTH_RANGE.min, CONV_WIDTH_RANGE.max, CONV_WIDTH_RANGE.def) }),
      toggleConv: () => set((s) => ({ convCollapsed: !s.convCollapsed })),
      setConvCollapsed: (collapsed) => set({ convCollapsed: collapsed }),
      setNickname: (name) => set({ nickname: name }),
      setAvatar: (dataUrl) => set({ avatar: dataUrl }),
      setTheme: (theme) => set({ theme }),
    }),
    {
      name: 'agent-nexus-ui',
      storage: createJSONStorage(() => localStorage),
      merge: (persisted, current) => ({ ...current, ...sanitize(persisted) }),
      partialize: (s) => ({
        navMode: s.navMode,
        navWidth: s.navWidth,
        convWidth: s.convWidth,
        convCollapsed: s.convCollapsed,
        nickname: s.nickname,
        avatar: s.avatar,
        theme: s.theme,
        eduTeacherToken: s.eduTeacherToken,
        eduTeacherUsername: s.eduTeacherUsername,
        eduTeacherClassId: s.eduTeacherClassId,
        eduTeacherClassName: s.eduTeacherClassName,
      }),
    },
  ),
);
