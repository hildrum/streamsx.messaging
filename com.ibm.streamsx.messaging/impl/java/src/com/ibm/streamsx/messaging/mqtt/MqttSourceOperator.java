/*******************************************************************************
 * Licensed Materials - Property of IBM
 * Copyright IBM Corp. 2014
 * US Government Users Restricted Rights - Use, duplication or
 * disclosure restricted by GSA ADP Schedule Contract with
 * IBM Corp.
 *******************************************************************************/
/* Generated by Streams Studio: 28 February, 2014 3:11:52 PM EST */
package com.ibm.streamsx.messaging.mqtt;


import java.net.URISyntaxException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;

import org.apache.log4j.Logger;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import com.ibm.streams.operator.AbstractOperator;
import com.ibm.streams.operator.OperatorContext;
import com.ibm.streams.operator.OutputTuple;
import com.ibm.streams.operator.StreamSchema;
import com.ibm.streams.operator.StreamingInput;
import com.ibm.streams.operator.StreamingOutput;
import com.ibm.streams.operator.Tuple;
import com.ibm.streams.operator.log4j.TraceLevel;
import com.ibm.streams.operator.model.Libraries;
import com.ibm.streams.operator.model.OutputPortSet;
import com.ibm.streams.operator.model.InputPortSet.WindowMode;
import com.ibm.streams.operator.model.InputPortSet.WindowPunctuationInputMode;
import com.ibm.streams.operator.model.OutputPortSet.WindowPunctuationOutputMode;
import com.ibm.streams.operator.model.InputPortSet;
import com.ibm.streams.operator.model.InputPorts;
import com.ibm.streams.operator.model.OutputPorts;
import com.ibm.streams.operator.model.Parameter;
import com.ibm.streams.operator.model.PrimitiveOperator;
import com.ibm.streams.operator.types.ValueFactory;
import com.ibm.streamsx.messaging.mqtt.MqttClientRequest.MqttClientRequestType;

/**
 * A source operator that does not receive any input streams and produces new tuples. 
 * The method <code>produceTuples</code> is called to begin submitting tuples.
 * <P>
 * For a source operator, the following event methods from the Operator interface can be called:
 * </p>
 * <ul>
 * <li><code>initialize()</code> to perform operator initialization</li>
 * <li>allPortsReady() notification indicates the operator's ports are ready to process and submit tuples</li> 
 * <li>shutdown() to shutdown the operator. A shutdown request may occur at any time, 
 * such as a request to stop a PE or cancel a job. 
 * Thus the shutdown() may occur while the operator is processing tuples, punctuation marks, 
 * or even during port ready notification.</li>
 * </ul>
 * <p>With the exception of operator initialization, all the other events may occur concurrently with each other, 
 * which lead to these methods being called concurrently by different threads.</p> 
 */
@PrimitiveOperator(name="MQTTSource", namespace="com.ibm.streamsx.messaging.mqtt",
description=SPLDocConstants.MQTTSRC_OP_DESCRIPTION)
@InputPorts({@InputPortSet(description=SPLDocConstants.MQTTSRC_INPUT_PORT0, optional=true, windowingMode=WindowMode.NonWindowed, windowPunctuationInputMode=WindowPunctuationInputMode.Oblivious)})
@OutputPorts({@OutputPortSet(description=SPLDocConstants.MQTTSRC_OUPUT_PORT_0, cardinality=1, optional=false, windowPunctuationOutputMode=WindowPunctuationOutputMode.Free), @OutputPortSet(description=SPLDocConstants.MQTTSRC_OUTPUT_PORT_1, optional=true, windowPunctuationOutputMode=WindowPunctuationOutputMode.Free)})
@Libraries(value = {"opt/downloaded/*"})
public class MqttSourceOperator extends AbstractOperator { 
	
	private static Logger TRACE = Logger.getLogger(MqttSourceOperator.class);
	
	// Parameters
	private List<String> topics;
	private int[] qos;
	private String serverUri;
	private String topicOutAttrName;
	
	private int reconnectionBound = IMqttConstants.DEFAULT_RECONNECTION_BOUND;		// default 5, 0 = no retry, -1 = infinite retry
	private long period = IMqttConstants.DEFAULT_RECONNECTION_PERIOD;
	

	private MqttClientWrapper mqttWrapper;
	private boolean shutdown = false;
	
	private ArrayBlockingQueue<MqttMessageRecord> messageQueue;
	private ArrayBlockingQueue<MqttClientRequest> clientRequestQueue;

