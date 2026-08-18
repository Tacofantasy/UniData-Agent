# Agent 重构实现规划

基于 18 条 ADR，按依赖关系划分为 5 个 Phase，每个 Phase 产出可独立验证的成果。

---

## Phase 1：LLM Service 扩展原生 Function Calling
> 对应 ADR-0002 | 预估 5-8 天 | 依赖：无

这是整个改造的技术基石。没有原生 FC，Agent 的 ReAct 循环无法可靠运行。

### 1.1 ChatMessage 扩展
- `ChatMessage.Role` 枚举增加 `TOOL`
- `ChatMessage` 增加 `toolCalls` 字段（assistant 消息携带工具调用请求）
- `ChatMessage` 增加 `toolCallId` 字段（tool 角色消息关联对应的 tool_call）

### 1.2 ChatRequest 扩展
- `tools` 字段从预留变为实际可用（List<ToolDefinition>）
- 新增 `toolChoice` 字段（auto/none/required）

### 1.3 LLMService 接口扩展
- 新增方法返回结构化响应（含 content + toolCalls），而非纯 String
- 保留现有 `chat()` 返回 String 的方法（向后兼容 WORKFLOW 模式）

### 1.4 AbstractOpenAIStyleChatClient 改造
- `buildRequestBody()`：当 `request.tools` 非空时，在请求体中增加 `tools` JSON 数组
- `buildMessages()`：支持 `TOOL` 角色（输出 `role: "tool"` + `content` + `tool_call_id`）；ASSISTANT 角色支持输出 `tool_calls` 字段
- `extractChatContent()`：增加对 `tool_calls` 响应的解析，返回结构化对象

### 1.5 验证标准
- 单独调用 `llmService.chat()` 传入 tools 定义，能正确收到 tool_calls 响应
- 传入 tool 角色消息（工具结果），LLM 能基于结果继续推理
- WORKFLOW 模式不受影响（不传 tools 时行为不变）

---

## Phase 2：RAG Tool 封装与注册
> 对应 ADR-0004,0007,0008,0018 | 预估 2-3 天 | 依赖：Phase 1

将 RAG 检索能力封装为 MCP Tool，注册到现有工具注册表。

### 2.1 RagSearchToolExecutor
- 实现 `McpToolExecutor` 接口
- `getToolDefinition()`：定义工具名 `rag_search`、描述、inputSchema（仅 question 一个 string 参数）
- `execute(params)`：
  1. 从 params 取 question
  2. 调用 `IntentResolver.resolve()` 做意图分类（跳过 QueryRewrite）
  3. 调用 `RetrievalEngine.retrieve()` 检索
  4. 将 sources 和 grounding chunks 存入 SourcesAccumulator
  5. 返回格式化的 kbContext 文本作为 CallToolResult

### 2.2 SourcesAccumulator
- Request-scoped Spring Bean
- 方法：`accumulateSources(List<SourceRef>)`、`accumulateGroundingChunks(List<GroundingChunk>)`
- 方法：`getMergedSources()`、`getMergedGroundingChunks()`
- 支持多次 RAG Tool 调用的 sources 累加

### 2.3 McpToolToFcConverter
- 将 `McpSchema.Tool` 转换为 OpenAI FC 格式的工具定义
- 结构映射：name→name, description→description, inputSchema→parameters
- 一个静态工具方法即可

### 2.4 t_message 表扩展
- 新增 `message_type` 字段（VARCHAR），区分 `NORMAL`（正常对话）和 `TOOL_SUMMARY`（工具调用摘要）
- 默认值为 `NORMAL`

### 2.5 验证标准
- `McpToolRegistry.listAllTools()` 包含 `rag_search`
- 通过 `registry.getExecutor("rag_search").execute({"question": "xxx"})` 能拿到检索结果文本
- SourcesAccumulator 能累加多次调用的 sources

---

## Phase 3：AgentChatPipeline ReAct 循环主逻辑
> 对应 ADR-0001,0003,0004,0005,0006,0015,0016,0018 | 预估 4-6 天 | 依赖：Phase 1 + Phase 2

