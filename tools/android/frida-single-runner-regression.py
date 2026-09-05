#!/usr/bin/env python3
"""Verify many changing Frida sources through one device-side Script runtime."""

import argparse
import json
from pathlib import Path
import subprocess
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from mira.mcp.server import MiraMcpServer, ToolError
def source(sequence: int) -> str:
    return r"""
// unique-sequence: %d
rpc.exports = {
  probe: function (tag) {
    var address = Module.getGlobalExportByName !== undefined
      ? Module.getGlobalExportByName('getpid') : Module.getExportByName(null, 'getpid');
    var nativeHits = 0, javaHits = 0, value = false;
    var listener = Interceptor.attach(address, { onEnter: function () { nativeHits++; } });
    var callGetpid = new NativeFunction(address, 'int', []);
    var StringClass = null, startsWith = null, object = null;
    try {
      Java.performNow(function () {
        StringClass = Java.use('java.lang.String');
        startsWith = StringClass.startsWith.overload('java.lang.String');
        startsWith.implementation = function (argument) {
          javaHits++;
          return startsWith.call(this, argument);
        };
        object = StringClass.$new('mira-' + tag);
        value = object.startsWith('mira-');
      });
      var pid = callGetpid();
      return { sequence: %d, tag: tag, pid: pid, processPid: Process.id,
        nativeHits: nativeHits, nativeOk: nativeHits >= 1 && pid === Process.id,
        javaHits: javaHits, javaValue: value, javaOk: javaHits >= 1 && value === true };
    } finally {
      try { if (startsWith !== null) startsWith.implementation = null; } catch (_) {}
      try { if (object !== null) object.$dispose(); } catch (_) {}
      try { listener.detach(); } catch (_) {}
    }
  },
  cleanup: function () { return true; }
};
""" % (sequence, sequence)


def isolated_source(sequence: int) -> str:
    prefix = ""
    if sequence == 6:
        prefix = "var source='user'; var args=['user']; var cleanupMethod='user'; var userRpc='user';\n"
    return prefix + source(sequence)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--install-id", required=True)
    parser.add_argument("--adb-serial", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--relay", required=True)
    parser.add_argument("--survival-seconds", type=int, default=60)
    args = parser.parse_args()
    server = MiraMcpServer(args.relay, "")
    report = {"installId": args.install_id, "scripts": [], "expectedErrors": []}
    before = server.tool_run_command({
        "installId": args.install_id,
        "command": "echo RUNNER_PRE; echo shell=$$ app=$(pidof com.vwww.mira)",
        "timeoutSeconds": 15,
    })
    session_id = before["sessionId"]
    app_pid = subprocess.check_output(
        ["adb", "-s", args.adb_serial, "shell", "pidof", "com.vwww.mira"], text=True
    ).strip()
    report.update({"sessionId": session_id, "pidBefore": app_pid, "before": before})

    error_cases = [
        ("syntax", "rpc.exports = { broken: function () {", "broken", "cleanup"),
        ("rpc", "rpc.exports={probe(){throw new Error('expected rpc failure')},cleanup(){return true}}", "probe", "cleanup"),
        ("cleanup", "rpc.exports={probe(){return true},cleanup(){throw new Error('expected cleanup failure')}}", "probe", "cleanup"),
    ]
    try:
        for sequence in range(1, 13):
            if sequence in (5, 9, 11):
                kind, source, method, cleanup = error_cases[(5, 9, 11).index(sequence)]
                try:
                    server.tool_frida_run_script({
                        "sessionId": session_id, "script": source,
                        "rpcMethod": method, "cleanupMethod": cleanup,
                        "timeoutSeconds": 30,
                    })
                    report["expectedErrors"].append({"kind": kind, "observed": False})
                except ToolError as error:
                    report["expectedErrors"].append({
                        "kind": kind, "observed": True, "error": str(error),
                    })
            result = server.tool_frida_run_script({
                "sessionId": session_id,
                "script": isolated_source(sequence),
                "rpcMethod": "probe",
                "rpcArgs": [f"runner-{sequence}"],
                "cleanupMethod": "cleanup",
                "timeoutSeconds": 30,
            })
            report["scripts"].append(result["frida"])

        after = server.tool_run_command({
            "sessionId": session_id,
            "command": "echo RUNNER_POST; echo shell=$$ app=$(pidof com.vwww.mira)",
            "timeoutSeconds": 15,
        })
        report["after"] = after
        report["pidAfterScripts"] = subprocess.check_output(
            ["adb", "-s", args.adb_serial, "shell", "pidof", "com.vwww.mira"], text=True
        ).strip()
        time.sleep(args.survival_seconds)
        report["pidAfterSurvival"] = subprocess.check_output(
            ["adb", "-s", args.adb_serial, "shell", "pidof", "com.vwww.mira"], text=True
        ).strip()
        report["survivalSeconds"] = args.survival_seconds
        report["ok"] = (
            len(report["scripts"]) == 12
            and all(item.get("ok") and item.get("executionMode") == "runner"
                    and item.get("cleanupError") is None
                    and item.get("rpcResult", {}).get("javaOk")
                    and item.get("rpcResult", {}).get("nativeOk")
                    for item in report["scripts"])
            and len(report["expectedErrors"]) == 3
            and all(item["observed"] for item in report["expectedErrors"])
            and before["sessionId"] == after["sessionId"]
            and app_pid == report["pidAfterScripts"] == report["pidAfterSurvival"]
        )
    finally:
        server.tool_close_terminal({"sessionId": session_id})
        Path(args.output).write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