	/**
	 * Thread for calling <code>produceTuples()</code> to produce tuples 
	 */
    private Thread processThread;
    
    private Thread clientRequestThread;
    
    private class MqttMessageRecord {
    	String topic;
    	MqttMessage message;
    	
    	public MqttMessageRecord(String topic, MqttMessage message) {
    		this.topic = topic;
    		this.message = message;
		}
    }
    
    
    private MqttCallback callback = new MqttCallback() {

		@Override
		public void connectionLost(Throwable cause) {
			scheduleConnectAndSubscribe(getServerUri());
		}

		@Override
		public void messageArrived(String topic, MqttMessage message)
				throws Exception {
			
			MqttMessageRecord record = new MqttMessageRecord(topic, message);
			messageQueue.put(record);					
		}

		@Override
		public void deliveryComplete(IMqttDeliveryToken token) {		
			
		}    	
    	
    };
    

    /**
     * Initialize this operator. Called once before any tuples are processed.
     * @param context OperatorContext for this operator.
     * @throws Exception Operator failure, will cause the enclosing PE to terminate.
     */
    @Override
    public synchronized void initialize(OperatorContext context)
            throws Exception {
    	// Must call super.initialize(context) to correctly setup an operator.
        super.initialize(context);
        Logger.getLogger(this.getClass()).trace("Operator " + context.getName() + " initializing in PE: " + context.getPE().getPEId() + " in Job: " + context.getPE().getJobId() );
        
        messageQueue = new ArrayBlockingQueue<MqttSourceOperator.MqttMessageRecord>(50);
        clientRequestQueue = new ArrayBlockingQueue<MqttClientRequest>(20);
        
        mqttWrapper = new MqttClientWrapper();
        mqttWrapper.setBrokerUri(serverUri);
        
        /*
         * Create the thread for producing tuples. 
         * The thread is created at initialize time but started.
         * The thread will be started by allPortsReady().
         */
        processThread = getOperatorContext().getThreadFactory().newThread(
                new Runnable() {

                    @Override
                    public void run() {
                        try {
                            produceTuples();
                        } catch (Exception e) {
                            Logger.getLogger(this.getClass()).error("Operator error", e);
                        }                    
                    }
                    
                });
        
        /*
         * Set the thread not to be a daemon to ensure that the SPL runtime
         * will wait for the thread to complete before determining the
         * operator is complete.
         */
        processThread.setDaemon(false);
        
        /*
         * Create the thread for producing tuples. 
         * The thread is created at initialize time but started.
         * The thread will be started by allPortsReady().
         */
        clientRequestThread = getOperatorContext().getThreadFactory().newThread(
                new Runnable() {

                    @Override
                    public void run() {                       
                        handleClientRequests();                                  
                    }
                    
                });
        
        /*
         * Set the thread not to be a daemon to ensure that the SPL runtime
         * will wait for the thread to complete before determining the
         * operator is complete.
         */
        clientRequestThread.setDaemon(true);
    }

	protected void handleClientRequests() {
		while (!shutdown)
        {
        	try {
				MqttClientRequest request = clientRequestQueue.take();
				
				if (request.getReqType() == MqttClientRequestType.CONNECT)
				{				
					// only handle the last request
					if (mqttWrapper.getPendingBrokerUri().isEmpty()
							|| !mqttWrapper.getPendingBrokerUri().isEmpty()
							&& mqttWrapper.getPendingBrokerUri().equals(
									request.getServerUri()))
					{
						TRACE.log(TraceLevel.DEBUG, "[Request Queue:] " + IMqttConstants.CONN_SERVERURI + ":" + serverUri); //$NON-NLS-1$ //$NON-NLS-2$

						setServerUri(request.getServerUri());
						mqttWrapper.setBrokerUri(request.getServerUri());
						
						// disconnect and try to connect again.
						// Disconnect is synchronous so wait for that to finish and attempt to connect.
						try {
							mqttWrapper.disconnect();
						} catch (Exception e) {
							// disconnect may fail as the server may have been disconnected
							TRACE.log(TraceLevel.DEBUG, "[Request Queue:] Disconnect exception."); //$NON-NLS-1$ //$NON-NLS-2$
						}					
						connectAndSubscribe();
					}
				}
				
				else if (request.getReqType() == MqttClientRequestType.ADD_TOPICS)
				{
					// add to topic list
					// add subscription, only need to add the specified topic
				}
				else if (request.getReqType() == MqttClientRequestType.REMOVE_TOPICS)
				{
					// remove from topic list
					// unsubscribe the specified topic
				}
				else if (request.getReqType() == MqttClientRequestType.UPDATE_TOPICS) {
					// update qos for topic
					// unsubscribe specified topic
					// subscribe topic with new qos					
				}
				else if (request.getReqType() == MqttClientRequestType.REPLACE_TOPICS)
				{
					// unsubscribe all topics
					// undate topic list and qos list
					// subscribe to new set of topics with correct qos
				}				
			} catch (InterruptedException e) {			
				TRACE.log(TraceLevel.DEBUG, "[Request Queue:] Thread interrupted as expected"); //$NON-NLS-1$ //$NON-NLS-2$
			} catch (URISyntaxException e1) {
				TRACE.log(TraceLevel.DEBUG, "[Request Queue:] URI Syntax Exception",e1); //$NON-NLS-1$ //$NON-NLS-2$
			} catch (MqttException e) {				
				TRACE.log(TraceLevel.DEBUG, "[Request Queue:] MQTT Client Error",e); //$NON-NLS-1$ //$NON-NLS-2$
			} 
        	
        	
        }
		
	}

