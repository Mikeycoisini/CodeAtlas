export type Page<T> = {
  items: T[];
  total: number;
  generationId: string | null;
  limit: number;
  offset: number;
};
export type Project = { id: string; name: string; sourcePath: string };
export type Task = {
  id: string;
  kind: "IMPORT" | "INDEX";
  status: string;
  progress: number;
  total: number;
  sourcePath: string | null;
  snapshotId: string | null;
  generationId: string | null;
  message: string | null;
  errorCode: string | null;
  cancelRequested: boolean;
};
export type Snapshot = {
  id: string;
  projectId: string;
  manifestHash: string;
  sourceKind: string;
  commitHash: string | null;
  createdAt: string;
  publishedGeneration: string | null;
  latestTask: Task | null;
};
export type SourceFile = {
  id: string;
  path: string;
  byteSize: number;
  sha256: string;
};
export type Symbol = {
  id: string;
  fileId: string;
  kind: string;
  name: string;
  qualifiedName: string;
  signature: string;
  startLine: number;
  endLine: number;
  startColumn: number;
  endColumn: number;
  annotations: string[];
};
export type RelationCorrection = {
  id: string;
  snapshotId: string;
  generationId: string;
  relationId: string;
  verdict: "CORRECTED" | "REJECTED" | "UNCERTAIN";
  correctedKind: string | null;
  correctedTargetText: string | null;
  reason: string;
  source: string | null;
  createdAt: string;
};
export type Relation = {
  id: string;
  sourceId: string;
  targetId: string | null;
  targetText: string;
  kind: string;
  status: string;
  reason: string;
  fileId: string;
  startLine: number;
  endLine: number;
  corrections?: RelationCorrection[];
  latestCorrection?: RelationCorrection | null;
  effectiveVerdict?: RelationCorrection["verdict"] | null;
  effectiveKind?: string;
  effectiveTargetText?: string;
};
export type Diagnostic = {
  fileId: string;
  code: string;
  message: string;
  startLine: number;
};
export type Content = {
  fileId: string;
  snapshotId: string;
  content: string;
  sha256: string;
  path: string;
  lineCount: number;
};
let session: Promise<string> | undefined;
function token(): Promise<string> {
  session ??= fetch("/api/session")
    .then(async (response) => {
      if (!response.ok)
        throw new Error("无法连接本地服务，请检查后端与数据库状态");
      return ((await response.json()) as { token: string }).token;
    })
    .catch((error) => {
      session = undefined;
      throw error;
    });
  return session;
}
export async function api<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const response = await fetch(`/api${path}`, {
    ...options,
    headers: {
      "X-CodeAtlas-Token": await token(),
      "Content-Type": "application/json",
      ...options.headers,
    },
  });
  let body;
  try {
    body = await response.json();
  } catch (error) {
    if (options.signal?.aborted) throw error;
    throw new Error("服务响应无效，请检查本地服务状态");
  }
  options.signal?.throwIfAborted();
  if (!response.ok) {
    if (response.status === 401) session = undefined;
    throw new Error(
      `${body.message ?? "请求失败"}${body.code ? ` · ${body.code}` : ""}`,
    );
  }
  return body as T;
}
export const post = <T>(path: string, body = {}) =>
  api<T>(path, { method: "POST", body: JSON.stringify(body) });
export const active = (task: Task | null) =>
  !!task && ["QUEUED", "RUNNING"].includes(task.status);
export const statusText: Record<string, string> = {
  QUEUED: "等待处理",
  RUNNING: "处理中",
  SUCCEEDED: "已完成",
  PARTIAL_SUCCESS: "部分成功",
  FAILED: "失败",
  CANCELLED: "已取消",
  CONFIRMED: "已确认",
  INFERRED: "推断",
  UNRESOLVED: "未解析",
};
export async function allFiles(
  snapshot: string,
  signal: AbortSignal,
): Promise<SourceFile[]> {
  const result: SourceFile[] = [];
  let total = 1;
  while (result.length < total) {
    const page = await api<Page<SourceFile>>(
      `/snapshots/${snapshot}/files?limit=1000&offset=${result.length}`,
      { signal },
    );
    result.push(...page.items);
    total = page.total;
    if (!page.items.length) break;
  }
  return result;
}
