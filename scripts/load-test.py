#!/usr/bin/env python3
"""Closed-loop localhost benchmark. Standard library only; never targets real financial services."""
import argparse, concurrent.futures, json, math, os, platform, statistics, time, urllib.error, urllib.request, urllib.parse, uuid
from pathlib import Path

parser=argparse.ArgumentParser()
parser.add_argument('--base-url',default='http://127.0.0.1:8080')
parser.add_argument('--requests',type=int,default=500)
parser.add_argument('--workers',type=int,default=8)
parser.add_argument('--output',default='target/performance/results.json')
args=parser.parse_args()
if urllib.parse.urlsplit(args.base_url).hostname not in ('localhost','127.0.0.1','::1'): parser.error('Only localhost simulation targets are allowed')
if not 1<=args.workers<=32 or not 1<=args.requests<=10000: parser.error('Use 1..32 workers and 1..10000 requests')
token=os.environ.get('DEMO_FUNDING_TOKEN')
if not token: parser.error('Set DEMO_FUNDING_TOKEN; token is never written to the report')

def http(method,path,body=None,headers=None):
    req=urllib.request.Request(args.base_url+path,method=method,data=json.dumps(body).encode() if body is not None else None,
                               headers={'Content-Type':'application/json',**(headers or {})})
    try:
        with urllib.request.urlopen(req,timeout=20) as response: return response.status,json.load(response)
    except urllib.error.HTTPError as response: return response.code,json.load(response)

def transfer(pair):
    start=time.perf_counter()
    code,result=http('POST','/transfers',{'sourceAccountId':pair[0],'targetAccountId':pair[1],'amountCents':1},{'Idempotency-Key':str(uuid.uuid4())})
    return (time.perf_counter()-start)*1000,code,result.get('status',result.get('code','UNKNOWN'))

def setup_pair():
    ids=[]
    for side in ('A','B'):
        code,a=http('POST','/accounts',{'name':f'Benchmark {side} {uuid.uuid4()}'});assert code==201
        code,f=http('POST','/demo/fundings',{'targetAccountId':a['id'],'amountCents':1000000},{'X-Demo-Token':token,'Idempotency-Key':str(uuid.uuid4())});assert code==201 and f['status']=='COMPLETED'
        ids.append(a['id'])
    for pair in (ids,ids[::-1]):
        _,code,state=transfer(pair);assert code==201 and state=='COMPLETED'
    return ids

def percentile(values,p): return sorted(values)[max(0,math.ceil(len(values)*p)-1)]
def run(name,pairs):
    # Fixed workers, each repeatedly sends its own requests. No unbounded submission queue.
    def worker(index):
        pair=pairs[index%len(pairs)];return [transfer(pair) for _ in range(index,args.requests,args.workers)]
    start=time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        observations=[item for group in pool.map(worker,range(args.workers)) for item in group]
    elapsed=time.perf_counter()-start;latencies=[o[0] for o in observations]
    codes={str(c):sum(o[1]==c for o in observations) for c in sorted({o[1] for o in observations})}
    states={s:sum(o[2]==s for o in observations) for s in sorted({o[2] for o in observations})}
    result={'scenario':name,'requests':len(observations),'workers':args.workers,'pairs':len(pairs),'elapsed_seconds':round(elapsed,3),
            'requests_per_second':round(len(observations)/elapsed,2),'latency_ms':{'p50':round(percentile(latencies,.5),2),'p95':round(percentile(latencies,.95),2),'p99':round(percentile(latencies,.99),2),'mean':round(statistics.mean(latencies),2)},'http_counts':codes,'status_counts':states}
    print(json.dumps(result),flush=True)
    if states!={'COMPLETED':args.requests} or codes!={'201':args.requests}: raise RuntimeError('Benchmark failed correctness; do not publish successful throughput')
    return result

shared=setup_pair();independent=[setup_pair() for _ in range(args.workers)]
results=[run('shared_source', [shared]),run('independent_sources',independent)]
report={'captured_at_utc':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),'commit':os.environ.get('GITHUB_SHA','local'),
        'platform':platform.platform(),'cpu_count':os.cpu_count(),'python':platform.python_version(),
        'method':'Closed-loop HTTP; fresh requests with unique keys; 1-cent transfers; warmed recipient relations; default fraud policy; no automatic retries. Setup is excluded. Shared database, sequential scenarios, not a saturation/capacity claim.',
        'scenarios':results,'source_ids':[shared[0],*[p[0] for p in independent]]}
path=Path(args.output);path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(report,indent=2)+'\n')
