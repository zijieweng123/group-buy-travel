import csv
import json
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path
import request_latency as latency
import compare_request_latency
import compare_real_trade as comparison

class RequestLatencyTest(unittest.TestCase):
    def test_same_build_and_bounded_capacity_changes(self):
        with tempfile.TemporaryDirectory(dir=comparison.rt.HERE/'results') as target:
            manifest=compare_request_latency.prepare(Path(target))
            self.assertEqual(manifest['baselineJar'],manifest['optimizedJar'])
            self.assertTrue(manifest['indexesForBoth'])
            baseline=manifest['versionArguments']['baseline']; optimized=manifest['versionArguments']['optimized']
            self.assertEqual(baseline[:-1],optimized[:2])
            self.assertIn('--trade.notify.batch.enabled=false',baseline)
            self.assertEqual('--spring.datasource.hikari.data-source-properties.useLocalSessionState=false',baseline[-1])
            self.assertEqual('--spring.datasource.hikari.data-source-properties.useLocalSessionState=true',optimized[2])
            self.assertEqual(['--spring.datasource.hikari.maximum-pool-size=40','--server.tomcat.threads.max=50'],optimized[3:])

    def test_application_overrides_do_not_create_comma_separated_integers(self):
        base=['java','-Xmx320m','--server.tomcat.threads.max=20','--spring.datasource.hikari.maximum-pool-size=10']
        extra=['--server.tomcat.threads.max=50','--spring.datasource.hikari.maximum-pool-size=40']
        merged=comparison.rt.merge_application_options(base,extra)
        self.assertEqual(['java','-Xmx320m']+extra,merged)
        with self.assertRaises(AssertionError): comparison.rt.merge_application_options(base,extra+extra)

    def test_live_pool_mismatch_blocks_the_phase(self):
        with tempfile.TemporaryDirectory() as target, \
                patch.object(comparison.rt,'require_memory'), \
                patch.object(comparison,'restore_seed'), \
                patch.object(comparison.rt,'command'), \
                patch.object(comparison.rt,'start_app'), \
                patch.object(comparison.rt,'smoke',return_value={'passed':True}), \
                patch.object(comparison.rt,'http',return_value={'measurements':[{'statistic':'VALUE','value':10}]}):
            with self.assertRaisesRegex(AssertionError,'Unexpected live Hikari capacity'):
                comparison.start_phase(Path(target),'optimized',Path('app.jar'),1,'test-topic',True,40)

    def fixture(self,directory,headers=True):
        (directory/'timing.json').write_text(json.dumps({'startMs':1000,'deadlineMs':61000}))
        fields=['timeStamp','elapsed','label','success','serverTiming']
        with (directory/'samples.jtl').open('w',newline='') as stream:
            writer=csv.DictWriter(stream,fieldnames=fields);writer.writeheader()
            for label in ('order-leader','order-member','settle-leader','settle-member'):
                for stamp in (15999,16000):
                    writer.writerow(dict(timeStamp=stamp,elapsed=100,label=label,success='true',
                        serverTiming='app;dur=70.000|pool;dur=10.500;desc="2"|sql;dur=20.000;desc="3"' if headers else ''))
    def test_per_http_header_correlation_and_warmup_exclusion(self):
        with tempfile.TemporaryDirectory() as target:
            directory=Path(target);self.fixture(directory)
            report=latency.analyze(directory)
            self.assertEqual(2,report['order']['all']['samples'])
            self.assertEqual(30,report['order']['all']['outsideAppMeanMs'])
            self.assertEqual(10.5,report['order']['all']['stageMeanMs']['pool'])
            self.assertEqual(3,report['settle']['all']['stageCountMean']['sql'])
    def test_missing_headers_cannot_be_reported_as_zero_time(self):
        with tempfile.TemporaryDirectory() as target:
            directory=Path(target);self.fixture(directory,False)
            with self.assertRaises(AssertionError): latency.analyze(directory)

if __name__=='__main__': unittest.main()
