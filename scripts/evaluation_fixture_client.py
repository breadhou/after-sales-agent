"""Strict persistent helper protocol and owned subprocess tree containment."""
from __future__ import annotations

import ctypes
import json
import os
import queue
import signal
import subprocess
import threading
import time
from pathlib import Path

from scripts.evaluation_contract import validate_wire


def strict_json(text: str):
    def pairs(items):
        value = {}
        for key, item in items:
            if key in value: raise ValueError('Duplicate JSON key')
            value[key] = item
        return value
    def invalid(value): raise ValueError('Non-finite JSON value')
    return json.loads(text, object_pairs_hook=pairs, parse_constant=invalid)


class _WindowsJob:
    """A suspended child joins a kill-on-close job before it can spawn MCP."""
    def __init__(self):
        from ctypes import wintypes as w
        class BasicLimits(ctypes.Structure):
            _fields_ = [('PerProcessUserTimeLimit', ctypes.c_longlong), ('PerJobUserTimeLimit', ctypes.c_longlong),
                        ('LimitFlags', w.DWORD), ('MinimumWorkingSetSize', ctypes.c_size_t),
                        ('MaximumWorkingSetSize', ctypes.c_size_t), ('ActiveProcessLimit', w.DWORD),
                        ('Affinity', ctypes.c_size_t), ('PriorityClass', w.DWORD), ('SchedulingClass', w.DWORD)]
        class IoCounters(ctypes.Structure):
            _fields_ = [(key, ctypes.c_ulonglong) for key in ('ReadOperationCount', 'WriteOperationCount',
                        'OtherOperationCount', 'ReadTransferCount', 'WriteTransferCount', 'OtherTransferCount')]
        class Extended(ctypes.Structure):
            _fields_ = [('BasicLimitInformation', BasicLimits), ('IoInfo', IoCounters),
                        ('ProcessMemoryLimit', ctypes.c_size_t), ('JobMemoryLimit', ctypes.c_size_t),
                        ('PeakProcessMemoryUsed', ctypes.c_size_t), ('PeakJobMemoryUsed', ctypes.c_size_t)]
        class Accounting(ctypes.Structure):
            _fields_ = [(key, ctypes.c_longlong) for key in ('TotalUserTime', 'TotalKernelTime',
                        'ThisPeriodTotalUserTime', 'ThisPeriodTotalKernelTime')] + [(key, w.DWORD) for key in
                        ('TotalPageFaultCount', 'TotalProcesses', 'ActiveProcesses', 'TotalTerminatedProcesses')]
        self.Accounting = Accounting
        self.kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        signatures = {'CreateJobObjectW': ([ctypes.c_void_p, w.LPCWSTR], w.HANDLE),
            'SetInformationJobObject': ([w.HANDLE, ctypes.c_int, ctypes.c_void_p, w.DWORD], w.BOOL),
            'AssignProcessToJobObject': ([w.HANDLE, w.HANDLE], w.BOOL),
            'QueryInformationJobObject': ([w.HANDLE, ctypes.c_int, ctypes.c_void_p, w.DWORD, ctypes.c_void_p], w.BOOL),
            'TerminateJobObject': ([w.HANDLE, w.UINT], w.BOOL), 'CloseHandle': ([w.HANDLE], w.BOOL),
            'OpenThread': ([w.DWORD, w.BOOL, w.DWORD], w.HANDLE), 'ResumeThread': ([w.HANDLE], w.DWORD),
            'CreateToolhelp32Snapshot': ([w.DWORD, w.DWORD], w.HANDLE)}
        for name, (arguments, result) in signatures.items():
            method = getattr(self.kernel, name); method.argtypes = arguments; method.restype = result
        self.handle = self.kernel.CreateJobObjectW(None, None)
        if not self.handle: raise OSError('Process containment unavailable')
        limits = Extended(); limits.BasicLimitInformation.LimitFlags = 0x2000
        if not self.kernel.SetInformationJobObject(self.handle, 9, ctypes.byref(limits), ctypes.sizeof(limits)):
            self.close(); raise OSError('Process containment unavailable')

    def attach_resume(self, process):
        from ctypes import wintypes as w
        if not self.kernel.AssignProcessToJobObject(self.handle, int(process._handle)):
            raise OSError('Process containment unavailable')
        class ThreadEntry(ctypes.Structure):
            _fields_ = [('dwSize', w.DWORD), ('cntUsage', w.DWORD), ('th32ThreadID', w.DWORD),
                        ('th32OwnerProcessID', w.DWORD), ('tpBasePri', w.LONG), ('tpDeltaPri', w.LONG), ('dwFlags', w.DWORD)]
        for name in ('Thread32First', 'Thread32Next'):
            method = getattr(self.kernel, name); method.argtypes = [w.HANDLE, ctypes.POINTER(ThreadEntry)]; method.restype = w.BOOL
        snapshot = self.kernel.CreateToolhelp32Snapshot(4, 0)
        if snapshot == ctypes.c_void_p(-1).value: raise OSError('Cannot resume owned child')
        try:
            entry = ThreadEntry(); entry.dwSize = ctypes.sizeof(entry)
            available = self.kernel.Thread32First(snapshot, ctypes.byref(entry))
            while available:
                if entry.th32OwnerProcessID == process.pid:
                    thread = self.kernel.OpenThread(2, False, entry.th32ThreadID)
                    if not thread: raise OSError('Cannot resume owned child')
                    try:
                        if self.kernel.ResumeThread(thread) == 0xffffffff: raise OSError('Cannot resume owned child')
                    finally: self.kernel.CloseHandle(thread)
                    return
                available = self.kernel.Thread32Next(snapshot, ctypes.byref(entry))
            raise OSError('Cannot resume owned child')
        finally: self.kernel.CloseHandle(snapshot)

    def active(self):
        value = self.Accounting()
        if not self.kernel.QueryInformationJobObject(self.handle, 1, ctypes.byref(value), ctypes.sizeof(value), None):
            raise OSError('Cannot verify child termination')
        return value.ActiveProcesses

    def terminate(self):
        if not self.kernel.TerminateJobObject(self.handle, 1): raise OSError('Cannot terminate owned children')

    def close(self):
        if self.handle:
            self.kernel.CloseHandle(self.handle); self.handle = None


