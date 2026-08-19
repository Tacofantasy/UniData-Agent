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

package com.melody.ai.unidata.rag.core.mcp;

import com.alibaba.ttl.TransmittableThreadLocal;
import com.melody.ai.unidata.framework.convention.GroundingChunk;
import com.melody.ai.unidata.framework.convention.SourceRef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 来源累加器（ThreadLocal-scoped）
 * <p>
 * 在 Agent ReAct 循环中，RAG Tool 每次被调用都会产生 sources 和 grounding chunks，
 * 但这些无法通过 {@code CallToolResult} 直接返回给前端。
 * <p>
 * 使用 ThreadLocal 在单次 Agent 执行线程内跨多次工具调用累加 sources/grounding，
 * 循环结束后由 Pipeline 统一取出，注入到最终的 assistant 消息中。
 * 因 ChatQueueLimiter 在独立线程池执行，无法用 RequestScope，改用 ThreadLocal。
 */
@Slf4j
@Component
public class SourcesAccumulator {

    // 使用 TransmittableThreadLocal：agentToolExecutor 已被 TtlExecutors 包装，
    // TTL 能在 CompletableFuture.supplyAsync 跨线程池传递，子线程写入的数据父线程可见
    private static final TransmittableThreadLocal<List<SourceRef>> sourcesHolder = TransmittableThreadLocal.withInitial(ArrayList::new);
    private static final TransmittableThreadLocal<List<GroundingChunk>> chunksHolder = TransmittableThreadLocal.withInitial(ArrayList::new);

    /**
     * 累加来源引用
     */
    public void accumulateSources(List<SourceRef> sources) {
        if (sources != null && !sources.isEmpty()) {
            sourcesHolder.get().addAll(sources);
        }
    }

    /**
     * 累加 grounding chunks
     */
    public void accumulateGroundingChunks(List<GroundingChunk> chunks) {
        if (chunks != null && !chunks.isEmpty()) {
            chunksHolder.get().addAll(chunks);
        }
    }

    /**
     * 获取累加的来源列表（去重后）
     * <p>
     * 按 docId 去重，保留首次出现的 SourceRef
     */
    public List<SourceRef> getAccumulatedSources() {
        return sourcesHolder.get().stream()
                .filter(distinctByKey(SourceRef::getDocId))
                .toList();
    }

    /**
     * 获取累加的 grounding chunks（去重后）
     */
    public List<GroundingChunk> getAccumulatedGroundingChunks() {
        return chunksHolder.get().stream()
                .filter(distinctByKey(GroundingChunk::getDocName))
                .toList();
    }

    /**
     * 是否有累加的来源
     */
    public boolean hasSources() {
        return !sourcesHolder.get().isEmpty();
    }

    /**
     * 清理 ThreadLocal（在 Pipeline 执行结束后调用）
     */
    public void clear() {
        sourcesHolder.remove();
        chunksHolder.remove();
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
}
