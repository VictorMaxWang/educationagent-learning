import { useCallback, useEffect, useRef, useState } from "react";
import AudioPlayer from "react-h5-audio-player";
import "react-h5-audio-player/lib/styles.css";
import {
  searchMusic, getMusicUrl, getSongDetail, getMusicCookie, setMusicCookie,
  getLoginStatus, getUserPlaylists, getPlaylistTracks, getLyric,
} from "../api/music";
import type { MusicSong } from "../api/music";
import styles from "./KittyMusicPlayer.module.css";

/** 播放器统一歌曲结构 */
interface PvSong {
  key: string; // 网易云 song id
  name: string;
  artist: string;
}

/** 解析 LRC 歌词 → [{time秒, text}] */
function parseLrc(lrc: string): { time: number; text: string }[] {
  const out: { time: number; text: string }[] = [];
  const re = /\[(\d{1,2}):(\d{1,2}(?:\.\d{1,3})?)\]/g;
  for (const line of lrc.split("\n")) {
    let m;
    re.lastIndex = 0;
    const text = line.replace(/\[[^\]]*\]/g, "").trim();
    while ((m = re.exec(line)) !== null) {
      const t = Number(m[1]) * 60 + Number(m[2]);
      out.push({ time: t, text });
    }
  }
  out.sort((a, b) => a.time - b.time);
  return out;
}

/**
 * Hello Kitty 黑胶唱片播放器（左下角悬浮，可拖动）
 * 功能：播放/暂停、上/下一首（在搜索结果队列里循环）、自动连播、进度条点击跳转、时间显示、搜索、可选 cookie
 */
