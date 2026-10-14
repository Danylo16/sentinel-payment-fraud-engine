import React, { useEffect, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { HttpError, request } from './api';
import type { Account, Audit, Decision, Entry, Page, Transfer, TransferItem } from './api';
import { centsFromEuros, euros } from './money';
import type { Integer } from './money';
import './style.css';

type Attempt = { key: string; sourceAccountId: string; targetAccountId: string; cents: string };
type Summary = { transfer: Transfer; riskScore: Integer | null; fraudDecision: string | null };
const time = (value: string) => new Date(value).toLocaleString();
const short = (value: string) => value.slice(0, 8);
const message = (error: unknown) => error instanceof HttpError ? `${error.detail.code}: ${error.message}${error.status === 404 ? ' — check the ID or demo profile.' : ''}` : error instanceof Error ? error.message : 'Request failed';
function restoreAttempt(): Attempt | null {
  try {
    const row = JSON.parse(sessionStorage.getItem('sentinel-attempt') ?? 'null') as Attempt | null;
    return row && typeof row.key === 'string' && typeof row.sourceAccountId === 'string' && typeof row.targetAccountId === 'string' && /^\d+$/.test(row.cents) ? row : null;
  } catch { return null; }
}
function Badge({ value }: { value: string }) { return <span className={`badge ${value.toLowerCase()}`}>{value.replaceAll('_', ' ')}</span>; }
function App() {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [accountCursor, setAccountCursor] = useState<string | null>(null);
  const [transfers, setTransfers] = useState<TransferItem[]>([]);
  const [transferCursor, setTransferCursor] = useState<string | null>(null);
  const [view, setView] = useState('monitor');
  const [selected, setSelected] = useState<Transfer | null>(null);
  const [decision, setDecision] = useState<Decision | null>(null);
  const [audit, setAudit] = useState<Audit[]>([]);
  const [inspecting, setInspecting] = useState(false);
  const inspectVersion = useRef(0);
  const [selectedAccount, setSelectedAccount] = useState<Account | null>(null);
  const [history, setHistory] = useState<Entry[]>([]);
  const [historyCursor, setHistoryCursor] = useState<Integer | null>(null);
  const accountVersion = useRef(0);
  const [source, setSource] = useState(''); const [target, setTarget] = useState('');
  const [amount, setAmount] = useState('10.00');
  const [attempt, setAttempt] = useState<Attempt | null>(restoreAttempt);
  const [busy, setBusy] = useState(false); const [online, setOnline] = useState(false);
  const [error, setError] = useState(''); const [notice, setNotice] = useState('');
  const [auto, setAuto] = useState(false);
  const [creating, setCreating] = useState(false); const [name, setName] = useState('');
  const [funding, setFunding] = useState(false); const [fundTarget, setFundTarget] = useState('');
  const [token, setToken] = useState(''); const [fundAmount, setFundAmount] = useState('100.00');
  // A failed/ambiguous funding response retains its exact key and body, too. Token stays only in memory.
  const [fundAttempt, setFundAttempt] = useState<{ key: string; target: string; cents: string } | null>(null);
  const refreshing = useRef(false);
  const knownName = (id: string) => accounts.find(a => a.id === id)?.name ?? short(id);
  async function refresh() {
    if (refreshing.current) return;
    refreshing.current = true;
    try {
      const [a, t] = await Promise.all([request<Page<Account>>('/accounts?limit=50'), request<Page<Summary>>('/transfers?limit=50')]);
      setAccounts(a.data.items); setAccountCursor(a.data.nextBeforeId);
      setTransfers(t.data.items.map(row => ({ ...row.transfer, riskScore: row.riskScore, fraudDecision: row.fraudDecision })));
      setTransferCursor(t.data.nextBeforeId); setOnline(true);
      setSource(old => old || a.data.items[0]?.id || ''); setTarget(old => old || a.data.items[1]?.id || '');
      setFundTarget(old => old || a.data.items[0]?.id || '');
    } catch (e) { setOnline(false); setError(message(e)); }
    finally { refreshing.current = false; }
  }
  useEffect(() => { void refresh(); }, []);
  useEffect(() => { if (!auto) return; const timer = setInterval(() => void refresh(), 5000); return () => clearInterval(timer); }, [auto]);
  useEffect(() => {
    if (attempt) {
      sessionStorage.setItem('sentinel-attempt', JSON.stringify(attempt));
      setSource(attempt.sourceAccountId); setTarget(attempt.targetAccountId);
      const cents=BigInt(attempt.cents); setAmount(`${cents/100n}.${(cents%100n).toString().padStart(2,'0')}`);
    } else sessionStorage.removeItem('sentinel-attempt');
  }, [attempt]);
  async function inspect(id: string) {
    const version = ++inspectVersion.current; setInspecting(true); setDecision(null); setAudit([]);
    try {
      const t = await request<Transfer>(`/transfers/${id}`);
      const [f, a] = await Promise.all([
        request<Decision>(`/transfers/${id}/fraud`).catch(e => { if (e instanceof HttpError && e.status === 404) return null; throw e; }),
        request<Audit[]>(`/transfers/${id}/audit`)
      ]);
      if (version !== inspectVersion.current) return;
      setSelected(t.data); setDecision(f?.data ?? null); setAudit(a.data);
    } catch (e) { if (version === inspectVersion.current) { setSelected(null); setError(message(e)); } }
    finally { if (version === inspectVersion.current) setInspecting(false); }
  }
  async function runTransfer(saved?: Attempt) {
    setError(''); setNotice(''); setBusy(true);
    let intent: Attempt | null = saved ?? null;
    try {
      if (!intent) {
        if (!source || !target || source === target) throw new Error('Choose two different accounts');
        intent = { key: crypto.randomUUID(), sourceAccountId: source, targetAccountId: target, cents: centsFromEuros(amount).toString() };
        setAttempt(intent);
        // Persist BEFORE sending so an interrupted tab can retry the same intent.
        sessionStorage.setItem('sentinel-attempt', JSON.stringify(intent));
      }
      const result = await request<Transfer>('/transfers', 'POST', { sourceAccountId: intent.sourceAccountId, targetAccountId: intent.targetAccountId, amountCents: BigInt(intent.cents) }, { 'Idempotency-Key': intent.key });
      setNotice(`${result.data.status}${result.replayed ? ' · replayed, no new payment' : ''}${result.data.status === 'REVIEW' ? ' · no funds moved or reserved' : result.data.status === 'DECLINED' ? ' · no funds moved' : ''}`);
      await inspect(result.data.id); await refresh();
    } catch (e) {
      setError(message(e));
      if (e instanceof HttpError && e.detail.fields?.transferId) { await inspect(e.detail.fields.transferId); await refresh(); }
      else if (!(e instanceof HttpError) && intent) setNotice('Outcome may be unknown. Retry this exact request; keep its key.');
    } finally { setBusy(false); }
  }
  async function createAccount(event: React.FormEvent) {
    event.preventDefault(); setBusy(true); setError('');
    try { await request<Account>('/accounts','POST',{ name }); setName(''); setCreating(false); await refresh(); }
    catch(e) { setError(message(e)); } finally { setBusy(false); }
  }
  async function fund(event: React.FormEvent) {
    event.preventDefault(); setBusy(true); setError('');
    try {
      if (!token) throw new Error('Enter the token configured for the demo profile');
      const intent = fundAttempt ?? { key: crypto.randomUUID(), target: fundTarget, cents: centsFromEuros(fundAmount).toString() };
      if (!intent.target) throw new Error('Choose a funding account');
      setFundAttempt(intent);
      const result = await request<Transfer>('/demo/fundings','POST',{ targetAccountId: intent.target, amountCents: BigInt(intent.cents) },{ 'X-Demo-Token': token, 'Idempotency-Key': intent.key });
      setNotice(`${result.data.status} · demo funding${result.replayed ? ' replayed' : ''}`); await refresh(); await inspect(result.data.id);
    } catch(e) { setError(message(e)); } finally { setBusy(false); }
  }
  async function loadAccounts() {
    if (!accountCursor) return;
    try { const r = await request<Page<Account>>(`/accounts?limit=50&beforeId=${accountCursor}`); setAccounts(old=>[...old,...r.data.items]);setAccountCursor(r.data.nextBeforeId); }
    catch(e) { setError(message(e)); }
  }
  async function loadTransfers() {
    if (!transferCursor) return;
    try { const r = await request<Page<Summary>>(`/transfers?limit=50&beforeId=${transferCursor}`);setTransfers(old=>[...old,...r.data.items.map(row=>({...row.transfer,riskScore:row.riskScore,fraudDecision:row.fraudDecision}))]);setTransferCursor(r.data.nextBeforeId); }
    catch(e) { setError(message(e)); }
  }
  async function openAccount(account: Account, more = false) {
    const version = ++accountVersion.current;
    try {
      const [a, r] = await Promise.all([request<Account>(`/accounts/${account.id}`), request<{items:Entry[];nextBeforeId:Integer|null}>(`/accounts/${account.id}/transactions?limit=20${more && historyCursor !== null ? `&beforeId=${historyCursor}` : ''}`)]);
      if (version !== accountVersion.current) return;
      setSelectedAccount(a.data);setHistory(old=>more?[...old,...r.data.items]:r.data.items);setHistoryCursor(r.data.nextBeforeId);
    } catch(e) { if (version === accountVersion.current) setError(message(e)); }
  }
  const assessed = transfers.filter(t=>t.fraudDecision !== null);
  const counts = ['APPROVE','REVIEW','DECLINE'].map(d=>assessed.filter(t=>t.fraudDecision===d).length);
  const approvedPercent = assessed.length ? counts[0]/assessed.length*100 : 0;
  const reviewedPercent = assessed.length ? counts[1]/assessed.length*100 : 0;
  const balance = accounts.reduce((sum,a)=>sum+BigInt(a.balanceCents),0n);
  const options = accounts.map(a=><option key={a.id} value={a.id}>{a.name} · {euros(a.balanceCents)}</option>);
  const score = decision ? Number(decision.score) : 0;
  return <div className="shell">
    <aside className="sidebar"><a className="brand" href="/"><span className="shield">S</span><span>SENTINEL<small>PAYMENT & RISK ENGINE</small></span></a><div className="nav-caption">WORKSPACE</div>
      <button className={view==='monitor'?'nav active':'nav'} onClick={()=>setView('monitor')}>◈ <span>Transaction monitor</span></button>
      <button className={view==='accounts'?'nav active':'nav'} onClick={()=>setView('accounts')}>▤ <span>Accounts & ledger</span></button>
      <a className="nav" href="http://localhost:8080/swagger-ui.html" target="_blank" rel="noreferrer">↗ <span>API documentation</span></a>
      <div className="sidebar-note"><span className="dot"/> LOCAL SIMULATION<p>EUR only. Test funds.<br/>No bank connections.</p></div>
    </aside>
    <main><header><div><div className="eyebrow">OPERATIONS CONSOLE</div><h1>{view==='monitor'?'Payment intelligence':'Accounts & ledger'}</h1><p className="muted">Trace every transfer. Understand every decision.</p></div><div className="header-actions"><span className={`connection ${online?'ok':''}`}><span className="dot"/>{online?'API connected':'API unavailable'}</span><button onClick={()=>{setError('');void refresh();}}>Refresh</button><button className="primary" onClick={()=>setCreating(!creating)}>New account</button></div></header>
      <div className="simulation-banner">DEMO ENVIRONMENT <span>Simulated money · no customer authentication · keep on localhost</span></div>
      {error && <div role="alert" className="alert"><span>{error}</span><button onClick={()=>setError('')}>Dismiss</button></div>}
      {notice && <div role="status" className="notice">{notice}</div>}
      {creating && <form className="panel inline-form" onSubmit={createAccount}><label>Account name<input value={name} onChange={e=>setName(e.target.value)} maxLength={100} required autoFocus/></label><button className="primary" disabled={busy}>Create account</button></form>}
      <div className="metrics"><section className="metric"><span>Customer balance</span><strong>{euros(balance)}</strong><small>Across {accounts.length} loaded accounts</small></section><section className="metric"><span>Transfers loaded</span><strong>{transfers.length}</strong><small>Newest records · funding included</small></section><section className="metric"><span>Awaiting review</span><strong className="amber">{transfers.filter(t=>t.status==='REVIEW').length}</strong><small>In the loaded transfer sample</small></section><section className="metric"><span>Fraud declined</span><strong className="red">{counts[2]}</strong><small>In the loaded decision sample</small></section></div>
      <div className="workspace"><div className="left-column">
      {view==='monitor'?<section className="panel monitor"><div className="panel-title"><h2>Transaction monitoring</h2><label className="toggle"><input type="checkbox" checked={auto} onChange={e=>setAuto(e.target.checked)}/> Refresh every 5s</label></div><div className="table-wrap"><table><thead><tr><th>Time</th><th>Transfer</th><th>Route</th><th>Amount</th><th>Risk</th><th>Status</th></tr></thead><tbody>{transfers.map(t=><tr key={t.id} className={selected?.id===t.id?'selected':''}><td>{new Date(t.createdAt).toLocaleTimeString()}</td><td><button className="text-button mono" aria-label={`Inspect transfer ${t.id}`} onClick={()=>void inspect(t.id)}>{short(t.id)}</button><small>{t.operation}</small></td><td><span>{knownName(t.sourceAccountId)}</span><small>→ {knownName(t.targetAccountId)}</small></td><td className="money">{euros(t.amountCents)}</td><td>{t.riskScore===null?<span className="muted">—</span>:<span className={`score-pill ${t.fraudDecision?.toLowerCase()}`}>{String(t.riskScore)} / 100</span>}</td><td><Badge value={t.status}/></td></tr>)}</tbody></table>{!transfers.length&&<div className="empty">No transfers yet. Create accounts and send your first simulated payment.</div>}</div>{transferCursor&&<button className="load-more" disabled={auto} onClick={()=>void loadTransfers()}>Load older transfers</button>}<div className="panel-foot">{transfers.length} loaded records. Charts describe this sample, not lifetime totals.</div></section>:<section className="panel"><div className="panel-title"><h2>Customer accounts</h2><span className="muted">System contra excluded</span></div><div className="account-grid">{accounts.map(a=><button className="account-card" key={a.id} onClick={()=>void openAccount(a)}><strong>{a.name}</strong><span>{euros(a.balanceCents)}</span><small className="mono">{short(a.id)} · EUR</small></button>)}</div>{!accounts.length&&<div className="empty">Create your first account using “New account”.</div>}{accountCursor&&<button className="load-more" onClick={()=>void loadAccounts()}>Load older accounts</button>}{selectedAccount&&<div className="history"><h3>{selectedAccount.name} · {euros(selectedAccount.balanceCents)}</h3><p className="mono muted">{selectedAccount.id}</p><h4>Immutable ledger history</h4>{history.map(e=><div className="history-row" key={String(e.id)}><button className="text-button mono" onClick={()=>void inspect(e.transferId)}>{short(e.transferId)}</button><span>{e.operation}</span><strong className={BigInt(e.signedCents)<0n?'red':'cyan'}>{euros(e.signedCents)}</strong><small>{time(e.createdAt)}</small></div>)}{!history.length&&<p className="muted">No ledger entries.</p>}{historyCursor!==null&&<button onClick={()=>void openAccount(selectedAccount,true)}>Load older entries</button>}</div>}</section>}
      <section className="panel payment-panel"><div className="panel-title"><h2>New transfer</h2><span className="muted">EUR · integer cents</span></div><form onSubmit={e=>{e.preventDefault();void runTransfer();}}><div className="form-grid"><label>Source account<select value={source} onChange={e=>setSource(e.target.value)} disabled={busy||!!attempt}><option value="">Choose account</option>{options}</select></label><label>Recipient account<select value={target} onChange={e=>setTarget(e.target.value)} disabled={busy||!!attempt}><option value="">Choose account</option>{options}</select></label><label>Amount (EUR)<input inputMode="decimal" value={amount} onChange={e=>setAmount(e.target.value)} disabled={busy||!!attempt} placeholder="10.00"/></label><div className="submit-area"><button className="primary" disabled={busy||!!attempt||!accounts.length}>{busy?'Processing…':'Submit transfer'}</button></div></div></form>{attempt&&<div className="request-retry"><div><small>Persisted request key</small><code>{attempt.key}</code><small>{knownName(attempt.sourceAccountId)} → {knownName(attempt.targetAccountId)} · {euros(BigInt(attempt.cents))}</small></div><button disabled={busy} onClick={()=>void runTransfer(attempt)}>Retry same request</button><button disabled={busy} onClick={()=>{setAttempt(null);setNotice('');}}>New request</button></div>}<p className="hint">APPROVE may settle. REVIEW holds the request without moving or reserving funds. DECLINE moves no funds.</p></section>
      <section className="panel funding-panel"><button className="disclosure" onClick={()=>setFunding(!funding)}>{funding?'−':'+'} Controlled demo funding</button>{funding&&<form onSubmit={fund}><p className="hint">Requires the demo profile and your configured token. The system contra account supplies the matching debit.</p><div className="form-grid"><label>Funding account<select value={fundTarget} onChange={e=>setFundTarget(e.target.value)} disabled={busy||!!fundAttempt}><option value="">Choose account</option>{options}</select></label><label>Funding amount (EUR)<input inputMode="decimal" value={fundAmount} onChange={e=>setFundAmount(e.target.value)} disabled={busy||!!fundAttempt}/></label><label>Demo token<input type="password" value={token} onChange={e=>setToken(e.target.value)} autoComplete="off"/></label><button className="primary" disabled={busy}>{fundAttempt?'Retry same funding':'Fund demo account'}</button></div>{fundAttempt&&<div className="request-retry"><code>{fundAttempt.key}</code><button type="button" disabled={busy} onClick={()=>setFundAttempt(null)}>New funding request</button></div>}<p className="hint">The token is never saved to browser storage. Funding retries keep the same key.</p></form>}</section>
      </div><aside className="right-column"><section className="panel"><div className="panel-title"><h2>Fraud decisions</h2><span className="muted">{assessed.length} assessed</span></div><div className="donut-wrap"><div className="donut" style={{ background: assessed.length?`conic-gradient(var(--cyan) 0% ${approvedPercent}%, var(--amber) ${approvedPercent}% ${approvedPercent+reviewedPercent}%, var(--red) ${approvedPercent+reviewedPercent}% 100%)`:'var(--border)' }}><div><strong>{assessed.length?`${Math.round(approvedPercent)}%`:'—'}</strong><span>APPROVE</span></div></div><div className="legend">{['APPROVE','REVIEW','DECLINE'].map((d,i)=><div key={d}><span className={`legend-dot ${d.toLowerCase()}`}/><span>{d}</span><strong>{counts[i]}</strong></div>)}</div></div><p className="hint">Loaded decision sample. Funding and legacy records are not assessed.</p></section>
      <section className="panel inspector"><div className="panel-title"><h2>Decision inspector</h2>{selected&&<Badge value={selected.status}/>}</div>{inspecting?<div className="empty">Loading decision…</div>:!selected?<div className="empty">Select a transfer to inspect its risk, rules and audit trail.</div>:<><p className="mono transfer-id">{selected.id}</p><div className="gauge" style={{ background:`conic-gradient(${score>=70?'var(--red)':score>=40?'var(--amber)':'var(--cyan)'} ${score*3.6}deg, var(--border) 0deg)` }}><div><strong>{decision?score:'—'}<small>/100</small></strong><span>{decision?.decision??'NOT ASSESSED'}</span></div></div><div className="detail-pair"><span>Amount</span><strong>{euros(selected.amountCents)}</strong></div>{selected.failureReason&&<p className="red">{selected.failureReason}</p>}{decision?<><p className="hint">{decision.engineVersion} · {time(decision.evaluatedAt)}</p><p className="hint">REVIEW ≥ {String(decision.reviewThreshold)} · DECLINE ≥ {String(decision.declineThreshold)}</p><h3>Triggered rules</h3>{decision.rules.map(r=><div className="rule" key={r.ruleId}><div><strong>{r.ruleId}</strong><span>+{String(r.contribution)}</span></div><p>{r.reason}</p><details><summary>Observed evidence</summary><dl>{Object.entries(r.evidence).map(([key,value])=><React.Fragment key={key}><dt>{key}</dt><dd>{value}</dd></React.Fragment>)}</dl></details></div>)}{!decision.rules.length&&<p className="muted">No rules triggered.</p>}</>:<p className="hint">Funding or a legacy payment has no fraud assessment.</p>}<h3>Status audit</h3><ol className="audit">{audit.map(a=><li key={String(a.id)}><span>{a.eventType}</span><small>{time(a.createdAt)}</small></li>)}</ol></>}</section>
      </aside></div><footer>SENTINEL · Educational fintech simulation<span>Java 21 / PostgreSQL / React</span></footer>
    </main>
  </div>;
}
createRoot(document.getElementById('root')!).render(<App/>);
