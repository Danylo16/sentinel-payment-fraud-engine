import { parse, stringify, parseNumberAndBigInt } from 'lossless-json';
export type Integer = bigint | number;
export function readJson(text: string): unknown {
  return parse(text, undefined, parseNumberAndBigInt);
}
export function writeJson(value: unknown): string {
  const result = stringify(value);
  if (result === undefined) throw new Error('Request cannot be serialized');
  return result;
}
export function centsFromEuros(value: string): bigint {
  const text = value.trim();
  if (!/^\d{1,17}(\.\d{1,2})?$/.test(text)) throw new Error('Enter EUR with at most two decimal places');
  const [whole, fraction = ''] = text.split('.');
  const cents = BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0'));
  if (cents < 1n || cents > 9223372036854775807n) throw new Error('Amount must be positive and fit a Java long');
  return cents;
}
export function euros(value: Integer): string {
  if (typeof value === 'number' && !Number.isSafeInteger(value)) throw new Error('Unsafe integer amount');
  const cents = BigInt(value); const abs = cents < 0n ? -cents : cents;
  const whole = (abs / 100n).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return `${cents < 0n ? '-' : ''}€${whole}.${(abs % 100n).toString().padStart(2, '0')}`;
}
