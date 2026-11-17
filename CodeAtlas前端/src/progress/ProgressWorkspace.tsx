import { useEffect, useRef, useState } from "react";
import type { EvidenceSource } from "../learning/learningApi";
import useStudyLocation from "../study/useStudyLocation";
import EvidencePicker from "./EvidencePicker";
import { errorText, get, gradeNames, practiceResultNames, progressNames, studyUrl, timeText, write, type Evidence, type Progress, type StudyPage } from "../study/studyApi";
import "../study/study.css";

function EvidenceItem({ evidence, currentRevision, onChanged, onSource }: { evidence: Evidence; currentRevision: number; onChanged: () => void; onSource: (source: EvidenceSource) => void }) {
  const [reason, setReason] = useState(""); const [editing, setEditing] = useState(false); const [error, setError] = useState(""); const [busy, setBusy] = useState(false);
  const alive = useRef(true); const keys = useRef<Record<string, string>>({});
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  const retract = async () => {
    setBusy(true); setError("");
    try {
      const key = keys.current[reason] ?? (keys.current[reason] = crypto.randomUUID());
      await write(`/progress/evidence/${evidence.id}/retract`, { reason, idempotencyKey: key });
      if (alive.current) { setEditing(false); onChanged(); }
    } catch (error) { if (alive.current) setError(errorText(error)); }
    finally { if (alive.current) setBusy(false); }
  };
  const payload = evidence.payload;
  const sourceName = payload.source === "RULE_BASED" ? "规则判分" : payload.source === "USER_PRACTICE" ? "代码实践提交" : payload.source === "USER_REPORTED" ? "用户报告" : payload.source === "NOT_ASSESSED" ? "待自评" : "用户自报";
  const outcomeName = payload.rating ? gradeNames[payload.rating] : payload.status ? practiceResultNames[payload.status] ?? payload.status : payload.verdict ? ({ CORRECT: "回答正确", INCORRECT: "回答错误", NEEDS_REVIEW: "待复盘", SKIPPED: "跳过", UNANSWERED: "未回答" }[payload.verdict] ?? "学习记录") : payload.source === "USER_PRACTICE" ? "代码提交" : "学习记录";
  return <article className={`evidence-item ${evidence.retracted ? "evidence-retracted" : ""}`}><div className="study-heading"><strong>{sourceName} · {outcomeName}</strong><small>{timeText(evidence.occurredAtMs)}</small></div>
    <p>{payload.question?.prompt ?? payload.card?.question ?? payload.reflection}</p>{payload.recalledAnswer && <p>当时回忆 {payload.recalledAnswer}</p>}{payload.answer && <p>当时回答 {String(payload.answer.value)}</p>}
    {payload.reflection && (payload.card || payload.question) && <p>{payload.reflection}</p>}
    {payload.submissionId && <div><p className="study-muted">提交 {payload.sequenceNo ?? "-"} · {payload.changeCount ?? 0} 个文件变更 · 内容 {(payload.contentDigest ?? "").slice(0, 12)}</p>{payload.taskId && <button onClick={() => studyUrl("training", { trainingTaskId: payload.taskId! })}>查看代码实践</button>}</div>}
    {payload.testResultId && <div><p>报告状态 {practiceResultNames[payload.status ?? ""] ?? payload.status} · {payload.method} · {payload.environment}</p>{payload.report && <details><summary>报告内容</summary><pre>{payload.report}</pre></details>}</div>}
    {payload.limitation && <p className="study-muted">{payload.limitation}</p>}
    <p className="study-muted">知识版本 {evidence.knowledgeRevision}{evidence.knowledgeRevision !== currentRevision && " · 历史版本证据"}{payload.assisted && " · 提交前使用过帮助"} · {payload.association === "USER_ASSOCIATED" ? "用户关联" : "卡片复习时关联"}{evidence.retracted && " · 已撤回"}</p>
    <div className="button-row">{payload.attemptId && <button onClick={() => studyUrl("training", { trainingAttemptId: payload.attemptId!, trainingQuestionId: payload.questionId ?? "" })}>查看原始作答</button>}
      {payload.reviewEventId && payload.card && <button onClick={() => studyUrl("reviews", { reviewCardId: payload.card!.id, reviewEventId: payload.reviewEventId! })}>查看复习历史</button>}
      {!evidence.retracted && <button disabled={busy} onClick={() => setEditing(previous => !previous)}>撤回关联</button>}</div>
    {(payload.sources ?? payload.card?.sources ?? []).map(source => <button key={source.id} onClick={() => onSource(source)}>{source.filePath} · L{source.startLine}–L{source.endLine}</button>)}
    {editing && <div><label>撤回原因<textarea aria-label="撤回原因" value={reason} maxLength={2000} onChange={event => setReason(event.target.value)} disabled={busy}/></label><button disabled={busy || !reason.trim()} onClick={retract}>确认撤回</button></div>}{error && <p role="alert" className="study-error">{error}</p>}
  </article>;
}

