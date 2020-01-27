package com.nasdaq.ncdsclient.core;

import com.nasdaq.ncdsclient.listeners.BrokerListener;
import com.nasdaq.ncdsclient.listeners.PlainListener;

import java.util.Properties;

/**
 * Shared code between JUnit4 and JUnit5 shared resources.
 * @param <T> The concrete implementation of this class, to allow for method chaining.
 */
public abstract class AbstractKafkaTestResource<T extends AbstractKafkaTestResource<T>> {
    /**
     * Our internal Kafka Test Server instance.
     */
    private KafkaCluster kafkaCluster = null;

    /**
     * Additional broker properties.
     */
    private final Properties brokerProperties = new Properties();

    /**
     * How many brokers to put into the cluster.
     */
    private int numberOfBrokers = 1;

    /**
     * Defines which listener has been set to be configured on the brokers.
     */
    private BrokerListener registeredListener = new PlainListener();

    /**
     * Default constructor.
     */
    public AbstractKafkaTestResource() {
        this(new Properties());
    }

    /**
     * Constructor allowing passing additional broker properties.
     * @param brokerProperties properties for Kafka broker.
     */
    public AbstractKafkaTestResource(final Properties brokerProperties) {
        this.brokerProperties.putAll(brokerProperties);
    }

    /**
     * Helper to allow overriding Kafka broker properties.  Can only be called prior to the service
     * being started.
     * @param name Kafka broker configuration property name.
     * @param value Value to set for the configuration property.
     * @return SharedKafkaTestResource instance for method chaining.
     * @throws IllegalArgumentException if name argument is null.
     * @throws IllegalStateException if method called after service has started.
     */
    @SuppressWarnings("unchecked")
    public T withBrokerProperty(final String name, final String value) {
        // Validate state.
        validateState(false, "Cannot add properties after service has started.");

        // Validate input.
        if (name == null) {
            throw new IllegalArgumentException("Cannot pass null name argument");
        }

        // Add or set property.
        if (value == null) {
            brokerProperties.remove(name);
        } else {
            brokerProperties.put(name, value);
        }
        return (T) this;
    }

    /**
     * Set how many brokers to include in the test cluster.
     * @param brokerCount The number of brokers.
     * @return SharedKafkaTestResource for method chaining.
     */
    @SuppressWarnings("unchecked")
    public T withBrokers(final int brokerCount) {
        // Validate state.
        validateState(false, "Cannot set brokers after service has started.");

        if (brokerCount < 1) {
            throw new IllegalArgumentException("Cannot have 0 or fewer brokers");
        }
        this.numberOfBrokers = brokerCount;
        return (T) this;
    }

    /**
     * Register additional listeners on the kafka brokers.
     * @param listener listener instance to register.
     * @return SharedKafkaTestResource for method chaining.
     */
    @SuppressWarnings("unchecked")
    public T registerListener(final BrokerListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Listener argument may not be null.");
        }
        registeredListener = listener;
        return (T) this;
    }

    /**
     * KafkaTestUtils is a collection of re-usable/common access patterns for interacting with the Kafka cluster.
     * @return Instance of KafkaTestUtils configured to operate on the Kafka cluster.
     */
    public KafkaTestUtils getKafkaTestUtils() {
        // Validate internal state.
        validateState(true, "Cannot access KafkaTestUtils before Kafka service has been started.");
        return new KafkaTestUtils(kafkaCluster);
    }

    /**
     * Returns connection string for zookeeper clients.
     * @return Connection string to connect to the Zookeeper instance.
     */
    public String getZookeeperConnectString() {
        validateState(true, "Cannot access Zookeeper before service has been started.");
        return kafkaCluster.getZookeeperConnectString();
    }

    /**
     * bootstrap.servers string to configure Kafka consumers or producers to access the Kafka cluster.
     * @return Connect string to use for Kafka clients.
     */
    public String getKafkaConnectString() {
        validateState(true, "Cannot access Kafka before service has been started.");
        return kafkaCluster.getKafkaConnectString();
    }

    /**
     * Returns an immutable list of broker hosts for the kafka cluster.
     * @return immutable list of hosts for brokers within the cluster.
     */
    public KafkaBrokers getKafkaBrokers() {
        validateState(true, "Cannot access Kafka before service has been started.");
        return kafkaCluster.getKafkaBrokers();
    }

    /**
     * Returns all registered listener.
     * @return The configured listener.
     */
    protected BrokerListener getRegisteredListener() {
        return registeredListener;
    }

    protected KafkaCluster getKafkaCluster() {
        return kafkaCluster;
    }

    protected void setKafkaCluster(final KafkaCluster kafkaCluster) {
        this.kafkaCluster = kafkaCluster;
    }

    protected Properties getBrokerProperties() {
        return brokerProperties;
    }

    protected int getNumberOfBrokers() {
        return numberOfBrokers;
    }

    /**
     * Helper method for ensure state consistency.
     * @param shouldKafkaExistYet True if KafkaCluster should exist, false if it should not.
     * @param errorMessage Error message to throw if the state is not consistent.
     * @throws IllegalStateException if the kafkaCluster state is not consistent.
     */
    protected void validateState(final boolean shouldKafkaExistYet, final String errorMessage) throws IllegalStateException {
        if (shouldKafkaExistYet && kafkaCluster == null) {
            throw new IllegalStateException(errorMessage);
        } else if (!shouldKafkaExistYet && kafkaCluster != null) {
            throw new IllegalStateException(errorMessage);
        }
    }
}