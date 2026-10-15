// 宠物板块 API 层 — 对接后端 /api/pets/**
// 浏览器走同源代理，Tauri 保留本机后端配置。
import { API_BASE_URL as BASE_URL } from '../api/baseUrl';

async function req(url, options = {}) {
  const resp = await fetch(`${BASE_URL}${url}`, {
    headers: { "Content-Type": "application/json", ...(options.headers || {}) },
    ...options,
  });
  if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
  return resp.json();
}

const qs = (obj = {}) => {
  const s = new URLSearchParams(
    Object.entries(obj).filter(([, v]) => v !== undefined && v !== null && v !== ""),
  ).toString();
  return s ? `?${s}` : "";
};

// ===== 宠物档案 =====
export const petRegister = (p) => req("/api/pets", { method: "POST", body: JSON.stringify(p) });
export const petList = (userId, name) => req(`/api/pets${qs({ userId, name })}`);
export const petGet = (id, userId) => req(`/api/pets/${id}${qs({ userId })}`);
export const petUpdate = (id, p) => req(`/api/pets/${id}`, { method: "PUT", body: JSON.stringify(p) });
export const petDelete = (id, userId) => req(`/api/pets/${id}${qs({ userId })}`, { method: "DELETE" });

// ===== 智能诊断 =====
// image 诊断：multipart 上传（File + 表单字段）
export function petDiagnose({ image, userId, petName, concern }) {
  const form = new FormData();
  form.append("image", image);
  form.append("userId", userId);
  form.append("petName", petName);
  form.append("concern", concern || "");
  return fetch(`${BASE_URL}/api/pets/diagnose`, { method: "POST", body: form }).then((r) => r.json());
}
export const petSymptomCheck = (p) =>
  req("/api/pets/symptom-check", { method: "POST", body: JSON.stringify(p) });
export const petDiagnoses = (userId, petName) => req(`/api/pets/diagnoses${qs({ userId, petName })}`);

// ===== 定时推送 =====
export const petCreateSchedule = (p) =>
  req("/api/pets/schedules", { method: "POST", body: JSON.stringify(p) });
export const petListSchedules = (userId, petName) =>
  req(`/api/pets/schedules${qs({ userId, petName })}`);
export const petDeleteSchedule = (id) => req(`/api/pets/schedules/${id}`, { method: "DELETE" });
export const petToggleSchedule = (id, enabled) =>
  req(`/api/pets/schedules/${id}`, { method: "PUT", body: JSON.stringify({ enabled }) });

// ===== 养宠知识 =====
export const petToxicFood = (species, food) => req(`/api/pets/toxic-foods${qs({ species, food })}`);
export const petAgeCalc = (species, size, age) => req(`/api/pets/age-calc${qs({ species, size, age })}`);

// ===== 健康记录（GET 返回格式化文本，直接展示） =====
export const petLogWeight = (p) => req("/api/pets/weights", { method: "POST", body: JSON.stringify(p) });
export const petGetWeights = (userId, petName) => req(`/api/pets/weights${qs({ userId, petName })}`);
export const petLogVaccine = (p) => req("/api/pets/vaccines", { method: "POST", body: JSON.stringify(p) });
export const petGetVaccines = (userId, petName) => req(`/api/pets/vaccines${qs({ userId, petName })}`);
export const petLogDeworming = (p) => req("/api/pets/dewormings", { method: "POST", body: JSON.stringify(p) });
export const petGetDewormings = (userId, petName) => req(`/api/pets/dewormings${qs({ userId, petName })}`);
export const petLogMedication = (p) => req("/api/pets/medications", { method: "POST", body: JSON.stringify(p) });
export const petGetMedications = (userId, petName) => req(`/api/pets/medications${qs({ userId, petName })}`);

// ===== 成长记录 =====
export const petLogGrowth = (p) => req("/api/pets/growth", { method: "POST", body: JSON.stringify(p) });
export const petGetGrowth = (userId, petName) => req(`/api/pets/growth${qs({ userId, petName })}`);

// ===== 花费管理 =====
export const petLogExpense = (p) => req("/api/pets/expenses", { method: "POST", body: JSON.stringify(p) });
export const petGetExpenses = (userId, petName) => req(`/api/pets/expenses${qs({ userId, petName })}`);

// ===== 症状日记 =====
export const petLogSymptom = (p) => req("/api/pets/symptoms", { method: "POST", body: JSON.stringify(p) });
export const petGetSymptoms = (userId, petName) => req(`/api/pets/symptoms${qs({ userId, petName })}`);

// ===== 健康评分 =====
export const petHealthScore = (userId, petName) => req(`/api/pets/health-score${qs({ userId, petName })}`);
