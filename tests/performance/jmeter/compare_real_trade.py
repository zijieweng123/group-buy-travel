"""Prepare or run a fresh-data, alternating real-trade comparison."""
import argparse
import hashlib
import json
import re
import shutil
import socket
import statistics
import zipfile
from datetime import datetime
from pathlib import Path
import run_real_trade as rt

COMPARE_DB = 'cultural_tourism_group_buy_compare'
OWNED = ('group-buy-perf-mysql', 'group-buy-perf-redis', 'group-buy-perf-kafka')
INDEX_SQL = rt.ROOT/'docs/dev-ops/mysql/sql/3-2-trade-performance-indexes.sql'


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_files():
    files = [p for module in rt.ROOT.glob('cultural-tourism-group-buy-*')
             for p in (module/'src/main').rglob('*') if p.is_file()]
    return files + [rt.ROOT/'pom.xml'] + list(rt.ROOT.glob('cultural-tourism-group-buy-*/pom.xml'))


def comparison_seed():
    base = (rt.ROOT/'docs/dev-ops/mysql/sql/2-19-group_buy_market.sql').read_text(encoding='utf-8')
    extra = (rt.ROOT/'docs/dev-ops/mysql/sql/3-1-travel-ticket-extension.sql').read_text(encoding='utf-8')
    seed = base.replace('group_buy_market', COMPARE_DB)
    seed += '\nDELETE FROM notify_task;\nDELETE FROM group_buy_order_list;\nDELETE FROM group_buy_order;\nDELETE FROM crowd_tags_job;\n'
    seed += extra.replace('cultural_tourism_group_buy', COMPARE_DB)
    uses = set(re.findall(r'(?im)^\s*USE\s+\x60?([a-zA-Z0-9_]+)', seed))
    assert uses == {COMPARE_DB}, uses
    assert not re.search(r'\bDROP\s+DATABASE\b', seed, re.I)
    assert COMPARE_DB == 'cultural_tourism_group_buy_compare'
    return seed


def prepare(folder):
    assert folder.resolve().is_relative_to((rt.HERE/'results').resolve())
    baseline = folder/'baseline.jar'
    recorded = json.loads((folder/'baseline-manifest.json').read_text(encoding='utf-8'))
    assert digest(baseline) == recorded['baselineJarSha256']
    current = rt.ROOT/'cultural-tourism-group-buy-app/target/cultural-tourism-group-buy-app.jar'
    files = source_files()
    assert all(p.stat().st_mtime <= current.stat().st_mtime for p in files), 'Build the latest application first'
    sha = digest(current)
    assert sha != digest(baseline), 'Optimized build still equals baseline'
    optimized = folder/('optimized-'+sha[:12]+'.jar')
    if not optimized.exists():
        shutil.copy2(current, optimized)
    archive = folder/('optimized-sources-'+sha[:12]+'.zip')
    if not archive.exists():
        with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as output:
            for path in files:
                output.write(path, path.relative_to(rt.ROOT))
            output.write(INDEX_SQL, INDEX_SQL.relative_to(rt.ROOT))
    (folder/'comparison-bootstrap.sql').write_text(comparison_seed(), encoding='utf-8')
    manifest = {'state':'prepared-awaiting-user-resume', 'directory':str(folder),
                'baselineJar':str(baseline), 'baselineSha256':digest(baseline),
                'optimizedJar':str(optimized), 'optimizedSha256':sha,
                'sourceHashes':{p.relative_to(rt.ROOT).as_posix():digest(p) for p in files},
                'indexSqlSha256':digest(INDEX_SQL), 'seedSha256':digest(folder/'comparison-bootstrap.sql'),
                'database':COMPARE_DB, 'businessHttpRequests':0, 'loadTestExecuted':False,
                'order':['baseline','optimized','optimized','baseline','baseline','optimized']}
    save(folder/'prepared.json', manifest)
    return manifest


def assert_owner():
    for name in OWNED:
        label = rt.command([rt.DOCKER,'inspect','-f',
                            '{{index .Config.Labels "com.docker.compose.project"}}',name])
        assert label == 'group-buy-perf', (name, label)


