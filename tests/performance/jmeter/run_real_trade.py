"""Run only the real group-buy project's HTTP orders, MySQL, Redis and Kafka."""
import argparse
import concurrent.futures
import csv
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import socket
import statistics
import subprocess
import sys
import time
from datetime import datetime, timedelta
import urllib.error
import urllib.request
import psutil

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
if ROOT.name != 'group-buy-travel':
    ROOT = Path('G:/Astudy/Java/autumn-recruitment/Project/group-buy-travel')
    HERE = ROOT / 'tests/performance/jmeter'
DOCKER = shutil.which('docker') or 'C:/Program Files/Docker/Docker/resources/bin/docker.exe'
COMPOSE = 'C:/Program Files/Docker/Docker/resources/bin/docker-compose.exe'
JAVA = os.environ.get('PERF_JAVA', 'C:/Users/19445/.jdks/ms-17.0.16/bin/java.exe')
JMETER = Path(os.environ.get('JMETER_JAR', str(ROOT.parent / 'travel-ticket-assistant/target/perf-tools/apache-jmeter-5.6.3/bin/ApacheJMeter.jar')))
BASE = 'http://127.0.0.1:38091'
FLAGS = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
TRAVEL_DATE = (datetime.now().date() + timedelta(days=1)).isoformat()
app = None
load = None
DATABASE = 'cultural_tourism_group_buy'
APP_EXTRA_ARGUMENTS = []
OBSERVE_METRICS = False
TEAM_SUCCESS_TOPIC = 'travel.loadtest.team-success'


def prepare_bootstrap():
    """Adapt only disposable test SQL; original development SQL is unchanged."""
    directory = HERE / 'bootstrap'
    directory.mkdir(exist_ok=True)
    base = ROOT / 'docs/dev-ops/mysql/sql/2-19-group_buy_market.sql'
    extension = ROOT / 'docs/dev-ops/mysql/sql/3-1-travel-ticket-extension.sql'
    original = base.read_text(encoding='utf-8')
    assert 'use `group_buy_market`;' in original
    adapted = original.replace('group_buy_market', 'cultural_tourism_group_buy')
    adapted += '\nDELETE FROM notify_task;\nDELETE FROM group_buy_order_list;\nDELETE FROM group_buy_order;\nDELETE FROM crowd_tags_job;\n'
    (directory/'01-base.sql').write_text(adapted, encoding='utf-8')
    shutil.copyfile(extension, directory/'02-travel.sql')
    manifest = {'database': 'cultural_tourism_group_buy', 'initialSeedTransactionsRemoved': True,
                'baseSourceSha256': hashlib.sha256(base.read_bytes()).hexdigest(),
                'extensionSourceSha256': hashlib.sha256(extension.read_bytes()).hexdigest()}
    (directory/'manifest.json').write_text(json.dumps(manifest,indent=2), encoding='utf-8')
    return manifest


def require_memory(minimum_mib):
    available = psutil.virtual_memory().available / 1048576
    if available < minimum_mib:
        raise RuntimeError(f'Available memory {available:.1f} MiB is below startup reserve {minimum_mib} MiB; no load started')


def validate_plan(directory):
    """Load the real plan and compile scripts while executing zero HTTP requests."""
    import xml.etree.ElementTree as ET
    ET.parse(HERE/'real-trade.jmx')
    probe = directory/'validation'
    probe.mkdir()
    arguments=[JAVA,'-Xms16m','-Xmx96m','-XX:ActiveProcessorCount=1','-jar',JMETER,
               '-n','-t',HERE/'real-trade.jmx','-q',HERE/'jmeter.properties',
               '-l',probe/'samples.jtl','-j',probe/'jmeter.log',
               '-Jperf.dir='+HERE.as_posix(),'-Jout.dir='+probe.as_posix(),
               '-Jthreads=1','-Jrampup=0','-Jduration=1','-Jloops=0',
               '-Jactivity.id=0','-Jorder.base=100000000000','-Jrun.id=validation']
    output=command(arguments,timeout=60)
    (probe/'console.log').write_text(output,encoding='utf-8')
    with (probe/'samples.jtl').open(encoding='utf-8-sig',newline='') as stream:
        samples=list(csv.DictReader(stream))
    assert len(samples)==1 and samples[0]['label']=='initialize' and samples[0]['success']=='true', samples
    return {'planLoaded':True,'groovyCompiled':True,'httpRequests':0,'businessTestExecuted':False}


