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

package com.melody.ai.unidata.framework.convention;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 大模型结构化响应
 * <p>
 * 用于 Function Calling 场景，同时返回文本内容和工具调用请求。
 * - 当模型决定直接回答时，content 非空、toolCalls 为空
 * - 当模型决定调用工具时，content 可能为空、toolCalls 非空
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatResponse {

    /**
     * 模型返回的文本内容（可能为空，当模型选择调用工具时）
     */
    private String content;

    /**
     * 模型请求执行的工具调用列表（为空表示模型未请求调用工具）
     */
    private List<ToolCall> toolCalls;

    /**
     * 是否包含工具调用
     */
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    /**
     * 是否为纯文本回答（无工具调用）
     */
    public boolean isTextOnly() {
        return !hasToolCalls() && content != null && !content.isBlank();
    }

    /**
     * 快速构建纯文本响应
     */
    public static ChatResponse text(String content) {
        return ChatResponse.builder().content(content).build();
    }

    /**
     * 快速构建工具调用响应
     */
    public static ChatResponse toolCalls(List<ToolCall> toolCalls) {
        return ChatResponse.builder().toolCalls(toolCalls).build();
    }
}
