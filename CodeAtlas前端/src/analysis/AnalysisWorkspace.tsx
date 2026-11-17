import { useEffect, useMemo, useState } from "react";
import { api, type Symbol } from "../api";
import type { EvidenceSource } from "../learning/learningApi";
import {
  flowKindLabels,
  visibilityMarks,
  type AnalysisSource,
  type FlowEdge,
  type FlowNode,
  type FlowPage,
  type SequencePage,
  type UmlPage,
} from "./analysisApi";
import "./analysis.css";

type Tab = "flow" | "sequence" | "uml";

function sourceEvidence(projectId: string, snapshotId: string, generationId: string, source: AnalysisSource): EvidenceSource {
  return {
    id: source.fileId, projectId, snapshotId, generationId: source.generationId || generationId,
    fileId: source.fileId, symbolId: "", filePath: source.filePath, sha256: source.fileHash,
    startLine: source.startLine, endLine: source.endLine, symbolName: "", content: "", snapshotOutdated: false,
  };
}

function flowLayout(page: FlowPage) {
  const nodes = new Map(page.nodes.map(node => [node.id, node]));
  const levels = new Map<string, number>();
  const start = page.nodes.find(node => node.kind === "START") ?? page.nodes[0];
  if (start) levels.set(start.id, 0);
  const queue = start ? [start.id] : [];
  const outgoing = new Map<string, FlowEdge[]>();
  page.edges.forEach(edge => { const list = outgoing.get(edge.source) ?? []; list.push(edge); outgoing.set(edge.source, list); });
  while (queue.length) {
    const id = queue.shift()!;
    const level = levels.get(id) ?? 0;
    for (const edge of outgoing.get(id) ?? []) {
      if (!levels.has(edge.target) && nodes.has(edge.target)) { levels.set(edge.target, level + 1); queue.push(edge.target); }
    }
  }
  let maxLevel = 0;
  for (const level of levels.values()) maxLevel = Math.max(maxLevel, level);
  page.nodes.forEach(node => { if (!levels.has(node.id)) levels.set(node.id, maxLevel + 1); });
  const byLevel = new Map<number, FlowNode[]>();
  page.nodes.forEach(node => { const level = levels.get(node.id) ?? 0; const list = byLevel.get(level) ?? []; list.push(node); byLevel.set(level, list); });
  const positions = new Map<string, { x: number; y: number; w: number; h: number }>();
  for (const [level, list] of [...byLevel.entries()].sort((a, b) => a[0] - b[0])) {
    list.forEach((node, index) => positions.set(node.id, { x: 40 + level * 260, y: 30 + index * 98, w: 220, h: 72 }));
  }
  const rows = Math.max(...[...byLevel.values()].map(list => list.length), 1);
  return { positions, width: 40 + (maxLevel + 2) * 260, height: Math.max(360, 30 + rows * 98 + 40) };
}

function FlowCanvas({ projectId, page, onSource }: { projectId: string; page: FlowPage; onSource: (source: EvidenceSource) => void }) {
  const layout = useMemo(() => flowLayout(page), [page]);
  return <div className="analysis-canvas" style={{ width: layout.width, height: layout.height }}>
    <svg className="analysis-edges" width={layout.width} height={layout.height} aria-hidden="true">
      {page.edges.map(edge => {
        const source = layout.positions.get(edge.source); const target = layout.positions.get(edge.target);
        if (!source || !target) return null;
        const sx = source.x + source.w, sy = source.y + source.h / 2, tx = target.x, ty = target.y + target.h / 2;
        return <path key={edge.id} className={`analysis-edge ${edge.status.toLowerCase()}`} d={`M ${sx} ${sy} C ${sx + 70} ${sy} ${tx - 70} ${ty} ${tx} ${ty}`}>
          <title>{edge.kind} {edge.label}</title>
        </path>;
      })}
    </svg>
    {page.edges.map(edge => {
      const source = layout.positions.get(edge.source); const target = layout.positions.get(edge.target);
      if (!source || !target || !edge.label) return null;
      return <span key={`${edge.id}-label`} className="analysis-edge-label" style={{ left: (source.x + source.w + target.x) / 2 - 12, top: (source.y + target.y) / 2 + 20 }}>{edge.label}</span>;
    })}
    {page.nodes.map(node => {
      const position = layout.positions.get(node.id)!;
      return <button key={node.id} className={`analysis-node kind-${node.kind.toLowerCase()}`} style={{ left: position.x, top: position.y, width: position.w, height: position.h }}
        onClick={() => onSource(sourceEvidence(projectId, page.snapshotId, page.generationId, node.source))}>
        <strong>{flowKindLabels[node.kind] ?? node.kind}</strong><span>{node.label}</span><small>L{node.source.startLine}</small>
      </button>;
    })}
  </div>;
}

