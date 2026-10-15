import { useEffect, useState } from "react";
import { scenicPhotoReference } from "./request";
import MdRender from "./MdRender";

const STYLES = ["氛围感", "古风", "胶片清新", "出片拍照"];

/**
 * 景点拍照参考弹窗：Pexels 实景图横向滚动 + 点击放大 + 拍照攻略（机位/姿势/构图/时段）。
 * 风格可切换，切换后重新请求。
 */
export default function PhotoGuideModal({ scenicName, onClose }: { scenicName: string; onClose: () => void }) {
  const [style, setStyle] = useState(STYLES[0]);
  const [imageUrls, setImageUrls] = useState<string[]>([]);
  const [guide, setGuide] = useState("");
  const [tip, setTip] = useState("");
  const [loading, setLoading] = useState(false);
  const [preview, setPreview] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    scenicPhotoReference(scenicName, style).then((res) => {
      if (cancelled) return;
      if (res?.code === 200) {
        setImageUrls(Array.isArray(res.imageUrls) ? res.imageUrls : []);
        setGuide(res.guideContent || "");
        setTip(res.tip || "");
      } else {
        setImageUrls([]);
        setGuide(res?.msg || "生成失败");
        setTip("");
      }
      setLoading(false);
    });
    return () => { cancelled = true; };
  }, [scenicName, style]);

  return (
    <div className="photo-modal" onClick={() => setPreview(null)}>
      <div className="photo-modal-box" onClick={(e) => e.stopPropagation()}>
        <div className="photo-modal-head">
          <span className="photo-modal-title">📷 {scenicName} · 人像打卡参考</span>
          <button className="btn tool-btn" onClick={onClose}>✕</button>
        </div>

        <div className="photo-modal-styles">
          {STYLES.map((s) => (
            <button
              key={s}
              className={`btn tool-btn photo-style ${style === s ? "photo-style-active" : ""}`}
              onClick={() => setStyle(s)}
            >
              {s}
            </button>
          ))}
        </div>

        {loading && <div className="photo-modal-loading">📷 正在生成参考…</div>}

        {!loading && imageUrls.length > 0 && (
          <div className="photo-modal-images">
            {imageUrls.map((url, i) => (
              <img
                key={i}
                src={url}
                className="photo-modal-img"
                alt={`${scenicName}参考${i + 1}`}
                loading="lazy"
                referrerPolicy="no-referrer"
                onClick={() => setPreview(url)}
              />
            ))}
          </div>
        )}

        {tip && <div className="photo-modal-tip">{tip}</div>}

        {guide && (
          <div className="photo-modal-guide">
            <div className="photo-modal-guide-title">拍照攻略</div>
            <MdRender content={guide} />
          </div>
        )}
      </div>

      {preview && (
        <div className="photo-modal-preview" onClick={() => setPreview(null)}>
          <img src={preview} alt="预览" />
        </div>
      )}
    </div>
  );
}
