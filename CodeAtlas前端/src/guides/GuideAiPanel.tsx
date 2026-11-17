import { useEffect, useRef, useState } from "react";
import { statusText } from "../api";
import type { PreparedContext } from "../learning/learningApi";
import AiConsentPreview from "../learning/AiConsentPreview";
import { errorText, get, write, type Guide, type GuideGeneration } from "../study/studyApi";

export default function GuideAiPanel({ guide, restoredId, onApplied, onClose }: { guide: Guide; restoredId: string; onApplied: (guide: Guide) => void; onClose: () => void }) {
  const [context, setContext] = useState<PreparedContext | null>(null); const [generation, setGeneration] = useState<GuideGeneration | null>(null);
  const [restoring, setRestoring] = useState(!!restoredId); const [sourceIds, setSourceIds] = useState<string[]>([]); const [knowledgeIds, setKnowledgeIds] = useState<string[]>([]);
  const [agreed, setAgreed] = useState(false); const [busy, setBusy] = useState(false); const [error, setError] = useState(""); const [notice, setNotice] = useState("");
  const alive = useRef(true); const generationKey = useRef(crypto.randomUUID()); const applyKey = useRef(crypto.randomUUID());
  const running = generation && ["QUEUED", "RUNNING"].includes(generation.status);
  useEffect(() => {
    alive.current = true; const controller = new AbortController();
    if (restoredId) get<GuideGeneration>(`/ai/generations/${restoredId}`, controller.signal).then(async item => {
      if (item.operation !== "LEARNING_GUIDE") throw new Error("该生成记录属于其他用途");
      const preview = await get<PreparedContext>(`/ai/contexts/${item.contextId}`, controller.signal);
      if (JSON.parse(preview.contextJson).guide.guideId !== guide.id) throw new Error("该建议属于其他学习引导");
      if (!controller.signal.aborted) { setContext(preview); setGeneration(item); }
    }).catch(error => { if (!controller.signal.aborted) setError(errorText(error)); }).finally(() => { if (!controller.signal.aborted) setRestoring(false); });
    return () => { alive.current = false; controller.abort(); };
  }, []);
  useEffect(() => {
    if (!running || !generation) return;
    const controller = new AbortController(); let timer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      try {
        const item = await get<GuideGeneration>(`/ai/generations/${generation.id}`, controller.signal);
        if (controller.signal.aborted) return; setGeneration(item);
        if (!["QUEUED", "RUNNING"].includes(item.status)) return;
      } catch (error) { if (controller.signal.aborted) return; setError(errorText(error)); }
      timer = setTimeout(poll, 700);
    };
    timer = setTimeout(poll, 400); return () => { controller.abort(); clearTimeout(timer); };
  }, [generation?.id, running]);
  const work = async (job: () => Promise<void>) => {
    setBusy(true); setError("");
    try { await job(); } catch (error) { if (alive.current) setError(errorText(error)); }
    finally { if (alive.current) setBusy(false); }
  };
  const prepare = () => work(async () => {
    const preview = await write<PreparedContext>(`/guides/${guide.id}/ai-context`, { revision: guide.revision, sourceIds, knowledgeIds });
    if (alive.current) { setContext(preview); setGeneration(null); setAgreed(false); setNotice(""); generationKey.current = crypto.randomUUID(); applyKey.current = crypto.randomUUID(); }
  });
  const generate = () => work(async () => {
    if (!context || !agreed) return;
    const consent = await write<{ id: string }>("/ai/consents", { contextId: context.id, decision: "ALLOW" });
    if (!alive.current) return;
    const item = await write<GuideGeneration>("/ai/generations", { consentId: consent.id, idempotencyKey: generationKey.current });
    if (alive.current) { setGeneration(item); const params = new URLSearchParams(location.search); params.set("guideAiGenerationId", item.id); history.replaceState(null, "", `?${params}`); }
  });
  const deny = () => work(async () => {
    if (!context) return;
    await write("/ai/consents", { contextId: context.id, decision: "DENY" });
    if (alive.current) { setContext(null); setAgreed(false); setNotice("已拒绝发送"); }
  });
  const apply = () => work(async () => {
    if (!generation) return;
    const saved = await write<Guide>(`/guides/${guide.id}/apply-suggestion`, { revision: guide.revision, generationId: generation.id, idempotencyKey: applyKey.current });
    if (alive.current) onApplied(saved);
  });
  const reset = () => { setContext(null); setGeneration(null); setAgreed(false); const params = new URLSearchParams(location.search); params.delete("guideAiGenerationId"); history.replaceState(null, "", `?${params}`); };
  const stale = !!context && JSON.parse(context.contextJson).guide.revision !== guide.revision;
  return <div className="modal-backdrop ai-backdrop"><section className="ai-panel" role="dialog" aria-modal="true" aria-label="AI学习引导"><div className="ai-panel-heading"><h2>AI学习引导</h2><button disabled={busy} onClick={onClose}>关闭</button></div><div className="ai-panel-content">
    {error && <p role="alert" className="study-error">{error}</p>}{notice && <p role="status" className="study-notice">{notice}</p>}{restoring && <p role="status">正在恢复生成记录…</p>}
    {!restoring && !context && !generation && <><p>目标和当前六个步骤将进入预览。可勾选本次需要的资料。</p>
      {guide.sources.map(source => <label className="authorization" key={source.id}><input type="checkbox" checked={sourceIds.includes(source.id)} onChange={event => setSourceIds(previous => event.target.checked ? [...previous, source.id] : previous.filter(id => id !== source.id))}/>{source.filePath} · L{source.startLine}–L{source.endLine}</label>)}
      {guide.knowledge.map(knowledge => <label className="authorization" key={knowledge.id}><input type="checkbox" checked={knowledgeIds.includes(knowledge.id)} onChange={event => setKnowledgeIds(previous => event.target.checked ? [...previous, knowledge.id] : previous.filter(id => id !== knowledge.id))}/>{knowledge.title} · 知识与学习证据摘要</label>)}
      <button className="primary" disabled={busy} onClick={prepare}>预览外发范围</button></>}
    {context && !generation && <AiConsentPreview context={context} agreed={agreed} busy={busy} onAgree={setAgreed} onDeny={deny} onEdit={() => setContext(null)} onGenerate={generate}/>}
    {generation && <section aria-live="polite"><p>{statusText[generation.status] ?? generation.status} {generation.errorCode} {generation.message}</p>
      {running && <button disabled={busy} onClick={() => work(async () => { const next = await write<GuideGeneration>(`/ai/generations/${generation.id}/cancel`, {}); if (alive.current) setGeneration(next); })}>取消生成</button>}
      {!running && <button disabled={busy} onClick={reset}>重新准备</button>}</section>}
    {generation?.result && <><h3>{generation.result.summary}</h3><p className="study-muted">AI辅助建议 · 供你调整学习顺序和方法</p>{generation.result.steps.map(step => <article className="guide-step" key={step.stepId}><h4>{guide.steps.find(item => item.id === step.stepId)?.title}</h4><p>{step.reason}</p><p>{step.actionAdvice}</p></article>)}
      {generation.result.citations.map((citation, index) => <p key={index}>{citation.verificationStatus === "VERIFIED" ? "引用已校验" : "引用无效"} · {citation.filePath} L{citation.startLine}–L{citation.endLine}</p>)}
      {generation.result.uncertainties.map((item, index) => <p key={index}>{item}</p>)}{stale && <p className="study-error">当前引导版本已变化，请重新准备建议。</p>}
      <button className="primary" disabled={busy || stale} onClick={apply}>采纳步骤建议</button></>}
  </div></section></div>;
}
