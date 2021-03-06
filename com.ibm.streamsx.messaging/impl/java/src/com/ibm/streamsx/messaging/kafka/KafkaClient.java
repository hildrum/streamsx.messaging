/*******************************************************************************
 * Copyright (C) 2014, International Business Machines Corporation
 * All Rights Reserved
 *******************************************************************************/

package com.ibm.streamsx.messaging.kafka;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ThreadFactory;
import java.util.logging.Logger;

import kafka.consumer.ConsumerConfig;
import kafka.consumer.KafkaStream;
import kafka.javaapi.consumer.ConsumerConnector;
import kafka.javaapi.producer.Producer;
import kafka.message.MessageAndMetadata;
import kafka.producer.KeyedMessage;
import kafka.producer.ProducerConfig;

import com.ibm.streams.operator.OutputTuple;
import com.ibm.streams.operator.StreamingOutput;
import com.ibm.streams.operator.Tuple;
import com.ibm.streams.operator.logging.TraceLevel;

class KafkaClient {
	
	
	static final Charset CS = Charset.forName("UTF-8");
	
	private AttributeHelper topicAH = null, keyAH = null, messageAH = null;
	private StreamingOutput<OutputTuple> streamingOutput = null;
	
	private  ConsumerConnector consumer;
	private boolean shutdown  = false;
	private Properties finalProperties = null;
	private boolean isInit = false, isConsumer = false;
	
	private AProducerHelper producer = null;
	

	static final Logger trace = Logger.getLogger(KafkaClient.class.getCanonicalName());
	
	public KafkaClient(AttributeHelper topicAH, AttributeHelper keyAH, AttributeHelper 	messageAH, Properties props) {
		this.topicAH = topicAH;
		this.keyAH = keyAH;
		this.messageAH =  messageAH;
		this.finalProperties = props;
	}
	
	private synchronized void checkInit(boolean isCons) {
		if(isInit)
			throw new RuntimeException("Client has already been initialized. Cannot initialized again");
		isConsumer = isCons;
		isInit = true;
	}
	
	//producer related methods
	public void initProducer() throws Exception {
		checkInit(false);
		
		trace.log(TraceLevel.INFO, "Initializing Kafka Producer: " + finalProperties);
		ProducerConfig config = new ProducerConfig(finalProperties);
		
		if(messageAH.isString())
			producer = new ProducerStringHelper();
		else
			producer = new ProducerByteHelper();
		
		producer.init(config, keyAH, messageAH);
	}
	
	public void send(Tuple tuple, List<String> topics) throws Exception {
		if(isConsumer)
			throw new RuntimeException("This object has not been initialized as a producer");
		
		if(trace.isLoggable(TraceLevel.DEBUG))
			trace.log(TraceLevel.DEBUG, "Sending Tuple To Kafka: " + tuple +", Topics: " + topics);
		
		producer.send(tuple, topics);

	}
	
	public void send(Tuple tuple) throws Exception {
		if(isConsumer)
			throw new RuntimeException("This object has not been initialized as a producer");
		
		if(trace.isLoggable(TraceLevel.DEBUG))
			trace.log(TraceLevel.DEBUG, "Sending Tuple To Kafka: " + tuple);
		
		producer.send(tuple,  topicAH);
	}
	
	
	//Consumer related methods
	
	public void initConsumer(
			StreamingOutput<OutputTuple> so,
			ThreadFactory tf, List<String> topics, int threadsPerTopic) {
		checkInit(true);
		
		this.streamingOutput = so;
		trace.log(TraceLevel.INFO, "Initializing Kafka consumer: " + finalProperties.toString());
		consumer = kafka.consumer.Consumer.createJavaConsumerConnector( new ConsumerConfig(finalProperties) );
		Map<String, Integer> topicCountMap = new HashMap<String, Integer>();
		for(String topic : topics) {
			topicCountMap.put(topic, threadsPerTopic);        
		}
		int threadNumber = 0;
		for(String topic : topics) {
			Map<String, List<KafkaStream<byte[], byte[]>>> consumerMap = consumer.createMessageStreams(topicCountMap);
			List<KafkaStream<byte[], byte[]>> streams = consumerMap.get(topic);        // now launch all the threads
			for (KafkaStream<byte[], byte[]> stream : streams) {
				trace.log(TraceLevel.INFO, "Starting thread [" + threadNumber + "] for topic: " + topic);
				Thread th = tf.newThread((new KafkaConsumer(topic, stream, this, threadNumber++)));
				th.setDaemon(false);
				th.start();
			}
		}		
	}
	
	public void shutdown() {
		shutdown = true;
		if(isConsumer) {
			consumer.shutdown();
		}
	}

	public boolean isShutdown() {
		return shutdown;
	}
	
