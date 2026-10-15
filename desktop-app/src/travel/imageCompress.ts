/**
 * 图片压缩工具 — 出行穿搭统一压缩规则。
 *  - 长边强制 ≤1280px（禁止原图直传）
 *  - 统一转 JPG，初始质量 0.7
 *  - 压缩后单张 ≤600KB（超出则逐步降质量，最低 0.35）
 */

export const MAX_LONG_EDGE = 1280;
export const JPEG_QUALITY = 0.7;
export const MAX_COMPRESSED_BYTES = 600 * 1024;
const MIN_QUALITY = 0.35;

/** base64 dataURL → 字节数估算（base64 长度 × 3/4） */
export function dataUrlBytes(dataUrl: string): number {
  const comma = dataUrl.indexOf(",");
  if (comma < 0) return 0;
  return Math.floor(((dataUrl.length - comma - 1) * 3) / 4);
}

/**
 * 压缩图片为 JPG：长边 ≤1280px，质量 0.7，必要时降质量保证 ≤600KB。
 * @param dataUrl 源图 data URL（png/jpg 均可）
 * @returns 压缩后 JPEG data URL（data:image/jpeg;base64,...）
 */
export function compressImage(dataUrl: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => {
      try {
        const longEdge = Math.max(img.naturalWidth, img.naturalHeight);
        const scale = longEdge > 0 ? Math.min(1, MAX_LONG_EDGE / longEdge) : 1;
        const w = Math.max(1, Math.round(img.naturalWidth * scale));
        const h = Math.max(1, Math.round(img.naturalHeight * scale));
        const canvas = document.createElement("canvas");
        canvas.width = w;
        canvas.height = h;
        const ctx = canvas.getContext("2d");
        if (!ctx) {
          resolve(dataUrl);
          return;
        }
        // 透明 PNG 先平铺白底，避免转 JPG 变黑底
        ctx.fillStyle = "#ffffff";
        ctx.fillRect(0, 0, w, h);
        ctx.drawImage(img, 0, 0, w, h);

        let quality = JPEG_QUALITY;
        let out = canvas.toDataURL("image/jpeg", quality);
        while (dataUrlBytes(out) > MAX_COMPRESSED_BYTES && quality > MIN_QUALITY) {
          quality = Math.round((quality - 0.1) * 100) / 100;
          out = canvas.toDataURL("image/jpeg", quality);
        }
        resolve(out);
      } catch (e) {
        reject(e instanceof Error ? e : new Error(String(e)));
      }
    };
    img.onerror = () => reject(new Error("图片加载失败，无法压缩"));
    img.src = dataUrl;
  });
}
