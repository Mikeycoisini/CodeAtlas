import { useEffect, useRef, useState } from "react";
import type { Page } from "../api";
import type { EvidenceSource } from "../learning/learningApi";
import TaskEditor from "./TaskEditor";
import AttemptView from "./AttemptView";
import TrainingSources from "./TrainingSources";
import PracticePanel from "./PracticePanel";
import { change, message, statuses, taskBody, training, trainingUrl, type Attempt, type TrainingTask } from "./trainingApi";
import "./training.css";

function TaskDetail({ id, onSource }: { id: string; onSource: (source: EvidenceSource) => void }) {
  const [task, setTask] = useState<TrainingTask | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const alive = useRef(true);
  const startKey = useRef(crypto.randomUUID());
  useEffect(() => {
    alive.current = true; const controller = new AbortController();
    training<TrainingTask>(`/tasks/${id}`, controller.signal).then(setTask).catch(error => { if (!controller.signal.aborted) setError(message(error)); });
    return () => { alive.current = false; controller.abort(); };
  }, [id]);
  const run = async (operation: string) => {
    setBusy(true); setError("");
    try {
      if (operation === "start") {
        const item = await change<Attempt>(`/tasks/${id}/attempts`, { idempotencyKey: startKey.current });
        if (alive.current) trainingUrl({ trainingAttemptId: item.id, trainingTaskId: id });
      } else if (operation === "delete") {
        await change(`/tasks/${id}`, {}, "DELETE"); if (alive.current) trainingUrl({});
      } else {
        const editor = await training<TrainingTask>(`/tasks/${id}/editor`);
        const item = await change<TrainingTask>(`/tasks/${id}`, taskBody(editor, "ARCHIVED"), "PUT");
        if (alive.current) setTask(item);
      }
    } catch (error) { if (alive.current) setError(message(error)); }
    finally { if (alive.current) setBusy(false); }
  };
  return <section className="training-detail">{error && <p className="learning-error" role="alert">{error}</p>}{task && <><div className="training-heading"><h2>{task.title}</h2><span>{statuses[task.status]} · 版本 {task.revision}</span></div><p>{task.goal}</p><p>{task.constraints}</p><h3>验收条件</h3><p>{task.acceptanceCriteria}</p><p>{task.questions.length} 道题 · {task.tags.join(" · ")}</p><TrainingSources sources={task.sources} onSource={onSource}/>
    <div className="button-row"><button className="primary" disabled={busy || task.status !== "ACTIVE"} onClick={() => run("start")}>开始作答</button><button disabled={busy} onClick={() => trainingUrl({ trainingTaskId: id, trainingMode: "edit" })}>编辑任务</button><button disabled={busy || task.status === "ARCHIVED"} onClick={() => run("archive")}>归档任务</button><button disabled={busy} onClick={() => run("delete")}>删除任务</button></div><PracticePanel task={task}/></>}</section>;
}

export default function TrainingWorkspace({ onSource }: { onSource: (source: EvidenceSource) => void }) {
  const [locationKey, setLocationKey] = useState(location.search);
  const [tasks, setTasks] = useState<Page<TrainingTask> | null>(null);
  const [attempts, setAttempts] = useState<Page<{ id: string; title: string; taskId: string; taskRevision: number; status: string }> | null>(null);
  const [query, setQuery] = useState(""); const [status, setStatus] = useState("");
  const [offset, setOffset] = useState(0); const [historyOffset, setHistoryOffset] = useState(0);
  const [error, setError] = useState("");
  const params = new URLSearchParams(locationKey);
  const taskId = params.get("trainingTaskId") ?? ""; const attemptId = params.get("trainingAttemptId") ?? "";
  const editing = params.get("trainingMode") === "edit"; const draftId = params.get("trainingDraftId") ?? "";
  useEffect(() => { const update = () => setLocationKey(location.search); addEventListener("popstate", update); return () => removeEventListener("popstate", update); }, []);
  useEffect(() => {
    const controller = new AbortController();
    training<Page<TrainingTask>>(`/tasks?query=${encodeURIComponent(query)}&status=${status}&limit=20&offset=${offset}`, controller.signal).then(setTasks).catch(error => { if (!controller.signal.aborted) setError(message(error)); });
    training<typeof attempts>(`/attempts?taskId=${encodeURIComponent(taskId)}&limit=20&offset=${historyOffset}`, controller.signal).then(setAttempts).catch(error => { if (!controller.signal.aborted) setError(message(error)); });
    return () => controller.abort();
  }, [query, status, offset, historyOffset, locationKey]);
  return <main className="training-workspace"><aside className="training-list"><div className="training-heading"><h2>训练任务</h2><button onClick={() => trainingUrl({ trainingMode: "edit", trainingDraftId: crypto.randomUUID() })}>新建任务</button></div>
    <label>搜索任务<input aria-label="搜索任务" value={query} onChange={event => { setQuery(event.target.value); setOffset(0); }}/></label><label>任务状态<select aria-label="任务状态" value={status} onChange={event => { setStatus(event.target.value); setOffset(0); }}><option value="">全部</option><option value="DRAFT">草稿</option><option value="ACTIVE">已发布</option><option value="ARCHIVED">已归档</option></select></label>
    {error && <p role="alert">{error}</p>}{tasks?.items.map(item => <button className={`training-list-item ${item.id === taskId ? "selected" : ""}`} key={item.id} onClick={() => { setHistoryOffset(0); trainingUrl({ trainingTaskId: item.id }); }}><strong>{item.title}</strong><small>{statuses[item.status]} · 版本 {item.revision}</small></button>)}
    {tasks?.total === 0 && <p className="hint">可从源码方法创建训练任务。</p>}<div className="button-row"><button disabled={!offset} onClick={() => setOffset(previous => Math.max(0, previous - 20))}>上一页</button><button disabled={!tasks || offset + 20 >= tasks.total} onClick={() => setOffset(previous => previous + 20)}>下一页</button></div>
    <h3>作答历史</h3>{attempts?.items.map(item => <button className={`training-list-item ${item.id === attemptId ? "selected" : ""}`} key={item.id} onClick={() => trainingUrl({ trainingAttemptId: item.id, trainingTaskId: item.taskId })}><strong>{item.title}</strong><small>{statuses[item.status]} · 任务版本 {item.taskRevision}</small></button>)}
    <div className="button-row"><button disabled={!historyOffset} onClick={() => setHistoryOffset(previous => Math.max(0, previous - 20))}>较新记录</button><button disabled={!attempts || historyOffset + 20 >= attempts.total} onClick={() => setHistoryOffset(previous => previous + 20)}>较早记录</button></div>
  </aside><div className="training-content">{attemptId ? <AttemptView key={attemptId} id={attemptId} onSource={onSource}/> : editing ? <TaskEditor key={`${taskId}-${draftId}`} taskId={taskId} draftId={draftId} onSource={onSource}/> : taskId ? <TaskDetail key={taskId} id={taskId} onSource={onSource}/> : <section className="training-detail"><h2>源码理解训练</h2><p>选择训练任务开始作答，或从源码方法创建题目。</p></section>}</div></main>;
}
