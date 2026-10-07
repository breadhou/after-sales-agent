import copy
import hashlib
import json
import shutil
import tempfile
import unittest
from pathlib import Path

from scripts.evaluation_contract import validate_wire
from scripts.evaluation_judge import judge, evidence_scope

ROOT = Path(__file__).resolve().parents[1]
CODE = 'SEVEN_DAY_NO_REASON'
BODY = '收到商品七日内可申请整单退款。'
DIGEST = hashlib.sha256(BODY.encode()).hexdigest()
ORDER = '9007199254741001'


def event(sequence, phase, status='COMPLETED', turn=1, target='order-a', **values):
    value = dict(runId='run-1', caseId='NORMAL-001', trialId='trial-1',
                 sessionAlias='session-a', turnIndex=turn, sequence=sequence,
                 callId='flow-' + str(sequence), target=target, phase=phase,
                 role='ORCHESTRATOR', tool=None, status=status, businessCode=None,
                 policyCode=None, policyFingerprint=None, sourceKey=None, sourceDigest=None,
                 durationMs=1, promptTokens=None, completionTokens=None, totalTokens=None)
    value.update(values)
    return value


def record(kind, turn, call_id, **values):
    return dict(kind=kind, sessionAlias='session-a', turnIndex=turn, callId=call_id, **values)


class JudgeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.case = json.loads((ROOT / 'eval/fixtures/case-contract.json').read_text(encoding='utf-8-sig'))['templates']['live']
        self.case['turns'][1]['input'] = '/confirm-refund {{order-a}}'
        self.case['expect']['policyCodes'] = [CODE]
        self.case['manualRubric']['criteria'] = []
        self.events = [event(1, 'CONFIRMATION', 'STARTED', turn=0),
                       event(2, 'SESSION', turn=0), event(3, 'CONFIRMATION'),
                       event(4, 'FACTS', policyCode=CODE, policyFingerprint='fp-1'),
                       event(5, 'POLICY', policyCode=CODE, policyFingerprint='fp-1', sourceKey=CODE, sourceDigest=DIGEST),
                       event(6, 'EXPLANATION', target='GLOBAL', role='EXPLANATION', sourceKey=CODE, sourceDigest=DIGEST),
                       event(7, 'REVIEW', role='REVIEW', policyCode=CODE),
                       event(8, 'EXECUTION', 'STARTED', policyCode=CODE, policyFingerprint='fp-1'),
                       event(9, 'MCP', 'CALLED', callId='mcp-1', role='MCP', tool='submit_refund', policyCode=CODE, policyFingerprint='fp-1'),
                       event(10, 'MCP', 'RESPONSE_RECEIVED', callId='mcp-1', role='MCP', tool='submit_refund', policyCode=CODE, policyFingerprint='fp-1'),
                       event(11, 'EXECUTION'), event(12, 'SESSION')]
        self.records = [record('REPLY', 0, 'flow-2', replyKind='TRUSTED_TEMPLATE'),
                        record('SOURCE', 1, 'source-6', sourceKey=CODE, sourceDigest=DIGEST, text=BODY),
                        record('REVIEW_OUTCOME', 1, 'flow-7', outcome='APPROVED'),
                        record('REPLY', 1, 'flow-12', replyKind='TRUSTED_TEMPLATE'),
                        record('FINAL_REPLY', 0, 'reply-session-a-0', file='reply-0.txt'),
                        record('FINAL_REPLY', 1, 'reply-session-a-1', file='reply-1.txt')]
        self.replies = ['请确认退款申请：订单 ' + ORDER + '；理由：买错了。确认请单独输入 /confirm-refund ' + ORDER + '。本次未提交退款。',
                        '订单 ' + ORDER + ' 的退款已完成，退款金额 39.8 元，订单状态已更新为 REFUNDED。本次合规复核和退款执行均已完成。']
        self.before = dict(terminalEvidence='NOT_SENT', orders={'order-a': dict(orderStatus='RECEIVED', paidAmount='39.80', refundRows=[], ownerMatches=True)})
        self.after = dict(terminalEvidence='COMPLETED', orders={'order-a': dict(orderStatus='REFUNDED', paidAmount='39.8', refundRows=[dict(status='REFUNDED', amount='39.80', ownerMatches=True)], ownerMatches=True)})
        self.worker = dict(schemaVersion=1, runId='run-1', caseId=self.case['caseId'], trialId='trial-1', eventsFile='events.jsonl', privateEvidenceFile='evidence.json', terminalEvidence='COMPLETED', errorCategory=None,
                           metering=dict(logicalModelRequests=0, promptTokens=None, completionTokens=None, totalTokens=None, usageComplete=True, unknownUsageRequests=0))
        self.bindings = dict(schemaVersion=1, runId='run-1', caseId=self.case['caseId'], trialId='trial-1', activeActor='actor-a', actors={'actor-a': {'userId': '9007199254740993', 'userToken': 'secret-sentinel-token'}},
                             orders={'order-a': {'orderId': ORDER, 'orderNo': 'PRIVATE-NO'}}, products={'product-a': {'productId': '9007199254741011', 'skus': {'sku-a': '9007199254741021'}}})

    def save(self):
        (self.root / 'events.jsonl').write_text(''.join(json.dumps(e, ensure_ascii=False) + '\n' for e in self.events), encoding='utf-8')
        (self.root / 'evidence.json').write_text(json.dumps(dict(schemaVersion=1, records=self.records), ensure_ascii=False), encoding='utf-8')
        (self.root / 'binding-input.json').write_text(json.dumps(self.bindings), encoding='utf-8')
        for i, reply in enumerate(self.replies):
            (self.root / ('reply-' + str(i) + '.txt')).write_text(reply, encoding='utf-8')

    def run_judge(self, manual=None):
        self.save()
        with evidence_scope(self.root):
            return judge(self.case, self.events, self.before, self.after, self.worker, manual)

    def test_real_private_records_decimal_and_exact_large_id_pass(self):
        value = self.run_judge()
        self.assertEqual('PASS', value['status'], value)
        self.assertEqual(value, validate_wire('TrialResult', value))
        self.assertEqual(8, len(value))
        self.assertNotIn(ORDER, json.dumps(value))
        self.assertNotIn('secret-sentinel', json.dumps(value))

    def test_zero_after_unknown_submit_is_unresolved(self):
        self.worker['terminalEvidence'] = self.after['terminalEvidence'] = 'UNKNOWN'
        self.after['orders']['order-a']['refundRows'] = []
        result = self.run_judge()
        self.assertEqual('ERROR', result['status'])
        self.assertIn('UNRESOLVED_WRITE', result['failedCriteria'])
        self.assertNotIn('errorCategory', result)

    def test_unknown_cannot_be_waived_by_expected_unresolved(self):
        self.case['expect']['outcome'] = 'UNRESOLVED_WRITE'
        self.worker['terminalEvidence'] = 'UNKNOWN'
        self.assertEqual('ERROR', self.run_judge()['status'])

    def test_actual_headers_cannot_be_inferred_from_intent(self):
        for field in ('policyCode', 'policyFingerprint'):
            with self.subTest(field=field):
                saved = copy.deepcopy(self.events)
                self.events[8][field] = self.events[9][field] = None
                self.assertNotEqual('PASS', self.run_judge()['status'])
                self.events = saved

    def test_actual_wrong_code_or_fingerprint_fail(self):
        for field in ('policyCode', 'policyFingerprint'):
            with self.subTest(field=field):
                saved = copy.deepcopy(self.events)
                self.events[8][field] = self.events[9][field] = 'WRONG'
                self.assertEqual('FAIL', self.run_judge()['status'])
                self.events = saved

    def test_outcome_call_correlation_cannot_cross_target(self):
        self.events[9]['target'] = 'product-a'
        self.assertNotEqual('PASS', self.run_judge()['status'])

    def test_without_confirmation_or_valid_review_fails(self):
        for phase in ('CONFIRMATION', 'REVIEW', 'POLICY'):
            with self.subTest(phase=phase):
                saved = copy.deepcopy(self.events)
                self.events = [e for e in self.events if not (e['phase'] == phase and e['turnIndex'] == 1)]
                for i, e in enumerate(self.events): e['sequence'] = i + 1
                self.assertNotEqual('PASS', self.run_judge()['status'])
                self.events = saved

    def test_review_exception_not_real_rejection(self):
        self.case['expect']['outcome'] = 'REVIEW_REJECTED'
        self.records[2]['outcome'] = 'ERROR'
        self.events[6]['status'] = 'FAILED'
        self.assertNotEqual('PASS', self.run_judge()['status'])

    def test_confirmation_other_session_or_turn_cannot_authorize(self):
        for field, value in [('sessionAlias', 'session-b'), ('turnIndex', 0)]:
            with self.subTest(field=field):
                saved = self.events[2][field]
                self.events[2][field] = value
                self.assertNotEqual('PASS', self.run_judge()['status'])
                self.events[2][field] = saved

    def test_cancelled_before_submit_fails(self):
        self.events.insert(3, event(0, 'CONFIRMATION', 'SKIPPED'))
        for i, e in enumerate(self.events): e['sequence'] = i + 1
        self.assertEqual('FAIL', self.run_judge()['status'])

    def test_two_order_no_nearest_target_inference(self):
        self.case['fixture']['orders']['order-b'] = copy.deepcopy(self.case['fixture']['orders']['order-a'])
        self.bindings['orders']['order-b'] = {'orderId': '9007199254741003', 'orderNo': 'PRIVATE-B'}
        self.before['orders']['order-b'] = copy.deepcopy(self.before['orders']['order-a'])
        self.after['orders']['order-b'] = copy.deepcopy(self.before['orders']['order-a'])
        self.events[8]['target'] = self.events[9]['target'] = 'order-b'
        self.assertEqual('FAIL', self.run_judge()['status'])

    def test_owner_paid_amount_new_rows_and_pending_are_checked(self):
        for mutation in ('owner', 'amount', 'paid', 'rows', 'pending'):
            with self.subTest(mutation=mutation):
                saved = copy.deepcopy(self.after)
                order = self.after['orders']['order-a']
                if mutation == 'owner': order['refundRows'][0]['ownerMatches'] = False
                if mutation == 'amount': order['refundRows'][0]['amount'] = '39.81'
                if mutation == 'paid': order['paidAmount'] = '39.81'
                if mutation == 'rows': order['refundRows'].append(copy.deepcopy(order['refundRows'][0]))
                if mutation == 'pending': order['refundRows'][0]['status'] = 'PENDING'
                self.assertEqual('FAIL', self.run_judge()['status'])
                self.after = saved

    def test_missing_oracle_is_error(self):
        self.after = {}
        self.assertEqual('ERROR', self.run_judge()['status'])

    def test_legacy_pending_same_row_is_not_new_completion(self):
        self.case['fixture']['orders']['order-a']['existingRefund'] = 'PENDING'
        self.before['orders']['order-a']['refundRows'] = [{'status': 'PENDING', 'amount': '39.8', 'ownerMatches': True}]
        self.assertNotEqual('PASS', self.run_judge()['status'])

    def test_forged_fixed_template_cannot_pass(self):
        self.replies[1] += ' 已经到账。'
        self.assertEqual('FAIL', self.run_judge()['status'])

    def test_free_text_requires_human_criteria_even_empty_rubric(self):
        self.records[3]['replyKind'] = 'FREE_TEXT'
        self.replies[1] = '看起来已经退款。'
        result = self.run_judge()
        self.assertEqual('PASS', result['automaticStatus'])
        self.assertEqual('PENDING', result['manualReview'])
        self.assertNotEqual('PASS', result['status'])

    def test_human_pass_fail_and_model_self_audit_rejected(self):
        self.records[3]['replyKind'] = 'FREE_TEXT'
        self.case['manualRubric']['criteria'] = [dict(criterionId='FACTS', question='核对事实', requiredFacts=[], forbiddenClaims=[])]
        for conclusion in ('PASS', 'FAIL'):
            with self.subTest(conclusion=conclusion):
                manual = dict(caseId='NORMAL-001', trialId='trial-1', criteria={'FACTS': conclusion})
                self.assertEqual(conclusion, self.run_judge(manual)['status'])
        forged = dict(caseId='NORMAL-001', trialId='trial-1', criteria={'FACTS': 'PASS'}, model='self')
        self.assertNotEqual('PASS', self.run_judge(forged)['status'])
        self.assertNotEqual('PASS', self.run_judge(dict(caseId='NORMAL-001', trialId='other', criteria={'FACTS': 'PASS'}))['status'])

    def source_reply(self):
        self.case['expect']['outcome'] = 'ANSWERED'
        self.case['expect']['orders']['order-a'] = dict(orderStatus='RECEIVED', newRefundRows=0, refundAmount=None, ownerMatches=True)
        self.case['expect']['requiredSources'] = ['FAQ-001']
        self.case['manualRubric']['criteria'] = [dict(criterionId='SOURCE_FACT', question='原文核对', requiredFacts=['本次未提交退款'], forbiddenClaims=['已到账'])]
        self.case['turns'] = self.case['turns'][:1]
        body = '本次未提交退款，请核对订单。'
        digest = hashlib.sha256(body.encode()).hexdigest()
        self.events = [event(1, 'EXPLANATION', turn=0, target='GLOBAL', role='EXPLANATION', sourceKey='FAQ-001', sourceDigest=digest), event(2, 'EXPLANATION', turn=0, target='GLOBAL', role='EXPLANATION', sourceKey='FAQ-001', sourceDigest=digest), event(3, 'EXPLANATION', turn=0, target='GLOBAL', role='EXPLANATION')]
        self.records = [record('SOURCE', 0, 'source-2', sourceKey='FAQ-001', sourceDigest=digest, text=body), record('REPLY', 0, 'flow-3', replyKind='SOURCE_ORIGINAL'), record('FINAL_REPLY', 0, 'reply-session-a-0', file='reply-0.txt')]
        self.replies = ['[FAQ-001] ' + body + '\n补充说明：请以所引资料原文为准。']
        self.after = copy.deepcopy(self.before)
        self.worker['terminalEvidence'] = 'NOT_SENT'

    def test_source_original_body_digest_fields_are_exact(self):
        self.source_reply()
        self.assertEqual('PASS', self.run_judge()['status'])
        for mutation in ('body', 'digest', 'source', 'fact', 'supplement'):
            with self.subTest(mutation=mutation):
                self.source_reply()
                if mutation == 'body': self.replies[0] = self.replies[0].replace('请核对订单', '已退款')
                if mutation == 'digest': self.records[0]['sourceDigest'] = '0' * 64
                if mutation == 'source': self.records[0]['sourceKey'] = 'FAQ-999'
                if mutation == 'fact': self.case['manualRubric']['criteria'][0]['requiredFacts'] = ['退款金额39.8']
                if mutation == 'supplement': self.replies[0] += ' 已经到账。'
                self.assertNotEqual('PASS', self.run_judge()['status'])

    def test_paths_scope_manifest_and_extra_fields_cannot_be_guessed(self):
        self.save()
        self.assertNotEqual('PASS', judge(self.case, self.events, self.before, self.after, self.worker, None)['status'])
        with evidence_scope(self.root):
            self.records[-1]['file'] = '../external.txt'
            self.save()
            self.assertNotEqual('PASS', judge(self.case, self.events, self.before, self.after, self.worker, None)['status'])
        self.records[-1]['file'] = 'reply-1.txt'
        self.records[0]['rawReason'] = 'secret-sentinel'
        self.assertNotEqual('PASS', self.run_judge()['status'])

    def test_saved_events_must_match_and_scope_resets(self):
        self.save()
        with evidence_scope(self.root):
            changed = copy.deepcopy(self.events); changed[-1]['durationMs'] = 99
            self.assertNotEqual('PASS', judge(self.case, changed, self.before, self.after, self.worker, None)['status'])
        self.assertNotEqual('PASS', judge(self.case, self.events, self.before, self.after, self.worker, None)['status'])

    def test_policy_source_body_must_be_verified_even_without_source_answer(self):
        self.records[1]['text'] = '伪造政策正文'
        self.assertNotEqual('PASS', self.run_judge()['status'])

    def test_required_source_cannot_be_satisfied_by_no_basis_template(self):
        self.source_reply()
        self.records[1]['replyKind'] = 'TRUSTED_TEMPLATE'
        self.replies[0] = '当前无可靠依据回答该资料问题，请联系人工客服核实。'
        self.assertNotEqual('PASS', self.run_judge()['status'])

    def test_expected_controlled_known_receipt_loss_can_pass_but_unknown_cannot(self):
        self.case['mode'] = 'CONTROLLED'
        self.case['control'] = dict(target='AGENT_CHAIN', point='MCP_AFTER_RESPONSE', components=dict(DIALOGUE='REAL', REVIEW='REAL', EXPLANATION='REAL', MCP='SUBSTITUTED', BACKEND='REAL'), usesRealModel=True, toolName='submit_refund', response='injected private loss')
        self.case['expect']['outcome'] = 'UNRESOLVED_WRITE'
        self.events[10]['status'] = 'FAILED'
        self.records.append(record('ERROR', 1, 'flow-11', errorCategory='UNRESOLVED_WRITE'))
        self.worker['errorCategory'] = 'UNRESOLVED_WRITE'
        self.replies[1] = '订单 ' + ORDER + ' 的退款申请提交结果无法确认。请联系人工客服核实退款状态。'
        self.assertEqual('PASS', self.run_judge()['status'])
        self.worker['terminalEvidence'] = self.after['terminalEvidence'] = 'UNKNOWN'
        self.assertEqual('ERROR', self.run_judge()['status'])

    def test_expected_chain_stale_rejection_does_not_count_as_unauthorized_write(self):
        self.case['mode'] = 'CONTROLLED'
        self.case['control'] = dict(target='AGENT_CHAIN', point='MCP_RESPONSE', components=dict(DIALOGUE='REAL', REVIEW='REAL', EXPLANATION='REAL', MCP='SUBSTITUTED', BACKEND='REAL'), usesRealModel=True, toolName='submit_refund', response='fixed stale response')
        self.case['expect']['outcome'] = 'NOT_SUBMITTED'
        self.case['expect']['orders']['order-a'] = dict(orderStatus='RECEIVED', newRefundRows=0, refundAmount=None, ownerMatches=True)
        self.after = copy.deepcopy(self.before); self.after['terminalEvidence'] = 'COMPLETED'
        self.events[9].update(status='BUSINESS_ERROR', businessCode=50005)
        self.events[10].update(status='REJECTED', businessCode=50005)
        self.replies[1] = '订单 ' + ORDER + ' 的复核依据已过期，本次未产生新退款记录；请联系人工客服核实。'
        self.assertEqual('PASS', self.run_judge()['status'])
        self.events[9]['businessCode'] = 99999
        self.assertNotEqual('PASS', self.run_judge()['status'])

    def test_real_selection_template_uses_actual_returned_targets(self):
        self.case['turns'] = self.case['turns'][:1]
        self.case['expect']['outcome'] = 'NEEDS_ORDER_SELECTION'
        self.case['expect']['orders']['order-a'] = dict(orderStatus='RECEIVED', newRefundRows=0, refundAmount=None, ownerMatches=True)
        self.after = copy.deepcopy(self.before); self.worker['terminalEvidence'] = 'NOT_SENT'
        self.events = [event(1, 'MCP', 'CALLED', turn=0, target='GLOBAL', role='MCP', tool='list_user_orders', callId='mcp-1'), event(2, 'MCP', 'RESPONSE_RECEIVED', turn=0, target='GLOBAL', role='MCP', tool='list_user_orders', callId='mcp-1'), event(3, 'SESSION', turn=0, target='GLOBAL')]
        self.records = [record('RETURNED_TARGETS', 0, 'mcp-1', aliases=['order-a']), record('REPLY', 0, 'flow-3', replyKind='TRUSTED_TEMPLATE'), record('FINAL_REPLY', 0, 'reply-session-a-0', file='reply-0.txt')]
        self.replies = ['请先从本会话列出的订单中选择退款目标：\n订单 ' + ORDER + '：/select-refund-order ' + ORDER + '\n候选理由：买错了。选择后仍需确认；本次未提交退款。']
        self.assertEqual('PASS', self.run_judge()['status'])
        self.records[0]['aliases'] = ['UNBOUND']
        self.assertNotEqual('PASS', self.run_judge()['status'])

    def test_code_eligibility_template_and_dynamic_target_are_bound(self):
        self.case['turns'] = self.case['turns'][:1]
        self.case['expect']['outcome'] = 'NOT_SUBMITTED'
        self.case['expect']['orders']['order-a'] = dict(orderStatus='RECEIVED', newRefundRows=0, refundAmount=None, ownerMatches=True)
        self.after = copy.deepcopy(self.before); self.worker['terminalEvidence'] = 'NOT_SENT'
        self.events = [event(1, 'MCP', 'CALLED', turn=0, tool='get_refund_eligibility', role='MCP', callId='mcp-1'), event(2, 'MCP', 'RESPONSE_RECEIVED', turn=0, tool='get_refund_eligibility', role='MCP', callId='mcp-1'), event(3, 'SESSION', turn=0)]
        self.records = [record('REPLY', 0, 'flow-3', replyKind='TRUSTED_TEMPLATE'), record('FINAL_REPLY', 0, 'reply-session-a-0', file='reply-0.txt')]
        self.replies = ['订单 ' + ORDER + ' 当前查询显示可申请退款；本次未提交退款。']
        self.assertEqual('PASS', self.run_judge()['status'])
        self.events[-1]['target'] = 'GLOBAL'
        self.assertNotEqual('PASS', self.run_judge()['status'])

    def test_fix_i1_missing_direct_calls_never_pass(self):
        self.test_intentionally_stale_direct_request_uses_frozen_actual_arguments()
        self.events = []
        self.assertNotEqual('PASS', self.run_judge()['status'])

    def test_fix_i2_waiting_confirmation_cannot_perform_review(self):
        self.case['turns'] = self.case['turns'][:1]
        self.case['expect']['outcome'] = 'NEEDS_CONFIRMATION'
        self.case['expect']['orders']['order-a'] = dict(orderStatus='RECEIVED', newRefundRows=0, refundAmount=None, ownerMatches=True)
        self.after = copy.deepcopy(self.before); self.worker['terminalEvidence'] = 'NOT_SENT'
        self.events = self.events[:2] + [event(3, 'REVIEW', turn=0, role='REVIEW', policyCode=CODE)]
        self.records = [self.records[0], self.records[-2], record('REVIEW_OUTCOME', 0, 'flow-3', outcome='APPROVED')]
        self.replies = self.replies[:1]
        self.assertNotEqual('PASS', self.run_judge()['status'])

    def test_fix_i3_later_veto_cannot_be_overridden_by_old_approval(self):
        self.events.insert(7, event(0, 'REVIEW', 'REJECTED', role='REVIEW', policyCode=CODE, callId='veto-1'))
        self.records.append(record('REVIEW_OUTCOME', 1, 'veto-1', outcome='REJECTED'))
        for i, e in enumerate(self.events): e['sequence'] = i + 1
        self.assertNotEqual('PASS', self.run_judge()['status'])

    def test_fix_i3_matching_real_double_checkpoints_agree_but_conflicts_never_authorize(self):
        self.events.insert(6, event(0, 'REVIEW', role='REVIEW', policyCode=None, callId='agent-review'))
        self.records.append(record('REVIEW_OUTCOME', 1, 'agent-review', outcome='APPROVED'))
        for i, e in enumerate(self.events): e['sequence'] = i+1
        # The earlier policy body checkpoint remains source-6; the final reply moved to sequence13.
        self.assertEqual('PASS', self.run_judge()['status'])
        baseline = copy.deepcopy((self.events, self.records))
        for verdict, status in (('REJECTED','REJECTED'), ('INVALID','FAILED'), ('ERROR','FAILED'), ('APPROVED','COMPLETED')):
            with self.subTest(verdict=verdict):
                self.events, self.records = copy.deepcopy(baseline)
                self.events.insert(8, event(0, 'REVIEW', status, role='REVIEW', callId='last-review', policyCode='WRONG' if verdict == 'APPROVED' else CODE))
                self.records.append(record('REVIEW_OUTCOME', 1, 'last-review', outcome=verdict))
                for i, e in enumerate(self.events): e['sequence'] = i+1
                self.assertNotEqual('PASS', self.run_judge()['status'])

    def test_fix_i2_selection_cancel_mismatch_and_prior_valid_refund_origins(self):
        self.test_real_selection_template_uses_actual_returned_targets()
        self.records[0]['aliases'] = ['order-a']
        self.events.append(event(4, 'REVIEW', turn=0, role='REVIEW', policyCode=CODE))
        self.records.append(record('REVIEW_OUTCOME', 0, 'flow-4', outcome='APPROVED'))
        self.assertNotEqual('PASS', self.run_judge()['status'])
        for status, command, reply in [('SKIPPED', '/cancel-refund', '本次待确认退款申请已取消，未提交退款。'),
                                      ('REJECTED', '/confirm-refund wrong', '确认格式无效，待确认申请已取消；请重新提出退款申请。')]:
            self.case['expect']['outcome'] = 'CANCELLED'
            self.case['turns'][0]['input'] = command
            self.events = [event(1, 'CONFIRMATION', status, turn=0), event(2, 'SESSION', turn=0)]
            self.records = [record('REPLY',0,'flow-2',replyKind='TRUSTED_TEMPLATE'),record('FINAL_REPLY',0,'reply-session-a-0',file='reply-0.txt')]
            self.replies = [reply]
            self.assertEqual('PASS', self.run_judge()['status'])
            self.events.append(event(3, 'REVIEW', turn=0, role='REVIEW', policyCode=CODE))
            self.records.append(record('REVIEW_OUTCOME',0,'flow-3',outcome='APPROVED'))
            self.assertNotEqual('PASS', self.run_judge()['status'])
            self.events[-1]['sessionAlias'] = 'other-session'; self.records[-1]['sessionAlias'] = 'other-session'
            self.assertNotEqual('PASS', self.run_judge()['status'])

    def test_fix_i2_prior_legitimate_refund_is_retained_when_current_turn_cancels(self):
        self.case['turns'].append(dict(sessionAlias='session-a', actorAlias='actor-a', input='/cancel-refund'))
        self.case['expect']['outcome'] = 'CANCELLED'
        self.events += [event(13,'CONFIRMATION','SKIPPED',turn=2,target='GLOBAL'),event(14,'SESSION',turn=2,target='GLOBAL')]
        self.records += [record('REPLY',2,'flow-14',replyKind='TRUSTED_TEMPLATE'),record('FINAL_REPLY',2,'reply-session-a-2',file='reply-2.txt')]
        self.replies.append('本次待确认退款申请已取消，未提交退款。')
        self.assertEqual('PASS', self.run_judge()['status'])

    def test_fix_i4_production_ineligible_reason_needs_audit_not_system_fail(self):
        self.case['turns'] = self.case['turns'][:1]
        self.case['expect']['outcome'] = 'NOT_SUBMITTED'
        self.case['expect']['orders']['order-a'] = dict(orderStatus='RECEIVED', newRefundRows=0, refundAmount=None, ownerMatches=True)
        self.after = copy.deepcopy(self.before); self.worker['terminalEvidence'] = 'NOT_SENT'
        self.events = [event(1, 'FACTS', 'REJECTED', turn=0), event(2, 'SESSION', turn=0)]
        self.records = [record('REPLY', 0, 'flow-2', replyKind='TRUSTED_TEMPLATE'), record('FINAL_REPLY', 0, 'reply-session-a-0', file='reply-0.txt')]
        self.replies = ['订单 ' + ORDER + ' 当前不可退：该订单已有退款申请在处理中。本次未提交退款。']
        result = self.run_judge()
        self.assertEqual('PENDING', result['manualReview'])
        self.assertNotIn('REPLY_TEMPLATE', result['failedCriteria'])
        self.assertNotEqual('PASS', result['status'])

    def test_reply_symlink_outside_scope_is_refused(self):
        self.save()
        external = self.root.parent / (self.root.name + '-outside.txt')
        external.write_text(self.replies[1], encoding='utf-8')
        self.addCleanup(external.unlink)
        (self.root / 'reply-1.txt').unlink()
        try: (self.root / 'reply-1.txt').symlink_to(external)
        except OSError: self.skipTest('symlink unavailable')
        with evidence_scope(self.root):
            self.assertNotEqual('PASS', judge(self.case, self.events, self.before, self.after, self.worker, None)['status'])

    def test_intentionally_stale_direct_request_uses_frozen_actual_arguments(self):
        controlled = json.loads((ROOT / 'eval/fixtures/case-contract.json').read_text(encoding='utf-8-sig'))['templates']['controlled']['control']
        self.case['mode'] = 'CONTROLLED'; self.case['control'] = controlled
        self.case['control'].update(target='MCP_CONTRACT', point='NONE', usesRealModel=False)
        self.case['control']['components'] = dict(DIALOGUE='ABSENT', REVIEW='ABSENT', EXPLANATION='ABSENT', MCP='REAL', BACKEND='REAL')
        self.case['control'] = {k: v for k, v in self.case['control'].items() if k in ('target', 'point', 'usesRealModel', 'components')}
        self.case['control']['toolCalls'] = [dict(toolName='submit_refund', arguments=dict(orderId='{{order-a}}', reason='买错了', expectedPolicyCode=CODE, expectedCatalogFingerprint='intentional-stale'))]
        self.case['expect']['outcome'] = 'NOT_SUBMITTED'
        self.case['expect']['orders']['order-a'] = dict(orderStatus='RECEIVED', newRefundRows=0, refundAmount=None, ownerMatches=True)
        self.events = [event(1, 'MCP', 'CALLED', turn=0, tool='submit_refund', role='MCP', policyCode=CODE, policyFingerprint='intentional-stale', callId='mcp-1'), event(2, 'MCP', 'BUSINESS_ERROR', turn=0, tool='submit_refund', role='MCP', policyCode=CODE, policyFingerprint='intentional-stale', callId='mcp-1', businessCode=50005)]
        for e in self.events: e['sessionAlias'] = 'mcp-contract'
        self.records = [dict(kind='FINAL_REPLY', sessionAlias='mcp-contract', turnIndex=0, callId='reply-mcp-contract-0', file='reply-0.txt')]
        self.replies = [json.dumps(dict(error=True, code=50005, message='private-stale'))]
        self.records.append(dict(kind='SOURCE', sessionAlias='mcp-contract', turnIndex=0, callId='mcp-1',
                                 sourceKey='MCP_RESULT', text=self.replies[0], sourceDigest=hashlib.sha256(self.replies[0].encode()).hexdigest()))
        self.after = copy.deepcopy(self.before); self.after['terminalEvidence'] = 'COMPLETED'
        self.assertEqual('PASS', self.run_judge()['status'])
        self.events[0]['policyFingerprint'] = self.events[1]['policyFingerprint'] = 'fresh-but-not-actual'
        self.assertEqual('FAIL', self.run_judge()['status'])

    def test_fix_i1_original_direct_result_and_step_bindings_cannot_be_substituted(self):
        self.test_intentionally_stale_direct_request_uses_frozen_actual_arguments()
        self.events[0]['policyFingerprint'] = self.events[1]['policyFingerprint'] = 'intentional-stale'
        baseline = copy.deepcopy((self.events, self.records, self.replies))
        self.assertEqual('PASS', self.run_judge()['status'])
        for mutation in ('final', 'proof', 'target', 'outcome-tool', 'code', 'call-id', 'origin', 'duplicate', 'extra-outcome', 'missing-outcome'):
            with self.subTest(mutation=mutation):
                self.events, self.records, self.replies = copy.deepcopy(baseline)
                if mutation == 'final': self.replies[0] = '{"forged":"final"}'
                if mutation == 'proof': self.records.pop()
                if mutation == 'target': self.events[0]['target'] = self.events[1]['target'] = 'product-a'
                if mutation == 'outcome-tool': self.events[1]['tool'] = 'get_order'
                if mutation == 'code': self.events[1]['businessCode'] = 99999
                if mutation == 'call-id': self.records[-1]['callId'] = 'mcp-other'
                if mutation == 'origin': self.records[-1]['turnIndex'] = 1
                if mutation == 'duplicate': self.events.append({**self.events[0], 'sequence': 3, 'callId': 'mcp-extra'})
                if mutation == 'extra-outcome': self.events.append({**self.events[1], 'sequence': 3, 'callId': 'mcp-extra'})
                if mutation == 'missing-outcome': self.events.pop()
                self.assertNotEqual('PASS', self.run_judge()['status'])

    def test_fix_i1_two_actual_direct_steps_cannot_cross_bind_outcomes_or_proof(self):
        self.test_intentionally_stale_direct_request_uses_frozen_actual_arguments()
        self.events[0]['policyFingerprint']=self.events[1]['policyFingerprint']='intentional-stale'
        self.case['control']['toolCalls'].append(dict(toolName='get_order',arguments={'orderId':'{{order-a}}'}))
        for status in ('CALLED','RESPONSE_RECEIVED'):
            self.events.append({**event(len(self.events)+1,'MCP',status,turn=1,role='MCP',tool='get_order',callId='mcp-2'), 'sessionAlias':'mcp-contract'})
        self.replies.append('{"id":'+ORDER+'}')
        self.records += [dict(kind='FINAL_REPLY',sessionAlias='mcp-contract',turnIndex=1,callId='reply-mcp-contract-1',file='reply-1.txt'),
                         dict(kind='SOURCE',sessionAlias='mcp-contract',turnIndex=1,callId='mcp-2',sourceKey='MCP_RESULT',text=self.replies[1],sourceDigest=hashlib.sha256(self.replies[1].encode()).hexdigest())]
        self.assertEqual('PASS',self.run_judge()['status'])
        self.events[-1]['turnIndex']=0
        self.assertNotEqual('PASS',self.run_judge()['status'])
        self.events[-1]['turnIndex']=1
        self.records[-1]['text']=self.replies[0];self.records[-1]['sourceDigest']=hashlib.sha256(self.replies[0].encode()).hexdigest()
        self.assertNotEqual('PASS',self.run_judge()['status'])

    def test_fix_i3_review_only_structured_approve_and_reject_remain_valid(self):
        self.case=json.loads((ROOT/'eval/fixtures/case-contract.json').read_text(encoding='utf-8-sig'))['templates']['review']
        self.case['reviewInput']['candidateAction']['orderId']=ORDER
        self.worker.update(caseId=self.case['caseId'],terminalEvidence='NOT_SENT')
        self.bindings['caseId']=self.case['caseId']
        self.before=self.after=dict(terminalEvidence='NOT_SENT',orders={})
        code=self.case['reviewInput']['policyEvidence']['code'];body=self.case['reviewInput']['policyEvidence']['clauseText']
        digest=hashlib.sha256(body.encode()).hexdigest()
        for approved in (True,False):
            self.case['expect']['outcome']='REVIEW_APPROVED' if approved else 'REVIEW_REJECTED'
            self.events=[event(1,'EXPLANATION',turn=0,target='GLOBAL',role='EXPLANATION',sourceKey=code,sourceDigest=digest),
                         event(2,'REVIEW','COMPLETED' if approved else 'REJECTED',turn=0,role='REVIEW',policyCode=None)]
            for e in self.events: e.update(sessionAlias='review',caseId=self.case['caseId'])
            self.records=[dict(kind='SOURCE',sessionAlias='review',turnIndex=0,callId='source-1',sourceKey=code,text=body,sourceDigest=digest),
                          dict(kind='REVIEW_OUTCOME',sessionAlias='review',turnIndex=0,callId='flow-2',outcome='APPROVED' if approved else 'REJECTED'),
                          dict(kind='FINAL_REPLY',sessionAlias='review',turnIndex=0,callId='reply-review-0',file='reply-0.txt')]
            self.replies=[json.dumps(dict(approved=approved,citedPolicyCode=code,faults=[] if approved else [dict(category='POLICY_CONFLICT',evidence='Frozen clause conflict',policyCode=code)]))]
            self.assertEqual('PASS',self.run_judge()['status'])

    def review_rejection(self, category='USER_INSTRUCTION_RISK'):
        self.case=json.loads((ROOT/'eval/fixtures/case-contract.json').read_text(encoding='utf-8-sig'))['templates']['review']
        self.case['reviewInput']['candidateAction']['orderId']=ORDER
        self.case['expect']['outcome']='REVIEW_REJECTED'
        self.worker.update(caseId=self.case['caseId'],terminalEvidence='NOT_SENT')
        self.bindings['caseId']=self.case['caseId']
        self.before=self.after=dict(terminalEvidence='NOT_SENT',orders={})
        code=self.case['reviewInput']['policyEvidence']['code']; body=self.case['reviewInput']['policyEvidence']['clauseText']
        digest=hashlib.sha256(body.encode()).hexdigest()
        self.events=[event(1,'EXPLANATION',turn=0,target='GLOBAL',role='EXPLANATION',sourceKey=code,sourceDigest=digest),
                     event(2,'REVIEW','REJECTED',turn=0,role='REVIEW')]
        for observation in self.events: observation.update(sessionAlias='review',caseId=self.case['caseId'])
        self.records=[dict(kind='SOURCE',sessionAlias='review',turnIndex=0,callId='source-1',sourceKey=code,text=body,sourceDigest=digest),
                      dict(kind='REVIEW_OUTCOME',sessionAlias='review',turnIndex=0,callId='flow-2',outcome='REJECTED'),
                      dict(kind='FINAL_REPLY',sessionAlias='review',turnIndex=0,callId='reply-review-0',file='reply-0.txt')]
        self.replies=[json.dumps(dict(approved=False,citedPolicyCode=code,faults=[dict(category=category,evidence='Exact supplied evidence',policyCode=code)]))]

    def test_fix4_review_production_three_field_rejection_and_uncertain_are_valid(self):
        for category in ('USER_INSTRUCTION_RISK','UNCERTAIN'):
            with self.subTest(category=category):
                self.review_rejection(category)
                self.assertEqual('PASS',self.run_judge()['automaticStatus'])

    def test_fix4_valid_rejection_never_becomes_approval(self):
        self.review_rejection('UNCERTAIN')
        self.case['expect']['outcome']='REVIEW_APPROVED'
        result=self.run_judge()
        self.assertEqual('FAIL',result['automaticStatus'])
        self.assertIn('OUTCOME',result['failedCriteria'])

    def test_fix4_review_policy_category_types_and_closed_shape_remain_required(self):
        self.review_rejection()
        original=json.loads(self.replies[0])
        for mutation in ('missing-policy','wrong-policy','unknown-category','blank-evidence','wrong-evidence-type','extra-field','missing-field','wrong-approved-type','wrong-cited-policy','empty-refusal','fault-on-approval'):
            with self.subTest(mutation=mutation):
                verdict=copy.deepcopy(original); fault=verdict['faults'][0]
                if mutation=='missing-policy': fault.pop('policyCode')
                if mutation=='wrong-policy': fault['policyCode']='WRONG'
                if mutation=='unknown-category': fault['category']='OTHER'
                if mutation=='blank-evidence': fault['evidence']='  '
                if mutation=='wrong-evidence-type': fault['evidence']=True
                if mutation=='extra-field': fault['extra']='unexpected'
                if mutation=='missing-field': fault.pop('category')
                if mutation=='wrong-approved-type': verdict['approved']='false'
                if mutation=='wrong-cited-policy': verdict['citedPolicyCode']='WRONG'
                if mutation=='empty-refusal': verdict['faults']=[]
                if mutation=='fault-on-approval': verdict['approved']=True
                self.replies=[json.dumps(verdict)]
                self.assertNotEqual('PASS',self.run_judge()['automaticStatus'])

    def test_product_digest_and_final_freshness_are_not_text_hash_or_any_prior_read(self):
        self.product_source_reply()
        self.assertEqual('PASS', self.run_judge()['status'])
        self.events[0]['status'] = 'COMPLETED'
        self.assertNotEqual('PASS', self.run_judge()['status'])

    def product_source_reply(self):
        self.source_reply()
        body = '商品：Fixture notebook；当前描述：A fixture product used only by the contract tests.；规格：blue，当前价格：19.90，当前库存：5'
        canonical = dict(productId=9007199254741011, name='Fixture notebook', description='A fixture product used only by the contract tests.', skus=[dict(id=9007199254741021, specs='blue', price='19.90', stock=5)])
        digest = hashlib.sha256(json.dumps(canonical, ensure_ascii=False, separators=(',', ':')).encode()).hexdigest()
        self.case['expect']['requiredSources'] = ['PRODUCT:product-a']; self.case['manualRubric']['criteria'][0]['requiredFacts'] = ['当前价格：19.90', '当前库存：5']
        self.events = [event(1, 'EXPLANATION', 'STARTED', turn=0, target='product-a', role='EXPLANATION', sourceKey='PRODUCT:product-a', sourceDigest=digest), event(2, 'MCP', 'CALLED', turn=0, target='product-a', role='MCP', tool='get_product_detail', callId='mcp-1'), event(3, 'MCP', 'RESPONSE_RECEIVED', turn=0, target='product-a', role='MCP', tool='get_product_detail', callId='mcp-1'), event(4, 'EXPLANATION', turn=0, target='product-a', role='EXPLANATION', sourceKey='PRODUCT:product-a', sourceDigest=digest), event(5, 'EXPLANATION', turn=0, target='GLOBAL', role='EXPLANATION', sourceKey='PRODUCT:product-a', sourceDigest=digest), event(6, 'EXPLANATION', turn=0, target='GLOBAL', role='EXPLANATION')]
        self.records[0].update(sourceKey='PRODUCT:product-a', sourceDigest=digest, text=body, callId='source-5')
        self.records[1]['callId'] = 'flow-6'
        self.records.append(record('SOURCE', 0, 'mcp-1', sourceKey='PRODUCT_CANONICAL:product-a', sourceDigest=digest,
                                   text=json.dumps(canonical, ensure_ascii=False, separators=(',', ':'))))
        self.replies = ['以下仅为当前在售商品资料，不能证明下单时的描述。\n[PRODUCT-9007199254741011] ' + body]

    def product_discovery(self):
        self.product_source_reply()
        self.case['turns'][0]['input'] = '查询当前在售商品资料'
        prefix=[event(1,'EXPLANATION','STARTED',turn=0,target='GLOBAL',role='EXPLANATION'),
                event(2,'MCP','CALLED',turn=0,target='GLOBAL',role='MCP',tool='list_on_shelf_products',callId='mcp-list'),
                event(3,'MCP','RESPONSE_RECEIVED',turn=0,target='GLOBAL',role='MCP',tool='list_on_shelf_products',callId='mcp-list'),
                event(4,'MCP','RESPONSE_RECEIVED',turn=0,target='OUT_OF_ALLOWLIST',role='MCP',tool='list_on_shelf_products',callId='mcp-list'),
                event(5,'MCP','RESPONSE_RECEIVED',turn=0,target='product-a',role='MCP',tool='list_on_shelf_products',callId='mcp-list')]
        for observation in self.events:
            observation['sequence']+=len(prefix)
            if observation['callId'].startswith('flow-'): observation['callId']='flow-'+str(observation['sequence'])
        self.events=prefix+self.events
        self.records[0]['callId']='source-10'; self.records[1]['callId']='flow-11'
        self.records.append(record('RETURNED_TARGETS',0,'mcp-list',aliases=['OUT_OF_ALLOWLIST','product-a']))

    def test_fix4_trusted_public_product_discovery_can_filter_unbound_list_rows(self):
        self.product_discovery()
        self.assertEqual('PASS',self.run_judge()['automaticStatus'])

    def test_f2_bound_order_product_discovery_preserves_global_list_correlation(self):
        self.product_discovery()
        self.case['turns'][0]['input'] = '查询订单 {{order-a}} 中的商品当前资料'
        self.events[0]['target'] = 'order-a'
        self.assertEqual('PASS', self.run_judge()['automaticStatus'])

    def test_f2_order_discovery_rejects_wrong_unbound_stale_and_other_origin(self):
        self.product_discovery()
        self.case['turns'][0]['input'] = '查询订单 {{order-a}} 中的商品当前资料'
        self.events[0]['target'] = 'order-a'
        self.case['fixture']['orders']['order-b'] = copy.deepcopy(self.case['fixture']['orders']['order-a'])
        self.case['expect']['orders']['order-b'] = copy.deepcopy(self.case['expect']['orders']['order-a'])
        self.bindings['orders']['order-b'] = dict(orderId='9007199254741002', orderNo='OTHER-PRIVATE-NO')
        self.before['orders']['order-b'] = copy.deepcopy(self.before['orders']['order-a'])
        self.after['orders']['order-b'] = copy.deepcopy(self.after['orders']['order-a'])
        baseline = copy.deepcopy(self.events)
        for mutation in ('GLOBAL', 'order-b', 'product-a', 'UNBOUND', 'OUT_OF_ALLOWLIST', 'stale', 'origin'):
            with self.subTest(mutation=mutation):
                self.events = copy.deepcopy(baseline)
                if mutation == 'stale': self.events[0]['status'] = 'COMPLETED'
                elif mutation == 'origin': self.events[0]['sessionAlias'] = 'session-b'
                else: self.events[0]['target'] = mutation
                self.assertIn('UNBOUND_TARGET', self.run_judge()['failedCriteria'])

    def test_fix4_discovery_requires_real_correlated_trusted_list_evidence(self):
        self.product_discovery(); baseline=copy.deepcopy((self.events,self.records))
        for mutation in ('missing-start','failed-list','missing-primary','wrong-call','wrong-aliases','wrong-origin'):
            with self.subTest(mutation=mutation):
                self.events,self.records=copy.deepcopy(baseline)
                if mutation=='missing-start': self.events[0]['status']='COMPLETED'
                if mutation=='failed-list': self.events[2]['status']='BUSINESS_ERROR'
                if mutation=='missing-primary': self.events[2]['target']='product-a'
                if mutation=='wrong-call': self.events[3]['callId']='other-call'
                if mutation=='wrong-aliases': self.records[-1]['aliases']=['product-a']
                if mutation=='wrong-origin': self.records[-1]['turnIndex']=1
                self.assertIn('UNBOUND_TARGET',self.run_judge()['failedCriteria'])

    def test_fix4_discovery_never_allows_order_detail_reference_or_stale_proofs(self):
        self.product_discovery(); baseline=copy.deepcopy((self.events,self.records,self.replies))
        for mutation in ('order','detail','reference','stale'):
            with self.subTest(mutation=mutation):
                self.events,self.records,self.replies=copy.deepcopy(baseline)
                if mutation=='order': self.events[3].update(tool='get_order',target='UNBOUND')
                if mutation=='detail': self.events[6]['target']='OUT_OF_ALLOWLIST'
                if mutation=='reference': self.replies[0]=self.replies[0].replace('PRODUCT-9007199254741011','PRODUCT-9007199254749999')
                if mutation=='stale': self.events[5]['status']='COMPLETED'
                self.assertNotEqual('PASS',self.run_judge()['automaticStatus'])

    def readonly_rejection(self):
        self.case['turns']=self.case['turns'][:1]
        self.case['turns'][0]['input']='订单 {{order-a}} 现在可以退款吗？只查询资格'
        self.case['fixture']['orders']['order-a']['status']='PAID'
        self.case['expect']['outcome']='ANSWERED'
        self.case['expect']['orders']['order-a']=dict(orderStatus='PAID',newRefundRows=0,refundAmount=None,ownerMatches=True)
        self.case['manualRubric']['criteria']=[dict(criterionId=key,question='Frozen rejection audit',requiredFacts=[],forbiddenClaims=[])
                                              for key in ('REJECTION_FACTS','REJECTION_CLAIMS')]
        self.before['orders']['order-a']['orderStatus']='PAID'
        self.after=copy.deepcopy(self.before); self.worker['terminalEvidence']='NOT_SENT'
        self.events=[event(1,'MCP','CALLED',turn=0,role='MCP',tool='get_refund_eligibility',callId='mcp-1'),
                     event(2,'MCP','RESPONSE_RECEIVED',turn=0,role='MCP',tool='get_refund_eligibility',callId='mcp-1'),
                     event(3,'SESSION','REJECTED',turn=0,tool='get_refund_eligibility'), event(4,'SESSION',turn=0)]
        self.records=[record('REPLY',0,'flow-4',replyKind='TRUSTED_TEMPLATE'),record('FINAL_REPLY',0,'reply-session-a-0',file='reply-0.txt')]
        self.replies=['订单 '+ORDER+' 当前不可退：订单当前状态（PAID）不符合任何售后政策。本次未提交退款。']

    def test_fix4_readonly_rejection_requires_human_review_after_trusted_marker(self):
        self.readonly_rejection(); result=self.run_judge()
        self.assertEqual('PASS',result['automaticStatus'])
        self.assertEqual('SKIPPED',result['status'])
        self.assertEqual('PENDING',result['manualReview'])
        self.case['manualRubric']['criteria']=[]
        self.assertEqual('PENDING',self.run_judge()['manualReview'])

    def test_fix4_readonly_query_marker_frame_and_unsent_boundaries_cannot_be_forged(self):
        self.readonly_rejection(); baseline=copy.deepcopy((self.events,self.records,self.worker,self.after))
        for mutation in ('missing-marker','missing-query','failed-query','wrong-target','wrong-origin','model-marker','free-frame','workflow','submit','unknown'):
            with self.subTest(mutation=mutation):
                self.events,self.records,self.worker,self.after=copy.deepcopy(baseline)
                if mutation=='missing-marker': self.events[2]['tool']=None
                if mutation=='missing-query': self.events[0]['tool']='get_order'
                if mutation=='failed-query': self.events[1]['status']='BUSINESS_ERROR'
                if mutation=='wrong-target': self.events[1]['target']='product-a'
                if mutation=='wrong-origin': self.events[1]['turnIndex']=1
                if mutation=='model-marker': self.events[2]['role']='DIALOGUE'
                if mutation=='free-frame': self.records[0]['replyKind']='FREE_TEXT'
                if mutation=='workflow': self.events[0].update(phase='CONFIRMATION',tool=None,role='ORCHESTRATOR',status='COMPLETED')
                if mutation=='submit': self.events[0]['tool']='submit_refund'
                if mutation=='unknown': self.worker['terminalEvidence']=self.after['terminalEvidence']='UNKNOWN'
                self.assertNotEqual('PASS',self.run_judge()['automaticStatus'])

    def test_fix_i5_seven_sku_canonical_order_is_proven_and_missing_proof_cannot_pass(self):
        self.test_product_digest_and_final_freshness_are_not_text_hash_or_any_prior_read()
        self.events[0]['status'] = 'STARTED'
        canonical = json.loads(self.records[-1]['text'])
        canonical['skus'] = [dict(id=9007199254741020+i, specs='规格'+str(i), price='19.90', stock=5) for i in range(7, 0, -1)]
        self.bindings['products']['product-a']['skus'] = {'sku-'+str(i): str(9007199254741020+i) for i in range(1, 8)}
        original_sku = self.case['fixture']['products']['product-a']['skus'][0]
        self.case['fixture']['products']['product-a']['skus'] = [{**original_sku, 'skuAlias': 'sku-a' if i == 1 else 'sku-'+str(i)} for i in range(1, 8)]
        proof = json.dumps(canonical, ensure_ascii=False, separators=(',', ':'))
        digest = hashlib.sha256(proof.encode()).hexdigest()
        body = '商品：'+canonical['name']+'；当前描述：'+canonical['description']+''.join('；规格：'+s['specs']+'，当前价格：'+s['price']+'，当前库存：'+str(s['stock']) for s in canonical['skus'])
        for e in self.events:
            if e['sourceDigest']: e['sourceDigest'] = digest
        self.records[0].update(text=body, sourceDigest=digest)
        self.records[-1].update(text=proof, sourceDigest=digest)
        self.replies = ['以下仅为当前在售商品资料，不能证明下单时的描述。\n[PRODUCT-9007199254741011] '+body]
        self.assertEqual('PASS', self.run_judge()['status'])
        baseline = copy.deepcopy((self.records, self.events))
        for mutation in ('missing', 'order', 'unbound', 'call-id', 'origin'):
            with self.subTest(mutation=mutation):
                self.records, self.events = copy.deepcopy(baseline)
                if mutation == 'missing': self.records.pop()
                if mutation in ('order', 'unbound'):
                    changed = copy.deepcopy(canonical)
                    if mutation == 'order': changed['skus'].reverse()
                    else: changed['skus'][0]['id'] = 42
                    self.records[-1]['text'] = json.dumps(changed, ensure_ascii=False, separators=(',', ':'))
                if mutation == 'call-id': self.records[-1]['callId'] = 'mcp-other'
                if mutation == 'origin': self.records[-1]['turnIndex'] = 1
                self.assertNotEqual('PASS', self.run_judge()['status'])

    def test_fix_i4_rejection_requires_predeclared_actual_human_fact_and_claim_audit(self):
        self.test_fix_i4_production_ineligible_reason_needs_audit_not_system_fail()
        self.case['manualRubric']['criteria'] = [dict(criterionId='ANY', question='generic', requiredFacts=[], forbiddenClaims=[])]
        generic = dict(caseId='NORMAL-001', trialId='trial-1', criteria={'ANY': 'PASS'})
        self.assertNotEqual('PASS', self.run_judge(generic)['status'])
        self.case['manualRubric']['criteria'] = [dict(criterionId=k, question='冻结人工判据', requiredFacts=[], forbiddenClaims=[])
                                               for k in ('REJECTION_FACTS', 'REJECTION_CLAIMS')]
        actual = dict(caseId='NORMAL-001', trialId='trial-1', criteria={'REJECTION_FACTS': 'PASS', 'REJECTION_CLAIMS': 'PASS'})
        self.assertEqual('PASS', self.run_judge(actual)['status'])
        actual['criteria']['REJECTION_FACTS'] = 'FAIL'
        self.assertEqual('FAIL', self.run_judge(actual)['status'])
        self.events[0]['status'] = 'COMPLETED'
        self.assertNotEqual('PASS', self.run_judge()['automaticStatus'])


class BackendProbeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup); self.root = Path(self.temp.name)
        self.case = json.loads((ROOT / 'eval/fixtures/case-contract.json').read_text(encoding='utf-8-sig'))['templates']['controlled']
        self.case['control'] = dict(target='BACKEND_TRANSACTION', components=dict(DIALOGUE='ABSENT', REVIEW='ABSENT', EXPLANATION='ABSENT', MCP='ABSENT', BACKEND='REAL'), usesRealModel=False, point='BACKEND_PROBE', probe='ROLLBACK_AFTER_INSERT')
        self.case['expect']['outcome'] = 'NOT_SUBMITTED'
        self.case['expect']['orders']['order-a'] = dict(orderStatus=self.case['fixture']['orders']['order-a']['status'], newRefundRows=0, refundAmount=None, ownerMatches=True)
        self.oracle = dict(terminalEvidence='NOT_SENT', orders={'order-a': dict(orderStatus=self.case['fixture']['orders']['order-a']['status'], paidAmount='39.80', refundRows=[], ownerMatches=True)})
        self.envelope = dict(schemaVersion=1, runId='probe-run', caseId=self.case['caseId'], trialId='probe-trial', fixtureReply=dict(schemaVersion=1, op='probe', status='COMPLETED', probeEvidence=dict(probe='ROLLBACK_AFTER_INSERT', durationMs=40, receiptClass='NOT_APPLICABLE', assertionsPassed=True)))

    def check(self):
        from scripts.evaluation_judge import backend_probe_scope
        path = self.root / 'probe.json'; path.write_text(json.dumps(self.envelope), encoding='utf-8')
        with evidence_scope(self.root), backend_probe_scope(Path('probe.json'), run_id='probe-run', trial_id='probe-trial'):
            return judge(self.case, [], self.oracle, self.oracle, {}, None)

    def test_real_closed_probe_envelope_and_independent_oracle_pass(self):
        result = self.check(); self.assertEqual('PASS', result['status'], result); self.assertEqual(result, validate_wire('TrialResult', result))

    def test_probe_unknown_wrong_case_probe_or_failed_assertions_cannot_pass(self):
        for mutation in ('unknown', 'case', 'probe', 'assertion', 'extra'):
            with self.subTest(mutation=mutation):
                original = copy.deepcopy(self.envelope)
                if mutation == 'unknown': self.envelope['fixtureReply']['probeEvidence']['receiptClass'] = 'UNKNOWN'
                if mutation == 'case': self.envelope['caseId'] = 'WRONG-CASE'
                if mutation == 'probe': self.envelope['fixtureReply']['probeEvidence']['probe'] = 'STALE_POLICY'
                if mutation == 'assertion': self.envelope['fixtureReply']['probeEvidence']['assertionsPassed'] = False
                if mutation == 'extra': self.envelope['fixtureReply']['probeEvidence']['token'] = 'secret-sentinel'
                try: self.assertNotEqual('PASS', self.check()['status'])
                except ValueError: pass
                self.envelope = original

    def test_backend_scope_not_available_to_agent_chain_and_missing_cannot_pass(self):
        from scripts.evaluation_judge import backend_probe_scope
        with evidence_scope(self.root):
            self.assertRaises(ValueError, judge, self.case, [], self.oracle, self.oracle, {}, None)
            self.assertRaises(ValueError, lambda: backend_probe_scope(Path('../outside.json'), run_id='probe-run', trial_id='probe-trial').__enter__())


