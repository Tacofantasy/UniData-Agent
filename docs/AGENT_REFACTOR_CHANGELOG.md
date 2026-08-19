# Agent 架构重构改动全记录

> **版本**: `ceef6b6` | **分支**: `main` | **日期**: 2026-08-18
>
> 本文档记录了将线性 RAG 管线重构为 Agent 架构的全部改动，旨在让新的 AI 对话能快速接手后续开发和维护。

---

## 1. 重构概述

### 1.1 目标

将原有的线性 WORKFLOW 管线（意图分类 → 检索 → 合成）重构为 **Agent 架构**，将 RAG 检索能力封装为 MCP Tool 供 Agent 通过原生 Function Calling 调用，实现基于 **ReAct 循环**（Thought → Action → Observation）的多轮推理能力。

### 1.2 核心决策（18 份 ADR）

| ADR | 主题 | 决策 |
|-----|------|------|
| 0001 | Agent 架构 | 采用 ReAct 循环，非 Plan-and-Execute |
| 0002 | Function Calling 策略 | 原生 FC，非 Prompt 模拟 |
| 0003 | 记忆策略 | 存最终答案 + 工具调用摘要，不存中间步骤 |
| 0004 | 循环终止与兜底 | 可配置最大循环次数（默认5），超限强制合成 |
| 0005 | Token 预算控制 | 循环次数 ≤5 时不做 Token 预算控制 |
| 0006 | 流式策略 | 中间轮同步，最终答案一次性返回 |
| 0007 | RAG Tool 内部管线 | 复用 IntentResolver + RetrievalEngine，跳过 QueryRewrite |
| 0008 | RAG Tool 注册 | 通过 McpToolRegistry 注册为本地工具 |
| 0009 | Pipeline 路由 | 保留 WORKFLOW 入口，配置切换 |
| 0010 | Agent 系统 Prompt | 简洁指令式，存入 t_agent_prompt |
| 0011 | Agent 可观测性 | 复用 RagTraceNode，AGENT_LOOP + RAG_TOOL 节点类型 |
| 0012 | 工具调用校验 | 工具名 + 参数双重校验 |
| 0013 | 工具权限控制 | 预留接口，暂不实现 |
| 0014 | Agent 评估 | 复用现有 eval 框架 |
| 0015 | 并发工具执行 | CompletableFuture + TTL 线程池 |
| 0016 | 深度思考 | 仅最终轮开启 thinking |
| 0017 | 前端状态显示 | 暂不改造前端，SSE 协议兼容 |
| 0018 | Sources 传递 | SourcesAccumulator 跨轮累加 |

### 1.3 改动统计

```
47 files changed, +1974 lines, -22 lines
  33 new files, 14 modified files
```

---

## 2. 新增文件详解

### 2.1 Agent ReAct 循环核心

#### `bootstrap/.../rag/service/pipeline/AgentChatPipeline.java` (369 行)

**职责**: Agent ReAct 循环的核心实现。

**执行流程**:
```
1. 加载历史记忆 + 持久化用户消息
2. 构建工具定义列表（从 McpToolRegistry 获取所有 MCP 工具，转换为 FC 格式）
3. 构建 system prompt（从 t_agent_prompt 表读取 AGENT_MAIN slot）
4. 初始化消息列表（system + history + user）
5. ReAct 循环（最多 maxIterations 轮）:
   a. 调用 LLM（chatWithTools，携带 tools 定义）
   b. LLM 返回纯文本 → 最终答案，break
   c. LLM 返回 tool_calls → 执行工具（并行）→ 追加结果到 messages → 继续
   d. 达到最大循环次数 → 强制合成（toolChoice=none）
6. 收集所有工具产生的 sources（去重）→ 传递给 callback
7. 一次性推送最终答案到前端（callback.onContent）
8. 持久化最终答案到 t_message（含 sources）
9. 持久化工具调用摘要到 t_message
10. 清理 ThreadLocal
```

**关键设计**:
- `@RagTraceNode(name = "agent-react-loop", type = "AGENT_LOOP")` — Trace 根节点
- `AgentConfigProperties agentProps` — 注入配置（maxIterations=5）
- `Executor agentToolExecutor` — TTL 增强的工具并行执行线程池
- `SourcesAccumulator sourcesAccumulator` — 跨轮累加 sources
- 内部 record `ToolExecutionResult(toolCallId, resultText, isError, sources, groundingChunks)` — 工具执行结果

