export type AiCapability = { id: string; name: string; requires: string };

export type AiStatus = {
  reachable: boolean;
  configured: boolean;
  provider?: string;
  model?: string;
  baseUrl?: string;
  fingerprint?: string;
  maxTokens?: number;
  message: string;
  code?: string;
  capabilities: AiCapability[];
};

export const capabilityTargets: Record<string, string> = {
  explanation: "源码解释",
  training: "训练",
  "practice-feedback": "训练中的实践反馈",
  guide: "引导",
  "project-note": "源码解释中的项目/技术说明",
};
