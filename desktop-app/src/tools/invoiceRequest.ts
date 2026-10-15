/**
 * 发票识别与核验 —— 前端 API 封装。
 * 调用后端 DesktopOcrController（/api/desktop/ocr/*），密钥只存后端。
 */
const BASE_URL = import.meta.env.VITE_API_BASE_URL;

/**
 * 票据 OCR（百度智能云，后端中转，密钥不落前端）。
 * 上传图片 Data URL → 后端返回结构化票面字段。
 */
export const ocrInvoice = async (imageDataUrl: string): Promise<any> => {
  try {
    const res = await fetch(`${BASE_URL}/api/desktop/ocr/invoice`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ image: imageDataUrl }),
    });
    return await res.json();
  } catch (e) {
    return { code: 500, msg: `请求失败：${e}` };
  }
};

/**
 * 票据核验（后端转发第三方查验接口）。
 * @param params 核验必填参数（发票代码/号码/开票日期/校验码/金额等）
 * @returns 核验结果 JSON
 */
export const verifyInvoice = async (params: Record<string, string>): Promise<any> => {
  try {
    const res = await fetch(`${BASE_URL}/api/desktop/ocr/verify`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ params }),
    });
    return await res.json();
  } catch (e) {
    return { code: 500, msg: `请求失败：${e}` };
  }
};

/**
 * 导出发票识别记录为 Excel(.xlsx) —— 后端直接写入机器人生成文件目录并返回保存路径。
 * @param invoices 发票记录数组（含 words_result 字段 + _type/_time）
 * @returns { ok, filePath, fileName, msg }
 */
export const ocrExportExcel = async (invoices: any[]): Promise<{ ok: boolean; filePath?: string; fileName?: string; msg?: string }> => {
  try {
    const res = await fetch(`${BASE_URL}/api/desktop/ocr/export-excel`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ invoices }),
    });
    const json = await res.json().catch(() => null);
    if (json?.code === 200 && json.filePath) {
      return { ok: true, filePath: json.filePath, fileName: json.fileName };
    }
    return { ok: false, msg: json?.msg || `导出失败（HTTP ${res.status}）` };
  } catch (e) {
    console.error("导出失败:", e);
    return { ok: false, msg: `导出失败：${e}` };
  }
};
