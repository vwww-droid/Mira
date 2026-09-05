#!/usr/bin/env python3
"""Verify binary RPC normalization through the Android product MCP path."""

import argparse
import base64
import hashlib
import json
from pathlib import Path
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from mira.mcp.server import MiraMcpServer, ToolError


SOURCES = {
    "json": r"""
rpc.exports = {
  probe: function () {
    return {
      scalar: 7,
      text: '你好🙂',
      array: [true, null, { nested: 'value' }],
      specialKeys: JSON.parse('{"__proto__":{"x":1},"constructor":"ok"}')
    };
  },
  cleanup: function () { return true; }
};
""",
    "topArrayBuffer": r"""
rpc.exports = {
  probe: function () { return new Uint8Array([228, 189, 160, 0, 240, 159, 153, 130]).buffer; },
  cleanup: function () { return true; }
};
""",
    "nestedBinary": r"""
rpc.exports = {
  probe: function () {
    var backing = new Uint8Array([99, 8, 0, 7, 88]);
    return {
      nested: [new Uint8Array([0, 255, 1]).buffer, { empty: new ArrayBuffer(0) }],
      slice: backing.subarray(1, 4)
    };
  },
  cleanup: function () { return true; }
};
""",
    "recovery": r"""
rpc.exports = {
  probe: function () {
    return { cleanupCount: globalThis.__miraBinaryCleanupCount || 0, recovered: true };
  },
  cleanup: function () { delete globalThis.__miraBinaryCleanupCount; return true; }
};
""",
}

FAILURE_EXPRESSIONS = {
    "circular": "var value = {}; value.self = value; return value;",
    "undefined": "return undefined;",
    "bigint": "return BigInt(7);",
}


def hook_source(sequence):
    return r"""
// distinct-hook-source-%d
var listener = null;
var startsWith = null;
var stringObject = null;
rpc.exports = {
  probe: function () {
    var address = Module.getGlobalExportByName !== undefined
      ? Module.getGlobalExportByName('getpid') : Module.getExportByName(null, 'getpid');
    var nativeHits = 0;
    var javaHits = 0;
    var javaValue = false;
    listener = Interceptor.attach(address, { onEnter: function () { nativeHits++; } });
    var callGetpid = new NativeFunction(address, 'int', []);
    Java.performNow(function () {
      var StringClass = Java.use('java.lang.String');
      var overload = StringClass.startsWith.overload('java.lang.String');
      startsWith = overload;
      overload.implementation = function (argument) {
        javaHits++;
        return overload.call(this, argument);
      };
      stringObject = StringClass.$new('mira-binary-%d');
      javaValue = stringObject.startsWith('mira-');
    });
    var pid = callGetpid();
    return { sequence: %d, processPid: Process.id, pid: pid, nativeHits: nativeHits,
      javaHits: javaHits, nativeOk: nativeHits >= 1 && pid === Process.id,
      javaOk: javaHits >= 1 && javaValue === true };
  },
  cleanup: function () {
    try { if (startsWith !== null) startsWith.implementation = null; } catch (_) {}
    try { if (listener !== null) listener.detach(); } catch (_) {}
    try { if (stringObject !== null) stringObject.$dispose(); } catch (_) {}
    startsWith = null; listener = null; stringObject = null;
    return true;
  }
};
""" % (sequence, sequence, sequence)


def decode_binary(value):
    if not isinstance(value, dict) or value.get("type") != "bytes" or value.get("encoding") != "base64":
        raise AssertionError(f"invalid binary envelope: {value!r}")
    return base64.b64decode(value.get("dataBase64", ""), validate=True)


def app_pid(serial):
    return subprocess.check_output(
        ["adb", "-s", serial, "shell", "pidof", "com.vwww.mira"], text=True
    ).strip()