**工具校验逻辑** (ADR-0012):
1. 工具名校验: `mcpToolRegistry.contains(toolName)` → 不存在则返回错误信息
2. 参数校验: `arguments == null` → 设为空 Map
3. 执行: `mcpToolRegistry.getExecutor(toolName).execute(arguments)`
4. 异常: catch 后返回错误信息作为 Observation

**错误处理** (ADR-0004):
- LLM 调用失败 → 注入"（系统错误，正在重试）"继续循环
- 工具执行失败 → 错误信息作为工具返回值注入 messages
- 达到最大循环 → 强制合成（`toolChoice=none`，不带工具定义）

#### `bootstrap/.../rag/service/pipeline/AgentChatContext.java` (60 行)

**职责**: Agent 对话上下文，Builder 模式。

```java
AgentChatContext.builder()
    .question(question)
    .conversationId(actualConversationId)
    .taskId(taskId)
    .deepThinking(Boolean.TRUE.equals(deepThinking))
    .userId(UserContext.getUserId())
    .callback(traceAware)
    .build();
```

#### `bootstrap/.../rag/service/pipeline/AgentConfigProperties.java` (44 行)

**职责**: Agent 配置属性，对应 `application.yaml` 中的 `unidata.agent` 前缀。

```yaml
unidata:
  agent:
    max-iterations: 5      # ReAct 循环最大迭代次数
    tool-parallelism: 4    # 工具并行执行线程数
```

**踩坑记录**: 最初使用嵌套内部类 `AgentProps`，导致 Spring 无法自动注册为 Bean。修复方案是直接将属性放在外层类中，移除嵌套结构。

---

### 2.2 RAG 工具封装

#### `bootstrap/.../rag/core/mcp/RagSearchToolExecutor.java` (203 行)

**职责**: 将 RAG 检索能力封装为 `rag_search` MCP 工具。

**工具定义**:
- 工具名: `rag_search`
- 参数: `query` (String, required) — 检索查询文本
- 描述: 知识库检索工具，基于企业知识库进行语义检索

**内部流程**:
```
1. 从参数提取 query
2. 构建 RewriteResult（跳过 QueryRewrite，Agent 已做改写）
3. IntentResolver.resolve(rewriteResult) — 意图解析
4. RetrievalEngine.retrieve(subIntents) — 多通道检索（向量→RRF融合→Rerank）
5. SourcesAssembler.assemble(intentChunks) — 组装来源引用
6. SourcesAccumulator.accumulateSources(sources) — 累加到线程上下文
7. GroundingChunksAssembler.assemble(intentChunks) — 组装 grounding 片段
8. SourcesAccumulator.accumulateGroundingChunks(chunks) — 累加
9. 构建文本返回（KB上下文 + 来源文档列表）
10. 返回 CallToolResult（TextContent + isError=false）
```

**循环依赖修复**: `RetrievalEngine → McpToolRegistry → List<McpToolExecutor> → RagSearchToolExecutor → RetrievalEngine`。使用 `@Lazy` 注入 `RetrievalEngine` 打破循环。

**Trace**: `@RagTraceNode(name = "rag-search-tool", type = "RAG_TOOL")`

#### `bootstrap/.../rag/core/mcp/SourcesAccumulator.java` (114 行)

**职责**: 在 Agent ReAct 循环中跨多次工具调用累加 sources 和 grounding chunks。

**演进历程**:
1. **v1**: `@RequestScope` Bean — 在 ChatQueueLimiter 线程池中抛 `ScopeNotActiveException`
2. **v2**: 改为 `ThreadLocal` — 子线程（agentToolExecutor）写入的数据父线程不可见
3. **v3（当前）**: 改为 `TransmittableThreadLocal` — TTL 能跨 TTL 增强的线程池传递

**最终方案**: `TransmittableThreadLocal` + `ToolExecutionResult` 返回值双重保障:
- `RagSearchToolExecutor` 在工具线程内调用 `accumulateSources()` 写入 TTL
- `AgentChatPipeline.executeSingleTool()` 在同一线程内从 TTL 读取后放入 `ToolExecutionResult` 返回
- `AgentChatPipeline.execute()` 在主线程从 `ToolExecutionResult` 收集到局部 `allSources` 列表

