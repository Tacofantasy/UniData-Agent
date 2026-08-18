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

package com.nageoffer.ai.ragent.framework.convention;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 工具定义
 * <p>
 * 描述一个可供 LLM 调用的工具的元信息，用于 Function Calling。
 * 对应 OpenAI 兼容协议中 tools 数组的一个元素。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ToolDefinition {

    /**
     * 工具类型，固定为 "function"
     */
    @Builder.Default
    private String type = "function";

    /**
     * 函数定义
     */
    private FunctionDef function;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class FunctionDef {

        /**
         * 工具名称
         */
        private String name;

        /**
         * 工具描述
         */
        private String description;

        /**
         * 参数 JSON Schema（Map 形式）
         * <p>
         * 包含 type、properties、required 等字段
         */
        private Map<String, Object> parameters;
    }

    /**
     * 快速创建工具定义
     */
    public static ToolDefinition of(String name, String description, Map<String, Object> parameters) {
        return ToolDefinition.builder()
                .function(FunctionDef.builder()
                        .name(name)
                        .description(description)
                        .parameters(parameters)
                        .build())
                .build();
    }
}
