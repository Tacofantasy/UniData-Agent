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

package com.melody.ai.unidata;

import com.mzt.logapi.starter.annotation.EnableLogRecord;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * UniData Agent 核心应用启动类
 */
@SpringBootApplication
@EnableScheduling
@EnableLogRecord(tenant = "unidata", proxyTargetClass = true)
@MapperScan(basePackages = {
        "com.melody.ai.unidata.rag.dao.mapper",
        "com.melody.ai.unidata.ingestion.dao.mapper",
        "com.melody.ai.unidata.knowledge.dao.mapper",
        "com.melody.ai.unidata.user.dao.mapper",
        "com.melody.ai.unidata.audit.dao.mapper"
})
public class UniDataApplication {

    public static void main(String[] args) {
        SpringApplication.run(UniDataApplication.class, args);
    }
}
