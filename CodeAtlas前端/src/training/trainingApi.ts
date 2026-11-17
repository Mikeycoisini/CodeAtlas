import { api } from "../api";
import { mutate, type EvidenceSource, type Citation, type Provider } from "../learning/learningApi";

export type Format = "SINGLE_CHOICE" | "TRUE_FALSE" | "SHORT_ANSWER";
export type Question = { id: string; category: string; format: Format; prompt: string; options: { id: string; text: string }[];
  correctAnswer?: string | boolean | null; referenceExplanation?: string; hints?: string[]; hintCount?: number };
export type TrainingSource = EvidenceSource & { availability?: string; availabilityMessage?: string };
export type TrainingTask = { id: string; revision: number; title: string; goal: string; constraints: string; acceptanceCriteria: string;
  status: string; tags: string[]; sourceIds: string[]; sources: TrainingSource[]; questions: Question[]; generationId: string; definitionDigest: string };
export type Answer = { questionId: string; value: string | boolean | null; skipped: boolean };
export type Feedback = { strengths: string[]; gaps: string[]; explanation: string; suggestedVerdict: string; source: string;
  citations: Citation[]; uncertainties: string[] };
export type Candidate = { title: string; goal: string; constraints: string; acceptanceCriteria: string; questions: Question[]; citations: Citation[]; uncertainties: string[] };
export type TrainingGeneration = { id: string; contextId: string; operation: string; status: string; result: Candidate | Feedback | null;
  provider: Provider; errorCode?: string; message?: string };
export type Attempt = { id: string; revision: number; taskId: string; taskRevision: number; status: string; definitionDigest: string;
  submissionDigest: string; task: TrainingTask; answers: Answer[];
  results: { questionId: string; verdict: string; source: string; hintViewed: boolean; referenceViewed: boolean }[];
  reveals: { id: string; questionId: string; kind: string; hintIndex: number; content: string | boolean; explanation: string }[];
  selfAssessments: { id: string; questionId: string; rating: string; reflection: string }[];
  aiFeedback: { id: string; questionId: string; status: string; result: Feedback | null }[] };
export const formats: Record<Format, string> = { SINGLE_CHOICE: "单选题", TRUE_FALSE: "判断题", SHORT_ANSWER: "开放题" };
export const statuses: Record<string, string> = { DRAFT: "草稿", ACTIVE: "已发布", ARCHIVED: "已归档", SUBMITTED: "已提交", SKIPPED: "已跳过" };
export const verdicts: Record<string, string> = { CORRECT: "回答正确", INCORRECT: "回答错误", UNANSWERED: "未回答", SKIPPED: "已跳过", NEEDS_REVIEW: "待复盘", PARTIAL: "部分理解", UNSURE: "需人工核对" };
export const newQuestion = (): Question => ({ id: crypto.randomUUID(), category: "CODE_READING", format: "SHORT_ANSWER", prompt: "", options: [], correctAnswer: "", referenceExplanation: "", hints: [] });
export const blankTask = (): TrainingTask => ({ id: "", revision: 0, title: "", goal: "", constraints: "", acceptanceCriteria: "", status: "DRAFT", tags: [], sourceIds: [], sources: [], questions: [newQuestion()], generationId: "", definitionDigest: "" });
export function taskBody(task: TrainingTask, status = task.status) {
  const { title, goal, constraints, acceptanceCriteria, tags, sourceIds, questions, generationId, revision } = task;
  return { title, goal, constraints, acceptanceCriteria, tags: tags.map(item => item.trim()).filter(Boolean), sourceIds,
    questions: questions.map(item => ({ ...item, hints: item.hints?.map(hint => hint.trim()).filter(Boolean) ?? [] })), generationId, revision, status };
}
export function trainingUrl(values: Record<string, string>) {
  const params = new URLSearchParams(location.search);
  for (const key of [...params.keys()]) if (key.startsWith("training") || key === "aiGenerationId" || key === "recordId") params.delete(key);
  params.set("view", "training");
  for (const [key, value] of Object.entries(values)) if (value) params.set(key, value);
  history.pushState(null, "", `?${params}`);
  dispatchEvent(new PopStateEvent("popstate"));
}
export const training = <T>(route: string, signal?: AbortSignal) => api<T>(`/training${route}`, { signal });
export const change = <T>(route: string, body: unknown, method = "POST") => mutate<T>(`/training${route}`, body, method);
export const message = (error: unknown) => error instanceof Error ? error.message : String(error);