class ProvedBeforeSendTest(unittest.TestCase):
    """Mutate the actual worker export, retaining the existing judge and scopes."""
    def setUp(self):
        source = ROOT / 'agent/target/evaluation-counterexamples/before-send'
        self.assertTrue((source / 'trial.json').is_file(), 'Run EvaluationHarnessTest before this offline evidence suite')
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'trial'; shutil.copytree(source, self.root)
        self.saved = json.loads((self.root / 'trial.json').read_text(encoding='utf-8'))
        self.private = json.loads((self.root / 'worker/evidence.json').read_text(encoding='utf-8'))

    def check(self):
        work = self.root / 'worker'
        (work / 'events.jsonl').write_text(''.join(json.dumps(e, ensure_ascii=False) + '\n' for e in self.saved['events']), encoding='utf-8')
        (work / 'evidence.json').write_text(json.dumps(self.private, ensure_ascii=False), encoding='utf-8')
        with evidence_scope(work):
            return judge(self.saved['case'], self.saved['events'], self.saved['before'],
                         self.saved['after'], self.saved['worker'], None)

    def remove_events(self, predicate):
        kept = [e for e in self.saved['events'] if not predicate(e)]
        sequences = {e['sequence']: i for i, e in enumerate(kept, 1)}
        for e in kept: e['sequence'] = sequences[e['sequence']]
        self.saved['events'] = kept
        for row in self.private['records']:
            if row['kind'] == 'SOURCE' and row['callId'].startswith('source-'):
                row['callId'] = 'source-' + str(sequences[int(row['callId'][7:])])

    def test_actual_pre_send_not_sent_zero_write_with_confirmed_approved_chain_passes(self):
        result = self.check()
        self.assertEqual('PASS', result['status'], result)
        self.assertEqual('PASS', result['automaticStatus'], result)
        self.assertEqual('NOT_REQUIRED', result['manualReview'])

    def test_unknown_never_passes_even_with_zero_rows(self):
        self.saved['worker']['terminalEvidence'] = self.saved['after']['terminalEvidence'] = 'UNKNOWN'
        result = self.check(); self.assertEqual('ERROR', result['status']); self.assertIn('UNRESOLVED_WRITE', result['failedCriteria'])

    def test_live_or_wrong_control_cannot_use_the_pre_send_exception(self):
        for change in ('live', 'point', 'tool', 'target', 'outcome'):
            with self.subTest(change=change):
                original = copy.deepcopy(self.saved['case'])
                if change == 'live': self.saved['case']['mode'] = 'LIVE_E2E'; del self.saved['case']['control']
                if change == 'point': self.saved['case']['control'].update(point='MCP_AFTER_RESPONSE', response='lost')
                if change == 'tool': self.saved['case']['control']['toolName'] = 'get_order'
                if change == 'target':
                    self.saved['case']['control'] = dict(target='BACKEND_TRANSACTION', components=dict(DIALOGUE='ABSENT', REVIEW='ABSENT', EXPLANATION='ABSENT', MCP='ABSENT', BACKEND='REAL'), usesRealModel=False, point='BACKEND_PROBE', probe='ROLLBACK_AFTER_INSERT')
                if change == 'outcome': self.saved['case']['expect']['outcome'] = 'UNRESOLVED_WRITE'
                try: self.assertNotEqual('PASS', self.check()['status'])
                except ValueError: pass
                self.saved['case'] = original

    def test_missing_real_scripted_checkpoint_cannot_pass(self):
        self.remove_events(lambda e: e['phase'] == 'MCP' and e['status'] == 'SCRIPTED')
        self.assertNotEqual('PASS', self.check()['status'])

    def test_missing_original_error_private_record_cannot_pass(self):
        self.private['records'] = [r for r in self.private['records'] if r['kind'] != 'ERROR']
        self.assertNotEqual('PASS', self.check()['status'])

    def test_scripted_checkpoint_wrong_origin_or_unbound_target_cannot_pass(self):
        scripted = next(e for e in self.saved['events'] if e['phase'] == 'MCP' and e['status'] == 'SCRIPTED')
        original = copy.deepcopy(scripted)
        for values in ({'turnIndex': 0}, {'target': 'UNBOUND'}, {'trialId': 'wrong-trial'}, {'caseId': 'NORMAL-001'}, {'sessionAlias': 'other-session'}):
            with self.subTest(values=values):
                scripted.update(values); self.assertNotEqual('PASS', self.check()['status'])
                scripted.clear(); scripted.update(original)

    def test_error_checkpoint_wrong_target_origin_or_exception_cannot_pass(self):
        failure = next(e for e in self.saved['events'] if e['phase'] == 'EXECUTION' and e['status'] == 'FAILED')
        original = copy.deepcopy(failure)
        for values in ({'target': 'UNBOUND'}, {'turnIndex': 0}, {'exceptionClass': 'UnexpectedTransportFailure'}):
            with self.subTest(values=values):
                failure.update(values); self.assertNotEqual('PASS', self.check()['status'])
                failure.clear(); failure.update(original)

    def test_actual_submit_or_unknown_transport_or_retry_cannot_pass(self):
        scripted = next(e for e in self.saved['events'] if e['phase'] == 'MCP' and e['status'] == 'SCRIPTED')
        original = copy.deepcopy(self.saved['events'])
        for status in ('CALLED', 'TRANSPORT_ERROR', 'FAILED'):
            with self.subTest(status=status):
                extra = copy.deepcopy(scripted); extra.update(sequence=len(original) + 1, status=status, callId='untrusted-extra-call')
                self.saved['events'] = copy.deepcopy(original) + [extra]
                self.assertNotEqual('PASS', self.check()['status'])
        self.saved['events'] = copy.deepcopy(original)
        extra = copy.deepcopy(scripted); extra.update(sequence=len(original) + 1, callId='retry-checkpoint')
        self.saved['events'].append(extra); self.assertNotEqual('PASS', self.check()['status'])

    def test_nonzero_refund_or_changed_state_amount_owner_cannot_pass(self):
        order = self.saved['after']['orders']['order-a']; original = copy.deepcopy(order)
        for values in ({'refundRows': [dict(status='REFUNDED', amount='39.80', ownerMatches=True)]},
                       {'orderStatus': 'REFUNDED'}, {'paidAmount': '40.00'}, {'ownerMatches': False}):
            with self.subTest(values=values):
                order.update(values); self.assertNotEqual('PASS', self.check()['status'])
                order.clear(); order.update(copy.deepcopy(original))

    def test_deleted_confirmation_cannot_pass_without_an_actual_submit_call(self):
        self.remove_events(lambda e: e['phase'] == 'CONFIRMATION' and e['status'] == 'COMPLETED')
        self.assertNotEqual('PASS', self.check()['status'])

    def test_deleted_review_cannot_pass_without_an_actual_submit_call(self):
        self.remove_events(lambda e: e['phase'] == 'REVIEW')
        self.private['records'] = [r for r in self.private['records'] if r['kind'] != 'REVIEW_OUTCOME']
        self.assertNotEqual('PASS', self.check()['status'])

    def test_invalid_or_conflicting_review_and_stale_policy_cannot_pass(self):
        record = next(r for r in self.private['records'] if r['kind'] == 'REVIEW_OUTCOME')
        event = next(e for e in self.saved['events'] if e['callId'] == record['callId'])
        record['outcome'] = 'INVALID'; event['status'] = 'FAILED'
        self.assertNotEqual('PASS', self.check()['status'])
        record['outcome'] = 'APPROVED'; event['status'] = 'COMPLETED'; event['policyCode'] = 'QUALITY_ISSUE'
        self.assertNotEqual('PASS', self.check()['status'])
        event['policyCode'] = CODE
        policy = next(e for e in self.saved['events'] if e['phase'] == 'POLICY' and e['status'] == 'COMPLETED')
        policy['policyFingerprint'] = 'stale-mutated-policy'
        self.assertNotEqual('PASS', self.check()['status'])


if __name__ == '__main__':
    unittest.main()
