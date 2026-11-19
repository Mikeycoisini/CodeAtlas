import { useEffect, useRef, useState, useCallback } from "react";
import { ReactFlow, Background, Controls, Panel, MarkerType, useNodesState, type Node, type Edge, type ReactFlowInstance } from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import LayoutWorker from "./layout.worker?worker";
import { nodeHeight, nodeWidth, umlHeight, umlWidth, type PositionedNode } from "./layout";
import { edgeKindLabels, evidenceStates, type GraphNode, type GraphPage } from "./graphApi";
import GraphEdgeView from "./GraphEdgeView";

type SavedView = { positions: PositionedNode[]; viewport?: { x: number; y: number; zoom: number } };
const colors: Record<string, string> = { CONFIRMED: "#39836a", INFERRED: "#b07a24", UNRESOLVED: "#788397" };
const edgeTypes = { evidence: GraphEdgeView };
const marks: Record<string, string> = { PUBLIC: "+", PROTECTED: "#", PRIVATE: "-", PACKAGE: "~" };
function umlLabel(node: GraphNode) {
  const members = node.members ?? [];
  const shown = members.slice(0, 10);
  return <div className="uml-node">
    <div className="uml-head"><small>{node.kind === "INTERFACE" ? "«interface»" : "«class»"}</small><strong>{node.label}</strong><small>{node.qualifiedName}</small></div>
    {shown.length > 0 && <ul>{shown.map(member => <li key={member.id}>
      <span className={`uml-visibility ${member.visibility.toLowerCase() || "package"}`}>{marks[member.visibility] ?? "~"}</span>
      <span>{member.kind === "FIELD" ? member.signature : `${member.signature}${member.typeText ? `: ${member.typeText}` : ""}`}</span>
    </li>)}</ul>}
    {members.length > 10 && <small className="uml-more">+{members.length - 10} 个成员</small>}
  </div>;
}
export default function GraphCanvas({ page, identity, theme, direction, center, onNode, onEdge }: {
  page: GraphPage; identity: string; theme: "light" | "dark"; direction: "LR" | "TB"; center: string;
  onNode: (node: GraphNode) => void; onEdge: (id: string) => void;
}) {
  const [nodes, setNodes, onNodesChange] = useNodesState<Node>([]);
  const [error, setError] = useState(""); const [loading, setLoading] = useState(true); const [retry, setRetry] = useState(0);
  const instance = useRef<ReactFlowInstance | null>(null);
  const [readyInstance, setReadyInstance] = useState<ReactFlowInstance | null>(null);
  const saved = useRef<SavedView | null>(null); const current = useRef(identity); current.current = identity;
  const shape=JSON.stringify([page.nodes.map(n=>[n.id,n.label]),page.edges.map(e=>[e.id,e.source,e.target])]);
  let digest=2166136261;for(let i=0;i<shape.length;i++)digest=Math.imul(digest^shape.charCodeAt(i),16777619);
  const key = `codeatlas-graph-v2-${identity}-${direction}-${digest>>>0}`;
  useEffect(() => {
    let active = true; setLoading(true); setError(""); setNodes([]); saved.current = null;
    try {
      const candidate = JSON.parse(sessionStorage.getItem(key) ?? "null");
      if (Array.isArray(candidate?.positions) && candidate.positions.every((node: PositionedNode) => typeof node.id === "string" && Number.isFinite(node.position?.x) && Number.isFinite(node.position?.y))) {
        saved.current = candidate;
        if (candidate.viewport && ![candidate.viewport.x, candidate.viewport.y, candidate.viewport.zoom].every(Number.isFinite)) delete candidate.viewport;
      }
    } catch { /* Recompute an unreadable browser layout. */ }
    const apply = (positions: PositionedNode[]) => {
      if (!active) return;
      const byId = new Map(positions.map(item => [item.id, item.position]));
      const uml = page.view === "CLASS";
      setNodes(page.nodes.map(node => {
        const width = uml ? umlWidth : nodeWidth;
        const height = uml ? umlHeight(node.members?.length ?? 0) : nodeHeight;
        const className = `${node.kind === "UNRESOLVED" ? "graph-unknown" : ""} ${node.id === center ? "graph-center" : ""} ${uml ? "graph-uml" : ""}`;
        const common = { id: node.id, position: byId.get(node.id) ?? { x: 0, y: 0 }, width, height, className,
          sourcePosition: direction === "LR" ? "right" : "bottom", targetPosition: direction === "LR" ? "left" : "top", ariaLabel: node.label } as const;
        if (uml) return { ...common, style: { width, height }, data: { label: umlLabel(node) } } as Node;
        return { ...common, data: { label: <><strong>{node.label}</strong><small>{node.kind === "UNRESOLVED" ? "目标未解析" : node.qualifiedName || "默认包"}</small></> } } as Node;
      }));
      setLoading(false);
    };
    if (saved.current?.positions?.length === page.nodes.length && page.nodes.every(node => saved.current!.positions.some(item => item.id === node.id))) {
      apply(saved.current.positions); return () => { active = false; };
    }
    const worker = new LayoutWorker();
    worker.onmessage = event => {
      if (!active || event.data.identity !== identity || current.current !== identity) return;
      if (event.data.error) { setError(event.data.error); setLoading(false); }
      else apply(event.data.positions);
    };
    worker.onerror = () => { if (active) { setError("布局计算失败，可重试或使用节点与关系列表"); setLoading(false); } };
    worker.postMessage({ identity, nodes: page.nodes.map(node => page.view === "CLASS"
      ? { id: node.id, width: umlWidth, height: umlHeight(node.members?.length ?? 0) }
      : { id: node.id, width: nodeWidth, height: nodeHeight }), edges: page.edges, direction });
    return () => { active = false; worker.terminate(); };
  }, [identity, direction, retry]);
  useEffect(() => {
    if (loading || !readyInstance || !nodes.length) return;
    const frame = requestAnimationFrame(() => {
      if (saved.current?.viewport) void readyInstance.setViewport(saved.current.viewport);
      else {
        const loops = page.edges.filter(edge => edge.source === edge.target);
        if (!loops.length) void readyInstance.fitView({ padding: 0.2 });
        else {
          const loopCount = Math.max(...loops.map(edge => loops.filter(item => item.source === edge.source).length));
          const reach = 120 + (loopCount - 1) * 42;
          const xs = nodes.map(node => node.position.x); const ys = nodes.map(node => node.position.y);
          void readyInstance.fitBounds({ x: Math.min(...xs) - reach, y: Math.min(...ys) - reach,
            width: Math.max(...xs) - Math.min(...xs) + nodeWidth + 2 * reach,
            height: Math.max(...ys) - Math.min(...ys) + nodeHeight + 2 * reach }, { padding: 0.15 });
        }
      }
    });
    return () => cancelAnimationFrame(frame);
  }, [loading, readyInstance]);
  const persist = useCallback(() => {
    if (current.current !== identity || !instance.current || loading) return;
    try { sessionStorage.setItem(key, JSON.stringify({ positions: instance.current.getNodes().map(node => ({ id: node.id, position: node.position })), viewport: instance.current.getViewport() })); }
    catch { /* Browser storage exhaustion does not block source exploration. */ }
  }, [identity, key, loading]);
  const edges: Edge[] = page.edges.map(edge => {
    const parallel = page.edges.filter(item => item.source === edge.source && item.target === edge.target);
    const fan = (parallel.findIndex(item => item.id === edge.id) - (parallel.length - 1) / 2) * 42;
    const label = edge.kind === "CONTAINS" ? edgeKindLabels[edge.kind]
      : `${edgeKindLabels[edge.kind]} · ${evidenceStates[edge.status]} · ${edge.evidenceCount}`;
    return ({ ...edge, label,
    type: "evidence", data: { fan, loopRank: parallel.findIndex(item => item.id === edge.id), horizontal: direction === "LR" }, markerEnd: { type: MarkerType.ArrowClosed, color: colors[edge.status] },
    style: { stroke: colors[edge.status], strokeDasharray: edge.status === "CONFIRMED" ? undefined : "6 4" },
    ariaLabel: edge.kind === "CONTAINS" ? edgeKindLabels[edge.kind] : `${edgeKindLabels[edge.kind]} ${evidenceStates[edge.status]} ${edge.evidenceCount}条证据` }); });
  return <section className="graph-canvas" aria-label="代码关系画布">
    {loading && <p className="graph-layout-status" role="status">正在计算布局…</p>}
    {error ? <div className="graph-layout-status"><p role="alert">{error}</p><button onClick={() => { sessionStorage.removeItem(key); setRetry(value => value + 1); }}>重新布局</button></div> :
      <ReactFlow nodes={nodes} edges={loading ? [] : edges} edgeTypes={edgeTypes} onNodesChange={onNodesChange} colorMode={theme}
        onInit={flow => { instance.current = flow; setReadyInstance(flow); }} onNodeDragStop={persist} onMoveEnd={persist}
        onNodeClick={(_, node) => { persist(); const item = page.nodes.find(item => item.id === node.id); if (item) onNode(item); }}
        onEdgeClick={(_, edge) => onEdge(edge.id)} nodesConnectable={false} edgesReconnectable={false} deleteKeyCode={null}
        minZoom={0.1} maxZoom={2} onError={(_, message) => setError(message)}>
        <Background/><Controls showInteractive={false}/><Panel position="top-right">
          <button onClick={() => { sessionStorage.removeItem(key); setRetry(value => value + 1); }}>重新布局</button>
          {center && <button onClick={() => { const node = instance.current?.getNode(center); if (node) void instance.current?.setCenter(node.position.x + nodeWidth / 2, node.position.y + nodeHeight / 2, { zoom: 1 }); }}>定位调用中心</button>}
        </Panel>
      </ReactFlow>}
  </section>;
}
