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

package com.nageoffer.ai.ragent.rag.core.mcp;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.SourceRef;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.rag.core.intent.IntentResolver;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.core.source.GroundingChunksAssembler;
import com.nageoffer.ai.ragent.rag.core.source.SourcesAssembler;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 检索工具执行器
 * <p>
 * 将 RAG 检索能力封装为 MCP Tool，供 Agent 通过 Function Calling 调用。
 * <p>
 * 内部流程：跳过 QueryRewrite（Agent 已做改写），直接跑 IntentResolver → RetrievalEngine，
 * 将检索结果格式化为文本返回给 Agent，同时通过 {@link SourcesAccumulator} 累加 sources/grounding。
 */
@Slf4j
@Component
public class RagSearchToolExecutor implements McpToolExecutor {

    public static final String TOOL_ID = "rag_search";
    public static final String PARAM_QUERY = "query";

    private static final String TOOL_DESCRIPTION =
            "知识库检索工具。基于企业知识库（文档、手册、FAQ等）进行语义检索，返回与查询最相关的文档片段。" +
            "当需要查找企业内部资料、产品文档、操作指南、政策规范等信息时调用此工具。";

    private final IntentResolver intentResolver;
    private final RetrievalEngine retrievalEngine;
    private final SourcesAssembler sourcesAssembler;
    private final GroundingChunksAssembler groundingChunksAssembler;
    private final SourcesAccumulator sourcesAccumulator;

    /**
     * 手动构造函数：对 RetrievalEngine 使用 @Lazy 打破循环依赖
     * （RetrievalEngine → McpToolRegistry → List<McpToolExecutor> → 本类 → RetrievalEngine）
     */
    public RagSearchToolExecutor(
            IntentResolver intentResolver,
            @Lazy RetrievalEngine retrievalEngine,
            SourcesAssembler sourcesAssembler,
            GroundingChunksAssembler groundingChunksAssembler,
            SourcesAccumulator sourcesAccumulator) {
        this.intentResolver = intentResolver;
        this.retrievalEngine = retrievalEngine;
        this.sourcesAssembler = sourcesAssembler;
        this.groundingChunksAssembler = groundingChunksAssembler;
        this.sourcesAccumulator = sourcesAccumulator;
    }

    @Override
    public Tool getToolDefinition() {
        return Tool.builder()
                .name(TOOL_ID)
                .description(TOOL_DESCRIPTION)
                .inputSchema(buildInputSchema())
                .build();
    }

    @Override
    @RagTraceNode(name = "rag-search-tool", type = "RAG_TOOL")
    public CallToolResult execute(Map<String, Object> parameters) {
        long startMs = System.currentTimeMillis();
        try {
            String query = extractQuery(parameters);
            if (StrUtil.isBlank(query)) {
                return errorResult("query 参数不能为空");
            }

            // 跳过 QueryRewrite：Agent 负责改写，RAG Tool 直接用改写后的 query
            RewriteResult rewriteResult = new RewriteResult(query, List.of(query));

            // 意图解析 + 检索
            List<SubQuestionIntent> subIntents = intentResolver.resolve(rewriteResult);
            RetrievalContext retrievalCtx = retrievalEngine.retrieve(subIntents);

            // 累加 sources 和 grounding chunks（供 Pipeline 循环结束后注入到最终消息）
            List<SourceRef> sources = sourcesAssembler.assemble(retrievalCtx.getIntentChunks());
            sourcesAccumulator.accumulateSources(sources);
            sourcesAccumulator.accumulateGroundingChunks(
                    groundingChunksAssembler.assemble(retrievalCtx.getIntentChunks()));

            // 构建返回给 Agent 的文本上下文
            String context = buildToolResultText(retrievalCtx, sources);
            if (StrUtil.isBlank(context)) {
                context = "未检索到相关内容。请尝试换个关键词或检查查询是否属于知识库覆盖范围。";
            }

            log.info("RAG 检索工具调用完成, query={}, sources={}, elapsed={}ms",
                    query, sources.size(), System.currentTimeMillis() - startMs);
            return successResult(context);
        } catch (Exception e) {
            log.error("RAG 检索工具调用失败, params={}, elapsed={}ms",
                    parameters, System.currentTimeMillis() - startMs, e);
            return errorResult("检索失败: " + e.getMessage());
        }
    }

    /**
     * 构建工具参数 JSON Schema
     */
    private JsonSchema buildInputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(PARAM_QUERY, Map.of(
                "type", "string",
                "description", "检索查询文本（可由 Agent 改写后的查询）"
        ));
        return new JsonSchema("object", properties, List.of(PARAM_QUERY), null, null, null);
    }

    /**
     * 从参数中提取 query
     */
    private String extractQuery(Map<String, Object> parameters) {
        if (parameters == null) {
            return null;
        }
        Object query = parameters.get(PARAM_QUERY);
        return query != null ? query.toString() : null;
    }

    /**
     * 构建返回给 Agent 的文本
     * <p>
     * 包含 KB 上下文和来源列表，使 Agent 能基于检索结果回答用户问题
     */
    private String buildToolResultText(RetrievalContext retrievalCtx, List<SourceRef> sources) {
        StringBuilder sb = new StringBuilder();

        if (retrievalCtx.hasKb()) {
            sb.append("## 检索到的知识内容\n\n");
            sb.append(retrievalCtx.getKbContext());
            sb.append("\n\n");
        }

        if (retrievalCtx.hasMcp()) {
            sb.append("## 工具检索结果\n\n");
            sb.append(retrievalCtx.getMcpContext());
            sb.append("\n\n");
        }

        if (CollUtil.isNotEmpty(sources)) {
            sb.append("## 来源文档\n\n");
            for (SourceRef source : sources) {
                sb.append("- ").append(source.getDocName() != null ? source.getDocName() : source.getDocId());
                if (StrUtil.isNotBlank(source.getExcerpt())) {
                    sb.append("：").append(source.getExcerpt());
                }
                sb.append("\n");
            }
        }

        return sb.toString().trim();
    }

    private static CallToolResult successResult(String text) {
        return CallToolResult.builder()
                .content(List.of(new TextContent(text)))
                .isError(false)
                .build();
    }

    private static CallToolResult errorResult(String message) {
        return CallToolResult.builder()
                .content(List.of(new TextContent(message)))
                .isError(true)
                .build();
    }
}
