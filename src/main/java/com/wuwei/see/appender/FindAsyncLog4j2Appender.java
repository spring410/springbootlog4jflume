package com.wuwei.see.appender;


import org.apache.flume.Event;
import org.apache.flume.FlumeException;
import org.apache.flume.api.RpcClient;
import org.apache.flume.api.RpcClientConfigurationConstants;
import org.apache.flume.api.RpcClientFactory;
import org.apache.flume.event.EventBuilder;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.core.util.Log4jThread;

import java.io.Serializable;
import java.nio.charset.Charset;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;


@Plugin(name = "FindAsync", category = "Core", elementType = "appender")
public class FindAsyncLog4j2Appender extends AbstractAppender {

    private static final int  DEFAULT_MAX_IO_WORKERS = 2;
    private int CONNECT_INTERVAL_RPC = 60; //连接重试间隔 秒

    private static final Event SHUTDOWN = EventBuilder.withBody("", Charset.forName("UTF8"));

    private static final AtomicLong THREAD_SEQUENCE = new AtomicLong(1);
    private static RpcClient rpcClient = null;
    private AsyncThread thread;

    private BlockingQueue<Event> queue;
    private BlockingQueue<Event> cacheQueue;
    private int queueSize;
    private boolean blocking;
    private long shutdownTimeout;
    private String hosts;
    private String application;
    private Layout layout;
    private long timeout;
    private Long lastConnectTime;


    private boolean init = false;

    private FindAsyncLog4j2Appender(final String name, final Filter filter, final Layout<? extends Serializable> layout,
                                    final int queueSize, final boolean blocking, final long shutdownTimeout, final String hosts,
                                    final long timeout, String application) {
        super(name, filter, layout);

        this.shutdownTimeout = shutdownTimeout;

        this.layout = layout;
        this.timeout = timeout;
        this.hosts = hosts;
        this.application = application;

        this.queueSize = queueSize;
        this.blocking = blocking;

        connect();

    }


    private boolean isInit(){
        return init;
    }

    private void connect(){

        Properties props = new Properties();

        if(isSingleSink(hosts)) {
            props.setProperty(RpcClientConfigurationConstants.CONFIG_HOSTS, "h1");
            props.setProperty(RpcClientConfigurationConstants.CONFIG_HOSTS_PREFIX + "h1", hosts);
            props.setProperty(RpcClientConfigurationConstants.CONFIG_CONNECT_TIMEOUT, String.valueOf(timeout));
            props.setProperty(RpcClientConfigurationConstants.CONFIG_REQUEST_TIMEOUT, String.valueOf(timeout));
        } else {
            props = getProperties(hosts, timeout);
        }

        try {
            rpcClient = RpcClientFactory.getInstance(props);
            if(!isStarted()){
                start();
            }
        } catch (FlumeException e) {
            String errormsg = "RPC client creation failed! " + e.getMessage();
            LOGGER.error(errormsg);
            throw e;
        }
    }

    @Override
    public void start() {
        if(!isInit()){
            this.queue = new ArrayBlockingQueue<>(queueSize);
            this.cacheQueue = new ArrayBlockingQueue<>(queueSize * 5);
            thread = new AsyncThread();
            thread.setName("FindAsyncLog4j2Appender-" + getName());

            thread.start();
            super.start();
            init = true;
        } else {
            LOGGER.warn("It has inited.");
        }
    }

    @Override
    public void stop() {
        super.stop();
        if(!init){
            return;
        }
        LOGGER.trace("AsyncAppender stopping. Queue still has {} events.", queue.size());
        thread.shutdown();
        try {
            thread.join(shutdownTimeout);
        } catch (final InterruptedException ex) {
            LOGGER.warn("Interrupted while stopping AsyncAppender {}", getName());
        }
        LOGGER.trace("AsyncAppender stopped. Queue has {} events.", queue.size());

        if (rpcClient != null) {
            try {
                rpcClient.close();
            } catch (FlumeException ex) {
                LOGGER.error("Error while trying to close RpcClient.", ex);

                throw ex;
            } finally {
                rpcClient = null;
            }
        } else {
            String errorMsg = "Flume log4jappender already closed!";
            LOGGER.error(errorMsg);

            throw new FlumeException(errorMsg);
        }
    }

