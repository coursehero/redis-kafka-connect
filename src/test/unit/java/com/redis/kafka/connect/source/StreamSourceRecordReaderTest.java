package com.redis.kafka.connect.source;

import static java.util.Collections.emptyMap;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import io.lettuce.core.StreamMessage;
import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

@ExtendWith(MockitoExtension.class)
class StreamSourceRecordReaderTest {

    public static final String OFFSET_FIELD = "offset";
    public static final String FIELD_ID = "id";
    public static final String FIELD_BODY = "body";
    public static final String FIELD_STREAM = "stream";
    private static final String VALUE_SCHEMA_NAME = "com.redis.kafka.connect.stream.Value";
    private static final long NOW = System.currentTimeMillis();
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneId.systemDefault());

    // This is published, so if it's changed, it may impact users.
    private static final Schema PUBLISHED_SCHEMA =
        SchemaBuilder.struct().field(FIELD_ID, Schema.STRING_SCHEMA)
            .field(FIELD_BODY, SchemaBuilder.map(Schema.STRING_SCHEMA, Schema.STRING_SCHEMA).build())
            .field(FIELD_STREAM, Schema.STRING_SCHEMA).name(VALUE_SCHEMA_NAME).build();

    @Mock(stubOnly = true)
    GenericObjectPool<StatefulConnection<String, String>> pool;

    @ParameterizedTest(name = "{index} {0}")
    @ArgumentsSource(PollArgsProvider.class)
    void testPoll(PollArgs args) throws Exception {
        final StreamSourceRecordReader sut = new StreamSourceRecordReader(args.config, 0) {
            @Override
            GenericObjectPool<StatefulConnection<String, String>> createPool(RedisSourceConfig config) {
                args.configurePool(pool);
                return pool;
            }
        };
        sut.open(args.connectOffset);

        final List<StreamMessage<String, String>> got = sut.doPoll();
        assertEquals(args.want, got);
    }

    static class PollArgsProvider implements ArgumentsProvider {

        @SuppressWarnings("unchecked")
        @Override
        public Stream<PollArgs> provideArguments(ExtensionContext context) throws Exception {
            return Arrays.<Supplier<PollArgs>>asList(/*() -> {
                final String name = "single message new group";
                final String id = "1-0";
                return new PollArgs(
                    name,
                    new RedisSourceConfig(mapOf("redis.stream.name", name)),
                    pool -> {
                        final StatefulRedisConnection<String, String> conn =
                            Mockito.mock(StatefulRedisConnection.class);
                        when(wrapEx(pool::borrowObject)).thenReturn(conn);

                        final RedisCommands<String, String> commands = Mockito.mock(RedisCommands.class);
                        when(conn.sync()).thenReturn(commands);

                        when(commands.xgroupCreate(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn("0");
                        when(commands.xreadgroup(Mockito.any(), Mockito.any(), Mockito.any()))
                            .thenReturn(Arrays.asList(new StreamMessage<String, String>(name, id, emptyMap())));
                    },
                    Collections.emptyMap(),
                    Arrays.<StreamMessage<String, String>>asList(
                        new StreamMessage<String, String>(name, id, emptyMap()))
                );
            }*/).stream().map(Supplier::get);
        }
    }

    static <T> T wrapEx(Callable<T> c) {
        try {
            return c.call();
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }

    }

    static class PollArgs implements Arguments {
        String name;
        RedisSourceConfig config;
        Consumer<GenericObjectPool<StatefulConnection<String, String>>> poolSetup;
        Map<String, Object> connectOffset;
        List<StreamMessage<String, String>> want;

        PollArgs(
            String name,
            RedisSourceConfig config,
            Consumer<GenericObjectPool<StatefulConnection<String, String>>> poolSetup,
            Map<String, Object> connectOffset,
            List<StreamMessage<String, String>> want) {
            this.name = name;
            this.config = config;
            this.poolSetup = poolSetup;
            this.want = want;
            this.connectOffset = connectOffset;
        }

        public void configurePool(GenericObjectPool<StatefulConnection<String, String>> pool) {
            poolSetup.accept(pool);
        }

        @Override
        public Object[] get() {
            return new Object[] { this };
        }

        @Override
        public String toString() {
            return name;
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ConvertArgsProvider.class)
    void testConvert(ConvertArgs args) {
        final StreamSourceRecordReader r = new StreamSourceRecordReader(args.config, 0);
        r.clock = CLOCK;

        final SourceRecord got = r.convert(args.message);

        assertThat(got, equalTo(args.want));
    }

    static class ConvertArgsProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            return Stream.of(
                new ConvertArgs(
                    "empty values",
                    new RedisSourceConfig(
                        mapOf("redis.stream.name", "stream1", "redis.stream.delivery.type", "at-most-once")),
                    new StreamMessage<>("stream1", "1-0", mapOf()),
                    new SourceRecord(
                        mapOf(),
                        mapOf("offset", "1-0"),
                        "stream1",
                        null,
                        Schema.STRING_SCHEMA,
                        "1-0",
                        PUBLISHED_SCHEMA,
                        new Struct(PUBLISHED_SCHEMA)
                            .put(FIELD_ID, "1-0")
                            .put(FIELD_STREAM, "stream1")
                            .put(FIELD_BODY, mapOf()),
                        NOW)),

                new ConvertArgs(
                    "map single key",
                    new RedisSourceConfig(
                        mapOf("redis.stream.name", "stream2", "redis.stream.delivery.type", "at-most-once")),
                    new StreamMessage<>("stream2", "2-0", mapOf("key2", "value2")),
                    new SourceRecord(
                        mapOf(),
                        mapOf("offset", "2-0"),
                        "stream2",
                        null,
                        Schema.STRING_SCHEMA,
                        "2-0",
                        PUBLISHED_SCHEMA,
                        new Struct(PUBLISHED_SCHEMA)
                            .put(FIELD_ID, "2-0")
                            .put(FIELD_STREAM, "stream2")
                            .put(FIELD_BODY, mapOf("key2", "value2")),
                        NOW)),

                new ConvertArgs(
                    "map single key, different topic",
                    new RedisSourceConfig(
                        mapOf(
                            "redis.stream.name",
                            "stream3",
                            "topic",
                            "topic3",
                            "redis.stream.delivery.type",
                            "at-most-once")),
                    new StreamMessage<>("stream3", "3-0", mapOf("key3", "value3")),
                    new SourceRecord(
                        mapOf(),
                        mapOf("offset", "3-0"),
                        "topic3",
                        null,
                        Schema.STRING_SCHEMA,
                        "3-0",
                        PUBLISHED_SCHEMA,
                        new Struct(PUBLISHED_SCHEMA)
                            .put(FIELD_ID, "3-0")
                            .put(FIELD_STREAM, "stream3")
                            .put(FIELD_BODY, mapOf("key3", "value3")),
                        NOW)),

                new ConvertArgs(
                    "map single key, different topic, at-least-once",
                    new RedisSourceConfig(
                        mapOf(
                            "redis.stream.name",
                            "stream3",
                            "topic",
                            "topic3",
                            "redis.stream.delivery.type",
                            "at-least-once")),
                    new StreamMessage<>("stream3", "3-0", mapOf("key3", "value3")),
                    new SourceRecord(
                        mapOf(),
                        mapOf("offset", "3-0"),
                        "topic3",
                        null,
                        Schema.STRING_SCHEMA,
                        "3-0",
                        PUBLISHED_SCHEMA,
                        new Struct(PUBLISHED_SCHEMA)
                            .put(FIELD_ID, "3-0")
                            .put(FIELD_STREAM, "stream3")
                            .put(FIELD_BODY, mapOf("key3", "value3")),
                        NOW))
            );
        }
    }

    static class ConvertArgs implements Arguments {
        String name;
        RedisSourceConfig config;
        StreamMessage<String, String> message;
        SourceRecord want;

        ConvertArgs() {
        }

        ConvertArgs(String name, RedisSourceConfig config, StreamMessage<String, String> message, SourceRecord want) {
            this.name = name;
            this.config = config;
            this.message = message;
            this.want = want;
        }

        @Override
        public Object[] get() {
            return new Object[] { this };
        }

        @Override
        public String toString() {
            return name;
        }
    }

    static Map<String, String> mapOf(String... args) {
        final HashMap<String, String> ret = new HashMap<>();
        int i = 0;
        for (; i < args.length; i+=2) {
            ret.put(args[i], args[i+1]);
        }
        if (i != args.length) {
            throw new IllegalArgumentException("Expects an even number of arguments");
        }
        return ret;
    }

}
