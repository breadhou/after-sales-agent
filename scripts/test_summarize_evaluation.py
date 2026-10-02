import copy
import json
import tempfile
import unittest
from pathlib import Path

from scripts.test_evaluation_contract import _build_suite_cases, _write_suite, SHARED_CASES
from scripts.test_evaluation_judge import event
from scripts.evaluation_contract import load_suite
from scripts.summarize_evaluation import summarize, summary_scope, export_report


class SummaryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        shared = json.loads(SHARED_CASES.read_text(encoding='utf-8-sig'))
        path = _write_suite(self.root, 'full', _build_suite_cases(shared, 'full'))
        self.cases = {c['caseId']: c for c in load_suite(path)}
        for case in self.cases.values():
            if 'reviewInput' in case: case['reviewInput'].pop('pairId', None)
        self.manifest = json.loads(path.read_text(encoding='utf-8'))
        self.results = []; self.evidence = {}

    def add(self, case_id, status='PASS', kind='FIRST', index=0, duration=10, tokens=(1, 2, 3)):
        trial_id = 'opaque-' + str(len(self.results))
        result = dict(schemaVersion=1, runId='opaque-run', caseId=case_id, trialId=trial_id,
                      status=status, automaticStatus=status, failedCriteria=[], manualReview='NOT_REQUIRED')
        self.results.append(result)
        model = event(1, 'MODEL', target='GLOBAL', role='DIALOGUE', promptTokens=tokens[0], completionTokens=tokens[1], totalTokens=tokens[2])
        model.update(runId='opaque-run', caseId=case_id, trialId=trial_id)
        worker = dict(schemaVersion=1, runId='opaque-run', caseId=case_id, trialId=trial_id,
                      eventsFile='events.jsonl', privateEvidenceFile='evidence.json', errorCategory=None, terminalEvidence='NOT_SENT',
                      metering=dict(logicalModelRequests=1, promptTokens=tokens[0], completionTokens=tokens[1], totalTokens=tokens[2], unknownUsageRequests=int(None in tokens), usageComplete=None not in tokens))
        self.evidence[('opaque-run', trial_id)] = dict(caseId=case_id, trialKind=kind, repeatIndex=index,
                   events=[model], worker=worker, before={'orders': {}, 'terminalEvidence': 'NOT_SENT'},
                   after={'orders': {}, 'terminalEvidence': 'NOT_SENT'}, workerDurationMs=duration, fixtureDurationMs=1000)
        return result

    def report(self):
        with summary_scope(self.cases, self.evidence):
            return summarize(self.results, self.manifest)

    def test_formal_live_fixed_denominator_includes_errors_skips_and_missing(self):
        live = [c['caseId'] for c in self.cases.values() if c['mode'] == 'LIVE_E2E']
        for case_id in live[:120]: self.add(case_id)
        self.add(live[120], 'ERROR'); self.add(live[121], 'SKIPPED')
        report = self.report()
        self.assertEqual(150, report['liveFirst']['planned'])
        self.assertEqual(120, report['liveFirst']['passed'])
        self.assertEqual(120, report['liveFirst']['valid'])
        self.assertEqual(28, report['liveFirst']['missing'])
        self.assertEqual(0.8, report['liveFirst']['passRate'])

    def test_three_modes_pilot_and_formal_do_not_mix(self):
        for mode in ('LIVE_E2E', 'CONTROLLED', 'REVIEW_ONLY'):
            self.add(next(c['caseId'] for c in self.cases.values() if c['mode'] == mode))
        report = self.report()
        self.assertEqual([1, 1, 1], [report[k]['passed'] for k in ('liveFirst', 'controlledFirst', 'reviewFirst')])
        self.assertEqual([150, 60, 30], [report[k]['planned'] for k in ('liveFirst', 'controlledFirst', 'reviewFirst')])
        shared = json.loads(SHARED_CASES.read_text(encoding='utf-8-sig'))
        path = _write_suite(self.root / 'pilot', 'pilot', _build_suite_cases(shared, 'pilot'))
        with summary_scope({c['caseId']: c for c in load_suite(path)}, {}):
            self.assertEqual(22, summarize([], json.loads(path.read_text()))['liveFirst']['planned'])

    def test_first_failure_not_replaced_by_repeat_or_supplement(self):
        case_id = self.manifest['repeatIds'][0]
        self.add(case_id, 'FAIL')
        self.add(case_id, kind='REPEAT', index=1)
        self.add(case_id, kind='REPEAT', index=2)
        self.add(case_id, kind='SUPPLEMENT')
        report = self.report()
        self.assertEqual(1, report['liveFirst']['failed'])
        self.assertEqual(0, report['liveFirst']['passed'])
        self.assertEqual(24, report['repeats']['planned'])
        self.assertEqual(2, report['repeats']['passed'])
        self.assertEqual(1, report['supplement']['passed'])
        self.assertEqual([1, 2], report['repeatSequences'][0]['attempts'])

    def test_absent_scope_or_trial_metadata_not_inferred_from_id(self):
        self.add(next(iter(self.cases)))
        self.assertRaises(ValueError, summarize, self.results, self.manifest)
        self.evidence.clear()
        self.assertRaises(ValueError, self.report)

    def test_duplicate_first_or_unknown_repeat_is_rejected(self):
        case_id = next(iter(self.cases)); self.add(case_id); self.add(case_id)
        self.assertRaises(ValueError, self.report)
        self.results.clear(); self.evidence.clear()
        nonrepeat = next(i for i in self.cases if i not in self.manifest['repeatIds'])
        self.add(nonrepeat, kind='REPEAT', index=1)
        self.assertRaises(ValueError, self.report)

    def test_role_provider_meter_excludes_script_and_retains_partial_usage(self):
        self.add(next(iter(self.cases)), tokens=(7, None, None))
        metadata = next(iter(self.evidence.values()))
        metadata['events'].append({**metadata['events'][0], 'sequence': 2, 'callId': 'script-1', 'status': 'SCRIPTED', 'totalTokens': None, 'promptTokens': None})
        report = self.report()
        role = report['cost']['roles']['DIALOGUE']
        self.assertEqual(1, role['requests'])
        self.assertEqual(7, role['promptTokens'])
        self.assertIsNone(role['totalTokens'])
        self.assertEqual(1, role['unknownUsageRequests'])
        self.assertNotIn('currency', json.dumps(report))

    def test_worker_latency_nearest_rank_not_event_or_fixture_sum(self):
        for i, case_id in enumerate(list(self.cases)[:20]): self.add(case_id, duration=i + 1)
        report = self.report()
        self.assertEqual(10, report['latency']['worker']['p50Ms'])
        self.assertEqual(19, report['latency']['worker']['p95Ms'])
        self.assertEqual(1000, report['latency']['fixture']['p50Ms'])

    def test_unknown_duration_is_unavailable_not_zero(self):
        self.add(next(iter(self.cases)), duration=None)
        report = self.report()
        self.assertEqual(1, report['latency']['worker']['unknown'])
        self.assertIsNone(report['latency']['worker']['p50Ms'])

    def test_review_invalid_error_not_rejection_hit_and_groups_separate(self):
        ids = [c['caseId'] for c in self.cases.values() if c['mode'] == 'REVIEW_ONLY']
        self.cases[ids[0]]['expect']['outcome'] = 'REVIEW_REJECTED'
        self.add(ids[0], 'ERROR')
        self.add(ids[1])
        report = self.report()
        self.assertEqual(12, report['reviewGroups']['risk']['planned'])
        self.assertEqual(12, report['reviewGroups']['normal']['planned'])
        self.assertEqual(0, report['reviewGroups']['risk']['passed'])
        self.assertEqual(3, len(report['reviewGroups']['policyPairs']))

    def test_normal_refund_and_escalation_attempts_and_actual_rows_distinct(self):
        case_id = next(iter(self.cases)); self.add(case_id)
        data = next(iter(self.evidence.values()))
        identity = {k: data['events'][0][k] for k in ('runId', 'caseId', 'trialId')}
        data['events'] += [{**event(2, 'MCP', 'CALLED', tool='submit_refund', role='MCP'), **identity}, {**event(3, 'ESCALATION'), **identity}]
        data['before']['orders'] = {'order-a': dict(orderStatus='RECEIVED', paidAmount='39.8', refundRows=[], ownerMatches=True)}
        data['after']['orders'] = {'order-a': dict(orderStatus='REFUNDED', paidAmount='39.80', refundRows=[dict(status='REFUNDED', amount='39.80', ownerMatches=True)], ownerMatches=True)}
        report = self.report()
        self.assertEqual(1, report['normalRefund']['completed'])
        self.assertEqual(1, report['normalRefund']['escalated'])
        self.assertEqual(1, report['safety']['submitAttempts'])
        self.assertEqual(1, report['safety']['newRefundRows'])

    def test_export_recursive_whitelist_rejects_secrets_extra_and_unsafe_criteria(self):
        self.add(next(iter(self.cases)))
        report = self.report(); path = self.root / 'report.json'
        export_report(report, path)
        self.assertEqual(report, json.loads(path.read_text(encoding='utf-8')))
        for mutate in ('top', 'nested', 'string', 'criterion'):
            with self.subTest(mutate=mutate):
                value = copy.deepcopy(report)
                if mutate == 'top': value['rawReply'] = 'secret-sentinel'
                if mutate == 'nested': value['cost']['roles']['REVIEW']['userToken'] = 'secret-sentinel'
                if mutate == 'string': value['phase'] = 'secret-sentinel'
                if mutate == 'criterion': value['failures']['secret-sentinel'] = 1
                self.assertRaises(ValueError, export_report, value, path)
                self.assertNotIn('secret-sentinel', path.read_text(encoding='utf-8'))

    def test_closed_metadata_cloned_and_reset(self):
        self.add(next(iter(self.cases)))
        with summary_scope(self.cases, self.evidence):
            next(iter(self.evidence.values()))['workerDurationMs'] = 99
            self.assertEqual(10, summarize(self.results, self.manifest)['latency']['worker']['p50Ms'])
        self.assertRaises(ValueError, summarize, self.results, self.manifest)
        next(iter(self.evidence.values()))['secret'] = 'secret-sentinel'
        self.assertRaises(ValueError, self.report)

    def test_missing_worker_and_oracles_error_skip_remain_in_fixed_denominator(self):
        ids = list(self.cases)[:2]
        for i, status in zip(ids, ('ERROR', 'SKIPPED')):
            result = self.add(i, status)
            data = self.evidence[(result['runId'], result['trialId'])]
            data.update(worker={}, events=[], before={}, after={}, workerDurationMs=None)
        report = self.report()
        self.assertEqual(150, report['liveFirst']['planned'])
        self.assertEqual(1, report['liveFirst']['errors']); self.assertEqual(1, report['liveFirst']['skipped'])
        self.assertEqual(2, report['evidenceAvailability']['workerMissingTrials'])
        self.assertEqual(2, report['evidenceAvailability']['oracleMissingTrials'])
        self.assertIsNone(report['latency']['worker']['p50Ms'])

    def test_three_actual_policy_pairs_and_review_error_not_a_hit(self):
        ids = [c['caseId'] for c in self.cases.values() if c['mode'] == 'REVIEW_ONLY']
        for index, case_id in enumerate(ids[:6]):
            self.cases[case_id]['reviewInput']['pairId'] = 'PAIR-' + str(index // 2)
            self.cases[case_id]['expect']['outcome'] = 'REVIEW_APPROVED' if index % 2 else 'REVIEW_REJECTED'
            result = self.add(case_id)
            data = self.evidence[(result['runId'], result['trialId'])]
            review = {**event(2, 'REVIEW', 'COMPLETED' if index % 2 else 'REJECTED', target='GLOBAL', role='REVIEW'), **{k: result[k] for k in ('runId', 'caseId', 'trialId')}}
            data['events'].append(review)
        self.assertTrue(all(p['bothPassed'] for p in self.report()['reviewGroups']['policyPairs']))
        self.evidence[('opaque-run', 'opaque-0')]['events'][-1]['status'] = 'FAILED'
        self.assertFalse(self.report()['reviewGroups']['policyPairs'][0]['bothPassed'])

    def test_cost_and_latency_modes_separate_and_blocked_attempt_visible(self):
        for mode in ('LIVE_E2E', 'CONTROLLED', 'REVIEW_ONLY'):
            result = self.add(next(c['caseId'] for c in self.cases.values() if c['mode'] == mode))
            data = self.evidence[(result['runId'], result['trialId'])]
            if mode == 'LIVE_E2E':
                data['events'].append({**event(2, 'SESSION', 'REJECTED', target='GLOBAL', tool='UNKNOWN_TOOL', role='DIALOGUE'), **{k: result[k] for k in ('runId', 'caseId', 'trialId')}})
        report = self.report()
        self.assertEqual([1, 1, 1], [report['costByMode'][m]['requests'] for m in ('LIVE_E2E', 'CONTROLLED', 'REVIEW_ONLY')])
        self.assertEqual(1, report['safety']['violatingAttempts'])
        self.assertEqual(0, report['safety']['newRefundRows'])

    def test_fix_i6_financial_write_has_violation_and_applicable_denominators(self):
        result = self.add(next(iter(self.cases)), 'FAIL')
        result['failedCriteria'] = ['AMOUNT']
        data = self.evidence[(result['runId'], result['trialId'])]
        data['events'].append({**event(2, 'MCP', 'CALLED', role='MCP', tool='submit_refund'), **{k: result[k] for k in ('runId', 'caseId', 'trialId')}})
        data['before']['orders'] = {'order-a': dict(orderStatus='RECEIVED', paidAmount='39.8', refundRows=[], ownerMatches=True)}
        data['after']['orders'] = {'order-a': dict(orderStatus='REFUNDED', paidAmount='39.8', refundRows=[dict(status='REFUNDED', amount='39.81', ownerMatches=True)], ownerMatches=True)}
        safety = self.report()['safety']
        self.assertEqual(1, safety['violatingTrials'])
        self.assertEqual(1, safety['prohibitedNewRefundRows'])
        self.assertEqual(1, safety['invariants']['amount']['applicable'])
        self.assertEqual(1, safety['invariants']['amount']['violations'])

    def test_fix_i7_six_categories_and_measured_duration_mean(self):
        for case_id, duration in zip(list(self.cases)[:2], (10, 30)): self.add(case_id, duration=duration)
        report = self.report()
        self.assertEqual(6, len(report['categoryFirst']))
        self.assertEqual(60, report['categoryFirst']['NORMAL']['planned'])
        self.assertEqual(2, report['categoryFirst']['NORMAL']['passed'])
        self.assertEqual(2/60, report['categoryFirst']['NORMAL']['coverage'])
        self.assertEqual(20, report['latency']['worker']['meanMs'])
        self.assertEqual(1000, report['latency']['fixture']['meanMs'])

    def test_fix_i6_per_order_financial_invariants_and_no_all_submit_attribution(self):
        result = self.add(next(iter(self.cases)), 'FAIL')
        result['failedCriteria'] = ['OWNER', 'REFUND_ROWS']
        data = self.evidence[(result['runId'], result['trialId'])]
        identity = {k: result[k] for k in ('runId','caseId','trialId')}
        self.cases[result['caseId']]['fixture']['orders']['order-b'] = copy.deepcopy(self.cases[result['caseId']]['fixture']['orders']['order-a'])
        self.cases[result['caseId']]['expect']['orders']['order-b'] = copy.deepcopy(self.cases[result['caseId']]['expect']['orders']['order-a'])
        data['before']['orders'] = {a:dict(orderStatus='RECEIVED', paidAmount='39.8',refundRows=[],ownerMatches=True) for a in ('order-a','order-b')}
        data['after']['orders'] = {a:dict(orderStatus='REFUNDED',paidAmount='39.8',refundRows=[dict(status='REFUNDED',amount='39.8',ownerMatches=a=='order-b')],ownerMatches=True) for a in ('order-a','order-b')}
        data['after']['orders']['order-a']['refundRows'].append(copy.deepcopy(data['after']['orders']['order-a']['refundRows'][0]))
        for target in ('order-a','order-b'):
            for phase, status, call in [('CONFIRMATION','COMPLETED','confirm-'+target),('REVIEW','COMPLETED','review-'+target),
                                         ('MCP','CALLED','rejected-'+target),('MCP','BUSINESS_ERROR','rejected-'+target),
                                         ('MCP','CALLED','write-'+target),('MCP','RESPONSE_RECEIVED','write-'+target)]:
                data['events'].append({**event(len(data['events'])+1,phase,status,target=target,callId=call,
                                             tool='submit_refund' if phase=='MCP' else None,role='MCP' if phase=='MCP' else 'ORCHESTRATOR'), **identity})
        safety = self.report()['safety']
        self.assertEqual(4, safety['submitAttempts'])
        self.assertEqual(1, safety['violatingAttempts'])
        self.assertEqual(1, safety['violatingTrials'])
        self.assertEqual(2, safety['prohibitedNewRefundRows'])
        self.assertEqual(dict(applicable=2,violations=1), safety['invariants']['ownership'])
        self.assertEqual(dict(applicable=2,violations=1), safety['invariants']['rowCount'])
        self.assertEqual(dict(applicable=2,violations=1), safety['invariants']['idempotence'])
        next(e for e in data['events'] if e['callId']=='rejected-order-a' and e['status']=='BUSINESS_ERROR')['status']='RESPONSE_RECEIVED'
        ambiguous=self.report()['safety']
        self.assertEqual(0,ambiguous['violatingAttempts'])
        self.assertEqual(1,ambiguous['unattributedWriteViolationOrders'])

    def test_fix_i6_prohibited_write_no_amount_expectation_stays_visible(self):
        result = self.add(next(iter(self.cases)), 'FAIL')
        result['failedCriteria'] = ['REFUND_ROWS']
        case = self.cases[result['caseId']]
        case['expect']['outcome'] = 'NOT_SUBMITTED'
        case['expect']['orders']['order-a'].update(orderStatus='RECEIVED',newRefundRows=0,refundAmount=None)
        data = self.evidence[(result['runId'],result['trialId'])]
        data['before']['orders'] = {'order-a':dict(orderStatus='RECEIVED',paidAmount='39.8',refundRows=[],ownerMatches=True)}
        data['after']['orders'] = {'order-a':dict(orderStatus='REFUNDED',paidAmount='39.8',refundRows=[dict(status='REFUNDED',amount='39.8',ownerMatches=True)],ownerMatches=True)}
        safety = self.report()['safety']
        self.assertEqual(1, safety['prohibitedNewRefundRows'])
        self.assertEqual(dict(applicable=1,violations=1),safety['invariants']['prohibitedWrite'])
        self.assertEqual(dict(applicable=1,violations=0),safety['invariants']['amount'])
        self.assertEqual(0,safety['violatingAttempts'])
        self.assertEqual(1,safety['unattributedWriteViolationOrders'])

    def test_fix_i6_rollback_probe_has_own_denominator_and_no_invented_submit(self):
        case_id=next(c['caseId'] for c in self.cases.values() if c['mode']=='CONTROLLED')
        case=self.cases[case_id]
        case['control']=dict(target='BACKEND_TRANSACTION',point='BACKEND_PROBE',probe='ROLLBACK_AFTER_INSERT',usesRealModel=False,
                             components=dict(DIALOGUE='ABSENT',REVIEW='ABSENT',EXPLANATION='ABSENT',MCP='ABSENT',BACKEND='REAL'))
        case['expect']['outcome']='NOT_SUBMITTED'
        case['expect']['orders']['order-a'].update(orderStatus='RECEIVED',newRefundRows=0,refundAmount=None)
        result=self.add(case_id,'FAIL');result['failedCriteria']=['PROBE_ASSERTIONS','REFUND_ROWS']
        data=self.evidence[(result['runId'],result['trialId'])]
        data.update(events=[],worker={},workerDurationMs=None)
        data['before']['orders']={'order-a':dict(orderStatus='RECEIVED',paidAmount='39.8',refundRows=[],ownerMatches=True)}
        data['after']['orders']={'order-a':dict(orderStatus='RECEIVED',paidAmount='39.8',refundRows=[dict(status='PENDING',amount='39.8',ownerMatches=True)],ownerMatches=True)}
        safety=self.report()['safety']
        self.assertEqual(dict(applicable=1,violations=1),safety['invariants']['atomicity'])
        self.assertEqual(1,safety['prohibitedNewRefundRows'])
        self.assertEqual(0,safety['submitAttempts'])
        self.assertEqual(0,safety['confirmedRefundCompletions'])
        case['control']['probe']='STALE_POLICY'
        self.assertEqual(dict(applicable=0,violations=0),self.report()['safety']['invariants']['atomicity'])

    def test_fix_i7_categories_preserve_kinds_status_coverage_and_mean_null(self):
        case_id = self.manifest['repeatIds'][0]
        self.add(case_id, 'ERROR',duration=None)
        self.add(case_id,'SKIPPED',kind='REPEAT',index=1,duration=25)
        self.add(case_id,kind='SUPPLEMENT',duration=75)
        report = self.report(); category = self.cases[case_id]['category']
        self.assertEqual(1,report['categoryFirst'][category]['errors'])
        self.assertEqual(1/report['categoryFirst'][category]['planned'],report['categoryFirst'][category]['coverage'])
        self.assertEqual(1,report['categoryByKind']['REPEAT'][category]['skipped'])
        self.assertEqual(1,report['categoryByKind']['SUPPLEMENT'][category]['passed'])
        self.assertEqual(50,report['latency']['worker']['meanMs'])
        self.assertEqual(1,report['latency']['worker']['unknown'])
        export_report(report,self.root/'safe-added-fields.json')
        for mean in (float('nan'),float('inf'),-1,'secret'):
            bad=copy.deepcopy(report);bad['latency']['worker']['meanMs']=mean
            self.assertRaises(ValueError,export_report,bad,self.root/'safe-added-fields.json')


if __name__ == '__main__':
    unittest.main()
