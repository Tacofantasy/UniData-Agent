<p align="center">
  <a href="https://github.com/nageoffer/ragent">
    <picture>
      <source srcset="assets/ragent-ai-banner.png">
      <img src="assets/ragent-ai-banner.png" alt="Ragent AI">
    </picture>
  </a>
</p>

<p align="center">
  <strong>基于 ReAct 循环的企业级 Agent 智能助手平台</strong><br/>
  <em>从线性 RAG 管线到自主决策 Agent 的完整演进</em>
</p>

<p align="center">
  <a href="https://github.com/nageoffer/ragent/stargazers"><img alt="GitHub stars" src="https://img.shields.io/github/stars/nageoffer/ragent?style=flat-square&logo=github&color=e8b227" /></a>&nbsp;
  <a href="https://github.com/nageoffer/ragent/network/members"><img alt="GitHub forks" src="https://img.shields.io/github/forks/nageoffer/ragent?style=flat-square&logo=github&color=2d6a8a" /></a>&nbsp;
  <a href="https://github.com/nageoffer/ragent/graphs/contributors"><img alt="Contributors" src="https://img.shields.io/github/contributors/nageoffer/ragent?style=flat-square&color=b56e7a" /></a>&nbsp;
  <a href="./LICENSE"><img alt="License" src="https://img.shields.io/badge/license-Apache--2.0-4a9b8f?style=flat-square" /></a>
  <img src="https://img.shields.io/badge/Spring%20Boot-3.5.7-6DB33F?style=flat-square&logo=springboot&logoColor=white" />
  <img src="https://img.shields.io/badge/MCP-1.1.2-FF6B6B?style=flat-square" />
  <img src="https://img.shields.io/badge/Agent-ReAct-9B59B6?style=flat-square" />
</p>

---

## 🤖 什么是 Ragent AI？

Ragent 是一个 **基于 ReAct 循环（Reasoning + Acting）的企业级 Agent 智能助手平台**。它将传统的线性 RAG 管线（意图分类 → 检索 → 合成）重构为 **Agent 自主决策架构**——LLM 通过原生 Function Calling 自主决定何时调用工具、调用哪个工具、调用几次。

### 核心架构：Agent + MCP 工具生态

```
┌─────────────────────────────────────────────────────────────────────┐
│                         用户请求 (SSE)                               │
└───────────────────────────┬─────────────────────────────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    RAGChatServiceImpl (路由层)                       │
│         OrchestrationMode.AGENT → AgentChatPipeline                  │
│         OrchestrationMode.WORKFLOW → StreamChatPipeline              │
└───────────────────────────┬─────────────────────────────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│              AgentChatPipeline (ReAct 循环核心)                        │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  for (i = 0; i < maxIterations; i++) {                      │   │
│  │    ① LLM.chatWithTools(messages, tools)  // 原生 FC 调用     │   │
│  │    if (response.hasToolCalls()) {                           │   │
│  │      ② executeToolsParallel(toolCalls)   // 并行执行工具       │   │
│  │      ③ messages += assistantMsg + toolResults               │   │
│  │    } else {                                                 │   │
│  │      ④ return response.content()        // 最终答案           │   │
│  │    }                                                        │   │
│  │  }                                                          │   │
│  └─────────────────────────────────────────────────────────────┘   │
└───────────────────────────┬─────────────────────────────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     McpToolRegistry (工具注册中心)                    │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │
│  │  rag_search   │  │ sales_query  │  │ weather_query│  ...         │
│  │ (知识库检索)  │  │ (销售数据)   │  │ (天气查询)   │              │
│  └──────────────┘  └──────────────┘  └──────────────┘              │
│  ┌──────────────┐  ┌──────────────┐                                │
│  │ ticket_query │  │ youcom_search│                                │
│  │ (工单查询)   │  │ (联网搜索)   │                                │
│  └──────────────┘  └──────────────┘                                │
└─────────────────────────────────────────────────────────────────────┘
```

### 与传统 RAG 的本质区别

| 维度 | 传统 RAG 线性管线 | Ragent Agent 架构 |
|:---|:---|:---|
| **决策主体** | 固定管线步骤 | LLM 自主决策 |
| **检索触发** | 每次必检 | LLM 判断是否需要 |
| **工具调用** | 无 / 硬编码 | Function Calling 动态选择 |
| **多跳推理** | 需要预定义流程 | Agent 自然循环实现 |
| **扩展方式** | 改代码 | 注册新 Tool 即可 |

---

## ✨ 核心能力

### 1. 🔁 ReAct 循环（Agent 核心）

完整的 Thought → Action → Observation 循环：

