import copy
import io
import json
import os
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts.test_evaluation_contract import _build_suite_cases, _write_suite
from scripts import test_evaluation_judge as judge_test

try:
    from scripts import run_evaluation as runner
    from scripts.evaluation_fixture_client import FixtureClient
except ImportError:
    runner = FixtureClient = None

ROOT = Path(__file__).resolve().parents[1]
SHARED = json.loads((ROOT / 'eval/fixtures/case-contract.json').read_text(encoding='utf-8-sig'))


class BatchTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(runner, 'Task10 batch runner implementation is missing')
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.run = self.root / 'eval/runs/pilot-run'
        self.cases = _build_suite_cases(SHARED, 'pilot')
        self.manifest = _write_suite(self.root / 'suite', 'pilot', self.cases)
        self.patch = patch.object(runner, 'REPO_ROOT', self.root)
        self.patch.start()
        self.addCleanup(self.patch.stop)

    def test_worker_environment_excludes_backend_secrets(self):
        parent = {'PATH': 'runtime', 'MERCHANT_JWT_SECRET': 'backend', 'SUPERMALL_TOKEN': 'old',
                  'SPRING_DATASOURCE_PASSWORD': 'db', 'JAVA_TOOL_OPTIONS': 'unsafe', 'MODEL_API_KEY': 'old-model'}
        environment = runner.build_worker_environment({'MODEL_NAME': 'chosen', 'MODEL_API_KEY': 'new',
                    'SPRING_DATASOURCE_PASSWORD': 'bad'}, 'new-fixture', 'http://localhost:8081', None, parent)
        self.assertEqual({'PATH': 'runtime', 'MODEL_NAME': 'chosen', 'MODEL_API_KEY': 'new',
                          'SUPERMALL_TOKEN': 'new-fixture', 'SUPERMALL_BASE_URL': 'http://localhost:8081'}, environment)
        self.assertNotIn('SUPERMALL_TOKEN', runner.build_worker_environment({}, None, 'local', None, parent))

    def test_windows_worker_environment_preserves_normalized_runtime_and_fresh_auth(self):
        parent = {'SYSTEMROOT': 'windows-root', 'WINDIR': 'windows-directory', 'PATH': 'runtime-path',
                  'JAVA_HOME': 'runtime-jdk', 'MERCHANT_JWT_SECRET': 'backend-sentinel',
                  'SPRING_DATASOURCE_PASSWORD': 'db-sentinel', 'JAVA_TOOL_OPTIONS': 'unsafe',
                  'SUPERMALL_TOKEN': 'old-customer', 'MODEL_API_KEY': 'old-model',
                  'DEMO_PRODUCT_MANIFEST': 'untrusted-manifest'}
        model = {'MODEL_NAME': 'chosen', 'MODEL_API_KEY': 'new-model',
                 'MERCHANT_JWT_SECRET': 'untrusted-backend'}
        manifest = self.root / 'fixture-products.json'
        with patch.object(runner.os, 'name', 'nt'):
            environment = runner.build_worker_environment(model, 'fresh-actor', 'fixture-base', manifest, parent)
            without_actor = runner.build_worker_environment({}, None, 'fixture-base', None, parent)
        self.assertEqual({'SystemRoot': 'windows-root', 'WINDIR': 'windows-directory', 'PATH': 'runtime-path',
                          'JAVA_HOME': 'runtime-jdk', 'MODEL_NAME': 'chosen', 'MODEL_API_KEY': 'new-model',
                          'SUPERMALL_TOKEN': 'fresh-actor', 'SUPERMALL_BASE_URL': 'fixture-base',
                          'DEMO_PRODUCT_MANIFEST': str(manifest)}, environment)
        self.assertNotIn('SUPERMALL_TOKEN', without_actor)
        self.assertNotIn('DEMO_PRODUCT_MANIFEST', without_actor)

    def test_posix_worker_environment_keeps_runtime_names_case_sensitive(self):
        parent = {'SYSTEMROOT': 'wrong-case-root', 'path': 'wrong-case-path', 'PATH': 'exact-path',
                  'windir': 'wrong-case-directory', 'SUPERMALL_TOKEN': 'old-customer'}
        with patch.object(runner.os, 'name', 'posix'):
            environment = runner.build_worker_environment({}, None, 'fixture-base', None, parent)
            with_exact_root = runner.build_worker_environment({}, None, 'fixture-base', None,
                                                             dict(parent, SystemRoot='exact-root'))
        self.assertEqual({'PATH': 'exact-path', 'SUPERMALL_BASE_URL': 'fixture-base'}, environment)
        self.assertEqual(dict(environment, SystemRoot='exact-root'), with_exact_root)

    def test_dry_run_makes_no_fixture_or_model_calls(self):
        with patch.object(runner, 'FixtureClient', side_effect=AssertionError('fixture touched')), \
             patch.object(runner, '_run_worker', side_effect=AssertionError('model touched')), \
             patch.object(runner, '_read_env_file', side_effect=AssertionError('credentials read')):
            result = runner.run_batch(self.manifest, self.run, False, False)
        self.assertEqual(32, result['plannedTrials'])
        self.assertTrue(result['dryRun'])
        self.assertFalse(self.run.exists())

    def test_default_cli_is_dry_run(self):
        completed = subprocess.run([sys.executable, str(ROOT / 'scripts/run_evaluation.py'),
            '--manifest', str(self.manifest), '--run-dir', str(ROOT / 'eval/runs/task10-offline-dry')],
            capture_output=True, text=True, check=False)
        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertTrue(json.loads(completed.stdout)['dryRun'])

    def test_case_version_change_is_not_silent_resume(self):
        state = runner._initialize_batch(self.manifest, self.run, False)
        self.cases[0]['variationRationale'] = 'Changed frozen case'
        _write_suite(self.root / 'suite', 'pilot', self.cases)
        with self.assertRaises(ValueError): runner.run_batch(self.manifest, self.run, True, True)
        self.assertEqual(state['manifestHash'], runner._read_json(self.run / 'batch.json')['manifestHash'])

    def test_resume_keeps_reservations_and_first_results(self):
        state = runner._initialize_batch(self.manifest, self.run, False)
        first = state['trials'][0]
        trial = self.run / first['trialId']; trial.mkdir()
        result = runner._error_result(state['runId'], first, 'MISSING_EVIDENCE')
        (trial / 'result.json').write_text(json.dumps(result))
        first.update(state='COMPLETE', terminated=True, terminalEvidence='NOT_SENT')
        state['trials'][1]['state'] = 'INFLIGHT'
        runner._atomic_json(self.run / 'batch.json', state)
        ledger = runner.BudgetLedger(self.root / 'eval/runs/budget-ledger.json')
        ledger.reserve(state['runId'] + '.' + state['trials'][1]['trialId'])
        old = (trial / 'result.json').read_bytes()
        with patch.object(runner, 'FixtureClient', side_effect=AssertionError('inflight replay')):
            report = runner.run_batch(self.manifest, self.run, True, True)
        self.assertEqual('INFLIGHT', report['stopReason'])
        self.assertEqual(old, (trial / 'result.json').read_bytes())
        self.assertEqual(12, ledger.snapshot()['chargedRequests'])

    def test_supplement_requires_infrastructure_reason_and_verified_old_write(self):
        state = runner._initialize_batch(self.manifest, self.run, False)
        first = state['trials'][0]
        first.update(state='COMPLETE', terminated=True, terminalEvidence='UNKNOWN')
        trial = self.run / first['trialId']; trial.mkdir()
        runner._atomic_json(trial / 'result.json', runner._error_result(state['runId'], first, 'UNRESOLVED_WRITE'))
        runner._atomic_json(self.run / 'batch.json', state)
        for reason in (None, 'semantic:wrong answer', 'infrastructure:timeout'):
            with self.assertRaises(ValueError):
                runner.run_batch(self.manifest, self.run, True, True, first['caseId'], reason)

    def test_worker_evidence_paths_reject_escape_and_identity_mismatch(self):
        work = self.root / 'work'; work.mkdir()
        worker = dict(schemaVersion=1, runId='run', caseId='NORMAL-001', trialId='one',
            eventsFile='../outside.jsonl', privateEvidenceFile='private.json', terminalEvidence='NOT_SENT',
            errorCategory=None, metering=dict(logicalModelRequests=0, promptTokens=None, completionTokens=None,
                totalTokens=None, usageComplete=True, unknownUsageRequests=0))
        with self.assertRaises(ValueError): runner._load_worker_evidence(worker, work, 'run', 'NORMAL-001', 'one')

    def fixture_helper(self, calls):
        owner = self
        class Helper:
            terminated = True
            def request(self, message):
                op = message['op']; calls.append(op)
                reply = dict(schemaVersion=1, op=op, status='COMPLETED')
                if op == 'prepare':
                    relative = '/'.join((message['runId'], message['caseId'], message['trialId']))
                    path = owner.root / 'backend' / relative; path.mkdir(parents=True)
                    binding = dict(schemaVersion=1, runId=message['runId'], caseId=message['caseId'], trialId=message['trialId'],
                        activeActor='actor-a', actors={'actor-a': dict(userId='9007199254740993', userToken='CURRENT_ONLY'),
                        'actor-b': dict(userId='9007199254740994')}, orders={'order-a': dict(orderId='9007199254741001', orderNo='PRIVATE')},
                        products={'product-a': dict(productId='9007199254741011', skus={'sku-a': dict(skuId='9007199254741021', price='19.90')})})
                    runner._exclusive_json(path / 'bindings.json', binding)
                    reply.update(ledgerPath=relative+'/ledger.json', bindingFile=relative+'/bindings.json')
                if op == 'oracle':
                    terminal = message['terminalEvidence']
                    reply['oracle'] = dict(terminalEvidence=terminal, orders={'order-a': dict(orderStatus='RECEIVED', paidAmount='39.80', refundRows=[], ownerMatches=True)})
                    if terminal == 'COMPLETED':
                        reply['oracle']['orders']['order-a'].update(orderStatus='REFUNDED', refundRows=[dict(status='REFUNDED',amount='39.80',ownerMatches=True)])
                return reply
            def close(self): calls.append('close'); return True
        return Helper()

    def worker(self, calls, *, unknown=False, tokens=None):
        def execute(config_path, environment, trial_root):
            calls.append('worker')
            config = runner._read_json(config_path)
            ledger = runner.BudgetLedger(self.root / 'eval/runs/budget-ledger.json').snapshot()
            self.assertEqual(12, ledger['chargedRequests'])
            self.assertEqual('CURRENT_ONLY', environment['SUPERMALL_TOKEN'])
            self.assertFalse(any(key in environment for key in ('MERCHANT_JWT_SECRET', 'SPRING_DATASOURCE_PASSWORD')))
            self.assertEqual([], list((trial_root / 'work').iterdir()))
            self.assertEqual('9007199254741021', runner._read_json(trial_root/'bindings.json')['products']['product-a']['skus']['sku-a'])
            self.assertEqual('19.90', runner._read_json(trial_root/'helper-bindings.json')['products']['product-a']['skus']['sku-a']['price'])
            evidence = judge_test.JudgeTest(); evidence.setUp()
            try:
                evidence.root = trial_root / 'work'
                evidence.worker.update(runId=config['runId'], caseId=config['caseId'], trialId=config['trialId'])
                evidence.bindings = runner._read_json(trial_root / 'bindings.json')
                for record in evidence.events:
                    record.update(runId=config['runId'], caseId=config['caseId'], trialId=config['trialId'])
                if unknown:
                    evidence.worker['terminalEvidence'] = 'UNKNOWN'
                if tokens is not None:
                    model = judge_test.event(len(evidence.events)+1, 'MODEL', target='GLOBAL', role='DIALOGUE', promptTokens=tokens, completionTokens=0, totalTokens=tokens)
                    model.update(runId=config['runId'], caseId=config['caseId'], trialId=config['trialId'])
                    evidence.events.append(model)
                    evidence.worker['metering'].update(logicalModelRequests=1, promptTokens=tokens, completionTokens=0,totalTokens=tokens)
                evidence.save()
                return evidence.worker, 4, True
            finally: evidence.doCleanups()
        return execute

    def prepare_real_first(self):
        self.cases[0]['turns'][1]['input'] = '/confirm-refund {{order-a}}'
        self.cases[0]['expect']['policyCodes'] = ['SEVEN_DAY_NO_REASON']
        self.cases[0]['manualRubric']['criteria'] = []
        _write_suite(self.root / 'suite', 'pilot', self.cases)

    def test_actual_orchestration_reserves_before_worker_and_halts_on_reported_tokens(self):
        self.prepare_real_first(); calls = []
        helper = self.fixture_helper(calls)
        with patch.object(runner, '_helper', return_value=(helper, self.root/'backend')), \
             patch.object(runner, '_read_env_file', return_value={}), \
             patch.object(runner, '_run_worker', side_effect=self.worker(calls, tokens=2000000)):
            result = runner.run_batch(self.manifest, self.run, True, False)
        self.assertEqual('BUDGET_STOP', result['stopReason'])
        self.assertEqual(1, result['completedTrials'])
        self.assertEqual(['preflight','prepare','oracle','worker','oracle','shutdown','close'], calls)
        state = runner._read_json(self.run / 'batch.json')
        first = runner._read_json(self.run / state['trials'][0]['trialId'] / 'result.json')
        self.assertEqual('PASS', first['status'], first)

    def test_unknown_write_stops_even_with_empty_after_oracle(self):
        self.prepare_real_first(); calls = []
        helper = self.fixture_helper(calls)
        with patch.object(runner, '_helper', return_value=(helper, self.root/'backend')), \
             patch.object(runner, '_read_env_file', return_value={}), \
             patch.object(runner, '_run_worker', side_effect=self.worker(calls, unknown=True)):
            result = runner.run_batch(self.manifest, self.run, True, False)
        self.assertEqual('UNRESOLVED_WRITE', result['stopReason'])
        self.assertEqual(1, result['completedTrials'])
        self.assertEqual(['preflight','prepare','oracle','worker','oracle','close'], calls)
        state = runner._read_json(self.run / 'batch.json')
        first = runner._read_json(self.run / state['trials'][0]['trialId'] / 'result.json')
        self.assertEqual(['UNRESOLVED_WRITE'], first['failedCriteria'])

    def test_missing_worker_reply_still_saves_unknown_after_oracle_without_replay(self):
        self.prepare_real_first(); calls=[]; helper=self.fixture_helper(calls)
        with patch.object(runner,'_helper',return_value=(helper,self.root/'backend')), \
             patch.object(runner,'_read_env_file',return_value={}), \
             patch.object(runner,'_run_worker',return_value=(None,3,True)):
            result=runner.run_batch(self.manifest,self.run,True,False)
        self.assertEqual('UNRESOLVED_WRITE',result['stopReason'])
        self.assertEqual(['preflight','prepare','oracle','oracle','close'],calls)
        state=runner._read_json(self.run/'batch.json')
        after=runner._read_json(self.run/state['trials'][0]['trialId']/'after.json')
        self.assertEqual('UNKNOWN',after['terminalEvidence'])
        self.assertEqual([],after['orders']['order-a']['refundRows'])
        self.assertEqual(12,result['budget']['chargedRequests'])

    def test_review_only_has_synthetic_binding_without_fixture_prepare_or_database_claim(self):
        case = self.cases[-1]; calls = []
        state = runner._initialize_batch(self.manifest, self.run, False)
        trial = state['trials'][-1]
        def worker(config, environment, directory):
            self.assertNotIn('SUPERMALL_TOKEN', environment)
            self.assertEqual({}, runner._read_json(directory/'bindings.json')['actors'])
            self.assertEqual(False, runner._read_json(directory/'oracle-scope.json')['databaseProof'])
            return None, 1, True
        with patch.object(runner, '_run_worker', side_effect=worker):
            runner._execute_trial(case, trial, state, self.run, None, None, {}, 'local',
                runner.BudgetLedger(self.root/'eval/runs/budget-ledger.json'), None)
        self.assertEqual('NOT_SENT', trial['terminalEvidence'])
        self.assertEqual('MISSING_EVIDENCE', runner._read_json(self.run/trial['trialId']/'result.json')['failedCriteria'][0])

    def test_no_request_allowance_does_not_occupy_worker_directory(self):
        state = runner._initialize_batch(self.manifest, self.run, False)
        ledger = runner.BudgetLedger(self.root/'eval/runs/budget-ledger.json')
        for i in range(100): ledger.reserve('old.'+str(i))
        trial = state['trials'][0]
        with self.assertRaises(runner.BudgetStop):
            runner._execute_trial(self.cases[0], trial, state, self.run, None, None, {}, 'local', ledger, None)
        self.assertFalse((self.run/trial['trialId']).exists())

    def test_supplement_is_new_id_and_preserves_failed_first(self):
        state = runner._initialize_batch(self.manifest, self.run, False)
        trial = state['trials'][0]
        trial.update(state='COMPLETE',terminated=True,terminalEvidence='NOT_SENT')
        folder = self.run/trial['trialId']; folder.mkdir()
        runner._exclusive_json(folder/'result.json',runner._error_result(state['runId'],trial,'FIXTURE_ERROR'))
        first = (folder/'result.json').read_bytes()
        extra = runner._supplement(state,self.run,trial['caseId'],'infrastructure:fixture unavailable')[0]
        self.assertNotEqual(trial['trialId'], extra['trialId'])
        self.assertEqual('SUPPLEMENT',extra['trialKind'])
        self.assertEqual(first,(folder/'result.json').read_bytes())

    def test_backend_probe_uses_saved_actual_reply_and_no_java_or_model_evidence(self):
        case = copy.deepcopy(self.cases[0]); case['mode'] = 'CONTROLLED'
        case['control'] = dict(target='BACKEND_TRANSACTION',components=dict(DIALOGUE='ABSENT',REVIEW='ABSENT',
            EXPLANATION='ABSENT',MCP='ABSENT',BACKEND='REAL'),usesRealModel=False,point='BACKEND_PROBE',probe='ROLLBACK_AFTER_INSERT')
        case['expect'].update(outcome='NOT_SUBMITTED',orders={'order-a':dict(orderStatus='RECEIVED',newRefundRows=0,refundAmount=None,ownerMatches=True)})
        state = runner._initialize_batch(self.manifest,self.run,False); trial=state['trials'][0]
        calls=[]; original=self.fixture_helper(calls)
        class Probe:
            def request(self,message):
                if message['op']=='probe':
                    calls.append('probe')
                    return dict(schemaVersion=1,op='probe',status='COMPLETED',probeEvidence=dict(probe='ROLLBACK_AFTER_INSERT',durationMs=9,receiptClass='NOT_APPLICABLE',assertionsPassed=True))
                if message['op']=='oracle' and message['terminalEvidence']=='COMPLETED':
                    value=original.request({**message,'terminalEvidence':'NOT_SENT'})
                    value['oracle']['terminalEvidence']='COMPLETED'; return value
                return original.request(message)
        with patch.object(runner,'_run_worker',side_effect=AssertionError('backend probe spawned Java')):
            result=runner._execute_trial(case,trial,state,self.run,Probe(),self.root/'backend',{},'local',
                runner.BudgetLedger(self.root/'eval/runs/budget-ledger.json'),None)
        self.assertEqual('PASS',result['status'],result)
        metadata=runner._read_json(self.run/trial['trialId']/'metadata.json')
        self.assertEqual([],metadata['events']); self.assertEqual({},metadata['worker'])
        self.assertIsNone(metadata['workerDurationMs'])
        self.assertEqual(0,runner.BudgetLedger(self.root/'eval/runs/budget-ledger.json').snapshot()['chargedRequests'])
        self.assertEqual(['prepare','oracle','probe','oracle'],calls)

    def test_worker_deadline_kills_owned_process_and_retains_real_exit_record(self):
        folder=self.root/'trial'; folder.mkdir()
        jar=self.root/'agent/target/agent.jar'; jar.parent.mkdir(parents=True); jar.write_bytes(b'offline placeholder')
        actual=runner.OwnedProcess
        def process(command,**kwargs):
            return actual([sys.executable,'-c','import time; time.sleep(60)'],**kwargs)
        environment={key:value for key,value in os.environ.items() if key in ('SystemRoot','PATH','TEMP','TMP')}
        with patch.object(runner,'OwnedProcess',side_effect=process),patch.object(runner,'DEADLINE_SECONDS',.1):
            worker,duration,terminated=runner._run_worker(folder/'config.json',environment,folder)
        self.assertIsNone(worker); self.assertTrue(terminated)
        self.assertLess(duration,6000)
        record=runner._read_json(folder/'process.json')
        self.assertIsNone(record['exitCode']); self.assertTrue(record['terminated'])


