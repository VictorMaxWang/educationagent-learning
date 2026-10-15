/**
 * 语音气泡 —— 机器人语音回复（TTS）。
 * 点击播放/暂停，显示时长；播放中显示音量条动画。
 */
import { useEffect, useRef, useState } from 'react';

export default function VoiceBubble({ src }: { src: string }) {
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const [playing, setPlaying] = useState(false);
  const [duration, setDuration] = useState(0);

  useEffect(() => {
    const audio = new Audio(src);
    audio.preload = 'metadata';
    audio.onloadedmetadata = () => setDuration(Number.isFinite(audio.duration) ? audio.duration : 0);
    audio.onended = () => setPlaying(false);
    audioRef.current = audio;
    return () => {
      audio.pause();
      audio.src = '';
    };
  }, [src]);

  const toggle = () => {
    const a = audioRef.current;
    if (!a) return;
    if (a.paused) {
      void a.play().then(() => setPlaying(true)).catch(() => {});
    } else {
      a.pause();
      setPlaying(false);
    }
  };

  const format = (s: number) => {
    const sec = Math.max(0, Math.round(s));
    return `${Math.floor(sec / 60)}:${String(sec % 60).padStart(2, '0')}`;
  };

  return (
    <button
      className={`msg-voice${playing ? ' playing' : ''}`}
      onClick={toggle}
      title={playing ? '暂停' : '播放语音'}
    >
      <span className="msg-voice-icon">{playing ? '⏸' : '🔊'}</span>
      <span className="msg-voice-waves"><i /><i /><i /></span>
      <span className="msg-voice-duration">{duration > 0 ? format(duration) : '语音'}</span>
    </button>
  );
}
