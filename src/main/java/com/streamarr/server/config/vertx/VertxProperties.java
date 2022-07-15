package com.streamarr.server.config.vertx;

import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.dns.AddressResolverOptions;
import io.vertx.core.file.FileSystemOptions;
import io.vertx.core.metrics.MetricsOptions;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Getter
@Setter
@ConfigurationProperties(prefix = VertxProperties.PROPERTIES_PREFIX)
public class VertxProperties {

    static final String PROPERTIES_PREFIX = "vertx";

    /**
     * A number of event loop threads to be used by the Vert.x instance.
     * <p>
     * Default: 2 * available processors.
     *
     * @see VertxOptions#getEventLoopPoolSize()
     */
    private int eventLoopPoolSize = VertxOptions.DEFAULT_EVENT_LOOP_POOL_SIZE;

    /**
     * A maximum number of worker threads to be used by the Vert.x instance.
     * Worker threads are used for running blocking code and worker verticles.
     * <p>
     * Default: 20
     *
     * @see VertxOptions#getWorkerPoolSize()
     */
    private int workerPoolSize = VertxOptions.DEFAULT_WORKER_POOL_SIZE;

    /**
     * An internal blocking pool size.
     * Vert.x maintains a pool for internal blocking operations
     * <p>
     * Default: 20
     *
     * @see VertxOptions#getWorkerPoolSize()
     */
    private int internalBlockingPoolSize = VertxOptions.DEFAULT_INTERNAL_BLOCKING_POOL_SIZE;

    /**
     * A blocked thread check period, in {@link VertxProperties#blockedThreadCheckIntervalUnit}.
     * This setting determines how often Vert.x will check whether event loop threads are executing for too long.
     * <p>
     * Default: 1000ms
     *
     * @see VertxOptions#getBlockedThreadCheckInterval()
     */
    private long blockedThreadCheckInterval = VertxOptions.DEFAULT_BLOCKED_THREAD_CHECK_INTERVAL;

    /**
     * Get the value of max event loop execute time, in {@link VertxProperties#maxEventLoopExecuteTimeUnit}.
     * Vert.x will automatically log a warning if it detects that event loop threads haven't returned within this time.
     * This can be used to detect where the user is blocking an event loop thread, contrary to the Golden Rule of the
     * holy Event Loop.
     * <p>
     * Default: 2000000000ns (2s)
     *
     * @see VertxOptions#getMaxEventLoopExecuteTime()
     */
    private long maxEventLoopExecuteTime = VertxOptions.DEFAULT_MAX_EVENT_LOOP_EXECUTE_TIME;

    /**
     * Get the value of max worker execute time, in {@link VertxProperties#maxWorkerExecuteTimeUnit}.
     * Vert.x will automatically log a warning if it detects that worker threads haven't returned within this time.
     * This can be used to detect where the user is blocking a worker thread for too long. Although worker threads
     * can be blocked longer than event loop threads, they shouldn't be blocked for long periods of time.
     * <p>
     * Default: 60000000000ns (60s)
     *
     * @see VertxOptions#getMaxWorkerExecuteTime()
     */
    private long maxWorkerExecuteTime = VertxOptions.DEFAULT_MAX_WORKER_EXECUTE_TIME;

    /**
     * Whether HA is enabled on the Vert.x instance.
     * <p>
     * Default: false
     *
     * @see VertxOptions#isHAEnabled()
     */
    private boolean haEnabled = VertxOptions.DEFAULT_HA_ENABLED;

    /**
     * A quorum size to be used when HA is enabled.
     * <p>
     * Default: 1
     *
     * @see VertxOptions#getQuorumSize()
     */
    private int quorumSize = VertxOptions.DEFAULT_QUORUM_SIZE;

    /**
     * An HA group to be used when HA is enabled.
     * <p>
     * Default: __DEFAULT__
     *
     * @see VertxOptions#getHAGroup()
     */
    private String haGroup = VertxOptions.DEFAULT_HA_GROUP;

    /**
     * A threshold value in {@link VertxProperties#warningExceptionTimeUnit} above which a blocked warning contains a stack trace.
     * <p>
     * Default: 5000000000ns (5s)
     *
     * @see VertxOptions#getWarningExceptionTime()
     */
    private long warningExceptionTime = TimeUnit.SECONDS.toNanos(5);

    /**
     * Whether to prefer the native transport to the JDK transport.
     * <p>
     * Default: false
     *
     * @see VertxOptions#getPreferNativeTransport()
     */
    private boolean preferNativeTransport = VertxOptions.DEFAULT_PREFER_NATIVE_TRANSPORT;