#### `bootstrap/.../rag/core/mcp/McpToolToFcConverter.java` (93 行)

**职责**: 将 MCP `Tool` 定义转换为 OpenAI Function Calling `ToolDefinition` 格式。

```
MCP Tool (name, description, JsonSchema) 
  → ToolDefinition (type="function", FunctionDef(name, description, parameters))
```

---

### 2.3 Function Calling 基础设施

#### `framework/.../convention/ToolCall.java` (53 行)

**职责**: 表示 LLM 请求调用的工具及其参数。

```java
public class ToolCall {
    private String id;              // 工具调用唯一标识（模型生成）
    private String name;            // 工具名称
    private Map<String, Object> arguments;  // 工具参数（已解析为 Map）
}
```

#### `framework/.../convention/ToolDefinition.java` (86 行)

**职责**: 表示可供 LLM 调用的工具元信息，对应 OpenAI 兼容协议中 `tools` 数组的一个元素。

```java
public class ToolDefinition {
    private String type = "function";  // 固定为 "function"
    private FunctionDef function;       // 函数定义
    
    public static class FunctionDef {
        private String name;                   // 工具名称
        private String description;            // 工具描述
        private Map<String, Object> parameters;  // 参数 JSON Schema
    }
}
```

#### `framework/.../convention/ChatResponse.java` (77 行)

**职责**: 大模型结构化响应，用于 Function Calling 场景。

```java
public class ChatResponse {
    private String content;           // 文本内容（模型调用工具时可能为空）
    private List<ToolCall> toolCalls; // 工具调用列表（为空表示未请求调用工具）
    
    public boolean hasToolCalls();    // 是否包含工具调用
    public boolean isTextOnly();      // 是否为纯文本回答
}
```

---

### 2.4 架构文档

#### `docs/adr/0001~0018` (18 份 ADR)

每份 ADR 包含: 状态、背景、决策、理由、结果、后续驱动。完整覆盖 Agent 架构的全部决策点。

#### `docs/agent-refactor-plan.md` (236 行)

完整重构规划，分 6 个 Phase:
1. 基础设施层（FC 协议适配）
2. RAG Tool 封装
3. AgentChatPipeline ReAct 循环
4. 记忆与 Prompt
5. 可观测性与评估
6. 验收

#### `CONTEXT.md` (45 行)

项目上下文和核心术语表，确保对话中使用一致的语言。

---

### 2.5 数据库迁移

#### `resources/database/upgrades/v1.1.0/260816_message_type.sql` (6 行)

```sql
ALTER TABLE t_message ADD COLUMN IF NOT EXISTS message_type VARCHAR(32) NOT NULL DEFAULT 'NORMAL';
COMMENT ON COLUMN t_message.message_type IS '消息类型：NORMAL=正常对话，TOOL_SUMMARY=工具调用摘要';
```

#### `resources/database/upgrades/v1.1.0/260816_agent_main_prompt.sql` (21 行)

向 `t_agent_prompt` 表插入 `AGENT_MAIN` 系统 Prompt，包含:
- 角色定义: 企业智能助手
- 工具使用策略: 何时调用 rag_search，何时直接回答
- 回答规范: 基于工具结果、不编造、Markdown 格式

---

## 3. 修改文件详解

### 3.1 Pipeline 路由

#### `bootstrap/.../rag/service/impl/RAGChatServiceImpl.java` (+42/-12)

**改动**: 从单一 `chatPipeline` 改为双 Pipeline 路由。

**新增依赖注入**:
```java
private final StreamChatPipeline streamChatPipeline;     // WORKFLOW 模式
private final AgentChatPipeline agentChatPipeline;       // AGENT 模式
private final OrchestrationProperties orchestrationProperties;  // 配置读取
```

**路由逻辑**:
```java
OrchestrationMode mode = orchestrationProperties.getMode();
if (mode == OrchestrationMode.AGENT) {
    AgentChatContext ctx = AgentChatContext.builder()...build();
    agentChatPipeline.execute(ctx);
} else {
    StreamChatContext ctx = StreamChatContext.builder()...build();
    streamChatPipeline.execute(ctx);
}
```

