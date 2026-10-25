import tempfile
import unittest
from pathlib import Path
import compare_trade_qps as qps
import compare_real_trade as comparison

class TradeQpsGuardTest(unittest.TestCase):
    def test_query_comparison_keeps_capacity_and_artifact_identical(self):
        with tempfile.TemporaryDirectory(dir=comparison.rt.HERE/'results') as target:
            manifest=qps.prepare(Path(target))
            self.assertEqual(manifest['baselineJar'],manifest['optimizedJar'])
            self.assertEqual({'baseline':40,'optimized':40},manifest['expectedPool'])
            baseline=manifest['versionArguments']['baseline'];optimized=manifest['versionArguments']['optimized']
            self.assertEqual(baseline[:4],optimized[:4])
            self.assertIn('--trade.read-snapshots.enabled=false',baseline)
            self.assertIn('--trade.read-snapshots.enabled=true',optimized)
            self.assertIn('--trade.notify.batch.enabled=false',baseline)
            self.assertIn('--trade.notify.batch.enabled=false',optimized)
            self.assertEqual(['baseline','optimized','optimized','baseline','baseline','optimized'],manifest['order'])
            self.assertTrue(manifest['observeReadSnapshots'])
            self.assertTrue(Path(manifest['scriptArchive']).exists())
    def test_atomic_reservation_is_only_enabled_with_fresh_read_snapshot(self):
        with tempfile.TemporaryDirectory(dir=comparison.rt.HERE/'results') as target:
            manifest=qps.prepare(Path(target),False,False,True)
            baseline=manifest['versionArguments']['baseline'];optimized=manifest['versionArguments']['optimized']
            self.assertIn('--trade.inventory.reservation.enabled=false',baseline)
            self.assertIn('--trade.inventory.reservation.enabled=true',optimized)
            self.assertIn('--trade.read-snapshots.enabled=true',optimized)
            self.assertIn('--trade.notify.batch.enabled=false',optimized)

    def test_prepared_statement_validation_uses_three_optimized_runs_and_bounded_caches(self):
        with tempfile.TemporaryDirectory(dir=comparison.rt.HERE/'results') as target:
            manifest=qps.prepare(Path(target),False,False,True,True,True)
            self.assertEqual(['optimized']*3,manifest['order'])
            self.assertTrue(manifest['verificationOnly'])
            self.assertEqual({'baseline':False,'optimized':True},manifest['preparedStatements'])
            args=manifest['versionArguments']['optimized']
            self.assertIn('--spring.datasource.hikari.data-source-properties.useServerPrepStmts=true',args)
            self.assertIn('--spring.datasource.hikari.data-source-properties.cachePrepStmts=true',args)
            self.assertIn('--spring.datasource.hikari.data-source-properties.prepStmtCacheSize=64',args)
            self.assertIn('--spring.datasource.hikari.data-source-properties.prepStmtCacheSqlLimit=2048',args)

    def test_pool_50_is_explicit_and_both_groups_use_identical_capacity(self):
        with tempfile.TemporaryDirectory(dir=comparison.rt.HERE/'results') as target:
            manifest=qps.prepare(Path(target),False,False,True,False,True,50)
            self.assertEqual({'baseline':50,'optimized':50},manifest['expectedPool'])
            for args in manifest['versionArguments'].values():
                self.assertIn('--spring.datasource.hikari.maximum-pool-size=50',args)
            with self.assertRaises(AssertionError):qps.prepare(Path(target),pool_size=60)

    def test_batch_is_an_explicit_optimized_group_toggle(self):
        with tempfile.TemporaryDirectory(dir=comparison.rt.HERE/'results') as target:
            manifest=qps.prepare(Path(target),True,True)
            self.assertEqual(['optimized'],manifest['order'])
            self.assertEqual(['optimized'],manifest['preflightVersions'])
            self.assertIn('--trade.notify.batch.enabled=false',manifest['versionArguments']['baseline'])
            self.assertIn('--trade.notify.batch.enabled=true',manifest['versionArguments']['optimized'])

if __name__=='__main__': unittest.main()
