import dagre from "@dagrejs/dagre";

export type LayoutNode = { id: string; width?: number; height?: number };
export type LayoutEdge = { id: string; source: string; target: string };
export type PositionedNode = { id: string; position: { x: number; y: number } };
export const nodeWidth = 240;
export const nodeHeight = 76;
export const umlWidth = 280;
export function umlHeight(memberCount: number) {
  return 84 + Math.min(memberCount, 10) * 18 + (memberCount > 10 ? 18 : 0);
}

export function layoutGraph(nodes: LayoutNode[], edges: LayoutEdge[], direction: "LR" | "TB"): PositionedNode[] {
  const graph = new dagre.graphlib.Graph({ multigraph: true });
  graph.setGraph({ rankdir: direction, nodesep: 45, ranksep: 240, marginx: 40, marginy: 100 });
  graph.setDefaultEdgeLabel(() => ({}));
  const ordered = [...nodes].sort((left, right) => left.id.localeCompare(right.id, "en"));
  for (const node of ordered) {
    const width = node.width ?? nodeWidth; const height = node.height ?? nodeHeight;
    const loops = edges.filter(edge => edge.source === node.id && edge.target === node.id).length;
    const reach = loops ? 120 + (loops - 1) * 42 : 0;
    graph.setNode(node.id, { width: width + (loops ? 2 * (reach + 120) : 0), height: height + (loops ? 2 * (reach + 20) : 0) });
  }
  for (const edge of [...edges].sort((left, right) => left.id.localeCompare(right.id, "en"))) {
    graph.setEdge(edge.source, edge.target, {}, edge.id);
  }
  dagre.layout(graph);
  return ordered.map(node => {
    const point = graph.node(node.id);
    if (!Number.isFinite(point.x) || !Number.isFinite(point.y)) throw new Error("布局未产生有效位置");
    const width = node.width ?? nodeWidth; const height = node.height ?? nodeHeight;
    return { id: node.id, position: { x: point.x - width / 2, y: point.y - height / 2 } };
  });
}
