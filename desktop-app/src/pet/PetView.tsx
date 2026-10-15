import { useCallback, useEffect, useRef, useState } from "react";
import * as api from "./petApi";
import "./PetView.css";

/** 🐾 萌宠日常 — 宠物档案 / 智能诊断 / 健康记录 / 成长日记 / 定时推送 / 养宠知识 */

type Pet = {
  id: number;
  petName: string;
  species?: string;
  breed?: string;
  gender?: string;
  birthDate?: string;
  weightKg?: number | null;
  photoUrl?: string;
  illnesses?: string;
  features?: string;
  createdAt?: string;
};

type Diagnosis = {
  id?: number;
  petName: string;
  diagnosis?: string;
  suggestions?: string;
  keywords?: string;
  rawText?: string;
  imageUrl?: string;
  createdAt?: string;
};

type Schedule = {
  id: number;
  petName: string;
  timeExpr: string;
  cronExpr: string;
  action: string;
  enabled: boolean;
  createdAt?: string;
};

type Tab = "dashboard" | "profile" | "diagnose" | "health" | "diary" | "schedule" | "knowledge";

const TABS: { key: Tab; label: string }[] = [
  { key: "dashboard", label: "📊 总览" },
  { key: "profile", label: "🐾 档案" },
  { key: "diagnose", label: "🩺 诊断" },
  { key: "health", label: "💪 健康" },
  { key: "diary", label: "📔 记录" },
  { key: "schedule", label: "⏰ 定时" },
  { key: "knowledge", label: "📖 知识" },
];

/** 侧栏子功能 → 内部 Tab 集合 */
type PetModule = "profile" | "care";
const MODULE_TABS: Record<PetModule, Tab[]> = {
  profile: ["dashboard", "profile"],
  care: ["diagnose", "health", "diary", "schedule", "knowledge"],
};
const MODULE_LABEL: Record<PetModule, string> = {
  profile: "宠物档案",
  care: "日常养护",
};

const SPECIES = ["狗", "猫", "兔", "鸟", "其他"];
const GENDERS = ["公", "母", "未确认"];
const EVENT_TYPES: [string, string][] = [
  ["milestone", "里程碑 ⭐"],
  ["photo", "照片 📷"],
  ["note", "笔记 📝"],
  ["vet", "就医 🏥"],
];
const EXPENSE_CATS: [string, string][] = [
  ["food", "食物 🍖"],
  ["medical", "医疗 🏥"],
  ["grooming", "美容 💇"],
  ["toy", "玩具 🎾"],
  ["other", "其他 📦"],
];
const DEWORM_TYPES = ["体内", "体外"];
const SEVERITIES = ["轻", "中", "重"];

const ok = (res: any) => !!res && res.code === 200;
const errOf = (res: any) => res?.message || "未知错误";

/* ============================================================
   主视图
   ============================================================ */
export default function PetView({
  userId,
  module = "profile",
}: {
  userId: string;
  module?: PetModule;
}) {
  const tabs = MODULE_TABS[module] || MODULE_TABS.profile;
  const tabItems = TABS.filter((t) => tabs.includes(t.key)); // 渲染用（含中文名）
  const [pets, setPets] = useState<Pet[]>([]);
  const [activeId, setActiveId] = useState<number | null>(null);
  const [tab, setTab] = useState<Tab>(tabs[0]);
  const [creating, setCreating] = useState(false);
  const [toast, setToast] = useState("");

  // 桌面端与微信端共用同一张表，自动匹配已有数据所属的 userId
  const [myId, setMyId] = useState(() => localStorage.getItem("pet_uid") || userId);
  const effectiveId = myId;

  useEffect(() => {
    setTab((MODULE_TABS[module] || MODULE_TABS.profile)[0]);
  }, [module]);

  const tryLoad = async (uid: string) => {
    try {
      const res = await api.petList(uid);
      if (ok(res) && res.data?.length > 0) return res.data;
    } catch (_) {}
    return null;
  };

  const loadPets = useCallback(async () => {
    try {
      // 1. 先用当前 userId 查
      let data = await tryLoad(effectiveId);
      if (data) {
        setPets(data);
        setActiveId((cur) => (data.some((p: Pet) => p.id === cur) ? cur : data[0]?.id ?? null));
        return;
      }
      // 2. 没查到 → 尝试历史候选 ID
      const candidates = JSON.parse(localStorage.getItem("pet_uid_candidates") || "[]");
      for (const cid of candidates) {
        if (cid === effectiveId) continue;
        data = await tryLoad(cid);
        if (data) {
          localStorage.setItem("pet_uid", cid);
          setMyId(cid);
          setPets(data);
          setActiveId(data[0]?.id ?? null);
          setToast("已匹配到用户 " + cid + " 的宠物数据");
          return;
        }
      }
      setPets([]);
      setActiveId(null);
    } catch (e) {
      setToast("加载失败：" + (e as Error).message);
    }
  }, [userId, effectiveId]);

  // 成功后记录 userId 到候选列表
  useEffect(() => {
    if (pets.length > 0 && effectiveId) {
      const list = JSON.parse(localStorage.getItem("pet_uid_candidates") || "[]");
      if (!list.includes(effectiveId)) {
        list.unshift(effectiveId);
        localStorage.setItem("pet_uid_candidates", JSON.stringify(list.slice(0, 5)));
      }
    }
  }, [pets.length, effectiveId]);

  useEffect(() => {
    loadPets();
  }, [loadPets]);

  const activePet = pets.find((p) => p.id === activeId) ?? null;

  const flash = (t: string) => {
    setToast(t);
    setTimeout(() => setToast(""), 3500);
  };

  const handleDelete = async () => {
    if (!activePet) return;
    if (!window.confirm(`确定删除「${activePet.petName}」的档案吗？相关记录也会删除。`)) return;
    const res = await api.petDelete(activePet.id, effectiveId);
    if (ok(res)) {
      flash(`已删除「${activePet.petName}」`);
      loadPets();
    } else {
      flash("删除失败：" + errOf(res));
    }
  };

  return (
    <div className="quiz-container quiz-scroll">
      <div className="pet-wrap">
        {/* 标题 + Tab + 内容（单列） */}
        <div className="pet-main">
          <div className="pet-head">
            <div className="pet-head-title">
              <h2>🐾 萌宠日常</h2>
              <span className="pet-head-sub">{MODULE_LABEL[module]}</span>
            </div>
            <div className="pet-head-ops">
              <PetDropdown
                pets={pets}
                activeId={activeId}
                onSwitch={(id) => setActiveId(id)}
                onAdd={() => setCreating(true)}
              />
              <button className="btn send-btn" onClick={() => setCreating(true)}>
                ＋ 新建
              </button>
              {activePet && (
                <button className="btn pet-del" onClick={handleDelete} title="删除宠物">
                  🗑 删除
                </button>
              )}
            </div>
          </div>

          {/* 功能 Tab（仅当前子功能包含的） */}
          {tabItems.length > 1 && (
            <div className="pet-tabs">
              {tabItems.map((t) => (
                <button
                  key={t.key}
                  className={`pet-tab${tab === t.key ? " active" : ""}`}
                  onClick={() => setTab(t.key)}
                >
                  {t.label}
                </button>
              ))}
            </div>
          )}

          {toast && <div className="pet-toast">{toast}</div>}

          {creating && (
            <PetRegisterForm
              userId={effectiveId}
              onClose={() => setCreating(false)}
              onDone={() => {
                setCreating(false);
                loadPets();
                flash("🎉 宠物档案创建成功");
              }}
            />
          )}

          {!activePet && !creating ? (
            <EmptyState
              userId={effectiveId}
              onSwitch={(newId) => {
                localStorage.setItem("pet_uid", newId);
                setMyId(newId);
              }}
            />
          ) : activePet ? (
            <div className="pet-body">
              {tab === "dashboard" && <DashboardTab pet={activePet} userId={effectiveId} flash={flash} />}
              {tab === "profile" && <ProfileTab pet={activePet} userId={effectiveId} onChanged={loadPets} flash={flash} />}
              {tab === "diagnose" && <DiagnoseTab pet={activePet} userId={effectiveId} flash={flash} />}
              {tab === "health" && <HealthTab pet={activePet} userId={effectiveId} flash={flash} />}
              {tab === "diary" && <DiaryTab pet={activePet} userId={effectiveId} flash={flash} />}
              {tab === "schedule" && <ScheduleTab pet={activePet} userId={effectiveId} flash={flash} />}
              {tab === "knowledge" && <KnowledgeTab pet={activePet} />}
            </div>
          ) : null}
        </div>
      </div>
    </div>
  );
}

