import { readJson, writeJson } from './money';
import type { Integer } from './money';
export type Account = { id: string; name: string; currency: string; balanceCents: Integer; createdAt: string };
export type Transfer = { id: string; operation: string; sourceAccountId: string; targetAccountId: string; amountCents: Integer; status: string; failureReason: string | null; createdAt: string };
export type TransferItem = Transfer & { riskScore: Integer | null; fraudDecision: string | null };
export type Page<T> = { items: T[]; nextBeforeId: string | null };
export type Decision = { score: Integer; decision: string; engineVersion: string; evaluatedAt: string; reviewThreshold: Integer; declineThreshold: Integer; rules: { ruleId: string; contribution: Integer; reason: string; evidence: Record<string,string> }[] };
export type Audit = { id: Integer; eventType: string; createdAt: string };
export type Entry = { id: Integer; transferId: string; signedCents: Integer; operation: string; createdAt: string };
export type ApiError = { code: string; message: string; errorId: string; fields?: Record<string,string> };
export class HttpError extends Error {
  constructor(public status: number, public detail: ApiError) { super(detail.message); }
}
export async function request<T>(path: string, method = 'GET', body?: unknown, headers: Record<string,string> = {}): Promise<{ data: T; status: number; replayed: boolean }> {
  const response = await fetch(`/api${path}`, { method, headers: { ...(body === undefined ? {} : { 'Content-Type': 'application/json' }), ...headers }, body: body === undefined ? undefined : writeJson(body) });
  const data = readJson(await response.text());
  if (!response.ok) throw new HttpError(response.status, data as ApiError);
  return { data: data as T, status: response.status, replayed: response.headers.get('Idempotency-Replayed') === 'true' };
}
