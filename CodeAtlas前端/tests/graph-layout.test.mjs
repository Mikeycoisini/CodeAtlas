import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import ts from 'typescript';

const source = fs.readFileSync(new URL('../src/graph/layout.ts', import.meta.url), 'utf8');
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText
  .replaceAll('"@dagrejs/dagre"', JSON.stringify(import.meta.resolve('@dagrejs/dagre')));
const { layoutGraph } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`);

test('layout preserves input, cycles, self loops and parallel status edges deterministically', () => {
  const nodes = ['a', 'b', 'c'].map(id => Object.freeze({ id }));
  const edges = [{ id: 'ab', source: 'a', target: 'b' }, { id: 'ba', source: 'b', target: 'a' }, { id: 'aa', source: 'a', target: 'a' }, { id: 'ab2', source: 'a', target: 'b' }];
  const before = JSON.stringify({ nodes, edges });
  const result = layoutGraph(nodes, edges, 'LR');
  assert.equal(JSON.stringify({ nodes, edges }), before);
  assert.equal(result.length, 3);
  assert.deepEqual(result, layoutGraph([...nodes].reverse(), [...edges].reverse(), 'LR'));
  for (const node of result) assert.ok(Number.isFinite(node.position.x) && Number.isFinite(node.position.y));
  assert.notDeepEqual(result, layoutGraph(nodes, edges, 'TB'));
});

test('bounded layout sample measurements', () => {
  for (const count of [50, 500, 1000]) {
    const nodes = Array.from({ length: count }, (_, index) => ({ id: `n${index}` }));
    const edges = nodes.slice(1).map((node, index) => ({ id: `e${index}`, source: `n${Math.floor(index / 3)}`, target: node.id }));
    const start = performance.now();
    const output = layoutGraph(nodes, edges, 'LR');
    assert.equal(output.length, count);
    console.log(JSON.stringify({ count, milliseconds: Math.round(performance.now() - start) }));
  }
});

test('edge labels have readable clearance between horizontal layers', () => {
  const positioned = layoutGraph([{ id: 'a' }, { id: 'b' }], [{ id: 'ab', source: 'a', target: 'b' }], 'LR');
  const a = positioned.find(node => node.id === 'a'); const b = positioned.find(node => node.id === 'b');
  assert.ok(b.position.x - a.position.x - 240 >= 220);
});
