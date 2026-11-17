type GeometryInput = { sourceX: number; sourceY: number; targetX: number; targetY: number; self: boolean; fan: number; horizontal: boolean; loopRank: number };

export function edgeGeometry({ sourceX, sourceY, targetX, targetY, self, fan, horizontal, loopRank }: GeometryInput) {
  let path: string;
  let labelX = (sourceX + targetX) / 2;
  let labelY = (sourceY + targetY) / 2;
  if (self) {
    const reach = 120 + loopRank * 42;
    if (horizontal) {
      path = `M ${sourceX} ${sourceY} C ${sourceX + reach} ${sourceY - reach} ${targetX - reach} ${targetY - reach} ${targetX} ${targetY}`;
      labelY -= reach * 0.75;
    } else {
      const outsideX = Math.max(sourceX, targetX) + 120 + reach;
      path = `M ${sourceX} ${sourceY} C ${sourceX} ${sourceY + reach} ${outsideX} ${sourceY + reach} ${outsideX} ${labelY} C ${outsideX} ${targetY - reach} ${targetX} ${targetY - reach} ${targetX} ${targetY}`;
      labelX = outsideX;
      labelY += loopRank * 32;
    }
  } else if (horizontal) {
    const reach = Math.max(65, Math.abs(targetX - sourceX) / 2);
    path = `M ${sourceX} ${sourceY} C ${sourceX + reach} ${sourceY + fan} ${targetX - reach} ${targetY + fan} ${targetX} ${targetY}`;
    labelY += fan * 0.75;
  } else {
    const reach = Math.max(65, Math.abs(targetY - sourceY) / 2);
    path = `M ${sourceX} ${sourceY} C ${sourceX + fan} ${sourceY + reach} ${targetX + fan} ${targetY - reach} ${targetX} ${targetY}`;
    labelX += fan * 0.75;
  }
  return { path, labelX, labelY };
}