def restore_seed(folder, version):
    assert_owner()
    rt.command([rt.DOCKER,'exec','-i','group-buy-perf-mysql','mysql','-uroot',
                '-ploadtest-only','--default-character-set=utf8mb4'],data=comparison_seed(),timeout=60)
    rt.DATABASE = COMPARE_DB
    assert rt.sql('SELECT DATABASE()') == COMPARE_DB
    assert rt.rows('SELECT (SELECT COUNT(*) FROM group_buy_order_list),(SELECT COUNT(*) FROM notify_task)') == [['0','0']]
    # Only dedicated test Redis is cleared; run() first saves and verifies an RDB backup.
    rt.redis('FLUSHDB')
    if version == 'optimized':
        rt.sql(INDEX_SQL.read_text(encoding='utf-8'))
    save(folder/'indexes.json', rt.rows("SELECT INDEX_NAME,GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) "
         "FROM information_schema.STATISTICS "
         f"WHERE TABLE_SCHEMA='{COMPARE_DB}' AND TABLE_NAME='group_buy_order_list' GROUP BY INDEX_NAME"))
    plans = {}
    for name,statement in {
        'team-members':"EXPLAIN SELECT out_trade_no FROM group_buy_order_list WHERE team_id='12345678' AND status=1",
        'external-order':"EXPLAIN SELECT order_id FROM group_buy_order_list WHERE user_id='example' AND out_trade_no='100000000000' AND status=0"
    }.items():
        plans[name] = rt.rows(statement)
    save(folder/'explain.json', plans)


def mysql_snapshot():
    records = rt.rows("SELECT COALESCE(DIGEST,''),COUNT_STAR,SUM_TIMER_WAIT,SUM_LOCK_TIME,SUM_ROWS_EXAMINED,"
                      "REPLACE(REPLACE(DIGEST_TEXT,CHAR(10),' '),CHAR(9),' ') "
                      "FROM performance_schema.events_statements_summary_by_digest "
                      f"WHERE SCHEMA_NAME='{COMPARE_DB}'")
    return {r[0]:{'count':int(r[1]),'timePs':int(r[2]),'lockPs':int(r[3]),
                  'rowsExamined':int(r[4]),'sql':r[5]} for r in records}


def mysql_delta(before, after):
    result = []
    for key,value in after.items():
        previous = before.get(key, {})
        count = value['count']-previous.get('count',0)
        if count > 0:
            total = value['timePs']-previous.get('timePs',0)
            result.append({'sql':value['sql'],'count':count,'averageMs':total/count/1e9,
                           'totalMs':total/1e9,'lockMs':(value['lockPs']-previous.get('lockPs',0))/1e9,
                           'rowsExamined':value['rowsExamined']-previous.get('rowsExamined',0)})
    return sorted(result,key=lambda row:-row['totalMs'])


def stop_application():
    if rt.app is not None and rt.app.poll() is None:
        try:
            rt.http('/actuator/shutdown',{},'POST')
            rt.app.wait(timeout=25)
        except Exception:
            pass
    rt.stop_app()


def start_phase(directory, version, jar, activity, topic):
    rt.require_memory(1024)  # Containers already allocated; initial run requires 2048 MiB.
    restore_seed(directory,version)
    rt.TEAM_SUCCESS_TOPIC = topic
    rt.command([rt.DOCKER,'exec','-e','KAFKA_HEAP_OPTS=-Xms16m -Xmx64m','group-buy-perf-kafka',
                '/opt/kafka/bin/kafka-topics.sh','--bootstrap-server','kafka:19092','--create',
                '--if-not-exists','--topic',topic,'--partitions','1','--replication-factor','1'],timeout=45)
    rt.start_app(directory,jar)
    result = rt.smoke(directory,activity)
    assert result['passed'], 'Transaction smoke failed'
    return result