class ArchivedAssessmentTest(unittest.TestCase):
    def setUp(self):
        BatchTest.setUp(self)

    def archive(self, *, fault=None, rejection=False, fixed_rubric=True):
        proof = judge_test.JudgeTest(); proof.setUp()
        self.addCleanup(proof.doCleanups)
        proof.records[3]['replyKind'] = 'FREE_TEXT'
        proof.case['manualRubric']['criteria'] = [dict(criterionId='FACTS', question='核对事实', requiredFacts=[], forbiddenClaims=[])]
        if rejection:
            proof.case['turns'] = proof.case['turns'][:1]
            proof.case['expect']['outcome'] = 'NOT_SUBMITTED'
            proof.case['expect']['orders']['order-a'] = dict(orderStatus='RECEIVED', newRefundRows=0, refundAmount=None, ownerMatches=True)
            proof.after = copy.deepcopy(proof.before); proof.worker['terminalEvidence'] = 'NOT_SENT'
            proof.events = [judge_test.event(1, 'FACTS', 'REJECTED', turn=0), judge_test.event(2, 'SESSION', turn=0)]
            proof.records = [judge_test.record('REPLY', 0, 'flow-2', replyKind='TRUSTED_TEMPLATE'),
                judge_test.record('FINAL_REPLY', 0, 'reply-session-a-0', file='reply-0.txt')]
            proof.replies = ['订单 ' + judge_test.ORDER + ' 当前不可退：已有申请。本次未提交退款。']
            if fixed_rubric:
                proof.case['manualRubric']['criteria'] = [dict(criterionId=key, question='核对', requiredFacts=[], forbiddenClaims=[])
                    for key in ('REJECTION_FACTS', 'REJECTION_CLAIMS')]
        if fault == 'FAIL': proof.after['orders']['order-a']['refundRows'][0]['amount'] = '39.81'
        if fault == 'UNKNOWN': proof.worker['terminalEvidence'] = proof.after['terminalEvidence'] = 'UNKNOWN'
        if fault == 'ERROR': proof.after = {}
        self.cases[0] = proof.case
        _write_suite(self.root / 'suite', 'pilot', self.cases)
        state = runner._initialize_batch(self.manifest, self.run, False)
        trial = state['trials'][0]; directory = self.run / trial['trialId']; directory.mkdir()
        proof.root = directory / 'work'; proof.root.mkdir()
        proof.worker.update(runId=state['runId'], trialId=trial['trialId'])
        proof.bindings.update(runId=state['runId'], trialId=trial['trialId'])
        for event in proof.events: event.update(runId=state['runId'], trialId=trial['trialId'])
        proof.save()
        with runner.evidence_scope(proof.root):
            first = runner.judge(proof.case, proof.events, proof.before, proof.after, proof.worker, None)
        metadata = dict(caseId=trial['caseId'], trialKind='FIRST', repeatIndex=0, events=proof.events,
            worker=proof.worker, before=proof.before, after=proof.after, workerDurationMs=4, fixtureDurationMs=7)
        for name, value in (('result.json', first), ('metadata.json', metadata), ('case.json', proof.case),
                ('worker.json', proof.worker), ('before.json', proof.before), ('after.json', proof.after)):
            runner._exclusive_json(directory / name, value)
        trial.update(state='COMPLETE', terminated=True, terminalEvidence=proof.worker['terminalEvidence'])
        if fault == 'UNKNOWN': state['stopReason'] = 'UNRESOLVED_WRITE'
        runner._atomic_json(self.run / 'batch.json', state)
        ledger = runner.BudgetLedger(self.root / 'eval/runs/budget-ledger.json')
        if fault == 'UNKNOWN': ledger.reserve(state['runId'] + '.' + trial['trialId'])
        self.state, self.trial, self.directory, self.first, self.ledger = state, trial, directory, first, ledger
        return proof

    def audit(self, criteria):
        value = dict(caseId=self.trial['caseId'], trialId=self.trial['trialId'], criteria=criteria)
        (self.run / 'manual-audit.jsonl').write_text(json.dumps(value) + '\n', encoding='utf-8')

    def source_bytes(self):
        files = [p for p in self.run.rglob('*') if p.is_file() and 'assessments' not in p.relative_to(self.run).parts]
        files += [self.root / 'eval/runs/budget-ledger.json']
        return {p: p.read_bytes() for p in files if p.exists()}

    def assess(self, output=None):
        old = self.source_bytes()
        with patch.object(runner, '_helper', side_effect=AssertionError('helper action')), \
             patch.object(runner, '_run_worker', side_effect=AssertionError('worker/model action')), \
             patch.object(runner, '_read_env_file', side_effect=AssertionError('credential read')), \
             patch.object(runner.BudgetLedger, 'reserve', side_effect=AssertionError('budget reservation')):
            value = runner._assess_saved_run(self.run, output or self.root / ('summary-' + str(time.time_ns()) + '.json'))
        for path, raw in old.items(): self.assertEqual(raw, path.read_bytes(), path)
        return json.loads(Path(value['resultsFile']).read_text(encoding='utf-8'))['results'][0], value

    def test_late_human_pass_changes_saved_report_without_original_or_action_changes(self):
        self.archive(); self.audit({'FACTS': 'PASS'}); old = self.source_bytes()
        self.assertEqual(('SKIPPED', 'PASS', 'PENDING'),
            (self.first['status'], self.first['automaticStatus'], self.first['manualReview']))
        with patch.object(runner, '_helper', side_effect=AssertionError('helper action')), \
             patch.object(runner, '_run_worker', side_effect=AssertionError('worker/model action')), \
             patch.object(runner, '_read_env_file', side_effect=AssertionError('credential read')):
            saved = runner._saved_report(self.state, self.run, self.cases, runner._read_json(self.manifest), self.ledger)
        report = json.loads(Path(saved['reportFile']).read_text(encoding='utf-8'))
        self.assertEqual(1, report['liveFirst']['passed'])
        self.assertEqual(0, report['liveFirst']['pending'])
        for path, raw in old.items(): self.assertEqual(raw, path.read_bytes(), path)

    def test_actual_late_pass_fail_absent_and_invalid_criteria_use_real_judge(self):
        self.archive()
        for criteria, expected, review in ((None, 'SKIPPED', 'PENDING'), ({'FACTS':'PASS'}, 'PASS', 'PASS'),
                ({'FACTS':'FAIL'}, 'FAIL', 'FAIL'), ({'UNDECLARED':'PASS'}, 'ERROR', 'PENDING'), ({}, 'SKIPPED', 'PENDING')):
            with self.subTest(criteria=criteria):
                if criteria is not None: self.audit(criteria)
                result, _ = self.assess()
                self.assertEqual((expected, 'PASS', review), (result['status'], result['automaticStatus'], result['manualReview']))

    def test_human_pass_never_waives_original_automatic_failure_error_or_unknown(self):
        for fault, status in (('FAIL', 'FAIL'), ('ERROR', 'ERROR'), ('UNKNOWN', 'ERROR')):
            with self.subTest(fault=fault):
                # Each subcase gets an independent frozen run and real judge trace.
                self.run = self.root / 'eval/runs' / ('archive-' + fault.lower())
                self.archive(fault=fault); self.audit({'FACTS':'PASS'})
                result, _ = self.assess()
                self.assertEqual(status, result['status'])
                self.assertEqual(self.first['automaticStatus'], result['automaticStatus'])
                if fault == 'UNKNOWN': self.assertEqual(12, self.ledger.snapshot()['chargedRequests'])

    def test_missing_private_or_worker_evidence_never_passes(self):
        self.archive(); self.audit({'FACTS':'PASS'})
        (self.directory / 'work/evidence.json').unlink()
        result, _ = self.assess(); self.assertEqual('ERROR', result['status'])
        (self.directory / 'worker.json').unlink()
        result, _ = self.assess(); self.assertEqual('ERROR', result['status'])

    def test_frozen_hash_or_actual_trial_identity_mismatch_is_refused(self):
        self.archive(); self.audit({'FACTS':'PASS'})
        path = self.run / 'cases.json'; original = path.read_bytes()
        value = runner._read_json(path); value['cases'][0]['variationRationale'] = 'changed'
        path.write_text(json.dumps(value), encoding='utf-8')
        with self.assertRaises(ValueError): self.assess()
        path.write_bytes(original)
        value = runner._read_json(self.directory / 'worker.json'); value['trialId'] = 'other'
        (self.directory / 'worker.json').write_text(json.dumps(value), encoding='utf-8')
        result, _ = self.assess(); self.assertNotEqual('PASS', result['status'])

    def test_invalid_human_envelope_and_source_output_overwrite_are_refused(self):
        self.archive(); self.audit({'FACTS':'PASS'})
        value = runner._read_json(self.run / 'manual-audit.jsonl'); value['model'] = 'self-audit'
        (self.run / 'manual-audit.jsonl').write_text(json.dumps(value) + '\n', encoding='utf-8')
        with self.assertRaises(ValueError): self.assess()
        self.audit({'FACTS':'PASS'})
        with self.assertRaises(ValueError): self.assess(self.directory / 'result.json')

    def test_dynamic_refusal_requires_actual_fixed_fact_and_claim_audits(self):
        self.archive(rejection=True)
        self.audit({'REJECTION_FACTS':'PASS'})
        result, _ = self.assess(); self.assertEqual('PENDING', result['manualReview'])
        self.audit({'REJECTION_FACTS':'PASS', 'REJECTION_CLAIMS':'PASS'})
        result, _ = self.assess(); self.assertEqual('PASS', result['status'])
        self.run = self.root / 'eval/runs/generic-refusal'
        self.archive(rejection=True, fixed_rubric=False); self.audit({'FACTS':'PASS'})
        result, _ = self.assess(); self.assertEqual('PENDING', result['manualReview'])
        self.assertNotEqual('PASS', result['status'])

    def test_real_cli_emits_sanitized_markdown_and_json_from_saved_evidence(self):
        self.archive(); self.audit({'FACTS':'PASS'}); old = self.source_bytes()
        for suffix in ('.md', '.json'):
            output = self.root / ('cli-report' + suffix)
            completed = subprocess.run([sys.executable, str(ROOT / 'scripts/summarize_evaluation.py'),
                '--run-dir', str(self.run), '--output', str(output)], capture_output=True, text=True, check=False)
            self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)
            report = output.read_text(encoding='utf-8')
            if suffix == '.md':
                self.assertTrue(report.startswith('# Evaluation assessment\n'))
                self.assertIn('| LIVE_E2E | 1 |', report)
            else: self.assertEqual(1, json.loads(report)['liveFirst']['passed'])
            for sensitive in (judge_test.ORDER, 'secret-sentinel-token', 'PRIVATE-NO', 'refundRows', 'reply-1.txt'):
                self.assertNotIn(sensitive, report)
        for path, raw in old.items(): self.assertEqual(raw, path.read_bytes(), path)