    /**
     * Actual writing occurs here.
     *
     * @param logEvent The LogEvent.
     */
    @Override
    public void append(LogEvent logEvent) {
        if(!isInit()){
            return;
        }
        if (!isStarted()) {
            throw new FlumeException("AsyncAppender " + getName() + " is not active");
        }
        if(rpcClient == null || !rpcClient.isActive()){
            reconnect();
        }

        Event flumeEvent = createEvent(logEvent);
        if(flumeEvent == null){
            return;
        }

        if (rpcClient == null || !rpcClient.isActive()) {
            String errorMsg = "Cannot Append to Appender! please check " + hosts;
            LOGGER.error(errorMsg);
            addCacheQuene(flumeEvent);

            throw new FlumeException(errorMsg);
        }

        if(queue.offer(flumeEvent)){
            if(cacheQueue.size() > 0){
                thread.callAppenders(cacheQueue);
            }
        } else {
            if (blocking) {
                addCacheQuene(flumeEvent);
                if(cacheQueue.size() >= queueSize){
                    thread.callAppenders(cacheQueue);
                }
            } else {
                LOGGER.warn("Appender is unable to write primary appenders. queue is full");
            }
        }
    }


    /**
     * 这里将发送不成功的消息，放到缓存中去, 满则丢弃之前的.
     */
    private void addCacheQuene(Event flumeEvent){
        try {
            cacheQueue.add(flumeEvent);
        } catch (IllegalStateException e) {
            LOGGER.warn("cacheQueue is full");
            boolean success = thread.callAppenders(cacheQueue);
            if(!success){
                LOGGER.warn("drop first cache quene");
                cacheQueue.poll();
            }
            cacheQueue.add(flumeEvent);
        }
    }

    private Event createEvent(LogEvent logEvent){
        Map<String, String> hdrs = new HashMap<>();
        StackTraceElement source = logEvent.getSource();
        if(source == null){
            return null;
        }
        hdrs.put("class", logEvent.getLoggerName());
        hdrs.put("method", source.getMethodName());
        hdrs.put("time", dateFormat(logEvent.getTimeMillis()));

        hdrs.put("host", "TODO");

        hdrs.put("application", application);
        hdrs.put("level", String.valueOf(logEvent.getLevel().toString()));


        Event flumeEvent;
        String message = new String(layout.toByteArray(logEvent));
        flumeEvent = EventBuilder.withBody(message, Charset.forName("UTF8"), hdrs);

        return flumeEvent;
    }

    private void reconnect(){
        if(!isInit()){
            if(rpcClient != null){
                rpcClient.close();
            }
            connect();
            return;
        }
        if(lastConnectTime == null || System.currentTimeMillis() - lastConnectTime >= CONNECT_INTERVAL_RPC *1000){
            lastConnectTime = System.currentTimeMillis();
            if(rpcClient != null){
                rpcClient.close();
            }
            connect();
        }
    }



    /**
     * Create an AsyncAppender.
     *
     * @param blocking         True if the Appender should wait when the queue is full. The default is true.
     * @param shutdownTimeout  How many milliseconds the Appender should wait to flush outstanding log events
     *                         in the queue on shutdown. The default is zero which means to wait forever.
     * @param size             The size of the event queue. The default is 128.
     * @param name             The name of the Appender.
     * @param filter           The Filter or null.
     * @return The AsyncAppender.
     */
    @PluginFactory
    public static FindAsyncLog4j2Appender createAppender(
            @PluginAttribute(value = "blocking", defaultBoolean = true) boolean blocking,
            @PluginAttribute(value = "shutdownTimeout") long shutdownTimeout,
            @PluginAttribute(value = "bufferSize") int size,
            @PluginAttribute("name") final String name,
            @PluginAttribute("hosts") String hosts,
            @PluginAttribute(value = "timeout") Long timeout,
            @PluginElement("Filter") Filter filter,
            @PluginAttribute("application") String application,

            @PluginElement("Layout") Layout<? extends Serializable> layout) {

        if (name == null ) {
            LOGGER.error("No name provided for FindAsyncLog4j2Appender");
            return null;
        }
        if (hosts == null ) {
            LOGGER.error("No host provided for FindAsyncLog4j2Appender");
            return null;
        }

        if (application == null) {
            LOGGER.error("No application provided for FindAsyncLog4j2Appender");
            return null;
        }

        if (layout == null) {
            layout = PatternLayout.createDefaultLayout();
        }

        return new FindAsyncLog4j2Appender(name, filter, layout, size, blocking,
                shutdownTimeout, hosts, timeout, application);
    }

