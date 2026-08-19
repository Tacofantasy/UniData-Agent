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

package com.melody.ai.unidata.rag.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.melody.ai.unidata.framework.context.UserContext;
import com.melody.ai.unidata.infra.chat.StreamCallback;
import com.melody.ai.unidata.rag.config.OrchestrationMode;
import com.melody.ai.unidata.rag.config.OrchestrationProperties;
import com.melody.ai.unidata.rag.service.ratelimit.ChatQueueLimiter;
import com.melody.ai.unidata.rag.service.RAGChatService;
import com.melody.ai.unidata.rag.service.handler.StreamCallbackFactory;
import com.melody.ai.unidata.rag.service.handler.StreamTaskManager;
import com.melody.ai.unidata.rag.service.pipeline.AgentChatContext;
import com.melody.ai.unidata.rag.service.pipeline.AgentChatPipeline;
import com.melody.ai.unidata.rag.service.pipeline.StreamChatContext;
import com.melody.ai.unidata.rag.service.pipeline.StreamChatPipeline;
import com.melody.ai.unidata.rag.trace.StreamChatTraceRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * RAG 对话服务默认实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RAGChatServiceImpl implements RAGChatService {

    private final StreamChatPipeline streamChatPipeline;
    private final AgentChatPipeline agentChatPipeline;
    private final OrchestrationProperties orchestrationProperties;
    private final ChatQueueLimiter chatQueueLimiter;
    private final StreamCallbackFactory callbackFactory;
    private final StreamChatTraceRunner traceRunner;
    private final StreamTaskManager taskManager;

    @Override
    public void streamChat(String question, String conversationId, Boolean deepThinking, SseEmitter emitter) {
        String actualConversationId = StrUtil.isBlank(conversationId) ? IdUtil.getSnowflakeNextIdStr() : conversationId;
        String taskId = IdUtil.getSnowflakeNextIdStr();
        StreamCallback callback = callbackFactory.createChatEventHandler(emitter, actualConversationId, taskId);

        OrchestrationMode mode = orchestrationProperties.getMode();

        chatQueueLimiter.enqueue(question, actualConversationId, emitter,
                () -> traceRunner.run(question, actualConversationId, taskId, callback, traceAware -> {
                    if (mode == OrchestrationMode.AGENT) {
                        // Agent 模式：ReAct 循环
                        AgentChatContext ctx = AgentChatContext.builder()
                                .question(question)
                                .conversationId(actualConversationId)
                                .taskId(taskId)
                                .deepThinking(Boolean.TRUE.equals(deepThinking))
                                .userId(UserContext.getUserId())
                                .callback(traceAware)
                                .build();
                        agentChatPipeline.execute(ctx);
                    } else {
                        // WORKFLOW 模式：线性管线
                        StreamChatContext ctx = StreamChatContext.builder()
                                .question(question)
                                .conversationId(actualConversationId)
                                .taskId(taskId)
                                .deepThinking(Boolean.TRUE.equals(deepThinking))
                                .userId(UserContext.getUserId())
                                .callback(traceAware)
                                .build();
                        streamChatPipeline.execute(ctx);
                    }
                }));
    }

    @Override
    public void stopTask(String taskId) {
        taskManager.cancel(taskId);
    }
}
