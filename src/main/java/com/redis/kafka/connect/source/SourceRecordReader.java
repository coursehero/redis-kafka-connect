package com.redis.kafka.connect.source;

import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.connect.source.SourceRecord;

public interface SourceRecordReader {

	void open(Map<String, Object> offset) throws Exception;

	List<SourceRecord> poll();

	default void commit() throws InterruptedException {
	}

	default void commitRecord(SourceRecord r, RecordMetadata m) throws InterruptedException {
	}

	void close();
}