def command(args, timeout=30, data=None):
    result = subprocess.run([str(arg) for arg in args], input=data, capture_output=True,
                            text=True, encoding='utf-8', errors='replace', timeout=timeout,
                            creationflags=FLAGS)
    if result.returncode:
        raise RuntimeError('Command failed: ' + ' '.join(map(str, args[:5])) + ': ' + result.stderr[-1500:])
    return result.stdout.strip()


def sql(statement):
    return command([DOCKER, 'exec', '-i', 'group-buy-perf-mysql', 'mysql', '-uroot',
                    '-ploadtest-only', '-N', '-B', '--default-character-set=utf8mb4',
                    '-D'+DATABASE], data=statement)


def rows(statement):
    result = sql(statement)
    return [line.split('\t') for line in result.splitlines()] if result else []


def redis(*arguments):
    with socket.create_connection(('127.0.0.1', 36379), timeout=10) as connection:
        parts = [str(arg).encode('utf-8') for arg in arguments]
        connection.sendall(b'*' + str(len(parts)).encode() + b'\r\n' + b''.join(
            b'$' + str(len(part)).encode() + b'\r\n' + part + b'\r\n' for part in parts))
        stream = connection.makefile('rb')
        def read():
            line = stream.readline()
            if not line:
                raise RuntimeError('Redis connection closed')
            kind, content = line[:1], line[1:-2]
            if kind == b'$':
                length = int(content)
                if length == -1:
                    return None
                value = stream.read(length).decode('utf-8')
                stream.read(2)
                return value
            if kind == b'*':
                return [read() for _ in range(int(content))]
            if kind == b':':
                return int(content)
            if kind == b'-':
                raise RuntimeError(content.decode())
            return content.decode()
        return read()


def http(path, body=None, method=None):
    data = None if body is None else json.dumps(body).encode('utf-8')
    request = urllib.request.Request(BASE + path, data=data, method=method,
                                     headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=35) as response:
        return json.load(response)


def fixture(activity, stock=1000000, date=TRAVEL_DATE):
    sql(f"UPDATE sc_sku_activity SET activity_id={activity},update_time=NOW() WHERE goods_id='9890001' AND source='s01' AND channel='c01'; "
        "INSERT INTO group_buy_activity(activity_id,activity_name,discount_id,group_type,take_limit_count,target,valid_time,status,start_time,end_time,tag_id,tag_scope,create_time,update_time) "
        f"VALUES({activity},'LOADTEST-{activity}','25120207',1,10000,2,60,1,NOW()-INTERVAL 1 DAY,NOW()+INTERVAL 7 DAY,NULL,NULL,NOW(),NOW()); "
        f"INSERT INTO ticket_calendar_inventory(activity_id,goods_id,travel_date,available_stock,status) VALUES({activity},'9890001','{date}',{stock},1);")


def order_body(activity, number, team=None, date=TRAVEL_DATE):
    body = {'userId': 'lt-check-' + str(number), 'activityId': activity,
            'goodsId': '9890001', 'source': 's01', 'channel': 'c01',
            'outTradeNo': str(number), 'travelDate': date, 'visitorName': 'Load Test',
            'visitorIdType': 'PASSPORT', 'visitorIdNo': 'P' + str(number),
            'notifyConfigVO': {'notifyType': 'MQ'}}
    if team:
        body['teamId'] = team
    return body


def settle(body):
    return http('/api/v1/group-buy/orders/settlement',
                {key: body[key] for key in ('userId', 'source', 'channel', 'outTradeNo')} |
                {'outTradeTime': int(time.time() * 1000)}, 'PUT')


def expect_success(response):
    if response.get('code') != '0000':
        raise RuntimeError('Business response: ' + json.dumps(response, ensure_ascii=False))
    return response['data']


def consumed_count(teams):
    if not teams:
        return 0
    values = redis('MGET', *['travel:kafka:consumed:team-success:' + team for team in teams])
    return sum(value is not None for value in values)


