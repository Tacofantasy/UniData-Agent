/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.service.pipeline;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.convention.ChatResponse;
import com.nageoffer.ai.ragent.framework.convention.GroundingChunk;
import com.nageoffer.ai.ragent.framework.convention.SourceRef;
import com.nageoffer.ai.ragent.framework.convention.ToolCall;
import com.nageoffer.ai.ragent.framework.convention.ToolDefinition;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolRegistry;
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolToFcConverter;
import com.nageoffer.ai.ragent.rag.core.mcp.SourcesAccumulator;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptResolver;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptSlot;
import com.nageoffer.ai.ragent.rag.service.handler.StreamTaskManager;
// AgentConfigProperties 直接注入，不再需要内部类引用
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * Agent 对话流水线（ReAct 循环）
 * <p>
 * 核心 ReAct 循环：
 * <ol>
 *   <li>调用 LLM（携带 tools 定义）</li>
 *   <li>如果 LLM 返回 tool_calls → 执行工具 → 将结果追加到 messages → 继续循环</li>
 *   <li>如果 LLM 返回纯文本 → 作为最终答案返回</li>
 * </ol>
 * <p>
 * 关键策略（按 ADR）：
 * <ul>
 *   <li>ADR-0005：循环次数限制（默认 5），超限强制合成</li>
 *   <li>ADR-0006：中间轮同步，最终答案一次性返回</li>
 *   <li>ADR-0015：多个工具并行执行</li>
 *   <li>ADR-0016：deepThinking 仅在最终轮开启</li>
 *   <li>ADR-0018：sources 通过 SourcesAccumulator 传递</li>
 *   <li>ADR-0012：工具名 + 参数双重校验</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentChatPipeline {

    private final LLMService llmService;
    private final ConversationMemoryService memoryService;
    private final McpToolRegistry mcpToolRegistry;
    private final McpToolToFcConverter mcpToolToFcConverter;
    private final SourcesAccumulator sourcesAccumulator;
    private final AgentPromptResolver agentPromptResolver;
    private final StreamTaskManager taskManager;
    private final AgentConfigProperties agentProps;
    private final Executor agentToolExecutor;

    /**
     * 执行 Agent 对话
     *
     * @param ctx Agent 对话上下文
     */
    @RagTraceNode(name = "agent-react-loop", type = "AGENT_LOOP")
    public void execute(AgentChatContext ctx) {
        // 1. 加载历史记忆并持久化用户消息
        List<ChatMessage> history = memoryService.load(ctx.getConversationId(), ctx.getUserId());
        String questionMessageId = memoryService.append(
                ctx.getConversationId(), ctx.getUserId(), ChatMessage.user(ctx.getQuestion()));
        ctx.getCallback().onReplyToMessageId(questionMessageId);

        // 2. 构建工具定义列表
        List<ToolDefinition> tools = buildToolDefinitions();

        // 3. 构建 system prompt
        String systemPrompt = agentPromptResolver.resolve(AgentPromptSlot.AGENT_MAIN);
        if (StrUtil.isBlank(systemPrompt)) {
            systemPrompt = "你是一个智能助手，可以通过工具调用获取信息来回答用户问题。";
        }

        // 4. 初始化消息列表
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt));
        if (CollUtil.isNotEmpty(history)) {
            messages.addAll(history);
        }
        messages.add(ChatMessage.user(ctx.getQuestion()));

        // 5. ReAct 循环
        int maxIterations = agentProps.getMaxIterations();
        String finalAnswer = null;
        String toolCallSummary = null;
        // 收集所有工具调用产生的 sources 和 grounding chunks
        List<SourceRef> allSources = new ArrayList<>();
        List<GroundingChunk> allGroundingChunks = new ArrayList<>();

        for (int i = 0; i < maxIterations; i++) {
            boolean isFinalRound = (i == maxIterations - 1);

            // ADR-0016：deepThinking 仅在最终轮开启
            boolean thinking = isFinalRound && ctx.isDeepThinking();

            ChatRequest request = ChatRequest.builder()
                    .messages(messages)
                    .tools(tools)
                    .toolChoice("auto")
                    .thinking(thinking)
                    .temperature(0.3D)
                    .build();

            ChatResponse response;
            try {
                response = llmService.chatWithTools(request);
            } catch (Exception e) {
                log.error("Agent 循环第 {} 轮 LLM 调用失败", i + 1, e);
                // ADR-0004：注入错误继续循环
                if (i < maxIterations - 1) {
                    messages.add(ChatMessage.assistant("（系统错误，正在重试）"));
                    continue;
                }
                throw e;
            }

            // 如果模型没有请求工具调用，说明已经给出最终答案
            if (!response.hasToolCalls()) {
                finalAnswer = response.getContent();
                break;
            }

            // 模型请求调用工具
            // 追加 assistant 消息（含 tool_calls）
            ChatMessage assistantMsg = new ChatMessage(ChatMessage.Role.ASSISTANT, response.getContent());
            assistantMsg.setToolCalls(response.getToolCalls());
            messages.add(assistantMsg);

            // 执行工具调用（并行，ADR-0015）
            List<ToolCall> toolCalls = response.getToolCalls();
            List<CompletableFuture<ToolExecutionResult>> futures = toolCalls.stream()
                    .map(tc -> CompletableFuture.supplyAsync(
                            () -> executeSingleTool(tc, ctx),
                            agentToolExecutor
                    ))
                    .toList();

            // 等待所有工具完成
            List<ToolExecutionResult> toolResults = futures.stream()
                    .map(CompletableFuture::join)
                    .toList();

            // 追加工具结果消息
            for (ToolExecutionResult result : toolResults) {
                ChatMessage toolMsg = new ChatMessage(ChatMessage.Role.TOOL, result.resultText());
                toolMsg.setToolCallId(result.toolCallId());
                messages.add(toolMsg);
                // 收集 sources 和 grounding chunks
                if (CollUtil.isNotEmpty(result.sources())) {
                    allSources.addAll(result.sources());
                }
                if (CollUtil.isNotEmpty(result.groundingChunks())) {
                    allGroundingChunks.addAll(result.groundingChunks());
                }
            }

            // 构建工具调用摘要（ADR-0003）
            toolCallSummary = buildToolCallSummary(toolCalls, toolResults);

            // 达到最大循环次数，强制合成（ADR-0005）
            if (isFinalRound) {
                log.info("Agent 达到最大循环次数 {}，强制合成最终答案", maxIterations);
                ChatRequest synthRequest = ChatRequest.builder()
                        .messages(messages)
                        .tools(List.of())
                        .toolChoice("none")
                        .thinking(ctx.isDeepThinking())
                        .temperature(0.3D)
                        .build();
                finalAnswer = llmService.chat(synthRequest);
            }
        }

        // 6. 去重 sources 和 grounding chunks，传递给回调
        List<SourceRef> sources = allSources.stream()
                .filter(distinctByKey(SourceRef::getDocId))
                .toList();
        if (CollUtil.isNotEmpty(sources)) {
            ctx.getCallback().onSources(sources);
        }
        List<GroundingChunk> groundingChunks = allGroundingChunks.stream()
                .filter(distinctByKey(GroundingChunk::getDocName))
                .toList();
        ctx.getCallback().onGroundingChunks(groundingChunks);

        // 7. 推送最终答案到前端（ADR-0006：一次性返回）
        StreamCallback callback = ctx.getCallback();
        if (StrUtil.isNotBlank(finalAnswer)) {
            callback.onContent(finalAnswer);
        } else {
            callback.onContent("抱歉，我无法处理这个问题。请稍后重试。");
        }
        callback.onComplete();

        // 8. 持久化最终答案（ADR-0003）
        ChatMessage assistantMessage = ChatMessage.assistant(finalAnswer);
        assistantMessage.setSources(sources);
        assistantMessage.setRetrievedChunks(groundingChunks);
        memoryService.append(ctx.getConversationId(), ctx.getUserId(), assistantMessage);

        // 9. 持久化工具调用摘要（ADR-0003）
        if (StrUtil.isNotBlank(toolCallSummary)) {
            ChatMessage summaryMsg = new ChatMessage(ChatMessage.Role.ASSISTANT, toolCallSummary);
            // TODO: 设置 messageType = TOOL_SUMMARY（需要扩展 memoryService 接口）
            memoryService.append(ctx.getConversationId(), ctx.getUserId(), summaryMsg);
        }

        // 10. 清理 ThreadLocal（防止内存泄漏）
        sourcesAccumulator.clear();
    }

    /**
     * 状态谓词辅助：按 key 去重
     */
    private <T> java.util.function.Predicate<T> distinctByKey(java.util.function.Function<? super T, ?> keyExtractor) {
        java.util.Set<Object> seen = new java.util.HashSet<>();
        return t -> {
            Object key = keyExtractor.apply(t);
            if (key == null) {
                return true;
            }
            return seen.add(key);
        };
    }

    /**
     * 构建可用工具定义列表
     */
    private List<ToolDefinition> buildToolDefinitions() {
        List<Tool> mcpTools = mcpToolRegistry.listAllTools();
        if (CollUtil.isEmpty(mcpTools)) {
            return List.of();
        }
        return mcpToolToFcConverter.convertAll(mcpTools);
    }

    /**
     * 执行单个工具调用（ADR-0012：双重校验）
     */
    private ToolExecutionResult executeSingleTool(ToolCall toolCall, AgentChatContext ctx) {
        String toolName = toolCall.getName();
        String toolCallId = toolCall.getId();
        Map<String, Object> arguments = toolCall.getArguments();

        // 校验 1：工具名是否存在
        if (!mcpToolRegistry.contains(toolName)) {
            log.warn("Agent 请求调用不存在的工具: {}", toolName);
            return new ToolExecutionResult(toolCallId,
                    "工具 '" + toolName + "' 不存在，请使用可用的工具。", true);
        }

        // 校验 2：参数是否有效（非 null）
        if (arguments == null) {
            arguments = Map.of();
        }

        try {
            CallToolResult result = mcpToolRegistry.getExecutor(toolName)
                    .orElseThrow(() -> new IllegalStateException("工具执行器不存在: " + toolName))
                    .execute(arguments);

            String resultText = extractTextFromResult(result);
            boolean isError = result.isError() != null && result.isError();
            // 从 SourcesAccumulator 获取当前线程累加的 sources 和 grounding chunks
            // （RagSearchToolExecutor 在本线程内调用 accumulateSources，TTL 保证可见）
            List<SourceRef> sources = sourcesAccumulator.getAccumulatedSources();
            List<GroundingChunk> chunks = sourcesAccumulator.getAccumulatedGroundingChunks();
            // 清理本线程的 TTL，避免下次工具调用重复累加
            sourcesAccumulator.clear();
            return new ToolExecutionResult(toolCallId, resultText, isError, sources, chunks);
        } catch (Exception e) {
            log.error("工具 {} 执行失败", toolName, e);
            return new ToolExecutionResult(toolCallId,
                    "工具执行失败: " + e.getMessage(), true);
        }
    }

    /**
     * 从 CallToolResult 中提取文本
     */
    private String extractTextFromResult(CallToolResult result) {
        if (result == null || result.content() == null || result.content().isEmpty()) {
            return "工具未返回内容。";
        }
        return result.content().stream()
                .filter(content -> content instanceof TextContent)
                .map(content -> ((TextContent) content).text())
                .collect(Collectors.joining("\n"));
    }

    /**
     * 构建工具调用摘要（ADR-0003）
     */
    private String buildToolCallSummary(List<ToolCall> toolCalls, List<ToolExecutionResult> results) {
        if (CollUtil.isEmpty(toolCalls) || CollUtil.isEmpty(results)) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[工具调用摘要]\n");
        for (int i = 0; i < toolCalls.size() && i < results.size(); i++) {
            ToolCall tc = toolCalls.get(i);
            ToolExecutionResult result = results.get(i);
            sb.append("- 工具: ").append(tc.getName());
            sb.append(", 参数: ").append(tc.getArguments());
            sb.append(", 结果: ").append(StrUtil.maxLength(result.resultText(), 200));
            if (result.isError()) {
                sb.append(" [错误]");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * 工具执行结果
     *
     * @param toolCallId      工具调用 ID
     * @param resultText      工具返回的文本
     * @param isError         是否为错误结果
     * @param sources         工具产生的来源引用（可为空）
     * @param groundingChunks 工具产生的 grounding 片段（可为空）
     */
    public record ToolExecutionResult(String toolCallId, String resultText, boolean isError,
                                       List<SourceRef> sources, List<GroundingChunk> groundingChunks) {
        /**
         * 兼容旧调用的便捷构造（无 sources）
         */
        public ToolExecutionResult(String toolCallId, String resultText, boolean isError) {
            this(toolCallId, resultText, isError, null, null);
        }
    }
}
