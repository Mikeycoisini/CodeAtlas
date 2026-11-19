import { useEffect, useState } from "react";
import { allFiles, api, type Page } from "../api";
import type { SourceSelection } from "../learning/learningApi";
import type { ArchitecturePage } from "../architecture/architectureApi";
import { training, trainingUrl, type Attempt as TrainingAttempt, type TrainingTask } from "../training/trainingApi";
import { get, studyUrl, type Guide, type Progress, type ReviewState } from "../study/studyApi";
import LearningPackPanel from "./LearningPackPanel";
import { capabilityTargets, type AiStatus } from "./aiApi";
import "./ai-assistant.css";

const aiPurposes: Record<string, string> = { explanation: "METHOD_EXPLANATION", "project-note": "PROJECT_NOTE" };
const PROJECT_GOAL = "先通读所选源码，概括这个项目的整体架构、模块职责、关键技术选型与关键调用链；再整理 3 个项目八股文知识点，每条都要包含八股文知识、对应源码片段和代码解析；然后生成 4 道面试复习题（原理、机制、对比、边界条件、项目实例），覆盖判断、选择与简答，并给出可核对的参考答案。";
type Metrics = { tasks: number; published: number; attempts: number; submitted: number; guides: number; activeGuides: number;
  guideProgress: number; knowledge: number; mastered: number; dueCards: number };
const emptyPage = <T,>(): Page<T> => ({ items: [], total: 0, generationId: "", limit: 200, offset: 0 } as Page<T>);

