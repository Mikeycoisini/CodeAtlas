import { useEffect, useRef, useState } from "react";
import type { EvidenceSource } from "../learning/learningApi";
import TrainingSources from "../training/TrainingSources";
import { errorText, get, gradeNames, studyUrl, timeText, write, type ReviewAttempt } from "../study/studyApi";

export default function ReviewAttemptView({ id, zone, onSource }: { id: string; zone: string; onSource: (source: EvidenceSource) => void }) {
  const [attempt, setAttempt] = useState<ReviewAttempt | null>(null);
  const [answer, setAnswer] = useState(""); const [reflection, setReflection] = useState("");
  const [error, setError] = useState(""); const [notice, setNotice] = useState(""); const [busy, setBusy] = useState(false);
  const alive = useRef(true); const keys = useRef<Record<string, string>>({});
  const storageKey = `codeatlas-review-${id}`;
  const key = (operation: string) => keys.current[operation] ?? (keys.current[operation] = crypto.randomUUID());
  useEffect(() => {
    alive.current = true; const controller = new AbortController();
    get<ReviewAttempt>(`/reviews/attempts/${id}`, controller.signal).then(item => {
      if (controller.signal.aborted) return;
      setAttempt(item); const cached = sessionStorage.getItem(storageKey);
      let draft: { answer: string; reflection: string } | null = null;
      try { draft = cached ? JSON.parse(cached) : null; } catch { /* A new server load restores the saved recall. */ }
      setAnswer(item.status === "DRAFT" ? draft?.answer ?? item.recalledAnswer : item.recalledAnswer);
      setReflection(["DRAFT", "REVEALED"].includes(item.status) ? draft?.reflection ?? item.reflection : item.reflection);
    }).catch(error => { if (!controller.signal.aborted) setError(errorText(error)); });
    return () => { alive.current = false; controller.abort(); };
  }, [id]);
  useEffect(() => { if (attempt && ["DRAFT", "REVEALED"].includes(attempt.status)) sessionStorage.setItem(storageKey, JSON.stringify({ answer, reflection })); }, [answer, reflection, attempt, storageKey]);
  const action = async (operation: string, rating = "") => {
    if (!attempt) return; setBusy(true); setError(""); setNotice("");
    try {
      let current = attempt;
      if (current.status === "DRAFT" && ["save", "reveal", "skip"].includes(operation)) {
        current = await write<ReviewAttempt>(`/reviews/attempts/${id}`, { revision: current.revision, recalledAnswer: answer }, "PUT");
        if (!alive.current) return; setAttempt(current);
      }
      if (operation === "save") { setNotice("已保存回忆"); return; }
      if (operation === "restart") {
        const next = await write<ReviewAttempt>(`/reviews/cards/${current.cardId}/attempts`, { idempotencyKey: key("restart") });
        if (alive.current) studyUrl("reviews", { reviewAttemptId: next.id, reviewCardId: next.cardId }); return;
      }
      const body = operation === "submit" ? { revision: current.revision, rating, reflection, idempotencyKey: key(JSON.stringify([operation, current.revision, rating, reflection])) }
        : { revision: current.revision, idempotencyKey: key(`${operation}-${current.revision}`) };
      const next = await write<ReviewAttempt>(`/reviews/attempts/${id}/${operation}`, body);
      if (!alive.current) return; setAttempt(next); setAnswer(next.recalledAnswer);
      if (["COMMITTED", "SKIPPED"].includes(next.status)) sessionStorage.removeItem(storageKey);
    } catch (error) { if (alive.current) setError(errorText(error)); }
    finally { if (alive.current) setBusy(false); }
  };
  const reload = async () => {
    setBusy(true);
    try { const item = await get<ReviewAttempt>(`/reviews/attempts/${id}`); if (alive.current) { setAttempt(item); setAnswer(item.recalledAnswer); setReflection(item.reflection); sessionStorage.removeItem(storageKey); setError(""); } }
    catch (error) { if (alive.current) setError(errorText(error)); }
    finally { if (alive.current) setBusy(false); }
  };
  if (!attempt) return <section className="study-detail">{error ? <p role="alert" className="study-error">{error}</p> : <p>正在加载复习…</p>}</section>;
  const terminal = ["COMMITTED", "SKIPPED"].includes(attempt.status);
  return <section className="study-detail"><div className="study-heading"><h2>主动回忆</h2><button onClick={() => studyUrl("reviews", { reviewCardId: attempt.cardId })}>返回卡片</button></div>
    <p className="review-attempt-state">{attempt.status === "COMMITTED" ? "已评分" : attempt.status === "SKIPPED" ? "已跳过" : attempt.status === "REVEALED" ? "已揭示答案" : "回忆中"} · 轮次 {attempt.epoch} · 卡片版本 {attempt.card.revision}</p>
    {error && <p role="alert" className="study-error">{error}</p>}{notice && <p role="status" className="study-notice">{notice}</p>}
    <h3>{attempt.card.question}</h3><label>回忆内容<textarea aria-label="回忆内容" maxLength={4000} rows={6} disabled={busy || attempt.status !== "DRAFT"} value={answer} onChange={event => setAnswer(event.target.value)}/></label>
    {attempt.status === "DRAFT" && <div className="button-row"><button disabled={busy} onClick={() => action("save")}>保存回忆</button><button className="primary" disabled={busy} onClick={() => action("reveal")}>查看答案</button></div>}
    {attempt.revealedAtMs != null && <section className="review-reference"><h3>参考答案</h3><p>{attempt.card.answer}</p><p>{attempt.card.explanation}</p>{attempt.card.codeExample && <pre>{attempt.card.codeExample}</pre>}</section>}
    <TrainingSources sources={attempt.card.sources} onSource={onSource}/>
    {attempt.status === "REVEALED" && <><label>复习复盘<textarea aria-label="复习复盘" value={reflection} maxLength={4000} onChange={event => setReflection(event.target.value)} disabled={busy}/></label>
      <p className="study-muted">根据自己的回忆选择评分。困难表示经过努力仍能正确回忆。</p><div className="button-row">{["AGAIN", "HARD", "GOOD", "EASY"].map(rating => <button key={rating} className={rating === "GOOD" ? "primary" : ""} disabled={busy} onClick={() => action("submit", rating)}>{gradeNames[rating]}</button>)}</div></>}
    {attempt.reviewEvent && <section className="review-result"><h3>{gradeNames[attempt.reviewEvent.payload.rating!]}</h3><p>用户自报 · {timeText(attempt.reviewEvent.occurredAtMs, zone)}</p><p>下次复习 {timeText(attempt.reviewEvent.payload.dueAtMs!, zone)}</p><p>{attempt.reviewEvent.payload.reflection}</p><small>{attempt.reviewEvent.algorithmId} · {attempt.reviewEvent.configHash.slice(0, 12)}</small></section>}
    <div className="button-row">{terminal ? <button className="primary" disabled={busy} onClick={() => action("restart")}>再次复习</button> : <button disabled={busy} onClick={() => action("skip")}>跳过本次</button>}
      <button onClick={() => studyUrl("reviews", { reviewCardId: attempt.cardId })}>查看复习历史</button><button disabled={busy} onClick={reload}>放弃本地修改并加载最新记录</button></div>
  </section>;
}
