import { useEffect, useRef, useState } from "react";
import { Sparkles, ArrowRight, Loader2, X } from "lucide-react";
import AiConsentPreview from "./AiConsentPreview";
import { api, statusText } from "../api";
import { mutate, explanationBody, type Provider, type PreparedContext, type Generation, type SourceSelection, type EvidenceSource, type LearningKind, type LearningRecord } from "./learningApi";

type Props = { selection?: SourceSelection; sourceIds?: string[]; purpose?: string; autoPrepare?: boolean; onClose: () => void;
  onSource: (source: EvidenceSource, line?: number) => void; onOpenRecord: (kind: LearningKind, id: string) => void };
const isActive = (generation: Generation | null) => !!generation && ["QUEUED", "RUNNING"].includes(generation.status);

export default function AiPanel({ selection, sourceIds, purpose: initialPurpose = "METHOD_EXPLANATION", autoPrepare = false, onClose, onSource, onOpenRecord }: Props) {
  const [provider, setProvider] = useState<Provider | null>(null);
  const [question, setQuestion] = useState("");
  const [depth, setDepth] = useState("STANDARD");
  const [purpose, setPurpose] = useState(initialPurpose);
  const [context, setContext] = useState<PreparedContext | null>(null);
  const [generation, setGeneration] = useState<Generation | null>(null);
  const [parent, setParent] = useState("");
  const [followupSources, setFollowupSources] = useState<string[]>([]);
  const [agreed, setAgreed] = useState(false);
  const [declined, setDeclined] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [title, setTitle] = useState("");
  const [body, setBody] = useState("");
  const generationKey = useRef(crypto.randomUUID());
  const autoPrepared = useRef(false);
  const saveKeys = useRef({ documents: crypto.randomUUID(), knowledge: crypto.randomUUID() });
  const report = (error: unknown) => { if (!(error instanceof DOMException && error.name === "AbortError")) setError(error instanceof Error ? error.message : String(error)); };
  useEffect(() => {
    const controller = new AbortController();
    api<Provider>("/ai/provider", { signal: controller.signal }).then(setProvider).catch(report);
    const restored = new URLSearchParams(location.search).get("aiGenerationId");
    if (restored) api<Generation>(`/ai/generations/${restored}`, { signal: controller.signal }).then(async result => {
      if ((result as Generation & { operation?: string }).operation && (result as Generation & { operation?: string }).operation !== "EXPLANATION") throw new Error("请选择训练页面查看此生成记录");
      const preview = await api<PreparedContext>(`/ai/contexts/${result.contextId}`, { signal: controller.signal });
      setContext(preview); setGeneration(result); setQuestion(preview.question); setDepth(preview.depth); setPurpose(preview.purpose);
    }).catch(report);
    return () => controller.abort();
  }, []);

  useEffect(() => {
    if (!isActive(generation)) return;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      try {
        const next = await api<Generation>(`/ai/generations/${generation!.id}`, { signal: controller.signal });
        setGeneration(next);
        if (!isActive(next)) return;
      } catch (error) { if (controller.signal.aborted) return; report(error); }
      timer = setTimeout(poll, 700);
    };
    timer = setTimeout(poll, 500);
    return () => { controller.abort(); clearTimeout(timer); };
  }, [generation?.id]);

  useEffect(() => {
    if (!generation?.result) return;
    setTitle(generation.result.title); setBody(explanationBody(generation.result));
    saveKeys.current = { documents: crypto.randomUUID(), knowledge: crypto.randomUUID() };
  }, [generation?.id, generation?.status]);

  const prepare = async () => {
    setBusy(true); setError(""); setDeclined(false);
    try {
      const chosen = parent && followupSources.length ? { sourceIds: followupSources } : sourceIds?.length ? { sourceIds } : selection;
      if (!chosen) throw new Error("请先选择源码方法或关联来源");
      const next = await mutate<PreparedContext>("/ai/contexts", { ...chosen, question, depth, purpose, parentGenerationId: parent });
      setContext(next); setAgreed(false); generationKey.current = crypto.randomUUID();
    } catch (error) { report(error); }
    finally { setBusy(false); }
  };
  useEffect(() => {
    if (!autoPrepare || autoPrepared.current || provider === null) return;
    if (context || generation || busy) return;
    if (!selection && !sourceIds?.length) return;
    autoPrepared.current = true;
    void prepare();
  }, [autoPrepare, provider, context, generation, selection?.snapshotId, selection?.symbolId, selection?.fileId]);
  const generate = async () => {
    if (!context || !agreed) return;
    setBusy(true); setError("");
    try {
      const consent = await mutate<{ id: string }>("/ai/consents", { contextId: context.id, decision: "ALLOW" });
      const next = await mutate<Generation>("/ai/generations", { consentId: consent.id, idempotencyKey: generationKey.current });
      setGeneration(next);
      const params = new URLSearchParams(location.search); params.set("aiGenerationId", next.id); history.replaceState(null, "", `?${params}`);
    } catch (error) { report(error); }
    finally { setBusy(false); }
  };
  const deny = async () => {
    if (!context) return;
    setBusy(true);
    try { await mutate("/ai/consents", { contextId: context.id, decision: "DENY" }); setDeclined(true); setContext(null); setAgreed(false); }
    catch (error) { report(error); }
    finally { setBusy(false); }
  };
  const save = async (kind: "documents" | "knowledge") => {
    if (!generation?.result) return;
    setBusy(true); setError("");
    try {
      const saved = await mutate<LearningRecord>(`/${kind}`, { title, body, generationId: generation.id,
        type: "EXPLANATION", tags: [], sourceIds: [], idempotencyKey: saveKeys.current[kind] });
      onOpenRecord(kind, saved.id);
    } catch (error) { report(error); }
    finally { setBusy(false); }
  };
  const close = () => { const params = new URLSearchParams(location.search); params.delete("aiGenerationId"); history.replaceState(null, "", `?${params}`); onClose(); };
  return <div className="modal-backdrop ai-backdrop"><section className="ai-panel" role="dialog" aria-modal="true" aria-labelledby="ai-title">
    <div className="ai-panel-heading"><div><Sparkles size={18}/><h2 id="ai-title">源码解释</h2></div><button onClick={close} disabled={busy} aria-label="关闭源码解释"><X size={18}/></button></div>
    <div className="ai-panel-content">
      {error && <p className="learning-error" role="alert">{error}</p>}
      {declined && <p className="learning-success" role="status">已拒绝发送，本次未发起模型请求。</p>}
      <p className="provider-line">{provider ? `${provider.provider} · ${provider.model} · ${provider.baseUrl}` : "正在核对本机 AI 服务"}</p>
      {!context && !isActive(generation) && <div className="ai-question-form">
        <div className="field-row"><label>解释深度<select aria-label="解释深度" value={depth} onChange={e => setDepth(e.target.value)}><option value="BRIEF">简要</option><option value="STANDARD">标准</option><option value="DETAILED">详细</option></select></label>
          <label>生成用途<select aria-label="生成用途" value={purpose} onChange={e => setPurpose(e.target.value)}><option value="METHOD_EXPLANATION">方法解释</option><option value="TECHNICAL_NOTE">技术笔记</option><option value="PROJECT_NOTE">项目说明</option><option value="BUSINESS_FLOW">业务流程说明</option></select></label></div>
        <label>我的问题<textarea aria-label="我的问题" value={question} onChange={e => setQuestion(e.target.value)} maxLength={2000} placeholder="希望了解哪些业务逻辑或技术原理？"/></label>
        <button className="primary" onClick={prepare} disabled={busy}>预览外发范围<ArrowRight size={14}/></button>
      </div>}
      {context && !generation && <AiConsentPreview context={context} agreed={agreed} busy={busy} onAgree={setAgreed} onDeny={deny} onEdit={() => setContext(null)} onGenerate={generate}/>}
      {generation && <div className={`generation-status ${generation.status.toLowerCase()}`} aria-live="polite">{isActive(generation) && <Loader2 size={15} className="spin"/>}<strong>{statusText[generation.status] ?? generation.status}</strong><span>{generation.errorCode ? `${generation.errorCode} · ` : ""}{generation.message}</span>
        {isActive(generation) && <button onClick={() => mutate<Generation>(`/ai/generations/${generation.id}/cancel`, {}).then(setGeneration).catch(report)}>取消生成</button>}
        {["FAILED", "CANCELLED"].includes(generation.status) && <button onClick={() => { setGeneration(null); setContext(null); }}>重新准备</button>}
      </div>}
      {generation?.result && <section className="explanation-result"><p className="verification-note">引用校验反映源码位置的可定位性，业务解释请结合实际项目核对。</p>
        <h3>{generation.result.title}</h3><p>{generation.result.summary}</p><ul>{generation.result.keyPoints.map((point, i) => <li key={i}>{point}</li>)}</ul><div className="explanation-text">{generation.result.explanation}</div>
        <h4>源码引用</h4>{generation.result.citations.map((citation, i) => <div className="explanation-citation" key={i}><span className={`citation-status ${citation.verificationStatus.toLowerCase()}`}>{citation.verificationStatus === "VERIFIED" ? "引用已校验" : "引用无效"}</span><strong>{citation.filePath} · L{citation.startLine}–L{citation.endLine}</strong><p>{citation.explanation}</p>{citation.verificationStatus === "VERIFIED" && citation.source ? <button onClick={() => { onSource(citation.source!, citation.startLine); close(); }}>定位源码<ArrowRight size={13}/></button> : <small>{citation.verificationReason}</small>}</div>)}
        {!generation.result.citations.length && <p className="hint">该解释没有提供可核对的源码引用。</p>}
        {!!generation.result.uncertainties.length && <><h4>不确定项</h4><ul>{generation.result.uncertainties.map((point, i) => <li key={i}>{point}</li>)}</ul></>}
        <p className="usage">输入 {generation.result.usage.prompt_tokens ?? "—"} tokens · 输出 {generation.result.usage.completion_tokens ?? "—"} tokens</p>
        <button onClick={() => { setParent(generation.id); setFollowupSources(context?.sources.map(s => s.sourceId ?? s.id) ?? []); setGeneration(null); setQuestion(""); setContext(null); }}>继续追问</button>
        <div className="save-explanation"><h4>保存学习资料</h4><label>保存标题<input aria-label="保存标题" value={title} onChange={e => setTitle(e.target.value)} maxLength={200}/></label><label>保存正文<textarea aria-label="保存正文" value={body} onChange={e => setBody(e.target.value)} rows={8}/></label><div className="button-row"><button onClick={() => save("knowledge")} disabled={busy}>提炼为知识</button><button className="primary" onClick={() => save("documents")} disabled={busy}>保存为文档</button></div></div>
      </section>}
    </div>
  </section></div>;
}
