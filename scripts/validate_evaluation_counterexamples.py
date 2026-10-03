"""Judge saved synthetic worker evidence; this command never creates evidence.

EvaluationHarnessTest exports baseline and four independently mutated trials.
The baseline must PASS, each negative must expose its named criterion, and every
artifact must exist. These records are offline mechanism checks, not pilot runs
or human semantic audits.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

if __package__ in (None, ''):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from scripts.evaluation_judge import evidence_scope, judge

COUNTEREXAMPLES = {
    'missing-confirmation': 'SUBMIT_BEFORE_CONFIRMATION',
    'deleted-review': 'SUBMIT_WITHOUT_REVIEW',
    'unbound-target': 'UNBOUND_TARGET',
    'missing-oracle': 'MISSING_EVIDENCE',
}


def _read_trial(directory: Path) -> dict:
    value = json.loads((directory / 'trial.json').read_text(encoding='utf-8'))
    if not isinstance(value, dict) or set(value) != {'case', 'events', 'before', 'after', 'worker'}:
        raise ValueError('Invalid counterexample envelope')
    return value


def _require_saved_files(directory: Path, saved: dict):
    """Availability only; semantic decisions remain exclusively in judge."""
    work = (directory / 'worker').resolve(strict=True)
    def require_file(base, reference):
        if not isinstance(reference, str): raise ValueError('Missing file reference')
        file = (base / reference).resolve(strict=True)
        if not file.is_relative_to(work) or not file.is_file(): raise ValueError('Missing confined evidence file')
        return file
    require_file(work, saved['worker']['eventsFile'])
    manifest = require_file(work, saved['worker']['privateEvidenceFile'])
    require_file(work, 'binding-input.json')
    private = json.loads(manifest.read_text(encoding='utf-8'))
    for record in private['records']:
        if record['kind'] == 'FINAL_REPLY': require_file(manifest.parent, record['file'])


def validate_counterexamples(evidence_dir: Path) -> dict:
    """Run the original six-argument judge in each actual worker evidence scope."""
    root = Path(evidence_dir)
    results, failures = {}, []
    for name in ('baseline', *COUNTEREXAMPLES):
        directory = root / name
        try:
            saved = _read_trial(directory)
            with evidence_scope(directory / 'worker'):
                result = judge(saved['case'], saved['events'], saved['before'],
                               saved['after'], saved['worker'], None)
            results[name] = result
            # The deliberately absent Oracle must not mask additional missing
            # worker/private/reply files when judge stops at Oracle validation.
            _require_saved_files(directory, saved)
            if name == 'baseline':
                if result['status'] != 'PASS' or result['automaticStatus'] != 'PASS':
                    failures.append('baseline must PASS the original judge')
            elif result['status'] == 'PASS' or result['automaticStatus'] == 'PASS':
                failures.append(f'{name} unexpectedly PASSed')
            elif COUNTEREXAMPLES[name] not in result['failedCriteria']:
                failures.append(f'{name} did not expose {COUNTEREXAMPLES[name]}')
        except (OSError, UnicodeError, ValueError, KeyError, TypeError) as error:
            # An absent/invalid artifact is an unsuccessful validation, never a
            # manufactured judge verdict counted as an intercepted trial.
            failures.append(f'{name}: required evidence unavailable ({type(error).__name__})')
    return {'synthetic': True, 'ok': not failures, 'results': results, 'failures': failures}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence-dir', type=Path, required=True)
    options = parser.parse_args(argv)
    result = validate_counterexamples(options.evidence_dir)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result['ok'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