class ValidationSelectionTest(unittest.TestCase):
    def setUp(self):
        BatchTest.setUp(self)
        self.cases = runner.contract.load_suite(ROOT / 'eval/scenarios/v2/manifest.pilot.json')
        self.manifest = _write_suite(self.root / 'suite', 'pilot', self.cases)
        value = runner._read_json(self.manifest); value['suiteVersion'] = 'v2-pilot'
        runner._atomic_json(self.manifest, value)
        originals = runner.contract.load_suite(ROOT / 'eval/scenarios/v1/manifest.pilot.json')
        source_manifest = _write_suite(self.root / 'source-suite', 'pilot', originals)
        self.source = self.root / 'eval/runs/source-run'
        self.source_state = runner._initialize_batch(source_manifest, self.source, False)
        defects = {'NORMAL-008': 'MISSING_EVIDENCE', 'ADVERSARIAL-003': 'OWNER',
            'INFO-019': 'UNBOUND_TARGET', 'REVIEW-001': 'REVIEW', 'REVIEW-013': 'REVIEW'}
        sources = []
        self.source_trials = {}
        for trial in self.source_state['trials']:
            if trial['caseId'] not in defects: continue
            trial.update(state='COMPLETE', terminated=True, terminalEvidence='NOT_SENT')
            directory = self.source / trial['trialId']; directory.mkdir()
            result = runner._error_result(self.source.name, trial, defects[trial['caseId']])
            if trial['caseId'] == 'ADVERSARIAL-003':
                result.update(status='FAIL', automaticStatus='FAIL', failedCriteria=['OWNER', 'MANUAL_REQUIRED'])
            runner._exclusive_json(directory / 'result.json', result)
            runner._exclusive_json(directory / 'process.json', dict(exitCode=0, terminated=True, durationMs=1))
            self.source_trials[trial['caseId']] = trial
            sources.append(dict(caseId=trial['caseId'], trialId=trial['trialId']))
        runner._atomic_json(self.source / 'batch.json', self.source_state)
        self.spec = dict(schemaVersion=1, reason='infrastructure:FIX4-FIX5-contract-correction',
            sourceRunId=self.source.name, sources=sources)
        self.selection_path = self.root / 'selection.json'
        runner._exclusive_json(self.selection_path, self.spec)
        token = runner._OPTIONS.set({'validation_selection': self.selection_path})
        self.addCleanup(runner._OPTIONS.reset, token)

    def source_bytes(self):
        return {p: p.read_bytes() for p in self.source.rglob('*') if p.is_file()}

    def dry(self):
        with patch.object(runner, '_helper', side_effect=AssertionError('helper touched')), \
             patch.object(runner, '_read_env_file', side_effect=AssertionError('credentials touched')), \
             patch.object(runner, '_run_worker', side_effect=AssertionError('worker touched')):
            return runner.run_batch(self.manifest, self.run, False, False)

    def test_fix5_selection_dry_run_and_cli_plan_only_five_of_full_frozen_suite(self):
        before = self.source_bytes()
        result = self.dry()
        self.assertEqual((5, 32, 27), (result['plannedTrials'], result.get('fullSuitePlannedTrials'), len(result.get('undispatchedCaseIds', []))))
        self.assertFalse(result['fullSuiteCoverageComplete'])
        self.assertFalse(self.run.exists())
        output = io.StringIO()
        with patch('sys.stdout', output):
            native = runner.main(['--manifest', str(self.manifest), '--run-dir', str(self.run),
                '--validation-selection', str(self.selection_path)])
        self.assertEqual(0, native)
        self.assertEqual(5, json.loads(output.getvalue())['plannedTrials'])
        self.assertEqual(before, self.source_bytes())

    def test_fix5_selection_rejects_wrong_version_ids_reason_and_supplement_mix(self):
        for change in ({'reason': 'semantic:retry'}, {'reason': 'infrastructure:'}, {'schemaVersion': True},
                {'extra': 1}, {'sourceRunId': '../source-run'}, {'sources': self.spec['sources'][:-1]},
                {'sources': self.spec['sources'] + [self.spec['sources'][0]]},
                {'sources': [dict(caseId='NORMAL-004', trialId='trial-bad'), *self.spec['sources'][1:]]}):
            with self.subTest(change=change):
                runner._atomic_json(self.selection_path, {**self.spec, **change})
                with self.assertRaises(ValueError): self.dry()
        runner._atomic_json(self.selection_path, self.spec)
        manifest = runner._read_json(self.manifest)
        for version in ('v1-pilot', 'v2-full'):
            runner._atomic_json(self.manifest, {**manifest, 'suiteVersion': version})
            with self.assertRaises(ValueError): self.dry()
        runner._atomic_json(self.manifest, manifest)
        with self.assertRaises(ValueError):
            runner.run_batch(self.manifest, self.run, False, True, 'NORMAL-008', 'infrastructure:timeout')
        self.assertFalse(self.run.exists())

    def test_fix5_selection_rejects_semantic_failures_unknown_and_unverified_source(self):
        trial = self.source_trials['NORMAL-008']
        result_path = self.source / trial['trialId'] / 'result.json'
        result = runner._read_json(result_path)
        for change in ({'status': 'PASS', 'automaticStatus': 'PASS'},
                {'status': 'FAIL', 'automaticStatus': 'FAIL'}, {'failedCriteria': ['AMOUNT']},
                {'runId': 'wrong-source'}, {'trialId': 'wrong-trial'}):
            runner._atomic_json(result_path, {**result, **change})
            with self.subTest(change=change), self.assertRaises(ValueError): self.dry()
        runner._atomic_json(result_path, result)
        owner = self.source_trials['ADVERSARIAL-003']
        owner_path = self.source / owner['trialId'] / 'result.json'; old_owner = runner._read_json(owner_path)
        runner._atomic_json(owner_path, {**old_owner, 'failedCriteria': ['OWNER', 'AMOUNT']})
        with self.assertRaises(ValueError): self.dry()
        runner._atomic_json(owner_path, old_owner)
        for change in ({'terminated': False}, {'terminated': 1}, {'terminalEvidence': 'UNKNOWN'},
                {'state': 'INFLIGHT'}, {'trialKind': 'SUPPLEMENT'}):
            state = copy.deepcopy(self.source_state)
            next(t for t in state['trials'] if t['trialId'] == trial['trialId']).update(change)
            runner._atomic_json(self.source / 'batch.json', state)
            with self.subTest(change=change), self.assertRaises(ValueError): self.dry()
        state = copy.deepcopy(self.source_state); state['trials'][0]['state'] = 'INFLIGHT'
        runner._atomic_json(self.source / 'batch.json', state)
        with self.assertRaises(ValueError): self.dry()
        runner._atomic_json(self.source / 'batch.json', self.source_state)
        process = self.source / trial['trialId'] / 'process.json'
        runner._atomic_json(process, dict(exitCode=None, terminated=False, durationMs=1))
        with self.assertRaises(ValueError): self.dry()
        self.assertFalse(self.run.exists())

    def test_fix5_selection_freezes_source_and_resume_cannot_omit_change_or_expand(self):
        state = runner._initialize_batch(self.manifest, self.run, False)
        self.assertIn('validationSelection', state)
        self.assertEqual(state['validationSelection'], runner._read_json(self.run / 'validation-selection.json'))
        self.assertEqual(state, runner._initialize_batch(self.manifest, self.run, True))
        with self.assertRaises(ValueError):
            runner._supplement(state, self.run, 'NORMAL-008', 'infrastructure:timeout')
        for change in ({'reason': 'infrastructure:changed'}, {'sources': self.spec['sources'][:-1]},
                {'sources': list(reversed(self.spec['sources']))}):
            runner._atomic_json(self.selection_path, {**self.spec, **change})
            with self.subTest(change=change), self.assertRaises(ValueError):
                runner._initialize_batch(self.manifest, self.run, True)
        runner._atomic_json(self.selection_path, self.spec)
        token = runner._OPTIONS.set({})
        try:
            with self.assertRaises(ValueError): runner._initialize_batch(self.manifest, self.run, True)
        finally: runner._OPTIONS.reset(token)
        runner._atomic_json(self.source / 'batch.json', {**self.source_state, 'diagnosticChange': True})
        with self.assertRaises(ValueError): runner._initialize_batch(self.manifest, self.run, True)

    def test_fix5_normal_runner_dispatches_and_reserves_selected_trials_only(self):
        before = self.source_bytes(); calls = []
        def execute(case, trial, state, run_dir, client, backend, model, url, ledger, manual):
            calls.append(case['caseId'])
            key = state['runId'] + '.' + trial['trialId']; ledger.reserve(key)
            folder = run_dir / trial['trialId']; folder.mkdir(); (folder / 'work').mkdir()
            for name, value in (('case.json', case), ('result.json', runner._error_result(state['runId'], trial, 'MISSING_EVIDENCE')),
                    ('metadata.json', dict(caseId=case['caseId'], trialKind='FIRST', repeatIndex=0,
                        events=[], worker={}, before={}, after={},
                        workerDurationMs=None if case.get('control', {}).get('target') == 'BACKEND_TRANSACTION' else 1, fixtureDurationMs=1))):
                runner._exclusive_json(folder / name, value)
            ledger.complete(key, dict(logicalModelRequests=0, promptTokens=None, completionTokens=None,
                totalTokens=None, usageComplete=True, unknownUsageRequests=0), True)
            trial.update(state='COMPLETE', terminated=True, terminalEvidence='NOT_SENT')
            runner._atomic_json(run_dir / 'batch.json', state)
        helper = BatchTest.fixture_helper(self, [])
        with patch.object(runner, '_helper', return_value=(helper, self.root / 'backend')), \
             patch.object(runner, '_read_env_file', return_value={}), patch.object(runner, '_execute_trial', side_effect=execute):
            result = runner.run_batch(self.manifest, self.run, True, False)
        self.assertEqual([s['caseId'] for s in self.spec['sources']], calls)
        self.assertEqual((5, 5, 32, 27), (result['plannedTrials'], result['completedTrials'],
            result['fullSuitePlannedTrials'], len(result['undispatchedCaseIds'])))
        state = runner._read_json(self.run / 'batch.json')
        self.assertEqual(27, sum(t['state'] == 'PENDING' for t in state['trials']))
        budget = runner._read_json(self.root / 'eval/runs/budget-ledger.json')
        self.assertEqual({state['runId'] + '.' + t['trialId'] for t in state['trials'] if t['state'] == 'COMPLETE'}, set(budget['trials']))
        report = runner._read_json(result['reportFile'])
        self.assertEqual(5, report['validationSelection']['selectedCompletedTrials'])
        self.assertFalse(report['validationSelection']['fullSuiteCoverageComplete'])
        self.assertEqual(before, self.source_bytes())