整个改造的核心产物。

### 3.1 ChatPipeline 接口
- 新建接口 `ChatPipeline`，方法 `void execute(StreamChatContext ctx)`
- `StreamChatPipeline` 加 `implements ChatPipeline`（不改实现逻辑）
- `AgentChatPipeline` 实现 `ChatPipeline`

### 3.2 AgentChatPipeline.execute() 主循环
```
loadMemory(ctx)                           // 复用现有记忆加载
List<ChatMessage> messages = buildInitialMessages(ctx)  // system + history + user

while (iteration < maxIterations) {
    ChatResponse resp = llmService.chatWithTools(request)  // 同步调用，含 tools
    
    if (resp.hasToolCalls()) {
        // 并行执行所有 tool_calls（ADR-0015）
        List<ChatMessage> toolResults = executeToolsParallel(resp.toolCalls)
        messages.add(assistantMessage(resp.toolCalls))
        messages.addAll(toolResults)
        iteration++
    } else {
        // 最终答案
        callback.onContent(resp.content())     // 一次性推送（ADR-0006）
        callback.onSources(accumulator.getMergedSources())     // 传递来源（ADR-0018）
        callback.onGroundingChunks(accumulator.getMergedGroundingChunks())
        saveFinalAnswer(ctx, resp.content())  // 存最终答案（ADR-0003）
        saveToolCallSummary(ctx)              // 存工具调用摘要（ADR-0003）
        callback.onComplete()
        return
    }
}

// 超限兜底：强制合成（ADR-0004）
String forcedAnswer = forceSynthesis(ctx, messages)
callback.onContent(forcedAnswer)
callback.onComplete()
```

### 3.3 工具执行与校验（ADR-0012,0015）
- `executeToolsParallel(toolCalls)`：
  1. 工具名校验：`registry.getExecutor(toolId)` 为空 → 注入错误
  2. 参数校验：复用 `coerceAndValidate` 逻辑 → 非法 → 注入错误
  3. 合法工具并行执行（CompletableFuture）
  4. 失败工具注入错误信息作为 Observation
  5. 返回 tool 角色消息列表

### 3.4 工具调用摘要生成（ADR-0003）
- 循环结束后，将所有 toolCall 记录压缩为结构化摘要
- 格式示例："调用 rag_search（查询：产品保修政策，找到 5 条结果）；调用 sales_query（查询：华东本月汇总，总销售额 120 万）"
- 存入 t_message，message_type = TOOL_SUMMARY

### 3.5 deepThinking 处理（ADR-0016）
- 中间轮：`ChatRequest.thinking = false`
- 最终答案轮：`ChatRequest.thinking = ctx.isDeepThinking()`
- 强制合成轮：`ChatRequest.thinking = ctx.isDeepThinking()`

### 3.6 Trace 节点（ADR-0011）
- `@RagTraceNode(name = "agent-llm-call", type = "AGENT_LLM_CALL")` 标注 LLM 调用方法
- `@RagTraceNode(name = "agent-tool-exec", type = "AGENT_TOOL_EXEC")` 标注工具执行方法
- `@RagTraceNode(name = "agent-final-synthesis", type = "AGENT_FINAL_SYNTHESIS")` 标注强制合成方法
- extraData 存 tool_call 参数和结果摘要

### 3.7 验证标准
- 闲聊问题（"你好"）→ Agent 直接回答，不调工具，1 轮完成
- 知识库问题 → Agent 调用 rag_search → 基于结果回答，2 轮完成
- 多工具问题 → Agent 并行调用多个工具 → 综合回答
- 工具失败 → Agent 收到错误 → 换工具或直接回答
- 超过 5 轮 → 强制合成
- Trace 页面能看到每轮 LLM 调用和工具执行节点
- 对话历史中只存最终答案 + 工具调用摘要

---

## Phase 4：Pipeline 路由 + Prompt + 配置
> 对应 ADR-0009,0010,0005 | 预估 1-2 天 | 依赖：Phase 3

