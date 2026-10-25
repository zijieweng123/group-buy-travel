"""Same-build real-trade comparison: only notification receipt batching changes."""
import argparse
import json
import shutil
import zipfile
from datetime import datetime
from pathlib import Path
import compare_real_trade as comparison


def prepare(folder):
    assert folder.resolve().is_relative_to((comparison.rt.HERE/'results').resolve())
    folder.mkdir(exist_ok=True)
    jar = comparison.rt.ROOT/'cultural-tourism-group-buy-app/target/cultural-tourism-group-buy-app.jar'
    files = comparison.source_files()
    assert all(p.stat().st_mtime <= jar.stat().st_mtime for p in files), 'Build latest sources first'
    sha = comparison.digest(jar)
    artifact = folder/('same-build-'+sha[:12]+'.jar')
    if not artifact.exists(): shutil.copy2(jar,artifact)
    assert comparison.digest(artifact)==sha
    archive = folder/('sources-'+sha[:12]+'.zip')
    if not archive.exists():
        with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as output:
            for path in files: output.write(path,path.relative_to(comparison.rt.ROOT))
            output.write(comparison.INDEX_SQL,comparison.INDEX_SQL.relative_to(comparison.rt.ROOT))
    seed = folder/'comparison-bootstrap.sql'
    seed.write_text(comparison.comparison_seed(),encoding='utf-8')
    manifest = {'comparisonKind':'same-build-notification-batch-toggle','directory':str(folder),
                'baselineJar':str(artifact),'optimizedJar':str(artifact),
                'baselineSha256':sha,'optimizedSha256':sha,
                'sourceHashes':{p.relative_to(comparison.rt.ROOT).as_posix():comparison.digest(p) for p in files},
                'indexSqlSha256':comparison.digest(comparison.INDEX_SQL),'seedSha256':comparison.digest(seed),
                'database':comparison.COMPARE_DB,'indexesForBoth':True,'observeTradeMetrics':True,
                'versionArguments':{'baseline':['--trade.notify.batch.enabled=false'],
                                    'optimized':['--trade.notify.batch.enabled=true']},
                'order':['baseline','optimized','optimized','baseline','baseline','optimized']}
    comparison.save(folder/'prepared.json',manifest)
    return manifest


def main():
    parser=argparse.ArgumentParser()
    actions=parser.add_mutually_exclusive_group()
    actions.add_argument('--prepare-only',action='store_true')
    actions.add_argument('--validate-only',action='store_true')
    actions.add_argument('--run',action='store_true')
    parser.add_argument('--comparison-dir',type=Path)
    args=parser.parse_args()
    pointer=comparison.rt.HERE/'results/notification-batch-latest.json'
    if args.comparison_dir:
        folder=args.comparison_dir
    elif pointer.exists():
        folder=Path(json.loads(pointer.read_text(encoding='utf-8'))['directory'])
    else:
        folder=comparison.rt.HERE/'results'/('notification-batch-'+datetime.now().strftime('%Y%m%d-%H%M%S'))
        folder.mkdir(parents=True)
        comparison.save(pointer,{'directory':str(folder)})
    manifest=prepare(folder)
    if args.validate_only:
        comparison.rt.require_memory(512)
        target=folder/('validation-'+datetime.now().strftime('%Y%m%d-%H%M%S'))
        target.mkdir()
        comparison.save(target/'validation.json',comparison.rt.validate_plan(target))
        print('Plan validated; zero business HTTP requests',flush=True)
    elif args.run:
        comparison.run(folder,manifest)
    else:
        print(json.dumps({'prepared':True,'directory':str(folder),'sha256':manifest['baselineSha256'],
                          'sameBuild':True,'indexesForBoth':True}),flush=True)


if __name__=='__main__':
    main()
