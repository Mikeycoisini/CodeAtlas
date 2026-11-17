import type { EvidenceSource } from "../learning/learningApi";

export type GraphView = "PACKAGE" | "TYPE" | "CLASS" | "STRUCTURE" | "CALL";
export type GraphSource = { projectId: string; snapshotId: string; generationId: string; fileId: string; symbolId: string;
  filePath: string; fileHash: string; startLine: number; startColumn: number; endLine: number; endColumn: number };
export type GraphMember = { id: string; kind: string; name: string; signature: string; visibility: string; typeText: string };
export type GraphNode = { id: string; kind: string; label: string; qualifiedName: string; source: GraphSource | null;
  memberCount: number; packageId: string | null; ownershipUnknown: boolean; members?: GraphMember[] };
export type GraphEdge = { id: string; source: string; target: string; kind: string; status: string; reason: string; evidenceCount: number };
export type GraphPage = { projectId: string; snapshotId: string; generationId: string | null; view: GraphView; nodes: GraphNode[]; edges: GraphEdge[];
  totalNodeCount: number; totalEdgeCount: number; outsideScopeRelationCount: number; outsidePageRelationCount: number; omittedEdgeCount: number;
  truncated: boolean; offset: number; limit: number; edgeLimit: number; neighborTotal: number; neighborLimit: number; diagnosticCount: number };
export type GraphRelationCorrection = { id: string; verdict: "CORRECTED" | "REJECTED" | "UNCERTAIN";
  correctedKind: string | null; correctedTargetText: string | null; reason: string; source: string | null; createdAt: string };
export type GraphEvidence = { relation: { id: string; kind: string; status: string; reason: string; resolver: string; targetText: string };
  source: GraphSource; target: GraphSource | null; corrections?: GraphRelationCorrection[];
  latestCorrection?: GraphRelationCorrection | null; effectiveVerdict?: string | null };
export const relationKinds: Record<string, string> = { CALL: "静态调用", RUNTIME_CALL: "运行时调用", EXTENDS: "继承", IMPLEMENTS: "接口实现", TRANSACTION_BOUNDARY: "事务候选" };
export const edgeKindLabels: Record<string, string> = { ...relationKinds, CONTAINS: "包结构" };
export const evidenceStates: Record<string, string> = { CONFIRMED: "已确认", INFERRED: "推断", UNRESOLVED: "未解析" };
export const correctionVerdicts: Record<string, string> = { CORRECTED: "用户修正", REJECTED: "用户标记不成立", UNCERTAIN: "用户标记存疑" };
export function sourceEvidence(source: GraphSource): EvidenceSource {
  return { ...source, id: source.symbolId, sha256: source.fileHash, symbolName: "", content: "", snapshotOutdated: false };
}
export function graphNavigate(values: Record<string, string>, replace = false) {
  const params = new URLSearchParams(location.search);
  params.set("view", "graph");
  for (const [key, value] of Object.entries(values)) { if (value) params.set(key, value); else params.delete(key); }
  history[replace ? "replaceState" : "pushState"](null, "", `?${params}`);
  dispatchEvent(new PopStateEvent("popstate"));
}
