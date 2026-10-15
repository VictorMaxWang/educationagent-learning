// 压缩图片：长边最大 maxSize，转 JPEG 0.85，返回 { mime, data }（data 为 base64 无前缀）
export function compressImage(file, maxSize = 1024) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const img = new Image();
      img.onload = () => {
        let { width, height } = img;
        if (width > height && width > maxSize) {
          height = Math.round((height * maxSize) / width);
          width = maxSize;
        } else if (height > maxSize) {
          width = Math.round((width * maxSize) / height);
          height = maxSize;
        }
        const canvas = document.createElement('canvas');
        canvas.width = width;
        canvas.height = height;
        canvas.getContext('2d').drawImage(img, 0, 0, width, height);
        const data = canvas.toDataURL('image/jpeg', 0.85).replace(/^data:image\/jpeg;base64,/, '');
        resolve({ mime: 'image/jpeg', data });
      };
      img.onerror = () => reject(new Error('图片解析失败'));
      img.src = reader.result;
    };
    reader.onerror = () => reject(new Error('文件读取失败'));
    reader.readAsDataURL(file);
  });
}

// 读取任意文件为 base64（无 data: 前缀）
export function fileToBase64(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result).split(',')[1]);
    reader.onerror = () => reject(new Error('文件读取失败'));
    reader.readAsDataURL(file);
  });
}

// ArrayBuffer → base64（无前缀）。供"后端直接返回文件字节流"的接口使用（如周报 docx / 备课素材 PDF）：
// 这些接口返回的是 application/octet-stream，不能走 JSON，所以要先转 base64 再交给原生保存。
// 分块处理是为了避免大文件把 String.fromCharCode 的参数栈打爆。
export function arrayBufferToBase64(buffer) {
  const bytes = new Uint8Array(buffer);
  let binary = '';
  const CHUNK = 0x8000;
  for (let i = 0; i < bytes.length; i += CHUNK) {
    binary += String.fromCharCode.apply(null, bytes.subarray(i, i + CHUNK));
  }
  return btoa(binary);
}

// base64 → Uint8Array
function base64ToUint8Array(base64) {
  const byteChars = atob(base64);
  const bytes = new Uint8Array(byteChars.length);
  for (let i = 0; i < byteChars.length; i++) {
    bytes[i] = byteChars.charCodeAt(i);
  }
  return bytes;
}

// 浏览器降级：a.download 触发下载。revokeObjectURL 必须延后执行，
// 否则 WebView/Chromium 可能在下载开始前就撤销 blob 引用，导致"点击没反应"。
function browserDownload(bytes, fileName) {
  const blob = new Blob([bytes], { type: 'application/octet-stream' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName || 'converted.bin';
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  setTimeout(() => URL.revokeObjectURL(url), 2000);
}

/**
 * 将 base64 字节保存为文件，返回 { saved, canceled }：
 * - Tauri 运行时：调用原生 Rust command save_converted_file，
 *   弹出"另存为"对话框让用户自选保存路径（saved=保存路径，canceled=用户取消）。
 * - 浏览器（vite dev 调试）降级：a.download 直接下载（saved=null, canceled=false）。
 * 供聊天流转换产物与学习栏目"格式转换"子页共用。
 */
export async function downloadBase64File(base64, fileName) {
  const bytes = base64ToUint8Array(base64);
  if (window.__TAURI_INTERNALS__) {
    try {
      const { invoke } = await import('@tauri-apps/api/core');
      // 传 base64 字符串而非大字节数组，避免 WebView IPC 截断；Rust 侧解码并校验长度
      const saved = await invoke('save_converted_file', {
        data: base64,
        suggestedName: fileName || 'converted.bin',
        expectedSize: bytes.length,
      });
      if (saved) return { saved, canceled: false };
      return { saved: null, canceled: true }; // 用户取消了"另存为"对话框
    } catch (e) {
      console.error('Tauri 原生保存失败，降级浏览器下载：', e);
      browserDownload(bytes, fileName);
      return { saved: null, canceled: false };
    }
  }
  browserDownload(bytes, fileName);
  return { saved: null, canceled: false };
}