export default function AiAssistantWorkspace({ snapshotId, currentSource, sourceLabel, onOpenAi, onNavigate, onOpenLibrary, theme }: {
  snapshotId: string; currentSource?: SourceSelection; sourceLabel: string;
  onOpenAi: (purpose?: string, selection?: SourceSelection, autoPrepare?: boolean) => void;
  onNavigate: (view: "training" | "guides") => void; onOpenLibrary: (kind: "knowledge" | "cards") => void; theme: "light" | "dark";
}) {
  const [status, setStatus] = useState<AiStatus | null>(null);
  const [checking, setChecking] = useState(false);
  const [showConfig, setShowConfig] = useState(false);
  const [metrics, setMetrics] = useState<Metrics | null>(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [pack, setPack] = useState<"training" | "guide" | null>(null);
  const [projectSources, setProjectSources] = useState<SourceSelection[] | null>(null);
  const [preparingProject, setPreparingProject] = useState(false);
  const loadStatus = async (signal?: AbortSignal) => {
    setChecking(true);
    try {
      const next = await api<AiStatus>("/ai/status", { signal });
      setStatus(next);
      setError("");
    } catch (reason) {
      if (!signal?.aborted) setError(String((reason as Error).message));
    } finally {
      if (!signal?.aborted) setChecking(false);
    }
  };
  useEffect(() => {
    const controller = new AbortController();
    void loadStatus(controller.signal);
    return () => controller.abort();
  }, []);
  useEffect(() => {
    const controller = new AbortController();
    const read = async <T,>(task: Promise<T>, fallback: T): Promise<T> => {
      try { return await task; } catch { return fallback; }
    };
    void Promise.all([
      read(training<Page<TrainingTask>>("/tasks?limit=100", controller.signal), emptyPage<TrainingTask>()),
      read(training<Page<TrainingAttempt>>("/attempts?limit=100", controller.signal), emptyPage<TrainingAttempt>()),
      read(get<Page<Guide>>("/guides?limit=100", controller.signal), emptyPage<Guide>()),
      read(get<Page<Progress>>("/progress/knowledge?limit=100", controller.signal), emptyPage<Progress>()),
      read(get<Page<ReviewState>>("/reviews/cards?dueOnly=true&limit=1", controller.signal), emptyPage<ReviewState>()),
    ]).then(([tasksPage, attemptsPage, guidesPage, knowledgePage, duePage]) => {
      if (controller.signal.aborted) return;
      const tasks = tasksPage.items ?? [];
      const attempts = attemptsPage.items ?? [];
      const guides = guidesPage.items ?? [];
      const knowledge = knowledgePage.items ?? [];
      setMetrics({
        tasks: tasksPage.total ?? tasks.length,
        published: tasks.filter(task => task.status === "ACTIVE").length,
        attempts: attemptsPage.total ?? attempts.length,
        submitted: attempts.filter(attempt => attempt.status === "SUBMITTED").length,
        guides: guidesPage.total ?? guides.length,
        activeGuides: guides.filter(guide => guide.status === "ACTIVE").length,
        guideProgress: guides.length ? Math.round(guides.reduce((sum, guide) => sum + (guide.steps.length ? guide.doneCount / guide.steps.length : 0), 0) / guides.length * 100) : 0,
        knowledge: knowledgePage.total ?? knowledge.length,
        mastered: knowledge.filter(item => item.assessment?.status === "SUPPORTED").length,
        dueCards: duePage.total ?? 0,
      });
    });
    return () => controller.abort();
  }, []);
  const selection = currentSource;
  const ready = !!status?.reachable && status.configured;
  const statsFor = (id: string) => {
    if (id === "explanation") return [{ value: selection ? "已固定" : "未选择", label: "源码身份" },
      { value: selection?.symbolId ? "符号" : selection?.fileId ? "文件" : "—", label: "来源类型" }];
    if (id === "training") return [{ value: metrics ? String(metrics.tasks) : "—", label: "训练任务" },
      { value: metrics ? String(metrics.published) : "—", label: "已发布" }];
    if (id === "practice-feedback") return [{ value: metrics ? String(metrics.attempts) : "—", label: "作答记录" },
      { value: metrics ? String(metrics.submitted) : "—", label: "已提交" }];
    if (id === "guide") return [{ value: metrics ? String(metrics.guides) : "—", label: "学习引导" },
      { value: metrics ? `${metrics.guideProgress}%` : "—", label: "平均完成" }];
    if (id === "project-note") return [{ value: metrics ? String(metrics.knowledge) : "—", label: "知识条目" },
      { value: metrics ? String(metrics.mastered) : "—", label: "已掌握" }];
    return [];
  };
  const openForCapability = (id: string) => {
    if (id === "training" || id === "practice-feedback") { onNavigate("training"); return; }
    if (id === "guide") { onNavigate("guides"); return; }
    const purpose = aiPurposes[id];
    if (!purpose) return;
    if (!selection) { setNotice("请先在“代码分析”标签中选择已导入源码，再发起 AI 生成。"); return; }
    if (!ready) { setNotice("AI 服务未连接或未配置模型，请先重试或检查配置。"); return; }
    onOpenAi(purpose, selection, true);
  };
  const startProjectPack = async () => {
    if (!snapshotId) { setNotice("请先在顶部选择项目和源码快照。"); return; }
    if (!ready) { setNotice("AI 服务未连接或未配置模型，请先重试或检查配置。"); return; }
    setPreparingProject(true); setNotice("");
    try {
      const picked: SourceSelection[] = [];
      const seenFiles = new Set<string>();
      let generation: string | undefined;
      try {
        const page = await api<ArchitecturePage>(`/snapshots/${snapshotId}/architecture?perspective=SYSTEM&limit=80`);
        generation = page.generationId ?? undefined;
        const sourceNodes = page.nodes;
        const wanted = new Set(sourceNodes
          .filter(node => node.kind === "MODULE" || node.kind === "COMPONENT")
          .flatMap(node => node.evidenceIds));
        const candidates = page.evidence.filter(item =>
          item.endLine >= item.startLine && item.rule !== "BUILD_MODULE" && !item.rule.startsWith("CONFIG_"));
        const preferred = candidates.filter(item => wanted.has(item.id));
        const ranked = [...(preferred.length ? preferred : candidates)].sort((left, right) =>
          Number(right.rule === "TYPE_DECLARATION") - Number(left.rule === "TYPE_DECLARATION")
          || (right.endLine - right.startLine) - (left.endLine - left.startLine));
        for (const item of ranked) {
          if (seenFiles.has(item.fileId)) continue;
          seenFiles.add(item.fileId);
          picked.push({ snapshotId: page.snapshotId, generationId: page.generationId ?? undefined, fileId: item.fileId,
            startLine: item.startLine, endLine: Math.max(item.startLine, Math.min(item.endLine, item.startLine + 40)) });
          if (picked.length >= 4) break;
        }
      } catch {
        // 架构投影不可用时继续用快照文件兜底
      }
      if (picked.length < 2) {
        const files = await allFiles(snapshotId, undefined);
        const java = files.filter(file => file.path.endsWith(".java"));
        for (const file of java) {
          if (picked.length >= 4) break;
          if (seenFiles.has(file.id) || file.byteSize > 4_000) continue;
          seenFiles.add(file.id);
          picked.push({ snapshotId, generationId: generation, fileId: file.id });
        }
        if (!picked.length) {
          for (const file of java) {
            if (picked.length >= 4) break;
            if (seenFiles.has(file.id)) continue;
            seenFiles.add(file.id);
            picked.push({ snapshotId, generationId: generation, fileId: file.id, startLine: 1, endLine: 40 });
          }
        }
      }
      if (!picked.length) { setNotice("没有从索引中拿到可用的 Java 源码片段，请确认项目包含 Java 源码并已完成索引。"); return; }
      setProjectSources(picked);
      setPack("training");
    } catch (reason) {
      setNotice(reason instanceof Error ? reason.message : String(reason));
    } finally { setPreparingProject(false); }
  };
  const requiresSource = (id: string) => id === "explanation" || id === "project-note";
  return <main className={`ai-assistant ai-${theme}`}>
    <header className="ai-header">
      <div><p className="ai-eyebrow">UNIFIED AI ASSISTANT</p><h1>AI 助手</h1><p className="ai-muted">解释、出题、实践反馈与引导共用固定源码身份和原有授权链路。</p></div>
      <div className={`ai-status ${ready ? "ok" : status?.reachable ? "warn" : "offline"}`}>
        <i/><div><strong>{status ? status.reachable ? status.configured ? "AI 服务已就绪" : "AI 服务已连接，未配置模型" : "AI 服务未连接" : "正在检查 AI 服务…"}</strong>
          <small>{status?.message ?? (error || "只读取本机公开配置，不展示内部令牌或模型密钥")}</small></div>
        {!ready && status && <div className="ai-status-actions">
          <button onClick={() => void loadStatus()} disabled={checking}>{checking ? "检查中" : "重试"}</button>
          <button onClick={() => setShowConfig(value => !value)} aria-expanded={showConfig}>{showConfig ? "收起配置" : "配置说明"}</button>
        </div>}
        {showConfig && <p className="ai-status-help">请确认本机 CodeAtlasAI 服务已启动，并在其配置中设置 provider、baseUrl 与 model；保存后点击“重试”。</p>}
      </div>
    </header>
    {status?.reachable && <section className="ai-provider" aria-label="AI 服务信息"><span>提供方 <strong>{status.provider}</strong></span><span>模型 <strong>{status.model}</strong></span><span>服务地址 <strong>{status.baseUrl}</strong></span>{status.maxTokens && <span>输出上限 <strong>{status.maxTokens}</strong></span>}</section>}
    <div className="ai-workspace-grid">
      <aside className="ai-source-rail">
        <section className="ai-source-picker" aria-label="当前源码来源">
          <div className="ai-source-heading"><div><p className="ai-step">01 · SOURCE</p><h2>当前源码来源</h2><p className="ai-muted">源码由“代码分析”标签统一选择，此处只读展示。</p></div>
            <span className={`ai-source-state ${selection ? "fixed" : "empty"}`}>{selection ? "已固定" : "未选择"}</span></div>
          {notice && <p role="status" className="ai-notice">{notice}</p>}
          {!snapshotId && <p className="ai-muted">请先在顶部选择项目和源码快照。</p>}
          {selection ? <>
            <div className="ai-source-preview"><span>当前来源</span><strong>{sourceLabel}</strong>
              <small>生成前仍会在授权预览中确认外发范围</small></div>
            <dl className="ai-source-facts">
              <div><dt>类型</dt><dd>{selection.symbolId ? "符号来源" : "整个文件"}</dd></div>
              {selection.startLine && <div><dt>行号</dt><dd>L{selection.startLine}{selection.endLine ? `–L${selection.endLine}` : ""}</dd></div>}
              <div><dt>快照</dt><dd title={selection.snapshotId}>{selection.snapshotId.slice(0, 12)}…</dd></div>
              <div><dt>代次</dt><dd title={selection.generationId ?? "最新索引"}>{selection.generationId ? `${selection.generationId.slice(0, 12)}…` : "最新索引"}</dd></div>
            </dl>
          </> : snapshotId && <p className="ai-muted">尚未选择来源，请在“代码分析”标签中选择符号或整个文件。</p>}
        </section>
        <section className="ai-pack-actions" aria-label="AI 学习草稿">
          <div><p className="ai-step">02 · DRAFT</p><h2>生成学习草稿</h2><p className="ai-muted">可以让 AI 先总结整个项目，再整理八股文知识点、代码解析与题目；保存后知识与卡片进入知识库，题目进入训练草稿。</p></div>
          <div className="button-row">
            <button className="primary-action" disabled={!snapshotId || !ready || preparingProject} onClick={() => void startProjectPack()}>
              {preparingProject ? "正在读取项目…" : "AI 总结项目并生成知识与题目"}
            </button>
            <button className="ghost-action" disabled={!selection} onClick={() => { setProjectSources(null); setPack("training"); }}>生成训练草稿</button>
            <button className="ghost-action" disabled={!selection} onClick={() => { setProjectSources(null); setPack("guide"); }}>生成引导草稿</button>
          </div>
        </section>
        <details className="ai-help">
          <summary>使用说明与前置条件</summary>
          <div>
            <p><strong>固定身份</strong>切换入口不会静默跨项目；解释、出题、反馈和引导继续使用当前快照／代次与已选源码。</p>
            <p><strong>离线与授权</strong>AI 离线时仍可管理手工知识、卡片和文档；每个生成请求仍需确认来源与授权范围。</p>
            <ul><li>解释：选择一个方法、类型或文件范围。</li><li>项目说明：选择文件级来源。</li><li>项目出题：无需选择单个符号，AI 会从项目证据中抽取代表源码，先总结再生成知识点、代码解析与题目。</li><li>实践反馈：完成一次代码练习提交。</li><li>引导：已有引导草稿和来源。</li></ul>
          </div>
        </details>
      </aside>
      <section className="ai-capability-zone">
        <div className="ai-quickstart">
          <div><p className="ai-step">QUICK START</p><h2>{selection ? "从当前源码开始" : "先选择源码来源"}</h2>
            <p className="ai-muted">{selection ? sourceLabel : "在“代码分析”标签中选择方法、类型或整个文件。"}</p></div>
          <button className="primary-action" disabled={!selection || !ready} title={!ready ? "AI 服务未连接或未配置模型" : undefined}
            onClick={() => selection && onOpenAi("METHOD_EXPLANATION", selection, true)}>解释当前源码</button>
        </div>
        <div className="ai-section-heading"><div><p className="ai-step">03 · CAPABILITIES</p><h2>能力矩阵</h2></div><span>{status?.capabilities.length ?? 0} 项能力</span></div>
        <section className="ai-cards">
          {status?.capabilities.map((capability, index) => <article className="ai-card" key={capability.id}>
            <div className="ai-card-top"><span className="ai-card-index">{String(index + 1).padStart(2, "0")}</span><span className="ai-card-target">{capabilityTargets[capability.id] ?? "现有能力"}</span></div>
            <h2>{capability.name}</h2><p>{capability.requires}</p>
            <div className="ai-card-stats">{statsFor(capability.id).map(stat => <span key={stat.label}><strong>{stat.value}</strong><small>{stat.label}</small></span>)}</div>
            <button disabled={!ready && !["training", "practice-feedback", "guide"].includes(capability.id)}
              onClick={() => openForCapability(capability.id)}>
              {requiresSource(capability.id) ? selection ? (capability.id === "project-note" ? "生成项目技术说明" : "打开当前源码 AI") : "先选择源码" : "前往" + capabilityTargets[capability.id]}
            </button>
          </article>)}
        </section>
        <p className="ai-footnote">AI 只生成草稿；发布、掌握与完成状态以真实作答、复习和验证记录为准。</p>
      </section>
    </div>
    {pack && projectSources && <LearningPackPanel kind={pack} selections={projectSources} goal={PROJECT_GOAL} questionCount={4}
      heading="AI 总结项目：知识、卡片与题目" onClose={() => { setPack(null); setProjectSources(null); }}
      onOpenLibrary={kind => { setPack(null); setProjectSources(null); onOpenLibrary(kind); }}
      onDone={(kind, id) => {
        setPack(null); setProjectSources(null);
        if (kind === "training") trainingUrl({ trainingTaskId: id });
        else studyUrl("guides", { guideId: id });
      }}/>}
    {pack && !projectSources && selection && <LearningPackPanel kind={pack} selection={selection} onClose={() => setPack(null)}
      onOpenLibrary={kind => { setPack(null); onOpenLibrary(kind); }}
      onDone={(kind, id) => {
        setPack(null);
        if (kind === "training") trainingUrl({ trainingTaskId: id });
        else studyUrl("guides", { guideId: id });
      }}/>}
  </main>;
}