const KittyMusicPlayer = () => {
  const playerRef = useRef<AudioPlayer>(null);
  const [playing, setPlaying] = useState(false);
  const [currentSong, setCurrentSong] = useState<PvSong | null>(null);
  const [audioSrc, setAudioSrc] = useState("");
  const [queue, setQueue] = useState<PvSong[]>([]); // 播放队列（来自上次搜索）
  const [results, setResults] = useState<PvSong[]>([]); // 搜索下拉显示
  const [idx, setIdx] = useState(0);
  const [percent, setPercent] = useState(0);
  const [curTime, setCurTime] = useState(0);
  const [duration, setDuration] = useState(0);
  const [lyrics, setLyrics] = useState<{ time: number; text: string }[]>([]);
  const [wantPlay, setWantPlay] = useState(false);
  const [query, setQuery] = useState("");
  const [mode, setMode] = useState<"search" | "playlist">("search");
  const [playlists, setPlaylists] = useState<{ id: number; name: string }[]>([]);
  const [nickname, setNickname] = useState("");
  const [cookieOpen, setCookieOpen] = useState(false);
  const [cookieInput, setCookieInput] = useState(getMusicCookie());
  const [hidden, setHidden] = useState(() => localStorage.getItem("music_player_hidden") === "1");
  // 圆形旋转播放状态（卡片 ↔ 圆形黑胶，可切换，记忆到 localStorage）
  const [circular, setCircular] = useState(() => localStorage.getItem("music_player_circular") === "1");

  // 侧栏顶部 🎵 按钮切换显示/隐藏（App.jsx dispatch music-toggle 事件）
  useEffect(() => {
    const handler = () => {
      setHidden((h) => {
        const next = !h;
        localStorage.setItem("music_player_hidden", next ? "1" : "0");
        return next;
      });
    };
    window.addEventListener("music-toggle", handler);
    return () => window.removeEventListener("music-toggle", handler);
  }, []);

  // 播放器位置（可拖动，存 localStorage）
  const [pos, setPos] = useState(() => {
    const saved = localStorage.getItem("music_player_pos");
    if (saved) {
      try {
        const p = JSON.parse(saved);
        if (typeof p.x === "number" && typeof p.y === "number") return { x: p.x, y: p.y };
      } catch { /* ignore */ }
    }
    const h = typeof window !== "undefined" ? window.innerHeight : 800;
    return { x: 258, y: h - 240 }; // 258 = 264 向左 6px
  });
  const posRef = useRef(pos);
  useEffect(() => {
    posRef.current = pos;
  }, [pos]);

  // 队列 / 索引 ref（next/prev 里读最新值）
  const queueRef = useRef<PvSong[]>([]);
  useEffect(() => {
    queueRef.current = queue;
  }, [queue]);
  const idxRef = useRef(idx);
  useEffect(() => {
    idxRef.current = idx;
  }, [idx]);

  /** 加载并（可选）自动播放 */
  const loadSong = useCallback(async (song: PvSong, autoplay = false) => {
    try {
      let name = song.name;
      let artist = song.artist;
      if (!name) {
        const d: MusicSong | null = await getSongDetail(Number(song.key));
        name = d?.name ?? "未知";
        artist = d?.ar?.[0]?.name ?? "未知";
      }
      const urlData = await getMusicUrl(Number(song.key));
      const url = urlData?.url ?? "";
      if (!url) {
        setCurrentSong(null);
        setAudioSrc("");
        setPercent(0);
        setLyrics([]);
        return;
      }
      setCurrentSong({ key: song.key, name, artist });
      setAudioSrc(url);
      setPercent(0);
      setCurTime(0);
      setDuration(0);
      setLyrics([]);
      setWantPlay(autoplay);
      // 异步加载歌词
      getLyric(Number(song.key))
        .then((lrc) => setLyrics(parseLrc(lrc)))
        .catch(() => setLyrics([]));
    } catch (e) {
      console.error("🎵 加载歌曲失败（请确认 NeteaseCloudMusicApi 已启动）:", e);
      setCurrentSong(null);
      setAudioSrc("");
      setPercent(0);
    }
  }, []);

  // 默认加载颜人中《嗜好》（不自动播放）
  useEffect(() => {
    loadSong({ key: "1391891631", name: "", artist: "" }, false);
  }, [loadSong]);

  // src 就绪后按 wantPlay 自动播放
  useEffect(() => {
    if (!audioSrc || !wantPlay) return;
    const audio = playerRef.current?.audio.current;
    if (audio) {
      const p = audio.play();
      if (p) p.catch(() => {});
    }
  }, [audioSrc, wantPlay]);

  // 播放状态 / 进度 / 结束监听
  useEffect(() => {
    const audio = playerRef.current?.audio.current;
    if (!audio) return;
    const playFn = () => setPlaying(true);
    const pauseFn = () => setPlaying(false);
    const timeFn = () => {
      setCurTime(audio.currentTime);
      setDuration(audio.duration || 0);
      const p = audio.duration ? audio.currentTime / audio.duration : 0;
      setPercent(Number.isNaN(p) ? 0 : p);
    };
    const endedFn = () => next();
    audio.addEventListener("play", playFn);
    audio.addEventListener("pause", pauseFn);
    audio.addEventListener("timeupdate", timeFn);
    audio.addEventListener("loadedmetadata", timeFn);
    audio.addEventListener("ended", endedFn);
    return () => {
      audio.removeEventListener("play", playFn);
      audio.removeEventListener("pause", pauseFn);
      audio.removeEventListener("timeupdate", timeFn);
      audio.removeEventListener("loadedmetadata", timeFn);
      audio.removeEventListener("ended", endedFn);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [audioSrc]);

  const togglePlay = () => {
    const audio = playerRef.current?.audio.current;
    if (!audio) return;
    if (playing) audio.pause();
    else {
      const p = audio.play();
      if (p) p.catch(() => {});
    }
  };

  const next = useCallback(() => {
    const q = queueRef.current;
    if (!q.length) return;
    const n = (idxRef.current + 1) % q.length;
    setIdx(n);
    loadSong(q[n], true);
  }, [loadSong]);

  const prev = useCallback(() => {
    const q = queueRef.current;
    if (!q.length) return;
    const n = (idxRef.current - 1 + q.length) % q.length;
    setIdx(n);
    loadSong(q[n], true);
  }, [loadSong]);

  const handleSearch = async () => {
    if (!query.trim()) return;
    try {
      const list: MusicSong[] = await searchMusic(query.trim());
      const songs: PvSong[] = list.map((s) => ({
        key: String(s.id),
        name: s.name,
        artist: s.ar?.[0]?.name ?? "未知",
      }));
      setQueue(songs);
      setResults(songs);
      setIdx(0);
    } catch {
      setResults([]);
      setQueue([]);
    }
  };

  const playSong = (s: PvSong, i: number) => {
    setIdx(i);
    setResults([]);
    setQuery("");
    loadSong(s, true);
  };

  // 点击进度条跳转
  const seek = (e: React.MouseEvent<HTMLDivElement>) => {
    const audio = playerRef.current?.audio.current;
    if (!audio || !duration) return;
    const rect = e.currentTarget.getBoundingClientRect();
    const ratio = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
    audio.currentTime = ratio * duration;
    setPercent(ratio);
  };

  const fmt = (s: number) => {
    if (!s || Number.isNaN(s)) return "0:00";
    const m = Math.floor(s / 60);
    const sec = Math.floor(s % 60);
    return `${m}:${String(sec).padStart(2, "0")}`;
  };

  /** 加载我的歌单（需要已登录 cookie） */
  const loadPlaylists = async () => {
    try {
      const me = await getLoginStatus();
      if (!me) {
        setNickname("");
        setPlaylists([]);
        return;
      }
      setNickname(me.nickname);
      setPlaylists(await getUserPlaylists(me.uid));
    } catch {
      setNickname("");
      setPlaylists([]);
    }
  };

  /** 播放某个歌单：加载全部歌曲进队列，播第一首 */
  const playPlaylist = async (id: number) => {
    try {
      const tracks = await getPlaylistTracks(id);
      const songs: PvSong[] = tracks.map((t) => ({
        key: String(t.id),
        name: t.name,
        artist: t.ar?.[0]?.name ?? "未知",
      }));
      if (!songs.length) return;
      setQueue(songs);
      setIdx(0);
      setResults([]);
      loadSong(songs[0], true);
    } catch (e) {
      console.error("🎵 加载歌单失败:", e);
    }
  };

  const saveCookie = () => {
    setMusicCookie(cookieInput);
    setCookieOpen(false);
    if (mode === "playlist") loadPlaylists();
    else if (currentSong) loadSong(currentSong, false);
  };

  // 拖动定位（按钮/输入框上不触发）
  const onMouseDown = (e: React.MouseEvent) => {
    if ((e.target as HTMLElement).closest("button, input, a")) return;
    e.preventDefault();
    const startX = e.clientX;
    const startY = e.clientY;
    const origX = posRef.current.x;
    const origY = posRef.current.y;
    const move = (ev: MouseEvent) => {
      const x = Math.max(0, Math.min(window.innerWidth - 80, origX + ev.clientX - startX));
      const y = Math.max(0, Math.min(window.innerHeight - 40, origY + ev.clientY - startY));
      setPos({ x, y });
    };
    const up = () => {
      window.removeEventListener("mousemove", move);
      window.removeEventListener("mouseup", up);
      localStorage.setItem("music_player_pos", JSON.stringify(posRef.current));
    };
    window.addEventListener("mousemove", move);
    window.addEventListener("mouseup", up);
  };

  // 当前歌词句（卡片内显示两句：当前 + 下一句）
  let lyricIndex = -1;
  for (let i = 0; i < lyrics.length; i++) {
    if (lyrics[i].time <= curTime) lyricIndex = i;
    else break;
  }
  const curLyric = lyricIndex >= 0 ? lyrics[lyricIndex].text : lyrics[0]?.text ?? "";
  const nextLyric = lyricIndex >= 0 && lyricIndex + 1 < lyrics.length ? lyrics[lyricIndex + 1].text : "";

  return (
    <>
      {/* 始终挂载；隐藏用 opacity+pointer-events（不用 display:none，否则音频元素无法播放） */}
      <div
        className={styles.wrapper}
        style={{
          left: pos.x,
          top: pos.y,
          opacity: hidden ? 0 : 1,
          pointerEvents: hidden ? "none" : "auto",
        }}
        onMouseDown={onMouseDown}
      >
        <div className={styles.audioHidden}>
          <AudioPlayer ref={playerRef} src={audioSrc} autoPlay={false} />
        </div>

      {circular ? (
        <div
          className={`${styles.circleDisk} ${playing ? styles.rotate : ""}`}
          onClick={togglePlay}
          onDoubleClick={() => { setCircular(false); localStorage.setItem("music_player_circular", "0"); }}
          title={playing ? "暂停（双击展开面板）" : "播放（双击展开面板）"}
        >
          <img src="/assets/kitty_record.jpg" alt="黑胶唱片" className={styles.circleDiskImg} />
        </div>
      ) : (
        <>
          <button
            className={styles.collapseBtn}
            onClick={() => { setCircular(true); localStorage.setItem("music_player_circular", "1"); }}
            title="转为圆形旋转播放"
          >⭕</button>
          <button
            className={styles.hideBtn}
            onClick={() => { setHidden(true); localStorage.setItem("music_player_hidden", "1"); }}
            title="隐藏播放器"
          >✕</button>
          <div className={styles.playerCard}>
        <div className={styles.mainRow}>
          <div className={styles.diskBox}>
            <div
              className={`${styles.disk} ${playing ? styles.rotate : ""}`}
              onClick={togglePlay}
              title={playing ? "暂停" : "播放"}
            >
              <img src="/assets/kitty_record.jpg" alt="黑胶唱片" className={styles.diskImg} />
            </div>
          </div>

          <div className={styles.infoCol}>
            <div className={styles.songInfo} title={currentSong ? `${currentSong.name} - ${currentSong.artist}` : ""}>
              {currentSong
                ? `${currentSong.name} - ${currentSong.artist}`
                : "🎵 搜索歌曲播放"}
            </div>

            {/* 卡片内两句歌词：当前句 + 下一句 */}
            <div className={styles.lyricMini}>
              <div className={`${styles.lyricMiniLine} ${styles.lyricMiniCurrent}`}>
                {curLyric || "♪ 暂无歌词"}
              </div>
              <div className={`${styles.lyricMiniLine} ${styles.lyricMiniNext}`}>
                {nextLyric}
              </div>
            </div>

            <div className={styles.progressWrap}>
              <span className={styles.timeLabel}>{fmt(curTime)}</span>
              <div className={styles.progressBar} onClick={seek}>
                <div className={styles.progressFill} style={{ width: `${percent * 100}%` }} />
              </div>
              <span className={styles.timeLabel}>{fmt(duration)}</span>
            </div>

            <div className={styles.controlRow}>
              <button className={styles.btnPrev} onClick={prev} title="上一首">⏮</button>
              <button className={styles.btnPlay} onClick={togglePlay}>{playing ? "⏸" : "▶"}</button>
              <button className={styles.btnNext} onClick={next} title="下一首">⏭</button>
            </div>
          </div>
        </div>

        <div className={styles.modeRow}>
          <button
            className={`${styles.modeBtn} ${mode === "search" ? styles.modeActive : ""}`}
            onClick={() => setMode("search")}
          >搜索</button>
          <button
            className={`${styles.modeBtn} ${mode === "playlist" ? styles.modeActive : ""}`}
            onClick={() => { setMode("playlist"); loadPlaylists(); }}
          >歌单</button>
        </div>

        {mode === "search" ? (
          <div className={styles.searchBox}>
            <input
              className={styles.searchInput}
              placeholder="搜索网易云歌曲，回车"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter") handleSearch();
              }}
            />
            {results.length > 0 && (
              <div className={styles.searchResults}>
                {results.map((s, i) => (
                  <button key={s.key} className={styles.searchItem} onClick={() => playSong(s, i)}>
                    {s.name} - {s.artist}
                  </button>
                ))}
              </div>
            )}
          </div>
        ) : (
          <div className={styles.searchBox}>
            {!getMusicCookie() ? (
              <div className={styles.playlistHint}>请先点 🔑 登录，粘贴你的网易云 cookie</div>
            ) : !playlists.length ? (
              <div className={styles.playlistHint}>加载中… 或未登录（点 🔑 登录）</div>
            ) : (
              <div className={styles.playlistList}>
                <div className={styles.playlistTitle}>{nickname} 的歌单</div>
                {playlists.map((p) => (
                  <button key={p.id} className={styles.playlistItem} onClick={() => playPlaylist(p.id)}>
                    {p.name}
                  </button>
                ))}
              </div>
            )}
          </div>
        )}

        {cookieOpen ? (
          <div className={styles.cookieRow}>
            <input
              className={styles.cookieInput}
              placeholder="粘贴网易云 cookie（可选，解锁 VIP）"
              value={cookieInput}
              onChange={(e) => setCookieInput(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter") saveCookie();
              }}
            />
            <button className={styles.cookieSave} onClick={saveCookie}>保存</button>
          </div>
        ) : (
          <button className={styles.cookieToggle} onClick={() => setCookieOpen(true)}>
            🔑 {getMusicCookie() ? "已登录" : "登录"}
          </button>
        )}
      </div>
        </>
      )}
      </div>
    </>
  );
};

export default KittyMusicPlayer;
