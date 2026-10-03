"""Pilot data and saved, actual-worker counterexample acceptance checks."""
import hashlib
import importlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime
from collections import Counter
from pathlib import Path
from unittest.mock import patch

from scripts.evaluation_contract import load_suite

ROOT = Path(__file__).resolve().parents[1]
SUITE = ROOT / 'eval/scenarios/v1'
EVIDENCE = ROOT / 'agent/target/evaluation-counterexamples'
IDS = {
    'NORMAL': [f'NORMAL-{n:03}' for n in range(1, 9)],
    'POLICY_CONFIRMATION': [f'BOUNDARY-{n:03}' for n in range(1, 7)] + ['BOUNDARY-041', 'BOUNDARY-042'],
    'ADVERSARIAL': [f'ADVERSARIAL-{n:03}' for n in range(1, 5)] + ['ADVERSARIAL-021', 'ADVERSARIAL-022'],
    'ABNORMAL': [f'FAULT-{n:03}' for n in range(1, 5)],
    'KNOWLEDGE': ['INFO-001', 'INFO-009', 'INFO-019', 'INFO-029'],
    'INDEPENDENT_REVIEW': ['REVIEW-001', 'REVIEW-013'],
}


class PilotScenariosTest(unittest.TestCase):
    def cases(self):
        self.assertTrue((SUITE / 'manifest.pilot.json').is_file(), 'Task11 pilot manifest is missing')
        return load_suite(SUITE / 'manifest.pilot.json')

    def test_fix5_v2_changes_only_shared_synthetic_time_facts(self):
        target = ROOT / 'eval/scenarios/v2'
        self.assertTrue((target / 'manifest.pilot.json').is_file(), 'Frozen v2 pilot missing')
        original = {c['caseId']: c for c in self.cases()}
        revised = load_suite(target / 'manifest.pilot.json')
        self.assertEqual(list(original), [c['caseId'] for c in revised])
        pair = []
        for case in revised:
            old = original[case['caseId']]
            if case['caseId'] not in ('REVIEW-001', 'REVIEW-013'):
                self.assertEqual(old, case)
                continue
            stripped = json.loads(json.dumps(case))
            stripped['reviewInput']['trustedOrder'] = old['reviewInput']['trustedOrder']
            stripped['reviewInput']['trustedEligibility'] = old['reviewInput']['trustedEligibility']
            self.assertEqual(old, stripped)
            order = json.loads(case['reviewInput']['trustedOrder'])
            eligibility = json.loads(case['reviewInput']['trustedEligibility'])
            self.assertEqual('2026-09-29T10:00:00', order['createdAt'])
            self.assertEqual('2026-10-01T10:00:00', eligibility['evaluationTime'])
            duration = datetime.fromisoformat(eligibility['evaluationTime']) - datetime.fromisoformat(order['createdAt'])
            self.assertEqual(48 * 3600, duration.total_seconds())
            self.assertEqual(2, eligibility['completeDays'])
            self.assertEqual(duration.days, eligibility['completeDays'])
            self.assertLessEqual(duration.days, 7)
            self.assertIs(True, order['synthetic'])
            self.assertIs(True, eligibility['synthetic'])
            self.assertEqual('Asia/Shanghai', eligibility['businessClock'])
            self.assertEqual('SEVEN_DAY_NO_REASON', eligibility['policyCode'])
            self.assertEqual({}, case['fixture'])
            self.assertEqual([], case['turns'])
            pair.append(case['reviewInput'])
        self.assertEqual(2, len(pair))
        self.assertEqual(pair[0]['trustedOrder'], pair[1]['trustedOrder'])
        self.assertEqual(pair[0]['trustedEligibility'], pair[1]['trustedEligibility'])
        self.assertNotEqual(pair[0]['originalUserRequest'], pair[1]['originalUserRequest'])
        for name in ('normal', 'boundary', 'adversarial', 'fault', 'information'):
            self.assertEqual((SUITE / (name + '.jsonl')).read_bytes(), (target / (name + '.jsonl')).read_bytes())

    def test_fix5_v2_frozen_hashes_survive_autocrlf_git_checkout(self):
        source = ROOT / 'eval/scenarios/v2'
        with tempfile.TemporaryDirectory() as temporary:
            repository = Path(temporary) / 'repository'; repository.mkdir()
            destination = repository / 'eval/scenarios/v2'; destination.mkdir(parents=True)
            (repository / '.gitattributes').write_bytes((ROOT / '.gitattributes').read_bytes())
            for entry in source.iterdir():
                if entry.suffix in ('.jsonl', '.json'): (destination / entry.name).write_bytes(entry.read_bytes())
            def git(*args):
                completed = subprocess.run(['git', '-C', str(repository), *args], capture_output=True, check=False)
                self.assertEqual(0, completed.returncode, 'temporary checkout Git native exit')
            git('init', '--quiet')
            git('config', 'core.autocrlf', 'true')
            git('add', '--', '.gitattributes', 'eval/scenarios/v2')
            exported = Path(temporary) / 'exported'; exported.mkdir()
            git('checkout-index', '--all', '--prefix=' + str(exported) + os.sep)
            checked = exported / 'eval/scenarios/v2'
            for original in source.glob('*.jsonl'):
                self.assertEqual(hashlib.sha256(original.read_bytes()).hexdigest(),
                    hashlib.sha256((checked / original.name).read_bytes()).hexdigest(), original.name)
            self.assertEqual(32, len(load_suite(checked / 'manifest.pilot.json')))

    def test_fix5_v2_rejects_missing_reversed_inconsistent_time_and_stale_hashes(self):
        target = ROOT / 'eval/scenarios/v2'
        self.assertTrue((target / 'manifest.pilot.json').is_file(), 'Frozen v2 pilot missing')
        for field, value in (('createdAt', None), ('createdAt', '2026-10-02T10:00:00'),
                ('evaluationTime', None), ('completeDays', 1), ('completeDays', True), ('businessClock', None),
                ('synthetic', False), ('policyCode', 'QUALITY_ISSUE')):
            with self.subTest(field=field, value=value), tempfile.TemporaryDirectory() as temporary:
                copied = Path(temporary) / 'v2'; shutil.copytree(target, copied)
                path = copied / 'review.jsonl'
                cases = [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines()]
                for case in cases:
                    key = 'trustedOrder' if field == 'createdAt' else 'trustedEligibility'
                    facts = json.loads(case['reviewInput'][key])
                    if value is None: facts.pop(field)
                    else: facts[field] = value
                    case['reviewInput'][key] = json.dumps(facts, ensure_ascii=False)
                path.write_text('\n'.join(json.dumps(c, ensure_ascii=False) for c in cases) + '\n', encoding='utf-8')
                with self.assertRaisesRegex(ValueError, 'sha256'): load_suite(copied / 'manifest.pilot.json')
                manifest = json.loads((copied / 'manifest.pilot.json').read_text())
                next(f for f in manifest['files'] if f['path'] == 'review.jsonl')['sha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
                (copied / 'manifest.pilot.json').write_text(json.dumps(manifest), encoding='utf-8')
                with self.assertRaisesRegex(ValueError, 'timing'): load_suite(copied / 'manifest.pilot.json')

    def test_pilot_counts_ids_and_modes(self):
        cases = self.cases()
        self.assertEqual(32, len(cases))
        self.assertEqual({'NORMAL': 8, 'POLICY_CONFIRMATION': 8, 'ADVERSARIAL': 6,
                          'ABNORMAL': 4, 'KNOWLEDGE': 4, 'INDEPENDENT_REVIEW': 2},
                         dict(Counter(c['category'] for c in cases)))
        self.assertEqual({'LIVE_E2E': 22, 'CONTROLLED': 8, 'REVIEW_ONLY': 2},
                         dict(Counter(c['mode'] for c in cases)))
        for category, ids in IDS.items():
            self.assertEqual(ids, [c['caseId'] for c in cases if c['category'] == category])

    def test_missing_case_and_changed_bytes_are_rejected(self):
        self.cases()
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / 'suite'
            shutil.copytree(SUITE, target)
            normal = target / 'normal.jsonl'
            normal.write_bytes(b''.join(normal.read_bytes().splitlines(keepends=True)[1:]))
            with self.assertRaisesRegex(ValueError, 'sha256'):
                load_suite(target / 'manifest.pilot.json')
            manifest = json.loads((target / 'manifest.pilot.json').read_text())
            manifest['files'][0]['sha256'] = hashlib.sha256(normal.read_bytes()).hexdigest()
            (target / 'manifest.pilot.json').write_text(json.dumps(manifest))
            with self.assertRaises(ValueError):
                load_suite(target / 'manifest.pilot.json')

    def test_refund_and_boundary_facts_match_execution_semantics(self):
        cases = {c['caseId']: c for c in self.cases()}
        for number, status, code in [(1, 'RECEIVED', 'SEVEN_DAY_NO_REASON'), (2, 'SHIPPED', 'SHIPPED_NOT_RECEIVED'),
                                    (3, 'DELIVERED', 'SHIPPED_NOT_RECEIVED'), (4, 'RECEIVED', 'QUALITY_ISSUE'),
                                    (5, 'RECEIVED', 'SEVEN_DAY_NO_REASON')]:
            case = cases[f'NORMAL-{number:03}']
            self.assertEqual(status, case['fixture']['orders']['order-a']['status'])
            self.assertEqual('REFUND_COMPLETED', case['expect']['outcome'])
            self.assertEqual([code], case['expect']['policyCodes'])
            self.assertEqual('/confirm-refund {{order-a}}', case['turns'][-1]['input'])
        self.assertEqual(12 * 86400, cases['NORMAL-004']['fixture']['orders']['order-a']['ageSeconds'])
        self.assertEqual(689400, cases['BOUNDARY-001']['fixture']['orders']['order-a']['ageSeconds'])
        self.assertEqual(693000, cases['BOUNDARY-002']['fixture']['orders']['order-a']['ageSeconds'])
        self.assertEqual(['QUALITY_ISSUE'], cases['BOUNDARY-002']['expect']['policyCodes'])
        self.assertEqual('REFUND_COMPLETED', cases['ADVERSARIAL-004']['expect']['outcome'])
        for identifier in ('BOUNDARY-003', 'BOUNDARY-004'):
            criterion_ids = {c['criterionId'] for c in cases[identifier]['manualRubric']['criteria']}
            self.assertTrue({'REJECTION_FACTS', 'REJECTION_CLAIMS'}.issubset(criterion_ids))

    def test_controls_name_the_actual_supported_boundaries(self):
        cases = {c['caseId']: c for c in self.cases()}
        expected = {'BOUNDARY-041': ('BACKEND_TRANSACTION', 'BACKEND_PROBE', 'LEGACY_PENDING'),
                    'BOUNDARY-042': ('BACKEND_TRANSACTION', 'BACKEND_PROBE', 'STALE_POLICY'),
                    'FAULT-001': ('BACKEND_TRANSACTION', 'BACKEND_PROBE', 'ROLLBACK_AFTER_INSERT'),
                    'FAULT-002': ('BACKEND_TRANSACTION', 'BACKEND_PROBE', 'CONCURRENT_IDEMPOTENCY'),
                    'FAULT-003': ('AGENT_CHAIN', 'MCP_BEFORE_REQUEST', None),
                    'FAULT-004': ('AGENT_CHAIN', 'MCP_AFTER_RESPONSE', None),
                    'ADVERSARIAL-021': ('AGENT_CHAIN', 'MODEL_SCRIPT', None),
                    'ADVERSARIAL-022': ('AGENT_CHAIN', 'MCP_RESPONSE', None)}
        self.assertEqual(set(expected), {c['caseId'] for c in cases.values() if c['mode'] == 'CONTROLLED'})
        for identifier, wanted in expected.items():
            control = cases[identifier]['control']
            self.assertEqual(wanted, (control['target'], control['point'], control.get('probe')))
        self.assertEqual('submit_refund', cases['FAULT-003']['control']['toolName'])
        self.assertEqual('submit_refund', cases['FAULT-004']['control']['toolName'])

    def test_knowledge_uses_versioned_sources_and_history_limit(self):
        cases = {c['caseId']: c for c in self.cases()}
        self.assertEqual(['FAQ-002'], cases['INFO-009']['expect']['requiredSources'])
        self.assertEqual('DEMO_READONLY', cases['INFO-019']['fixture']['products']['product-a']['source'])
        self.assertEqual('paper_grid_a5', cases['INFO-019']['fixture']['products']['product-a']['logicalKey'])
        self.assertEqual(['PRODUCT:product-a'], cases['INFO-019']['expect']['requiredSources'])
        self.assertEqual('ANSWERED', cases['INFO-029']['expect']['outcome'])
        self.assertNotIn('requiredSources', cases['INFO-029']['expect'])
        self.assertTrue(any('历史' in item['question'] for item in cases['INFO-029']['manualRubric']['criteria']))

    def test_review_inputs_are_fixed_synthetic_with_no_executor(self):
        reviews = [c for c in self.cases() if c['mode'] == 'REVIEW_ONLY']
        self.assertEqual(['REVIEW_REJECTED', 'REVIEW_APPROVED'], [c['expect']['outcome'] for c in reviews])
        for case in reviews:
            self.assertEqual({}, case['fixture'])
            self.assertEqual([], case['turns'])
            self.assertTrue(case['reviewInput']['synthetic'])
            self.assertIn('EXECUTOR_AVAILABLE', case['expect']['forbiddenEvents'])

    def test_all_cases_have_specific_basis_and_manual_criteria(self):
        for case in self.cases():
            with self.subTest(case=case['caseId']):
                self.assertTrue(case['variationRationale'].strip())
                self.assertTrue(case['expect']['basis'])
                self.assertGreaterEqual(len(case['manualRubric']['criteria']), 2)
                self.assertTrue(all(c['requiredFacts'] or c['forbiddenClaims']
                                    for c in case['manualRubric']['criteria']))


class CounterexamplesTest(unittest.TestCase):
    def validator(self):
        self.assertTrue((ROOT / 'scripts/validate_evaluation_counterexamples.py').is_file(),
                        'Task11 counterexample validator is missing')
        return importlib.import_module('scripts.validate_evaluation_counterexamples')

    def exported(self):
        self.assertTrue((EVIDENCE / 'baseline/trial.json').is_file(),
                        'Run EvaluationHarnessTest to export actual-worker synthetic evidence first')
        return EVIDENCE

    def test_actual_worker_baseline_passes_and_all_four_fail_for_their_causes(self):
        result = self.validator().validate_counterexamples(self.exported())
        self.assertTrue(result['ok'], result)
        self.assertEqual('PASS', result['results']['baseline']['status'])
        for identifier, criterion in [('missing-confirmation', 'SUBMIT_BEFORE_CONFIRMATION'),
                                      ('deleted-review', 'SUBMIT_WITHOUT_REVIEW'),
                                      ('unbound-target', 'UNBOUND_TARGET'),
                                      ('missing-oracle', 'MISSING_EVIDENCE')]:
            self.assertNotEqual('PASS', result['results'][identifier]['status'])
            self.assertIn(criterion, result['results'][identifier]['failedCriteria'])

    def test_missing_any_counterexample_is_nonzero(self):
        validator = self.validator()
        for identifier in validator.COUNTEREXAMPLES:
            with self.subTest(counterexample=identifier), tempfile.TemporaryDirectory() as temporary:
                target = Path(temporary) / 'evidence'
                shutil.copytree(self.exported(), target)
                (target / identifier / 'trial.json').unlink()
                self.assertFalse(validator.validate_counterexamples(target)['ok'])
                result = subprocess.run([sys.executable, str(ROOT / 'scripts/validate_evaluation_counterexamples.py'),
                                         '--evidence-dir', str(target)], capture_output=True, text=True)
                self.assertNotEqual(0, result.returncode)

    def test_baseline_reused_as_counterexample_cannot_pass_validation(self):
        validator = self.validator()
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / 'evidence'
            shutil.copytree(self.exported(), target)
            shutil.rmtree(target / 'missing-confirmation')
            shutil.copytree(target / 'baseline', target / 'missing-confirmation')
            self.assertFalse(validator.validate_counterexamples(target)['ok'])

    def test_validator_calls_existing_judge_for_each_artifact(self):
        validator = self.validator()
        with patch.object(validator, 'judge', wraps=validator.judge) as actual:
            self.assertTrue(validator.validate_counterexamples(self.exported())['ok'])
            self.assertEqual(5, actual.call_count)

    def test_missing_private_proof_and_nonpassing_baseline_are_rejected(self):
        validator = self.validator()
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / 'evidence'
            shutil.copytree(self.exported(), target)
            (target / 'baseline/worker/evidence.json').unlink()
            result = validator.validate_counterexamples(target)
            self.assertFalse(result['ok'])
            self.assertEqual('ERROR', result['results']['baseline']['status'])

    def test_missing_any_worker_or_reply_file_including_missing_oracle_is_nonzero(self):
        validator = self.validator()
        for name in ('baseline', *validator.COUNTEREXAMPLES):
            for filename in ('events.jsonl', 'evidence.json', 'binding-input.json', 'reply-session-a-1.txt'):
                with self.subTest(artifact=name, filename=filename), tempfile.TemporaryDirectory() as temporary:
                    target = Path(temporary) / 'evidence'; shutil.copytree(self.exported(), target)
                    (target / name / 'worker' / filename).unlink()
                    self.assertFalse(validator.validate_counterexamples(target)['ok'])


if __name__ == '__main__':
    unittest.main()