    private Properties getProperties(String hosts, long timeout) throws FlumeException {
        if (null== hosts || hosts.isEmpty()) {
            throw new FlumeException("hosts must not be null");
        }
        Properties props = new Properties();
        String[] hostsAndPorts = getHosts(hosts);
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < hostsAndPorts.length; i++) {
            String hostAndPort = hostsAndPorts[i];
            String name = "h" + i;
            props.setProperty(RpcClientConfigurationConstants.CONFIG_HOSTS_PREFIX + name, hostAndPort);
            names.append(name).append(" ");
        }
        props.put(RpcClientConfigurationConstants.CONFIG_HOSTS, names.toString());
        props.put(RpcClientConfigurationConstants.CONFIG_CLIENT_TYPE, RpcClientFactory.ClientType.DEFAULT_LOADBALANCE.toString());

        props.setProperty(RpcClientConfigurationConstants.CONFIG_CONNECT_TIMEOUT, String.valueOf(timeout));
        props.setProperty(RpcClientConfigurationConstants.CONFIG_REQUEST_TIMEOUT, String.valueOf(timeout));
        props.setProperty(RpcClientConfigurationConstants.MAX_IO_WORKERS, String.valueOf(DEFAULT_MAX_IO_WORKERS));
        return props;
    }

    private boolean isSingleSink(String hosts) {
        String[] hostsAndPorts = getHosts(hosts);
        if(hostsAndPorts.length == 1) {
            return true;
        }
        return false;
    }

    private String[] getHosts(String hosts){
        if(hosts.contains(",")){
            return hosts.split(",");
        } else {
            return hosts.split("\\s+");
        }
    }

    private static DateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS") ;
    public static String dateFormat(long time) {

        Calendar calendar = Calendar.getInstance();
        calendar.setTimeZone(TimeZone.getTimeZone("UTC"));
        calendar.setTimeInMillis(time);
        return df.format(new Date(time));
    }


    /**
     * Thread that calls the Appenders.
     */
    private class AsyncThread extends Log4jThread {

        private volatile boolean shutdown = false;

        public AsyncThread() {
            setDaemon(true);
            setName("FindAsyncLog4j2Appender" + THREAD_SEQUENCE.getAndIncrement());
        }

        @Override
        public void run() {
            while (!shutdown) {
                Event event;
                try {
                    int size = queue.size();
                    if(size > queueSize / 2){
                        callAppenders(queue);
                    } else {
                        event = queue.take();
                        if (event == SHUTDOWN) {
                            shutdown = true;
                            continue;
                        }
                        callAppenders(event);
                    }

                } catch (final InterruptedException ex) {
                    break; // LOG4J2-830
                }
            }
            // Process any remaining items in the queue.
            LOGGER.trace("AsyncAppender.AsyncThread shutting down. Processing remaining {} queue events.",
                    queue.size());
            int count = 0;
            int error = 0;
            while (!queue.isEmpty()) {
                try {
                    final Event event = queue.take();
                    callAppenders(event);
                    count ++;
                } catch (final InterruptedException ex) {
                    error ++;
                    // May have been interrupted to shut down.
                    // Here we ignore interrupts and try to process all remaining events.
                }
            }
            LOGGER.trace("AsyncAppender.AsyncThread stopped. Queue has {} events remaining. "
                    + "Processed {} and error {} events since shutdown started.", queue.size(), count, error);
        }


        boolean callAppenders(final Event event) {
            boolean success = false;
            try {
                rpcClient.append(event);
                success = true;
            } catch (Exception e) {
                String msg = "Flume append() failed." + e.getMessage();
                LOGGER.error(msg);
            }
            return success;
        }

        boolean callAppenders(final BlockingQueue<Event> eventQueue) {
            boolean success = false;
            try {
                int size = eventQueue.size();
                List<Event> eventList = new ArrayList<>(size);
                Event event;
                for(int i = 0;i<size;i++){
                    event = eventQueue.poll();
                    if(event != null){
                        eventList.add(event);
                    }
                }
                rpcClient.appendBatch(eventList);
                success = true;
            } catch (Exception e) {
                String msg = "Flume append() failed." + e.getMessage();
                LOGGER.error(msg);
            }
            return success;
        }

        public void shutdown() {
            shutdown = true;
            if (queue.isEmpty()) {
                queue.offer(SHUTDOWN);
            }
        }


    }
}
