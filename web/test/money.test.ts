import test from 'node:test';
import assert from 'node:assert/strict';
import { centsFromEuros, euros, readJson, writeJson } from '../src/money.ts';
test('preserves Java long cents across JSON and display', () => {
  const row = readJson('{"balanceCents":9223372036854775807}') as { balanceCents: bigint };
  assert.equal(row.balanceCents, 9223372036854775807n);
  assert.equal(euros(row.balanceCents), '€92,233,720,368,547,758.07');
  assert.equal(writeJson(row), '{"balanceCents":9223372036854775807}');
});
test('decimal input uses exact integer arithmetic', () => {
  assert.equal(centsFromEuros('0.01'),1n);
  assert.equal(centsFromEuros('10.10'),1010n);
  assert.equal(centsFromEuros('92233720368547758.07'),9223372036854775807n);
  assert.equal(euros(-101n),'-€1.01');
});
test('rejects fractions of a cent, zero, exponent syntax and overflow', () => {
  for (const value of ['0','0.001','1e3','-1','1,00','92233720368547758.08']) assert.throws(()=>centsFromEuros(value));
  assert.throws(()=>euros(Number.MAX_SAFE_INTEGER+1));
});
