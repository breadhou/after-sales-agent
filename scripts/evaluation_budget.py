"""One durable, shared budget for pilot, formal and infrastructure supplements."""
from __future__ import annotations

import copy
import json
import os
from contextlib import contextmanager
from pathlib import Path

from scripts.evaluation_contract import validate_wire

PLANNED_TRIALS = 296
MAX_TRIALS = 320
MAX_REQUESTS = 1200
MAX_REPORTED_TOKENS = 2000000
MAX_TRIAL_REQUESTS = 12


class BudgetStop(RuntimeError):
    """No new automatic trial may be dispatched."""


def atomic_json(path: Path, value: dict) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + '.tmp-' + str(os.getpid()))
    try:
        with temporary.open('x', encoding='utf-8') as stream:
            json.dump(value, stream, ensure_ascii=False, separators=(',', ':'), allow_nan=False)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        if os.name != 'nt':
            descriptor = os.open(path.parent, os.O_RDONLY)
            try: os.fsync(descriptor)
            finally: os.close(descriptor)
    finally:
        temporary.unlink(missing_ok=True)


@contextmanager
def file_lock(path: Path):
    """OS locks release on process death; reservations themselves remain durable."""
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('a+b') as stream:
        stream.seek(0)
        if not stream.read(1):
            stream.write(b'0'); stream.flush()
        stream.seek(0)
        if os.name == 'nt':
            import msvcrt
            msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        try: yield
        finally:
            stream.seek(0)
            if os.name == 'nt': msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else: fcntl.flock(stream, fcntl.LOCK_UN)


class BudgetLedger:
    def __init__(self, path: Path):
        self.path = Path(path)
        self.lock_path = self.path.with_suffix(self.path.suffix + '.lock')

    def _read(self):
        if not self.path.exists(): return dict(schemaVersion=1, trials={})
        value = json.loads(self.path.read_text(encoding='utf-8'))
        if set(value) != {'schemaVersion', 'trials'} or value['schemaVersion'] != 1 or not isinstance(value['trials'], dict):
            raise ValueError('Invalid durable budget ledger')
        for trial, entry in value['trials'].items():
            if not isinstance(trial, str) or not trial or set(entry) != {'reservedRequests', 'chargedRequests', 'state', 'usage', 'terminated'}:
                raise ValueError('Invalid budget reservation')
            if any(type(entry[key]) is not int or not 0 <= entry[key] <= 12 for key in ('reservedRequests', 'chargedRequests')):
                raise ValueError('Invalid request reservation')
            if entry['chargedRequests'] > entry['reservedRequests'] or entry['state'] not in ('RESERVED', 'COMPLETE') or type(entry['terminated']) is not bool:
                raise ValueError('Invalid reservation state')
            if entry['usage'] is not None: _usage(entry['usage'])
        return value

    @staticmethod
    def _snapshot(value):
        trials = value['trials']
        # Known partial totals are counted; missing reported usage remains unknown.
        reported = sum(_known_tokens(e['usage']) for e in trials.values() if e['usage'] is not None)
        charged = sum(e['chargedRequests'] for e in trials.values())
        return dict(plannedTrials=PLANNED_TRIALS, maxTrials=MAX_TRIALS, maxRequests=MAX_REQUESTS,
                    maxReportedTokens=MAX_REPORTED_TOKENS, trialCount=len(trials), chargedRequests=charged,
                    reportedTokens=reported, remainingRequests=max(0, MAX_REQUESTS-charged),
                    remainingReportedTokens=max(0, MAX_REPORTED_TOKENS-reported),
                    unresolvedReservations=sum(e['state'] == 'RESERVED' or not e['terminated'] for e in trials.values()))

    def reserve(self, trial_id: str, allowance: int = 12) -> dict:
        if not isinstance(trial_id, str) or not trial_id or type(allowance) is not int or allowance < 0:
            raise ValueError('Invalid budget reservation input')
        with file_lock(self.lock_path):
            value = self._read()
            if trial_id in value['trials']: raise ValueError('Trial budget cannot be replayed')
            snapshot = self._snapshot(value)
            if snapshot['trialCount'] >= MAX_TRIALS or snapshot['remainingReportedTokens'] == 0:
                raise BudgetStop('BUDGET_STOP')
            granted = min(allowance, MAX_TRIAL_REQUESTS, snapshot['remainingRequests'])
            if allowance > 0 and granted == 0: raise BudgetStop('BUDGET_STOP')
            value['trials'][trial_id] = dict(reservedRequests=granted, chargedRequests=granted,
                                           state='RESERVED', usage=None, terminated=False)
            atomic_json(self.path, value)
            return dict(requestAllowance=granted, reportedTokenAllowance=snapshot['remainingReportedTokens'])

    def complete(self, trial_id: str, usage: dict, terminated: bool) -> None:
        usage = _usage(usage)
        if type(terminated) is not bool: raise ValueError('Invalid termination evidence')
        with file_lock(self.lock_path):
            value = self._read()
            entry = value['trials'].get(trial_id)
            if entry is None or entry['state'] != 'RESERVED': raise ValueError('Completion cannot overwrite prior usage')
            if usage['logicalModelRequests'] > entry['reservedRequests']: raise ValueError('Worker exceeded request reservation')
            entry.update(state='COMPLETE', usage=copy.deepcopy(usage), terminated=terminated)
            if terminated and usage['usageComplete']:
                entry['chargedRequests'] = usage['logicalModelRequests']
            atomic_json(self.path, value)

    def snapshot(self) -> dict:
        with file_lock(self.lock_path): return self._snapshot(self._read())


def _usage(value):
    return validate_wire('WorkerResult', dict(schemaVersion=1, runId='budget', caseId='BUDGET', trialId='usage',
        eventsFile='events.jsonl', privateEvidenceFile='private.json', metering=value,
        terminalEvidence='NOT_SENT', errorCategory=None))['metering']


def _known_tokens(value):
    if value['totalTokens'] is not None: return value['totalTokens']
    return sum(value[field] for field in ('promptTokens', 'completionTokens') if value[field] is not None)