class FixtureProtocolTest(unittest.TestCase):
    def setUp(self): self.assertIsNotNone(FixtureClient, 'Task10 strict fixture client is missing')

    def client(self, code, root):
        return FixtureClient([sys.executable, '-u', '-c', code], cwd=root,
            environment={key: value for key, value in os.environ.items() if key in ('SystemRoot', 'PATH', 'TEMP', 'TMP')},
            stderr_path=root / 'helper.stderr', timeout=2)

    def test_persistent_ndjson_validates_each_actual_reply(self):
        code = 'import sys,json\nfor line in sys.stdin:\n r=json.loads(line); print(json.dumps(dict(schemaVersion=1,op=r["op"],status="COMPLETED")),flush=True)\n if r["op"]=="shutdown": break'
        with tempfile.TemporaryDirectory() as folder:
            with self.client(code, Path(folder)) as client:
                request = dict(schemaVersion=1, op='preflight', runId='run', caseId='NORMAL-001', trialId='one')
                self.assertEqual('COMPLETED', client.request(request)['status'])
                self.assertEqual('COMPLETED', client.request({**request, 'op': 'shutdown'})['status'])
            self.assertTrue(client.terminated)

    def test_wrong_operation_duplicate_key_and_trailing_output_are_rejected(self):
        bad = ['{"schemaVersion":1,"op":"shutdown","status":"COMPLETED"}',
               '{"schemaVersion":1,"schemaVersion":1,"op":"preflight","status":"COMPLETED"}',
               '{"schemaVersion":1,"op":"preflight","status":"COMPLETED"} extra']
        for reply in bad:
            with self.subTest(reply=reply), tempfile.TemporaryDirectory() as folder:
                code = 'import sys\nsys.stdin.readline()\nprint(' + repr(reply) + ',flush=True)'
                with self.client(code, Path(folder)) as client:
                    with self.assertRaises(ValueError): client.request(dict(schemaVersion=1, op='preflight', runId='run', caseId='NORMAL-001', trialId='one'))

    def test_owned_tree_closes_child_before_budget_can_be_released(self):
        from scripts.evaluation_fixture_client import OwnedProcess
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder); marker=root/'escaped-child.txt'
            child='import time,pathlib; time.sleep(.8); pathlib.Path('+repr(str(marker))+').write_text("escaped")'
            parent='import subprocess,sys,time; p=subprocess.Popen([sys.executable,"-c",'+repr(child)+']); print("CHILD_STARTED",flush=True); time.sleep(60)'
            with (root/'stdout').open('w') as stdout,(root/'stderr').open('w') as stderr:
                owned=OwnedProcess([sys.executable,'-u','-c',parent],cwd=root,environment={k:v for k,v in os.environ.items() if k in ('SystemRoot','PATH','TEMP','TMP')},stdout=stdout,stderr=stderr)
                deadline=time.monotonic()+3
                try:
                    while 'CHILD_STARTED' not in (root/'stdout').read_text() and time.monotonic()<deadline: time.sleep(.02)
                    self.assertIn('CHILD_STARTED',(root/'stdout').read_text())
                finally: terminated=owned.close()
            self.assertTrue(terminated)
            time.sleep(1)
            self.assertFalse(marker.exists(),'owned child escaped process-tree termination')


if __name__ == '__main__': unittest.main()
