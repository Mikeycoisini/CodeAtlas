import { useEffect, useRef, useState } from "react";
import { api } from "../api";
import { mutate } from "../learning/learningApi";
import EvidencePicker from "../progress/EvidencePicker";
import PracticeAiPanel from "./PracticeAiPanel";
import { message, type TrainingTask } from "./trainingApi";

type Workspace = { id: string; path: string; snapshotId: string; taskRevision: number; status: string; submissions: { id: string; sequenceNo: number; contentDigest: string }[] };
type Result = { id: string; source: string; status: string; method: string; environment: string; report: string; contentDigest: string };
type Submission = { id: string; sequenceNo: number; contentDigest: string; archivePath: string;
  changes: { path: string; beforeSha256: string | null; afterSha256: string | null; diff: string }[]; testResults: Result[] };

export default function PracticePanel({ task }: { task: TrainingTask }) {
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [workspaceId, setWorkspaceId] = useState("");
  const [submission, setSubmission] = useState<Submission | null>(null);
  const [status, setStatus] = useState("PASSED");
  const [method, setMethod] = useState("");
  const [environment, setEnvironment] = useState("");
  const [report, setReport] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [association, setAssociation] = useState<{ sourceType: string; sourceId: string } | null>(null);
  const [aiOpen, setAiOpen] = useState(false);
  const createKey = useRef(crypto.randomUUID());
  const submitKey = useRef(crypto.randomUUID());
  const reportKey = useRef(crypto.randomUUID());
  const selected = workspaces.find(item => item.id === workspaceId);
  const refresh = async () => {
    const items = await api<Workspace[]>(`/practice/workspaces?taskId=${encodeURIComponent(task.id)}`);
    setWorkspaces(items); setWorkspaceId(previous => previous || items[0]?.id || "");
  };
  useEffect(() => { refresh().catch(error => setError(message(error))); }, [task.id]);
  const run = async (action: () => Promise<void>) => {
    setBusy(true); setError("");
    try { await action(); } catch (error) { setError(message(error)); }
    finally { setBusy(false); }
  };
  return <section className="training-practice"><h3>代码练习</h3>
    <p>从任务关联的快照创建独立目录，在本地 IDE 修改后保存提交。测试由你在外部环境运行，结果按用户提供的报告记录。</p>
    <p className="hint">可把代码提交或测试报告关联到知识点。提交本身只作为成果，不单独计入掌握；PASSED/FAILED 报告分别作为正/负证据，ERROR/TIMEOUT 不计为能力失败。</p>
    {error && <p className="learning-error" role="alert">{error}</p>}
    <div className="button-row"><button disabled={busy || task.status === "ARCHIVED" || !task.sources[0]?.snapshotId} onClick={() => run(async () => {
      const item = await mutate<Workspace>("/practice/workspaces", { taskId: task.id, snapshotId: task.sources[0].snapshotId, idempotencyKey: createKey.current });
      createKey.current = crypto.randomUUID(); await refresh(); setWorkspaceId(item.id); setSubmission(null);
    })}>创建独立练习副本</button></div>
    {workspaces.length > 0 && <label>练习副本<select value={workspaceId} onChange={event => { setWorkspaceId(event.target.value); setSubmission(null); }}>
      {workspaces.map(item => <option key={item.id} value={item.id}>{item.id.slice(0, 8)} · 任务版本 {item.taskRevision}</option>)}
    </select></label>}
    {selected && <><p>副本目录：<code>{selected.path}</code></p><p>来源快照：<code>{selected.snapshotId}</code></p>
      <div className="button-row"><button disabled={busy || selected.status !== "ACTIVE"} onClick={() => run(async () => {
        const item = await mutate<Submission>(`/practice/workspaces/${selected.id}/submissions`, { idempotencyKey: submitKey.current });
        submitKey.current = crypto.randomUUID(); setSubmission(item); await refresh();
      })}>保存当前代码提交</button><button disabled={busy || selected.status !== "ACTIVE"} onClick={() => run(async () => {
        if (!window.confirm("关闭并清理练习副本目录？已保存的提交和测试报告会保留。")) return;
        await mutate(`/practice/workspaces/${selected.id}/close`, { idempotencyKey: crypto.randomUUID() }); await refresh();
      })}>关闭并清理副本</button></div>
      {selected.submissions.map(item => <button className="training-list-item" key={item.id} onClick={() => run(async () => setSubmission(await api<Submission>(`/practice/submissions/${item.id}`)))}>
        提交 {item.sequenceNo} · {item.contentDigest.slice(0, 12)}
      </button>)}
    </>}
    {submission && <div className="training-question"><h3>提交 {submission.sequenceNo}</h3><p>内容标识：<code>{submission.contentDigest}</code></p>
      <p>不可变副本：<code>{submission.archivePath}</code></p>
      <div className="button-row"><button onClick={() => setAssociation({ sourceType: "PRACTICE_SUBMISSION", sourceId: submission.id })}>关联代码提交到知识</button><button onClick={() => setAiOpen(true)}>请求 AI 反馈</button></div>
      {submission.changes.length ? submission.changes.map(change => <details key={change.path}><summary>{change.path}</summary><pre>{change.diff}</pre></details>) : <p>与原快照内容一致。</p>}
      <h3>外部测试报告</h3><p>以下结果仅表示用户提供的信息，CodeAtlas 未执行代码。</p>
      <label>状态<select value={status} onChange={event => setStatus(event.target.value)}><option>PASSED</option><option>FAILED</option><option>ERROR</option><option>TIMEOUT</option></select></label>
      <label>验证方式<input value={method} maxLength={200} onChange={event => setMethod(event.target.value)}/></label>
      <label>运行环境<input value={environment} maxLength={1000} onChange={event => setEnvironment(event.target.value)}/></label>
      <label>日志或报告<textarea value={report} maxLength={16000} onChange={event => setReport(event.target.value)}/></label>
      <button disabled={busy || !method.trim() || !environment.trim() || !report.trim()} onClick={() => run(async () => {
        await mutate(`/practice/submissions/${submission.id}/test-results`, { status, method, environment, report, idempotencyKey: reportKey.current });
        reportKey.current = crypto.randomUUID(); setSubmission(await api<Submission>(`/practice/submissions/${submission.id}`)); setReport("");
      })}>记录用户测试结果</button>
      {submission.testResults.map(item => <div className="training-result" key={item.id}><strong>{item.status}</strong> · {item.source}<p>{item.method} · {item.environment}</p><pre>{item.report}</pre>
        <div className="button-row"><button onClick={() => setAssociation({ sourceType: "PRACTICE_TEST_RESULT", sourceId: item.id })}>关联该报告到知识</button></div>
      </div>)}
    </div>}
    {association && <EvidencePicker sourceType={association.sourceType} sourceId={association.sourceId}
      onClose={() => setAssociation(null)} onAssociated={() => setAssociation(null)} />}
    {aiOpen && submission && <PracticeAiPanel submissionId={submission.id} onClose={() => setAiOpen(false)} />}
  </section>;
}
