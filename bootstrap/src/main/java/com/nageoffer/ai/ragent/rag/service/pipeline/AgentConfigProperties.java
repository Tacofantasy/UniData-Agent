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

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent 配置属性
 * <p>
 * 对应 application.yaml 中的 ragent.agent 前缀配置项
 */
@Data
@Component
@ConfigurationProperties(prefix = "ragent.agent")
public class AgentConfigProperties {

    /**
     * ReAct 循环最大迭代次数（ADR-0005）
     * 默认 5 次，可配置
     */
    private int maxIterations = 5;

    /**
     * 工具并行执行的线程池大小（ADR-0015）
     */
    private int toolParallelism = 4;
}
