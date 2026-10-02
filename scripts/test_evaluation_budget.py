import json
import tempfile
import unittest
from pathlib import Path

try:
    from scripts.evaluation_budget import BudgetLedger, BudgetStop
except ImportError:
    BudgetLedger = BudgetStop = None


def usage(requests=1, tokens=10, complete=True):
    return dict(logicalModelRequests=requests, promptTokens=tokens, completionTokens=0,
                totalTokens=tokens, usageComplete=complete,
                unknownUsageRequests=0 if complete else requests)


class BudgetTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(BudgetLedger, 'Task10 durable budget implementation is missing')
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'shared.json'
        self.ledger = BudgetLedger(self.path)

    def test_reservation_is_durable_before_dispatch_and_capped_at_twelve(self):
        self.assertEqual(12, self.ledger.reserve('pilot.one', 99)['requestAllowance'])
        reopened = BudgetLedger(self.path).snapshot()
        self.assertEqual(12, reopened['chargedRequests'])
        self.assertEqual(296, reopened['plannedTrials'])
        self.assertEqual(320, reopened['maxTrials'])
        self.assertEqual(1200, reopened['maxRequests'])
        self.assertEqual(2000000, reopened['maxReportedTokens'])

    def test_only_known_usage_and_verified_termination_release_unused(self):
        self.ledger.reserve('pilot.one')
        self.ledger.complete('pilot.one', usage(), terminated=False)
        self.assertEqual(12, BudgetLedger(self.path).snapshot()['chargedRequests'])
        with self.assertRaises(ValueError):
            self.ledger.reserve('pilot.one')
        self.ledger.reserve('formal.one')
        self.ledger.complete('formal.one', usage(2, 20), terminated=True)
        self.assertEqual(14, self.ledger.snapshot()['chargedRequests'])
        self.assertEqual(30, self.ledger.snapshot()['reportedTokens'])

    def test_unknown_usage_keeps_reserve_and_is_never_zero_filled(self):
        self.ledger.reserve('pilot.unknown')
        self.ledger.complete('pilot.unknown', usage(1, None, False), terminated=True)
        self.assertEqual(12, self.ledger.snapshot()['chargedRequests'])
        self.assertIsNone(json.loads(self.path.read_text())['trials']['pilot.unknown']['usage']['totalTokens'])

    def test_pilot_formal_supplement_share_request_ceiling(self):
        for i in range(99):
            self.ledger.reserve('pilot.' + str(i))
        self.assertEqual(12, self.ledger.reserve('formal.one')['requestAllowance'])
        with self.assertRaises(BudgetStop):
            BudgetLedger(self.path).reserve('supplement.one')
        self.assertEqual(0, self.ledger.reserve('probe.one', 0)['requestAllowance'])

    def test_remaining_requests_and_reported_tokens_stop_next_dispatch(self):
        for i in range(99): self.ledger.reserve('trial.' + str(i))
        self.ledger.reserve('known')
        self.ledger.complete('known', usage(5, 2000000), True)
        self.assertEqual(1193, self.ledger.snapshot()['chargedRequests'])
        with self.assertRaises(BudgetStop): self.ledger.reserve('after-token-limit')

    def test_trial_ceiling_includes_no_model_probes(self):
        for i in range(320):
            self.ledger.reserve('probe.' + str(i), 0)
            self.ledger.complete('probe.' + str(i), usage(0, None), True)
        with self.assertRaises(BudgetStop): self.ledger.reserve('probe.321', 0)

    def test_completion_cannot_overwrite_first_usage_or_overspend(self):
        self.ledger.reserve('one', 2)
        with self.assertRaises(ValueError): self.ledger.complete('one', usage(3), True)
        self.ledger.complete('one', usage(1), True)
        with self.assertRaises(ValueError): self.ledger.complete('one', usage(0), True)
        self.assertEqual(1, self.ledger.snapshot()['chargedRequests'])


if __name__ == '__main__': unittest.main()