    /**
     * A time unit of {@link VertxProperties#maxEventLoopExecuteTime}.
     * <p>
     * Default: ns
     *
     * @see VertxOptions#getMaxEventLoopExecuteTimeUnit()
     */
    private TimeUnit maxEventLoopExecuteTimeUnit = VertxOptions.DEFAULT_MAX_EVENT_LOOP_EXECUTE_TIME_UNIT;

    /**
     * A time unit of {@link VertxProperties#maxWorkerExecuteTime}.
     * <p>
     * Default: ns
     *
     * @see VertxOptions#getMaxWorkerExecuteTimeUnit()
     */
    private TimeUnit maxWorkerExecuteTimeUnit = VertxOptions.DEFAULT_MAX_WORKER_EXECUTE_TIME_UNIT;

    /**
     * A time unit of {@link VertxProperties#warningExceptionTime}.
     * <p>
     * Default: ns
     *
     * @see VertxOptions#getWarningExceptionTimeUnit()
     */
    private TimeUnit warningExceptionTimeUnit = VertxOptions.DEFAULT_WARNING_EXCEPTION_TIME_UNIT;

    /**
     * A time unit of {@link VertxProperties#blockedThreadCheckInterval}.
     * <p>
     * Default: ms
     *
     * @see VertxOptions#getBlockedThreadCheckIntervalUnit()
     */
    private TimeUnit blockedThreadCheckIntervalUnit = VertxOptions.DEFAULT_BLOCKED_THREAD_CHECK_INTERVAL_UNIT;

    /**
     * Whether metrics are enabled on the Vert.x instance.
     * <p>
     * Default: false
     *
     * @see MetricsOptions#isEnabled()
     */
    private boolean metricsEnabled = MetricsOptions.DEFAULT_METRICS_ENABLED;

    private final FileSystem fileSystem = new FileSystem();

    private final AddressResolver addressResolver = new AddressResolver();

    public VertxOptions toVertxOptions() {
        VertxOptions vertxOptions = new VertxOptions();
        vertxOptions.setEventLoopPoolSize(eventLoopPoolSize);
        vertxOptions.setWorkerPoolSize(workerPoolSize);
        vertxOptions.setInternalBlockingPoolSize(internalBlockingPoolSize);
        vertxOptions.setBlockedThreadCheckInterval(blockedThreadCheckInterval);
        vertxOptions.setMaxEventLoopExecuteTime(maxEventLoopExecuteTime);
        vertxOptions.setMaxWorkerExecuteTime(maxWorkerExecuteTime);
        vertxOptions.setHAEnabled(haEnabled);
        vertxOptions.setQuorumSize(quorumSize);
        vertxOptions.setHAGroup(haGroup);
        vertxOptions.setWarningExceptionTime(warningExceptionTime);
        vertxOptions.setPreferNativeTransport(preferNativeTransport);
        vertxOptions.setMaxEventLoopExecuteTimeUnit(maxEventLoopExecuteTimeUnit);
        vertxOptions.setMaxWorkerExecuteTimeUnit(maxWorkerExecuteTimeUnit);
        vertxOptions.setWarningExceptionTimeUnit(warningExceptionTimeUnit);
        vertxOptions.setBlockedThreadCheckIntervalUnit(blockedThreadCheckIntervalUnit);

        MetricsOptions metricsOptions = new MetricsOptions();
        metricsOptions.setEnabled(metricsEnabled);
        vertxOptions.setMetricsOptions(metricsOptions);

        FileSystemOptions fileSystemOptions = new FileSystemOptions();
        fileSystemOptions.setClassPathResolvingEnabled(fileSystem.isClassPathResolvingEnabled());
        fileSystemOptions.setFileCachingEnabled(fileSystem.isFileCachingEnabled());
        vertxOptions.setFileSystemOptions(fileSystemOptions);

        AddressResolverOptions addressResolverOptions = new AddressResolverOptions();
        addressResolverOptions.setHostsPath(addressResolver.getHostsPath());
        addressResolverOptions.setHostsValue(addressResolver.getHostsValue());
        addressResolverOptions.setServers(addressResolver.getServers());
        addressResolverOptions.setOptResourceEnabled(addressResolver.isOptResourceEnabled());
        addressResolverOptions.setCacheMinTimeToLive(addressResolver.getCacheMinTimeToLive());
        addressResolverOptions.setCacheMaxTimeToLive(addressResolver.getCacheMaxTimeToLive());
        addressResolverOptions.setCacheNegativeTimeToLive(addressResolver.getCacheNegativeTimeToLive());
        addressResolverOptions.setQueryTimeout(addressResolver.getQueryTimeout());
        addressResolverOptions.setMaxQueries(addressResolver.getMaxQueries());
        addressResolverOptions.setRdFlag(addressResolver.isRdFlag());
        addressResolverOptions.setSearchDomains(addressResolver.getSearchDomains());
        addressResolverOptions.setNdots(addressResolver.getNdots());
        addressResolverOptions.setRotateServers(addressResolver.isRotateServers());
        vertxOptions.setAddressResolverOptions(addressResolverOptions);

        return vertxOptions;
    }