**配置类**: `OrchestrationProperties` 读取 `unidata.engine.type`，返回 `OrchestrationMode` 枚举（WORKFLOW / AGENT）。

### 3.2 Function Calling 协议适配

#### `infra-ai/.../chat/AbstractOpenAIStyleChatClient.java` (+181)

**改动量最大的文件**，新增以下方法:

**`doChatWithTools()`** — 同步调用支持 FC:
- 与 `doChat()` 类似，但返回 `ChatResponse`（content + toolCalls）
- 构建请求体时包含 tools 定义
- 解析响应时提取 content 和 tool_calls

**`buildToolsArray()`** — 构建工具定义 JSON:
```json
[{"type":"function","function":{"name":"rag_search","description":"...","parameters":{...}}}]
```

**`buildToolCallsArray()`** — 构建 ASSISTANT 消息中的 tool_calls:
```json
[{"id":"call_xxx","type":"function","function":{"name":"rag_search","arguments":"{\"query\":\"...\"}"}}]
```

**`buildMessages()` 修改** — 支持 TOOL 和 ASSISTANT 角色的新字段:
- TOOL 角色: 添加 `tool_call_id` 字段
- ASSISTANT 角色: 可携带 `tool_calls` 数组，content 可为空

**`toOpenAiRole()` 修改** — 新增 `TOOL → "tool"` 映射

**`extractChatResponse()`** — 从 OpenAI 响应中提取结构化内容:
- 提取 `content`（模型调用工具时可能为 null）
- 提取 `tool_calls` 数组，解析每个工具调用的 id、name、arguments
- `parseArguments()` — 将 arguments JSON 字符串解析为 Map

**`customizeRequestBody()` 修复** — `enable_thinking` 参数:
- 原代码: thinking=true → `enable_thinking:true`，thinking=false → `enable_thinking:false`
- 修复后: 仅 thinking=true 时发送 `enable_thinking:true`，false 时不发送该字段
- 原因: 百炼 qwen3.7-max 不允许 `enable_thinking=false`，返回 400

#### `infra-ai/.../chat/ChatClient.java` (+13)

接口新增 `chatWithTools()` 方法:
```java
ChatResponse chatWithTools(ChatRequest request, ModelTarget target);
```

#### `infra-ai/.../chat/LLMService.java` (+21)

接口新增两个重载:
```java
ChatResponse chatWithTools(ChatRequest request);
ChatResponse chatWithTools(ChatRequest request, Tier tier);
```

#### `infra-ai/.../chat/RoutingLLMService.java` (+23)

实现 `chatWithTools()`，带 fallback 路由:
```java
@RagTraceNode(name = "llm-chat-fc-routing", type = "LLM_ROUTING")
public ChatResponse chatWithTools(ChatRequest request) {
    return executor.executeWithFallback(
        ModelCapability.CHAT,
        selector.selectChatCandidates(...),
        target -> clientsByProvider.get(target.candidate().getProvider()),
        (client, target) -> client.chatWithTools(request, target)
    );
}
```

#### 5 个 ChatClient 子类 (各 +7 行)

`BaiLianChatClient`、`AIHubMixChatClient`、`SiliconFlowChatClient`、`OllamaChatClient` 均新增:
```java
@Override
@RagTraceNode(name = "xxx-chat-fc", type = "LLM_PROVIDER")
public ChatResponse chatWithTools(ChatRequest request, ModelTarget target) {
    return doChatWithTools(request, target);
}
```

### 3.3 消息模型扩展

#### `framework/.../convention/ChatMessage.java` (+26/-4)

新增 `Role.TOOL` 枚举值:
```java
public enum Role {
    SYSTEM, USER, ASSISTANT, TOOL;
}
```

新增字段:
```java
private List<ToolCall> toolCalls;   // ASSISTANT 角色携带的工具调用列表
private String toolCallId;          // TOOL 角色携带的关联 ID
```

#### `framework/.../convention/ChatRequest.java` (+22)

新增字段:
```java
@Default
private List<ToolDefinition> tools = new ArrayList<>();  // 可用工具定义列表
private String toolChoice;  // 工具调用策略: "auto"/"none"/"required"
```

### 3.4 线程池配置

#### `bootstrap/.../rag/config/ThreadPoolExecutorConfig.java` (+19)

