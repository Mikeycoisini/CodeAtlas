import { api, type Page } from "../api";

export type LearningKind = "documents" | "knowledge" | "cards";
export type SourceSelection = { snapshotId: string; generationId?: string; fileId?: string; symbolId?: string; startLine?: number; endLine?: number };
export type EvidenceSource = SourceSelection & { id: string; sourceId?: string; projectId: string; fileId: string; filePath: string;
  sha256: string; startLine: number; endLine: number; symbolName: string; content: string; snapshotOutdated: boolean; currentSourceStatus?: "UNCHECKED" };
export type SourceCheck = { sourceId: string; status: "MATCHED" | "CHANGED" | "MISSING" | "UNAVAILABLE" | "UNSAFE" | "LIMIT_EXCEEDED";
  checkedAt: string; message: string; snapshotSha256: string; currentSha256?: string };
export type LearningRecord = { id: string; kind: string; title: string; body?: string; question?: string; answer?: string;
  explanation?: string; codeExample?: string; notes?: string; knowledgeId?: string | null; knowledgeDeleted?: boolean;
  generationId?: string | null; type?: string; tags: string[]; sourceIds: string[]; sources: EvidenceSource[];
  revision: number; createdAt: string; updatedAt: string };
export type Provider = { provider: string; model: string; baseUrl: string; fingerprint: string; configured: boolean; maxTokens: number };
export type PreparedContext = { id: string; question: string; depth: string; purpose: string; sources: EvidenceSource[];
  history: { question: string; answer: string }[]; provider: Provider; expiresAt: string; characterCount: number; byteCount: number; contextJson: string };
export type Citation = { sourceId: string; filePath: string; startLine: number; endLine: number; explanation: string;
  verificationStatus: "VERIFIED" | "INVALID"; verificationReason: string; source?: EvidenceSource };
export type Explanation = { title: string; summary: string; keyPoints: string[]; explanation: string; citations: Citation[];
  uncertainties: string[]; usage: { prompt_tokens?: number; completion_tokens?: number; total_tokens?: number } };
export type Generation = { id: string; contextId: string; status: string; result: Explanation | null; provider: Provider;
  errorCode?: string | null; message?: string | null; outboundStartedAt?: string | null };
export const names: Record<LearningKind, string> = { documents: "文档", knowledge: "知识", cards: "卡片" };
export const mutate = <T>(path: string, body: unknown, method = "POST") => api<T>(path, { method, body: JSON.stringify(body) });
export const listRecords = (kind: LearningKind, query: string, offset: number, signal: AbortSignal) =>
  api<Page<LearningRecord>>(`/${kind}?query=${encodeURIComponent(query)}&offset=${offset}&limit=30`, { signal });
export function explanationBody(result: Explanation): string {
  return [result.summary, ...result.keyPoints.map(point => `- ${point}`), result.explanation,
    result.uncertainties.length ? `需要核对\n${result.uncertainties.map(point => `- ${point}`).join("\n")}` : ""].filter(Boolean).join("\n\n");
}
export function recordUrl(kind: LearningKind, id = ""): void {
  const params = new URLSearchParams(location.search);
  params.set("view", kind);
  if (id) params.set("recordId", id); else params.delete("recordId");
  params.delete("aiGenerationId");
  history.pushState(null, "", `?${params}`);
}
