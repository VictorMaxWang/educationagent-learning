/**
 * 设置面板（模态框）
 * 昵称 / 头像 / 主题颜色，全部实时写入 uiStore（persist 到 localStorage）
 */
import { useRef, useState } from 'react';
import { THEMES, useUiStore } from '../store/uiStore';

interface SettingsViewProps {
  onClose: () => void;
}

export default function SettingsView({ onClose }: SettingsViewProps) {
  const nickname = useUiStore((s) => s.nickname);
  const avatar = useUiStore((s) => s.avatar);
  const theme = useUiStore((s) => s.theme);
  const setNickname = useUiStore((s) => s.setNickname);
  const setAvatar = useUiStore((s) => s.setAvatar);
  const setTheme = useUiStore((s) => s.setTheme);
  const fileRef = useRef<HTMLInputElement>(null);
  const [dragOver, setDragOver] = useState(false); // 拖拽头像图片高亮

  /** 图片文件 → base64 dataURL 设为头像（点击选择 / 拖拽 共用） */
  const readAvatar = (file: File) => {
    const reader = new FileReader();
    reader.onload = () => setAvatar(typeof reader.result === 'string' ? reader.result : '');
    reader.readAsDataURL(file);
  };

  const handleAvatarChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    e.target.value = ''; // 允许重复选同一个
    if (file) readAvatar(file);
  };

  // 拖拽上传：头像区域作为投放区
  const onAvatarDragOver = (e: React.DragEvent) => { e.preventDefault(); setDragOver(true); };
  const onAvatarDragLeave = () => setDragOver(false);
  const onAvatarDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setDragOver(false);
    const file = e.dataTransfer?.files?.[0];
    if (file && file.type.startsWith('image/')) readAvatar(file);
  };

  return (
    <div className="settings-overlay" onClick={onClose}>
      <div className="settings-modal" onClick={(e) => e.stopPropagation()}>
        <div className="settings-head">
          <h2>⚙ 设置</h2>
          <button className="settings-close" onClick={onClose} title="关闭">
            ✕
          </button>
        </div>

        {/* 昵称 */}
        <div className="settings-section">
          <label className="settings-label">昵称</label>
          <input
            className="settings-input"
            value={nickname}
            placeholder="给自己起个昵称"
            maxLength={20}
            onChange={(e) => setNickname(e.target.value)}
          />
        </div>

        {/* 头像 */}
        <div className="settings-section">
          <label className="settings-label">头像</label>
          <div
            className={`settings-avatar-row${dragOver ? ' dragover' : ''}`}
            onDragOver={onAvatarDragOver}
            onDragLeave={onAvatarDragLeave}
            onDrop={onAvatarDrop}
          >
            <div className="settings-avatar-preview">
              {avatar ? (
                <img src={avatar} alt="头像" />
              ) : (
                <span>{nickname ? nickname[0] : '✦'}</span>
              )}
            </div>
            <div className="settings-avatar-actions">
              <button onClick={() => fileRef.current?.click()}>选择图片</button>
              {avatar && <button onClick={() => setAvatar('')}>移除</button>}
              <span className="settings-avatar-hint">📎 或拖拽图片到此处</span>
            </div>
            <input
              ref={fileRef}
              type="file"
              accept="image/*"
              style={{ display: 'none' }}
              onChange={handleAvatarChange}
            />
          </div>
        </div>

        {/* 主题颜色 */}
        <div className="settings-section">
          <label className="settings-label">主题颜色</label>
          <div className="settings-theme-row">
            {THEMES.map((t) => (
              <button
                key={t.key}
                className={`theme-chip${theme === t.key ? ' theme-chip-active' : ''}`}
                onClick={() => setTheme(t.key)}
              >
                <span className="theme-chip-dot" style={{ background: t.color }} />
                <span className="theme-chip-label">{t.label}</span>
                {theme === t.key && <span className="theme-chip-check">✓</span>}
              </button>
            ))}
          </div>
        </div>

        <div className="settings-foot">
          <button className="settings-done" onClick={onClose}>
            完成
          </button>
        </div>
      </div>
    </div>
  );
}