def run_task(server, session_id, source, rpc_args=None):
    return server.tool_frida_run_script({
        "sessionId": session_id,
        "script": source,
        "rpcMethod": "probe",
        "rpcArgs": rpc_args or [],
        "cleanupMethod": "cleanup",
        "timeoutSeconds": 30,
    })


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--install-id", required=True)
    parser.add_argument("--adb-serial", required=True)
    parser.add_argument("--relay", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    server = MiraMcpServer(args.relay, "")
    report = {"installId": args.install_id, "adbSerial": args.adb_serial, "results": {}, "errors": []}
    before = server.tool_run_command({
        "installId": args.install_id,
        "command": "echo shell=$$ app=$(pidof com.vwww.mira); cat $HOME/../.mira-bootstrap-state 2>/dev/null || true",
        "timeoutSeconds": 15,
    })
    session_id = before["sessionId"]
    report.update({"sessionId": session_id, "before": before, "pidBefore": app_pid(args.adb_serial)})
    try:
        for name in ("json", "topArrayBuffer", "nestedBinary"):
            report["results"][name] = run_task(server, session_id, SOURCES[name])["frida"]
        for sequence in (1, 2):
            name = f"hook{sequence}"
            report["results"][name] = run_task(server, session_id, hook_source(sequence))["frida"]

        for name, expression in FAILURE_EXPRESSIONS.items():
            source = """
rpc.exports = {
  probe: function () { %s },
  cleanup: function () {
    globalThis.__miraBinaryCleanupCount = (globalThis.__miraBinaryCleanupCount || 0) + 1;
    return true;
  }
};
""" % expression
            try:
                run_task(server, session_id, source)
                report["errors"].append({"name": name, "observed": False})
            except ToolError as error:
                report["errors"].append({"name": name, "observed": True, "error": str(error)})

        report["results"]["recovery"] = run_task(server, session_id, SOURCES["recovery"])["frida"]
        report["after"] = server.tool_run_command({
            "sessionId": session_id,
            "command": "echo shell=$$ app=$(pidof com.vwww.mira); sha256sum $PREFIX/bin/mira-frida-agent.py; cat $HOME/../.mira-bootstrap-state",
            "timeoutSeconds": 15,
        })
        report["pidAfter"] = app_pid(args.adb_serial)

        ordinary = report["results"]["json"]["rpcResult"]
        top = report["results"]["topArrayBuffer"]["rpcResult"]
        nested = report["results"]["nestedBinary"]["rpcResult"]
        recovery = report["results"]["recovery"]["rpcResult"]
        expected_errors = all(
            item["observed"] and "not JSON-compatible" in item.get("error", "")
            for item in report["errors"]
        )
        report["checks"] = {
            "ordinaryJsonUnchanged": ordinary == {
                "scalar": 7,
                "text": "你好🙂",
                "array": [True, None, {"nested": "value"}],
                "specialKeys": {"__proto__": {"x": 1}, "constructor": "ok"},
            },
            "topArrayBufferExact": decode_binary(top) == b"\xe4\xbd\xa0\x00\xf0\x9f\x99\x82",
            "nestedArrayBufferExact": decode_binary(nested["nested"][0]) == b"\x00\xff\x01",
            "emptyArrayBufferExact": decode_binary(nested["nested"][1]["empty"]) == b"",
            "typedArrayOffsetExact": decode_binary(nested["slice"]) == b"\x08\x00\x07",
            "unsupportedValuesExplicit": expected_errors,
            "cleanupRanOnEveryFailure": recovery == {"cleanupCount": len(FAILURE_EXPRESSIONS), "recovered": True},
            "javaNativeHooks": all(
                report["results"][f"hook{sequence}"]["rpcResult"].get("javaOk")
                and report["results"][f"hook{sequence}"]["rpcResult"].get("nativeOk")
                for sequence in (1, 2)
            ),
            "sameMiraPid": report["pidBefore"] == report["pidAfter"],
            "samePtySession": before["sessionId"] == report["after"]["sessionId"],
        }
        report["ok"] = all(report["checks"].values())
        report["localAgentSha256"] = hashlib.sha256(
            (Path(__file__).resolve().parents[2] / "mira/mcp/frida_agent.py").read_bytes()
        ).hexdigest()
    finally:
        try:
            server.tool_close_terminal({"sessionId": session_id})
        finally:
            Path(args.output).parent.mkdir(parents=True, exist_ok=True)
            Path(args.output).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    raise SystemExit(0 if report.get("ok") else 1)


if __name__ == "__main__":
    main()