def write_report(folder, report):
    lines=['# Real group-buy optimization comparison','','State: '+report['state'],'',
           '50 trade users; 60 seconds per run, first 15 seconds warm-up; alternating versions, three runs each.',
           'Real orders, settlement, inventory and Kafka consumption markers; payment callbacks are test-generated.','',
           '| Sequence | Version | Order success/s | Order P95 ms | Failures | Data gates |',
           '| --- | --- | ---: | ---: | ---: | --- |']
    for index,run in enumerate(report.get('runs',[]),1):
        lines.append(f"| {index} | {run['version']} | {run['order']['successTps']} | {run['order']['p95Ms']} | {run['allHttpFailures']} | {run['verification']['passed']} |")
    if report.get('comparison'):
        lines += ['',json.dumps(report['comparison'],ensure_ascii=False,indent=2)]
    if report.get('error'):
        lines += ['','Error: '+report['error']]
    lines += ['','Each run restores the dedicated comparison database, clears dedicated test Redis after an RDB backup, and uses a new topic.',
              'The original business database is retained. The comparison database retains its last run.',
              'SQL deltas include fixture creation, warm-up, measurement and reconciliation. TPS/P95 use the fixed measurement window.',
              'All correctness and consistency gates must pass, with three runs per version, before reporting percentage changes.']
    (folder/'REPORT.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')


