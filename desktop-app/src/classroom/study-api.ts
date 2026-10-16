import { classroomRequest } from './api';
import type { LearningAttempt, LearningHistory, RecommendationResponse, SaveLearningAttempt, StudyBudget, VideoFeedback } from './study-types';

const coursePath = (id: string) => `/courses/${encodeURIComponent(id)}`;
const attemptPath = (id: string) => `/learning-attempts/${encodeURIComponent(id)}`;
const post = <T,>(path: string, body: unknown, signal?: AbortSignal) => classroomRequest<T>(path, { method: 'POST', body: JSON.stringify(body), signal });
export const getRecommendations = (courseId: string, sceneId: string, signal?: AbortSignal) => classroomRequest<RecommendationResponse>(`${coursePath(courseId)}/recommendations?sceneId=${encodeURIComponent(sceneId)}`, { signal });
export const refreshRecommendations = (courseId: string, sceneId: string, budgetMinutes: StudyBudget, signal?: AbortSignal, manual = false) => post<RecommendationResponse>(`${coursePath(courseId)}/recommendations/refresh`, { sceneId, budgetMinutes, ...(manual ? { manual: true } : {}) }, signal);
export const sendVideoFeedback = (courseId: string, decisionId: string, resourceId: string, feedback: VideoFeedback, requestId: string, signal?: AbortSignal) => post<unknown>(`${coursePath(courseId)}/recommendations/feedback`, { decisionId, resourceId, feedback, requestId }, signal);
export const getLearningAttempts = (courseId: string, sceneId: string, signal?: AbortSignal) => classroomRequest<LearningHistory>(`${coursePath(courseId)}/learning-attempts?sceneId=${encodeURIComponent(sceneId)}`, { signal });
export const saveLearningAttempt = (courseId: string, body: SaveLearningAttempt, signal?: AbortSignal) => post<LearningAttempt>(`${coursePath(courseId)}/learning-attempts`, body, signal);
export const checkLearningAttempt = (id: string, requestId: string, signal?: AbortSignal) => post<LearningAttempt>(`${attemptPath(id)}/check`, { requestId }, signal);
export const getLearningHint = (id: string, level: number, signal?: AbortSignal) => post<LearningAttempt>(`${attemptPath(id)}/hint`, { level }, signal);
export const prepareSelfCheck = (id: string, signal?: AbortSignal) => post<LearningAttempt>(`${attemptPath(id)}/self-check`, {}, signal);
export const submitSelfCheck = (id: string, answer: string, submissionId: string, signal?: AbortSignal) => post<LearningAttempt>(`${attemptPath(id)}/self-check`, { answer, submissionId }, signal);
