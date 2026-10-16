/** 补充学习使用独立记录；视频外跳、自述与自检结果不合并成掌握度。 */
export type StudyBudget = 5 | 10 | 20;
export type VideoFeedback = 'helpful' | 'not_helpful' | 'too_long' | 'tried' | 'opened';
export interface SupplementaryVideo {
  id: string; url: string; title: string; author: string; durationSeconds: number;
  reason: string; goals: string[]; purpose: string; verificationScope: 'metadata'; checkedAt: string;
  sourceKind?: 'bilibili-public-api' | 'verified-metadata-cache'; sourceLabel?: string;
}
export interface RecommendationResponse {
  status: 'idle' | 'queued' | 'running' | 'ready' | 'hidden' | 'error';
  contentFingerprint: string; decisionId?: string; sceneId: string; budgetMinutes: StudyBudget;
  classification?: { status: 'eligible' | 'ineligible' | 'uncertain'; purpose: string; reason: string; goals: string[] };
  items: SupplementaryVideo[]; nextPollMs?: number; updatedAt?: string;
}
export interface LearningAnalysis {
  goal: string; status: 'covered' | 'missing' | 'conflict' | 'uncertain';
  feedback: string; anchorId?: string; quote?: string;
}
export interface LearningHint { level: number; text: string; anchorId?: string; quote?: string }
export interface LearningAttempt {
  id: string; courseId: string; sceneId: string; contentFingerprint: string; parentAttemptId?: string;
  understanding: string; checkPoint: string; createdAt: string;
  status: 'saved' | 'checking' | 'checked' | 'unconfirmed' | 'stale';
  analysis?: LearningAnalysis[]; summary?: string; hints?: LearningHint[]; hintLevel?: number;
  selfCheck?: { id: string; type: 'single' | 'multiple' | 'judge' | 'fill' | 'short_answer'; question: string; options?: { label: string; value: string }[]; hasAnswer: boolean };
  selfCheckResult?: { correct: boolean | null; correctAnswer?: string; explanation: string };
  selfCheckSubmissionId?: string; selfCheckAnswer?: string;
  suggestedGoals?: string[]; error?: string;
}
export interface LearningHistory { contentFingerprint: string; attempts: LearningAttempt[] }
export interface SaveLearningAttempt {
  sceneId: string; understanding: string; checkPoint: string; parentAttemptId?: string;
  expectedContentFingerprint?: string; requestId: string;
}