function RelatedReviews({ knowledgeId }: { knowledgeId: string }) {
  const [page, setPage] = useState<StudyPage<{ cardId: string; title: string; dueAtMs: number }> | null>(null);
  const [offset, setOffset] = useState(0);
  const [error, setError] = useState("");
  useEffect(() => {
    const controller = new AbortController();
    get<typeof page>(`/reviews/cards?knowledgeId=${knowledgeId}&dueOnly=false&offset=${offset}&limit=10`, controller.signal)
      .then(setPage).catch(error => { if (!controller.signal.aborted) setError(errorText(error)); });
    return () => controller.abort();
  }, [knowledgeId, offset]);
  return <section className="progress-reviews"><h3>关联卡片到期情况</h3><p className="study-muted">显示已启用且未暂停的复习卡片。</p>
    {error && <p role="alert" className="study-error">{error}</p>}
    {page?.items.map(card => <article className="review-history-item" key={card.cardId}>
      <button onClick={() => studyUrl("reviews", { reviewCardId: card.cardId })}>{card.title}</button>
      <p>到期时间 {timeText(card.dueAtMs)}</p>
    </article>)}
    {page?.total === 0 && <p>暂无启用的关联复习卡片。</p>}
    <div className="button-row"><button disabled={!offset} onClick={() => setOffset(previous => Math.max(0, previous - 10))}>上一页卡片</button><button disabled={!page || offset + 10 >= page.total} onClick={() => setOffset(previous => previous + 10)}>下一页卡片</button></div>
  </section>;
}

function KnowledgeProgress({ id, onChanged, onSource }: { id: string; onChanged: () => void; onSource: (source: EvidenceSource) => void }) {
  const [progress, setProgress] = useState<Progress | null>(null); const [revision, setRevision] = useState(0);
  const [rating, setRating] = useState("NEEDS_REVIEW"); const [reflection, setReflection] = useState(""); const [error, setError] = useState(""); const [busy, setBusy] = useState(false);
  const [history, setHistory] = useState<StudyPage<{ id: string; knowledgeRevision: number; payload: Progress["assessment"]; createdAtMs: number }> | null>(null);
  const [historyOffset, setHistoryOffset] = useState(0);
  const alive = useRef(true); const keys = useRef<Record<string, string>>({});
  useEffect(() => {
    alive.current = true; const controller = new AbortController();
    get<Progress>(`/progress/knowledge/${id}`, controller.signal).then(setProgress).catch(error => { if (!controller.signal.aborted) setError(errorText(error)); });
    return () => { alive.current = false; controller.abort(); };
  }, [id, revision]);
  const changed = () => { setRevision(previous => previous + 1); setHistory(null); onChanged(); };
  const save = async () => {
    setBusy(true); setError("");
    try {
      const signature = JSON.stringify([rating, reflection]); const key = keys.current[signature] ?? (keys.current[signature] = crypto.randomUUID());
      await write("/progress/evidence", { knowledgeId: id, sourceType: "PERSONAL_REFLECTION", rating, reflection, idempotencyKey: key });
      delete keys.current[signature];
      if (alive.current) { setReflection(""); changed(); }
    } catch (error) { if (alive.current) setError(errorText(error)); }
    finally { if (alive.current) setBusy(false); }
  };
  const loadHistory = async (offset = 0) => {
    try { const result = await get<typeof history>(`/progress/knowledge/${id}/history?limit=20&offset=${offset}`); if (alive.current) { setHistory(result); setHistoryOffset(offset); } }
    catch (error) { if (alive.current) setError(errorText(error)); }
  };
  return <section className="study-detail progress-detail">{error && <p role="alert" className="study-error">{error}</p>}{progress ? <><h2>{progress.title}</h2><small>知识版本 {progress.revision}</small>
    <section className="progress-assessment"><h3>{progressNames[progress.assessment.status]}</h3><p>{progress.assessment.reason}</p><p>自报记住/简单复习 {progress.assessment.positiveRecalls} 次 · 无帮助正确作答 {progress.assessment.unassistedAttempts} 个独立尝试 · 用户报告通过的实践测试 {progress.assessment.practicePasses ?? 0} 次</p><small>{progress.assessment.ruleVersion} · {progress.historicalEvidenceCount} 条历史版本证据</small></section>
    <fieldset disabled={busy}><label>个人记录评分<select aria-label="个人记录评分" value={rating} onChange={event => setRating(event.target.value)}><option value="UNDERSTOOD">已理解</option><option value="PARTIAL">部分理解</option><option value="NEEDS_REVIEW">需要复盘</option></select></label><label>个人复盘<textarea aria-label="个人复盘" maxLength={4000} value={reflection} onChange={event => setReflection(event.target.value)}/></label><button onClick={save}>保存个人记录</button></fieldset>
    <RelatedReviews knowledgeId={id}/>
    <h3>学习证据</h3>{progress.evidence.map(evidence => <EvidenceItem key={evidence.id} evidence={evidence} currentRevision={progress.revision} onChanged={changed} onSource={onSource}/>)}
    {!progress.evidence.length && <p className="study-empty">还没有关联的学习证据。</p>}<button onClick={() => loadHistory()}>查看判断历史</button>
    {history?.items.map(item => <details key={item.id} className="review-history-item"><summary>{timeText(item.createdAtMs)} · 知识版本 {item.knowledgeRevision} · {progressNames[item.payload.status]}</summary><p>{item.payload.reason}</p><small>{item.payload.ruleVersion}</small></details>)}
    {history && <div className="button-row"><button disabled={!historyOffset} onClick={() => loadHistory(Math.max(0, historyOffset - 20))}>较新判断</button><button disabled={historyOffset + 20 >= history.total} onClick={() => loadHistory(historyOffset + 20)}>较早判断</button></div>}
  </> : <p>正在加载学习证据…</p>}</section>;
}