新增 `agentToolExecutor` Bean:
```java
@Bean
public Executor agentToolExecutor() {
    ThreadPoolExecutor executor = new ThreadPoolExecutor(
        CPU_COUNT,           // 核心线程数
        CPU_COUNT << 1,      // 最大线程数
        60, TimeUnit.SECONDS,
        new SynchronousQueue<>(),
        ThreadFactoryBuilder.create().setNamePrefix("agent_tool_executor_").build(),
        new ThreadPoolExecutor.CallerRunsPolicy()
    );
    return TtlExecutors.getTtlExecutor(executor);  // TTL 增强
}
```

**关键**: 使用 `TtlExecutors.getTtlExecutor()` 包装，使 `TransmittableThreadLocal` 能跨线程池传递。

### 3.5 数据库实体

#### `bootstrap/.../rag/dao/entity/ConversationMessageDO.java` (+5)

新增字段:
```java
private String messageType;  // NORMAL=正常对话, TOOL_SUMMARY=工具调用摘要
```

#### `bootstrap/.../rag/service/bo/ConversationMessageBO.java` (+5)

同上，BO 层新增 `messageType` 字段。

### 3.6 配置文件

#### `bootstrap/src/main/resources/application.yaml` (+5/-1)

```yaml
unidata:
  engine:
    type: agent  # 从 workflow 改为 agent
  agent:
    max-iterations: 5
    tool-parallelism: 4
```

---

## 4. Bug 修复记录

### 4.1 循环依赖 (RagSearchToolExecutor)

**现象**: 启动失败，Spring 报循环依赖。

**链路**: `RetrievalEngine → McpToolRegistry → List<McpToolExecutor> → RagSearchToolExecutor → RetrievalEngine`

**修复**: 在 `RagSearchToolExecutor` 构造函数中对 `RetrievalEngine` 使用 `@Lazy` 注入:
```java
public RagSearchToolExecutor(
    IntentResolver intentResolver,
    @Lazy RetrievalEngine retrievalEngine,  // ← @Lazy 打破循环
    ...
)
```

### 4.2 AgentConfigProperties Bean 缺失

**现象**: `AgentChatPipeline` 无法注入 `AgentProps` Bean。

**原因**: 最初 `AgentConfigProperties` 使用嵌套内部类 `AgentProps`，与 `application.yaml` 的配置路径不匹配，Spring 未自动注册内部类为 Bean。

**修复**: 移除嵌套结构，将 `maxIterations` 和 `toolParallelism` 直接放在 `AgentConfigProperties` 外层类中。

### 4.3 SourcesAccumulator RequestScope 不活跃

**现象**: `ScopeNotActiveException: Error creating bean with name 'scopedTarget.sourcesAccumulator': Scope 'request' is not active for the current thread`

**原因**: `SourcesAccumulator` 标注了 `@RequestScope`，但 `ChatQueueLimiter` 在独立线程池中执行 Agent Pipeline，不在 Web 请求线程中，`RequestScope` 不可用。

**修复历程**:
1. 改为普通 `ThreadLocal` — 解决了 RequestScope 问题，但引入新问题（见 4.4）
2. 改为 `TransmittableThreadLocal` — 配合 TTL 增强的 `agentToolExecutor` 线程池

### 4.4 enable_thinking 百炼 API 400

**现象**: 百炼 API 返回 400: `The value of the enable_thinking parameter is restricted to True.`

**原因**: `customizeRequestBody()` 在 `thinking=false` 时发送 `enable_thinking: false`，但百炼 qwen3.7-max 模型不允许该值为 false。

**修复**: `thinking=false` 时不发送 `enable_thinking` 字段，让 API 使用默认值:
```java
protected void customizeRequestBody(JsonObject body, ChatRequest request) {
    if (Boolean.TRUE.equals(request.getThinking())) {
        body.addProperty("enable_thinking", true);
    }
    // false 时不发送，让 API 用默认值
}
```

### 4.5 Sources 跨线程传递丢失

**现象**: Agent 调用了 `rag_search` 工具（日志确认 `sources=3`），但 SSE `finish` 事件中没有 sources 字段。

**原因**: `ThreadLocal`（甚至 `TransmittableThreadLocal`）在 `CompletableFuture.supplyAsync` 场景下，子线程写入的数据无法自动回传到父线程。TTL 在任务提交时复制父线程的值到子线程，但子线程的修改不会回传。