def verify(activity, expected_orders=None):
    order = list(map(int, rows(f'SELECT COUNT(*),COALESCE(SUM(status=1),0),COUNT(DISTINCT out_trade_no) FROM group_buy_order_list WHERE activity_id={activity};')[0]))
    duplicate = int(sql(f'SELECT COUNT(*) FROM (SELECT user_id,out_trade_no FROM group_buy_order_list WHERE activity_id={activity} GROUP BY user_id,out_trade_no HAVING COUNT(*)>1) duplicates;'))
    teams = rows(f'SELECT team_id,target_count,lock_count,complete_count,status FROM group_buy_order WHERE activity_id={activity};')
    completed = [team[0] for team in teams if int(team[3]) == 2]
    broken = [team for team in teams if int(team[2]) > int(team[1]) or int(team[3]) > int(team[1]) or (int(team[3]) == 2 and int(team[4]) != 1)]
    tasks = list(map(int, rows(f'SELECT COUNT(*),COALESCE(SUM(notify_status=1),0),COALESCE(SUM(notify_status IN (2,3)),0) FROM notify_task WHERE activity_id={activity};')[0]))
    deadline = time.monotonic() + 30
    consumed = consumed_count(completed)
    while (tasks[1] < len(completed) or consumed < len(completed)) and time.monotonic() < deadline:
        time.sleep(0.5)
        tasks = list(map(int, rows(f'SELECT COUNT(*),COALESCE(SUM(notify_status=1),0),COALESCE(SUM(notify_status IN (2,3)),0) FROM notify_task WHERE activity_id={activity};')[0]))
        consumed = consumed_count(completed)
    occupied = int(redis('GET', f'travel:calendar:stock:{activity}:9890001:{TRAVEL_DATE}') or 0)
    check = {'orders':order[0], 'paidOrders':order[1], 'uniqueExternalNumbers':order[2],
             'duplicateOrders':duplicate, 'redisOccupied':occupied, 'teams':len(teams),
             'fullyPaidTeams':len(completed), 'invalidTeams':len(broken), 'invalidTeamExamples':broken[:3],
             'notifyTasks':tasks[0], 'acknowledgedTasks':tasks[1], 'retryOrFailedTasks':tasks[2],
             'consumedTeams':consumed}
    check['passed'] = (duplicate == 0 and occupied == order[0] and len(broken) == 0 and
                       order[0] == order[1] == order[2] and
                       order[0] == len(teams) * 2 and len(completed) == len(teams) and
                       tasks[0] == tasks[1] == consumed == len(teams) and tasks[2] == 0 and
                       (expected_orders is None or order[0] == expected_orders))
    return check


def write_report(report, directory):
    lines=['# Real group-buy transaction load test', '', 'State: '+report['state'], '',
           'Workload: leader order -> leader settlement -> member joins -> member settlement -> Kafka consumer marker.',
           'MySQL orders and Redis occupancy are real. Payment notification is generated by the test; no external payment/ticket provider is called.', '',
           '| Users | Order success/s | Order P95 ms | Settlement success/s | Full team flows/s | HTTP failures | Data gates |',
           '| ---: | ---: | ---: | ---: | ---: | ---: | --- |']
    for run in report['runs']:
        lines.append(f"| {run['users']} | {run['order']['successTps']} | {run['order']['p95Ms']} | {run['settlement']['successTps']} | {run['fullFlow']['successTps']} | {run['allHttpFailures']} | {run['verification']['passed']} |")
    lines.extend(['', 'HTTP success requires code=0000 and matching business identifiers. Rates count only each named operation; HTTP child and transaction samples are never added together.',
                  'Rates and percentiles exclude warm-up; database reconciliation includes all successful orders, including warm-up. In-flight trade flows finish before the load stops.',
                  'Performance percentiles use successful operation samples. Zero business errors and all data gates are required for a passing run.',
                  'This is a baseline on the tested build. It supplies no before/after improvement percentage.'])
    if report.get('error'):
        lines.extend(['', 'Error: '+report['error']])
    (directory/'REPORT.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')


def merge_application_options(base, overrides):
    """Replace defaults; Spring combines duplicate CLI values (e.g. '20,50') instead of overriding."""
    keys=[str(value).split('=',1)[0] for value in overrides]
    assert all(str(value).startswith('--') and '=' in str(value) for value in overrides)
    assert len(keys)==len(set(keys)), 'Duplicate application overrides'
    return [value for value in base if str(value).split('=',1)[0] not in keys]+list(overrides)


