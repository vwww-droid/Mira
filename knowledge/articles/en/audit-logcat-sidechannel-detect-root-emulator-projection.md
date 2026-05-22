---
title: Detecting root, emulators, and scrcpy-like projection through an Android audit-log side channel
description: How procfs-triggered SELinux audit logs can leak target process domains through logcat, and how that signal can detect Magisk, Android emulators, and scrcpy-like projection.
tags: android, security, reverseengineering, mobile
published: false
---

## Overview

This post is about an Android audit-log information leak fixed by the AOSP change [Hide procfs related audit messages from appdomain](https://android-review.googlesource.com/c/platform/system/logging/+/3725346).

Inside a restricted third-party Android app sandbox, even when the app cannot directly read another process under `/proc/<pid>`, touching procfs may trigger SELinux audit logs. If those logs are visible through logcat, the `tcontext` field can reveal the SELinux domain of the target process.

In other words, this is a detection technique based on an audit-log side channel.

I tried three scenarios with this idea:

1. Root environment: observe Magisk-related domains through `tcontext=u:r:magisk:s0`.
2. Emulator environment: observe emulator traits through `qemu_props`, `goldfish`, and `ranchu` related domains.
3. Possible active screen projection: observe consecutive high-PID `u:r:shell:s0` targets that look like scrcpy-driven shell automation.

The experiments use the open-source [Mira](https://github.com/vwww-droid/Mira) framework for runtime risk analysis. A Chinese introduction to Mira is available in [this Kanxue article](https://bbs.kanxue.com/thread-291041.htm).

Compared with repeatedly packaging, installing, and triggering an app, Mira lets an AI iterate shell probes directly. For this research, tuning PID ranges, logcat match rules, and scan windows was quick and low effort.

**The leak mechanism, AOSP fix, and evasion discussion are at the end of this article.**

## Reproduction

### Magisk root environment

I asked an AI to call Mira MCP and run a chunked scan. The scan started at PID `900`, touched `/proc/<pid>` in 25-PID windows, and stopped after a hit.

```log
[Magisk audit side-channel scan]
scan pid=900-924
scan pid=925-949
scan pid=950-974
scan pid=975-999
scan pid=1000-1024
scan pid=1025-1049
hit_window=1025-1049
05-20 21:48:56.216 ... avc: denied { getattr } for comm="sh" path="/proc/1028" dev="proc" ... scontext=u:r:untrusted_app_27:s0:... tcontext=u:r:magisk:s0 tclass=dir permissive=0 app=com.vwww.mira
05-20 21:48:56.216 ... avc: denied { getattr } for comm="sh" path="/proc/1029" dev="proc" ... scontext=u:r:untrusted_app_27:s0:... tcontext=u:r:magisk:s0 tclass=dir permissive=0 app=com.vwww.mira
05-20 21:48:56.219 ... avc: denied { getattr } for comm="sh" path="/proc/1048" dev="proc" ... scontext=u:r:untrusted_app_27:s0:... tcontext=u:r:magisk:s0 tclass=dir permissive=0 app=com.vwww.mira
```

![Screenshot](https://raw.githubusercontent.com/vwww-droid/Mira/main/i/image-1.png)

After the app sandbox touches procfs, the audit log directly exposes `tcontext=u:r:magisk:s0`.

### AVD

AVD means the emulator bundled with Android Studio. This demo uses an Android 13 image from Android Studio on an Apple Silicon Mac.

In the [Magisk root environment section](#magisk-root-environment), I used AI-driven MCP calls to demonstrate how fast Mira can iterate. After understanding the mechanism, manually using the controlled third-party shell is often faster.

First, use adb to inspect candidate processes and names.

```sh
❯ adb shell
emu64a:/ $ ps -e | grep qemu
root           158     1 10780188  2184 0                   0 S qemu-props
emu64a:/ $
```

Then probe it. The `qemu_props` trait appears.

![Screenshot](https://raw.githubusercontent.com/vwww-droid/Mira/main/i/image-2.png)

Other emulator-related candidates can also be found:

```sh
❯ adb shell
emu64a:/ $ ps -e | grep qemu
root           158     1 10780188  2184 0                   0 S qemu-props
emu64a:/ $ ps -e | grep goldfish
root           152     2       0      0 0                   0 S [irq/46-goldfish]
media          317     1 11086960  4008 0                   0 S android.hardware.media.c2@1.0-service-goldfish
radio          370     1 11113696  3372 0                   0 S libgoldfish-rild
emu64a:/ $ ps -e | grep anchu
gps            777     1 10949284  2056 0                   0 S android.hardware.gnss@2.0-service.ranchu
emu64a:/ $
```

These are less obvious than `qemu`, but they can also be triggered in some images.

![Screenshot](https://raw.githubusercontent.com/vwww-droid/Mira/main/i/image-3.png)

### scrcpy projection

After Android 9 tightened permissions, detecting scrcpy became difficult because scrcpy hides its runtime traces well. Audit logs provide another angle.

From the scrcpy source code, newer scrcpy versions start through adb shell, launch `app_process`, run scrcpy's jar logic, and delete `/data/local/tmp/scrcpy-server.jar` after startup. That leaves no stable file artifact.

Following the same method as [AVD](#avd), inspect from adb shell first. During projection, the projection service appears as a tight `sh -> app_process -> app_process` PID cluster.

```log
emu64a:/ $ ps -e | grep shell
shell          351     1 11105424  7368 0                   0 S adbd
shell          508   351 10789444  2236 __arm64_sys_nanosleep 0 S process-tracker
shell         5615   351 10815908  3168 __do_sys_rt_sigsuspend 0 S sh
shell         6212   351 10858824  3256 unix_stream_data_wait 0 S abb

shell        27769   351 10797476  2632 __do_sys_rt_sigsuspend 0 S sh
shell        27771 27769 14129832 135132 do_epoll_wait      0 S app_process
shell        27800 27771 13609408 102484 pipe_read          0 S app_process
```

The audit log does not directly expose `app_process`, but it can show several nearby high-PID targets with `u:r:shell:s0`.

![Screenshot](https://raw.githubusercontent.com/vwww-droid/Mira/main/i/image-4.png)

The start-stop comparison also matched expectations. After stopping projection, scanning the same high-PID range returned:

```log
START=10000 END=18000 CHUNK=100 STEP=100
no_shell_domain_hit
```

A normal user with adb enabled but without scrcpy projection usually will not have this high-PID cluster. Therefore, **three consecutive nearby `u:r:shell:s0` targets** can be used as a strategy for detecting suspected scrcpy-like projection. This still needs online-environment validation. Here it is presented as a research direction.

To hide this trace, one would need either a patched system or a root-level plugin to hook this framework path. That moves the problem into root-evasion territory and often introduces new traits, raising the attacker's cost.

## Notes

### Scan stability

This side channel should not be scanned with a careless wide range. **In experiments, a single PID hit could succeed while a wide-window scan missed it.**

There are two main reasons:

1. SELinux audit logs are rate-limited.
2. A large window creates many unrelated denials. If the target PID is late in the window, useful lines may be buried by noise.

Prefer small or overlapping windows:

```sh
START=1000
END=2500
CHUNK=10
STEP=10
WAIT_SEC=1
LOG_TAIL=400
MATCH='tcontext=u:r:qemu_props:s0|tcontext=u:r:[^ ]*(goldfish|ranchu|qemu)[^ ]*:s0'
```

If `CHUNK=50` misses, lower it to `CHUNK=10`, or use an overlapping strategy such as `CHUNK=50 STEP=25`. Do not only increase `sleep`, because the failure is usually not log latency, but audit rate limiting and window noise.

### Execution syntax details

Touching `/proc/<pid>` from the current shell is not necessarily equivalent to touching it from a new `sh script.sh` child shell.

Recommended execution models:

1. Touch from the current shell.
2. Or write a file and load it in the current shell with `. script.sh`.

## AOSP original leak path analysis

The core code path is `system/logging/logd/LogAudit.cpp`, specifically `LogAudit::logPrint`.

After logd receives an audit message, it first formats the message as a string:

```cpp
char* str = nullptr;
va_start(args, fmt);
int rc = vasprintf(&str, fmt, args);
va_end(args);
```

At this point, `str` already contains raw audit information such as `dev="proc"`, `scontext`, `tcontext`, `tclass`, `comm`, and `path`. For this article, the important combination is `dev="proc"` plus `tcontext`.

The code then looks for the `pid=` field in the audit string and calls `pidToUid` to resolve the UID that triggered the audit:

```cpp
static const char pid_str[] = " pid=";
char* pidptr = strstr(str, pid_str);
...
uid = android::pidToUid(pid);
```

That UID is later passed to the logging system. For procfs audit events triggered by an app, the parsed UID falls under `AID_APP_START`.

The original code calls `auditParse(str, uid)`:

```cpp
denial_metadata = auditParse(str, uid);
```

`auditParse` parses `scontext`, `tcontext`, and `tclass`, and tries to match bug metadata. More importantly, when the UID belongs to the app range, it appends the app package name:

```cpp
if (uid >= AID_APP_START && uid <= AID_APP_END) {
    char* uidname = android::uidToName(uid);
    if (uidname) {
        result.append(" app="s + uidname);
        free(uidname);
    }
}
```

So logd does more than forward the raw audit string. It enriches the message for debugging. That is useful for system diagnostics, but when the log is visible to apps, it makes the side channel easier to read.

The code then writes the audit string and appended metadata into the events buffer:

```cpp
rc = logbuf->Log(LOG_ID_EVENTS, now, uid, pid, tid,
                 reinterpret_cast<char*>(event),
                 (message_len <= UINT16_MAX) ? (uint16_t)message_len : UINT16_MAX);
```

The `uid`, `pid`, and `tid` used here come from the previous parsing step. In other words, this audit event enters the logging system with information related to the triggering app.

Later, the code also builds a main-buffer log line. It parses `comm="..."` from the audit string, builds a new log message, and appends denial metadata.

The result is that the same procfs denial may enter both the main and events buffers. If the app side can read the relevant log surface, `tcontext` becomes visible.

The root cause can be summarized as:

`hidepid=2` protects the normal procfs read surface, but the original logd path forwards diagnostics from failed procfs accesses into an app-visible logging surface.

Therefore, the app does not need to directly read `/proc/<pid>`. It only needs to touch `/proc/<pid>` to trigger a `getattr` denial, then read `tcontext` from logcat and infer the target process's SELinux domain.

The information flow is:

```text
App touches /proc/<pid>
  -> SELinux denies the access
  -> kernel produces an audit record
  -> audit record reaches logd through netlink
  -> logd writes it into main or events log buffers
  -> app-side logcat sees tcontext
```

This does not bypass SELinux permission checks. It abuses diagnostic information produced after the permission check fails.

## AOSP patch author's fix

Patch 3725346 adds the following logic:

```cpp
// Hide procfs related audit messages from appdomain to prevent selinux context leak
if (uid >= AID_APP_START && strstr(str, "dev=\"proc\"")) {
    free(str);
    return 0;
}
```

The filter has two conditions.

First, `uid >= AID_APP_START`. The filter targets audit events triggered by app UIDs, not every system audit event. This avoids breaking diagnostics for system services and native system processes.

Second, `strstr(str, "dev=\"proc\"")`. The filter is scoped to procfs-related audit events. It does not broadly filter all SELinux denials. It specifically blocks the path that leaks other process domains through `/proc/<pid>`.

The fix:

1. Does not change SELinux decisions.
2. Does not change the procfs permission model.
3. Does not discard all system debugging information.
4. Blocks app-triggered procfs audit records from entering the main and events log buffers.

It fixes the forwarding boundary in logd. Access can still fail, and the system can still audit, but apps can no longer observe other processes' `tcontext` through log buffers.

## SELinux audit structure

A typical SELinux denial looks like this:

```text
avc: denied { getattr } for comm="sh" path="/proc/1030" dev="proc" ... scontext=u:r:untrusted_app:s0 ... tcontext=u:r:magisk:s0 tclass=dir permissive=0
```

Key fields:

| Field | Meaning | Why it matters here |
| --- | --- | --- |
| `avc: denied` | SELinux denied access | Shows that the denial path was reached |
| `{ getattr }` | Access operation | Directory metadata probing is enough |
| `path` | Target path | Points to `/proc/<pid>` |
| `dev="proc"` | Filesystem source | Identifies procfs-related audit |
| `scontext` | Source security domain | Usually the app sandbox domain |
| `tcontext` | Target security domain | The core leaked side-channel field |
| `tclass` | Target object class | For example `dir` |

AOSP SELinux docs state that SELinux denials enter `dmesg` and `logcat`, and that `scontext`, `tcontext`, and `tclass` describe the source, target, and target object class. The design goal is to help system developers debug policy issues, but under certain log visibility conditions it becomes an information side channel.


## Evasion and patching

### ZN-AuditPatch

Update on 2026-05-22 21:17:53.

[ZN-AuditPatch](https://github.com/aviraxp/ZN-AuditPatch) rewrites known root-related target contexts. The relevant code is in [`hook.cpp#L36`](https://github.com/aviraxp/ZN-AuditPatch/blob/master/module/src/main/cpp/hook.cpp#L36):

```c
constexpr std::string_view target_context = "tcontext=u:r:priv_app:s0:c512,c768";
constexpr std::string_view source_contexts[] = {
        "tcontext=u:r:su:s0",
        "tcontext=u:r:magisk:s0"
};
```

The patch only filters `magisk` and `su`, then replaces them with the fixed string `tcontext=u:r:priv_app:s0:c512,c768`.

My first reaction was that this had two problems:

1. Matching only `tcontext=u:r:magisk:s0` and `tcontext=u:r:su:s0` does not cover the emulator and projection signals discussed in this article. Examples include `qemu_props`, `goldfish`, and `ranchu` in emulator scenarios, and high-PID `u:r:shell:s0` clusters in scrcpy-like scenarios. I therefore thought it would be better to rewrite all audit messages related to `path="/proc/..."` into `priv_app`.
2. I also assumed that the hard-coded `tcontext=u:r:priv_app:s0:c512,c768` string was itself a fingerprint, and tried to replace it with a stable randomized value such as `u:r:priv_app:s0:c115,c371,c512,c768`.

After feedback from [@mb_bvvcoitr](https://bbs.kanxue.com/homepage-1006272.htm), I realized that this conclusion was not rigorous. The key detail is that `c512,c768` is not an arbitrary hard-coded suffix. It comes from Android `libselinux` MLS/MCS category generation.

The relevant AOSP code is [`external/selinux/libselinux/src/android/android_seapp.c`](https://android.googlesource.com/platform/external/selinux/+/main/libselinux/src/android/android_seapp.c#691), in `set_range_from_level()`:

```c
/* Sets the categories of ctx based on the level request */
int set_range_from_level(context_t ctx, enum levelFrom levelFrom, uid_t userid, uid_t appid)
{
    char level[255];
    switch (levelFrom) {
    case LEVELFROM_NONE:
        strncpy(level, "s0", sizeof level);
        break;
    case LEVELFROM_APP:
        snprintf(level, sizeof level, "s0:c%u,c%u",
             appid & 0xff,
             256 + (appid>>8 & 0xff));
        break;
    case LEVELFROM_USER:
        snprintf(level, sizeof level, "s0:c%u,c%u",
             512 + (userid & 0xff),
             768 + (userid>>8 & 0xff));
        break;
    case LEVELFROM_ALL:
        snprintf(level, sizeof level, "s0:c%u,c%u,c%u,c%u",
             appid & 0xff,
             256 + (appid>>8 & 0xff),
             512 + (userid & 0xff),
             768 + (userid>>8 & 0xff));
        break;
    default:
        return -1;
    }
    if (context_range_set(ctx, level)) {
        return -2;
    }
    return 0;
}
```

In the `LEVELFROM_USER` branch, the range is formatted as `s0:c%u,c%u`. The two categories are computed as `512 + (userid & 0xff)` and `768 + (userid >> 8 & 0xff)`.

The split from Linux UID to Android `userid` and `appid` happens in `seapp_context_lookup_internal()` in the same file:

```c
userid = uid / AID_USER_OFFSET;
appid = uid % AID_USER_OFFSET;
```

Therefore, when the Android `userId` is 0, `LEVELFROM_USER` produces `512 + 0 = c512` and `768 + 0 = c768`, resulting in `s0:c512,c768`.

Why does `priv_app` use that branch? The common AOSP rule can be found in [`system/sepolicy/private/seapp_contexts`](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/android16-release/private/seapp_contexts#201):

```text
user=_app isPrivApp=true domain=priv_app type=privapp_data_file levelFrom=user
```

In other words, `priv_app` normally derives its MLS/MCS categories from the Android `userId`, not from the concrete app. For Android user 0, `u:r:priv_app:s0:c512,c768` is the natural-looking form.

In multi-user, work-profile, or app-cloning environments, the Android `userId` may not be 0. In that case, the `priv_app` suffix should be recomputed from the actual `userId`. For example, `userId=10` produces `s0:c522,c768`. But this is still rule-based computation, not unconstrained randomization.

So my earlier PatchAudit rewrite was semantically mismatched: it used the `priv_app` domain, but the MLS/MCS suffix looked closer to an `untrusted_app` `LEVELFROM_ALL` form.

```diff
- tcontext=u:r:magisk:s0
+ tcontext=u:r:priv_app:s0:c115,c371,c512,c768
```

This replacement may be syntactically valid, but it is semantically awkward. It looks like ordinary app-level categories were attached to the `priv_app` domain, which can become a new fingerprint under default AOSP rules.

The more accurate conclusion is:

1. ZN-AuditPatch is narrow because it only handles `magisk` and `su`.
2. `tcontext=u:r:priv_app:s0:c512,c768` should not be treated as a fingerprint by default. It matches the common MLS/MCS form of `priv_app` under Android user 0.
3. If procfs audit rewriting is generalized, the replacement must follow the target device's real `seapp_contexts` semantics and derive categories from the actual Android `userId`.
4. For low-PID root-related processes such as `magisk`, `priv_app` may be a more natural replacement. For scrcpy-like shell chains, whether `priv_app` is a natural replacement needs separate analysis and should not be assumed.

Thanks again to [@mb_bvvcoitr](https://bbs.kanxue.com/homepage-1006272.htm) for the correction.

## Source cases and scripts

This post is distilled from the following Mira cases:

1. [Android proc audit side channel detects Magisk SELinux context](https://github.com/vwww-droid/Mira/blob/main/knowledge/cases/zh/2026/2026-05-19-android-proc-audit-magisk-sidechannel.md)
2. [Android emulator proc audit side channel exposes qemu SELinux context](https://github.com/vwww-droid/Mira/blob/main/knowledge/cases/zh/2026/2026-05-20-android-emulator-proc-audit-sidechannel.md)
3. [Android high-PID shell proc audit side channel suggests scrcpy projection](https://github.com/vwww-droid/Mira/blob/main/knowledge/cases/zh/2026/2026-05-20-android-high-pid-shell-audit-sidechannel-scrcpy.md)

Companion scripts, with parameters such as PID ranges and wait times expected to be tuned per device:

1. [mira-proc-audit-sidechannel.sh](https://github.com/vwww-droid/Mira/blob/main/tools/android/mira-proc-audit-sidechannel.sh)
2. [mira-emulator-audit-sidechannel.sh](https://github.com/vwww-droid/Mira/blob/main/tools/android/mira-emulator-audit-sidechannel.sh)
3. [mira-high-pid-shell-audit-sidechannel.sh](https://github.com/vwww-droid/Mira/blob/main/tools/android/mira-high-pid-shell-audit-sidechannel.sh)

Mira is mainly a debugging entry point here. The detection logic does not need to be hard-coded into the app first. It is better to validate the idea with shell scripts, then decide whether it is stable enough to become a reusable case or rule based on results from different devices.

## Takeaway

This side channel does not bypass SELinux. It relies on diagnostics generated after SELinux correctly denies access. That makes the bug interesting: the protected data path is blocked, but the failure report can still reveal enough context to classify the target process.

For defenders, the AOSP fix closes the cleanest leak path by filtering app-triggered procfs audit messages before they reach app-visible log buffers. For researchers, the pattern is still useful as a reminder to inspect diagnostic surfaces, not only successful read APIs.

## References

1. AOSP Gerrit: [Hide procfs related audit messages from appdomain](https://android-review.googlesource.com/c/platform/system/logging/+/3725346)
2. AOSP Docs: [Validate SELinux](https://source.android.com/docs/security/features/selinux/validate)
3. AOSP Docs: [Understand logging](https://source.android.com/docs/core/tests/debug/understanding-logging)
4. [ZN-AuditPatch](https://github.com/aviraxp/ZN-AuditPatch)
5. [PatchAudit](https://github.com/vwww-droid/PatchAudit)
