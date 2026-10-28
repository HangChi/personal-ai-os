# Feature Specification: 阶段 24 — 上下文窗口识别修正与单模型查询

**Feature Branch**: `024-window-probe-refine`

**Created**: 2026-10-29

**Status**: Implemented

## 现象

用户反馈：`deepseek-v4.1-flash` 官方口径 1M，但「获取模型列表」后窗口显示 **126K**。

126K = 129024 token = **131072 − 2048**。这个数字本身就是线索：它是 DashScope 的
通用实例默认值，不是任何具体模型的能力。

## 根因一：探测值覆盖了内置表

`ModelProfileService.appendModels` 的合并顺序是：

```java
Integer window = contextWindows.get(name);          // 用户提交值（含探测结果）
if (window == null) window = ModelContextWindows.lookup(name);   // 只在空时才查表
```

也就是「探测值优先，内置表只兜底」。而 DashScope 的
`GET /models/{name}` 返回的 `extra_info.default_envs.max_input_tokens` 是
**部署实例的默认参数**：

- 几乎所有模型都回 129024；
- `qwen-long` 文档 1M，它也回 129024；
- embedding / rerank 这类没有上下文概念的模型同样回 129024。

于是 `qwen-long` 的 1M 也一直被盖成 126K —— 只是没人注意到。
`deepseek-v4.1-flash` 更糟：内置表里根本没有它（只有 `deepseek-v3` 系列），
连兜底的机会都没有。

## 决策

### D1 取较大者，而不是「探测值优先」

新增 `reconcileWindow(probed, known)`：两边都有值时取 `max`。

- 取大的理由：探测值是「部署默认」，内置表是「文档能力」，分母要的是「能塞多少」。
- 不做「无条件用内置表」：端点比文档更了解自己的部署（自建 vLLM 可能只开 8K），
  取大只影响「内置表说更大」的情形，其余仍是端点说了算。
- 风险：内置表写错（偏大）会让预算分母偏大，用户选高档位时可能撞 400。
  因此这张表只收录窗口明确的模型，且界面上始终可改。

### D2 内置表补 `deepseek-v4.1-flash → 1M`

按公开口径 1,048,576（1024 进制，与前端档位生成方式一致）。

### D3 单模型查窗口：`POST /settings/model/profiles/probe-window`

批量探测只能补查 12 个模型（限流 + 预算），而「刚加入清单的模型」那一刻
不在优先名单里，窗口必然为空。用户要么整轮重探（1.8 秒 + 再撞一次 429），要么手填。

新接口只发一次 `GET /v1/models/{name}`，复用列表请求的鉴权头（协议差异不重写一遍），
返回 `{ok, window, kind, error}`。`kind` 区分两种情况：
`UNSUPPORTED`（端点不提供，不用管）与 `RATE_LIMITED`（过一会儿可重试）。

### D4 前端查询「只填空缺，不覆盖已填」

按钮解决的是「新加模型窗口为空」。但端点返回的常常是 126K 这类实例默认值 ——
若无条件覆盖，用户点一下就会把自己按文档填的 1M 冲掉，恰好制造本阶段要修的问题。

所以：空 → 写入；已有值且不同 → 只提示「端点返回 X，当前填的是 Y，未覆盖」。
要强制以端点为准，先清空再查。这与批量探测 `mergeProbedWindows` 的
「只补空缺」原则一致。

## 验收

1. 探测到 129024、内置表 1048576 → 落库 1048576。
2. 端点报 200000、内置表 131072 → 落库 200000（端点更大时以端点为准）。
3. 单模型查询：`/v1/models` 尾斜杠、已含 `/v1`、模型名带斜杠（`kimi/k3.1-pro`）都打到正确路径。
4. 429 → `kind=RATE_LIMITED`；404 / 无窗口字段 → `kind=UNSUPPORTED`。
5. 前端：已有值时点击查询不覆盖，仅提示。

## 存量数据需要一次重存

本修复只影响**之后的保存**。库里已经存成 126K 的配置不会自动更正，
需要在设置页把该配置重新保存一次（会走 `reconcileWindow`），
或在窗口输入框里点「查询」（先清空）。
