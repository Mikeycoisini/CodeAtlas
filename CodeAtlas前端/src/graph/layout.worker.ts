import { layoutGraph, type LayoutNode, type LayoutEdge } from "./layout";

self.onmessage = (event: MessageEvent<{ identity: string; nodes: LayoutNode[]; edges: LayoutEdge[]; direction: "LR" | "TB" }>) => {
  const { identity, nodes, edges, direction } = event.data;
  try { self.postMessage({ identity, positions: layoutGraph(nodes, edges, direction) }); }
  catch (error) { self.postMessage({ identity, error: error instanceof Error ? error.message : "布局失败" }); }
};
