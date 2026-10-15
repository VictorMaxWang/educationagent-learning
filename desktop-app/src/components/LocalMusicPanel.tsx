import { useCallback, useEffect, useRef, useState } from 'react';

/** 公网不请求访问者的 localhost 音乐代理；本地音频在浏览器内播放，不上传文件。 */
export default function LocalMusicPanel({ onClose, onOpenOriginal }: { onClose(): void; onOpenOriginal?(): void }) {
  const [tracks, setTracks] = useState<{ name: string; url: string }[]>([]);
  const [index, setIndex] = useState(0);
  const urls = useRef<string[]>([]);
  const audio = useRef<HTMLAudioElement | null>(null);
  const attachAudio = useCallback((node: HTMLAudioElement | null) => {
    if (!node) audio.current?.pause();
    audio.current = node;
  }, []);
  useEffect(() => () => { audio.current?.pause(); urls.current.forEach(url => URL.revokeObjectURL(url)); }, []);
  return <div className="more-tools-overlay" onClick={onClose}><section className="local-music-panel" role="dialog" aria-modal="true" aria-label="音乐与休息" onClick={event => event.stopPropagation()}>
    <header><div><span>给耳朵一点轻松</span><h2>音乐与休息</h2></div><button aria-label="关闭音乐面板" onClick={onClose}>×</button></header>
    <p>选择电脑或手机里的音乐，陪你安静学一会儿。开始录音或听老师讲课时，音乐会自动暂停。</p>
    <label className="music-file-choice">＋ 选择音频文件<input type="file" accept="audio/*" multiple onChange={event => {
      const files = [...(event.target.files || [])]; event.target.value = ''; if (!files.length) return;
      audio.current?.pause(); urls.current.forEach(url => URL.revokeObjectURL(url));
      const next = files.map(file => ({ name: file.name, url: URL.createObjectURL(file) })); urls.current = next.map(track => track.url); setTracks(next); setIndex(0);
    }} /></label>
    {!!tracks.length && <><div className="music-current-track">{tracks[index]?.name}</div><audio ref={attachAudio} controls src={tracks[index]?.url} onEnded={() => { if (index + 1 < tracks.length) setIndex(index + 1); }} /><ol>{tracks.map((track, i) => <li key={track.url}><button className={i === index ? 'active' : ''} onClick={() => { audio.current?.pause(); setIndex(i); }}>{track.name}</button></li>)}</ol></>}
    <small>文件只在当前浏览器播放，关闭面板后释放。</small>
    {onOpenOriginal ? <button className="music-original" onClick={onOpenOriginal}>打开原网易云音乐播放器 →</button> : <p className="music-service-note">原网易云音乐功能仍保留在本机版本；在线曲库需要单独可用的音乐服务。</p>}
  </section></div>;
}