def run(folder, manifest):
    rt.require_memory(2048)
    directory = folder/('comparison-'+datetime.now().strftime('%Y%m%d-%H%M%S'))
    directory.mkdir()
    report = {'state':'running','startedAt':datetime.now().astimezone().isoformat(),
              'manifest':manifest,'runs':[]}
    rt.APP_EXTRA_ARGUMENTS = ['--management.endpoints.web.exposure.include=health,metrics,shutdown',
                             '--management.endpoint.shutdown.enabled=true',
                             '--spring.kafka.consumer.properties.session.timeout.ms=10000',
                             '--spring.kafka.consumer.properties.heartbeat.interval.ms=2000']
    rt.OBSERVE_METRICS = True
    token = datetime.now().strftime('%m%d%H%M%S')
    try:
        rt.prepare_bootstrap()
        rt.command([rt.COMPOSE,'-p','group-buy-perf','-f',rt.HERE/'compose.loadtest.yml','up','-d','--wait'],timeout=300)
        assert_owner()
        rt.redis('SAVE')
        backup = directory/'redis-before-comparison.rdb'
        rt.command([rt.DOCKER,'cp','group-buy-perf-redis:/data/dump.rdb',backup],timeout=45)
        assert backup.exists() and backup.stat().st_size > 0
        report['redisBackupSha256'] = digest(backup)
        report['durabilityAndPool'] = rt.rows('SELECT @@innodb_flush_log_at_trx_commit,@@sync_binlog,@@innodb_buffer_pool_size,@@max_connections')
        from check_consistency import run_checks
        for index,version in enumerate(('baseline','optimized')):
            target = directory/('preflight-'+version)
            target.mkdir()
            activity = 600000000+int(token)*100+index*10
            start_phase(target,version,Path(manifest[version+'Jar']),activity,'travel.cmp.'+token+'.c'+str(index))
            checks = run_checks(target,activity+1)
            assert checks['passed'], 'Correctness preflight failed: '+version
            report[version+'Correctness'] = checks
            stop_application()
        for index,version in enumerate(manifest['order'],1):
            print(f'Comparison {index}/6: {version}',flush=True)
            target = directory/(str(index)+'-'+version)
            target.mkdir()
            activity = 700000000+int(token)*100+index*10
            start_phase(target,version,Path(manifest[version+'Jar']),activity,'travel.cmp.'+token+'.r'+str(index))
            before = mysql_snapshot()
            rt.fixture(activity+1)
            result = rt.jmeter_run(target/'load',activity+1,50,60,15,index)
            result['version'] = version
            result['jarSha256'] = digest(Path(manifest[version+'Jar']))
            result['mysqlDelta'] = mysql_delta(before,mysql_snapshot())
            record = rt.rows(f"SELECT team_id,user_id,out_trade_no FROM group_buy_order_list WHERE activity_id={activity+1} LIMIT 1")
            assert len(record)==1
            team,user,external = record[0]
            assert re.fullmatch(r"[0-9]{8}",team) and re.fullmatch(r"[a-zA-Z0-9_-]+",user) and re.fullmatch(r"[0-9]{12}",external)
            save(target/'explain-after-load.json', {
                'team-members':rt.rows(f"EXPLAIN SELECT out_trade_no FROM group_buy_order_list WHERE team_id='{team}' AND status=1"),
                'external-order':rt.rows(f"EXPLAIN SELECT order_id FROM group_buy_order_list WHERE user_id='{user}' AND out_trade_no='{external}' AND status=0")
            })
            save(target/'comparison-summary.json',result)
            report['runs'].append(result)
            save(directory/'status.json',report)
            print(json.dumps({k:result[k] for k in ('version','order','allHttpFailures','passed')},ensure_ascii=False),flush=True)
            assert result['passed'], 'Business or data gates failed'
            stop_application()
        comparison = {}
        for version in ('baseline','optimized'):
            selected = [r for r in report['runs'] if r['version']==version]
            assert len(selected)==3 and all(r['passed'] for r in selected)
            comparison[version] = {key:statistics.median(r['order'][key] for r in selected)
                                   for key in ('successTps','p95Ms','p99Ms')}
        comparison['throughputIncreasePercent'] = round((comparison['optimized']['successTps']/comparison['baseline']['successTps']-1)*100,2)
        comparison['p95ReductionPercent'] = round((1-comparison['optimized']['p95Ms']/comparison['baseline']['p95Ms'])*100,2)
        report['comparison'] = comparison
        report['state'] = 'completed'
    except Exception as failure:
        report['state'] = 'failed'
        report['error'] = repr(failure)
        raise
    finally:
        if rt.load and rt.load.poll() is None:
            with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as control:
                control.sendto(b'Shutdown',('127.0.0.1',4545))
            try:
                rt.load.wait(timeout=35)
            except Exception:
                rt.load.terminate()
                rt.load.wait(timeout=10)
        stop_application()
        try:
            existing = rt.command([rt.DOCKER,'ps','-a','--filter',
                                   'label=com.docker.compose.project=group-buy-perf',
                                   '--format','{{.Names}}']).splitlines()
            owned = [name for name in existing if name in OWNED]
            for name in owned:
                label = rt.command([rt.DOCKER,'inspect','-f',
                                    '{{index .Config.Labels "com.docker.compose.project"}}',name])
                assert label == 'group-buy-perf'
            if owned:
                rt.command([rt.DOCKER,'stop',*owned],timeout=50)
            report['containersStopped'] = True
        except Exception as failure:
            report['cleanupWarning'] = repr(failure)
        report['finishedAt'] = datetime.now().astimezone().isoformat()
        save(directory/'status.json',report)
        write_report(directory,report)
        save(folder/'last-run.json',{'directory':str(directory),'state':report['state']})
        print('Comparison state: '+report['state'],flush=True)


def main():
    parser = argparse.ArgumentParser()
    actions = parser.add_mutually_exclusive_group()
    actions.add_argument('--prepare-only',action='store_true')
    actions.add_argument('--validate-only',action='store_true')
    actions.add_argument('--run',action='store_true')
    parser.add_argument('--comparison-dir',type=Path)
    args = parser.parse_args()
    folder = args.comparison_dir or Path(json.loads((rt.HERE/'results/optimization-latest.json').read_text(encoding='utf-8'))['directory'])
    manifest = prepare(folder)
    if args.validate_only:
        rt.require_memory(512)
        target = folder/('validation-'+datetime.now().strftime('%Y%m%d-%H%M%S'))
        target.mkdir()
        save(target/'validation.json',rt.validate_plan(target))
        print('Validation passed: zero business HTTP requests',flush=True)
    elif args.run:
        run(folder,manifest)
    else:
        print(json.dumps({'prepared':True,'directory':str(folder),'businessHttpRequests':0,'loadTestExecuted':False}),flush=True)


if __name__ == '__main__':
    main()