export default function ProgressWorkspace({ onSource }: { onSource: (source: EvidenceSource) => void }) {
  const params = useStudyLocation(); const id = params.get("progressKnowledgeId") ?? "";
  const [page, setPage] = useState<StudyPage<Progress> | null>(null); const [query, setQuery] = useState(""); const [status, setStatus] = useState(""); const [offset, setOffset] = useState(0); const [revision, setRevision] = useState(0);
  const [mistakes, setMistakes] = useState<StudyPage<{ id: string; attemptId: string; questionId: string; question: { prompt: string }; verdict: string }> | null>(null);
  const [mistakeOffset, setMistakeOffset] = useState(0); const [showMistakes, setShowMistakes] = useState(false); const [error, setError] = useState("");
  const [association, setAssociation] = useState<{ sourceId: string; questionId: string } | null>(null);
  useEffect(() => {
    const controller = new AbortController();
    get<typeof page>(`/progress/knowledge?query=${encodeURIComponent(query)}&status=${status}&offset=${offset}&limit=20`, controller.signal).then(setPage).catch(error => { if (!controller.signal.aborted) setError(errorText(error)); });
    if (showMistakes) get<typeof mistakes>(`/progress/mistakes?offset=${mistakeOffset}&limit=20`, controller.signal).then(setMistakes).catch(error => { if (!controller.signal.aborted) setError(errorText(error)); });
    return () => controller.abort();
  }, [query, status, offset, revision, showMistakes, mistakeOffset]);
  return <main className="study-workspace"><aside className="study-list"><h1>学习进度</h1><label>搜索学习进度<input aria-label="搜索学习进度" value={query} onChange={event => { setQuery(event.target.value); setOffset(0); }}/></label><label>证据状态<select aria-label="证据状态" value={status} onChange={event => { setStatus(event.target.value); setOffset(0); }}><option value="">全部</option>{Object.entries(progressNames).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
    {page?.items.map(item => <button key={item.id} className={`study-list-item ${item.id === id ? "selected" : ""}`} onClick={() => { setShowMistakes(false); studyUrl("progress", { progressKnowledgeId: item.id }); }}><strong>{item.title}</strong><small>{progressNames[item.assessment.status]} · 版本 {item.revision}</small></button>)}
    <div className="button-row"><button disabled={!offset} onClick={() => setOffset(previous => Math.max(0, previous - 20))}>上一页</button><button disabled={!page || offset + 20 >= page.total} onClick={() => setOffset(previous => previous + 20)}>下一页</button></div><button onClick={() => setShowMistakes(true)}>待复盘题目</button>
  </aside><div className="study-content">{error && <p className="study-error">{error}</p>}{showMistakes ? <section className="study-detail"><h2>待复盘题目</h2>{mistakes?.items.map(item => <article key={item.id} className="evidence-item"><h3>{item.question.prompt}</h3><p>{item.verdict === "INCORRECT" ? "回答错误" : "开放题待复盘"}</p><div className="button-row"><button onClick={() => studyUrl("training", { trainingAttemptId: item.attemptId, trainingQuestionId: item.questionId })}>查看原始作答</button><button onClick={() => setAssociation({ sourceId: item.attemptId, questionId: item.questionId })}>关联知识</button></div></article>)}
    <div className="button-row"><button disabled={!mistakeOffset} onClick={() => setMistakeOffset(previous => Math.max(0, previous - 20))}>上一页</button><button disabled={!mistakes || mistakeOffset + 20 >= mistakes.total} onClick={() => setMistakeOffset(previous => previous + 20)}>下一页</button></div></section> : id ? <KnowledgeProgress key={id} id={id} onSource={onSource} onChanged={() => setRevision(previous => previous + 1)}/> : <section className="study-detail"><h2>学习证据与复盘</h2><p>选择知识查看具体依据，或查看待复盘题目。缺少记录时显示暂无证据。</p></section>}</div>
    {association && <EvidencePicker sourceType="TRAINING_RESULT" {...association} onClose={() => setAssociation(null)} onAssociated={() => setRevision(previous => previous + 1)}/>}
  </main>;
}
