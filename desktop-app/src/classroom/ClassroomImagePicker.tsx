import { useEffect, useRef, useState } from 'react';
import type { EditorAssetPickerRequest } from '@educationagent/editor/ui';
import { classroomRequest } from './api';
import { resolveApiUrl } from '../api/request';

interface ImageAsset { url: string; width: number; height: number; mimeType: string; cached?: boolean }

/** 上传与生成都经过 Java 图片服务；课程只引用本站处理后的图片。 */
export default function ClassroomImagePicker({ request }: { request: EditorAssetPickerRequest }) {
  const [prompt, setPrompt] = useState('');
  const [ratio, setRatio] = useState('4:3');
  const [asset, setAsset] = useState<ImageAsset | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const active = useRef(true);
  const controller = useRef<AbortController | null>(null);
  useEffect(() => { active.current = true; return () => { active.current = false; controller.current?.abort(); }; }, []);
  const run = async (path: string, body: FormData | string) => {
    setBusy(true); setError(''); controller.current?.abort();
    const current = new AbortController(); controller.current = current;
    try {
      const result = await classroomRequest<ImageAsset>(path, { method: 'POST', body, signal: current.signal });
      if (active.current && !current.signal.aborted) setAsset(result);
    } catch (error) { if (active.current && !current.signal.aborted) setError(error instanceof Error ? error.message : '图片准备失败'); }
    finally { if (active.current) setBusy(false); }
  };
  return <div className="ce-image-picker">
    <label className="ce-image-upload">选择本地图片<input type="file" accept="image/png,image/jpeg,image/webp,image/gif" disabled={busy} onChange={event => {
      const file = event.target.files?.[0]; event.target.value = ''; if (!file) return;
      if (file.size > 8 * 1024 * 1024) { setError('请选择不超过 8 MB 的图片。'); return; }
      const form = new FormData(); form.append('file', file); void run('/media/upload', form);
    }} /></label>
    <span className="ce-image-divider">或者，为这堂课画一张图</span>
    <label>图片描述<textarea rows={3} maxLength={2000} value={prompt} disabled={busy} onChange={event => setPrompt(event.target.value)} placeholder="例如：一块分成四等份的菜地，其中一份种着白菜，清晰的教学插画。" /></label>
    <div className="ce-image-generate-row"><label>比例<select value={ratio} disabled={busy} onChange={event => setRatio(event.target.value)}><option value="4:3">4:3</option><option value="16:9">16:9</option><option value="1:1">1:1</option><option value="9:16">9:16</option></select></label><button type="button" disabled={busy || !prompt.trim()} onClick={() => void run('/media/images', JSON.stringify({ prompt: prompt.trim(), aspectRatio: ratio }))}>{busy ? '正在准备图片…' : '生成配图'}</button></div>
    {error && <p className="cr-error" role="alert">{error}</p>}
    {asset && <div className="ce-image-preview"><img src={resolveApiUrl(asset.url)} alt="待插入课堂的配图预览" /><button type="button" className="ce-primary" disabled={busy} onClick={() => request.onPick({ src: asset.url, width: asset.width, height: asset.height, ext: 'jpg' })}>放到画布</button></div>}
  </div>;
}
