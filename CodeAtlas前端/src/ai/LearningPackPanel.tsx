import { useEffect, useRef, useState } from "react";
import { statusText } from "../api";
import { mutate, type LearningRecord, type PreparedContext, type Provider, type SourceSelection } from "../learning/learningApi";
import AiConsentPreview from "../learning/AiConsentPreview";
import { get, type Guide, type GuideAdvice } from "../study/studyApi";
import type { Candidate } from "../training/trainingApi";
import "./ai-assistant.css";

type PackKind = "training" | "guide";
type PackGeneration = { id: string; contextId: string; status: string; result: Candidate | GuideAdvice | null;
  provider: Provider; errorCode?: string | null; message?: string | null };
const active = (generation: PackGeneration | null) => !!generation && ["QUEUED", "RUNNING"].includes(generation.status);

export default function LearningPackPanel({ kind, selection, selections, goal, questionCount, heading, onClose, onDone, onOpenLibrary }: {
  kind: PackKind; selection?: SourceSelection; selections?: SourceSelection[]; goal?: string; questionCount?: number; heading?: string;
  onClose: () => void; onDone: (kind: PackKind, id: string) => void; onOpenLibrary?: (kind: "knowledge" | "cards") => void;
}) {
  const chosen = selections && selections.length ? selections : selection ? [selection] : [];
  const [provider, setProvider] = useState<Provider | null>(null);
  const [context, setContext] = useState<PreparedContext | null>(null);
  const [generation, setGeneration] = useState<PackGeneration | null>(null);
  const [guideId, setGuideId] = useState("");
  const [guideRevision, setGuideRevision] = useState(0);
  const [agreed, setAgreed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [applied, setApplied] = useState<{ trainingId: string; knowledge: number; cards: number } | null>(null);
  const started = useRef(false);
  const generationKey = useRef(crypto.randomUUID());
  const applyKey = useRef(crypto.randomUUID());
  const knowledgeKeys = useRef<string[]>([]);
  const cardKeys = useRef<string[]>([]);
  const resetKeys = () => {
    knowledgeKeys.current = Array.from({ length: 8 }, () => crypto.randomUUID());
    cardKeys.current = Array.from({ length: 8 }, () => crypto.randomUUID());
  };
  const report = (reason: unknown) => setError(reason instanceof Error ? reason.message : String(reason));

  useEffect(() => {
    get<Provider>("/ai/provider").then(setProvider).catch(report);
  }, []);
  const prepare = async () => {
    setBusy(true); setError(""); setAgreed(false); setNotice(""); setApplied(null);
    generationKey.current = crypto.randomUUID();
    applyKey.current = crypto.randomUUID();
    resetKeys();
    try {
      const sources = await Promise.all(chosen.map(item => mutate<{ id: string }>("/sources", item)));
      const sourceIds = sources.map(item => item.id);
      if (!sourceIds.length) throw new Error("未选择源码来源");
      if (kind === "training") {
        const prepared = await mutate<PreparedContext>("/training/ai/contexts", {
          sourceIds,
          goal: goal ?? "从所选源码理解实现、边界与风险，并生成可复习的理解题",
          questionCount: questionCount ?? 3,
          formats: ["SINGLE_CHOICE", "TRUE_FALSE", "SHORT_ANSWER"],
        });
        setContext(prepared);
      } else {
        const guide = guideId ? await get<Guide>(`/guides/${guideId}`) : await mutate<Guide>("/guides", {
          title: `AI 学习引导 · ${new Date().toLocaleDateString("zh-CN")}`,
          goal: goal ?? "从所选源码建立六步学习路径，并保留真实执行证据",
          sourceIds,
          knowledgeIds: [],
          idempotencyKey: crypto.randomUUID(),
        });
        setGuideId(guide.id);
        setGuideRevision(guide.revision);
        const prepared = await mutate<PreparedContext>(`/guides/${guide.id}/ai-context`, {
          revision: guide.revision, sourceIds, knowledgeIds: [],
        });
        setContext(prepared);
      }
    } catch (reason) { report(reason); }
    finally { setBusy(false); }
  };
  useEffect(() => {
    if (started.current) return;
    started.current = true;
    void prepare();
  }, []);
  useEffect(() => {
    if (!active(generation)) return;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      try {
        const next = await get<PackGeneration>(`/ai/generations/${generation!.id}`, controller.signal);
        if (controller.signal.aborted) return;
        setGeneration(next);
        if (!active(next)) return;
      } catch (reason) { if (controller.signal.aborted) return; report(reason); }
      timer = setTimeout(poll, 700);
    };
    timer = setTimeout(poll, 400);
    return () => { controller.abort(); clearTimeout(timer); };
  }, [generation?.id]);
  const consent = async (decision: "ALLOW" | "DENY") => {
    if (!context) return;
    setBusy(true); setError("");
    try {
      const result = await mutate<{ id: string }>("/ai/consents", { contextId: context.id, decision });
      if (decision === "DENY") {
        setContext(null); setAgreed(false); setNotice("已拒绝发送，本次没有调用模型。");
        return;
      }
      const startedGeneration = await mutate<PackGeneration>("/ai/generations", { consentId: result.id, idempotencyKey: generationKey.current });
      setGeneration(startedGeneration);
    } catch (reason) { report(reason); }
    finally { setBusy(false); }
  };
  const apply = async () => {
    if (!generation || applied) return;
    setBusy(true); setError("");
    try {
      if (kind === "training") {
        const candidate = generation.result as Candidate;
        const sourceIds = context?.sources.map(item => item.sourceId ?? item.id) ?? [];
        const first = context?.sources[0];
        const points = candidate.knowledge?.length ? candidate.knowledge : [{
          title: candidate.title,
          body: candidate.goal,
          code: first?.content?.slice(0, 4_000) ?? "",
          analysis: candidate.acceptanceCriteria,
          sourceId: first?.sourceId ?? first?.id ?? "",
          filePath: first?.filePath,
          startLine: first?.startLine,
          endLine: first?.endLine,
        }];
        for (let index = 0; index < points.length; index++) {
          const point = points[index];
          const pointer = point.filePath && point.startLine
            ? `${point.filePath} · L${point.startLine}–L${point.endLine ?? point.startLine}` : "";
          const body = [point.body, point.analysis ? `代码解析\n${point.analysis}` : "",
            point.code ? `源码片段\n${point.code}` : "", pointer].filter(Boolean).join("\n\n");
          const pointSources = Array.from(new Set([...sourceIds, ...(point.sourceId ? [point.sourceId] : [])]));
          const knowledge = await mutate<LearningRecord>("/knowledge", {
            title: (point.title || `项目知识点 ${index + 1}`).slice(0, 200),
            body: body.slice(0, 32_000),
            type: "AI_PROJECT",
            tags: ["AI", "项目八股文"],
            sourceIds: pointSources,
            idempotencyKey: knowledgeKeys.current[index] ?? crypto.randomUUID(),
          });
          await mutate<LearningRecord>("/cards", {
            question: `请讲清「${(point.title || "项目知识点").slice(0, 80)}」的核心原理、适用边界，并结合源码说明。`.slice(0, 500),
            answer: (point.body || point.title).slice(0, 16_000),
            explanation: (point.analysis || "").slice(0, 16_000),
            codeExample: (point.code || "").slice(0, 16_000),
            notes: pointer.slice(0, 16_000),
            knowledgeId: knowledge.id,
            tags: ["AI", "项目八股文"],
            sourceIds: pointSources,
            idempotencyKey: cardKeys.current[index] ?? crypto.randomUUID(),
          });
        }
        const task = await mutate<{ id: string }>(`/training/ai/generations/${generation.id}/task`, { idempotencyKey: applyKey.current });
        setApplied({ trainingId: task.id, knowledge: points.length, cards: points.length });
      } else {
        await mutate(`/guides/${guideId}/apply-suggestion`, {
          revision: guideRevision, generationId: generation.id, idempotencyKey: applyKey.current,
        });
        onDone("guide", guideId);
      }
    } catch (reason) { report(reason); }
    finally { setBusy(false); }
  };
  const title = heading ?? (kind === "training" ? "AI 出题并创建训练草稿" : "AI 生成六步引导草稿");
  return <div className="modal-backdrop ai-backdrop"><section className="ai-panel" role="dialog" aria-modal="true" aria-label={title}>
    <div className="ai-panel-heading"><div><h2>{title}</h2></div><button onClick={onClose} disabled={busy}>关闭</button></div>
    <div className="ai-panel-content">
      {error && <><p className="learning-error" role="alert">{error}</p>{!context && !generation && <button disabled={busy} onClick={() => void prepare()}>重试准备</button>}</>}
      {notice && <p className="learning-success" role="status">{notice}</p>}
      <p className="provider-line">{provider ? `${provider.provider} · ${provider.model} · ${provider.baseUrl}` : "正在核对本机 AI 服务"}</p>
      {busy && !context && !generation && <p role="status">正在准备固定源码与有界上下文…</p>}
      {context && !generation && <AiConsentPreview context={context} agreed={agreed} busy={busy}
        onAgree={setAgreed} onDeny={() => void consent("DENY")} onEdit={() => { setContext(null); setGeneration(null); void prepare(); }}
        onGenerate={() => void consent("ALLOW")}/>}
      {generation && <section className="ai-pack-generation" aria-live="polite">
        <p><strong>{statusText[generation.status] ?? generation.status}</strong> {generation.errorCode} {generation.message}</p>
        {generation.errorCode === "INVALID_RESPONSE_SCHEMA" && <p className="hint">模型返回的题目结构不符合要求，常见原因是题量或选项格式；可先重试。若持续失败，请重启 CodeAtlasAI 服务，确认题目数量上限已更新。</p>}
        {active(generation) && <button disabled={busy} onClick={() => mutate<PackGeneration>(`/ai/generations/${generation.id}/cancel`, {}).then(setGeneration).catch(report)}>取消生成</button>}
        {kind === "training" && generation.result && "questions" in generation.result && <article className="ai-pack-result">
          <h3>{(generation.result as Candidate).title}</h3>
          <p>{(generation.result as Candidate).goal}</p>
          {!!(generation.result as Candidate).knowledge?.length && <section className="ai-pack-knowledge">
            <h4>项目知识点 · {(generation.result as Candidate).knowledge!.length} 条</h4>
            <div className="ai-knowledge-grid">
              {(generation.result as Candidate).knowledge!.map((point, index) => <article className="ai-knowledge-card" key={index}>
                <header><span>{String(index + 1).padStart(2, "0")}</span><strong>{point.title}</strong></header>
                <p>{point.body}</p>
                {point.code && <pre>{point.code}</pre>}
                {point.analysis && <div className="ai-knowledge-analysis"><span>代码解析</span><p>{point.analysis}</p></div>}
                {point.filePath && <small>{point.filePath} · L{point.startLine}–L{point.endLine}</small>}
              </article>)}
            </div>
          </section>}
          <p>{(generation.result as Candidate).questions.length} 道题将写入训练任务草稿；知识点会写入「知识」并生成关联卡片，均可继续编辑。</p>
          {applied ? <div className="ai-pack-applied" role="status">
            <p>已写入 {applied.knowledge} 条知识、{applied.cards} 张关联卡片，并创建训练草稿。</p>
            <div className="button-row">
              <button onClick={() => onOpenLibrary?.("knowledge")}>查看知识</button>
              <button onClick={() => onOpenLibrary?.("cards")}>查看卡片墙</button>
              <button className="primary" onClick={() => onDone("training", applied.trainingId)}>打开训练草稿</button>
            </div>
          </div> : <button className="primary" disabled={busy} onClick={() => void apply()}>保存知识、卡片与训练草稿</button>}
        </article>}
        {kind === "guide" && generation.result && "steps" in generation.result && <article className="ai-pack-result">
          <h3>{(generation.result as GuideAdvice).summary}</h3>
          <ul>{(generation.result as GuideAdvice).steps.map(step => <li key={step.stepId}>{step.reason} · {step.actionAdvice}</li>)}</ul>
          <button className="primary" disabled={busy} onClick={() => void apply()}>采纳为学习引导</button>
        </article>}
        {["FAILED", "CANCELLED"].includes(generation.status) && <button disabled={busy} onClick={() => { setGeneration(null); setContext(null); void prepare(); }}>重新准备</button>}
      </section>}
      <p className="hint">AI 只生成草稿；题目、引导步骤、知识掌握与完成状态仍以真实作答和验证记录为准。</p>
    </div>
  </section></div>;
}