def start_app(directory, jar=None):
    global app
    with socket.socket() as check:
        if check.connect_ex(('127.0.0.1', 38091)) == 0:
            raise RuntimeError('Test port 38091 already owned')
    args = [JAVA, '-Xms64m', '-Xmx320m', '-XX:ActiveProcessorCount=2', '-Duser.timezone=Asia/Shanghai',
            '-jar', jar or ROOT/'cultural-tourism-group-buy-app/target/cultural-tourism-group-buy-app.jar',
            '--spring.profiles.active=dev', '--server.address=127.0.0.1', '--server.port=38091',
            f'--spring.datasource.url=jdbc:mysql://127.0.0.1:33306/{DATABASE}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false',
            '--spring.datasource.username=root', '--spring.datasource.password=loadtest-only',
            '--spring.datasource.hikari.maximum-pool-size=10', '--spring.datasource.hikari.minimum-idle=2',
            '--spring.datasource.hikari.connection-timeout=5000',
            '--redis.sdk.config.host=127.0.0.1', '--redis.sdk.config.port=36379',
            '--spring.kafka.bootstrap-servers=127.0.0.1:39092',
            '--spring.kafka.topics.team-success='+TEAM_SUCCESS_TOPIC,
            '--spring.kafka.topics.team-refund=travel.loadtest.team-refund',
            '--server.tomcat.max-connections=200', '--server.tomcat.threads.max=20',
            '--server.tomcat.accept-count=100', '--logging.level.root=WARN', '--logging.config=' + (HERE/'logback-loadtest.xml').as_uri()]
    args = merge_application_options(args, APP_EXTRA_ARGUMENTS)
    log = (directory/'application.log').open('w', encoding='utf-8')
    app = subprocess.Popen(list(map(str, args)), cwd=directory, stdout=log, stderr=subprocess.STDOUT, creationflags=FLAGS)
    (directory/'application-arguments.json').write_text(json.dumps(list(map(str,args)),indent=2),encoding='utf-8')
    deadline=time.monotonic()+90
    while time.monotonic()<deadline:
        if app.poll() is not None:
            raise RuntimeError('Application startup failed; see ' + str(directory/'application.log'))
        try:
            http('/api/v1/group-buy/activities/quote')
            return
        except urllib.error.HTTPError as error:
            if error.code in (400,404,405):
                return
        except Exception:
            pass
        time.sleep(0.5)
    raise RuntimeError('Application startup timeout')


def stop_app():
    global app
    if app and app.poll() is None:
        app.terminate()
        try:
            app.wait(timeout=30)
        except subprocess.TimeoutExpired:
            app.kill()
            app.wait(timeout=10)
    app=None



def observe_hikari():
    result = {}
    for short, name in (('active', 'hikaricp.connections.active'),
                        ('pending', 'hikaricp.connections.pending'),
                        ('acquire', 'hikaricp.connections.acquire')):
        try:
            with urllib.request.urlopen(BASE+'/actuator/metrics/'+name, timeout=0.3) as response:
                metric = json.load(response)
            result[short] = {item['statistic']: item['value'] for item in metric['measurements']}
        except Exception as error:
            result[short] = {'unavailable': type(error).__name__}
    return result


def percentile(values, fraction):
    ordered=sorted(values)
    return ordered[max(0,math.ceil(len(ordered)*fraction)-1)] if ordered else None


