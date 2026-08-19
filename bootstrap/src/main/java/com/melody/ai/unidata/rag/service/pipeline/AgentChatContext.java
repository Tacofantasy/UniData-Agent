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

package com.melody.ai.unidata.rag.service.pipeline;

import com.melody.ai.unidata.infra.chat.StreamCallback;
import lombok.Builder;
import lombok.Getter;

/**
 * Agent 对话上下文
 */
@Getter
@Builder
public class AgentChatContext {

    /**
     * 用户问题
     */
    private final String question;

    /**
     * 会话 ID
     */
    private final String conversationId;

    /**
     * 任务 ID
     */
    private final String taskId;

    /**
     * 用户 ID
     */
    private final String userId;

    /**
     * 是否启用深度思考（ADR-0016：仅最终轮生效）
     */
    private final boolean deepThinking;

    /**
     * 流式回调
     */
    private final StreamCallback callback;
}
