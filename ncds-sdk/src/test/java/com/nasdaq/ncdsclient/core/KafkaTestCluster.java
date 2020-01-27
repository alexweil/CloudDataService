package com.nasdaq.ncdsclient.core;

import com.nasdaq.ncdsclient.listeners.BrokerListener;
import com.nasdaq.ncdsclient.listeners.PlainListener;
import org.apache.kafka.common.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toList;

/**
 * Utility for setting up a Cluster of KafkaTestServers.
 */
public class KafkaTestCluster implements KafkaCluster, KafkaProvider, AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(KafkaTestCluster.class);

    /**
     * Clock instance.
     */
    private final Clock clock = Clock.systemUTC();

    /**
     * Internal Test Zookeeper service shared by all Kafka brokers.
     */
    private final ZookeeperTestServer zkTestServer = new ZookeeperTestServer();

    /**
     * Defines how many brokers will be created and started within the cluster.
     */
    private final int numberOfBrokers;

    /**
     * Defines overridden broker properties.
     */
    private final Properties overrideBrokerProperties = new Properties();

    /**
     * Collection of listeners that get registered with the broker.
     */
    private final List<BrokerListener> registeredListeners;

    /**
     * List containing all of the brokers in the cluster.  Since each broker is quickly accessible via it's 'brokerId' property
     * by simply removing 1 from it's id and using it as the index into the list.
     *
     * Example: to get brokerId = 4, retrieve index 3 in this list.
     */
    private final List<KafkaTestServer> brokers = new ArrayList<>();

    /**
     * Constructor.
     * @param numberOfBrokers How many brokers you want in your Kafka cluster.
     */
    public KafkaTestCluster(final int numberOfBrokers) {
        this(numberOfBrokers, new Properties(), Collections.emptyList());
    }

    /**
     * Constructor.
     * @param numberOfBrokers How many brokers you want in your Kafka cluster.
     * @param overrideBrokerProperties Define Kafka broker properties.
     */
    public KafkaTestCluster(final int numberOfBrokers, final Properties overrideBrokerProperties) {
        this(numberOfBrokers, overrideBrokerProperties, Collections.emptyList());
    }

    /**
     * Constructor.
     * @param numberOfBrokers How many brokers you want in your Kafka cluster.
     * @param overrideBrokerProperties Define Kafka broker properties.
     * @param listeners List of listeners to register on each broker.
     */
    public KafkaTestCluster(
            final int numberOfBrokers,
            final Properties overrideBrokerProperties,
            final Collection<BrokerListener> listeners
    ) {
        if (numberOfBrokers <= 0) {
            throw new IllegalArgumentException("numberOfBrokers argument must be 1 or larger.");
        }
        if (overrideBrokerProperties == null) {
            throw new IllegalArgumentException("overrideBrokerProperties argument must not be null.");
        }

        final List<BrokerListener> brokerListeners = new ArrayList<>();
        if (listeners == null || listeners.isEmpty()) {
            // If we have no listeners defined, use default plain listener.
            brokerListeners.add(new PlainListener());
        } else {
            brokerListeners.addAll(listeners);
        }

        // Save references.
        this.numberOfBrokers = numberOfBrokers;
        this.overrideBrokerProperties.putAll(overrideBrokerProperties);
        this.registeredListeners = Collections.unmodifiableList(brokerListeners);
    }

    /**
     * Starts the cluster.
     * @throws Exception on startup errors.
     * @throws TimeoutException When the cluster fails to start up within a timely manner.
     */
    public void start() throws Exception, TimeoutException {
        // Ensure zookeeper instance has been started.
        zkTestServer.start();

        // If we have no brokers defined yet...
        if (brokers.isEmpty()) {
            // Loop over brokers, starting with brokerId 1.
            for (int brokerId = 1; brokerId <= numberOfBrokers; brokerId++) {
                // Create properties for brokers
                final Properties brokerProperties = new Properties();

                // Add user defined properties.
                brokerProperties.putAll(overrideBrokerProperties);

                // Set broker.id
                brokerProperties.put("broker.id", String.valueOf(brokerId));

                // Create new KafkaTestServer and add to our broker list
                brokers.add(
                        new KafkaTestServer(brokerProperties, zkTestServer, registeredListeners)
                );
            }
        }

        // Loop over each broker and start it
        for (final KafkaTestServer broker : brokers) {
            broker.start();
        }

        // Block until the cluster is 'up' or the timeout is exceeded.
        waitUntilClusterReady(10_000L);
    }

    /**
     * Returns an immutable list of broker hosts for the kafka cluster.
     * @return immutable list of hosts for brokers within the cluster.
     */
    @Override
    public KafkaBrokers getKafkaBrokers() {
        // If we have no brokers yet, the cluster has not yet started.
        if (brokers.isEmpty()) {
            throw new IllegalStateException("Cannot access brokers before cluster has been started.");
        }

        return new KafkaBrokers(
                brokers
                        .stream()
                        .flatMap((kafkaTestServer) -> (kafkaTestServer.getKafkaBrokers().stream()))
                        .collect(toList())
        );
    }

    /**
     * Retrieve a broker by its brokerId.
     * @param brokerId the Id of the broker to retrieve.
     * @return KafkaTestServer instance for the given broker Id.
     */
    public KafkaBroker getKafkaBrokerById(final int brokerId) {
        // If we have no brokers yet, the cluster has not yet started.
        if (brokers.isEmpty()) {
            throw new IllegalStateException("Cannot access brokers before cluster has been started.");
        }

        // Find the requested broker.
        final Optional<KafkaTestServer> kafkaTestServer = brokers
                .stream()
                .filter((testServer) -> testServer.getBrokerId() == brokerId)
                .findFirst();

        // If we found a match
        if (kafkaTestServer.isPresent()) {
            // Return it!
            return new KafkaBroker(kafkaTestServer.get());
        }
        // Otherwise toss an IllegalArgument exception.
        throw new IllegalArgumentException("Broker with id " + brokerId + " does not exist.");
    }

    /**
     * bootstrap.servers string to configure Kafka consumers or producers to access the Kafka cluster.
     * @return Connect string to use for Kafka clients.
     */
    public String getKafkaConnectString() {
        // If we have no brokers yet, the cluster has not yet started.
        if (brokers.isEmpty()) {
            throw new IllegalStateException("Cannot access brokers before cluster has been started.");
        }

        return brokers
                .stream()
                .map((KafkaTestServer::getKafkaConnectString))
                .collect(Collectors.joining(","));
    }

    @Override
    public List<ListenerProperties> getListenerProperties() {
        // Collect all the properties from all brokers in the cluster.
        final List<ListenerProperties> listenerProperties = new ArrayList<>();
        brokers.forEach((broker) -> {
            listenerProperties.addAll(broker.getListenerProperties());
        });
        return Collections.unmodifiableList(listenerProperties);
    }

    /**
     * Returns connection string for zookeeper clients.
     * @return Connection string to connect to the Zookeeper instance.
     */
    public String getZookeeperConnectString() {
        return zkTestServer.getConnectString();
    }

    /**
     * Shuts the cluster down.
     * @throws Exception on shutdown errors.
     */
    public void stop() throws Exception {
        // Loop over brokers
        for (final KafkaTestServer kafkaBroker : brokers) {
            kafkaBroker.stop();
        }

        // Stop zkServer
        zkTestServer.stop();
    }

    /**
     * Alias for stop().
     * @throws Exception on shutdown errors.
     */
    @Override
    public void close() throws Exception {
        stop();
    }

    /**
     * This method will block up to timeoutMs milliseconds waiting for the cluster to become available and ready.
     * @param timeoutMs How long to block for, in milliseconds.
     * @throws TimeoutException if the timeout period is exceeded.
     */
    private void waitUntilClusterReady(final long timeoutMs) throws TimeoutException {
        // Get AdminClient for cluster.
        final KafkaTestUtils kafkaTestUtils = new KafkaTestUtils(this);

        // Start looping.
        final long startTime = clock.millis();
        int numberOfBrokersReady = 0;
        do {
            try {
                // Ask for the nodes in the cluster.
                final Collection<Node> nodes = kafkaTestUtils.describeClusterNodes();

                // We should know how many nodes there are
                if (nodes.size() >= numberOfBrokers) {
                    // Looks like the cluster is ready to go.
                    logger.info("Found {} brokers on-line, cluster is ready.", nodes.size());
                    return;
                }

                // Log when brokers found on-line changes.
                if (nodes.size() > numberOfBrokersReady) {
                    numberOfBrokersReady = nodes.size();
                    logger.info(
                            "Found {} of {} brokers ready, continuing to wait for cluster to start.",
                            numberOfBrokersReady,
                            nodes.size()
                    );
                }

                // Small wait to throttle cycling.
                Thread.sleep(100);
            } catch (final InterruptedException exception) {
                // Caught interrupt, break out of loop.
                break;
            }
        }
        while (clock.millis() <= startTime + timeoutMs);

        // If we got here, throw timeout exception
        throw new TimeoutException("Cluster failed to come online within " + timeoutMs + " milliseconds.");
    }
}