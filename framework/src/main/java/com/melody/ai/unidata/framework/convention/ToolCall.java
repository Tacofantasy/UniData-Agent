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
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 工具调用请求
 * <p>
 * 当模型决定调用工具时，返回此对象。包含：
 * - id：工具调用唯一标识，用于关联工具结果消息
 * - name：要调用的工具名称
 * - arguments：工具参数（JSON 对象）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ToolCall {

    /**
     * 工具调用唯一标识（由模型生成，用于关联 tool 角色消息）
     */
    private String id;

    /**
     * 要调用的工具名称
     */
    private String name;

    /**
     * 工具参数（已解析为 Map）
     */
    private Map<String, Object> arguments;
}
