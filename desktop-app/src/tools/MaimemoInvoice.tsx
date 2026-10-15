import { useRef, useState } from "react";
import { ocrInvoice, verifyInvoice, ocrExportExcel } from "./invoiceRequest";

type InvoiceRec = {
  _id: string;
  _type: string;
  _time: string;
  [key: string]: any;
};

/** 提取发票号码（兼容中英文/拼音字段名，值可能是 {word:"xxx"} 或字符串） */
function getInvoiceNumber(rec: any): string {
  for (const k of ["发票号码", "发票编号", "InvoiceNum", "InvoiceCode", "invoice_num"]) {
    const v = rec?.[k];
    if (v != null && v !== "") return typeof v === "object" ? (v.word ?? "") : String(v);
  }
  return "";
}

/** 安全取值：兼容 {word:"xxx"} 对象，空值/占位返回 "-" */
function field(rec: any, ...keys: string[]): string {
  for (const k of keys) {
    const v = rec?.[k];
    if (v != null && v !== "") {
      const s = typeof v === "object" ? (v.word ?? "") : String(v);
      if (s && s !== "-" && s !== "null") return s;
    }
  }
  return "-";
}

/** 剥离"名称:xxx"前缀，过滤纯标签占位文案（年月日/社会信用代码等） */
function cleanLabel(v: string): string {
  if (!v || v === "-") return "-";
  const cleaned = v.replace(/^名称[:：]\s*/, "").trim();
  const labels = [
    "年月日", "名称", "购买方", "销售方", "购买方名称", "销售方名称",
    "社会信用代码", "纳税人识别号", "社会信用代码/纳税人识别号",
  ];
  return labels.includes(cleaned) ? "-" : cleaned;
}

/**
 * 🧾 票据识别 — 独立页面。
 * 上传发票图片/PDF → OCR 结构化字段 → 逐条核验 → 批量导出 Excel。
 * 复用 request.ts 的 ocrInvoice / verifyInvoice / ocrExportExcel，与聊天页的 🧾 功能互补。
 */
