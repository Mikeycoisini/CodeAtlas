import { useEffect, useRef, useState } from "react";
import { mutate, type EvidenceSource, type SourceSelection } from "../learning/learningApi";
import SourcePicker from "../learning/SourcePicker";
import TrainingSources from "./TrainingSources";
import TrainingAiPanel from "./TrainingAiPanel";
import { blankTask, change, formats, message, newQuestion, taskBody, training, trainingUrl, type Candidate, type Format, type Question, type TrainingTask } from "./trainingApi";

export default function TaskEditor({ taskId, draftId, onSource }: { taskId: string; draftId: string; onSource: (source: EvidenceSource) => void }) {
  const storageKey = `codeatlas-training-${taskId || draftId || "new"}`;
  const [task, setTask] = useState<TrainingTask | null>(null);
  const [dirty, setDirty] = useState(false);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [picker, setPicker] = useState(false);
  const [ai, setAi] = useState(!!new URLSearchParams(location.search).get("trainingAiGenerationId"));
  const alive = useRef(true);
  const createKey = useRef(crypto.randomUUID());
  useEffect(() => {
    alive.current = true;
    const controller = new AbortController();
    const load = async () => {
      const cached = sessionStorage.getItem(storageKey);
      if (cached) { const draft = JSON.parse(cached); if (draft.dirty && draft.task) { setTask(draft.task); setDirty(true); return; } }
      if (taskId) { const item = await training<TrainingTask>(`/tasks/${taskId}/editor`, controller.signal); if (!controller.signal.aborted) setTask(item); return; }
      const item = blankTask();
      const seed = sessionStorage.getItem(`codeatlas-training-seed-${draftId}`);
      if (seed) {
        const source = await mutate<EvidenceSource>("/sources", JSON.parse(seed) as SourceSelection);
        item.sourceIds = [source.id]; item.sources = [source];
      }
      if (!controller.signal.aborted) { setTask(item); setDirty(true); }
    };
    load().catch(error => { if (!controller.signal.aborted) setError(message(error)); });
    return () => { alive.current = false; controller.abort(); };
  }, []);
  useEffect(() => { if (task && dirty) sessionStorage.setItem(storageKey, JSON.stringify({ task, dirty: true })); }, [task, dirty, storageKey]);
  const patch = (values: Partial<TrainingTask>) => { setDirty(true); setTask(previous => previous && ({ ...previous, ...values })); };
  const editQuestion = (id: string, values: Partial<Question>) => { setDirty(true); setTask(previous => previous && ({ ...previous, questions: previous.questions.map(item => item.id === id ? { ...item, ...values } : item) })); };
  const reloadLatest = async () => {
    if (!taskId) return;
    setBusy(true); setError("");
    try {
      const latest = await training<TrainingTask>(`/tasks/${taskId}/editor`);
      if (alive.current) { setDirty(false); sessionStorage.removeItem(storageKey); setTask(latest); }
    } catch (error) { if (alive.current) setError(message(error)); }
    finally { if (alive.current) setBusy(false); }
  };
  const save = async (status: string) => {
    if (!task) return;
    setBusy(true); setError("");
    try {
      const saved = await change<TrainingTask>(task.id ? `/tasks/${task.id}` : "/tasks", { ...taskBody(task, status), idempotencyKey: task.id ? undefined : createKey.current }, task.id ? "PUT" : "POST");
      if (!alive.current) return;
      setTask(saved);
      setDirty(false);
      sessionStorage.removeItem(storageKey);
      sessionStorage.removeItem(`codeatlas-training-${saved.id}`);
      trainingUrl({ trainingTaskId: saved.id, trainingMode: status === "ACTIVE" ? "" : "edit" });
    } catch (error) { if (alive.current) setError(message(error)); }
    finally { if (alive.current) setBusy(false); }
  };
  const apply = (candidate: Candidate, generationId: string) => {
    if (!task) return;
    const existing = task.questions.filter(item => item.prompt.trim() || item.referenceExplanation?.trim() || item.hints?.length || item.options.some(option => option.text.trim()) || String(item.correctAnswer ?? "").trim());
    const additions = candidate.questions.filter(item => !existing.some(previous => previous.id === item.id));
    if (existing.length + additions.length > 10) { setError("每个任务最多十题，请先调整题目数量"); return; }
    patch({ questions: [...existing, ...additions], title: task.title || candidate.title, goal: task.goal || candidate.goal,
      constraints: task.constraints || candidate.constraints, acceptanceCriteria: task.acceptanceCriteria || candidate.acceptanceCriteria, generationId });
    closeAi();
  };
  const closeAi = () => { setAi(false); const params = new URLSearchParams(location.search); params.delete("trainingAiGenerationId"); history.replaceState(null, "", `?${params}`); };
  return <section className="training-editor">{error && <p className="learning-error" role="alert">{error}</p>}{!task ? <p>正在加载训练草稿…</p> : <>
    <div className="training-heading"><h2>{task.id ? "编辑训练任务" : "创建训练任务"}</h2><span>{task.id && `版本 ${task.revision}`}</span></div>
    {task.id && <button disabled={busy} onClick={reloadLatest}>放弃本地修改并加载最新版本</button>}
    <fieldset disabled={busy}><label>任务标题<input aria-label="任务标题" maxLength={200} value={task.title} onChange={event => patch({ title: event.target.value })}/></label>
      <label>学习目标<textarea aria-label="学习目标" maxLength={4000} value={task.goal} onChange={event => patch({ goal: event.target.value })}/></label>
      <div className="field-row"><label>约束<textarea aria-label="约束" maxLength={4000} value={task.constraints} onChange={event => patch({ constraints: event.target.value })}/></label><label>验收条件<textarea aria-label="验收条件" maxLength={4000} value={task.acceptanceCriteria} onChange={event => patch({ acceptanceCriteria: event.target.value })}/></label></div>
      <label>标签<input aria-label="标签" value={task.tags.join(",")} onChange={event => patch({ tags: event.target.value.split(",") })}/></label>
      <TrainingSources sources={task.sources} onSource={onSource}/><div className="button-row"><button onClick={() => setPicker(true)} disabled={task.sourceIds.length >= 6}>关联源码</button>
        {task.sources.map(source => <button key={source.id} onClick={() => patch({ sourceIds: task.sourceIds.filter(id => id !== source.id), sources: task.sources.filter(item => item.id !== source.id), generationId: "" })}>移除 {source.symbolName || source.filePath}</button>)}
        <button onClick={() => setAi(true)} disabled={!task.sourceIds.length || !task.goal.trim()}>AI出题</button></div>
      {task.questions.map((question, index) => { const prefix = `第${index + 1}题`; return <article className="training-question" key={question.id}><div className="training-heading"><h3>{prefix}</h3><button onClick={() => patch({ questions: task.questions.filter(item => item.id !== question.id) })}>删除题目</button></div>
        <div className="field-row"><label>类别<select aria-label={`${prefix}类别`} value={question.category} onChange={event => editQuestion(question.id, { category: event.target.value })}><option value="CODE_READING">代码阅读</option><option value="CALL_CHAIN">调用链理解</option><option value="ERROR_JUDGMENT">错误判断</option></select></label>
          <label>题型<select aria-label={`${prefix}题型`} value={question.format} onChange={event => editQuestion(question.id, { format: event.target.value as Format, correctAnswer: null, options: event.target.value === "SINGLE_CHOICE" ? [{ id: "A", text: "" }, { id: "B", text: "" }] : [] })}>{Object.entries(formats).map(([value, label]) => <option value={value} key={value}>{label}</option>)}</select></label></div>
        <label>题干<textarea aria-label={`${prefix}题干`} maxLength={4000} value={question.prompt} onChange={event => editQuestion(question.id, { prompt: event.target.value })}/></label>
        {question.format === "SINGLE_CHOICE" && <div>{question.options.map((option, optionIndex) => <label key={option.id}>选项 {option.id}<input aria-label={`${prefix}选项${option.id}`} maxLength={500} value={option.text} onChange={event => editQuestion(question.id, { options: question.options.map((item, i) => i === optionIndex ? { ...item, text: event.target.value } : item) })}/><button disabled={question.options.length <= 2} onClick={() => editQuestion(question.id, { options: question.options.filter(item => item.id !== option.id), correctAnswer: question.correctAnswer === option.id ? "" : question.correctAnswer })}>删除选项 {option.id}</button></label>)}<button disabled={question.options.length >= 6} onClick={() => { const id = "ABCDEF".split("").find(value => !question.options.some(item => item.id === value))!; editQuestion(question.id, { options: [...question.options, { id, text: "" }] }); }}>添加选项</button></div>}
        <label>参考答案{question.format === "SHORT_ANSWER" ? <textarea aria-label={`${prefix}参考答案`} maxLength={4000} value={String(question.correctAnswer ?? "")} onChange={event => editQuestion(question.id, { correctAnswer: event.target.value })}/> : <select aria-label={`${prefix}参考答案`} value={String(question.correctAnswer ?? "")} onChange={event => editQuestion(question.id, { correctAnswer: question.format === "TRUE_FALSE" ? event.target.value === "" ? null : event.target.value === "true" : event.target.value })}><option value="">请选择</option>{question.format === "TRUE_FALSE" ? <><option value="true">正确</option><option value="false">错误</option></> : question.options.map(option => <option key={option.id} value={option.id}>{option.id}</option>)}</select>}</label>
        <label>参考解释<textarea aria-label={`${prefix}参考解释`} maxLength={8000} value={question.referenceExplanation} onChange={event => editQuestion(question.id, { referenceExplanation: event.target.value })}/></label>
        <label>提示（每行一条，最多五条）<textarea aria-label={`${prefix}提示`} value={question.hints?.join("\n") ?? ""} onChange={event => editQuestion(question.id, { hints: event.target.value.split("\n") })}/></label>
      </article>; })}
      <div className="button-row"><button disabled={task.questions.length >= 10} onClick={() => patch({ questions: [...task.questions, newQuestion()] })}>添加题目</button><button onClick={() => save("DRAFT")}>保存草稿</button><button className="primary" onClick={() => save("ACTIVE")}>发布任务</button></div>
    </fieldset>
    {picker && <SourcePicker onClose={() => setPicker(false)} onSelect={source => {
      const first = task.sources[0];
      if (first && (first.snapshotId !== source.snapshotId || first.generationId !== source.generationId)) { setError("任务来源须属于同一快照与代次"); setPicker(false); return; }
      if (!task.sourceIds.includes(source.id)) patch({ sourceIds: [...task.sourceIds, source.id], sources: [...task.sources, source] });
      setPicker(false);
    }}/>}
    {ai && <TrainingAiPanel sourceIds={task.sourceIds} goal={task.goal} restoredId={new URLSearchParams(location.search).get("trainingAiGenerationId") ?? ""} onApply={apply} onClose={closeAi}/>}
  </>}</section>;
}
