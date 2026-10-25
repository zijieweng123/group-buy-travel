"""100+ successful-order QPS target: same-build fresh SQL reads at fixed HTTP/JDBC capacity."""
import argparse
import json
from pathlib import Path
from datetime import datetime
import compare_real_trade as comparison
import compare_request_latency

def prepare(folder,pilot=False,batch_notify=False,atomic_reservation=False,prepared_statements=False,verify_optimized=False,pool_size=40,notify_threads=4):
    assert notify_threads in (4,8,12)
    assert pool_size in (40,50), 'Pool cap must leave headroom under the dedicated MySQL max_connections=60'
    manifest=compare_request_latency.prepare(folder)
    common=['--trade.trace.enabled=true','--spring.datasource.hikari.maximum-pool-size='+str(pool_size),
            '--server.tomcat.threads.max=50','--spring.datasource.hikari.data-source-properties.useLocalSessionState=true',
            '--spring.profiles.active=dev,performance','--trade.notify-threads='+str(notify_threads)]
    manifest.update(comparisonKind='same-build-fresh-trade-read-consolidation',
                    expectedPool={'baseline':pool_size,'optimized':pool_size},expectedNotifyThreads={'baseline':notify_threads,'optimized':notify_threads},observeReadSnapshots=True,
                    versionArguments={
                        'baseline':common+['--trade.inventory.reservation.enabled=false','--trade.read-snapshots.enabled=false','--trade.notify.batch.enabled=false'],
                        'optimized':common+['--trade.inventory.reservation.enabled='+str(atomic_reservation).lower(),'--trade.read-snapshots.enabled=true',
                                            '--trade.notify.batch.enabled='+str(batch_notify).lower()]})
    for version,enabled in [('baseline',False),('optimized',prepared_statements)]:
        options={'useServerPrepStmts':enabled,'cachePrepStmts':enabled,'cacheResultSetMetadata':enabled,
                 'prepStmtCacheSize':64,'prepStmtCacheSqlLimit':2048,'metadataCacheSize':64}
        manifest['versionArguments'][version]+=['--spring.datasource.hikari.data-source-properties.'+key+'='+
                (str(value).lower() if isinstance(value,bool) else str(value)) for key,value in options.items()]
    manifest['preparedStatements']={'baseline':False,'optimized':prepared_statements}
    if pilot: manifest.update(diagnosisOnly=True,preflightVersions=['optimized'],order=['optimized'])
    if verify_optimized:
        manifest.update(diagnosisOnly=True,verificationOnly=True,preflightVersions=['optimized'],order=['optimized']*3)
    comparison.save(folder/'prepared.json',manifest)
    return manifest

def main():
    parser=argparse.ArgumentParser()
    mode=parser.add_mutually_exclusive_group()
    mode.add_argument('--pilot',action='store_true')
    mode.add_argument('--verify-optimized',action='store_true')
    parser.add_argument('--batch-notify',action='store_true')
    parser.add_argument('--atomic-reservation',action='store_true')
    parser.add_argument('--prepared-statements',action='store_true')
    parser.add_argument('--pool-size',type=int,choices=(40,50),default=40)
    parser.add_argument('--notify-threads',type=int,choices=(4,8,12),default=4)
    parser.add_argument('--prepare-only',action='store_true')
    parser.add_argument('--comparison-dir',type=Path)
    args=parser.parse_args()
    pointer=comparison.rt.HERE/'results/qps100-latest.json'
    if args.comparison_dir: folder=args.comparison_dir
    elif pointer.exists(): folder=Path(json.loads(pointer.read_text())['directory'])
    else:
        folder=comparison.rt.HERE/'results'/('qps100-'+datetime.now().strftime('%Y%m%d-%H%M%S'))
        folder.mkdir(parents=True)
        comparison.save(pointer,{'directory':str(folder)})
    manifest=prepare(folder,args.pilot,args.batch_notify,args.atomic_reservation,args.prepared_statements,args.verify_optimized,args.pool_size,args.notify_threads)
    if args.prepare_only: print(json.dumps({'prepared':True,'directory':str(folder),'sha256':manifest['baselineSha256']}),flush=True)
    else: comparison.run(folder,manifest)

if __name__=='__main__':main()
