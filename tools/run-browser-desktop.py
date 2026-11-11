"""Run Windows browser acceptance on an owned desktop."""

import argparse
import ctypes
from ctypes import wintypes as w
from datetime import datetime
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import time


class StartupInfo(ctypes.Structure):
    _fields_ = [
        ("cb", w.DWORD), ("lpReserved", w.LPWSTR), ("lpDesktop", w.LPWSTR), ("lpTitle", w.LPWSTR),
        ("dwX", w.DWORD), ("dwY", w.DWORD), ("dwXSize", w.DWORD), ("dwYSize", w.DWORD),
        ("dwXCountChars", w.DWORD), ("dwYCountChars", w.DWORD), ("dwFillAttribute", w.DWORD),
        ("dwFlags", w.DWORD), ("wShowWindow", w.WORD), ("cbReserved2", w.WORD),
        ("lpReserved2", ctypes.POINTER(w.BYTE)), ("hStdInput", w.HANDLE),
        ("hStdOutput", w.HANDLE), ("hStdError", w.HANDLE),
    ]


class ProcessInfo(ctypes.Structure):
    _fields_ = [("process", w.HANDLE), ("thread", w.HANDLE), ("pid", w.DWORD), ("tid", w.DWORD)]


class BasicJobLimits(ctypes.Structure):
    _fields_ = [
        ("processTime", ctypes.c_int64), ("jobTime", ctypes.c_int64), ("flags", w.DWORD),
        ("minimumWorkingSet", ctypes.c_size_t), ("maximumWorkingSet", ctypes.c_size_t),
        ("activeProcessLimit", w.DWORD), ("affinity", ctypes.c_size_t),
        ("priority", w.DWORD), ("schedulingClass", w.DWORD),
    ]


class JobLimits(ctypes.Structure):
    _fields_ = [
        ("basic", BasicJobLimits), ("ioCounters", ctypes.c_uint64 * 6),
        ("processMemoryLimit", ctypes.c_size_t), ("jobMemoryLimit", ctypes.c_size_t),
        ("peakProcessMemory", ctypes.c_size_t), ("peakJobMemory", ctypes.c_size_t),
    ]


class JobAccounting(ctypes.Structure):
    _fields_ = [
        ("userTime", ctypes.c_int64), ("kernelTime", ctypes.c_int64),
        ("periodUserTime", ctypes.c_int64), ("periodKernelTime", ctypes.c_int64),
        ("pageFaults", w.DWORD), ("totalProcesses", w.DWORD),
        ("activeProcesses", w.DWORD), ("terminatedProcesses", w.DWORD),
    ]


def api():
    user = ctypes.WinDLL("user32", use_last_error=True)
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    user.CreateDesktopW.argtypes = [w.LPCWSTR, w.LPCWSTR, w.LPVOID, w.DWORD, w.DWORD, w.LPVOID]
    user.CreateDesktopW.restype = w.HANDLE
    user.CloseDesktop.argtypes = [w.HANDLE]
    user.GetThreadDesktop.argtypes = [w.DWORD]
    user.GetThreadDesktop.restype = w.HANDLE
    user.GetUserObjectInformationW.argtypes = [w.HANDLE, ctypes.c_int, w.LPVOID, w.DWORD, ctypes.POINTER(w.DWORD)]
    kernel.GetCurrentThreadId.restype = w.DWORD
    kernel.CreateProcessW.argtypes = [w.LPCWSTR, w.LPWSTR, w.LPVOID, w.LPVOID, w.BOOL, w.DWORD,
                                      w.LPVOID, w.LPCWSTR, ctypes.POINTER(StartupInfo), ctypes.POINTER(ProcessInfo)]
    kernel.CreateProcessW.restype = w.BOOL
    kernel.CreateJobObjectW.argtypes = [w.LPVOID, w.LPCWSTR]
    kernel.CreateJobObjectW.restype = w.HANDLE
    kernel.SetInformationJobObject.argtypes = [w.HANDLE, ctypes.c_int, w.LPVOID, w.DWORD]
    kernel.QueryInformationJobObject.argtypes = [w.HANDLE, ctypes.c_int, w.LPVOID, w.DWORD, w.LPVOID]
    kernel.AssignProcessToJobObject.argtypes = [w.HANDLE, w.HANDLE]
    kernel.ResumeThread.argtypes = [w.HANDLE]
    kernel.ResumeThread.restype = w.DWORD
    kernel.WaitForSingleObject.argtypes = [w.HANDLE, w.DWORD]
    kernel.WaitForSingleObject.restype = w.DWORD
    kernel.GetExitCodeProcess.argtypes = [w.HANDLE, ctypes.POINTER(w.DWORD)]
    kernel.TerminateProcess.argtypes = [w.HANDLE, w.UINT]
    kernel.TerminateJobObject.argtypes = [w.HANDLE, w.UINT]
    kernel.CloseHandle.argtypes = [w.HANDLE]
    return user, kernel


def require(success):
    if not success:
        raise ctypes.WinError(ctypes.get_last_error())


