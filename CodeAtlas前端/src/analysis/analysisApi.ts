export type AnalysisSource = {
  snapshotId?: string;
  generationId?: string;
  fileId: string;
  filePath: string;
  fileHash: string;
  startLine: number;
  endLine: number;
};

export type FlowNode = {
  id: string;
  kind: "START" | "END" | "STATEMENT" | "DECISION" | "LOOP" | "RETURN" | "THROW" | "TRY" | "CATCH" | "FINALLY" | "SWITCH" | "BRANCH" | "MERGE" | "JUMP";
  label: string;
  status: string;
  source: AnalysisSource;
};

export type FlowEdge = {
  id: string;
  source: string;
  target: string;
  kind: string;
  label: string;
  status: string;
};

export type FlowPage = {
  snapshotId: string;
  generationId: string;
  symbolId: string;
  signature: string;
  filePath: string;
  mode: "STATIC";
  nodes: FlowNode[];
  edges: FlowEdge[];
  warnings: string[];
  truncated: boolean;
};

export type SequenceParticipant = {
  id: string;
  name: string;
  kind: string;
  source?: { fileId: string; startLine: number; endLine: number };
};

export type SequenceMessage = {
  id: string;
  order: number;
  level: number;
  from: string;
  to: string;
  label: string;
  kind: string;
  status: string;
  cycle: boolean;
  evidence: { fileId: string; filePath: string; fileHash: string; startLine: number; startColumn: number; endLine: number; endColumn: number; sourceId: string; targetId: string };
};

export type SequencePage = {
  snapshotId: string;
  generationId: string;
  entry: { id: string; name: string; signature: string; fileId: string; startLine: number };
  participants: SequenceParticipant[];
  messages: SequenceMessage[];
  truncated: boolean;
  warnings: string[];
  mode: "STATIC";
};

export type UmlMember = {
  id: string;
  name: string;
  signature?: string;
  typeText?: string;
  returnType?: string;
  visibility: "PUBLIC" | "PROTECTED" | "PRIVATE" | "PACKAGE";
  modifiers: string[];
  annotations: string[];
  source: AnalysisSource;
};

export type UmlAssociation = {
  id: string;
  field: string;
  targetName: string;
  targetId: string;
  multiplicity: string;
  kind: string;
  status: string;
  composition: string;
  reason: string;
  source: AnalysisSource;
};

export type UmlPage = {
  snapshotId: string;
  generationId: string;
  type: { id: string; name: string; qualifiedName: string; kind: string; source: AnalysisSource };
  fields: UmlMember[];
  methods: UmlMember[];
  associations: UmlAssociation[];
  relations: { kind: string; status: string; targetId: string; targetText: string; source: AnalysisSource }[];
  totalFields: number;
  totalMethods: number;
  truncated: boolean;
  warnings: string[];
};

export const visibilityMarks: Record<string, string> = { PUBLIC: "+", PROTECTED: "#", PRIVATE: "-", PACKAGE: "~" };
export const flowKindLabels: Record<string, string> = {
  START: "开始", END: "结束", STATEMENT: "语句", DECISION: "条件", LOOP: "循环", RETURN: "返回",
  THROW: "异常", TRY: "try", CATCH: "catch", FINALLY: "finally", SWITCH: "switch", BRANCH: "分支",
  MERGE: "汇合", JUMP: "跳转",
};
