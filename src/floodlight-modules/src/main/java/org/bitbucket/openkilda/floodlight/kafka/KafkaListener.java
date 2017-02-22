package org.bitbucket.openkilda.floodlight.kafka;

import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KafkaListener implements Runnable {

  private final AtomicBoolean closed = new AtomicBoolean(false);
  private static final Logger logger = LoggerFactory.getLogger(KafkaListener.class);
  private KafkaConsumer<String, String> consumer;
  ConcurrentLinkedQueue<String> queue;

  public KafkaListener(ConcurrentLinkedQueue<String> queue) {
    this.queue = queue;
    Properties props = new Properties();
    props.put("bootstrap.servers", "localhost:9092");
    props.put("group.id", "kilda-listener");
    props.put("enable.auto.commit", "true");
//    props.put("auto.commit.interval.ms", "1000");
    props.put("session.timeout.ms", "30000");
    props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
    props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
    this.consumer = new KafkaConsumer<>(props);
  }

  @Override
  public void run() {
    logger.info("starting a KafkaListener");
    try {
      consumer.subscribe(Arrays.asList("kilda-test"));

      while (!closed.get()) {
        ConsumerRecords<String, String> records = consumer.poll(100);
        for (ConsumerRecord<String, String> record: records) {
          logger.debug("kafka message received:  offset = {}, key = {}, value = {}", new Object[]{record.offset(), record.key(), record.value()});
          queue.add(record.value());
        }
      }
    } catch (WakeupException e) {
      if (!closed.get()) {
        throw e;
      }
    } finally {
      consumer.close();
    }
  }

  public void shutdown() {
    closed.set(true);
    consumer.wakeup();
  }

}
