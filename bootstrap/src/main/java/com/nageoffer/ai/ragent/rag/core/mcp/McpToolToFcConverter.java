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

import com.nageoffer.ai.ragent.framework.convention.ToolDefinition;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP Tool → OpenAI FC ToolDefinition 转换器
 * <p>
 * 将 MCP 工具定义转换为框架内部 {@link ToolDefinition} 格式，
 * 供 Agent 构造 {@code ChatRequest.tools} 时使用。
 */
@Slf4j
@Component
public class McpToolToFcConverter {

    /**
     * 转换单个 MCP Tool 为 FC ToolDefinition
     */
    public ToolDefinition convert(Tool mcpTool) {
        if (mcpTool == null) {
            return null;
        }

        ToolDefinition.FunctionDef functionDef = ToolDefinition.FunctionDef.builder()
                .name(mcpTool.name())
                .description(mcpTool.description())
                .parameters(convertJsonSchema(mcpTool.inputSchema()))
                .build();

        return ToolDefinition.builder()
                .type("function")
                .function(functionDef)
                .build();
    }

    /**
     * 批量转换
     */
    public List<ToolDefinition> convertAll(List<Tool> mcpTools) {
        if (mcpTools == null || mcpTools.isEmpty()) {
            return List.of();
        }
        return mcpTools.stream()
                .map(this::convert)
                .toList();
    }

    /**
     * 将 MCP JsonSchema 转换为 Map 形式（用于 ToolDefinition.FunctionDef.parameters）
     */
    private Map<String, Object> convertJsonSchema(JsonSchema schema) {
        if (schema == null) {
            return Map.of();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", schema.type() != null ? schema.type() : "object");

        if (schema.properties() != null) {
            result.put("properties", schema.properties());
        }

        if (schema.required() != null && !schema.required().isEmpty()) {
            result.put("required", schema.required());
        }

        return result;
    }
}