/* ============================================================
   宠物切换下拉（参考健康模块 MemberDropdown 样式）
   ============================================================ */
function PetDropdown({
  pets,
  activeId,
  onSwitch,
  onAdd,
}: {
  pets: Pet[];
  activeId: number | null;
  onSwitch: (id: number) => void;
  onAdd: () => void;
}) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const active = pets.find((p) => p.id === activeId) ?? null;
  const emoji = (s?: string) => (s === "猫" ? "🐱" : s === "兔" ? "🐰" : "🐶");

  // 点击外部关闭
  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", handler);
    return () => document.removeEventListener("mousedown", handler);
  }, []);

  return (
    <div className="pet-dropdown" ref={ref}>
      <button className="pet-dropdown-trigger" onClick={() => setOpen((v) => !v)}>
        <span className="pet-dropdown-avatar">{active ? emoji(active.species) : "🐾"}</span>
        <span className="pet-dropdown-name">{active?.petName ?? "选择宠物"}</span>
        <span className={`pet-dropdown-arrow${open ? " open" : ""}`}>▾</span>
      </button>
      {open && (
        <div className="pet-dropdown-menu">
          {pets.length === 0 && <div className="pet-dropdown-item pet-dropdown-empty">暂无宠物</div>}
          {pets.map((p) => (
            <div
              key={p.id}
              className={`pet-dropdown-item${p.id === activeId ? " active" : ""}`}
              onClick={() => {
                onSwitch(p.id);
                setOpen(false);
              }}
            >
              <span className="pet-dropdown-avatar">{emoji(p.species)}</span>
              <span className="pet-dropdown-name">{p.petName}</span>
            </div>
          ))}
          <div
            className="pet-dropdown-item pet-dropdown-add"
            onClick={() => {
              onAdd();
              setOpen(false);
            }}
          >
            <span className="pet-dropdown-avatar">＋</span>
            <span className="pet-dropdown-name">新建宠物</span>
          </div>
        </div>
      )}
    </div>
  );
}

/* ============================================================
   新建宠物表单
   ============================================================ */
