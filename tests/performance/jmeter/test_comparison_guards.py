"""Preparation safety tests; no live Docker, database or business HTTP."""
import re
import tempfile
import compare_notify_batch
import unittest
from pathlib import Path
from unittest.mock import patch
import compare_real_trade as compare

class ComparisonGuardTest(unittest.TestCase):
    def test_wrong_container_owner_blocks_all_data_mutations(self):
        with patch.object(compare.rt, "command", return_value="another-project") as command, \
                patch.object(compare.rt, "redis") as redis, \
                patch.object(compare.rt, "sql") as sql:
            with self.assertRaises(AssertionError):
                compare.restore_seed(Path("."), "optimized")
            redis.assert_not_called()
            sql.assert_not_called()
            self.assertEqual(1, command.call_count)
            self.assertIn("inspect", command.call_args.args[0])

    def test_seed_targets_only_fixed_comparison_database(self):
        text = compare.comparison_seed()
        targets = re.findall(r"(?im)^\s*USE\s+\x60?([a-zA-Z0-9_]+)", text)
        self.assertTrue(targets)
        self.assertEqual({"cultural_tourism_group_buy_compare"}, set(targets))
        self.assertIsNone(re.search(r"\bDROP\s+DATABASE\b", text, re.I))

    def test_batch_comparison_uses_identical_build_and_indexes(self):
        with tempfile.TemporaryDirectory(dir=compare.rt.HERE/'results') as directory:
            manifest=compare_notify_batch.prepare(Path(directory))
            self.assertEqual(manifest['baselineJar'],manifest['optimizedJar'])
            self.assertEqual(manifest['baselineSha256'],manifest['optimizedSha256'])
            self.assertTrue(manifest['indexesForBoth'])
            self.assertEqual({'baseline':['--trade.notify.batch.enabled=false'],
                              'optimized':['--trade.notify.batch.enabled=true']},manifest['versionArguments'])

    def test_each_phase_recreates_calendar_inventory_in_comparison_namespace(self):
        seed=compare.comparison_seed()
        self.assertIn('DROP TABLE IF EXISTS ticket_calendar_inventory;',seed)
        self.assertLess(seed.index('DROP TABLE IF EXISTS ticket_calendar_inventory;'),
                        seed.index('CREATE TABLE IF NOT EXISTS ticket_calendar_inventory'))

    def test_low_memory_blocks_processes_before_run_setup(self):
        with patch.object(compare.rt, "require_memory", side_effect=RuntimeError("Insufficient memory")), \
                patch.object(compare.rt, "command") as command, \
                patch.object(compare.rt, "start_app") as start_app:
            with self.assertRaises(RuntimeError):
                compare.run(compare.rt.HERE/"results/memory-guard-no-files", {})
            command.assert_not_called()
            start_app.assert_not_called()

if __name__ == "__main__":
    unittest.main()
