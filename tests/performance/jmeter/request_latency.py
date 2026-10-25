"""Correlate fixed-window successful HTTP samples with opt-in server timings.
Stages nest and market tasks overlap; never add their percentiles or all stage means.
"""
import csv
import json
import math
import re
import statistics
from pathlib import Path

PATTERN=re.compile(r'([a-z_]+);dur=([0-9.]+)(?:;desc="([0-9]+)")?')
def parse(value):
    return {key:{'ms':float(ms),'count':int(count) if count else None}
            for key,ms,count in PATTERN.findall(value or '')}
def percentile(values, fraction):
    return sorted(values)[max(0,math.ceil(len(values)*fraction)-1)] if values else None

def analyze(directory):
    timing=json.loads((directory/'timing.json').read_text())
    with (directory/'samples.jtl').open(encoding='utf-8-sig',newline='') as stream:
        rows=[r for r in csv.DictReader(stream) if r['success']=='true' and
              timing['startMs']+15000<=int(r['timeStamp'])<timing['deadlineMs'] and
              r['label'] in ('order-leader','order-member','settle-leader','settle-member')]
    assert rows and all('app' in parse(r.get('serverTiming')) for r in rows), 'Missing request timing header'
    report={'scope':'50 users; 60s; first 15s excluded; successful HTTP samples only',
            'notes':['JDBC timing is client wall time, not MySQL-only SQL execution.',
                     'Pool/SQL/state/commit overlap business and transaction stages; do not sum nested stages.',
                     'Market task and queue times sum two parallel tasks, not critical-path wall time.',
                     'Client minus app includes pre-filter queue, network, serialization and client scheduling.']}
    for label in ('order','settle','order-leader','order-member','settle-leader','settle-member'):
        selected=[(r,parse(r['serverTiming'])) for r in rows if r['label']==label or r['label'].startswith(label+'-')]
        cutoff=percentile([int(r['elapsed']) for r,t in selected],.95)
        groups={}
        for name,group in [('all',selected),('slowest5Percent',[(r,t) for r,t in selected if int(r['elapsed'])>=cutoff])]:
            means={key:round(statistics.mean(t[key]['ms'] for r,t in group),3) for key in group[0][1]}
            groups[name]={'samples':len(group),'elapsedMeanMs':round(statistics.mean(int(r['elapsed']) for r,t in group),3),
                          'stageMeanMs':means,
                          'stageCountMean':{key:round(statistics.mean(t[key]['count'] for r,t in group),3)
                                            for key in group[0][1] if key!='app'},
                          'outsideAppMeanMs':round(statistics.mean(int(r['elapsed'])-t['app']['ms'] for r,t in group),3)}
        groups['p95Ms']=cutoff
        report[label]=groups
    (directory/'request-latency.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    return report