	private void newMessage(String topic, MessageAndMetadata<byte[], byte[]> msg) throws Exception {
		if(shutdown) return;
		if(trace.isLoggable(TraceLevel.DEBUG))
			trace.log(TraceLevel.DEBUG, "Topic: " + topic + ", Message: " + msg );
		OutputTuple otup = streamingOutput.newTuple();
		if(topicAH.isAvailable())
			topicAH.setValue(otup, topic);
		if(keyAH.isAvailable())
			keyAH.setValue(otup, msg.key());
		messageAH.setValue(otup, msg.message());
		streamingOutput.submit(otup);
	}

	
	/**
	 * Kafka Consumer Thread
	 *
	 */
	private static class KafkaConsumer implements Runnable {
		private String topic = null;
		private KafkaStream<byte[], byte[]> kafkaStream;
		private KafkaClient client;
		private String baseMsg = null;
		private static final Logger logger = Logger.getLogger(KafkaConsumer.class.getName());

		public KafkaConsumer(String topic, KafkaStream<byte[], byte[]> kafkaStream, KafkaClient kSource, int threadNumber) {
			this.topic = topic;
			this.kafkaStream = kafkaStream;
			this.client = kSource;
			baseMsg = " Topic[" + topic + "] Thread[" + threadNumber +"] ";
		}

		public void run() {
			logger.log(TraceLevel.INFO,baseMsg + "Thread Started");
			for(MessageAndMetadata<byte[], byte[]> ret : kafkaStream) {
				if(client.isShutdown()) break;
				if(logger.isLoggable(TraceLevel.DEBUG))
					logger.log(TraceLevel.DEBUG, baseMsg + "New Message");
				try {
					client.newMessage(topic, ret);
				} catch (Exception e) {
					if(logger.isLoggable(TraceLevel.ERROR))
						logger.log(TraceLevel.ERROR, baseMsg + "Could not send message", e);
				}
			}
			logger.log(TraceLevel.INFO,baseMsg + "Thread Stopping");
		}
	}
}

abstract class AProducerHelper {
	AttributeHelper keyAH=null, messageAH = null;

	abstract void init(ProducerConfig config, AttributeHelper keyAH, AttributeHelper messageAH) throws Exception;
	
	abstract void send(Tuple tuple, AttributeHelper topicAH)  throws Exception;
	
	abstract void send(Tuple tuple,  List<String> topics)  throws Exception;
	
} 

class ProducerByteHelper extends AProducerHelper {
	private Producer<byte[],byte[]> producer = null;
	
	@Override
	void init(ProducerConfig config, AttributeHelper keyAH, AttributeHelper messageAH) {
		producer = new Producer<byte[], byte[]>(config);
		this.keyAH = keyAH;
		this.messageAH = messageAH;
	}
	
	
	@Override
	void send(Tuple tuple, AttributeHelper topicAH) throws Exception {
		byte[] data = messageAH.getBytes(tuple);
		byte[] key = data;
		if(keyAH.isAvailable()) {
			key=keyAH.getBytes(tuple);
		}

		String topic = topicAH.getString(tuple); 	
		KeyedMessage<byte[], byte[]> keyedMessage = 
				new KeyedMessage<byte[], byte[]>(topic,key, data);
		producer.send(keyedMessage);
	}
	
	@Override
	void send(Tuple tuple, List<String> topics) throws Exception {
		byte[] data = messageAH.getBytes(tuple);
		byte[] key = data;
		if(keyAH.isAvailable()) {
			key=keyAH.getBytes(tuple);
		}
		List<KeyedMessage<byte[], byte[]> > lst = 
				new ArrayList<KeyedMessage<byte[],byte[]>>(topics.size());
		for(String topic : topics) {
			lst.add(new KeyedMessage<byte[], byte[]>(topic, key, data));
		}
		producer.send(lst);
	}	
} 

class ProducerStringHelper extends AProducerHelper {
	private Producer<String, String> producer = null;

	@Override
	void init(ProducerConfig config, AttributeHelper keyAH,
			AttributeHelper messageAH) {
		producer = new Producer<String, String>(config);
		this.keyAH = keyAH;
		this.messageAH = messageAH;
	}

	@Override
	void send(Tuple tuple, AttributeHelper topicAH) throws Exception {
		String data = messageAH.getString(tuple);
		String key = data;
		if(keyAH.isAvailable()) {
			key=keyAH.getString(tuple);
		}

		String topic = topicAH.getString(tuple); 	
		KeyedMessage<String, String> keyedMessage = 
				new KeyedMessage<String, String>(topic,key, data);
		producer.send(keyedMessage);
	}

	@Override
	void send(Tuple tuple, List<String> topics) throws Exception {
		String data = messageAH.getString(tuple);
		String key = data;
		if(keyAH.isAvailable()) {
			key=keyAH.getString(tuple);
		}
		List<KeyedMessage<String, String> > lst = 
				new ArrayList<KeyedMessage<String,String>>(topics.size());
		for(String topic : topics) {
			lst.add(new KeyedMessage<String, String>(topic, key, data));
		}
		producer.send(lst);
		
	}
}
