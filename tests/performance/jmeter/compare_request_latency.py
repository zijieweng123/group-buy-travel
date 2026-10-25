"""Same-build request-latency diagnosis and bounded capacity comparison; durability is unchanged."""
import argparse
import json
import hashlib
import zipfile
from datetime import datetime
from pathlib import Path
import compare_real_trade as comparison
import compare_notify_batch

def prepare(folder,diagnosis_only=False,optimized_pilot=False,batch_notify=False):
    manifest=compare_notify_batch.prepare(folder)
    common=['--trade.notify.batch.enabled=false','--trade.trace.enabled=true']
    manifest.update(expectedPool={'baseline':10,'optimized':40},comparisonKind='same-build-trade-capacity-and-session-state',observeRequestTimings=True,
                    diagnosisOnly=diagnosis_only,preflightVersions=['baseline'] if diagnosis_only else ['baseline','optimized'],
                    versionArguments={'baseline':common+['--spring.datasource.hikari.data-source-properties.useLocalSessionState=false'],
                                      'optimized':common+['--spring.datasource.hikari.data-source-properties.useLocalSessionState=true',
                                      '--spring.datasource.hikari.maximum-pool-size=40','--server.tomcat.threads.max=50']})
    if batch_notify: manifest['versionArguments']['optimized'][0]='--trade.notify.batch.enabled=true'
    if diagnosis_only: manifest['order']=['baseline']
    if optimized_pilot:
        manifest.update(diagnosisOnly=True,preflightVersions=['optimized'],order=['optimized'])
    scripts=[p for p in comparison.rt.HERE.iterdir() if p.suffix in ('.py','.jmx','.properties','.yml','.xml')]
    scripts+=list((comparison.rt.HERE/'scripts').glob('*.groovy'))
    manifest['scriptHashes']={p.relative_to(comparison.rt.ROOT).as_posix():comparison.digest(p) for p in scripts}
    script_id=hashlib.sha256(json.dumps(manifest['scriptHashes'],sort_keys=True).encode()).hexdigest()[:12]
    script_archive=folder/('performance-scripts-'+script_id+'.zip')
    if not script_archive.exists():
        with zipfile.ZipFile(script_archive,'w',zipfile.ZIP_DEFLATED) as archive:
            for path in scripts: archive.write(path,path.relative_to(comparison.rt.ROOT))
    manifest['scriptArchive']=str(script_archive)
    comparison.save(folder/'prepared.json',manifest)
    return manifest

def main():
    parser=argparse.ArgumentParser()
    pilot=parser.add_mutually_exclusive_group()
    pilot.add_argument('--diagnose-only',action='store_true')
    pilot.add_argument('--optimized-pilot',action='store_true')
    parser.add_argument('--batch-notify',action='store_true')
    parser.add_argument('--prepare-only',action='store_true')
    parser.add_argument('--comparison-dir',type=Path)
    args=parser.parse_args()
    folder=args.comparison_dir or comparison.rt.HERE/'results'/('request-latency-'+datetime.now().strftime('%Y%m%d-%H%M%S'))
    folder.mkdir(parents=True,exist_ok=True)
    comparison.save(comparison.rt.HERE/'results/request-latency-latest.json',{'directory':str(folder)})
    manifest=prepare(folder,args.diagnose_only,args.optimized_pilot,args.batch_notify)
    if args.prepare_only: print(json.dumps(manifest),flush=True)
    else: comparison.run(folder,manifest)

if __name__=='__main__': main()
