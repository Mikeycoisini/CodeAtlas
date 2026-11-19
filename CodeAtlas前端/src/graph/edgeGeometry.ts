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
    const mid=(sourceX+targetX)/2+fan;
    path=`M ${sourceX} ${sourceY} L ${mid} ${sourceY} L ${mid} ${targetY} L ${targetX} ${targetY}`;
    labelX=mid;
  } else {
    const mid=(sourceY+targetY)/2+fan;
    path=`M ${sourceX} ${sourceY} L ${sourceX} ${mid} L ${targetX} ${mid} L ${targetX} ${targetY}`;
    labelY=mid;
  }
  return { path, labelX, labelY };
}
