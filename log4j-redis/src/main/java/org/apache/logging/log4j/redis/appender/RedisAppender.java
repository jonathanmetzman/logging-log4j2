/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache license, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the license for the specific language governing permissions and
 * limitations under the license.
 */

package org.apache.logging.log4j.redis.appender;

import org.apache.logging.log4j.LoggingException;
import org.apache.logging.log4j.core.*;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.appender.AppenderLoggingException;
import org.apache.logging.log4j.core.config.Node;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginBuilderAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginBuilderFactory;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.validation.constraints.Required;
import org.apache.logging.log4j.core.net.ssl.SslConfiguration;
import org.apache.logging.log4j.spi.AbstractLogger;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Sends log events to a Redis key as a List. All logs are appended to Redis lists via the RPUSH command at keys defined
 * in the configuration.
 */
@Plugin(name = "Redis", category = Node.CATEGORY, elementType = Appender.ELEMENT_TYPE, printObject = true)
public final class RedisAppender extends AbstractAppender {

    // The default port here is the default port for Redis generally.
    // For more details, see the full configuration: http://download.redis.io/redis-stable/redis.conf
    private static final int DEFAULT_REDIS_PORT = 6379;
    private static final String DEFAULT_REDIS_KEYS = "log-events";
    private static final int DEFAULT_APPENDER_QUEUE_CAPACITY = 20;

    private final RedisManager manager;
    private final boolean immediateFlush;
    private final LinkedBlockingQueue<String> logQueue;

    private RedisAppender(final String name, final Layout<? extends Serializable> layout, final Filter filter,
                          final boolean ignoreExceptions, boolean immediateFlush, final int queueCapacity, final RedisManager manager) {
        super(name, filter, layout, ignoreExceptions);
        this.manager = Objects.requireNonNull(manager, "Redis Manager");
        this.immediateFlush = immediateFlush;
        this.logQueue = new LinkedBlockingQueue<>(queueCapacity);
    }

    /**
     * Builds RedisAppender instances.
     * @param <B> The type to build
     */
    public static class Builder<B extends Builder<B>> extends AbstractAppender.Builder<B>
            implements org.apache.logging.log4j.core.util.Builder<RedisAppender> {

        private final String KEY_SEPARATOR = ",";

        @PluginBuilderAttribute("host")
        @Required(message = "No Redis hostname provided")
        private String host;

        @PluginBuilderAttribute("keys")
        private String keys = DEFAULT_REDIS_KEYS;

        @PluginBuilderAttribute("port")
        private int port = DEFAULT_REDIS_PORT;

        @PluginBuilderAttribute("immediateFlush")
        private boolean immediateFlush = true;

        @PluginBuilderAttribute("queueCapacity")
        private int queueCapacity = DEFAULT_APPENDER_QUEUE_CAPACITY;

        @PluginElement("SslConfiguration")
        private SslConfiguration sslConfiguration;

        @PluginElement("RedisPoolConfiguration")
        private LoggingRedisPoolConfiguration poolConfiguration = LoggingRedisPoolConfiguration.defaultConfiguration();

        @SuppressWarnings("resource")
        @Override
        public RedisAppender build() {
            return new RedisAppender(
                    getName(),
                    getLayout(),
                    getFilter(),
                    isIgnoreExceptions(),
                    isImmediateFlush(),
                    getQueueCapacity(),
                    getRedisManager()
            );
        }

        String getKeys() {
            return keys;
        }

        String getHost() {
            return host;
        }

        int getQueueCapacity() {
            return queueCapacity;
        }

        boolean isImmediateFlush() {
            return immediateFlush;
        }

        SslConfiguration getSslConfiguration() {
            return sslConfiguration;
        }

        LoggingRedisPoolConfiguration getPoolConfiguration() {
            return poolConfiguration;
        }

        int getPort() {
            return port;
        }

        public B setKeys(final String keys) {
            this.keys = keys;
            return asBuilder();
        }

        public B setHost(final String host) {
            this.host = host;
            return asBuilder();
        }

        public B setPort(final int port) {
            this.port = port;
            return asBuilder();
        }

        public B setQueueCapacity(final int queueCapacity) {
            this.queueCapacity = queueCapacity;
            return asBuilder();
        }

        public B setPoolConfiguration(final LoggingRedisPoolConfiguration poolConfiguration) {
            this.poolConfiguration = poolConfiguration;
            return asBuilder();
        }

        public B setSslConfiguration(final SslConfiguration ssl) {
            this.sslConfiguration = ssl;
            return asBuilder();
        }

        public B setImmediateFlush(final boolean immediateFlush) {
            this.immediateFlush = immediateFlush;
            return asBuilder();
        }

        RedisManager getRedisManager() {
            return new RedisManager(
                    getConfiguration().getLoggerContext(),
                    getName(),
                    getKeys().split(KEY_SEPARATOR),
                    getHost(),
                    getPort(),
                    getSslConfiguration(),
                    getPoolConfiguration()
            );
        }
    }

    /**
     * Creates a builder for a RedisAppender.
     * @return a builder for a RedisAppender.
     */
    @PluginBuilderFactory
    public static <B extends Builder<B>> B newBuilder() {
        return new Builder<B>().asBuilder();
    }

    @Override
    public void append(final LogEvent event) {
        final Layout<? extends Serializable> layout = getLayout();
        if (event.getLoggerName() != null && AbstractLogger.getRecursionDepth() > 1) {
            LOGGER.warn("Recursive logging from [{}] for appender [{}].", event.getLoggerName(), getName());
        } else if (layout instanceof StringLayout) {
            String serializedEvent = ((StringLayout)layout).toSerializable(event);
            boolean successfulOffer = logQueue.offer(serializedEvent);
            if (shouldFlushLogQueue(event.isEndOfBatch(), successfulOffer)) {
                try {
                    tryFlushQueue();
                } catch (final Exception e) {
                    error("Unable to write to Redis in appender [" + getName() + "]", event, e);
                }
                if (!successfulOffer) {
                    successfulOffer = logQueue.offer(serializedEvent);
                    if (!successfulOffer) {
                        throw new LoggingException(
                                "Unable to add elements to the RedisAppender queue after attempting to clear space."
                                        + " This could occur if your Redis host is no longer running. Consider setting "
                                        + "a larger queueCapacity in your configuration to prevent data losses."
                        );
                    }
                }
            }
        } else {
            throw new AppenderLoggingException("The Redis appender only supports StringLayouts.");
        }
    }

    private boolean shouldFlushLogQueue(boolean endOfBatch, boolean successfulOffer) {
        return immediateFlush || endOfBatch || !successfulOffer;
    }

    private void tryFlushQueue() {
        List<String> logEvents = new ArrayList<>();
        logQueue.drainTo(logEvents);
        manager.sendBulk(logEvents);
    }

    @Override
    public void start() {
        setStarting();
        manager.startup();
        setStarted();
    }

    @Override
    public boolean stop(final long timeout, final TimeUnit timeUnit) {
        setStopping();
        if (logQueue.size() > 0) {
            tryFlushQueue();
        }
        boolean stopped = super.stop(timeout, timeUnit, false);
        stopped &= manager.stop(timeout, timeUnit);
        setStopped();
        return stopped;
    }

    @Override
    public String toString() {
        return "RedisAppender{" +
            "name=" + getName() +
            ", host=" + manager.getHost() +
            ", port=" + manager.getPort() +
            ", keys=" + manager.getKeysAsString() +
            ", immediateFlush=" + this.immediateFlush +
            '}';
    }
}
