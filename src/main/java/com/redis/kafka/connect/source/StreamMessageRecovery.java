package com.redis.kafka.connect.source;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.pool2.impl.GenericObjectPool;

import com.redis.spring.batch.common.Utils;
import com.redis.spring.batch.reader.StreamReaderOptions.AckPolicy;

import io.lettuce.core.Consumer;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.XReadArgs.StreamOffset;
import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.api.sync.RedisStreamCommands;

class StreamMessageRecovery {
    private final GenericObjectPool<StatefulConnection<String, String>> pool;
    private final Consumer<String> consumer;
    private final RedisSourceConfig config;
    private final AckPolicy ackPolicy;
    private final StreamId recoveryId;
    private StreamId startOffset = StreamId.ZERO;
    private boolean recovered = false;

    StreamMessageRecovery(
        GenericObjectPool<StatefulConnection<String, String>> pool,
        Consumer<String> consumer,
        RedisSourceConfig config,
        AckPolicy ackPolicy,
        String lastCommitted) {

        this.pool = pool;
        this.consumer = consumer;
        this.config = config;
        this.ackPolicy = ackPolicy;
        StreamId recoveryId;
        try {
            recoveryId = StreamId.parse(lastCommitted);
        } catch (final RuntimeException e) {
            // If we can't parse the last committed offset, we'll recover all
            // the messages
            recoveryId = StreamId.ZERO;
        }
        this.recoveryId = recoveryId;
    }

    void initialize() throws Exception {
        try (StatefulConnection<String, String> connection = pool.borrowObject()) {
            final RedisStreamCommands<String, String> commands = Utils.sync(connection);
            final List<Object> xinfoGroups = commands.xinfoGroups(config.getStreamName());
            boolean found = false;
            for (final Object groupInfoObj : xinfoGroups) {
                final List<?> groupInfo = (List<?>) groupInfoObj;
                for (int i = 0; i < groupInfo.size(); ++i) {
                    if ("name".equals(String.valueOf(groupInfo.get(i)))) {
                        if (config.getStreamConsumerGroup().equals(String.valueOf(groupInfo.get(i + 1)))) {
                            found = true;
                        }
                    }
                }
            }
            // Our consumer group doesn't yet exist, so we don't have anything to recover
            if (!found) {
                System.out.println("could not find consumer group " + config.getStreamConsumerGroup());
                recovered = true;
            }
        }
    }

    /**
     * Returns any messages not yet been processed by Kafka. For
     * {@link AckPolicy#AUTO}, this will never return any messages, but will
     * {@code XACK} any messages in the pending list for the consumer group
     * represented by the {@link RedisSourceConfig}. For {@link AckPolicy#MANUAL},
     * this will return any messages whose stream ids were not committed to Kafka.
     *
     * @return any unprocessed messages to be processed. If this returns an empty
     *         list, there are no more messages to be recovered.
     * @throws Exception
     */
    @SuppressWarnings("unchecked")
    List<StreamMessage<String, String>> recoverMessages() throws Exception {
        if (recovered) {
            return emptyList();
        }
        // There are two pending message types here, processed and unprocessed.
        // Processed messages have already been converted and sent to Kafka.
        // These should not be sent again, but they must be XACK'd to clear the
        // pending entries list in Redis.
        // Unprocessed messages were received from Redis, but due to shutdown or
        // failure, _may_ not have been sent to Kafka. The lastCommitted offset
        // we receive in the constructor is the last message we know was sent to
        // Kafka; anything after the lastCommitted id may or may not have been sent.
        // If our AckPolicy is AUTO, i.e., "at most once", we must treat
        // these as having already been sent, and we will XACK them. Otherwise,
        // if our AckPolicy is MANUAL, i.e. "at least once", we can send them
        // again. All messages in the needsAck list will be XACK'd. The messages
        // in the unprocessed list will be returned to the caller.
        // Kafka Connect can support exactly-once processing in version
        // 3.3, and "at-least-once" processing supports exactly-once semantics.
        final ArrayList<StreamMessage<String, String>> needsAck = new ArrayList<>();
        final ArrayList<StreamMessage<String, String>> unprocessed = new ArrayList<>();
        final long batchSize = config.getBatchSize();
        try (final StatefulConnection<String, String> connection = pool.borrowObject()) {
            final RedisStreamCommands<String, String> commands = Utils.sync(connection);
            while (unprocessed.size() < batchSize) {
                // XREADGROUP with any id other than '>' or '$' only returns pending messages,
                // and doesn't block.
                final List<StreamMessage<String, String>> messages =
                    commands.xreadgroup(
                        consumer,
                        XReadArgs.Builder.count(batchSize - unprocessed.size()),
                        StreamOffset.from(config.getStreamName(), startOffset.toString()));
                // If we don't have any more pending messages, we can XACK the ones needing
                // to be XACK'd and return any unprocessed messages.
                if (messages.isEmpty()) {
                    break;
                }
                if (ackPolicy == AckPolicy.AUTO) {
                    needsAck.addAll(messages);
                } else {
                    for (final StreamMessage<String, String> m : messages) {
                        final StreamId msgId = StreamId.parse(m.getId());
                        if (msgId.compareTo(recoveryId) > 0) {
                            unprocessed.add(m);
                            startOffset = StreamId.parse(m.getId());
                        } else {
                            needsAck.add(m);
                        }
                    }
                }
                // XACK any messages that were already written to Kafka.
                needsAck.stream().collect(groupingBy(StreamMessage::getStream))
                .forEach(
                    (stream, msgs) -> commands.xack(
                            stream,
                            config.getStreamConsumerGroup(),
                        msgs.stream()
                        .map(StreamMessage::getId)
                        .peek(x -> { System.out.println("XACK " + x); })
                        .collect(toList())
                        .toArray(new String[0])));
                needsAck.clear();
            }
        }
        if (unprocessed.size() == 0) {
            recovered = true;
        }
        return unprocessed;
    }

    public static class StreamId implements Comparable<StreamId> {
        public static StreamId ZERO = StreamId.of(0, 0);
        private final long millis;
        private final long sequence;

        public StreamId(long millis, long sequence) {
            this.millis = millis;
            this.sequence = sequence;
        }

        public static StreamId parse(String id) {
            final int off = id.indexOf("-");
            if (off == -1) {
                final long millis = Long.parseLong(id);
                if (millis < 0) {
                    throw new IllegalArgumentException(String.format("not an id: %s", id));
                }
                return StreamId.of(millis, 0L);
            }
            final long millis = Long.parseLong(id.substring(0, off));
            if (millis < 0) {
                throw new IllegalArgumentException(String.format("not an id: %s", id));
            }
            final long sequence = Long.parseLong(id.substring(off + 1));
            if (sequence < 0) {
                throw new IllegalArgumentException(String.format("not an id: %s", id));
            }
            return StreamId.of(millis, sequence);
        }

        public static StreamId of(long millis, long sequence) {
            return new StreamId(millis, sequence);
        }

        public String toStreamId() {
            return millis + "-" + sequence;
        }

        @Override
        public String toString() {
            return toStreamId();
        }

        @Override
        public int compareTo(StreamId o) {
            final long diff = millis - o.millis;
            if (diff != 0) {
                return Long.signum(diff);
            }
            return Long.signum(sequence - o.sequence);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) {
                return true;
            }
            if (obj == null || !(obj instanceof StreamId)) {
                return false;
            }
            final StreamId o = (StreamId) obj;
            return o.millis == millis && o.sequence == sequence;
        }

        @Override
        public int hashCode() {
            final long val = millis * 31 * sequence;
            return (int) (val ^ (val >> 32));
        }
    }
}