class OwnedProcess:
    """Own worker/helper and descendants; termination means the whole tree exited."""
    def __init__(self, command, *, cwd, environment, stdout, stderr, stdin=None):
        self.job = _WindowsJob() if os.name == 'nt' else None
        self.process = None
        self.terminated = False
        try:
            self.process = subprocess.Popen(command, cwd=cwd, env=environment, shell=False,
                stdin=stdin, stdout=stdout, stderr=stderr, text=True, encoding='utf-8', errors='strict',
                creationflags=4 if self.job else 0, start_new_session=not self.job)
            if self.job: self.job.attach_resume(self.process)
        except BaseException:
            if self.process is not None: self.process.kill(); self.process.wait(timeout=5)
            if self.job: self.job.close()
            raise

    def close(self):
        try:
            if self.job: self.job.terminate()
            else:
                try: os.killpg(self.process.pid, signal.SIGKILL)
                except ProcessLookupError: pass
            self.process.wait(timeout=5)
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                if self.job:
                    alive = self.job.active() != 0
                else:
                    try: os.killpg(self.process.pid, 0); alive = True
                    except ProcessLookupError: alive = False
                if not alive: self.terminated = True; break
                time.sleep(.02)
        finally:
            if self.job: self.job.close()
        return self.terminated


class FixtureClient:
    def __init__(self, command: list[str], *, cwd: Path, environment: dict[str, str],
                 stderr_path: Path, timeout: float = 300):
        self.timeout = timeout
        self.terminated = False
        self._last = None
        self._lines = queue.Queue()
        self._stderr = Path(stderr_path).open('x', encoding='utf-8')
        try:
            self._owned = OwnedProcess(command, cwd=cwd, environment=environment,
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self._stderr)
        except BaseException:
            self._stderr.close(); raise
        self._reader = threading.Thread(target=self._read, daemon=True)
        self._reader.start()

    def _read(self):
        try:
            for line in self._owned.process.stdout: self._lines.put(line)
        except (UnicodeError, OSError): self._lines.put(None)
        finally: self._lines.put(None)

    def request(self, message: dict) -> dict:
        message = validate_wire('FixtureRequest', message)
        if not self._lines.empty(): raise ValueError('Unsolicited helper output')
        self._last = message
        self._owned.process.stdin.write(json.dumps(message, separators=(',', ':')) + '\n')
        self._owned.process.stdin.flush()
        try: line = self._lines.get(timeout=self.timeout)
        except queue.Empty: raise TimeoutError('Fixture protocol deadline exceeded') from None
        if line is None or not line.endswith('\n'): raise ValueError('Missing complete helper reply')
        reply = validate_wire('FixtureReply', strict_json(line))
        if reply['op'] != message['op']: raise ValueError('Wrong helper operation')
        return reply

    def close(self):
        try: self.terminated = self._owned.close()
        finally:
            self._reader.join(timeout=5)
            self._owned.process.stdin.close()
            self._owned.process.stdout.close()
            self._stderr.close()
        return self.terminated

    def __enter__(self): return self
    def __exit__(self, *exception): self.close()
