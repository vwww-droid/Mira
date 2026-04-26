# Case Report Template(案例报告模板)

## 目录命名

默认生成到:

```text
cases/<githubName>-<yyyymmdd>-<topic>/
```

如果无法获取 GitHub 用户名, 使用本机用户名。如果仍无法获取, 使用 `anonymous`。

## 文件结构

```text
README.md
event.redacted.json
environment.md
evidence.summary.json
analysis.md
skill-notes.md
share.md
```

## README.md

```markdown
# <topic>

## 结论

- platform: <android|ios|cross-platform>
- boundary: <android sandbox|ios sandbox|mira virtual filesystem>
- observation_count: <number>
- unresolved_count: <number>

## 一句话摘要

<用一句话说明这次看到了什么不对劲, 以及还需要追什么>
```

## environment.md

必须包含:

1. 平台和设备上下文。
2. MCP(Model Context Protocol, 模型上下文协议)入口。
3. 权限边界。
4. 是否存在 `/mira` 或 `/mira/proc` 投影视图。
5. UI upload(界面上传)是否仅限 Mira App 自身窗口。

## evidence.summary.json

只写脱敏摘要:

```json
{
  "evidence_sources": [
    "android_proc_self_maps",
    "android_proc_self_status",
    "ios_mira_proc_projection"
  ],
  "observations": [],
  "possible_explanations": [],
  "unresolved_questions": [],
  "next_checks": [],
  "redactions": ["userId", "deviceId", "token", "private_paths"]
}
```

## analysis.md

结构:

1. 触发来源。
2. 可见证据。
3. 所有不对劲的观察。
4. 每条观察为什么不对劲。
5. 可能的风险解释和误报解释。
6. 仍未解释的问题。
7. 下一步建议。

## skill-notes.md

只写可复用经验, 不写敏感原文:

1. 新增判断模式。
2. 新增误报模式。
3. 新增现象记录规则。
4. 需要加入 skill 的候选文本。

## share.md

面向社区分享的脱敏版本。默认不发布, 只作为草稿。