def probe(output):
    user, kernel = api()
    desktop = user.GetThreadDesktop(kernel.GetCurrentThreadId())
    needed = w.DWORD()
    user.GetUserObjectInformationW(desktop, 2, None, 0, ctypes.byref(needed))
    name = ctypes.create_unicode_buffer(max(needed.value // 2, 1))
    require(user.GetUserObjectInformationW(desktop, 2, name, ctypes.sizeof(name), ctypes.byref(needed)))
    output.write_text(json.dumps({"pid": os.getpid(), "desktop": name.value}), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="New evidence directory under this project")
    parser.add_argument("--timeout", type=int, default=600, help="Command timeout in seconds")
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command or args.timeout < 1:
        parser.error("A command and positive timeout are required")
    root = Path(__file__).resolve().parent.parent
    output = args.output.resolve()
    if not output.is_relative_to(root) or "onedrive" in str(output).casefold():
        parser.error("Evidence must be inside the project and outside OneDrive")
    output.mkdir(parents=True, exist_ok=False)
    executable = shutil.which(command[0])
    if not executable:
        parser.error("Command executable was not found")
    command[0] = executable
    user, kernel = api()
    desktop_name = "CodexBrowserQa" + secrets.token_hex(8)
    desktop = job = None
    result = {"passed": False, "desktop": desktop_name, "steps": [],
              "startedAt": datetime.now().astimezone().isoformat()}

    def active_processes():
        accounting = JobAccounting()
        require(kernel.QueryInformationJobObject(job, 1, ctypes.byref(accounting), ctypes.sizeof(accounting), None))
        return accounting.activeProcesses

    def launch(argv, timeout):
        startup = StartupInfo()
        startup.cb = ctypes.sizeof(startup)
        startup.lpDesktop = "WinSta0\\" + desktop_name
        startup.dwFlags = 1
        startup.wShowWindow = 0
        process = ProcessInfo()
        line = ctypes.create_unicode_buffer(subprocess.list2cmdline(argv))
        require(kernel.CreateProcessW(argv[0], line, None, None, False, 0x08000004,
                                      None, str(root), ctypes.byref(startup), ctypes.byref(process)))
        assigned = False
        try:
            require(kernel.AssignProcessToJobObject(job, process.process))
            assigned = True
            require(kernel.ResumeThread(process.thread) != 0xFFFFFFFF)
            waited = kernel.WaitForSingleObject(process.process, timeout * 1000)
            if waited == 258:
                raise TimeoutError("Owned acceptance command timed out")
            require(waited == 0)
            code = w.DWORD()
            require(kernel.GetExitCodeProcess(process.process, ctypes.byref(code)))
            result["steps"].append({"pid": process.pid, "exitCode": code.value})
            return code.value
        finally:
            if not assigned:
                kernel.TerminateProcess(process.process, 125)
                kernel.WaitForSingleObject(process.process, 10000)
            kernel.CloseHandle(process.thread)
            kernel.CloseHandle(process.process)

    try:
        desktop = user.CreateDesktopW(desktop_name, None, None, 0, 0x10000000, None)
        require(desktop)
        job = kernel.CreateJobObjectW(None, None)
        require(job)
        limits = JobLimits()
        limits.basic.flags = 0x2000  # Kill only this job's descendants when its last handle closes.
        require(kernel.SetInformationJobObject(job, 9, ctypes.byref(limits), ctypes.sizeof(limits)))
        probe_output = output / "desktop-probe.json"
        probe_command = [sys.executable, str(Path(__file__).resolve()), "--internal-probe", str(probe_output)]
        if launch(probe_command, 20):
            raise RuntimeError("Desktop probe failed")
        result["probe"] = json.loads(probe_output.read_text(encoding="utf-8"))
        if result["probe"]["desktop"] != desktop_name:
            raise RuntimeError("Child process did not attach to the owned desktop")
        result["exitCode"] = launch(command, args.timeout)
        until = time.monotonic() + 5
        while active_processes() and time.monotonic() < until:
            time.sleep(0.1)
        result["activeDescendants"] = active_processes()
        result["passed"] = result["exitCode"] == 0 and result["activeDescendants"] == 0
    except BaseException as error:
        result["error"] = str(error)
        if isinstance(error, TimeoutError):
            result["exitCode"] = 124
    finally:
        if job:
            try:
                if active_processes():
                    result["ownedTreeTerminated"] = bool(kernel.TerminateJobObject(job, 124))
                    until = time.monotonic() + 10
                    while active_processes() and time.monotonic() < until:
                        time.sleep(0.1)
                result["remainingDescendantsAfterCleanup"] = active_processes()
            except OSError as error:
                result["cleanupError"] = str(error)
            finally:
                result["jobClosed"] = bool(kernel.CloseHandle(job))
        if desktop:
            result["desktopClosed"] = bool(user.CloseDesktop(desktop))
        result["passed"] = (result["passed"] and result.get("jobClosed", False)
                            and result.get("desktopClosed", False)
                            and result.get("remainingDescendantsAfterCleanup") == 0)
        result["finishedAt"] = datetime.now().astimezone().isoformat()
        (output / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"passed": result["passed"], "evidence": str(output)}), flush=True)
    return 0 if result["passed"] else result.get("exitCode") or 1


if __name__ == "__main__":
    if os.name != "nt":
        raise SystemExit("This launcher requires Windows")
    if len(sys.argv) == 3 and sys.argv[1] == "--internal-probe":
        probe(Path(sys.argv[2]))
    else:
        raise SystemExit(main())
