import { BaseEdge, type EdgeProps } from "@xyflow/react";
import { edgeGeometry } from "./edgeGeometry";

export default function GraphEdgeView({ id, sourceX, sourceY, targetX, targetY, source, target, data, label, style, markerEnd }: EdgeProps) {
  const { path, labelX, labelY } = edgeGeometry({ sourceX, sourceY, targetX, targetY, self: source === target,
    fan: Number(data?.fan ?? 0), horizontal: data?.horizontal !== false, loopRank: Number(data?.loopRank ?? 0) });
  return <BaseEdge id={id} path={path} label={label} labelX={labelX} labelY={labelY} style={style} markerEnd={markerEnd}/>;
}