function SequenceView({ projectId, page, onSource }: { projectId: string; page: SequencePage; onSource: (source: EvidenceSource) => void }) {
  const names = new Map(page.participants.map(participant => [participant.id, participant.name]));
  const source = (message: SequencePage["messages"][number]): EvidenceSource => ({
    id: message.evidence.fileId, projectId, snapshotId: page.snapshotId, generationId: page.generationId,
    fileId: message.evidence.fileId, symbolId: message.evidence.sourceId, filePath: message.evidence.filePath, sha256: message.evidence.fileHash,
    startLine: message.evidence.startLine, endLine: message.evidence.endLine, symbolName: "", content: "", snapshotOutdated: false,
  });
  return <div className="sequence-view">
    <div className="sequence-participants">{page.participants.map(participant => <span key={participant.id} className={`sequence-participant ${participant.kind.toLowerCase()}`}>{participant.name}</span>)}</div>
    <div className="sequence-messages">{page.messages.map(message => <button key={message.id} className={`sequence-message ${message.status.toLowerCase()} ${message.cycle ? "cycle" : ""}`}
      onClick={() => onSource(source(message))}>
      <span className="sequence-order">{message.order}</span>
      <span className="sequence-lifelines"><strong>{names.get(message.from) ?? message.from}</strong><i>→</i><strong>{names.get(message.to) ?? message.to}</strong></span>
      <span>{message.label}</span>
      <small>{message.kind} · {message.status}{message.cycle ? " · 递归/循环" : ""} · L{message.evidence.startLine}</small>
    </button>)}</div>
    {page.messages.length === 0 && <p className="analysis-muted">该入口没有可解析的静态调用消息。</p>}
  </div>;
}

function UmlView({ projectId, page, onSource }: { projectId: string; page: UmlPage; onSource: (source: EvidenceSource) => void }) {
  return <div className="uml-view">
    <article className="uml-class">
      <header><strong>{page.type.name}</strong><small>{page.type.qualifiedName} · {page.type.kind}</small></header>
      <section><h3>字段</h3>{page.fields.length ? page.fields.map(field => <button key={field.id} className="uml-member" onClick={() => onSource(sourceEvidence(projectId, page.snapshotId, page.generationId, field.source))}>
        <span>{visibilityMarks[field.visibility]} {field.name}</span><small>: {field.typeText}</small>
        {field.modifiers.length > 0 && <em>{field.modifiers.join(" ")}</em>}
      </button>) : <p className="analysis-muted">无字段</p>}</section>
      <section><h3>方法 / 构造器</h3>{page.methods.length ? page.methods.map(method => <button key={method.id} className="uml-member" onClick={() => onSource(sourceEvidence(projectId, page.snapshotId, page.generationId, method.source))}>
        <span>{visibilityMarks[method.visibility]} {method.signature}</span><small>{method.returnType || "constructor"}</small>
        {method.modifiers.length > 0 && <em>{method.modifiers.join(" ")}</em>}
      </button>) : <p className="analysis-muted">无方法</p>}</section>
    </article>
    <aside className="uml-side">
      <h3>关联候选</h3>
      {page.associations.map(association => <article key={association.id} className={`uml-association ${association.status.toLowerCase()}`}>
        <strong>{association.field} → {association.targetName}</strong>
        <small>{association.multiplicity} · {association.kind} · {association.status}</small>
        <p>{association.reason}</p>
        <button onClick={() => onSource(sourceEvidence(projectId, page.snapshotId, page.generationId, association.source))}>定位字段</button>
      </article>)}
      {page.associations.length === 0 && <p className="analysis-muted">无字段类型关联候选。</p>}
      <h3>继承 / 实现</h3>
      {page.relations.length ? page.relations.map((relation, index) => <p key={`${relation.kind}-${index}`} className="uml-relation"><strong>{relation.kind}</strong> {relation.targetText} · {relation.status}</p>) : <p className="analysis-muted">无已解析继承或实现关系。</p>}
      <details><summary>说明</summary><p>关联来自静态字段类型；集合字段标记为聚合候选，生命周期不足以证明组合关系。旧索引缺少字段时，本视图会直接读取固定快照源码。</p></details>
    </aside>
  </div>;
}

