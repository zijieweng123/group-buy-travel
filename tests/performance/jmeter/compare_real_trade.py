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
    seed += '\nDROP TABLE IF EXISTS ticket_calendar_inventory;\n'
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


def restore_seed(folder, version, with_indexes=None):
    assert_owner()
    rt.command([rt.DOCKER,'exec','-i','group-buy-perf-mysql','mysql','-uroot',
                '-ploadtest-only','--default-character-set=utf8mb4'],data=comparison_seed(),timeout=60)
    rt.DATABASE = COMPARE_DB
    assert rt.sql('SELECT DATABASE()') == COMPARE_DB
    assert rt.rows('SELECT (SELECT COUNT(*) FROM group_buy_order_list),(SELECT COUNT(*) FROM notify_task)') == [['0','0']]
    # Only dedicated test Redis is cleared; run() first saves and verifies an RDB backup.
    rt.redis('FLUSHDB')
    apply_indexes = with_indexes if with_indexes is not None else version == 'optimized'
    if apply_indexes:
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



def application_metrics():
    import urllib.parse
    import urllib.request
    paths = {}
    for operation in ('lock','settlement'):
        for phase in ('begin','body','complete','total'):
            query = urllib.parse.urlencode([('tag','operation:'+operation),('tag','phase:'+phase)])
            paths[operation+'.'+phase] = 'trade.transaction.phase?'+query
    for short,name in {
        'pending':'pending','queued':'queued','coalesced':'coalesced','fallback':'fallback',
        'batch.calls':'batch.calls','batch.rows':'batch.rows','batch.size':'batch.size',
        'batch.failures':'batch.failures','deferred':'deferred','single.calls':'single.calls',
        'confirmation.lag':'confirmation.lag'
    }.items():
        paths['notify.'+short] = 'trade.notify.status.'+name
    result = {}
    for key,path in paths.items():
        with urllib.request.urlopen(rt.BASE+'/actuator/metrics/'+path,timeout=3) as response:
            value = json.load(response)
        result[key] = {m['statistic']:m['value'] for m in value['measurements']}
    return result


def mysql_io_snapshot():
    # Dedicated test server only; global file waits include fixture/warm-up/load/reconciliation.
    status = rt.rows("SHOW GLOBAL STATUS WHERE Variable_name IN "
                    "('Innodb_data_fsyncs','Innodb_os_log_fsyncs','Innodb_log_waits',"
                    "'Innodb_row_lock_time','Innodb_row_lock_waits','Innodb_log_write_requests',"
                    "'Com_stmt_prepare','Com_stmt_execute','Com_stmt_close','Prepared_stmt_count')")
    files = rt.rows("SELECT EVENT_NAME,COUNT_STAR,SUM_TIMER_WAIT,COUNT_READ,SUM_TIMER_READ,"
                    "COUNT_WRITE,SUM_TIMER_WRITE,COUNT_MISC,SUM_TIMER_MISC "
                    "FROM performance_schema.file_summary_by_event_name WHERE COUNT_STAR>0")
    return {'status':{row[0]:int(row[1]) for row in status},
            'files':{row[0]:dict(zip(('count','timePs','reads','readPs','writes','writePs','misc','miscPs'),map(int,row[1:])))
                     for row in files}}


def stop_application():
    if rt.app is not None and rt.app.poll() is None:
        try:
            rt.http('/actuator/shutdown',{},'POST')
            rt.app.wait(timeout=25)
        except Exception:
            pass
    rt.stop_app()


def start_phase(directory, version, jar, activity, topic, with_indexes=None, expected_pool=None, expected_notify_threads=None):
    rt.require_memory(1024)  # Containers already allocated; initial run requires 2048 MiB.
    restore_seed(directory,version,with_indexes)
    rt.TEAM_SUCCESS_TOPIC = topic
    rt.command([rt.DOCKER,'exec','-e','KAFKA_HEAP_OPTS=-Xms16m -Xmx64m','group-buy-perf-kafka',
                '/opt/kafka/bin/kafka-topics.sh','--bootstrap-server','kafka:19092','--create',
                '--if-not-exists','--topic',topic,'--partitions','1','--replication-factor','1'],timeout=45)
    rt.start_app(directory,jar)
    result = rt.smoke(directory,activity)
    assert result['passed'], 'Transaction smoke failed'
    if expected_pool is not None:
        metadata = rt.http('/actuator/metrics/hikaricp.connections.max')
        actual = {row['statistic']:row['value'] for row in metadata['measurements']}['VALUE']
        save(directory/'pool-configuration.json',{'expectedMax':expected_pool,'actualMax':actual})
        assert actual == expected_pool, 'Unexpected live Hikari capacity'
    if expected_notify_threads is not None:
        metadata=rt.http('/actuator/metrics/executor.pool.max?tag=name%3Atrade.notify')
        actual={row['statistic']:row['value'] for row in metadata['measurements']}['VALUE']
        save(directory/'notify-pool-configuration.json',{'expectedMax':expected_notify_threads,'actualMax':actual})
        assert actual==expected_notify_threads, 'Unexpected live notification pool capacity'
    return result