    @Getter
    @Setter
    public static class FileSystem {

        /**
         * Whether classpath resolving is enabled.
         * <p>
         * Default: {@link FileSystemOptions#DEFAULT_CLASS_PATH_RESOLVING_ENABLED}
         *
         * @see FileSystemOptions#isClassPathResolvingEnabled()
         */
        private boolean classPathResolvingEnabled = FileSystemOptions.DEFAULT_CLASS_PATH_RESOLVING_ENABLED;

        /**
         * Whether file caching is enabled for class path resolving.
         * <p>
         * Default: {@link FileSystemOptions#DEFAULT_FILE_CACHING_ENABLED}
         *
         * @see FileSystemOptions#isFileCachingEnabled()
         */
        private boolean fileCachingEnabled = FileSystemOptions.DEFAULT_FILE_CACHING_ENABLED;
    }

    @Getter
    @Setter
    public static class AddressResolver {

        /**
         * A path to the alternate host's configuration file.
         *
         * @see AddressResolverOptions#getHostsPath()
         */
        private String hostsPath;

        /**
         * A hosts configuration file value.
         *
         * @see AddressResolverOptions#getHostsValue()
         */
        private Buffer hostsValue;

        /**
         * A list of dns servers.
         * <p>
         * Default: null
         *
         * @see AddressResolverOptions#getServers()
         */
        private List<String> servers = AddressResolverOptions.DEFAULT_SERVERS;

        /**
         * Whether an optional record is automatically included in DNS queries.
         * <p>
         * Default: true
         *
         * @see AddressResolverOptions#isOptResourceEnabled()
         */
        private boolean optResourceEnabled = AddressResolverOptions.DEFAULT_OPT_RESOURCE_ENABLED;

        /**
         * A cache min TTL in seconds.
         * <p>
         * Default: 0
         *
         * @see AddressResolverOptions#getCacheMinTimeToLive()
         */
        private int cacheMinTimeToLive = AddressResolverOptions.DEFAULT_CACHE_MIN_TIME_TO_LIVE;

        /**
         * A cache max TTL in seconds.
         * <p>
         * Default: Integer.MAX_VALUE
         *
         * @see AddressResolverOptions#getCacheMaxTimeToLive()
         */
        private int cacheMaxTimeToLive = AddressResolverOptions.DEFAULT_CACHE_MAX_TIME_TO_LIVE;

        /**
         * A cache negative TTL in seconds.
         * <p>
         * Default: 0
         *
         * @see AddressResolverOptions#getCacheNegativeTimeToLive()
         */
        private int cacheNegativeTimeToLive = AddressResolverOptions.DEFAULT_CACHE_NEGATIVE_TIME_TO_LIVE;

        /**
         * A query timeout in milliseconds.
         * <p>
         * Default: 5000
         *
         * @see AddressResolverOptions#getQueryTimeout()
         */
        private long queryTimeout = AddressResolverOptions.DEFAULT_QUERY_TIMEOUT;

        /**
         * A maximum number of queries to be sent during a resolution.
         * <p>
         * Default: 4
         *
         * @see AddressResolverOptions#getMaxQueries()
         */
        private int maxQueries = AddressResolverOptions.DEFAULT_MAX_QUERIES;

        /**
         * A DNS queries <i>Recursion Desired</i> flag value.
         * <p>
         * Default: true
         *
         * @see AddressResolverOptions#getRdFlag()
         */
        private boolean rdFlag = AddressResolverOptions.DEFAULT_RD_FLAG;

        /**
         * A list of search domains.
         * <p>
         * Default: null
         *
         * @see AddressResolverOptions#getSearchDomains()
         */
        private List<String> searchDomains = AddressResolverOptions.DEFAULT_SEACH_DOMAINS;

        /**
         * An ndots value
         * <p>
         * Default: {@link AddressResolverOptions#DEFAULT_NDOTS}
         *
         * @see AddressResolverOptions#getNdots()
         */
        private int ndots = AddressResolverOptions.DEFAULT_NDOTS;

        /**
         * Whether a dns server selection uses round robin.
         * <p>
         * Default: {@link AddressResolverOptions#DEFAULT_ROTATE_SERVERS}
         *
         * @see AddressResolverOptions#isRotateServers()
         */
        private boolean rotateServers = AddressResolverOptions.DEFAULT_ROTATE_SERVERS;
    }
}
