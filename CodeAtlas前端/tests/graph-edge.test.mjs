import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import ts from 'typescript';

const source = fs.readFileSync(new URL('../src/graph/edgeGeometry.ts', import.meta.url), 'utf8');
const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext } }).outputText;
const { edgeGeometry } = await import(`data:text/javascript;base64,${Buffer.from(code).toString('base64')}`);
for (const count of [2, 3]) test(`${count} self loops have distinct paths and labels in both directions`, () => {
  for (const horizontal of [true, false]) {
    const loops = Array.from({ length: count }, (_, loopRank) => edgeGeometry({ sourceX: 240, sourceY: 38, targetX: 0, targetY: 38,
      self: true, horizontal, fan: (loopRank - (count - 1) / 2) * 42, loopRank }));
    assert.equal(new Set(loops.map(loop => loop.path)).size, count);
    assert.equal(new Set(loops.map(loop => JSON.stringify([loop.labelX, loop.labelY]))).size, count);
  }
});