	private void connectAndSubscribe() throws MqttException, InterruptedException {
		mqttWrapper.connect(getReconnectionBound(), getPeriod());
        mqttWrapper.addCallBack(callback);               
        
        // qos is an optional parameter, set up defaults if it is not specified
        if (qos == null)
        {
        	qos = new int[topics.size()];
        	for (int i = 0; i < qos.length; i++) {
				qos[i] = 0;
			}
        }
        
        mqttWrapper.subscribe((String[])topics.toArray(new String[0]), qos);
	}

    /**
     * Notification that initialization is complete and all input and output ports 
     * are connected and ready to receive and submit tuples.
     * @throws Exception Operator failure, will cause the enclosing PE to terminate.
     */
    @Override
    public synchronized void allPortsReady() throws Exception {
        OperatorContext context = getOperatorContext();
        Logger.getLogger(this.getClass()).trace("Operator " + context.getName() + " all ports are ready in PE: " + context.getPE().getPEId() + " in Job: " + context.getPE().getJobId() );
    	// Start a thread for producing tuples because operator 
    	// implementations must not block and must return control to the caller.
        processThread.start();        
        clientRequestThread.start(); 
        
        // submit and subscribe on background thread, allow operator to start
        scheduleConnectAndSubscribe(getServerUri());        
    }
    
    private void scheduleConnectAndSubscribe(String serverUri) {
    	try {
    		// connect request will automatically take current topics and subscribe
			MqttClientRequest request = new MqttClientRequest().setReqType(MqttClientRequestType.CONNECT).setServerUri(serverUri);
			clientRequestQueue.put(request);
			
		} catch (InterruptedException e) {
		
		}
    }
    
    /**
     * Submit new tuples to the output stream
     * @throws Exception if an error occurs while submitting a tuple
     */
    private void produceTuples() throws Exception  {
        while (!shutdown)
        {
        	MqttMessageRecord record = messageQueue.take();
			byte[] blob = record.message.getPayload();
			
			StreamingOutput<OutputTuple> outputPort = getOutput(0);
			OutputTuple tuple = outputPort.newTuple();
			tuple.setBlob(0, ValueFactory.newBlob(blob));
			
			if (topicOutAttrName != null)
			{
				tuple.setString(topicOutAttrName, record.topic);
			}
			
			outputPort.submit(tuple);
        }
    }
    
    @Override
    public void process(StreamingInput<Tuple> stream, Tuple tuple)
    		throws Exception {
    	handleControlSignal(tuple);
    }
    