**修复**: 双重保障方案:
1. `RagSearchToolExecutor` 在工具线程内调用 `sourcesAccumulator.accumulateSources()` 写入 TTL
2. `AgentChatPipeline.executeSingleTool()` 在同一线程内从 TTL 读取后放入 `ToolExecutionResult.sources` 返回
3. `AgentChatPipeline.execute()` 在主线程从 `ToolExecutionResult` 收集到局部 `allSources` 列表
4. 循环结束后统一去重并传递给 callback

**关键代码**:
```java
// executeSingleTool 中（工具线程内）
List<SourceRef> sources = sourcesAccumulator.getAccumulatedSources();
sourcesAccumulator.clear();  // 清理避免重复
return new ToolExecutionResult(toolCallId, resultText, isError, sources, chunks);

// execute 中（主线程）
for (ToolExecutionResult result : toolResults) {
    if (CollUtil.isNotEmpty(result.sources())) {
        allSources.addAll(result.sources());
    }
}
```

---

## 5. 端到端测试结果

### 5.1 场景1: 闲聊（不调工具，1轮完成）

**请求**: `question=你好&deepThinking=false`

**结果**: ✅ 通过
- Agent 直接回答"你好！我是企业智能助手，很高兴为您服务。请问有什么我可以帮您的吗？"
- 未触发任何工具调用
- SSE: meta → message(response) → finish → done

### 5.2 场景2: 知识库问答（调 rag_search，2轮完成）

**请求**: `question=IELTS口语Day 6练习了哪些内容&deepThinking=false`

**结果**: ✅ 通过
- 第1轮: LLM 返回 `tool_calls` 请求调用 `rag_search`
- 第2轮: LLM 基于检索结果给出完整回答
- `finish` 事件包含 3 个 sources（DAY 6.md, 每日代办.md, DAY 5.md）
- SSE: meta → message(response) → finish(含sources) → done

### 5.3 场景3: 工具不存在时的错误处理

**请求**: `question=请调用一个不存在的工具weather_query来查天气`

**结果**: ✅ 通过
- LLM 识别到工具列表中没有 `weather_query`，直接回复告知用户可用工具
- 未触发工具调用，1轮完成

### 5.4 场景4: Trace 节点记录

**结果**: ✅ 通过

完整 Trace 树（场景2）:
```
AGENT_LOOP (agent-react-loop, 19366ms)
├── LLM_ROUTING (llm-chat-fc-routing, 1004ms) — 第1轮 LLM
│   └── LLM_PROVIDER (bailian-chat-fc, 987ms)
├── RAG_TOOL (rag-search-tool, 3536ms) — 工具调用
│   ├── INTENT (intent-resolve, 1496ms)
│   │   └── LLM_ROUTING → LLM_PROVIDER (bailian-chat, 1430ms)
│   └── RETRIEVE (retrieval-engine, 2011ms)
│       └── RETRIEVE_CHANNEL (multi-channel-retrieval, 1989ms)
└── LLM_ROUTING (llm-chat-fc-routing, 4424ms) — 第2轮 LLM（最终答案）
    └── LLM_PROVIDER (bailian-chat-fc, 4412ms)
```

---

## 6. 架构依赖关系

```
RAGChatController
  └─ RAGChatServiceImpl
       ├─ OrchestrationProperties (配置读取)
       ├─ ChatQueueLimiter (限流排队)
       ├─ StreamChatTraceRunner (Trace 包装)
       ├─ StreamChatPipeline (WORKFLOW 模式, 保留不启用)
       └─ AgentChatPipeline (AGENT 模式)
            ├─ LLMService → RoutingLLMService → ChatClient (chatWithTools)
            ├─ ConversationMemoryService (对话记忆)
            ├─ McpToolRegistry (工具注册中心)
            │    └─ RagSearchToolExecutor (rag_search 工具)
            │         ├─ IntentResolver (意图解析)
            │         ├─ RetrievalEngine (多通道检索) [@Lazy]
            │         ├─ SourcesAssembler (来源组装)
            │         └─ SourcesAccumulator (来源累加, TTL)
            ├─ McpToolToFcConverter (MCP→FC 转换)
            ├─ AgentPromptResolver (Prompt 解析)
            ├─ StreamTaskManager (任务管理)
            ├─ AgentConfigProperties (配置)
            └─ agentToolExecutor (工具并行线程池, TTL增强)
```

