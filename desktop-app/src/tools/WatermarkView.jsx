import { useRef, useState } from "react";
import "./watermark.css";

import { API_BASE_URL as BASE_URL } from '../api/baseUrl';

const POSITIONS = [
  { id: "bottom-right", label: "右下角" },
  { id: "top-left", label: "左上角" },
  { id: "top-right", label: "右上角" },
  { id: "bottom-left", label: "左下角" },
  { id: "center", label: "居中" },
  { id: "tiled", label: "平铺" },
];
const REGIONS = [
  { id: "auto", label: "自动识别" },
  { id: "corner", label: "角落水印" },
  { id: "tiled", label: "平铺水印" },
];

/**
 * 💧 图片水印 — 独立页面。
 * 加水印（文字/字号/透明度/位置/颜色/平铺）+ 去水印（Java2D 邻域填充淡化）。
 * 图片以 Data URL 交后端内存处理，不落盘。
 */
export default function WatermarkView() {
  const [tab, setTab] = useState("add"); // add | remove
  const [original, setOriginal] = useState(null); // 原图 dataURL
  const [result, setResult] = useState(null); // 结果 dataURL
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState("");
  const fileInputRef = useRef(null);

  // 加水印参数
  const [text, setText] = useState("Copyright");
  const [fontSize, setFontSize] = useState(36);
  const [opacity, setOpacity] = useState(0.5);
  const [position, setPosition] = useState("bottom-right");
  const [tiled, setTiled] = useState(false);
  const [color, setColor] = useState("#ffffff");

  // 去水印参数
  const [regionType, setRegionType] = useState("auto");
  const [removePosition, setRemovePosition] = useState("bottom-right");

  const onFile = (f) => {
    if (!f) return;
    const reader = new FileReader();
    reader.onload = () => {
      setOriginal(reader.result);
      setResult(null);
      setMsg("");
    };
    reader.readAsDataURL(f);
  };

  const process = async () => {
    if (!original) {
      setMsg("请先上传图片");
      return;
    }
    setBusy(true);
    setMsg(tab === "add" ? "💧 添加水印中…" : "🧽 去水印中…");
    try {
      const body = tab === "add"
        ? { image: original, text, fontSize, opacity, position, tiled, color }
        : { image: original, regionType, position: removePosition };
      const res = await fetch(`${BASE_URL}/api/desktop/watermark/${tab}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      const json = await res.json().catch(() => null);
      if (res.ok && json?.code === 200 && json.image) {
        setResult(json.image);
        setMsg(json.msg || "处理完成");
      } else {
        setMsg(json?.msg || `处理失败（HTTP ${res.status}）`);
        setResult(null);
      }
    } catch (e) {
      setMsg(`❌ 处理失败：${e}`);
      setResult(null);
    }
    setBusy(false);
  };

  const download = () => {
    if (!result) return;
    const a = document.createElement("a");
    a.href = result;
    a.download = `watermark_${tab}_${Date.now()}.png`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
  };

  return (
    <div className="quiz-container">
      <div className="mg-panel wm-panel tool-card">
      <div className="mg-header">
        <h2>💧 图片水印</h2>
        <span className="mg-sub">加水印 / 去水印（后端内存处理，不落盘）</span>
      </div>

      <div className="wm-tabs">
        <button className={`wm-tab${tab === "add" ? " active" : ""}`} onClick={() => setTab("add")}>
          ➕ 加水印
        </button>
        <button className={`wm-tab${tab === "remove" ? " active" : ""}`} onClick={() => setTab("remove")}>
          🧽 去水印
        </button>
      </div>

      <div className="wm-upload" onClick={() => fileInputRef.current?.click()}>
        <input
          type="file"
          ref={fileInputRef}
          accept="image/*"
          style={{ display: "none" }}
          onChange={(e) => { onFile(e.target.files?.[0]); e.target.value = ""; }}
        />
        <div className="wm-upload-icon">🖼️</div>
        <div className="wm-upload-text">{original ? "点击更换图片" : "点击上传图片"}</div>
      </div>

      {tab === "add" ? (
        <div className="wm-params">
          <div className="wm-row">
            <label>水印文字</label>
            <input value={text} onChange={(e) => setText(e.target.value)} />
          </div>
          <div className="wm-row">
            <label>字号 {fontSize}</label>
            <input type="range" min="16" max="120" value={fontSize}
              onChange={(e) => setFontSize(+e.target.value)} />
          </div>
          <div className="wm-row">
            <label>透明度 {Math.round(opacity * 100)}%</label>
            <input type="range" min="5" max="100" value={Math.round(opacity * 100)}
              onChange={(e) => setOpacity(+e.target.value / 100)} />
          </div>
          <div className="wm-row">
            <label>位置</label>
            <select value={position} onChange={(e) => {
              const v = e.target.value;
              setPosition(v);
              setTiled(v === "tiled");
            }}>
              {POSITIONS.map((p) => <option key={p.id} value={p.id}>{p.label}</option>)}
            </select>
          </div>
          <div className="wm-row">
            <label>颜色</label>
            <input type="color" value={color} onChange={(e) => setColor(e.target.value)} />
          </div>
        </div>
      ) : (
        <div className="wm-params">
          <div className="wm-row">
            <label>水印区域</label>
            <select value={regionType} onChange={(e) => setRegionType(e.target.value)}>
              {REGIONS.map((r) => <option key={r.id} value={r.id}>{r.label}</option>)}
            </select>
          </div>
          {regionType === "corner" && (
            <div className="wm-row">
              <label>位置</label>
              <select value={removePosition} onChange={(e) => setRemovePosition(e.target.value)}>
                {POSITIONS.filter((p) => p.id !== "tiled").map((p) => (
                  <option key={p.id} value={p.id}>{p.label}</option>
                ))}
              </select>
            </div>
          )}
          <div className="wm-hint">去水印为本地 Java2D 邻域填充，适合淡化角落/半透明文字水印，效果有限</div>
        </div>
      )}

      <div className="mg-actions">
        <button className="btn send-btn" disabled={busy || !original} onClick={process}>
          {busy ? "处理中…" : tab === "add" ? "💧 添加水印" : "🧽 去水印"}
        </button>
        {result && <button className="btn tool-btn" onClick={download}>📥 下载</button>}
      </div>

      {msg && <div className="mg-msg">{msg}</div>}

      {(original || result) && (
        <div className="wm-preview">
          {original && (
            <div className="wm-preview-col">
              <div className="wm-preview-title">原图</div>
              <img src={original} alt="原图" />
            </div>
          )}
          {result && (
            <div className="wm-preview-col">
              <div className="wm-preview-title">结果</div>
              <img src={result} alt="结果" />
            </div>
          )}
        </div>
      )}
      </div>
    </div>
  );
}
