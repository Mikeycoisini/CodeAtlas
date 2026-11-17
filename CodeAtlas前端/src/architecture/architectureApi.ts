export type ArchitectureEvidence = {
  id: string;
  fileId: string;
  filePath: string;
  symbolId: string | null;
  startLine: number;
  endLine: number;
  fileHash: string;
  rule: string;
};

export type ArchitectureNode = {
  id: string;
  kind: "SYSTEM" | "APPLICATION" | "MODULE" | "COMPONENT" | "EXTERNAL_SERVICE" | "DATASTORE";
  name: string;
  responsibility: string;
  technology: string;
  parentId: string | null;
  status: "CONFIRMED" | "INFERRED" | "UNRESOLVED";
  evidenceIds: string[];
  memberCount: number;
};

export type ArchitectureEdge = {
  id: string;
  source: string;
  target: string;
  kind: string;
  status: "CONFIRMED" | "INFERRED" | "UNRESOLVED";
  reason: string;
  evidenceIds: string[];
};

export type ArchitectureGroup = {
  id: string;
  kind: string;
  name: string;
  parentId: string | null;
  nodeIds: string[];
};

export type ArchitectureCoverage = {
  files: number;
  javaFiles: number;
  configFiles: number;
  modules: number;
  components: number;
  externalServices: number;
  dataStores: number;
  unclassifiedTypes: number;
  truncated: boolean;
};

export type ArchitecturePage = {
  schemaVersion: string;
  projectId: string;
  snapshotId: string;
  generationId: string | null;
  projectionId: string;
  digest: string;
  analyzerVersion: string;
  perspective: string;
  focusId: string;
  depth: number;
  limit: number;
  nodes: ArchitectureNode[];
  edges: ArchitectureEdge[];
  groups: ArchitectureGroup[];
  evidence: ArchitectureEvidence[];
  diagnostics: { code: string; message: string; fileId: string | null; filePath: string | null; startLine: number }[];
  coverage: ArchitectureCoverage;
  totalNodeCount: number;
  totalEdgeCount: number;
  omittedEdgeCount: number;
  truncated: boolean;
};

export type ArchitectureExport = {
  id: string;
  status: string;
  format: string;
  scope: string;
  theme: string;
  projectionId: string;
  artifactName: string;
  artifactSize: number;
  artifactSha256: string;
  downloadUrl: string;
};

export const architectureKinds: Record<string, string> = {
  SYSTEM: "系统",
  APPLICATION: "应用",
  MODULE: "业务模块",
  COMPONENT: "组件",
  EXTERNAL_SERVICE: "外部服务",
  DATASTORE: "数据存储",
};

export const architectureStatuses: Record<string, string> = {
  CONFIRMED: "已确认",
  INFERRED: "推断",
  UNRESOLVED: "未解析",
};

export const architectureEdgeKinds: Record<string, string> = {
  CONTAINS: "包含",
  DEPENDS_ON: "依赖",
  COMMUNICATES_WITH: "通信",
  READS_WRITES: "读写",
};