def write_report(folder, report):
    lines=['# Real group-buy optimization comparison','','State: '+report['state'],'',
           (str(len(report.get('manifest',{}).get('order',[])))+' validation run(s); 50 users; 60 seconds per run, first 15 seconds warm-up.'
            if report.get('manifest',{}).get('diagnosisOnly') else
            '50 trade users; 60 seconds per run, first 15 seconds warm-up; alternating versions, three runs each.'),
           'Real orders, settlement, inventory and Kafka consumption markers; payment callbacks are test-generated.',
           'Comparison kind: '+report.get('manifest',{}).get('comparisonKind','archived-builds'),'',
           '| Sequence | Version | Order success/s | Order P95 ms | Failures | Data gates |',
           '| --- | --- | ---: | ---: | ---: | --- |']
    for index,run in enumerate(report.get('runs',[]),1):
        lines.append(f"| {index} | {run['version']} | {run['order']['successTps']} | {run['order']['p95Ms']} | {run['allHttpFailures']} | {run['verification']['passed']} |")
    if report.get('comparison'):
        lines += ['',json.dumps(report['comparison'],ensure_ascii=False,indent=2)]
    if report.get('validation'):
        lines += ['',json.dumps(report['validation'],ensure_ascii=False,indent=2)]
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
    common_arguments = ['--management.endpoints.web.exposure.include=health,metrics,shutdown',
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
        for index,version in enumerate(manifest.get('preflightVersions',('baseline','optimized'))):
            target = directory/('preflight-'+version)
            target.mkdir()
            activity = 600000000+int(token)*100+index*10
            rt.APP_EXTRA_ARGUMENTS = common_arguments + manifest.get('versionArguments',{}).get(version,[])
            start_phase(target,version,Path(manifest[version+'Jar']),activity,'travel.cmp.'+token+'.c'+str(index),
                        True if manifest.get('indexesForBoth') else None, manifest.get('expectedPool',{}).get(version), manifest.get('expectedNotifyThreads',{}).get(version))
            checks = run_checks(target,activity+1,include_notify_recovery=manifest.get('observeTradeMetrics',False),
                                include_trade_reads=manifest.get('observeReadSnapshots',False))
            assert checks['passed'], 'Correctness preflight failed: '+version
            report[version+'Correctness'] = checks
            stop_application()
        for index,version in enumerate(manifest['order'],1):
            print(f"Comparison {index}/{len(manifest['order'])}: {version}",flush=True)
            target = directory/(str(index)+'-'+version)
            target.mkdir()
            activity = 700000000+int(token)*100+index*10
            rt.APP_EXTRA_ARGUMENTS = common_arguments + manifest.get('versionArguments',{}).get(version,[])
            start_phase(target,version,Path(manifest[version+'Jar']),activity,'travel.cmp.'+token+'.r'+str(index),
                        True if manifest.get('indexesForBoth') else None, manifest.get('expectedPool',{}).get(version), manifest.get('expectedNotifyThreads',{}).get(version))
            before = mysql_snapshot()
            metrics_before = application_metrics() if manifest.get('observeTradeMetrics') else None
            io_before = mysql_io_snapshot() if manifest.get('observeTradeMetrics') else None
            rt.fixture(activity+1)
            result = rt.jmeter_run(target/'load',activity+1,50,60,15,index)
            if manifest.get('observeRequestTimings'):
                from request_latency import analyze
                result['requestTimings'] = analyze(target/'load')
            result['version'] = version
            result['jarSha256'] = digest(Path(manifest[version+'Jar']))
            result['mysqlDelta'] = mysql_delta(before,mysql_snapshot())
            if metrics_before is not None:
                result['tradeMetrics'] = {'before':metrics_before,'after':application_metrics()}
                result['mysqlIo'] = {'before':io_before,'after':mysql_io_snapshot()}
                assert result['tradeMetrics']['after']['notify.pending'].get('VALUE') == 0, 'Pending status writes after reconciliation'
                if version in manifest.get('preparedStatements',{}):
                    io=result['mysqlIo'];before_status=io['before']['status'];after_status=io['after']['status']
                    executions=after_status.get('Com_stmt_execute',0)-before_status.get('Com_stmt_execute',0)
                    preparations=after_status.get('Com_stmt_prepare',0)-before_status.get('Com_stmt_prepare',0)
                    expected=manifest['preparedStatements'][version]
                    result['preparedStatements']={'enabled':expected,'executions':executions,'preparations':preparations,
                                                  'openStatements':after_status.get('Prepared_stmt_count',0)}
                    assert (executions>0)==expected, 'Server prepared statement configuration did not take effect'
                    if expected: assert executions>2*preparations, 'Prepared statements were not reused'
            record = rt.rows(f"SELECT team_id,user_id,out_trade_no FROM group_buy_order_list WHERE activity_id={activity+1} LIMIT 1")
            assert len(record)==1
            team,user,external = record[0]
            assert re.fullmatch(r"[0-9]{8}",team) and re.fullmatch(r"[a-zA-Z0-9_-]+",user) and re.fullmatch(r"[0-9]{12}",external)
            save(target/'explain-after-load.json', {
                'team-members':rt.rows(f"EXPLAIN SELECT out_trade_no FROM group_buy_order_list WHERE team_id='{team}' AND status=1"),
                'external-order':rt.rows(f"EXPLAIN SELECT order_id FROM group_buy_order_list WHERE user_id='{user}' AND out_trade_no='{external}' AND status=0")
            })
            if manifest.get('observeReadSnapshots'):
                import xml.etree.ElementTree as ET
                plans = {}
                parameters = {'userId':"'"+user+"'",'outTradeNo':"'"+external+"'",'activityId':str(activity+1),
                              'goodsId':"'9890001'",'travelDate':"'"+rt.TRAVEL_DATE+"'"}
                for filename,statement in [('group_buy_activity_mapper.xml','queryLockPrecheck'),
                                            ('group_buy_order_mapper.xml','querySettlementSnapshot')]:
                    mapper = ET.parse(rt.ROOT/'cultural-tourism-group-buy-app/src/main/resources/mybatis/mapper'/filename).getroot()
                    node = mapper.find("select[@id='"+statement+"']")
                    assert node is not None and len(node)==0
                    query = node.text
                    for name,value in parameters.items(): query = query.replace('#{'+name+'}',value)
                    assert '#{' not in query
                    plans[statement] = rt.rows('EXPLAIN '+query)
                save(target/'read-query-explain.json',plans)
            save(target/'comparison-summary.json',result)
            report['runs'].append(result)
            save(directory/'status.json',report)
            print(json.dumps({k:result[k] for k in ('version','order','allHttpFailures','passed')},ensure_ascii=False),flush=True)
            assert result['passed'], 'Business or data gates failed'
            stop_application()
        if not manifest.get('diagnosisOnly'):
            comparison = {}
            for version in ('baseline','optimized'):
                selected = [r for r in report['runs'] if r['version']==version]
                assert len(selected)==3 and all(r['passed'] for r in selected)
                comparison[version] = {key:statistics.median(r['order'][key] for r in selected)
                                       for key in ('successTps','p95Ms','p99Ms')}
            comparison['throughputIncreasePercent'] = round((comparison['optimized']['successTps']/comparison['baseline']['successTps']-1)*100,2)
            comparison['p95ReductionPercent'] = round((1-comparison['optimized']['p95Ms']/comparison['baseline']['p95Ms'])*100,2)
            report['comparison'] = comparison
        elif manifest.get('verificationOnly'):
            assert len(report['runs'])==3 and all(run['passed'] for run in report['runs'])
            report['validation']={op:{key:statistics.median(run[op][key] for run in report['runs'])
                                     for key in ('successTps','p95Ms','p99Ms')} for op in ('order','settlement')}
            report['validation']['qps100Reached']=report['validation']['order']['successTps']>=100
            report['validation']['orderP95Below500']=report['validation']['order']['p95Ms']<500
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
