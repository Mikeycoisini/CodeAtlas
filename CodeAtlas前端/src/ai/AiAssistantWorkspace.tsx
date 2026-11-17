import { useEffect, useState } from "react";
import { api } from "../api";
import { capabilityTargets, type AiStatus } from "./aiApi";
import "./ai-assistant.css";

export default function AiAssistantWorkspace({ hasSource, onOpenAi, onNavigate, theme }: {
  hasSource: boolean; onOpenAi: () => void; onNavigate: (view: "training" | "guides") => void; theme: "light" | "dark";
}) {
  const [status, setStatus] = useState<AiStatus | null>(null);
  const [error, setError] = useState("");
  useEffect(() => {
    const controller = new AbortController();
    api<AiStatus>("/ai/status", { signal: controller.signal }).then(setStatus)
      .catch(reason => { if (!controller.signal.aborted) setError(String(reason.message)); });
    return () => controller.abort();
  }, []);
  const openForCapability = (id: string) => {
    if (id === "training" || id === "practice-feedback") { onNavigate("training"); return; }
    if (id === "guide") { onNavigate("guides"); return; }
    if (hasSource) onOpenAi();
  };
  return <main className={`ai-assistant ai-${theme}`}>
    <header className="ai-header">
      <div><p className="ai-eyebrow">UNIFIED AI ASSISTANT</p><h1>AI 助手</h1><p className="ai-muted">解释、出题、实践反馈与引导共用固定源码身份和原有授权链路。</p></div>
      <div className={`ai-status ${status?.reachable && status.configured ? "ok" : status?.reachable ? "warn" : "offline"}`}>
        <i/><div><strong>{status ? status.reachable ? status.configured ? "AI 服务已就绪" : "AI 服务已连接，未配置模型" : "AI 服务未连接" : "正在检查 AI 服务…"}</strong>
          <small>{status?.message ?? (error || "只读取本机公开配置，不展示内部令牌或模型密钥")}</small></div>
      </div>
    </header>
    {status?.reachable && <section className="ai-provider"><span>提供方 <strong>{status.provider}</strong></span><span>模型 <strong>{status.model}</strong></span><span>服务地址 <strong>{status.baseUrl}</strong></span>{status.maxTokens && <span>输出上限 <strong>{status.maxTokens}</strong></span>}</section>}
    <section className="ai-cards">
      {status?.capabilities.map(capability => <article className="ai-card" key={capability.id}>
        <div className="ai-card-top"><span className="ai-card-index">{String(status.capabilities.indexOf(capability) + 1).padStart(2, "0")}</span><span className="ai-card-target">{capabilityTargets[capability.id] ?? "现有能力"}</span></div>
        <h2>{capability.name}</h2><p>{capability.requires}</p>
        <button disabled={!status.reachable && capability.id !== "training" && capability.id !== "practice-feedback" && capability.id !== "guide"}
          onClick={() => openForCapability(capability.id)}>
          {capability.id === "explanation" || capability.id === "project-note" ? hasSource ? "打开当前源码 AI" : "先选择源码" : "前往" + capabilityTargets[capability.id]}
        </button>
      </article>)}
    </section>
    <section className="ai-notes">
      <article><h3>固定身份</h3><p>切换入口不会静默跨项目；解释、出题、反馈和引导继续使用当前快照／代次与已选源码。</p></article>
      <article><h3>离线与授权</h3><p>AI 服务离线时仍可管理手工知识、卡片和文档；每个生成请求仍需在看板中确认来源与授权范围。</p></article>
      <article><h3>前置条件</h3><ul><li>解释：选择一个方法、类型或文件范围。</li><li>出题：选择固定源码来源。</li><li>实践反馈：完成一次代码练习提交。</li><li>引导：已有引导草稿和来源。</li></ul></article>
    </section>
  </main>;
}
