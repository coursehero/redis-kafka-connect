package com.redis.kafka.connect.source;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.RetriableException;
import org.apache.kafka.connect.source.SourceRecord;
import org.springframework.batch.item.ExecutionContext;

import com.redis.spring.batch.RedisItemReader;
import com.redis.spring.batch.reader.StreamItemReader;
import com.redis.spring.batch.reader.StreamReaderOptions;
import com.redis.spring.batch.reader.StreamReaderOptions.AckPolicy;

import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.Consumer;
import io.lettuce.core.RedisURI;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.api.StatefulConnection;

public class StreamSourceRecordReader extends AbstractSourceRecordReader<StreamMessage<String, String>> {

	public static final String OFFSET_FIELD = "offset";
	public static final String FIELD_ID = "id";
	public static final String FIELD_BODY = "body";
	public static final String FIELD_STREAM = "stream";
	private static final Schema KEY_SCHEMA = Schema.STRING_SCHEMA;
	private static final String VALUE_SCHEMA_NAME = "com.redis.kafka.connect.stream.Value";
	private static final Schema VALUE_SCHEMA = SchemaBuilder.struct().field(FIELD_ID, Schema.STRING_SCHEMA)
                       .field(FIELD_BODY, SchemaBuilder.map(Schema.STRING_SCHEMA, Schema.STRING_SCHEMA).build())
                       .field(FIELD_STREAM, Schema.STRING_SCHEMA).name(VALUE_SCHEMA_NAME).build();
	private final String topic;
    private final String consumerName;
    private final AckPolicy ackPolicy;

	private StreamItemReader<String, String> reader;
	private AbstractRedisClient client;
	private GenericObjectPool<StatefulConnection<String, String>> pool;
    private final ArrayList<StreamMessage<String, String>> pendingMsgs = new ArrayList<>();
	Clock clock = Clock.systemDefaultZone();
    StreamMessageRecovery recovery;

	public StreamSourceRecordReader(RedisSourceConfig sourceConfig, int taskId) {
		super(sourceConfig);
		this.topic = sourceConfig.getTopicName().replace(RedisSourceConfig.TOKEN_STREAM, sourceConfig.getStreamName());
        this.consumerName =
            sourceConfig.getStreamConsumerName().replace(
                RedisSourceConfig.TOKEN_TASK,
				String.valueOf(taskId));
        this.ackPolicy = convertDeliveryType(config.getStreamDeliveryType());
	}

    private AckPolicy convertDeliveryType(String deliveryType) {
        AckPolicy policy = AckPolicy.MANUAL;
        switch (config.getStreamDeliveryType()) {
        case RedisSourceConfig.STREAM_DELIVERY_TYPE_AT_MOST_ONCE:
            policy = AckPolicy.AUTO;
            break;
        case RedisSourceConfig.STREAM_DELIVERY_TYPE_AT_LEAST_ONCE:
            policy = AckPolicy.MANUAL;
            break;
        default:
            throw new IllegalArgumentException(
                "Illegal value for "
                    + RedisSourceConfig.STREAM_DELIVERY_TYPE_CONFIG
                    + ": " + config.getStreamDeliveryType());
        }
        return policy;
    }

    @Override
    public void open(Map<String, Object> offset) throws Exception {
        final RedisURI uri = config.uri();
        this.client = config.client(uri);
        this.pool = createPool(config);
        final Consumer<String> consumer = Consumer.from(config.getStreamConsumerGroup(), consumerName);
		this.reader = RedisItemReader
            .stream(pool, config.getStreamName(), consumer)
            .options(
                StreamReaderOptions.builder()
                    .offset(config.getStreamOffset())
                    .block(Duration.ofMillis(config.getStreamBlock()))
                    .count(config.getBatchSize())
                    .ackPolicy(ackPolicy)
                    .build())
				.build();
		reader.open(new ExecutionContext());
        final String lastCommitted = (String) offset.get(OFFSET_FIELD);
        recovery = new StreamMessageRecovery(pool, consumer, config, ackPolicy, lastCommitted);
    }

    // provided to allow overriding pool creation for testing.
    GenericObjectPool<StatefulConnection<String, String>> createPool(RedisSourceConfig config) {
        return config.pool(client);
    }

	@Override
	protected List<StreamMessage<String, String>> doPoll() throws Exception {
        try {
            final List<StreamMessage<String, String>> msgs = recovery.recoverMessages();
            if (!msgs.isEmpty()) {
                return msgs;
            }
            return reader.readMessages();
        } catch (final Exception e) {
            throw new RetriableException(e);
        }
	}

	@Override
	public void close() {
		if (reader != null) {
			reader.close();
			reader = null;
		}
		if (pool != null) {
			pool.close();
			pool = null;
		}
		if (client != null) {
			client.shutdown();
			client.getResources().shutdown();
			client = null;
		}
	}

	@Override
	protected SourceRecord convert(StreamMessage<String, String> message) {
	    final Map<String, ?> sourcePartition = new HashMap<>();
	    final Map<String, ?> sourceOffset = Collections.singletonMap(OFFSET_FIELD, message.getId());
	    final String key = message.getId();
	    final Struct value =
	        new Struct(VALUE_SCHEMA).put(FIELD_ID, message.getId()).put(FIELD_BODY, message.getBody())
	        .put(FIELD_STREAM, message.getStream());
        return new SourceRecord(sourcePartition, sourceOffset, topic, null, KEY_SCHEMA, key, VALUE_SCHEMA, value,
            clock.instant().toEpochMilli());
	}

    @Override
    public void commitRecord(SourceRecord r, RecordMetadata data) {
        if (ackPolicy == AckPolicy.MANUAL) {
            synchronized (pendingMsgs) {
                pendingMsgs.add(
                    new StreamMessage<String, String>(
                        config.getStreamName(),
                        ((String) r.sourceOffset().get(OFFSET_FIELD)),
                        null));
            }
        }
    }

    @Override
    public void commit() throws InterruptedException {
        try {
            if (ackPolicy == AckPolicy.MANUAL) {
                synchronized (pendingMsgs) {
                    if (!pendingMsgs.isEmpty()) {
                        reader.ack(pendingMsgs);
                        pendingMsgs.clear();
                    }
                }
            }
        } catch (final InterruptedException e) {
            throw e;
        } catch (final Exception e) {
            throw new RetriableException(e);
        }
    }
}