export default function AnalysisWorkspace({ projectId, snapshotId, generationId, selectedSymbol, onSource, theme }: {
  projectId: string; snapshotId: string; generationId: string; selectedSymbol: Symbol | null;
  onSource: (source: EvidenceSource) => void; theme: "light" | "dark";
}) {
  const [tab, setTab] = useState<Tab>(selectedSymbol?.kind === "CLASS" || selectedSymbol?.kind === "INTERFACE" ? "uml" : "flow");
  const [flow, setFlow] = useState<FlowPage | null>(null);
  const [sequence, setSequence] = useState<SequencePage | null>(null);
  const [uml, setUml] = useState<UmlPage | null>(null);
  const [error, setError] = useState("");
  useEffect(() => { setFlow(null); setSequence(null); setUml(null); setError(""); }, [selectedSymbol?.id, generationId, tab]);
  useEffect(() => {
    if (!selectedSymbol) return;
    setTab(selectedSymbol.kind === "CLASS" || selectedSymbol.kind === "INTERFACE" ? "uml" : "flow");
  }, [selectedSymbol?.id]);
  useEffect(() => {
    if (!snapshotId || !selectedSymbol) return;
    const controller = new AbortController();
    const callable = selectedSymbol.kind === "METHOD" || selectedSymbol.kind === "CONSTRUCTOR";
    if (tab === "flow" && callable) {
      api<FlowPage>(`/snapshots/${snapshotId}/symbols/${selectedSymbol.id}/flow?generationId=${generationId}&limit=180`, { signal: controller.signal })
        .then(setFlow).catch(reason => { if (!controller.signal.aborted) setError(String(reason.message)); });
    } else if (tab === "sequence" && callable) {
      api<SequencePage>(`/snapshots/${snapshotId}/symbols/${selectedSymbol.id}/sequence?generationId=${generationId}&depth=3&limit=120`, { signal: controller.signal })
        .then(setSequence).catch(reason => { if (!controller.signal.aborted) setError(String(reason.message)); });
    } else if (tab === "uml" && (selectedSymbol.kind === "CLASS" || selectedSymbol.kind === "INTERFACE")) {
      api<UmlPage>(`/snapshots/${snapshotId}/symbols/${selectedSymbol.id}/uml?generationId=${generationId}`, { signal: controller.signal })
        .then(setUml).catch(reason => { if (!controller.signal.aborted) setError(String(reason.message)); });
    }
    return () => controller.abort();
  }, [snapshotId, generationId, selectedSymbol?.id, selectedSymbol?.kind, tab]);
  if (!snapshotId) return <main className={`analysis-workspace analysis-${theme}`}><h1>代码分析</h1><p>选择项目和源码快照后查看。</p></main>;
  if (!selectedSymbol) return <main className={`analysis-workspace analysis-${theme}`}><div className="analysis-header"><div><p className="analysis-eyebrow">CODE ANALYSIS</p><h1>代码分析</h1><p className="analysis-muted">在项目探索中选择一个方法查看流程图／时序图，或选择一个类型查看完整 UML。</p></div></div></main>;
  const callable = selectedSymbol.kind === "METHOD" || selectedSymbol.kind === "CONSTRUCTOR";
  const type = selectedSymbol.kind === "CLASS" || selectedSymbol.kind === "INTERFACE";
  return <main className={`analysis-workspace analysis-${theme}`}>
    <div className="analysis-header">
      <div><p className="analysis-eyebrow">CODE ANALYSIS</p><h1>{selectedSymbol.name}</h1><p className="analysis-muted">{selectedSymbol.signature} · {selectedSymbol.qualifiedName}</p></div>
      <div className="analysis-tabs">
        <button disabled={!callable} className={tab === "flow" ? "active" : ""} onClick={() => setTab("flow")}>流程图</button>
        <button disabled={!callable} className={tab === "sequence" ? "active" : ""} onClick={() => setTab("sequence")}>时序图</button>
        <button disabled={!type} className={tab === "uml" ? "active" : ""} onClick={() => setTab("uml")}>完整 UML</button>
      </div>
    </div>
    {error && <p className="analysis-error" role="alert">{error}</p>}
    {tab === "flow" && !callable && <p className="analysis-warning">流程图需要选择一个方法或构造器。</p>}
    {tab === "uml" && !type && <p className="analysis-warning">完整 UML 需要选择一个类型；请在上方选择类型后切换。</p>}
    {tab === "flow" && callable && (flow ? <>
      <div className="analysis-summary"><strong>{flow.nodes.length} 节点 · {flow.edges.length} 条控制流</strong><small>静态模式 · {flow.filePath}</small>{flow.truncated && <span className="analysis-warning">已截断</span>}</div>
      <FlowCanvas projectId={projectId} page={flow} onSource={onSource}/>
      <ul className="analysis-warnings">{flow.warnings.map(warning => <li key={warning}>{warning}</li>)}</ul>
    </> : !error && <p className="analysis-muted">正在读取控制流…</p>)}
    {tab === "sequence" && callable && (sequence ? <>
      <div className="analysis-summary"><strong>{sequence.messages.length} 条消息 · {sequence.participants.length} 个参与者</strong><small>静态顺序，不代表运行时顺序</small>{sequence.truncated && <span className="analysis-warning">已截断</span>}</div>
      <SequenceView projectId={projectId} page={sequence} onSource={onSource}/>
      <ul className="analysis-warnings">{sequence.warnings.map(warning => <li key={warning}>{warning}</li>)}</ul>
    </> : !error && <p className="analysis-muted">正在读取时序…</p>)}
    {tab === "uml" && type && (uml ? <>
      <div className="analysis-summary"><strong>{uml.totalFields} 字段 · {uml.totalMethods} 方法</strong><small>{uml.type.qualifiedName}</small>{uml.truncated && <span className="analysis-warning">已截断</span>}</div>
      <UmlView projectId={projectId} page={uml} onSource={onSource}/>
      <ul className="analysis-warnings">{uml.warnings.map(warning => <li key={warning}>{warning}</li>)}</ul>
    </> : !error && <p className="analysis-muted">正在读取 UML…</p>)}
  </main>;
}