function PetRegisterForm({
  userId,
  onClose,
  onDone,
}: {
  userId: string;
  onClose: () => void;
  onDone: () => void;
}) {
  const [petName, setPetName] = useState("");
  const [species, setSpecies] = useState(SPECIES[0]);
  const [breed, setBreed] = useState("");
  const [gender, setGender] = useState(GENDERS[0]);
  const [birthDate, setBirthDate] = useState("");
  const [weightKg, setWeightKg] = useState("");
  const [features, setFeatures] = useState("");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");

  const submit = async () => {
    if (!petName.trim()) {
      setErr("请填写宠物名字");
      return;
    }
    setBusy(true);
    setErr("");
    try {
      const res = await api.petRegister({
        userId,
        petName: petName.trim(),
        species,
        breed: breed.trim(),
        gender,
        birthDate: birthDate || null,
        weightKg: weightKg ? Number(weightKg) : null,
        features: features.trim(),
      });
      if (ok(res)) onDone();
      else setErr(errOf(res));
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="pet-modal-overlay">
      <div className="pet-modal">
        <div className="pet-modal-head">
          <h3>🐾 新建宠物</h3>
          <button className="pet-modal-close" onClick={onClose}>
            ✕
          </button>
        </div>
        <div className="pet-form">
          <label className="pet-field">
            <span>名字 *</span>
            <input value={petName} onChange={(e) => setPetName(e.target.value)} placeholder="如：旺财" maxLength={20} />
          </label>
          <div className="pet-field-row">
            <label className="pet-field">
              <span>种类</span>
              <select value={species} onChange={(e) => setSpecies(e.target.value)}>
                {SPECIES.map((s) => (
                  <option key={s} value={s}>
                    {s}
                  </option>
                ))}
              </select>
            </label>
            <label className="pet-field">
              <span>性别</span>
              <select value={gender} onChange={(e) => setGender(e.target.value)}>
                {GENDERS.map((g) => (
                  <option key={g} value={g}>
                    {g}
                  </option>
                ))}
              </select>
            </label>
          </div>
          <label className="pet-field">
            <span>品种</span>
            <input value={breed} onChange={(e) => setBreed(e.target.value)} placeholder="如：金毛 / 英短" />
          </label>
          <div className="pet-field-row">
            <label className="pet-field">
              <span>出生日期</span>
              <input type="date" value={birthDate} onChange={(e) => setBirthDate(e.target.value)} />
            </label>
            <label className="pet-field">
              <span>体重 (kg)</span>
              <input type="number" step="0.1" value={weightKg} onChange={(e) => setWeightKg(e.target.value)} placeholder="如：8.5" />
            </label>
          </div>
          <label className="pet-field">
            <span>特征</span>
            <input value={features} onChange={(e) => setFeatures(e.target.value)} placeholder="如：棕黄色、活泼好动" />
          </label>
        </div>
        {err && <div className="pet-err">{err}</div>}
        <div className="pet-modal-actions">
          <button className="btn tool-btn" onClick={onClose}>
            取消
          </button>
          <button className="btn send-btn" onClick={submit} disabled={busy}>
            {busy ? "创建中…" : "保存"}
          </button>
        </div>
      </div>
    </div>
  );
}

/* ============================================================
   档案 Tab：基本信息 + 资料更新 + 病史 + 健康评分
   ============================================================ */
function ProfileTab({
  pet,
  userId,
  onChanged,
  flash,
}: {
  pet: Pet;
  userId: string;
  onChanged: () => void;
  flash: (t: string) => void;
}) {
  const [form, setForm] = useState({
    species: pet.species || "狗",
    breed: pet.breed || "",
    gender: pet.gender || "未确认",
    birthDate: pet.birthDate || "",
    weightKg: pet.weightKg ? String(pet.weightKg) : "",
    features: pet.features || "",
  });
  const [score, setScore] = useState("");
  const [busy, setBusy] = useState(false);

  // 宠物切换时重置表单
  useEffect(() => {
    setForm({
      species: pet.species || "狗",
      breed: pet.breed || "",
      gender: pet.gender || "未确认",
      birthDate: pet.birthDate || "",
      weightKg: pet.weightKg ? String(pet.weightKg) : "",
      features: pet.features || "",
    });
  }, [pet.id]); // eslint-disable-line react-hooks/exhaustive-deps

  const save = async () => {
    setBusy(true);
    try {
      const patches: [string, string][] = [
        ["species", form.species],
        ["breed", form.breed],
        ["gender", form.gender],
        ["birthDate", form.birthDate],
        ["weightKg", form.weightKg],
        ["features", form.features],
      ];
      for (const [field, value] of patches) {
        const cur = field === "weightKg" ? (pet.weightKg ? String(pet.weightKg) : "") : (pet as any)[field] || "";
        if (cur === value) continue;
        const res = await api.petUpdate(pet.id, { userId, field, value });
        if (!ok(res)) {
          flash("更新失败：" + errOf(res));
          return;
        }
      }
      flash("✅ 资料已更新");
      onChanged();
    } catch (e) {
      flash("更新失败：" + (e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const checkScore = async () => {
    setScore("");
    const res = await api.petHealthScore(userId, pet.petName);
    if (ok(res)) setScore(res.data || "");
    else flash("评分失败：" + errOf(res));
  };

  return (
    <div className="pet-grid">
      {/* 基本信息 */}
      <div className="pet-panel">
        <div className="pet-panel-title">📋 基本信息</div>
        <div className="pet-info">
          <div className="pet-info-row">
            <span>名字</span>
            <b>{pet.petName}</b>
          </div>
          <div className="pet-info-row">
            <span>种类</span>
            <b>{pet.species || "—"}</b>
          </div>
          <div className="pet-info-row">
            <span>品种</span>
            <b>{pet.breed || "—"}</b>
          </div>
          <div className="pet-info-row">
            <span>性别</span>
            <b>{pet.gender || "—"}</b>
          </div>
          <div className="pet-info-row">
            <span>出生</span>
            <b>{pet.birthDate || "—"}</b>
          </div>
          <div className="pet-info-row">
            <span>体重</span>
            <b>{pet.weightKg != null ? `${pet.weightKg} kg` : "—"}</b>
          </div>
          <div className="pet-info-row">
            <span>建档</span>
            <b>{pet.createdAt || "—"}</b>
          </div>
        </div>
        <button className="btn tool-btn pet-score-btn" onClick={checkScore}>
          🏥 查看健康评分
        </button>
        {score && (
          <pre className="pet-text-block">{score}</pre>
        )}
      </div>

      {/* 资料更新 */}
      <div className="pet-panel">
        <div className="pet-panel-title">✏️ 更新资料</div>
        <div className="pet-form">
          <div className="pet-field-row">
            <label className="pet-field">
              <span>种类</span>
              <select value={form.species} onChange={(e) => setForm({ ...form, species: e.target.value })}>
                {SPECIES.map((s) => (
                  <option key={s} value={s}>
                    {s}
                  </option>
                ))}
              </select>
            </label>
            <label className="pet-field">
              <span>性别</span>
              <select value={form.gender} onChange={(e) => setForm({ ...form, gender: e.target.value })}>
                {GENDERS.map((g) => (
                  <option key={g} value={g}>
                    {g}
                  </option>
                ))}
              </select>
            </label>
          </div>
          <label className="pet-field">
            <span>品种</span>
            <input value={form.breed} onChange={(e) => setForm({ ...form, breed: e.target.value })} placeholder="如：金毛" />
          </label>
          <div className="pet-field-row">
            <label className="pet-field">
              <span>出生日期</span>
              <input type="date" value={form.birthDate} onChange={(e) => setForm({ ...form, birthDate: e.target.value })} />
            </label>
            <label className="pet-field">
              <span>体重 (kg)</span>
              <input type="number" step="0.1" value={form.weightKg} onChange={(e) => setForm({ ...form, weightKg: e.target.value })} />
            </label>
          </div>
          <label className="pet-field">
            <span>特征</span>
            <input value={form.features} onChange={(e) => setForm({ ...form, features: e.target.value })} placeholder="如：棕黄色、活泼好动" />
          </label>
        </div>
        <div className="pet-panel-actions">
          <button className="btn send-btn" onClick={save} disabled={busy}>
            {busy ? "保存中…" : "💾 保存修改"}
          </button>
        </div>
      </div>

      {/* 病史 */}
      <div className="pet-panel pet-panel-wide">
        <div className="pet-panel-title">🏥 病史记录</div>
        {pet.illnesses ? (
          <pre className="pet-text-block">{pet.illnesses}</pre>
        ) : (
          <div className="pet-subtle">暂无病史记录，智能诊断发现问题时会自动追加。</div>
        )}
      </div>
    </div>
  );
}

/* ============================================================
   诊断 Tab：图片诊断 + 症状自查 + 历史
   ============================================================ */
function DiagnoseTab({
  pet,
  userId,
  flash,
}: {
  pet: Pet;
  userId: string;
  flash: (t: string) => void;
}) {
  const [image, setImage] = useState<File | null>(null);
  const [imageUrl, setImageUrl] = useState("");
  const [concern, setConcern] = useState("");
  const [symptoms, setSymptoms] = useState("");
  const [result, setResult] = useState<Diagnosis | null>(null);
  const [history, setHistory] = useState<Diagnosis[]>([]);
  const [busy, setBusy] = useState(false);
  const [dragOver, setDragOver] = useState(false);
  const fileRef = useRef<HTMLInputElement>(null);

  const loadHistory = useCallback(async () => {
    const res = await api.petDiagnoses(userId, pet.petName);
    if (ok(res)) setHistory(res.data || []);
  }, [userId, pet.petName]);

  useEffect(() => {
    loadHistory();
  }, [loadHistory]);

  const pickImage = (f: File | null) => {
    if (!f) return;
    if (!f.type.startsWith("image/")) {
      flash("请选择图片文件");
      return;
    }
    setImage(f);
    setImageUrl(URL.createObjectURL(f));
  };

  const doImageDiagnose = async () => {
    if (!image) {
      flash("请先上传宠物照片");
      return;
    }
    setBusy(true);
    try {
      const res = await api.petDiagnose({ image, userId, petName: pet.petName, concern });
      if (ok(res)) {
        setResult(res.data);
        loadHistory();
      } else {
        flash("诊断失败：" + errOf(res));
      }
    } catch (e) {
      flash("诊断失败：" + (e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const doSymptomCheck = async () => {
    if (!symptoms.trim()) {
      flash("请描述症状");
      return;
    }
    setBusy(true);
    try {
      const res = await api.petSymptomCheck({ userId, petName: pet.petName, symptoms: symptoms.trim() });
      if (ok(res)) {
        setResult(res.data);
        loadHistory();
      } else {
        flash("分析失败：" + errOf(res));
      }
    } catch (e) {
      flash("分析失败：" + (e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="pet-grid">
      {/* 图片诊断 */}
      <div
        className={`pet-panel pet-drop${dragOver ? " dragover" : ""}`}
        onDragOver={(e) => {
          e.preventDefault();
          setDragOver(true);
        }}
        onDragLeave={() => setDragOver(false)}
        onDrop={(e) => {
          e.preventDefault();
          setDragOver(false);
          pickImage(e.dataTransfer?.files?.[0] ?? null);
        }}
      >
        <div className="pet-panel-title">🩺 照片诊断</div>
        {image ? (
          <div className="pet-diag-img-wrap">
            <img className="pet-diag-img" src={imageUrl} alt="待诊断" />
            <div className="pet-diag-img-meta">
              {image.name} · <button className="link-btn" onClick={() => fileRef.current?.click()}>更换</button>
            </div>
          </div>
        ) : (
          <div className="pet-dropzone" onClick={() => fileRef.current?.click()}>
            <div className="pet-drop-icon">📷</div>
            <div className="pet-drop-title">点击上传宠物照片，或拖拽到此处</div>
          </div>
        )}
        <input
          ref={fileRef}
          type="file"
          accept="image/*"
          style={{ display: "none" }}
          onChange={(e) => {
            pickImage(e.target.files?.[0] ?? null);
            e.target.value = "";
          }}
        />
        <label className="pet-field">
          <span>主诉（可选）</span>
          <input value={concern} onChange={(e) => setConcern(e.target.value)} placeholder="如：皮肤有点红肿，怎么办" />
        </label>
        <button className="btn send-btn pet-block-btn" onClick={doImageDiagnose} disabled={busy}>
          {busy ? "诊断中…" : "🔍 开始诊断"}
        </button>
      </div>

      {/* 症状自查 */}
      <div className="pet-panel">
        <div className="pet-panel-title">📝 症状自查</div>
        <label className="pet-field">
          <span>症状描述</span>
          <textarea
            className="pet-textarea"
            rows={4}
            value={symptoms}
            onChange={(e) => setSymptoms(e.target.value)}
            placeholder="描述宠物的症状，如：呕吐、没精神、不吃东西"
          />
        </label>
        <button className="btn send-btn pet-block-btn" onClick={doSymptomCheck} disabled={busy}>
          {busy ? "分析中…" : "🧐 症状分析"}
        </button>
        <div className="pet-subtle" style={{ marginTop: 8 }}>
          💡 诊断结果仅供参考，宠物有明显不适请及时就医。
        </div>
      </div>

      {/* 诊断结果 */}
      {result && (
        <div className="pet-panel pet-panel-wide">
          <div className="pet-panel-title">📋 诊断结果</div>
          {result.diagnosis && (
            <div className="pet-diag-section">
              <b>诊断结论</b>
              <p>{result.diagnosis}</p>
            </div>
          )}
          {result.suggestions && (
            <div className="pet-diag-section">
              <b>处理建议</b>
              <p style={{ whiteSpace: "pre-wrap" }}>{result.suggestions}</p>
            </div>
          )}
          {result.keywords && (
            <div className="pet-diag-keywords">
              {result.keywords.split(/[,，]/).map((k, i) => (
                <span key={i} className="pet-kw">
                  {k}
                </span>
              ))}
            </div>
          )}
        </div>
      )}

      {/* 历史 */}
      <div className="pet-panel pet-panel-wide">
        <div className="pet-panel-title">🗂 历史诊断</div>
        {history.length === 0 ? (
          <div className="pet-subtle">暂无诊断记录。</div>
        ) : (
          <div className="pet-history">
            {history.map((h) => (
              <div key={h.id} className="pet-history-item">
                <div className="pet-history-head">
                  <b>{h.diagnosis || "未命名"}</b>
                  <span>{h.createdAt || ""}</span>
                </div>
                {h.suggestions && <div className="pet-history-sub">{h.suggestions.slice(0, 120)}</div>}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

/* ============================================================
   健康 Tab：体重 / 疫苗 / 驱虫 / 用药
   ============================================================ */
function HealthTab({
  pet,
  userId,
  flash,
}: {
  pet: Pet;
  userId: string;
  flash: (t: string) => void;
}) {
  const [weightKg, setWeightKg] = useState("");
  const [weightNote, setWeightNote] = useState("");
  const [weightView, setWeightView] = useState("");

  const [vaccineName, setVaccineName] = useState("");
  const [vaccineView, setVaccineView] = useState("");

  const [dewormType, setDewormType] = useState(DEWORM_TYPES[0]);
  const [drugName, setDrugName] = useState("");
  const [dewormView, setDewormView] = useState("");

  const [medDrug, setMedDrug] = useState("");
  const [medStart, setMedStart] = useState("");
  const [medEnd, setMedEnd] = useState("");
  const [medTimes, setMedTimes] = useState(1);
  const [medDosage, setMedDosage] = useState("");
  const [medView, setMedView] = useState("");

  const [busy, setBusy] = useState(false);

  const run = async (fn: () => Promise<any>, onOk: (t: string) => void) => {
    setBusy(true);
    try {
      const res = await fn();
      if (ok(res)) {
        if (res.data) onOk(res.data);
        flash(res.message || "✅ 已记录");
      } else {
        flash("失败：" + errOf(res));
      }
    } catch (e) {
      flash("失败：" + (e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="pet-grid">
      {/* 体重 */}
      <div className="pet-panel">
        <div className="pet-panel-title">⚖️ 体重管理</div>
        <div className="pet-field-row">
          <label className="pet-field">
            <span>体重 (kg)</span>
            <input type="number" step="0.1" value={weightKg} onChange={(e) => setWeightKg(e.target.value)} />
          </label>
          <label className="pet-field">
            <span>备注</span>
            <input value={weightNote} onChange={(e) => setWeightNote(e.target.value)} placeholder="可选" />
          </label>
        </div>
        <div className="pet-panel-actions">
          <button
            className="btn send-btn"
            disabled={busy || !weightKg}
            onClick={() =>
              run(
                () => api.petLogWeight({ userId, petName: pet.petName, weightKg: Number(weightKg), note: weightNote }),
                (t) => setWeightView(t),
              )
            }
          >
            记录体重
          </button>
          <button className="btn tool-btn" onClick={() => run(() => api.petGetWeights(userId, pet.petName), (t) => setWeightView(t))}>
            查看
          </button>
        </div>
        {weightView && <pre className="pet-text-block">{weightView}</pre>}
      </div>

      {/* 疫苗 */}
      <div className="pet-panel">
        <div className="pet-panel-title">💉 疫苗管理</div>
        <label className="pet-field">
          <span>疫苗名称</span>
          <input value={vaccineName} onChange={(e) => setVaccineName(e.target.value)} placeholder="如：狂犬 / 猫三联" />
        </label>
        <div className="pet-panel-actions">
          <button
            className="btn send-btn"
            disabled={busy || !vaccineName.trim()}
            onClick={() =>
              run(
                () => api.petLogVaccine({ userId, petName: pet.petName, vaccineName: vaccineName.trim() }),
                (t) => setVaccineView(t),
              )
            }
          >
            记录接种
          </button>
          <button className="btn tool-btn" onClick={() => run(() => api.petGetVaccines(userId, pet.petName), (t) => setVaccineView(t))}>
            查看
          </button>
        </div>
        {vaccineView && <pre className="pet-text-block">{vaccineView}</pre>}
      </div>

      {/* 驱虫 */}
      <div className="pet-panel">
        <div className="pet-panel-title">🪱 驱虫管理</div>
        <div className="pet-field-row">
          <label className="pet-field">
            <span>类型</span>
            <select value={dewormType} onChange={(e) => setDewormType(e.target.value)}>
              {DEWORM_TYPES.map((d) => (
                <option key={d} value={d}>
                  {d}
                </option>
              ))}
            </select>
          </label>
          <label className="pet-field">
            <span>药品</span>
            <input value={drugName} onChange={(e) => setDrugName(e.target.value)} placeholder="如：大宠爱" />
          </label>
        </div>
        <div className="pet-panel-actions">
          <button
            className="btn send-btn"
            disabled={busy || !drugName.trim()}
            onClick={() =>
              run(
                () => api.petLogDeworming({ userId, petName: pet.petName, dewormType, drugName: drugName.trim() }),
                (t) => setDewormView(t),
              )
            }
          >
            记录驱虫
          </button>
          <button className="btn tool-btn" onClick={() => run(() => api.petGetDewormings(userId, pet.petName), (t) => setDewormView(t))}>
            查看
          </button>
        </div>
        {dewormView && <pre className="pet-text-block">{dewormView}</pre>}
      </div>

      {/* 用药 */}
      <div className="pet-panel">
        <div className="pet-panel-title">💊 用药提醒</div>
        <label className="pet-field">
          <span>药名</span>
          <input value={medDrug} onChange={(e) => setMedDrug(e.target.value)} placeholder="如：阿莫西林" />
        </label>
        <div className="pet-field-row">
          <label className="pet-field">
            <span>开始</span>
            <input type="date" value={medStart} onChange={(e) => setMedStart(e.target.value)} />
          </label>
          <label className="pet-field">
            <span>结束</span>
            <input type="date" value={medEnd} onChange={(e) => setMedEnd(e.target.value)} />
          </label>
        </div>
        <div className="pet-field-row">
          <label className="pet-field">
            <span>每天次数</span>
            <input type="number" min={1} max={5} value={medTimes} onChange={(e) => setMedTimes(Number(e.target.value) || 1)} />
          </label>
          <label className="pet-field">
            <span>用量</span>
            <input value={medDosage} onChange={(e) => setMedDosage(e.target.value)} placeholder="如：半片 / 5ml" />
          </label>
        </div>
        <div className="pet-panel-actions">
          <button
            className="btn send-btn"
            disabled={busy || !medDrug.trim() || !medStart || !medEnd}
            onClick={() =>
              run(
                () =>
                  api.petLogMedication({
                    userId,
                    petName: pet.petName,
                    drugName: medDrug.trim(),
                    startDate: medStart,
                    endDate: medEnd,
                    timesPerDay: medTimes,
                    dosage: medDosage.trim(),
                  }),
                (t) => setMedView(t),
              )
            }
          >
            设置用药
          </button>
          <button className="btn tool-btn" onClick={() => run(() => api.petGetMedications(userId, pet.petName), (t) => setMedView(t))}>
            查看
          </button>
        </div>
        {medView && <pre className="pet-text-block">{medView}</pre>}
      </div>
    </div>
  );
}

/* ============================================================
   记录 Tab：成长日记 / 花费 / 症状
   ============================================================ */
function DiaryTab({
  pet,
  userId,
  flash,
}: {
  pet: Pet;
  userId: string;
  flash: (t: string) => void;
}) {
  const [gType, setGType] = useState(EVENT_TYPES[0][0]);
  const [gContent, setGContent] = useState("");
  const [gView, setGView] = useState("");

  const [eCat, setECat] = useState(EXPENSE_CATS[0][0]);
  const [eAmount, setEAmount] = useState("");
  const [eNote, setENote] = useState("");
  const [eView, setEView] = useState("");

  const [sSymptom, setSSymptom] = useState("");
  const [sSev, setSSev] = useState(SEVERITIES[1]);
  const [sNote, setSNote] = useState("");
  const [sView, setSView] = useState("");

  const [busy, setBusy] = useState(false);

  const run = async (fn: () => Promise<any>, onOk: (t: string) => void) => {
    setBusy(true);
    try {
      const res = await fn();
      if (ok(res)) {
        if (res.data) onOk(res.data);
        flash(res.message || "✅ 已记录");
      } else {
        flash("失败：" + errOf(res));
      }
    } catch (e) {
      flash("失败：" + (e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  /** 记录成长日记并刷新列表 */
  const logGrowth = async () => {
    if (!gContent.trim()) return;
    setBusy(true);
    try {
      const res = await api.petLogGrowth({ userId, petName: pet.petName, eventType: gType, content: gContent.trim() });
      if (!ok(res)) {
        flash("失败：" + errOf(res));
        return;
      }
      flash(res.message || "✅ 已记录");
      const view = await api.petGetGrowth(userId, pet.petName);
      if (ok(view)) setGView(view.data || "");
    } catch (e) {
      flash("失败：" + (e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  /** 记录花费并刷新汇总 */
  const logExpense = async () => {
    if (!eAmount) return;
    setBusy(true);
    try {
      const res = await api.petLogExpense({ userId, petName: pet.petName, category: eCat, amount: Number(eAmount), note: eNote.trim() });
      if (!ok(res)) {
        flash("失败：" + errOf(res));
        return;
      }
      flash(res.message || "✅ 已记录");
      const view = await api.petGetExpenses(userId, pet.petName);
      if (ok(view)) setEView(view.data || "");
    } catch (e) {
      flash("失败：" + (e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  /** 记录症状并刷新时间线 */
  const logSymptom = async () => {
    if (!sSymptom.trim()) return;
    setBusy(true);
    try {
      const res = await api.petLogSymptom({ userId, petName: pet.petName, symptom: sSymptom.trim(), severity: sSev, note: sNote.trim() });
      if (!ok(res)) {
        flash("失败：" + errOf(res));
        return;
      }
      flash(res.message || "✅ 已记录");
      const view = await api.petGetSymptoms(userId, pet.petName);
      if (ok(view)) setSView(view.data || "");
    } catch (e) {
      flash("失败：" + (e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="pet-grid">
      {/* 成长日记 */}
      <div className="pet-panel">
        <div className="pet-panel-title">📔 成长日记</div>
        <div className="pet-field-row">
          <label className="pet-field">
            <span>类型</span>
            <select value={gType} onChange={(e) => setGType(e.target.value)}>
              {EVENT_TYPES.map(([v, label]) => (
                <option key={v} value={v}>
                  {label}
                </option>
              ))}
            </select>
          </label>
        </div>
        <label className="pet-field">
          <span>内容</span>
          <textarea className="pet-textarea" rows={3} value={gContent} onChange={(e) => setGContent(e.target.value)} placeholder="如：第一次学会握手！" />
        </label>
        <div className="pet-panel-actions">
          <button className="btn send-btn" disabled={busy || !gContent.trim()} onClick={logGrowth}>
            记录
          </button>
          <button className="btn tool-btn" onClick={() => run(() => api.petGetGrowth(userId, pet.petName), (t) => setGView(t))}>
            查看
          </button>
        </div>
        {gView && <pre className="pet-text-block">{gView}</pre>}
      </div>

      {/* 花费 */}
      <div className="pet-panel">
        <div className="pet-panel-title">💰 花费记录</div>
        <div className="pet-field-row">
          <label className="pet-field">
            <span>类别</span>
            <select value={eCat} onChange={(e) => setECat(e.target.value)}>
              {EXPENSE_CATS.map(([v, label]) => (
                <option key={v} value={v}>
                  {label}
                </option>
              ))}
            </select>
          </label>
          <label className="pet-field">
            <span>金额 (元)</span>
            <input type="number" step="0.01" value={eAmount} onChange={(e) => setEAmount(e.target.value)} />
          </label>
        </div>
        <label className="pet-field">
          <span>备注</span>
          <input value={eNote} onChange={(e) => setENote(e.target.value)} placeholder="如：买了新玩具" />
        </label>
        <div className="pet-panel-actions">
          <button className="btn send-btn" disabled={busy || !eAmount} onClick={logExpense}>
            记录
          </button>
          <button className="btn tool-btn" onClick={() => run(() => api.petGetExpenses(userId, pet.petName), (t) => setEView(t))}>
            查看
          </button>
        </div>
        {eView && <pre className="pet-text-block">{eView}</pre>}
      </div>

      {/* 症状 */}
      <div className="pet-panel">
        <div className="pet-panel-title">📋 症状日记</div>
        <div className="pet-field-row">
          <label className="pet-field">
            <span>症状</span>
            <input value={sSymptom} onChange={(e) => setSSymptom(e.target.value)} placeholder="如：打喷嚏" />
          </label>
          <label className="pet-field">
            <span>程度</span>
            <select value={sSev} onChange={(e) => setSSev(e.target.value)}>
              {SEVERITIES.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          </label>
        </div>
        <label className="pet-field">
          <span>备注</span>
          <input value={sNote} onChange={(e) => setSNote(e.target.value)} placeholder="可选" />
        </label>
        <div className="pet-panel-actions">
          <button className="btn send-btn" disabled={busy || !sSymptom.trim()} onClick={logSymptom}>
            记录
          </button>
          <button className="btn tool-btn" onClick={() => run(() => api.petGetSymptoms(userId, pet.petName), (t) => setSView(t))}>
            查看
          </button>
        </div>
        {sView && <pre className="pet-text-block">{sView}</pre>}
      </div>
    </div>
  );
}

/* ============================================================
   定时 Tab：定时推送任务
   ============================================================ */
function ScheduleTab({
  pet,
  userId,
  flash,
}: {
  pet: Pet;
  userId: string;
  flash: (t: string) => void;
}) {
  const [timeExpr, setTimeExpr] = useState("");
  const [action, setAction] = useState("");
  const [list, setList] = useState<Schedule[]>([]);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    const res = await api.petListSchedules(userId, pet.petName);
    if (ok(res)) setList(res.data || []);
  }, [userId, pet.petName]);

  useEffect(() => {
    load();
  }, [load]);

  const create = async () => {
    if (!timeExpr.trim() || !action.trim()) {
      flash("请填写时间和提醒内容");
      return;
    }
    setBusy(true);
    try {
      const res = await api.petCreateSchedule({
        userId,
        petName: pet.petName,
        timeExpr: timeExpr.trim(),
        action: action.trim(),
      });
      if (ok(res)) {
        flash("✅ 定时任务已创建");
        setTimeExpr("");
        setAction("");
        load();
      } else {
        flash("创建失败：" + errOf(res));
      }
    } catch (e) {
      flash("创建失败：" + (e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const toggle = async (s: Schedule) => {
    const res = await api.petToggleSchedule(s.id, !s.enabled);
    if (ok(res)) load();
    else flash("操作失败：" + errOf(res));
  };

  const remove = async (s: Schedule) => {
    if (!window.confirm(`删除定时任务「${s.action}」？`)) return;
    const res = await api.petDeleteSchedule(s.id);
    if (ok(res)) {
      flash("已删除");
      load();
    } else {
      flash("删除失败：" + errOf(res));
    }
  };

  return (
    <div className="pet-panel pet-panel-wide">
      <div className="pet-panel-title">⏰ 定时推送</div>
      <div className="pet-form">
        <div className="pet-field-row">
          <label className="pet-field">
            <span>时间</span>
            <input value={timeExpr} onChange={(e) => setTimeExpr(e.target.value)} placeholder="如：每天早上8点 / 每晚8点半 / 每周一晚上9点" />
          </label>
          <label className="pet-field pet-field-grow">
            <span>提醒内容</span>
            <input value={action} onChange={(e) => setAction(e.target.value)} placeholder="如：带旺财去散步" />
          </label>
        </div>
        <div className="pet-panel-actions">
          <button className="btn send-btn" onClick={create} disabled={busy}>
            {busy ? "创建中…" : "＋ 创建定时"}
          </button>
        </div>
      </div>
      <div className="pet-subtle">💡 用大白话描述时间即可（如"每天早8点"），到点会通过已接入的推送渠道提醒。</div>

      <div className="pet-schedule-list">
        {list.length === 0 ? (
          <div className="pet-subtle">暂无定时任务。</div>
        ) : (
          list.map((s) => (
            <div key={s.id} className="pet-schedule-item">
              <div className="pet-schedule-info">
                <b>{s.action}</b>
                <span>
                  {s.timeExpr} · {s.enabled ? "🟢 启用中" : "⚪ 已暂停"}
                </span>
              </div>
              <div className="pet-schedule-ops">
                <button className="btn tool-btn" onClick={() => toggle(s)}>
                  {s.enabled ? "暂停" : "启用"}
                </button>
                <button className="btn pet-del" onClick={() => remove(s)}>
                  删除
                </button>
              </div>
            </div>
          ))
        )}
      </div>
    </div>
  );
}

/* ============================================================
   知识 Tab：有毒食物 + 年龄换算
   ============================================================ */
function KnowledgeTab({ pet }: { pet: Pet }) {
  const [food, setFood] = useState("");
  const [foodResult, setFoodResult] = useState<any>(null);
  const [busyFood, setBusyFood] = useState(false);

  const [age, setAge] = useState("");
  const [size, setSize] = useState("中型");
  const [ageResult, setAgeResult] = useState<any>(null);
  const [busyAge, setBusyAge] = useState(false);

  const species = pet.species || "狗";

  const checkFood = async () => {
    if (!food.trim()) return;
    setBusyFood(true);
    try {
      const res = await api.petToxicFood(species, food.trim());
      if (ok(res)) setFoodResult(res.data);
    } finally {
      setBusyFood(false);
    }
  };

  const calcAge = async () => {
    if (!age) return;
    setBusyAge(true);
    try {
      const res = await api.petAgeCalc(species, size, Number(age));
      if (ok(res)) setAgeResult(res.data);
    } finally {
      setBusyAge(false);
    }
  };

  return (
    <div className="pet-grid">
      {/* 有毒食物 */}
      <div className="pet-panel">
        <div className="pet-panel-title">🚫 有毒食物查询</div>
        <div className="pet-field-row">
          <label className="pet-field">
            <span>食物</span>
            <input value={food} onChange={(e) => setFood(e.target.value)} placeholder="如：巧克力 / 葡萄" />
          </label>
        </div>
        <div className="pet-panel-actions">
          <button className="btn send-btn" onClick={checkFood} disabled={busyFood || !food.trim()}>
            查询
          </button>
        </div>
        {foodResult && (
          <div className={`pet-food-result ${foodResult.isSafe ? "safe" : "toxic"}`}>
            <div className="pet-food-head">
              <b>{foodResult.foodName}</b>
              <span className={foodResult.isSafe ? "" : `lv-${foodResult.toxicityLevel}`}>
                {foodResult.isSafe ? "✅ 未收录/安全" : `⚠️ ${foodResult.toxicityLevel}`}
              </span>
            </div>
            {foodResult.symptoms && <div className="pet-food-line">症状：{foodResult.symptoms}</div>}
            {foodResult.explanation && <div className="pet-food-line">{foodResult.explanation}</div>}
          </div>
        )}
      </div>

      {/* 年龄换算 */}
      <div className="pet-panel">
        <div className="pet-panel-title">🎂 年龄换算</div>
        <div className="pet-field-row">
          <label className="pet-field">
            <span>宠物年龄（岁）</span>
            <input type="number" step="0.5" value={age} onChange={(e) => setAge(e.target.value)} />
          </label>
          {species === "狗" && (
            <label className="pet-field">
              <span>体型</span>
              <select value={size} onChange={(e) => setSize(e.target.value)}>
                <option value="小型">小型</option>
                <option value="中型">中型</option>
                <option value="大型">大型</option>
              </select>
            </label>
          )}
        </div>
        <div className="pet-panel-actions">
          <button className="btn send-btn" onClick={calcAge} disabled={busyAge || !age}>
            换算
          </button>
        </div>
        {ageResult && (
          <div className="pet-age-result">
            <div className="pet-age-main">
              <b>{ageResult.humanAge}</b> 岁（人类年龄）
            </div>
            <div className="pet-food-line">{ageResult.formula}</div>
          </div>
        )}
      </div>
    </div>
  );
}

/* ============================================================
   仪表盘 Tab：数据可视化（统计卡片 / 体重趋势 / 花费分布 / 健康评分）
   ============================================================ */
function DashboardTab({ pet, userId, flash }: { pet: Pet; userId: string; flash: (t: string) => void }) {
  const [weightData, setWeightData] = useState<{ date: string; kg: number }[]>([]);
  const [expenseData, setExpenseData] = useState<{ cat: string; pct: number; color: string }[]>([]);
  const [healthScore, setHealthScore] = useState<number | null>(null);
  const [diagCount, setDiagCount] = useState(0);
  const [schedCount, setSchedCount] = useState(0);

  const EXP_COLORS: Record<string, string> = { food: "#FF6B6B", medical: "#4ECDC4", grooming: "#FFD93D", toy: "#6C5CE7", other: "#A8A8A8" };

  useEffect(() => {
    api.petGetWeights(userId, pet.petName).then((res) => {
      if (ok(res) && res.data) {
        const pts = res.data.split("\n").filter((l: string) => l.includes("kg")).map((l: string) => {
          const m = l.match(/(\S+)\s*\|.*?([\d.]+)\s*kg/);
          return m ? { date: m[1], kg: parseFloat(m[2]) } : null;
        }).filter(Boolean).slice(-14) as { date: string; kg: number }[];
        setWeightData(pts);
      }
    }).catch(() => {});
    api.petGetExpenses(userId, pet.petName).then((res) => {
      if (ok(res) && res.data) {
        const cats: Record<string, number> = {};
        res.data.split("\n").forEach((l: string) => {
          Object.entries(EXP_COLORS).forEach(([k]) => { if (l.includes(k) || l.includes(EXPENSE_CATS.find(([v]) => v === k)?.[1] || "")) cats[k] = (cats[k] || 0) + 1; });
        });
        const total = Object.values(cats).reduce((a, b) => a + b, 0) || 1;
        setExpenseData(Object.entries(cats).map(([k, v]) => ({ cat: k, pct: Math.round(v / total * 100), color: EXP_COLORS[k] || "#ccc" })));
      }
    }).catch(() => {});
    api.petHealthScore(userId, pet.petName).then((res) => {
      if (ok(res) && res.data) { const m = res.data.match(/(\d+)\s*分/); if (m) setHealthScore(Number(m[1])); }
    }).catch(() => {});
    api.petDiagnoses(userId, pet.petName).then((res) => { if (ok(res)) setDiagCount(res.data?.length || 0); }).catch(() => {});
    api.petListSchedules(userId, pet.petName).then((res) => { if (ok(res)) setSchedCount(res.data?.length || 0); }).catch(() => {});
  }, [pet.id, userId, pet.petName]);

  const maxW = Math.max(...weightData.map((d) => d.kg), 1);
  const minW = Math.min(...weightData.map((d) => d.kg), 0);
  const rng = maxW - minW || 1;

  return (
    <div className="pet-dash">
      <div className="pet-dash-cards">
        <DashCard icon="⚖️" label="体重" value={pet.weightKg != null ? `${pet.weightKg} kg` : "--"} color="#6366f1" />
        <DashCard icon="🏥" label="健康分" value={healthScore != null ? `${healthScore}` : "--"} color={healthScore != null && healthScore >= 80 ? "#16A34A" : "#F59E0B"} />
        <DashCard icon="🩺" label="诊断" value={`${diagCount} 条`} color="#EC4899" />
        <DashCard icon="⏰" label="定时" value={`${schedCount} 个`} color="#0EA5E9" />
      </div>
      <div className="pet-dash-grid">
        <div className="pet-panel">
          <div className="pet-panel-title">📈 体重趋势</div>
          {weightData.length > 1 ? (
            <svg viewBox="0 0 360 140" className="pet-chart-svg">
              {[0, 1, 2, 3].map((i) => (
                <line key={i} x1={28} y1={8 + i * 32} x2={350} y2={8 + i * 32} stroke="#E5E7EB" strokeWidth="1" />
              ))}
              <polyline fill="none" stroke="#6366f1" strokeWidth="2" strokeLinecap="round"
                points={weightData.map((d, i) => `${28 + (i / Math.max(weightData.length - 1, 1)) * 322},${8 + (1 - (d.kg - minW) / rng) * 128}`).join(" ")} />
              {weightData.map((d, i) => {
                const cx = 28 + (i / Math.max(weightData.length - 1, 1)) * 322;
                const cy = 8 + (1 - (d.kg - minW) / rng) * 128;
                return (
                  <g key={i}>
                    <circle cx={cx} cy={cy} r="3.5" fill="#6366f1" />
                    <text x={cx} y={138} textAnchor="middle" fontSize="9" fill="#9CA3AF">{d.date.slice(-5)}</text>
                    <text x={cx} y={cy - 7} textAnchor="middle" fontSize="9" fill="#6366f1" fontWeight="600">{d.kg}</text>
                  </g>
                );
              })}
            </svg>
          ) : <div className="pet-subtle">记录体重后自动生成趋势图</div>}
        </div>
        <div className="pet-panel">
          <div className="pet-panel-title">💰 花费分布</div>
          {expenseData.length > 0 ? (
            <div className="pet-donut-row">
              <svg viewBox="0 0 100 100" width="100" height="100">
                <DonutSvg data={expenseData} />
              </svg>
              <div className="pet-donut-legend">
                {expenseData.map((e) => (
                  <div key={e.cat} className="pet-legend-item">
                    <span className="pet-legend-dot" style={{ background: e.color }} />
                    <span className="pet-legend-name">{EXPENSE_CATS.find(([v]) => v === e.cat)?.[1] || e.cat}</span>
                    <span className="pet-legend-pct">{e.pct}%</span>
                  </div>
                ))}
              </div>
            </div>
          ) : <div className="pet-subtle">记录花费后自动统计</div>}
        </div>
        <div className="pet-panel">
          <div className="pet-panel-title">🏥 健康评分</div>
          {healthScore != null ? (
            <div className="pet-gauge-wrap">
              <svg viewBox="0 0 140 80" width="140" height="80">
                <GaugeSvg value={healthScore} />
              </svg>
              <div className="pet-gauge-label">{healthScore >= 80 ? "🟢 健康" : healthScore >= 60 ? "🟡 注意" : "🔴 需关注"}</div>
            </div>
          ) : (
            <button className="btn tool-btn" onClick={async () => {
              const res = await api.petHealthScore(userId, pet.petName);
              if (ok(res)) { const m = res.data?.match(/(\d+)\s*分/); if (m) setHealthScore(Number(m[1])); else flash("未能解析评分"); }
              else flash("获取失败：" + errOf(res));
            }}>🔍 点击获取</button>
          )}
        </div>
      </div>
    </div>
  );
}

function DashCard({ icon, label, value, color }: { icon: string; label: string; value: string; color: string }) {
  return (
    <div className="pet-dash-card" style={{ borderTopColor: color }}>
      <span className="pet-dash-card-icon">{icon}</span>
      <div className="pet-dash-card-val">{value}</div>
      <div className="pet-dash-card-lbl">{label}</div>
    </div>
  );
}

function DonutSvg({ data }: { data: { cat: string; pct: number; color: string }[] }) {
  const total = data.reduce((s, d) => s + d.pct, 0) || 1;
  let cum = 0;
  const arcs = data.map((d) => {
    const start = (cum / total) * Math.PI * 2 - Math.PI / 2;
    cum += d.pct;
    const end = (cum / total) * Math.PI * 2 - Math.PI / 2;
    return { ...d, start, end };
  });
  return (
    <g transform="translate(50,50)">
      {arcs.map((a, i) => {
        const R = 38, ir = 18;
        const x1o = Math.cos(a.start) * R, y1o = Math.sin(a.start) * R;
        const x2o = Math.cos(a.end) * R, y2o = Math.sin(a.end) * R;
        const x1i = Math.cos(a.start) * ir, y1i = Math.sin(a.start) * ir;
        const x2i = Math.cos(a.end) * ir, y2i = Math.sin(a.end) * ir;
        const large = a.end - a.start > Math.PI ? 1 : 0;
        return (
          <path key={i}
            d={`M ${x1o} ${y1o} A ${R} ${R} 0 ${large} 1 ${x2o} ${y2o} L ${x2i} ${y2i} A ${ir} ${ir} 0 ${large} 0 ${x1i} ${y1i} Z`}
            fill={a.color} opacity="0.85" />
        );
      })}
      <text textAnchor="middle" dy="4" fontSize="12" fontWeight="700" fill="#374151">{data.length}类</text>
    </g>
  );
}

function EmptyState({ userId, onSwitch }: { userId: string; onSwitch: (id: string) => void }) {
  const [input, setInput] = useState("");
  const apply = () => {
    const trimmed = input.trim();
    if (!trimmed) return;
    localStorage.setItem("pet_uid", trimmed);
    const list = JSON.parse(localStorage.getItem("pet_uid_candidates") || "[]");
    if (!list.includes(trimmed)) { list.unshift(trimmed); localStorage.setItem("pet_uid_candidates", JSON.stringify(list.slice(0, 5))); }
    onSwitch(trimmed);
  };
  return (
    <div className="pet-empty">
      <div className="pet-empty-icon">🐾</div>
      <div>当前用户 <b>{userId}</b> 下还没有宠物</div>
      <div style={{ fontSize: 13, color: "var(--text-muted)", marginTop: 4 }}>
        桌面端与微信端共用数据库，若已有宠物请输入微信用户ID查找
      </div>
      <div style={{ display: "flex", gap: 8, marginTop: 10 }}>
        <input
          className="pet-uid-input"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && apply()}
          placeholder="微信用户ID，如 o9cq80zd..."
          style={{ padding: "6px 10px", width: 260, borderRadius: 8, border: "1px solid var(--border-strong)", fontSize: 12 }}
        />
        <button className="btn send-btn" onClick={apply}>查找</button>
      </div>
    </div>
  );
}

function GaugeSvg({ value }: { value: number }) {
  const v = Math.min(value, 100);
  const angle = ((v / 100) * 180 - 180) * Math.PI / 180;
  const x = 70 + Math.cos(angle) * 32;
  const y = 65 + Math.sin(angle) * 32;
  const color = v >= 80 ? "#16A34A" : v >= 60 ? "#F59E0B" : "#DC2626";
  return (
    <g>
      <path d="M 20 65 A 50 50 0 0 1 120 65" fill="none" stroke="#E5E7EB" strokeWidth="10" strokeLinecap="round" />
      <path d={`M 20 65 A 50 50 0 ${v > 50 ? 1 : 0} 1 ${x} ${y}`}
        fill="none" stroke={color} strokeWidth="10" strokeLinecap="round" />
      <circle cx={x} cy={y} r="5" fill="white" stroke={color} strokeWidth="2.5" />
      <text x="70" y="50" textAnchor="middle" fontSize="17" fontWeight="800" fill="#1A1D23">{v}</text>
      <text x="70" y="62" textAnchor="middle" fontSize="8" fill="#9CA3AF">分</text>
    </g>
  );
}