打通入口，让 Agent 模式真正可用。

### 4.1 RAGChatServiceImpl 分流
- 注入 `OrchestrationProperties` 和两个 `ChatPipeline` 实现
- 按 `mode` 选择 Pipeline：AGENT → AgentChatPipeline，WORKFLOW → StreamChatPipeline

### 4.2 AGENT_MAIN Prompt 内容
- 编写简洁指令式 System Prompt（ADR-0010）
- 写入数据库 `t_agent_prompt` 表，关联内置智能体
- 内容示例：
```
你是一个企业智能助手。你可以使用以下工具来回答用户问题：
- 当用户问及知识库内容时，调用 rag_search 工具检索
- 当用户问及销售数据时，调用 sales_query 工具
- 如果你可以直接回答（如闲聊、常识问题），就不需要调用工具
调用工具后，基于工具返回的结果给出完整、准确的回答。
```

### 4.3 配置项
- `ragent.agent.max-iterations=5`（默认值，可配置）
- 在 `application.yaml` 中增加 agent 配置段

### 4.4 验证标准
- `ragent.engine.type=agent` 时走 AgentChatPipeline
- `ragent.engine.type=workflow` 时走 StreamChatPipeline（不受影响）
- Agent Prompt 可在前端管理页面编辑

---

## Phase 5：Trace 完善 + 端到端验证
> 对应 ADR-0011,0012 | 预估 2-3 天 | 依赖：Phase 4

### 5.1 参数校验逻辑抽取
- 从 `LLMMcpParameterExtractor` 中抽取 `coerceAndValidate` 为公共方法
- 在 `executeToolsParallel` 中调用

### 5.2 Trace 验证
- 前端 Trace 页面能看到 Agent 节点
- 每轮 LLM 调用和工具调用都有独立节点
- extraData 中有 tool_call 参数和结果摘要

### 5.3 端到端测试场景
| 场景 | 预期行为 | 验证点 |
|------|---------|--------|
| 闲聊"你好" | 1 轮，不调工具，直接回答 | 无 tool_call |
| 知识库问答"产品保修政策" | 2 轮，调 rag_search，基于结果回答 | sources 展示正确 |
| 销售数据"华东本月销量" | 2 轮，调 sales_query，基于结果回答 | Mock 数据正确 |
| 多工具"华东销量和北京天气" | 2 轮，并行调 sales_query + weather_query | 并行执行 |
| 工具失败"查一下不存在的数据" | Agent 收到错误，换方式或直接告知 | 错误注入正确 |
| 超限兜底（连续 5 轮调工具） | 第 6 轮强制合成 | 强制合成触发 |
| 多轮对话"刚才查的销量是多少" | Agent 从工具调用摘要中回溯 | 记忆正确 |
| deepThinking 开启 | 中间轮标准模型，最终轮深度思考 | Tier 正确 |

### 5.4 回归验证
- `ragent.engine.type=workflow` 时所有现有功能不受影响
- 意图管理、知识库管理、文档解析等功能不受影响

---

## 依赖关系图

```
Phase 1 (FC 基础)
    ↓
Phase 2 (RAG Tool)     ←── 依赖 Phase 1 的 ChatRequest.tools
    ↓
Phase 3 (Agent 循环)    ←── 依赖 Phase 1 的 FC 响应 + Phase 2 的 Tool 注册
    ↓
Phase 4 (路由+Prompt)   ←── 依赖 Phase 3 的 AgentChatPipeline
    ↓
Phase 5 (Trace+验证)   ←── 依赖 Phase 4 的完整链路
```

## 总工作量

| Phase | 工时 | 核心交付 |
|-------|------|---------|
| Phase 1 | 5-8 天 | FC 能力可用 |
| Phase 2 | 2-3 天 | RAG Tool 可调用 |
| Phase 3 | 4-6 天 | ReAct 循环跑通 |
| Phase 4 | 1-2 天 | 入口打通 |
| Phase 5 | 2-3 天 | Trace + 测试 |
| **合计** | **14-22 天** | 约 3-4 周 |