	/**
	 * In the handling of control signal of MQTTSource, the following should occur
	 * 1)  read in the entire tuple to see what topic actions need to be taken
	 * 2)  update topic subscription list before handling any connection signal
	 * 3)  handle connection signal
	 * @param tuple
	 */
	private void handleControlSignal(Tuple tuple) {
		// handle control signal to switch server
		try {
			// TODO:  Build a more atomic request on topic updates and connection
			// TODO:  if a connect signal comes in, wake up the waiting thread, clear
			//  all pending connection requests, and submit one
			StreamSchema streamSchema = tuple.getStreamSchema();
			int attributeCount = streamSchema.getAttributeCount();
			
			for(int i=0; i<attributeCount; i++)
			{
				Object object = tuple.getObject(i);
				TRACE.log(TraceLevel.DEBUG, "[Control Port:] object: " + object + " " + object.getClass().getName()); //$NON-NLS-1$ //$NON-NLS-2$

				// if it's a map, it must be the mqttConfig attribute
				if (object instanceof Map)
				{									
					Map map = (Map)object;
					Set keySet = map.keySet();
					for (Iterator iterator = keySet.iterator(); iterator
							.hasNext();) {
						Object key = (Object) iterator.next();
						TRACE.log(TraceLevel.DEBUG, "[Control Port:] " + key + " " + key.getClass()); //$NON-NLS-1$ //$NON-NLS-2$
						
						String keyStr = key.toString();
						
						// case insensitive checks
						if (keyStr.toLowerCase().equals(IMqttConstants.CONN_SERVERURI.toLowerCase()))
						{
							Object serverUri = map.get(key);				
							
							String serverUriStr = serverUri.toString();
							
							// only handle if server URI has changed
							if (!serverUriStr.toLowerCase().equals(getServerUri().toLowerCase()))
							{
								// set pending broker URI field to get wrapper
								// to get out of retry loop
								mqttWrapper.setPendingBrokerUri(serverUriStr);
								
								scheduleConnectAndSubscribe(serverUriStr);
								
								// wake up the thread in case it is sleeping
								clientRequestThread.interrupt();
							}
						}					
					}
				}
				else if (object instanceof List)
				{
					List<Tuple> topicList = (List<Tuple>)object;
					for (Tuple topicDesc : topicList) {
						String action=topicDesc.getString("action");
						List topics = topicDesc.getList("topics");
						int qos = topicDesc.getInt("qos");
					}
					System.out.println(object.toString());
				}
			}
			
			
		} catch (Exception e) {
			TRACE.log(TraceLevel.ERROR, Messages.getString("MqttSinkOperator.21")); //$NON-NLS-1$
		}
	}

    /**
     * Shutdown this operator, which will interrupt the thread
     * executing the <code>produceTuples()</code> method.
     * @throws Exception Operator failure, will cause the enclosing PE to terminate.
     */
    public synchronized void shutdown() throws Exception {
    	
    	shutdown = true;
    	
        if (processThread != null) {
            processThread.interrupt();
            processThread = null;
        }
        OperatorContext context = getOperatorContext();
        Logger.getLogger(this.getClass()).trace("Operator " + context.getName() + " shutting down in PE: " + context.getPE().getPEId() + " in Job: " + context.getPE().getJobId() );
        
        mqttWrapper.disconnect();
        mqttWrapper.shutdown();

        // Must call super.shutdown()
        super.shutdown();
    }
    
    @Parameter(name="topics", description=SPLDocConstants.MQTTSRC_PARAM_TOPICS_DESC, optional=false, cardinality=-1)
	public void setTopics(List<String> topics) {
		this.topics = topics;
	}

    @Parameter(name="qos", description=SPLDocConstants.MQTTSRC_PARAM_QOS_DESC, optional=true, cardinality=-1)
	public void setQos(int[] qos) {
		this.qos = qos;
	}

    @Parameter(name="serverURI", description=SPLDocConstants.MQTTSRC_PARAM_SERVERIURI_DESC, optional=false)
	public void setServerUri(String serverUri) {
		this.serverUri = serverUri;
	}
	
	public List<String> getTopics() {
		return topics;
	}

	public int[] getQos() {
		return qos;
	}

	public String getServerUri() {
		return serverUri;
	}
	
	 @Parameter(name="topicOutAttrName", description=SPLDocConstants.MQTTSRC_PARAM_TOPICATTRNAME_DESC, optional=true)
	public void setTopicOutAttrName(String topicOutAttrName) {
		this.topicOutAttrName = topicOutAttrName;
	}
	
	public String getTopicOutAttrName() {
		return topicOutAttrName;
	}
	
	@Parameter(name="reconnectionBound", description=SPLDocConstants.MQTTSRC_PARAM_RECONN_BOUND_DESC, optional=true)
	public void setReconnectionBound(int reconnectionBound) {
		this.reconnectionBound = reconnectionBound;
	}
	
	@Parameter(name="period", description=SPLDocConstants.MQTTSRC_PARAM_PERIOD_DESC, optional=true)
	public void setPeriod(long period) {
		this.period = period;
	}
	
	public int getReconnectionBound() {
		return reconnectionBound;
	}
	
	public long getPeriod() {
		return period;
	}
}