```java
// AgentChatPipeline.java - 核心循环逻辑
for (int i = 0; i < maxIterations; i++) {
    ChatResponse response = llmService.chatWithTools(request);  // Thought
    
    if (!response.hasToolCalls()) {
        return response.getContent();  // 最终答案，退出循环
    }
    
    // Action: 并行执行所有工具调用
    List<CompletableFuture<ToolExecutionResult>> futures = toolCalls.stream()
            .map(tc -> CompletableFuture.supplyAsync(
                    () -> executeSingleTool(tc, ctx), agentToolExecutor))
            .toList();
    
    // Observation: 将工具结果追加到消息列表，继续循环
    messages.addAll(buildToolResultMessages(futures));
}
// 超限兜底：强制合成最终答案
```

**关键设计决策（18 份 ADR）：**
- ADR-0001：采用 ReAct 循环，非 Plan-and-Execute
- ADR-0002：原生 Function Calling，非 Prompt 模拟
- ADR-0004：可配置最大循环次数（默认 5），超限强制合成
- ADR-0015：多个工具并行执行（CompletableFuture + TTL 线程池）
- ADR-0016：深度思考仅在最终轮开启

### 2. 🔧 MCP 工具系统

基于 [Model Context Protocol](https://modelcontextprotocol.io/) 标准的工具注册与执行框架：

| 工具名 | 功能 | 参数 |
|:---|:---|:---|
| `rag_search` | 企业知识库语义检索 | `query` (String) |
| `sales_query` | 销售数据多维查询 | `region`, `period`, `product`, `queryType` |
| `weather_query` | 城市天气查询/预报 | `city`, `queryType`, `days` |
| `ticket_query` | 客户工单查询统计 | `status`, `priority`, `region`, `queryType` |
| `youcom_search` | You.com 联网搜索 | `query`, `count`, `freshness` |

**工具注册机制：**
```java
// McpToolRegistry 接口 - 统一注册中心
public interface McpToolRegistry {
    void register(McpToolExecutor executor);
    Optional<McpToolExecutor> getExecutor(String toolId);
    List<Tool> listAllTools();
}

// 新增工具只需实现接口并注册为 Spring Bean
@Component
public class RagSearchToolExecutor implements McpToolExecutor {
    @Override
    public Tool getToolDefinition() { /* 定义工具元信息 */ }
    @Override
    public CallToolResult execute(Map<String, Object> params) { /* 执行逻辑 */ }
}
```

### 3. 📡 原生 Function Calling 协议适配

完整实现了 OpenAI 兼容的 Function Calling 协议：

```
Request:
{
  "messages": [...],
  "tools": [{"type":"function","function":{"name":"rag_search","parameters":{...}}}],
  "tool_choice": "auto"
}

Response (LLM 决定调工具):
{
  "message": {
    "content": null,
    "tool_calls": [{"id":"call_xxx","type":"function","function":{"name":"rag_search","arguments":"{...}"}}]
  }
}

Response (LLM 给出答案):
{
  "message": {
    "content": "根据知识库检索结果...",
    "tool_calls": []
  }
}
```

**支持 4 个模型提供商：** 百炼 (BaiLian)、AIHubMix、SiliconFlow、Ollama

### 4. 🧠 对话记忆与上下文管理

| 策略 | 说明 |
|:---|:---|
| **历史加载** | 从数据库加载最近 N 轮对话 |
| **记忆持久化** | 存储最终答案 + 工具调用摘要（不存中间步骤） |
| **Token 控制** | 中间轮关闭深度思考，仅最终轮开启 |

### 5. 🔍 可观测性（Trace）

完整的 Trace 树结构，覆盖 Agent 每一步决策：

```
AGENT_LOOP (agent-react-loop, 19366ms)
├── LLM_ROUTING (llm-chat-fc-routing, 1004ms) — 第1轮 LLM 推理
│   └── LLM_PROVIDER (bailian-chat-fc, 987ms)
├── RAG_TOOL (rag-search-tool, 3536ms) — 工具调用
│   ├── INTENT (intent-resolve, 1496ms)
│   │   └── LLM_ROUTING → LLM_PROVIDER
│   └── RETRIEVE (retrieval-engine, 2011ms)
│       └── RETRIEVE_CHANNEL (multi-channel-retrieval, 1989ms)
└── LLM_ROUTING (llm-chat-fc-routing, 4424ms) — 第2轮 LLM 综合回答
    └── LLM_PROVIDER (bailian-chat-fc, 4412ms)
```

---

## 🏗️ 项目架构

### 模块分层

| 模块 | 职责 | 核心类 |
|:---|:---|:---|
| `framework` | 通用基础能力 | 统一响应、异常处理、认证上下文、幂等、分布式 ID、Trace |
| `infra-ai` | AI 模型基础设施 | ChatClient、EmbeddingClient、RerankClient、模型路由、熔断降级 |
| `bootstrap` | 业务编排层 | **AgentChatPipeline**、McpToolRegistry、RAG Core、入库 Pipeline、管理 API |
| `mcp-server` | MCP 工具服务 | SalesMcpExecutor、WeatherMcpExecutor、TicketMcpExecutor、YouComSearchMcpExecutor |

![](docs/assets/ragent-architecture-overview.svg)

### 运行时拓扑

![](assets/ragent-module-layering-v2.png)

### 一次用户提问的完整链路

![](assets/ragent-chain-v3.png)

---

## 🚀 快速开始

### 环境要求

- **JDK 17+**
- **Maven 3.8+**
- **PostgreSQL 15+**
- **Redis 7+**
- **Milvus 2.x**（向量数据库）
- **Node.js 18+**（前端）

### 启动后端

```bash
# 1. 克隆项目
git clone https://github.com/nageoffer/ragent.git
cd ragent

# 2. 配置环境变量（见 bootstrap/src/main/resources/application.yaml）
# - 数据库连接、Redis、Milvus、OSS 等

# 3. 执行数据库迁移脚本
psql -U your_user -d ragent -f resources/database/upgrades/v1.1.0/*.sql

# 4. 构建并启动
./mvnw clean package -DskipTests
java -jar bootstrap/target/bootstrap-0.0.1-SNAPSHOT.jar
```

### 启动前端

```bash
cd frontend
npm install
npm run dev
```

### 启动 MCP Server（可选）

```bash
# mcp-server 是独立服务，提供额外工具
java -jar mcp-server/target/mcp-server-0.0.1-SNAPSHOT.jar --server.port=9099
```

### 配置 Agent 模式

```yaml
# application.yaml
ragent:
  engine:
    type: agent          # agent = Agent 模式, workflow = 传统 RAG 管线
  agent:
    max-iterations: 5    # ReAct 循环最大迭代次数
    tool-parallelism: 4  # 工具并行执行线程数
```

---

## 📊 项目规模

| 指标 | 数值 |
|:---|:---|
| **Java 主代码** | ~6.1 万行 / 553 个文件 |
| **前端代码** | ~2.75 万行 / 27 个页面 |
| **业务表** | 22 张 |
| **测试用例** | 30 个文件 / 84 个测试点 |
| **ADR（架构决策记录）** | 18 份 |
| **MCP 工具** | 5 个 |
| **支持的 LLM 提供商** | 4 个（百炼/AIHubMix/SiliconFlow/Ollama） |

---

## 🛠️ 技术栈

### 后端
- **框架**: Spring Boot 3.5.7 + MyBatis-Plus 3.5.14
- **AI 协议**: OpenAI 兼容 Function Calling + MCP 1.1.2
- **向量库**: Milvus 2.6.6
- **缓存/队列**: Redis (Redisson 4.0) + RocketMQ 2.3.5
- **对象存储**: S3 协议（MinIO/阿里云 OSS）
- **文档解析**: Apache Tika 3.2.3 + MinerU

### 前端
- **框架**: React 18 + TypeScript + Vite
- **UI**: Tailwind CSS + shadcn/ui
- **状态管理**: Zustand
- **通信**: Server-Sent Events (SSE)

---

## 🎯 设计模式应用

| 模式 | 应用场景 | 解决的问题 |
|:---|:---|:---|
| **策略模式** | 检索通道、结果后处理、文档来源 | 不同实现可独立替换 |
| **工厂模式** | 意图树、分块策略、流式回调创建 | 集中复杂对象的创建逻辑 |
| **模板方法** | 并行检索、模型请求 | 固定通用流程，开放差异步骤 |
| **注册表模式** | MCP 工具发现、意图节点管理 | 统一注册、查找和调用组件 |
| **装饰器模式** | 向量写入时同步关键词和图谱索引 | 不修改主流程的前提下增强能力 |
| **责任链模式** | 检索后处理、模型故障降级 | 按顺序组合处理步骤 |
| **AOP** | 链路追踪、幂等、审计日志 | 将横切逻辑与业务解耦 |

---

## 📦 扩展指南

### 新增一个 MCP 工具

只需 3 步：

```java
// 1. 实现 McpToolExecutor 接口
@Component
public class MyCustomToolExecutor implements McpToolExecutor {
    @Override
    public Tool getToolDefinition() {
        return Tool.builder()
                .name("my_tool")
                .description("我的自定义工具")
                .inputSchema(new JsonSchema("object", Map.of(
                        "param", Map.of("type", "string", "description", "参数")
                ), List.of("param"), null, null, null))
                .build();
    }

    @Override
    public CallToolResult execute(Map<String, Object> parameters) {
        String param = (String) parameters.get("param");
        // ... 执行逻辑 ...
        return CallToolResult.builder()
                .content(List.of(new TextContent("执行结果")))
                .isError(false)
                .build();
    }
}

// 2. 注册到 McpToolRegistry（自动完成，Spring Bean 扫描）

// 3. Agent 自动发现并使用该工具
```

### 新增一个 LLM 提供商

```java
// 1. 继承 AbstractOpenAIStyleChatClient
@Component
public class MyProviderChatClient extends AbstractOpenAIStyleChatClient {
    @Override
    protected String provider() { return "my-provider"; }

    @Override
    @RagTraceNode(name = "my-provider-chat-fc", type = "LLM_PROVIDER")
    public ChatResponse chatWithTools(ChatRequest request, ModelTarget target) {
        return doChatWithTools(request, target);  // 复用基类 FC 逻辑
    }
}

// 2. 在 application.yaml 中配置候选模型
```

---

## ❓ 常见问题

<details>
<summary><b>为什么不用 Spring AI / LangChain4j？</b></summary>

Spring AI 和 LangChain4j 都是优秀的框架，但本项目选择自己实现 FC 协议适配的原因：

1. **深度理解**：自己造轮子能真正理解 Function Calling 的协议细节（tools/tool_calls/tool_choice）
2. **可控性**：不依赖框架版本迭代，避免低版本功能缺失或高版本 breaking change
3. **灵活性**：可以针对百炼等国内模型的特殊行为做定制适配（如 `enable_thinking` 参数）
4. **学习价值**：面试时能讲清楚协议细节，而不是"我调了个 SDK"

</details>

<details>
<summary><b>Agent 模式和 WORKFLOW 模式如何切换？</b></summary>

通过配置文件切换，无需改代码：

```yaml
ragent:
  engine:
    type: agent    # agent = ReAct Agent, workflow = 线性 RAG 管线
```

两种模式共享同一套基础设施（模型路由、检索引擎、会话记忆），只是编排方式不同。保留 WORKFLOW 模式作为 fallback，降低改造风险。

</details>

<details>
<summary><b>MCP Server 为什么是独立服务？</b></summary>

MCP Server 作为独立部署的服务，遵循 MCP 协议的**零内部依赖**原则：

- 不依赖 bootstrap/framework 模块
- 可以被任何 MCP Client 发现和使用
- 支持独立扩缩容和部署
- 符合 MCP 协议的"服务级隔离"设计理念

bootstrap 通过 HTTP MCP Client 连接远程 MCP Server，自动发现可用工具。

</details>

---

## 📈 路线图

- [x] v1.0: 线性 RAG 管线（意图分类 → 检索 → 合成）
- [x] v1.1: **Agent 重构**（ReAct 循环 + Function Calling + MCP 工具）
- [ ] v1.2: Agent 评估框架 + 多工具并行 Demo
- [ ] v1.3: 前端 Agent 推理过程可视化
- [ ] v1.4: 工具权限控制 + 多租户隔离
- [ ] v1.5: Plan-and-Execute 模式（复杂任务规划）

---

## 🤝 贡献

欢迎参与共建！贡献流程：

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/amazing-feature`)
3. 提交更改 (`git commit -m 'Add amazing feature'`)
4. 推送到分支 (`git push origin feature/amazing-feature`)
5. 开启 Pull Request

<p align="left">
    <a href="https://github.com/nageoffer/ragent/graphs/contributors">
        <img src="https://contrib.rocks/image?repo=nageoffer/ragent&columns=8" />
    </a>
</p>

---

## 📄 许可证

本项目基于 [Apache License 2.0](LICENSE) 开源。

---

<p align="center">
  <a href="https://www.star-history.com/?repos=nageoffer%2Fragent&type=date&legend=top-left">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/image?repos=nageoffer/ragent&type=date&theme=dark&legend=top-left" />
      <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/image?repos=nageoffer/ragent&type=date&theme=light&legend=top-left" />
      <img alt="Star History Chart" src="https://api.star-history.com/image?repos=nageoffer/ragent&type=date&legend=top-left" />
    </picture>
  </a>
</p>

<p align="center">
  如果觉得项目对你有帮助，点个 ⭐ Star 支持一下！
</p>
