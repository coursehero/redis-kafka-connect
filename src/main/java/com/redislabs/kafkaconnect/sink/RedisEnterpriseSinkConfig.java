/**
 * Copyright © 2021 Redis Labs
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.redislabs.kafkaconnect.sink;

import com.redislabs.kafkaconnect.source.RedisEnterpriseSourceConfig;
import lombok.Getter;
import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.common.config.ConfigDef;

import java.util.Map;

public class RedisEnterpriseSinkConfig extends AbstractConfig {

    public static final ConfigDef CONFIG_DEF = new RedisEnterpriseSinkConfigDef();

    public static final String STREAM_NAME_FORMAT = "stream.name.format";
    public static final String STREAM_NAME_FORMAT_DEFAULT = "${topic}";
    public static final String STREAM_NAME_FORMAT_DOC = "A format string for the destination stream name, which may contain '${topic}' as a " + "placeholder for the originating topic name.\n" + "For example, ``kafka_${topic}`` for the topic 'orders' will map to the stream name " + "'kafka_orders'.";
    public static final String STREAM_NAME_FORMAT_DISPLAY = "Stream Name Format";

    public static final String TRANSACTIONAL = "transactional";
    public static final String TRANSACTIONAL_DEFAULT = "false";
    public static final String TRANSACTIONAL_DOC = "Whether to execute Redis commands in multi/exec transactions.";
    public static final String TRANSACTIONAL_DISPLAY = "Use Transactions";

    @Getter
    private final String redisUri;
    @Getter
    private final String streamNameFormat;
    @Getter
    private final Boolean transactional;

    public RedisEnterpriseSinkConfig(final Map<?, ?> originals) {
        this(originals, true);
    }

    private RedisEnterpriseSinkConfig(final Map<?, ?> originals, final boolean validateAll) {
        super(CONFIG_DEF, originals, false);
        redisUri = getString(RedisEnterpriseSourceConfig.REDIS_URI);
        streamNameFormat = getString(STREAM_NAME_FORMAT).trim();
        transactional = getBoolean(TRANSACTIONAL);
    }


    public static class RedisEnterpriseSinkConfigDef extends ConfigDef {

        public RedisEnterpriseSinkConfigDef() {
            String group = "Redis Enterprise";
            int order = 0;
            define(RedisEnterpriseSourceConfig.REDIS_URI, Type.STRING, RedisEnterpriseSourceConfig.REDIS_URI_DEFAULT, Importance.HIGH, RedisEnterpriseSourceConfig.REDIS_URI_DOC, group, ++order, Width.MEDIUM, RedisEnterpriseSourceConfig.REDIS_URI_DISPLAY);
            define(STREAM_NAME_FORMAT, Type.STRING, STREAM_NAME_FORMAT_DEFAULT, Importance.MEDIUM, STREAM_NAME_FORMAT_DOC, group, ++order, Width.MEDIUM, STREAM_NAME_FORMAT_DISPLAY);
            define(TRANSACTIONAL, Type.BOOLEAN, TRANSACTIONAL_DEFAULT, Importance.MEDIUM, TRANSACTIONAL_DOC, group, ++order, Width.SHORT, TRANSACTIONAL_DISPLAY);
        }

    }
}
