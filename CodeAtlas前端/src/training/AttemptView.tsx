import { useEffect, useRef, useState } from "react";
import type { EvidenceSource } from "../learning/learningApi";
import TrainingSources from "./TrainingSources";
import TrainingAiPanel from "./TrainingAiPanel";
import EvidencePicker from "../progress/EvidencePicker";
import useStudyLocation from "../study/useStudyLocation";
import { change, formats, message, statuses, training, trainingUrl, verdicts, type Answer, type Attempt } from "./trainingApi";

export default function AttemptView({ id, onSource }: { id: string; onSource: (source: EvidenceSource) => void }) {
  const selectedQuestionId = useStudyLocation().get("trainingQuestionId") ?? "";
  const [attempt, setAttempt] = useState<Attempt | null>(null);
  const [answers, setAnswers] = useState<Answer[]>([]);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const [ratings, setRatings] = useState<Record<string, string>>({});
  const [reflections, setReflections] = useState<Record<string, string>>({});
  const [aiQuestion, setAiQuestion] = useState(() => { const params = new URLSearchParams(location.search); return params.has("trainingAiGenerationId") ? params.get("trainingQuestionId") ?? "" : ""; });
  const [evidenceSelection, setEvidenceSelection] = useState<{ sourceType: string; sourceId: string; questionId?: string } | null>(null);
  const alive = useRef(true);
  const keys = useRef<Record<string, string>>({});
  const stableKey = (name: string) => keys.current[name] ?? (keys.current[name] = crypto.randomUUID());
  useEffect(() => {
    if (!attempt || !selectedQuestionId) return;
    const question = document.getElementById(`training-question-${selectedQuestionId}`);
    question?.scrollIntoView({ block: "start" });
    question?.focus({ preventScroll: true });
  }, [attempt?.id, selectedQuestionId]);
  const apply = (item: Attempt, restoreAnswers = false) => {
    setAttempt(item);
    if (restoreAnswers) setAnswers(item.task.questions.map(question => item.answers.find(answer => answer.questionId === question.id) ?? { questionId: question.id, value: null, skipped: false }));
  };
  useEffect(() => {
    alive.current = true;
    const controller = new AbortController();
    training<Attempt>(`/attempts/${id}`, controller.signal).then(item => { if (!controller.signal.aborted) apply(item, true); }).catch(error => { if (!controller.signal.aborted) setError(message(error)); });
    return () => { alive.current = false; controller.abort(); };
  }, [id]);
  const act = async (work: () => Promise<void>) => {
    setBusy(true); setError(""); setNotice("");
    try { await work(); } catch (error) { if (alive.current) setError(message(error)); }
    finally { if (alive.current) setBusy(false); }
  };
  const save = (operation = "") => act(async () => {
    if (!attempt) return;
    const item = await change<Attempt>(`/attempts/${id}${operation ? `/${operation}` : ""}`, { revision: attempt.revision, answers,
      idempotencyKey: operation ? stableKey(`${operation}-${attempt.revision}`) : undefined }, operation ? "POST" : "PUT");
    if (alive.current) { apply(item, true); setNotice(operation ? "本次尝试已结束" : "已保存回答"); }
  });
  const reveal = (questionId: string, kind: string, hintIndex = 0) => act(async () => {
    await change(`/attempts/${id}/reveal`, { questionId, kind, hintIndex, idempotencyKey: stableKey(`${questionId}-${kind}-${hintIndex}`) });
    const item = await training<Attempt>(`/attempts/${id}`);
    if (alive.current) apply(item);
  });
  const assess = (questionId: string) => act(async () => {
    const rating = ratings[questionId] || "NEEDS_REVIEW"; const reflection = reflections[questionId] || "";
    await change(`/attempts/${id}/self-assessments`, { questionId, rating, reflection, idempotencyKey: stableKey(JSON.stringify([questionId, rating, reflection])) });
    const item = await training<Attempt>(`/attempts/${id}`);
    if (alive.current) apply(item);
  });
  const restart = () => act(async () => {
    if (!attempt) return;
    const next = await change<Attempt>(`/tasks/${attempt.taskId}/attempts`, { idempotencyKey: stableKey("restart") });
    if (alive.current) trainingUrl({ trainingAttemptId: next.id, trainingTaskId: next.taskId });
  });
  const closeAi = () => { setAiQuestion(""); const params = new URLSearchParams(location.search); params.delete("trainingQuestionId"); params.delete("trainingAiGenerationId"); history.replaceState(null, "", `?${params}`);
    training<Attempt>(`/attempts/${id}`).then(item => { if (alive.current) apply(item); }).catch(error => { if (alive.current) setError(message(error)); }); };
  const openAi = (questionId: string, generationId = "") => {
    setAiQuestion(questionId); const params = new URLSearchParams(location.search); params.set("trainingQuestionId", questionId);
    if (generationId) params.set("trainingAiGenerationId", generationId); else params.delete("trainingAiGenerationId");
    history.replaceState(null, "", `?${params}`);
  };
  if (!attempt) return <section className="training-detail">{error ? <p role="alert">{error}</p> : <p>正在加载尝试…</p>}</section>;
  const terminal = attempt.status !== "DRAFT";
  return <section className="training-detail"><div className="training-heading"><h2>{attempt.task.title}</h2><span className="training-attempt-status">{statuses[attempt.status]} · 任务版本 {attempt.taskRevision}</span></div>
    <p>{attempt.task.goal}</p><p>{attempt.task.constraints}</p><h3>验收条件</h3><p>{attempt.task.acceptanceCriteria}</p>
    <small>尝试 {attempt.id} · 定义 {attempt.definitionDigest.slice(0, 12)}{attempt.submissionDigest && ` · 提交 ${attempt.submissionDigest.slice(0, 12)}`}</small>
    {terminal && <p className="training-summary">客观题正确 {attempt.results.filter(item => item.source === "RULE_BASED" && item.verdict === "CORRECT").length} / {attempt.results.filter(item => item.source === "RULE_BASED").length} · 开放题待复盘 {attempt.results.filter(item => item.verdict === "NEEDS_REVIEW").length} · 跳过 {attempt.results.filter(item => item.verdict === "SKIPPED").length}</p>}
    {error && <p className="learning-error" role="alert">{error}</p>}{notice && <p className="learning-success" role="status">{notice}</p>}
    <TrainingSources sources={attempt.task.sources} onSource={onSource}/>
    {attempt.task.questions.map((question, index) => {
      const prefix = `第${index + 1}题`; const answer = answers.find(item => item.questionId === question.id);
      const result = attempt.results.find(item => item.questionId === question.id);
      const setAnswer = (values: Partial<Answer>) => setAnswers(previous => previous.map(item => item.questionId === question.id ? { ...item, ...values } : item));
      const reveals = attempt.reveals.filter(item => item.questionId === question.id);
      const reference = reveals.find(item => item.kind === "REFERENCE");
      return <article className="training-question" id={`training-question-${question.id}`} data-selected={question.id === selectedQuestionId} tabIndex={-1} key={question.id}><h3>{prefix} · {formats[question.format]}</h3><p className="training-prompt">{question.prompt}</p>
        <fieldset disabled={terminal || busy}><label>我的回答{question.format === "SHORT_ANSWER" ? <textarea aria-label={`${prefix}回答`} maxLength={4000} rows={5} value={String(answer?.value ?? "")} onChange={event => setAnswer({ value: event.target.value })}/> : <select aria-label={`${prefix}回答`} value={String(answer?.value ?? "")} onChange={event => setAnswer({ value: question.format === "TRUE_FALSE" ? event.target.value === "" ? null : event.target.value === "true" : event.target.value || null })}><option value="">请选择</option>{question.format === "TRUE_FALSE" ? <><option value="true">正确</option><option value="false">错误</option></> : question.options.map(option => <option key={option.id} value={option.id}>{option.id} · {option.text}</option>)}</select>}</label>
          <label className="authorization"><input type="checkbox" checked={answer?.skipped ?? false} onChange={event => setAnswer({ skipped: event.target.checked })}/>跳过此题</label></fieldset>
        <div className="button-row">{Array.from({ length: question.hintCount ?? 0 }, (_, hintIndex) => <button disabled={busy} key={hintIndex} onClick={() => reveal(question.id, "HINT", hintIndex)}>查看提示 {hintIndex + 1}</button>)}<button disabled={busy} onClick={() => reveal(question.id, "REFERENCE")}>查看参考答案</button></div>
        {reveals.filter(item => item.kind === "HINT").map(item => <p key={item.id} className="training-hint">{item.content}</p>)}
        {reference && <div className="training-reference"><h4>参考答案</h4><p>{String(reference.content)}</p><p>{reference.explanation}</p></div>}
        {result && <div className="training-result"><strong>{verdicts[result.verdict]}</strong><span>{result.source === "RULE_BASED" ? "规则判分" : "开放题复盘"}</span>{result.hintViewed && <p>提交前查看过提示</p>}{result.referenceViewed && <p>提交前查看过参考答案</p>}</div>}
        {attempt.status === "SUBMITTED" && <><button disabled={busy} onClick={() => openAi(question.id)}>AI辅助反馈</button><button disabled={busy} onClick={() => setEvidenceSelection({ sourceType: "TRAINING_RESULT", sourceId: id, questionId: question.id })}>关联知识</button>
          {question.format === "SHORT_ANSWER" && <fieldset disabled={busy}><label>自评<select aria-label={`${prefix}自评`} value={ratings[question.id] || "NEEDS_REVIEW"} onChange={event => setRatings(previous => ({ ...previous, [question.id]: event.target.value }))}><option value="UNDERSTOOD">已理解</option><option value="PARTIAL">部分理解</option><option value="NEEDS_REVIEW">需要复盘</option></select></label><label>复盘<textarea aria-label={`${prefix}复盘`} maxLength={4000} value={reflections[question.id] || ""} onChange={event => setReflections(previous => ({ ...previous, [question.id]: event.target.value }))}/></label><button onClick={() => assess(question.id)}>保存自评</button></fieldset>}
        </>}
        {attempt.selfAssessments.filter(item => item.questionId === question.id).map(item => <div className="training-reference" key={item.id}><strong>用户自评 · {item.rating === "UNDERSTOOD" ? "已理解" : item.rating === "PARTIAL" ? "部分理解" : "需要复盘"}</strong><p>{item.reflection}</p><button onClick={() => setEvidenceSelection({ sourceType: "TRAINING_SELF_ASSESSMENT", sourceId: item.id })}>关联这条自评</button></div>)}
        {attempt.aiFeedback.filter(item => item.questionId === question.id).map(item => <div className="training-ai-feedback" key={item.id}><strong>AI辅助建议 · {item.status}</strong>{item.result && <><p>{verdicts[item.result.suggestedVerdict]}</p><p>{item.result.explanation}</p></>}<button onClick={() => openAi(question.id, item.id)}>查看反馈详情</button></div>)}
      </article>;
    })}
    <div className="button-row">{terminal ? <button className="primary" disabled={busy} onClick={restart}>重新作答</button> : <><button disabled={busy} onClick={() => save()}>保存回答</button><button className="primary" disabled={busy} onClick={() => save("submit")}>提交回答</button><button disabled={busy} onClick={() => save("skip")}>跳过本次</button></>}</div>
    {aiQuestion && <TrainingAiPanel key={aiQuestion} sourceIds={attempt.task.sourceIds} goal={attempt.task.goal} attemptId={id} questionId={aiQuestion} restoredId={new URLSearchParams(location.search).get("trainingAiGenerationId") ?? ""} onClose={closeAi}/>}
    {evidenceSelection && <EvidencePicker {...evidenceSelection} onClose={() => setEvidenceSelection(null)} onAssociated={() => { if (alive.current) setNotice("已关联知识，学习进度保留原判分与帮助记录"); }}/>}
  </section>;
}