export default function MaimemoInvoice() {
  const [records, setRecords] = useState<InvoiceRec[]>([]);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState("");
  const [verifyText, setVerifyText] = useState<Record<string, string>>({});
  const [manualCheckCode, setManualCheckCode] = useState<Record<string, string>>({}); // 每张发票手动补充的校验码
  const fileInputRef = useRef<HTMLInputElement>(null);

  const onFile = (file: File) => {
    const reader = new FileReader();
    reader.onload = async () => {
      setBusy(true);
      setMsg("🧾 识别中…");
      try {
        const res = await ocrInvoice(reader.result as string);
        if (res?.code === 200) {
          const rec: InvoiceRec = {
            _id: `inv_${Date.now()}_${Math.floor(Math.random() * 10000)}`,
            _type: res.type || "invoice",
            _time: new Date().toLocaleString(),
            ...(res.data?.words_result || {}),
          };
          // 同号发票去重：已存在则用最新一条替换
          setRecords((prev) => {
            const num = getInvoiceNumber(rec);
            if (!num) return [...prev, rec];
            const idx = prev.findIndex((r) => getInvoiceNumber(r) === num);
            if (idx === -1) return [...prev, rec];
            const next = [...prev];
            next[idx] = rec;
            return next;
          });
          setMsg(`✅ 识别完成（${res.type === "electronic" ? "数电票" : "通用票据"}）`);
        } else {
          setMsg(res?.msg || "❌ 识别失败");
        }
      } catch (e) {
        setMsg(`❌ 识别失败：${e}`);
      }
      setBusy(false);
    };
    reader.readAsDataURL(file);
  };

  const doVerify = async (rid: string) => {
    const rec = records.find((r) => r._id === rid);
    if (!rec) return;
    // 校验码：优先用手动补充值，其次用 OCR 识别的（普通发票识别不到会报「手动补充」）
    const ocrCheck = field(rec, "校验码", "CheckCode", "check_code").slice(-6);
    const manualCheck = (manualCheckCode[rid] || "").trim();
    const checkCode = manualCheck || (ocrCheck !== "-" ? ocrCheck : "");
    const params: Record<string, string> = {
      invoiceNumber: getInvoiceNumber(rec),
      invoiceCode: field(rec, "发票代码"),
      billingDate: cleanLabel(field(rec, "开票日期", "InvoiceDate", "invoice_date")),
      totalAmount: cleanLabel(field(rec, "价税合计", "价税合计(小写)", "合计金额", "AmountInFiguers", "AmountInFigures")),
      checkCode,
    };
    const clean = Object.fromEntries(Object.entries(params).filter(([, v]) => v && v !== "-"));
    setVerifyText((prev) => ({ ...prev, [rid]: "🔍 核验中…" }));
    try {
      const res = await verifyInvoice(clean);
      const text = res?.code === 200
        ? "✅ 核验完成"
        : `❌ ${res?.msg || "核验失败"}`;
      setVerifyText((prev) => ({ ...prev, [rid]: text }));
    } catch (e) {
      setVerifyText((prev) => ({ ...prev, [rid]: `❌ 核验失败：${e}` }));
    }
  };

  const doExport = async () => {
    if (!records.length) {
      setMsg("暂无识别记录");
      return;
    }
    setBusy(true);
    try {
      const res = await ocrExportExcel(records);
      if (res?.ok && res.filePath) {
        setMsg(`✅ 已导出 ${records.length} 条发票记录\n保存到：${res.filePath}`);
      } else {
        setMsg(res?.msg || "导出失败");
      }
    } finally {
      setBusy(false);
    }
  };

  const removeRec = (rid: string) => {
    setRecords((prev) => prev.filter((r) => r._id !== rid));
    setVerifyText((prev) => {
      const next = { ...prev };
      delete next[rid];
      return next;
    });
  };

  return (
    <div className="quiz-container">
      <div className="mg-panel tool-card">
      <div className="mg-header">
        <h2>🧾 票据识别</h2>
        <span className="mg-sub">图片/PDF 发票 → 结构化字段 → 核验 → 导出 Excel</span>
      </div>

      <div className="mg-actions">
        <input
          type="file"
          ref={fileInputRef}
          accept="image/*,application/pdf,.pdf"
          style={{ display: "none" }}
          onChange={(e) => {
            const f = e.target.files?.[0];
            e.target.value = ""; // 允许重复选同一张
            if (f) onFile(f);
          }}
        />
        <button className="btn send-btn" disabled={busy} onClick={() => fileInputRef.current?.click()}>
          {busy ? "识别中…" : "🧾 上传发票识别"}
        </button>
        <button className="btn tool-btn" disabled={busy || !records.length} onClick={doExport}>
          📥 导出Excel({records.length})
        </button>
      </div>

      {msg && <div className="mg-msg">{msg}</div>}

      {records.length === 0 && !msg && <div className="mg-hint">上传发票图片或 PDF，自动识别票面字段（发票号码/日期/金额/税号/校验码）</div>}

      <div className="inv-records">
        {records.map((rec) => (
          <div className="inv-card" key={rec._id}>
            <div className="inv-card-head">
              <span className="lk-badge">{rec._type === "electronic" ? "数电票" : "通用票据"}</span>
              <span className="inv-time">{rec._time}</span>
              <button className="inv-del" onClick={() => removeRec(rec._id)} title="删除">🗑️</button>
            </div>
            <div className="inv-fields">
              <div><span className="inv-label">发票号码</span>{getInvoiceNumber(rec) || "-"}</div>
              <div><span className="inv-label">开票日期</span>{cleanLabel(field(rec, "开票日期", "InvoiceDate", "invoice_date"))}</div>
              <div><span className="inv-label">价税合计</span>{field(rec, "价税合计", "价税合计(小写)", "合计金额", "AmountInFiguers", "AmountInFigures", "AmountInWords")}</div>
              <div><span className="inv-label">销售方</span>{cleanLabel(field(rec, "销售方名称", "销售方", "SellerName", "seller_name"))}</div>
              <div><span className="inv-label">购买方</span>{cleanLabel(field(rec, "购买方名称", "购买方", "PurchaserName", "purchaser_name"))}</div>
              <div><span className="inv-label">校验码</span>{field(rec, "校验码", "CheckCode", "check_code").slice(-6) || "-"}</div>
            </div>
            <div className="mg-actions">
              <input
                className="inv-check-input"
                placeholder="校验码（识别不到可手动补）"
                value={manualCheckCode[rec._id] ?? ""}
                onChange={(e) => setManualCheckCode((p) => ({ ...p, [rec._id]: e.target.value }))}
              />
              <button className="btn tool-btn" disabled={busy} onClick={() => doVerify(rec._id)}>🔍 核验</button>
            </div>
            {verifyText[rec._id] && <pre className="inv-verify">{verifyText[rec._id]}</pre>}
          </div>
        ))}
      </div>
      </div>
    </div>
  );
}
