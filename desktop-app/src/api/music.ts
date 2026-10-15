/**
 * 网易云音乐 API（NeteaseCloudMusicApi 本地代理，默认 http://localhost:3000）
 * 需先单独启动代理：git clone https://github.com/Binary-Hung/NeteaseCloudMusicApi && npm install && node app.js
 * 打包分发可把 BASE_URL 换成自部署的公网地址。
 */
const MUSIC_BASE = import.meta.env.VITE_MUSIC_API_URL || "http://localhost:3000";

/** 你自己的网易云 cookie（localStorage 持久化），有它才能播 VIP 歌 / 读你的歌单 */
export const getMusicCookie = (): string => localStorage.getItem("netease_cookie") ?? "";
export const setMusicCookie = (c: string): void =>
  localStorage.setItem("netease_cookie", c.trim());

export interface MusicSong {
  id: number;
  name: string;
  ar?: { id: number; name: string }[];
  al?: { picUrl?: string };
}

/** 搜索歌曲（搜索接口歌手字段是 artists，归一化成 ar） */
export const searchMusic = async (keywords: string): Promise<MusicSong[]> => {
  const res = await fetch(`${MUSIC_BASE}/search?keywords=${encodeURIComponent(keywords)}&type=1`);
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const data = await res.json();
  return (data?.result?.songs ?? []).map((s: any) => ({
    id: s.id,
    name: s.name,
    ar: s.ar ?? s.artists ?? [],
    al: s.al ?? s.album,
  }));
};

/** 获取播放地址（有时效性，每次播放前重新获取；realIP 绕过限源，cookie 解锁 VIP） */
export const getMusicUrl = async (id: number): Promise<{ url: string } | null> => {
  const cookie = getMusicCookie();
  const url = `${MUSIC_BASE}/song/url?id=${id}&realIP=116.25.146.177${
    cookie ? `&cookie=${encodeURIComponent(cookie)}` : ""
  }`;
  const res = await fetch(url);
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const data = await res.json();
  return data?.data?.[0] ?? null;
};

/** 获取歌曲详情（封面、歌手） */
export const getSongDetail = async (id: number): Promise<MusicSong | null> => {
  const res = await fetch(`${MUSIC_BASE}/song/detail?ids=${id}`);
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const data = await res.json();
  return data?.songs?.[0] ?? null;
};

/** 获取歌词 */
export const getLyric = async (id: number): Promise<string> => {
  const res = await fetch(`${MUSIC_BASE}/lyric?id=${id}`);
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const data = await res.json();
  return data?.lrc?.lyric ?? "";
};

/** 登录状态（需要 cookie）→ 返回 uid + 昵称；未登录返回 null */
export const getLoginStatus = async (): Promise<{ uid: number; nickname: string } | null> => {
  const cookie = getMusicCookie();
  const url = `${MUSIC_BASE}/login/status${cookie ? `?cookie=${encodeURIComponent(cookie)}` : ""}`;
  const res = await fetch(url);
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const data = await res.json();
  const profile = data?.data?.profile;
  if (profile?.userId) return { uid: profile.userId, nickname: profile.nickname };
  return null;
};

/** 我的歌单列表（需要 cookie + uid） */
export const getUserPlaylists = async (uid: number): Promise<{ id: number; name: string }[]> => {
  const cookie = getMusicCookie();
  const url = `${MUSIC_BASE}/user/playlist?uid=${uid}${
    cookie ? `&cookie=${encodeURIComponent(cookie)}` : ""
  }`;
  const res = await fetch(url);
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const data = await res.json();
  return (data?.playlist ?? []).map((p: any) => ({ id: p.id, name: p.name }));
};

/** 歌单内全部歌曲 */
export const getPlaylistTracks = async (id: number): Promise<MusicSong[]> => {
  const cookie = getMusicCookie();
  const url = `${MUSIC_BASE}/playlist/track/all?id=${id}${
    cookie ? `&cookie=${encodeURIComponent(cookie)}` : ""
  }`;
  const res = await fetch(url);
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const data = await res.json();
  return (data?.songs ?? []).map((s: any) => ({
    id: s.id,
    name: s.name,
    ar: s.ar,
    al: s.al,
  }));
};