---

## 7. 后续待完成项

| 项目 | 说明 | 优先级 |
|------|------|--------|
| `messageType = TOOL_SUMMARY` 落库 | `AgentChatPipeline` 第 241 行 TODO: 需扩展 `memoryService.append()` 接口支持 messageType 参数 | P1 |
| 工具权限控制 (ADR-0013) | 预留了接口，暂未实现。需要在 `executeSingleTool` 中增加权限校验 | P2 |
| Agent 评估框架 (ADR-0014) | 复用现有 eval 框架，新增 Agent 场景评估指标 | P2 |
| 前端状态显示 (ADR-0017) | SSE 协议兼容，前端暂未改造。可增加工具调用进度展示 | P3 |
| Ollama 本地模型适配 | 当前 Ollama 返回 404（模型未下载），需检查模型配置 | P3 |

---

## 8. 关键文件索引

### 新增文件

| 文件路径 | 行数 | 说明 |
|----------|------|------|
| `bootstrap/.../rag/service/pipeline/AgentChatPipeline.java` | 369 | ReAct 循环核心 |
| `bootstrap/.../rag/service/pipeline/AgentChatContext.java` | 60 | Agent 上下文 |
| `bootstrap/.../rag/service/pipeline/AgentConfigProperties.java` | 44 | Agent 配置 |
| `bootstrap/.../rag/core/mcp/RagSearchToolExecutor.java` | 203 | RAG 工具执行器 |
| `bootstrap/.../rag/core/mcp/SourcesAccumulator.java` | 114 | Sources 累加器 |
| `bootstrap/.../rag/core/mcp/McpToolToFcConverter.java` | 93 | MCP→FC 转换器 |
| `framework/.../convention/ChatResponse.java` | 77 | LLM 结构化响应 |
| `framework/.../convention/ToolCall.java` | 53 | 工具调用请求 |
| `framework/.../convention/ToolDefinition.java` | 86 | 工具定义 |
| `docs/adr/0001~0018` | 18份 | 架构决策记录 |
| `docs/agent-refactor-plan.md` | 236 | 重构规划 |
| `CONTEXT.md` | 45 | 术语表 |
| `resources/database/upgrades/v1.1.0/260816_message_type.sql` | 6 | DB 迁移 |
| `resources/database/upgrades/v1.1.0/260816_agent_main_prompt.sql` | 21 | DB 迁移 |

### 修改文件

| 文件路径 | +/- | 说明 |
|----------|-----|------|
| `bootstrap/.../rag/service/impl/RAGChatServiceImpl.java` | +42/-12 | Pipeline 路由 |
| `infra-ai/.../chat/AbstractOpenAIStyleChatClient.java` | +181 | FC 协议适配 |
| `framework/.../convention/ChatMessage.java` | +26/-4 | 新增 TOOL 角色 |
| `infra-ai/.../chat/RoutingLLMService.java` | +23 | chatWithTools 路由 |
| `framework/.../convention/ChatRequest.java` | +22 | tools/toolChoice |
| `infra-ai/.../chat/LLMService.java` | +21 | chatWithTools 接口 |
| `bootstrap/.../rag/config/ThreadPoolExecutorConfig.java` | +19 | agentToolExecutor |
| `infra-ai/.../chat/ChatClient.java` | +13 | chatWithTools 接口 |
| `bootstrap/src/main/resources/application.yaml` | +5/-1 | engine.type=agent |
| `bootstrap/.../rag/dao/entity/ConversationMessageDO.java` | +5 | messageType |
| `bootstrap/.../rag/service/bo/ConversationMessageBO.java` | +5 | messageType |
| `infra-ai/.../chat/BaiLianChatClient.java` | +7 | chatWithTools |
| `infra-ai/.../chat/AIHubMixChatClient.java` | +7 | chatWithTools |
| `infra-ai/.../chat/SiliconFlowChatClient.java` | +7 | chatWithTools |
| `infra-ai/.../chat/OllamaChatClient.java` | +7 | chatWithTools |
| `.gitignore` | +2 | 临时文件忽略 |
