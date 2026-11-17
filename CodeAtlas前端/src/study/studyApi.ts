import { api, type Page } from "../api";
import { mutate, type EvidenceSource, type Citation, type Provider } from "../learning/learningApi";

export const get = <T>(path: string, signal?: AbortSignal) => api<T>(path, { signal });
export const write = mutate;
export const errorText = (error: unknown) => error instanceof Error ? error.message : String(error);
export type StudySource = EvidenceSource & { availability?: string; availabilityMessage?: string };
export type FrozenCard = { id: string; revision: number; question: string; answer?: string; explanation?: string; codeExample?: string;
  knowledgeId?: string; knowledgeRevision: number; sources: StudySource[]; sourceIds: string[]; contentDigest: string };
export type ReviewState = { id: string; cardId: string; epoch: number; revision: number; status: string; dueAtMs: number; suspended: boolean; card: FrozenCard };
export type ReviewEvent = { id: string; cardId: string; kind: string; epoch: number; stateRevision: number; occurredAtMs: number;
  algorithmId: string; configHash: string; payload: { card: FrozenCard; rating?: string; source?: string; recalledAnswer?: string; reflection?: string; dueAtMs?: number; configuration: unknown; before?: unknown; after?: unknown; algorithmLog?: unknown; configVersion?: string } };
export type ReviewAttempt = { id: string; cardId: string; epoch: number; revision: number; stateRevision: number; status: string;
  recalledAnswer: string; reflection: string; revealedAtMs?: number; card: FrozenCard; reviewEvent: ReviewEvent | null };
export type Evidence = { id: string; knowledgeId: string; knowledgeRevision: number; sourceType: string; sourceKey: string; retracted: boolean;
  occurredAtMs: number; payload: { source: string; rating?: string; verdict?: string; status?: string; reflection?: string; recalledAnswer?: string; assisted?: boolean; association: string;
  card?: FrozenCard; reviewEventId?: string; attemptId?: string; questionId?: string; question?: { prompt: string }; answer?: { value: string | boolean }; sources?: StudySource[];
  submissionId?: string; testResultId?: string; taskId?: string; sequenceNo?: number; changeCount?: number; contentDigest?: string;
  method?: string; environment?: string; report?: string; limitation?: string; changePaths?: string[] } };
export type Progress = { id: string; title: string; revision: number; historicalEvidenceCount: number; progressRevision: number;
  assessment: { status: string; reason: string; ruleVersion: string; positiveRecalls: number; unassistedAttempts: number; practicePasses: number; activeEvidenceCount: number }; evidence: Evidence[] };
export type GuideStep = { id: string; title: string; status: string; notes: string; advice: string; reason: string };
export type Guide = { id: string; revision: number; title: string; goal: string; status: string; steps: GuideStep[]; sourceIds: string[];
  knowledgeIds: string[]; sources: StudySource[]; knowledge: { id: string; title: string; revision: number }[]; doneCount: number; skippedCount: number;
  suggestionGenerationId?: string; suggestionSummary?: string };
export type GuideAdvice = { summary: string; steps: { stepId: string; reason: string; actionAdvice: string }[]; citations: Citation[]; uncertainties: string[]; source: string };
export type GuideGeneration = { id: string; contextId: string; operation: string; status: string; result: GuideAdvice | null; provider: Provider; errorCode?: string; message?: string };
export type StudyPage<T> = Page<T>;
export const gradeNames: Record<string, string> = { AGAIN: "忘记", HARD: "困难", GOOD: "记住", EASY: "简单", UNDERSTOOD: "已理解", PARTIAL: "部分理解", NEEDS_REVIEW: "建议复盘" };
export const practiceResultNames: Record<string, string> = { PASSED: "测试通过", FAILED: "测试未通过", ERROR: "环境错误", TIMEOUT: "测试超时" };
export const progressNames: Record<string, string> = { NO_EVIDENCE: "暂无记录", PRACTICING: "练习中", NEEDS_REVIEW: "建议复盘", SUPPORTED: "已有支持证据" };
export const guideNames: Record<string, string> = { ACTIVE: "进行中", PAUSED: "已暂停", EXITED: "已退出", COMPLETED: "已结束" };
export function studyUrl(view: string, values: Record<string, string> = {}) {
  const params = new URLSearchParams(location.search);
  for (const key of [...params.keys()]) if (/^(review|progress|guide|training)/.test(key) || ["recordId", "aiGenerationId"].includes(key)) params.delete(key);
  params.set("view", view); for (const [key, value] of Object.entries(values)) if (value) params.set(key, value);
  history.pushState(null, "", `?${params}`); dispatchEvent(new PopStateEvent("popstate"));
}
export const timeText = (value: number, zone = "Asia/Shanghai") => new Date(value).toLocaleString("zh-CN", { timeZone: zone, hour12: false });
