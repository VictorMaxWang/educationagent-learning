import { useState, useRef, useEffect } from 'react';
import { uploadFile, convertFile } from '../api/chatApi';
import { fileToBase64, downloadBase64File } from '../utils/file';

/** 文档类目标格式（LibreOffice 输出白名单） */
const DOC_TARGETS = ['pdf', 'docx', 'txt', 'md', 'rtf', 'html'];
/** 图片类目标格式（ImageMagick 输出白名单） */
const IMG_TARGETS = ['jpg', 'png', 'webp'];

/** 格式 → 中文说明 */
const FORMAT_HINTS = {
  pdf: 'PDF 文档', docx: 'Word 文档', txt: '纯文本', md: 'Markdown',
  rtf: 'RTF 富文本', html: '网页', jpg: 'JPEG 图片', png: 'PNG 图片', webp: 'WebP 图片',
};

function formatSize(bytes) {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
}

/**
 * 格式转换子功能（学习栏目）：
 * 点击选择 / 拖拽文件 → 自动识别类型给出目标格式 → 转换 → 下载
 */
function FileConvertView() {
  const [fileId, setFileId] = useState(null);
  const [file, setFile] = useState(null); // { name, size }
  const [targets, setTargets] = useState(DOC_TARGETS);
  const [targetFormat, setTargetFormat] = useState('pdf');
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState(null); // { fileName, size, base64 }
  const [error, setError] = useState('');
  const [savedPath, setSavedPath] = useState('');
  const [dragOver, setDragOver] = useState(false);
  const fileInputRef = useRef(null);

  // 全局防拖拽：文件拖到拖拽框之外时，阻止浏览器默认"打开文件"行为导致页面跳走
  useEffect(() => {
    const prevent = (e) => e.preventDefault();
    window.addEventListener('dragover', prevent);
    window.addEventListener('drop', prevent);
    return () => {
      window.removeEventListener('dragover', prevent);
      window.removeEventListener('drop', prevent);
    };
  }, []);

  // 处理选中的文件（点击选择或拖拽进入共用）
  const handleFile = async (f) => {
    if (!f) return;
    setError('');
    setResult(null);
    setSavedPath('');
    setFile({ name: f.name, size: f.size });

    // 按文件类型切换目标格式列表
    const isImage = f.type.startsWith('image/')
      || /\.(jpg|jpeg|png|gif|webp|heic|heif|tiff|bmp|jxl)$/i.test(f.name);
    const list = isImage ? IMG_TARGETS : DOC_TARGETS;
    setTargets(list);
    setTargetFormat(list[0]);

    try {
      const data = await fileToBase64(f);
      const res = await uploadFile({ fileName: f.name, mime: f.type || 'application/octet-stream', data });
      if (res && res.fileId) setFileId(res.fileId);
      else setError('上传失败：' + (res?.error || '未知错误'));
    } catch (err) {
      setError('上传失败：' + err.message);
    }
  };

  const onInputChange = (e) => {
    const f = e.target.files?.[0];
    e.target.value = ''; // 允许重复选同一个
    handleFile(f);
  };

  const onDrop = (e) => {
    e.preventDefault();
    setDragOver(false);
    handleFile(e.dataTransfer?.files?.[0]);
  };

  const handleConvert = async () => {
    if (!fileId) { setError('请先选择文件'); return; }
    setBusy(true);
    setError('');
    setResult(null);
    setSavedPath('');
    try {
      const res = await convertFile({ fileId, targetFormat });
      if (res && res.success) {
        setResult({ fileName: res.fileName, size: res.size, base64: res.base64 });
      } else {
        setError(res?.error || '转换失败');
      }
    } catch (err) {
      setError('转换失败：' + err.message);
    } finally {
      setBusy(false);
    }
  };

  const handleDownload = async () => {
    if (!result) return;
    const { saved, canceled } = await downloadBase64File(result.base64, result.fileName);
    if (saved) {
      setSavedPath('✅ 已保存到：' + saved);
    } else if (!canceled) {
      setError('下载失败，请重试');
    }
  };

  return (
    <div className="quiz-container">
      <div className="convert-card">
        <h3 className="convert-card-title">🔄 格式转换</h3>

        {/* 大拖拽框：点击选择 / 拖拽文件 */}
        <div
          className={`convert-dropzone ${dragOver ? 'dragover' : ''}`}
          onClick={() => fileInputRef.current?.click()}
          onDragOver={(e) => { e.preventDefault(); setDragOver(true); }}
          onDragLeave={() => setDragOver(false)}
          onDrop={onDrop}
        >
          <input type="file" ref={fileInputRef} style={{ display: 'none' }} onChange={onInputChange} />
          {file ? (
            <>
              <div className="convert-drop-icon">📄</div>
              <div className="convert-drop-file">{file.name}</div>
              <div className="convert-drop-meta">{formatSize(file.size)} · 点击更换文件</div>
            </>
          ) : (
            <>
              <div className="convert-drop-icon">📁</div>
              <div className="convert-drop-title">点击选择文件，或将文件拖拽到此处</div>
              <div className="convert-drop-meta">支持 Word / PDF / Excel / PPT / 图片等格式</div>
            </>
          )}
        </div>

        {/* 目标格式 */}
        <div className="convert-panel-row">
          <span className="convert-label">目标格式</span>
          <div className="convert-format-tags">
            {targets.map(f => (
              <button
                key={f}
                className={`convert-tag ${targetFormat === f ? 'active' : ''}`}
                onClick={() => setTargetFormat(f)}
                title={FORMAT_HINTS[f] || f}
              >
                {f.toUpperCase()}
              </button>
            ))}
          </div>
        </div>

        <button className="quiz-btn primary convert-submit" onClick={handleConvert} disabled={busy || !fileId}>
          {busy ? '⏳ 转换中...' : '🔄 开始转换'}
        </button>

        {error && <div className="convert-error">⚠️ {error}</div>}

        {result && (
          <div className="convert-result">
            <div className="convert-result-row">
              <span>✅ 转换成功：{result.fileName}（{formatSize(result.size)}）</span>
              <button className="quiz-btn primary" onClick={handleDownload}>⬇️ 下载</button>
            </div>
            {savedPath && <div className="convert-saved">{savedPath}</div>}
          </div>
        )}
      </div>
    </div>
  );
}

export default FileConvertView;
