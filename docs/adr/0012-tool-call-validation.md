# 工具调用真实性校验：工具名 + 参数双重校验

在 Agent 执行 tool_call 前增加两层防御性校验：

## 工具名校验
`McpToolRegistry.getExecutor(toolId)` 返回 empty 时，说明 LLM 幻觉了工具名。不执行，注入错误信息"工具 xxx 不存在"喂回 LLM，继续循环。

## 参数校验
复用 `LLMMcpParameterExtractor` 中的 `coerceAndValidate` 逻辑（抽为公共方法），用 Tool 的 inputSchema（type、enum、required）对 LLM 返回参数做校验。非法参数不执行工具，注入错误信息让 LLM 重试。

## 校验失败不阻塞流程
两层校验失败均走问题 5 已定策略：注入错误信息作为 Observation 喂回 LLM，让 LLM 自主决定重试、换工具或直接回答。

理由：
1. 工具名校验零成本（一次 Map 查找），防止 NPE 导致 Agent 循环中断
2. 参数校验复用已有逻辑，纯内存操作，防止 LLM 幻觉枚举值导致静默错误结果
3. 不阻塞流程，给 LLM 自我修正机会