def jmeter_run(directory, activity, users, duration, warmup, serial):
    global load
    directory.mkdir()
    arguments=[JAVA,'-Xms32m','-Xmx128m','-XX:ActiveProcessorCount=2','-Duser.timezone=Asia/Shanghai',
               '-jar',JMETER,'-n','-t',HERE/'real-trade.jmx','-q',HERE/'jmeter.properties',
               '-l',directory/'samples.jtl','-j',directory/'jmeter.log',
               '-Jperf.dir='+HERE.as_posix(),'-Jout.dir='+directory.as_posix(),
               '-Jport=38091','-Jthreads='+str(users),'-Jrampup=5','-Jduration='+str(duration),
               '-Jloops=-1','-Jactivity.id='+str(activity),'-Jtravel.date='+TRAVEL_DATE,
               '-Jorder.base='+str(100000000000+serial*10000000),'-Jrun.id='+str(activity)]
    (directory/'arguments.json').write_text(json.dumps(list(map(str,arguments)),indent=2),encoding='utf-8')
    with (directory/'console.log').open('w',encoding='utf-8') as log:
        load=subprocess.Popen(list(map(str,arguments)),cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,creationflags=FLAGS)
        deadline=time.monotonic()+duration+90
        low=0
        with (directory/'resources.jsonl').open('w',encoding='utf-8') as resource_log:
            while load.poll() is None:
                resource={'time':datetime.now().astimezone().isoformat(),
                          'availableMiB':round(psutil.virtual_memory().available/1048576,1),
                          'cpuPercent':psutil.cpu_percent(interval=1)}
                for name,process in (('application',app),('jmeter',load)):
                    try:
                        observed=psutil.Process(process.pid)
                        resource[name]={'rssMiB':observed.memory_info().rss/1048576,
                                        'cpuTimes':sum(observed.cpu_times()[:2])}
                    except psutil.Error:
                        pass
                if OBSERVE_METRICS:
                    resource['hikari'] = observe_hikari()
                resource_log.write(json.dumps(resource)+'\n')
                resource_log.flush()
                low=low+1 if resource['availableMiB']<512 else 0
                if low>=3 or time.monotonic()>deadline:
                    raise RuntimeError('JMeter stopped by memory/time guard')
                time.sleep(4)
        if load.returncode:
            raise RuntimeError('JMeter exit '+str(load.returncode))
        load=None
    with (directory/'samples.jtl').open(encoding='utf-8-sig',newline='') as stream:
        samples=list(csv.DictReader(stream))
    timing=json.loads((directory/'timing.json').read_text())
    start=timing['startMs']+warmup*1000
    end=timing['deadlineMs']
    measured=[sample for sample in samples if start<=int(sample['timeStamp'])<end]
    def stats(labels):
        selected=[sample for sample in measured if sample['label'] in labels]
        successes=[sample for sample in selected if sample['success']=='true']
        latencies=[int(sample['elapsed']) for sample in successes]
        return {'samples':len(selected),'successes':len(successes),'failures':len(selected)-len(successes),
                'successTps':round(len(successes)/(duration-warmup),3),
                'p95Ms':percentile(latencies,.95),'p99Ms':percentile(latencies,.99),
                'meanMs':round(statistics.mean(latencies),3) if latencies else None}
    all_orders=[sample for sample in samples if sample['label'] in ('order-leader','order-member')]
    counts=verify(activity, sum(sample['success']=='true' for sample in all_orders))
    resources=[json.loads(line) for line in (directory/'resources.jsonl').read_text().splitlines()]
    result={'activityId':activity,'users':users,'durationSeconds':duration,'warmupSeconds':warmup,
            'order':stats(('order-leader','order-member')),'settlement':stats(('settle-leader','settle-member')),
            'fullFlow':stats(('trade-flow',)), 'allHttpFailures':sum(sample['success']!='true' for sample in samples if sample['label'] not in ('trade-flow','initialize')),
            'allOrderSuccesses':sum(sample['success']=='true' for sample in all_orders),
            'verification':counts,'cpuMean':round(statistics.mean(item['cpuPercent'] for item in resources),2),
            'minAvailableMiB':min(item['availableMiB'] for item in resources)}
    result['passed']=(counts['passed'] and result['allHttpFailures']==0 and
                      result['order']['successes']>0 and result['settlement']['successes']>0 and
                      result['fullFlow']['successes']>0)
    (directory/'summary.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    return result


def smoke(directory, activity):
    fixture(activity,100)
    leader=order_body(activity,900000000000+activity%1000000*2)
    member=order_body(activity,int(leader['outTradeNo'])+1)
    responses=[]
    responses.append(http('/api/v1/group-buy/orders',leader))
    expect_success(responses[-1])
    responses.append(settle(leader))
    team=expect_success(responses[-1])['teamId']
    member['teamId']=team
    responses.append(http('/api/v1/group-buy/orders',member))
    expect_success(responses[-1])
    responses.append(settle(member))
    expect_success(responses[-1])
    check=verify(activity,2)
    (directory/'smoke.json').write_text(json.dumps({'responses':responses,'verification':check},ensure_ascii=False,indent=2),encoding='utf-8')
    print('Smoke: '+json.dumps(check,ensure_ascii=False),flush=True)
    return check


def main():
    global load
    parser=argparse.ArgumentParser()
    parser.add_argument('--smoke-only',action='store_true')
    parser.add_argument('--prepare-only',action='store_true')
    parser.add_argument('--validate-only',action='store_true')
    parser.add_argument('--keep-containers',action='store_true')
    parser.add_argument('--users',nargs='+',type=int,default=[10,25,50,50,50])
    parser.add_argument('--duration',type=int,default=60)
    parser.add_argument('--warmup',type=int,default=15)
    parser.add_argument('--min-startup-mib',type=int,default=2048)
    args=parser.parse_args()
    if args.duration <= args.warmup or args.warmup < 5:
        parser.error('Require duration > warmup >= ramp-up (5 seconds)')
    manifest=prepare_bootstrap()
    if args.prepare_only:
        print(json.dumps({'prepared':True,'bootstrap':manifest,'businessTestExecuted':False}),flush=True)
        return
    directory=HERE/'results'/('real-trade-'+datetime.now().strftime('%Y%m%d-%H%M%S'))
    directory.mkdir(parents=True)
    report={'state':'running','directory':str(directory),'startedAt':datetime.now().astimezone().isoformat(),'runs':[]}
    print('Results: '+str(directory),flush=True)
    (HERE/'results/latest.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    try:
        if args.validate_only:
            require_memory(512)
            report['validation']=validate_plan(directory)
            report['state']='validated-without-business-requests'
            return
        require_memory(args.min_startup_mib)
        report['bootstrap']=manifest
        report['validation']=validate_plan(directory)
        jar=ROOT/'cultural-tourism-group-buy-app/target/cultural-tourism-group-buy-app.jar'
        main_sources=[path for module in ROOT.glob('cultural-tourism-group-buy-*')
                      for path in (module/'src/main').rglob('*') if path.is_file()]
        if any(path.stat().st_mtime > jar.stat().st_mtime for path in main_sources):
            raise RuntimeError('Business source is newer than the jar; run mvn -DskipTests package first')
        report['applicationJarSha256']=hashlib.sha256(jar.read_bytes()).hexdigest()
        report['gitHead']=command(['git','-C',ROOT,'rev-parse','HEAD'])
        report['sourceHashes']={path.relative_to(ROOT).as_posix():hashlib.sha256(path.read_bytes()).hexdigest()
                                for path in sorted(main_sources)}
        # All mutated data lives in these three dedicated containers.
        command([COMPOSE,'-p','group-buy-perf','-f',HERE/'compose.loadtest.yml','up','-d','--wait'],timeout=300)
        command([DOCKER,'exec','-e','KAFKA_HEAP_OPTS=-Xms16m -Xmx64m','group-buy-perf-kafka',
                 '/opt/kafka/bin/kafka-topics.sh','--bootstrap-server','kafka:19092','--create','--if-not-exists',
                 '--topic','travel.loadtest.team-success','--partitions','1','--replication-factor','1'],timeout=45)
        activity=400000000+int(datetime.now().strftime('%H%M%S'))*10
        start_app(directory)
        initial=smoke(directory,activity)
        report['smoke']=initial
        if not initial['passed']:
            raise RuntimeError('Real transaction smoke failed data consistency checks')
        if not args.smoke_only:
            from check_consistency import run_checks
            report['correctness']=run_checks(directory,activity+1)
            activity+=10
            if not report['correctness']['passed']:
                raise RuntimeError('Transaction correctness scenarios failed; throughput measurement was not started')
            for index,users in enumerate(args.users,1):
                activity+=1
                fixture(activity)
                print('Running '+str(users)+' users, round '+str(index),flush=True)
                result=jmeter_run(directory/('u'+str(users)+'-r'+str(index)),activity,users,args.duration,args.warmup,index)
                report['runs'].append(result)
                (directory/'status.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
                print(json.dumps(result,ensure_ascii=False),flush=True)
                if not result['passed']:
                    raise RuntimeError('Run failed business/data gates; inspect summary.json')
        report['state']='completed'
    except Exception as failure:
        report['state']='failed'
        report['error']=repr(failure)
        print('Failed: '+repr(failure),flush=True)
        raise
    finally:
        if load and load.poll() is None:
            with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as control:
                control.sendto(b'Shutdown',('127.0.0.1',4545))
            try:
                load.wait(timeout=35)
            except subprocess.TimeoutExpired:
                load.terminate()
                load.wait(timeout=10)
        stop_app()
        if not args.keep_containers and not args.validate_only:
            try:
                existing=command([DOCKER,'ps','-a','--filter','label=com.docker.compose.project=group-buy-perf','--format','{{.Names}}']).splitlines()
                owned=[name for name in existing if name in ('group-buy-perf-mysql','group-buy-perf-redis','group-buy-perf-kafka')]
                if owned:
                    command([DOCKER,'stop',*owned],timeout=50)
                report['containersStopped']=True
            except Exception as cleanup_failure:
                report['cleanupWarning']=repr(cleanup_failure)
        report['finishedAt']=datetime.now().astimezone().isoformat()
        (directory/'status.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
        (HERE/'results/latest.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
        write_report(report,directory)
        print('Final state: '+report['state'],flush=True)


if __name__=='__main__':
    main()
