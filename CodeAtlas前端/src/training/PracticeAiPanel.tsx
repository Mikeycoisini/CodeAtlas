import { useEffect, useRef, useState } from "react";
import { api, statusText } from "../api";
import { mutate, type Citation, type PreparedContext, type Provider } from "../learning/learningApi";
import AiConsentPreview from "../learning/AiConsentPreview";

type PracticeFeedback = { strengths: string[]; gaps: string[]; explanation: string; suggestedVerdict: string; source: string;
  citations: Citation[]; uncertainties: string[] };
type PracticeGeneration = { id: string; contextId: string; operation: string; status: string; result: PracticeFeedback | null;
  provider: Provider; errorCode?: string; message?: string };

const verdicts: Record<string, string> = { PASS: "建议通过", PARTIAL: "部分满足", NEEDS_WORK: "仍需修改", UNSURE: "需人工核对" };

export default function PracticeAiPanel({ submissionId, onClose }: { submissionId: string; onClose: () => void }) {
  const [context, setContext] = useState<PreparedContext | null>(null);
  const [generation, setGeneration] = useState<PracticeGeneration | null>(null);
  const [question, setQuestion] = useState("");
  const [depth, setDepth] = useState("STANDARD");
  const [agreed, setAgreed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const alive = useRef(true);
  const key = useRef(crypto.randomUUID());
  const running = generation && ["QUEUED", "RUNNING"].includes(generation.status);

  useEffect(() => {
    alive.current = true;
    return () => { alive.current = false; };
  }, []);
  useEffect(() => {
    if (!running || !generation) return;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      try {
        const item = await api<PracticeGeneration>(`/ai/generations/${generation.id}`, { signal: controller.signal });
        if (controller.signal.aborted) return;
        setGeneration(item);
        if (!["QUEUED", "RUNNING"].includes(item.status)) return;
      } catch (error) {
        if (controller.signal.aborted) return;
        setError(error instanceof Error ? error.message : String(error));
      }
      timer = setTimeout(poll, 700);
    };
    timer = setTimeout(poll, 400);
    return () => { controller.abort(); clearTimeout(timer); };
  }, [generation?.id, running]);

  const action = async (work: () => Promise<void>) => {
    setBusy(true); setError(""); setNotice("");
    try { await work(); } catch (error) { if (alive.current) setError(error instanceof Error ? error.message : String(error)); }
    finally { if (alive.current) setBusy(false); }
  };
  const prepare = () => action(async () => {
    const preview = await mutate<PreparedContext>(`/practice/submissions/${submissionId}/ai-context`, { question, depth });
    if (!alive.current) return;
    setContext(preview); setGeneration(null); setAgreed(false); key.current = crypto.randomUUID();
  });
  const generate = () => action(async () => {
    if (!context || !agreed) return;
    const consent = await mutate<{ id: string }>("/ai/consents", { contextId: context.id, decision: "ALLOW" });
    if (!alive.current) return;
    const item = await mutate<PracticeGeneration>("/ai/generations", { consentId: consent.id, idempotencyKey: key.current });
    if (alive.current) setGeneration(item);
  });
  const deny = () => action(async () => {
    if (!context) return;
    await mutate("/ai/consents", { contextId: context.id, decision: "DENY" });
    if (alive.current) { setContext(null); setAgreed(false); setNotice("已拒绝发送"); }
  });
  const feedback = generation?.result;
  return <div className="modal-backdrop ai-backdrop"><section className="ai-panel" role="dialog" aria-modal="true" aria-label="代码修改 AI 反馈">
    <div className="ai-panel-heading"><h2>代码修改 AI 反馈</h2><button disabled={busy} onClick={onClose}>关闭</button></div>
    <div className="ai-panel-content">{error && <p className="learning-error" role="alert">{error}</p>}{notice && <p role="status">{notice}</p>}
      {!context && !generation && <><p>根据任务目标、验收条件、代码差异、用户外部测试报告和所选源码生成辅助反馈。外部报告由用户提供，CodeAtlas 未执行代码。</p>
        <label>问题（可选）<textarea aria-label="AI 反馈问题" maxLength={2000} value={question} onChange={event => setQuestion(event.target.value)} placeholder="例如：这次修改是否满足验收条件？还存在哪些风险？"/></label>
        <label>反馈深度<select aria-label="反馈深度" value={depth} onChange={event => setDepth(event.target.value)}><option value="BRIEF">简要</option><option value="STANDARD">标准</option><option value="DETAILED">详细</option></select></label>
        <button className="primary" disabled={busy} onClick={prepare}>预览外发范围</button></>}
      {context && !generation && <AiConsentPreview context={context} agreed={agreed} busy={busy} onAgree={setAgreed} onDeny={deny} onEdit={() => setContext(null)} onGenerate={generate}/>}
      {generation && <section aria-live="polite"><p>{statusText[generation.status] ?? generation.status} {generation.errorCode} {generation.message}</p>
        {running && <button onClick={() => action(async () => { const item = await mutate<PracticeGeneration>(`/ai/generations/${generation.id}/cancel`, {}); if (alive.current) setGeneration(item); })}>取消生成</button>}
        {["FAILED", "CANCELLED"].includes(generation.status) && <button onClick={() => { setContext(null); setGeneration(null); }}>重新准备</button>}
      </section>}
      {feedback && <section className="training-ai-feedback"><h3>AI辅助建议</h3><p>{verdicts[feedback.suggestedVerdict] ?? feedback.suggestedVerdict} · AI_ASSISTED</p><p>{feedback.explanation}</p>
        <h4>已体现的优点</h4><ul>{feedback.strengths.map((item, index) => <li key={index}>{item}</li>)}</ul><h4>待改进</h4><ul>{feedback.gaps.map((item, index) => <li key={index}>{item}</li>)}</ul>
        <h4>源码引用</h4>{feedback.citations.map((item, index) => <p key={index}>{item.verificationStatus === "VERIFIED" ? "引用已校验" : "引用无效"} · {item.filePath} L{item.startLine}–L{item.endLine}<br/>{item.explanation}</p>)}
        {feedback.uncertainties.map((item, index) => <p key={index}>{item}</p>)}<p className="hint">AI 反馈为辅助建议，由你决定是否采纳；外部测试报告不代表系统验证。</p></section>}
    </div>
  </section></div>;
}
