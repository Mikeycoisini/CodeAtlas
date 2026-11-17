import { useEffect, useRef, useState } from "react";
import { api, statusText } from "../api";
import { mutate, type PreparedContext } from "../learning/learningApi";
import AiConsentPreview from "../learning/AiConsentPreview";
import { change, formats, message, verdicts, type Candidate, type Feedback, type TrainingGeneration } from "./trainingApi";

export default function TrainingAiPanel({ sourceIds, goal, attemptId, questionId, restoredId, onApply, onClose }: {
  sourceIds: string[]; goal: string; attemptId?: string; questionId?: string; restoredId?: string;
  onApply?: (candidate: Candidate, generationId: string) => void; onClose: () => void;
}) {
  const feedback = !!attemptId;
  const operation = feedback ? "TRAINING_FEEDBACK" : "TRAINING_QUESTIONS";
  const [context, setContext] = useState<PreparedContext | null>(null);
  const [generation, setGeneration] = useState<TrainingGeneration | null>(null);
  const [restoring, setRestoring] = useState(!!restoredId);
  const [count, setCount] = useState(3);
  const [selectedFormats, setSelectedFormats] = useState(Object.keys(formats));
  const [agreed, setAgreed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const alive = useRef(true);
  const key = useRef(crypto.randomUUID());
  const running = generation && ["QUEUED", "RUNNING"].includes(generation.status);
  useEffect(() => {
    alive.current = true;
    const controller = new AbortController();
    if (restoredId) api<TrainingGeneration>(`/ai/generations/${restoredId}`, { signal: controller.signal }).then(async item => {
      if (item.operation !== operation) throw new Error("生成用途与当前训练操作不匹配");
      const preview = await api<PreparedContext>(`/ai/contexts/${item.contextId}`, { signal: controller.signal });
      const frozen = JSON.parse(preview.contextJson).training;
      if (feedback && (frozen.attemptId !== attemptId || frozen.question.id !== questionId)) throw new Error("反馈属于其他提交或题目");
      if (!controller.signal.aborted) { setContext(preview); setGeneration(item); }
    }).catch(error => { if (!controller.signal.aborted) setError(message(error)); })
      .finally(() => { if (!controller.signal.aborted) setRestoring(false); });
    return () => { alive.current = false; controller.abort(); };
  }, []);
  useEffect(() => {
    if (!running || !generation) return;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      try {
        const item = await api<TrainingGeneration>(`/ai/generations/${generation.id}`, { signal: controller.signal });
        if (controller.signal.aborted) return;
        setGeneration(item);
        if (!["QUEUED", "RUNNING"].includes(item.status)) return;
      } catch (error) { if (controller.signal.aborted) return; setError(message(error)); }
      timer = setTimeout(poll, 700);
    };
    timer = setTimeout(poll, 400);
    return () => { controller.abort(); clearTimeout(timer); };
  }, [generation?.id, running]);
  const action = async (work: () => Promise<void>) => {
    setBusy(true); setError("");
    try { await work(); } catch (error) { if (alive.current) setError(message(error)); }
    finally { if (alive.current) setBusy(false); }
  };
  const prepare = () => action(async () => {
    const preview = feedback
      ? await change<PreparedContext>(`/attempts/${attemptId}/questions/${questionId}/ai-context`, { sourceIds })
      : await change<PreparedContext>("/ai/contexts", { sourceIds, goal, questionCount: count, formats: selectedFormats });
    if (!alive.current) return;
    setContext(preview); setGeneration(null); setAgreed(false); setNotice(""); key.current = crypto.randomUUID();
  });
  const generate = () => action(async () => {
    if (!context || !agreed) return;
    const consent = await mutate<{ id: string }>("/ai/consents", { contextId: context.id, decision: "ALLOW" });
    if (!alive.current) return;
    const item = await mutate<TrainingGeneration>("/ai/generations", { consentId: consent.id, idempotencyKey: key.current });
    if (!alive.current) return;
    setGeneration(item);
    const params = new URLSearchParams(location.search); params.set("trainingAiGenerationId", item.id);
    if (questionId) params.set("trainingQuestionId", questionId);
    history.replaceState(null, "", `?${params}`);
  });
  const deny = () => action(async () => {
    if (!context) return;
    await mutate("/ai/consents", { contextId: context.id, decision: "DENY" });
    if (alive.current) { setContext(null); setAgreed(false); setNotice("已拒绝发送"); }
  });
  const result = generation?.result;
  const candidate = !feedback ? result as Candidate | null : null;
  const review = feedback ? result as Feedback | null : null;
  return <div className="modal-backdrop ai-backdrop"><section className="ai-panel" role="dialog" aria-modal="true" aria-label={feedback ? "AI辅助反馈" : "AI出题"}>
    <div className="ai-panel-heading"><h2>{feedback ? "AI辅助反馈" : "AI出题"}</h2><button disabled={busy} onClick={onClose}>关闭</button></div>
    <div className="ai-panel-content">{error && <p className="learning-error" role="alert">{error}</p>}{notice && <p role="status">{notice}</p>}
      {restoring && <p role="status">正在恢复生成记录…</p>}
      {!restoring && !context && !generation && <><p>{feedback ? "根据本题的冻结提交、参考答案与源码生成辅助建议。" : "生成候选后可编辑题目，再发布训练任务。"}</p>
        {!feedback && <><label>出题数量<input aria-label="出题数量" type="number" min={1} max={5} value={count} onChange={event => setCount(Number(event.target.value))}/></label>
          <fieldset><legend>出题格式</legend>{Object.entries(formats).map(([value, label]) => <label className="authorization" key={value}><input type="checkbox" checked={selectedFormats.includes(value)} onChange={event => setSelectedFormats(previous => event.target.checked ? [...previous, value] : previous.filter(item => item !== value))}/>{label}</label>)}</fieldset></>}
        <button className="primary" disabled={busy || !selectedFormats.length} onClick={prepare}>预览外发范围</button></>}
      {context && !generation && <AiConsentPreview context={context} agreed={agreed} busy={busy} onAgree={setAgreed} onDeny={deny} onEdit={() => setContext(null)} onGenerate={generate}/>}
      {generation && <section aria-live="polite"><p>{statusText[generation.status] ?? generation.status} {generation.errorCode} {generation.message}</p>
        {running && <button onClick={() => action(async () => { const item = await mutate<TrainingGeneration>(`/ai/generations/${generation.id}/cancel`, {}); if (alive.current) setGeneration(item); })}>取消生成</button>}
        {["FAILED", "CANCELLED"].includes(generation.status) && <button onClick={() => { setContext(null); setGeneration(null); }}>重新准备</button>}
      </section>}
      {candidate && <section><h3>{candidate.title}</h3><p>{candidate.goal}</p>{candidate.questions.map((question, index) => <article className="training-question" key={question.id}><h4>{index + 1}. {formats[question.format]}</h4><p>{question.prompt}</p>{question.options.map(option => <p key={option.id}>{option.id} · {option.text}</p>)}<p>参考答案 {String(question.correctAnswer)}</p><p>{question.referenceExplanation}</p></article>)}
        <button className="primary" disabled={!candidate.citations.some(item => item.verificationStatus === "VERIFIED")} onClick={() => onApply?.(candidate, generation!.id)}>追加到任务</button></section>}
      {review && <section className="training-ai-feedback"><h3>AI辅助建议</h3><p>{verdicts[review.suggestedVerdict] ?? review.suggestedVerdict} · AI_ASSISTED</p><p>{review.explanation}</p><h4>已体现的理解</h4><ul>{review.strengths.map((item, index) => <li key={index}>{item}</li>)}</ul><h4>待补充</h4><ul>{review.gaps.map((item, index) => <li key={index}>{item}</li>)}</ul></section>}
      {result && <><h3>源码引用</h3>{result.citations.map((item, index) => <p key={index}>{item.verificationStatus === "VERIFIED" ? "引用已校验" : "引用无效"} · {item.filePath} L{item.startLine}–L{item.endLine}<br/>{item.explanation}</p>)}{result.uncertainties.map((item, index) => <p key={index}>{item}</p>)}<p className="hint">请结合源码核对题目与辅助建议。</p></>}
    </div>
  </section></div>;
}
