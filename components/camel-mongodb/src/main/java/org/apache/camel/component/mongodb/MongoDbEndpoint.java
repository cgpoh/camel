/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.mongodb;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.apache.camel.Category;
import org.apache.camel.Consumer;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
import org.apache.camel.spi.Metadata;
import org.apache.camel.spi.UriEndpoint;
import org.apache.camel.spi.UriParam;
import org.apache.camel.spi.UriPath;
import org.apache.camel.support.CamelContextHelper;
import org.apache.camel.support.DefaultEndpoint;
import org.apache.camel.util.ObjectHelper;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.camel.component.mongodb.MongoDbOperation.command;
import static org.apache.camel.component.mongodb.MongoDbOperation.findAll;
import static org.apache.camel.component.mongodb.MongoDbOperation.getDbStats;
import static org.apache.camel.component.mongodb.MongoDbOperation.valueOf;
import static org.apache.camel.component.mongodb.MongoDbOutputType.Document;
import static org.apache.camel.component.mongodb.MongoDbOutputType.DocumentList;
import static org.apache.camel.component.mongodb.MongoDbOutputType.MongoIterable;

/**
 * Perform operations on MongoDB documents and collections.
 */
@UriEndpoint(firstVersion = "2.19.0", scheme = "mongodb", title = "MongoDB", syntax = "mongodb:connectionBean",
             category = { Category.DATABASE, Category.NOSQL })
public class MongoDbEndpoint extends DefaultEndpoint {

    private static final Logger LOG = LoggerFactory.getLogger(MongoDbEndpoint.class);

    @UriParam(description = "Sets the connection bean used as a client for connecting to a database.")
    private MongoClient mongoConnection;

    @UriPath(description = "Sets the connection bean reference used to lookup a client for connecting to a database if no hosts parameter is present.")
    @Metadata(required = true)
    private String connectionBean;

    @UriParam(label = "security", secret = true)
    private String username;
    @UriParam(label = "security", secret = true)
    private String password;
    @UriParam
    private String hosts;
    @UriParam
    private String database;
    @UriParam
    private String collection;
    @UriParam
    private String collectionIndex;
    @UriParam
    private MongoDbOperation operation;
    @UriParam(defaultValue = "true")
    private boolean createCollection = true;
    @UriParam(label = "advanced")
    private boolean dynamicity;
    @UriParam(label = "advanced", defaultValue = "ACKNOWLEDGED",
              enums = "ACKNOWLEDGED,W1,W2,W3,UNACKNOWLEDGED,JOURNALED,MAJORITY")
    private String writeConcern = "ACKNOWLEDGED";
    @UriParam(label = "advanced",
              defaultValue = "PRIMARY",
              enums = "PRIMARY,PRIMARY_PREFERRED,SECONDARY,SECONDARY_PREFERRED,NEAREST")
    private String readPreference = "PRIMARY";
    @UriParam(label = "advanced")
    private boolean writeResultAsHeader;
    @UriParam(label = "consumer")
    private String consumerType;
    @UriParam(label = "advanced", defaultValue = "1000", javaType = "java.time.Duration")
    private long cursorRegenerationDelay = 1000L;
    @UriParam(label = "consumer,tail")
    private String tailTrackIncreasingField;
    @UriParam(label = "consumer,changeStream")
    private String streamFilter;
    // persistent tail tracking
    @UriParam(label = "consumer,tail")
    private boolean persistentTailTracking;
    @UriParam(label = "consumer,tail")
    private String persistentId;
    @UriParam(label = "consumer,tail")
    private String tailTrackDb;
    @UriParam(label = "consumer,tail")
    private String tailTrackCollection;
    @UriParam(label = "consumer,tail")
    private String tailTrackField;
    @UriParam(label = "common")
    private MongoDbOutputType outputType;

    // tailable cursor consumer by default
    private MongoDbConsumerType dbConsumerType;

    private MongoDbTailTrackingConfig tailTrackingConfig;

    private MongoDatabase mongoDatabase;
    private MongoCollection<Document> mongoCollection;

    public MongoDbEndpoint() {
    }

    public MongoDbEndpoint(String uri, MongoDbComponent component) {
        super(uri, component);
    }

    @Override
    public Producer createProducer() {
        validateProducerOptions();
        initializeConnection();
        return new MongoDbProducer(this);
    }

    @Override
    public Consumer createConsumer(Processor processor) throws Exception {
        validateConsumerOptions();

        // we never create the collection
        createCollection = false;
        initializeConnection();

        // select right consumer type
        try {
            dbConsumerType = ObjectHelper.isEmpty(consumerType)
                    ? MongoDbConsumerType.tailable
                    : MongoDbConsumerType.valueOf(consumerType);
        } catch (Exception e) {
            throw new CamelMongoDbException("Consumer type not supported: " + consumerType, e);
        }

        Consumer consumer;

        switch (dbConsumerType) {
            case tailable:
                consumer = new MongoDbTailableCursorConsumer(this, processor);
                break;
            case changeStreams:
                consumer = new MongoDbChangeStreamsConsumer(this, processor);
                break;
            default:
                throw new CamelMongoDbException("Consumer type not supported: " + dbConsumerType);
        }

        configureConsumer(consumer);
        return consumer;
    }

    /**
     * Check if outputType is compatible with operation. DbCursor and DocumentList applies to findAll. Document applies
     * to others.
     */
    @SuppressWarnings("unused")
    // TODO: validate Output on createProducer method.
    private void validateOutputType() {
        if (!ObjectHelper.isEmpty(outputType)) {
            if (DocumentList.equals(outputType) && !(findAll.equals(operation))) {
                throw new IllegalArgumentException("outputType DocumentList is only compatible with operation findAll");
            }
            if (MongoIterable.equals(outputType) && !(findAll.equals(operation))) {
                throw new IllegalArgumentException("outputType MongoIterable is only compatible with operation findAll");
            }
            if (Document.equals(outputType) && (findAll.equals(operation))) {
                throw new IllegalArgumentException("outputType Document is not compatible with operation findAll");
            }
        }
    }

    private void validateProducerOptions() throws IllegalArgumentException {
        // make our best effort to validate, options with defaults are checked
        // against their defaults, which is not always a guarantee that
        // they haven't been explicitly set, but it is enough
        if (!ObjectHelper.isEmpty(dbConsumerType) || persistentTailTracking || !ObjectHelper.isEmpty(tailTrackDb)
                || !ObjectHelper.isEmpty(tailTrackCollection)
                || !ObjectHelper.isEmpty(tailTrackField) || cursorRegenerationDelay != 1000L) {
            throw new IllegalArgumentException(
                    "dbConsumerType, tailTracking, cursorRegenerationDelay options cannot appear on a producer endpoint");
        }
    }

    private void validateConsumerOptions() throws IllegalArgumentException {
        // make our best effort to validate, options with defaults are checked
        // against their defaults, which is not always a guarantee that
        // they haven't been explicitly set, but it is enough
        if (!ObjectHelper.isEmpty(operation) || dynamicity || outputType != null) {
            throw new IllegalArgumentException(
                    "operation, dynamicity, outputType " + "options cannot appear on a consumer endpoint");
        }
        if (dbConsumerType == MongoDbConsumerType.tailable) {
            if (tailTrackIncreasingField == null) {
                throw new IllegalArgumentException(
                        "tailTrackIncreasingField option must be set for tailable cursor MongoDB consumer endpoint");
            }
            if (persistentTailTracking && (ObjectHelper.isEmpty(persistentId))) {
                throw new IllegalArgumentException("persistentId is compulsory for persistent tail tracking");
            }
        }
    }

    /**
     * Initialises the MongoDB connection using the Mongo object provided to the endpoint
     *
     * @throws CamelMongoDbException
     */
    public void initializeConnection() throws CamelMongoDbException {
        LOG.info("Initialising MongoDb endpoint: {}", this);
        if (database == null || collection == null && !(getDbStats.equals(operation) || command.equals(operation))) {
            throw new CamelMongoDbException("Missing required endpoint configuration: database and/or collection");
        }

        if (mongoConnection == null) {
            mongoConnection = resolveMongoConnection();
            if (mongoConnection == null) {
                throw new CamelMongoDbException(
                        "Could not initialise MongoDbComponent. Could not resolve the mongo connection.");
            }
        }

        mongoDatabase = mongoConnection.getDatabase(database);
        if (mongoDatabase == null) {
            throw new CamelMongoDbException("Could not initialise MongoDbComponent. Database " + database + " does not exist.");
        }
        if (collection != null) {
            if (!createCollection && !databaseContainsCollection(collection)) {
                throw new CamelMongoDbException(
                        "Could not initialise MongoDbComponent. Collection "
                                                + collection
                                                + " does not exist on the database and createCollection is false.");
            }
            mongoCollection = mongoDatabase.getCollection(collection, Document.class);

            LOG.debug("MongoDb component initialised and endpoint bound to MongoDB collection with the following parameters. "
                      + "Cluster description: {}, Db: {}, Collection: {}",
                    mongoConnection.getClusterDescription(), mongoDatabase.getName(), collection);

            try {
                if (ObjectHelper.isNotEmpty(collectionIndex)) {
                    ensureIndex(mongoCollection, createIndex());
                }
            } catch (Exception e) {
                throw new CamelMongoDbException("Error creating index", e);
            }
        }
    }

    private boolean databaseContainsCollection(String collectionName) {
        return StreamSupport.stream(mongoDatabase.listCollectionNames().spliterator(), false).anyMatch(collectionName::equals);
    }

    /**
     * Add Index
     *
     * @param aCollection
     */
    public void ensureIndex(MongoCollection<Document> aCollection, List<Bson> dynamicIndex) {
        if (dynamicIndex != null && !dynamicIndex.isEmpty()) {
            for (Bson index : dynamicIndex) {
                LOG.debug("create Document Index {}", index);
                aCollection.createIndex(index);
            }
        }
    }

    /**
     * Create technical list index
     *
     * @return technical list index
     */
    @SuppressWarnings("unchecked")
    public List<Bson> createIndex() {
        try {
            List<Bson> indexList = new ArrayList<>();

            if (ObjectHelper.isNotEmpty(collectionIndex)) {
                HashMap<String, String> indexMap = new ObjectMapper().readValue(collectionIndex, HashMap.class);

                for (Map.Entry<String, String> set : indexMap.entrySet()) {
                    Document index = new Document();
                    // MongoDB 2.4 upwards is restrictive about the type of the
                    // 'single field index' being
                    // in use below (set.getValue())) as only an integer value
                    // type is accepted, otherwise
                    // server will throw an exception, see more details:
                    // http://docs.mongodb.org/manual/release-notes/2.4/#improved-validation-of-index-types
                    index.put(set.getKey(), set.getValue());

                    indexList.add(index);
                }
            }
            return indexList;
        } catch (IOException e) {
            throw new CamelMongoDbException("createIndex failed", e);
        }
    }

    @Override
    protected void doStart() throws Exception {
        if (mongoConnection == null) {
            mongoConnection = resolveMongoConnection();
        } else {
            LOG.debug("Resolved the connection provided by mongoConnection property parameter as {}", mongoConnection);
        }
        super.doStart();
    }

    private MongoClient resolveMongoConnection() {
        MongoClient mongoClient;
        if (this.hosts != null) {
            String credentials = username == null ? "" : username;

            if (!credentials.equals("")) {
                credentials += this.password == null ? "@" : ":" + password + "@";
            }

            mongoClient = MongoClients.create(String.format("mongodb://%s%s", credentials, hosts));
            LOG.debug("Connection created using provided credentials");
        } else {
            mongoClient = CamelContextHelper.mandatoryLookup(getCamelContext(), connectionBean, MongoClient.class);
            LOG.debug("Resolved the connection provided by {} context reference as {}", connectionBean,
                    mongoConnection);
        }

        return mongoClient;
    }

    public String getConnectionBean() {
        return connectionBean;
    }

    /**
     * Name of {@link com.mongodb.client.MongoClient} to use.
     */
    public void setConnectionBean(String connectionBean) {
        this.connectionBean = connectionBean;
    }

    /**
     * Sets the name of the MongoDB collection to bind to this endpoint
     *
     * @param collection collection name
     */
    public void setCollection(String collection) {
        this.collection = collection;
    }

    public String getCollection() {
        return collection;
    }

    /**
     * Sets the collection index (JSON FORMAT : { "field1" : order1, "field2" : order2})
     */
    public void setCollectionIndex(String collectionIndex) {
        this.collectionIndex = collectionIndex;
    }

    public String getCollectionIndex() {
        return collectionIndex;
    }

    /**
     * Sets the operation this endpoint will execute against MongoDB.
     */
    public void setOperation(String operation) throws CamelMongoDbException {
        try {
            this.operation = valueOf(operation);
        } catch (IllegalArgumentException e) {
            throw new CamelMongoDbException("Operation not supported", e);
        }
    }

    /**
     * Sets the operation this endpoint will execute against MongoDB.
     */
    public void setOperation(MongoDbOperation operation) {
        this.operation = operation;
    }

    public MongoDbOperation getOperation() {
        return operation;
    }

    /**
     * Sets the name of the MongoDB database to target
     *
     * @param database name of the MongoDB database
     */
    public void setDatabase(String database) {
        this.database = database;
    }

    public String getDatabase() {
        return database;
    }

    /**
     * Create collection during initialisation if it doesn't exist. Default is true.
     *
     * @param createCollection true or false
     */
    public void setCreateCollection(boolean createCollection) {
        this.createCollection = createCollection;
    }

    public boolean isCreateCollection() {
        return createCollection;
    }

    /**
     * Sets the Mongo instance that represents the backing connection
     *
     * @param mongoConnection the connection to the database
     */
    public void setMongoConnection(MongoClient mongoConnection) {
        this.mongoConnection = mongoConnection;
    }

    public MongoClient getMongoConnection() {
        return mongoConnection;
    }

    /**
     * Sets whether this endpoint will attempt to dynamically resolve the target database and collection from the
     * incoming Exchange properties. Can be used to override at runtime the database and collection specified on the
     * otherwise static endpoint URI. It is disabled by default to boost performance. Enabling it will take a minimal
     * performance hit.
     *
     * @param dynamicity true or false indicated whether target database and collection should be calculated dynamically
     *                   based on Exchange properties.
     * @see              MongoDbConstants#DATABASE
     * @see              MongoDbConstants#COLLECTION
     */
    public void setDynamicity(boolean dynamicity) {
        this.dynamicity = dynamicity;
    }

    public boolean isDynamicity() {
        return dynamicity;
    }

    /**
     * Reserved for future use, when more consumer types are supported.
     *
     * @param  dbConsumerType        key of the consumer type
     * @throws CamelMongoDbException if consumer type is not supported
     */
    public void setDbConsumerType(String dbConsumerType) throws CamelMongoDbException {
        try {
            this.dbConsumerType = MongoDbConsumerType.valueOf(dbConsumerType);
        } catch (IllegalArgumentException e) {
            throw new CamelMongoDbException("Consumer type not supported", e);
        }
    }

    public MongoDbConsumerType getDbConsumerType() {
        return dbConsumerType;
    }

    public String getConsumerType() {
        return consumerType;
    }

    /**
     * Consumer type.
     */
    public void setConsumerType(String consumerType) {
        this.consumerType = consumerType;
    }

    public String getTailTrackDb() {
        return tailTrackDb;
    }

    /**
     * Indicates what database the tail tracking mechanism will persist to. If not specified, the current database will
     * be picked by default. Dynamicity will not be taken into account even if enabled, i.e. the tail tracking database
     * will not vary past endpoint initialisation.
     *
     * @param tailTrackDb database name
     */
    public void setTailTrackDb(String tailTrackDb) {
        this.tailTrackDb = tailTrackDb;
    }

    public String getTailTrackCollection() {
        return tailTrackCollection;
    }

    /**
     * Collection where tail tracking information will be persisted. If not specified,
     * {@link MongoDbTailTrackingConfig#DEFAULT_COLLECTION} will be used by default.
     *
     * @param tailTrackCollection collection name
     */
    public void setTailTrackCollection(String tailTrackCollection) {
        this.tailTrackCollection = tailTrackCollection;
    }

    public String getTailTrackField() {
        return tailTrackField;
    }

    /**
     * Field where the last tracked value will be placed. If not specified,
     * {@link MongoDbTailTrackingConfig#DEFAULT_FIELD} will be used by default.
     *
     * @param tailTrackField field name
     */
    public void setTailTrackField(String tailTrackField) {
        this.tailTrackField = tailTrackField;
    }

    /**
     * Enable persistent tail tracking, which is a mechanism to keep track of the last consumed message across system
     * restarts. The next time the system is up, the endpoint will recover the cursor from the point where it last
     * stopped slurping records.
     *
     * @param persistentTailTracking true or false
     */
    public void setPersistentTailTracking(boolean persistentTailTracking) {
        this.persistentTailTracking = persistentTailTracking;
    }

    public boolean isPersistentTailTracking() {
        return persistentTailTracking;
    }

    /**
     * Correlation field in the incoming record which is of increasing nature and will be used to position the tailing
     * cursor every time it is generated. The cursor will be (re)created with a query of type: tailTrackIncreasingField
     * greater than lastValue (possibly recovered from persistent tail tracking). Can be of type Integer, Date, String,
     * etc. NOTE: No support for dot notation at the current time, so the field should be at the top level of the
     * document.
     *
     * @param tailTrackIncreasingField
     */
    public void setTailTrackIncreasingField(String tailTrackIncreasingField) {
        this.tailTrackIncreasingField = tailTrackIncreasingField;
    }

    public String getTailTrackIncreasingField() {
        return tailTrackIncreasingField;
    }

    public MongoDbTailTrackingConfig getTailTrackingConfig() {
        if (tailTrackingConfig == null) {
            tailTrackingConfig = new MongoDbTailTrackingConfig(
                    persistentTailTracking, tailTrackIncreasingField, tailTrackDb == null ? database : tailTrackDb,
                    tailTrackCollection,
                    tailTrackField, getPersistentId());
        }
        return tailTrackingConfig;
    }

    /**
     * MongoDB tailable cursors will block until new data arrives. If no new data is inserted, after some time the
     * cursor will be automatically freed and closed by the MongoDB server. The client is expected to regenerate the
     * cursor if needed. This value specifies the time to wait before attempting to fetch a new cursor, and if the
     * attempt fails, how long before the next attempt is made. Default value is 1000ms.
     *
     * @param cursorRegenerationDelay delay specified in milliseconds
     */
    public void setCursorRegenerationDelay(long cursorRegenerationDelay) {
        this.cursorRegenerationDelay = cursorRegenerationDelay;
    }

    public long getCursorRegenerationDelay() {
        return cursorRegenerationDelay;
    }

    /**
     * One tail tracking collection can host many trackers for several tailable consumers. To keep them separate, each
     * tracker should have its own unique persistentId.
     *
     * @param persistentId the value of the persistent ID to use for this tailable consumer
     */
    public void setPersistentId(String persistentId) {
        this.persistentId = persistentId;
    }

    public String getPersistentId() {
        return persistentId;
    }

    public boolean isWriteResultAsHeader() {
        return writeResultAsHeader;
    }

    /**
     * In write operations, it determines whether instead of returning WriteResult as the body of the OUT message, we
     * transfer the IN message to the OUT and attach the WriteResult as a header.
     *
     * @param writeResultAsHeader flag to indicate if this option is enabled
     */
    public void setWriteResultAsHeader(boolean writeResultAsHeader) {
        this.writeResultAsHeader = writeResultAsHeader;
    }

    public MongoDbOutputType getOutputType() {
        return outputType;
    }

    /**
     * Convert the output of the producer to the selected type : DocumentList Document or MongoIterable. DocumentList or
     * MongoIterable applies to findAll and aggregate. Document applies to all other operations.
     *
     * @param outputType
     */
    public void setOutputType(MongoDbOutputType outputType) {
        this.outputType = outputType;
    }

    public MongoDatabase getMongoDatabase() {
        return mongoDatabase;
    }

    public MongoCollection<Document> getMongoCollection() {
        return mongoCollection;
    }

    public String getStreamFilter() {
        return streamFilter;
    }

    /**
     * Filter condition for change streams consumer.
     */
    public void setStreamFilter(String streamFilter) {
        this.streamFilter = streamFilter;
    }

    /**
     * Configure the connection bean with the level of acknowledgment requested from MongoDB for write operations to a
     * standalone mongod, replicaset or cluster. Possible values are ACKNOWLEDGED, W1, W2, W3, UNACKNOWLEDGED, JOURNALED
     * or MAJORITY.
     *
     * @param writeConcern
     */
    public void setWriteConcern(String writeConcern) {
        this.writeConcern = writeConcern;
    }

    public String getWriteConcern() {
        return this.writeConcern;
    }

    public WriteConcern getWriteConcernBean() {
        WriteConcern writeConcernBean = WriteConcern.valueOf(getWriteConcern());
        if (writeConcernBean == null) {
            throw new IllegalArgumentException(String.format("Unknown WriteConcern configuration %s", getWriteConcern()));
        }
        return writeConcernBean;
    }

    /**
     * Configure how MongoDB clients route read operations to the members of a replica set. Possible values are PRIMARY,
     * PRIMARY_PREFERRED, SECONDARY, SECONDARY_PREFERRED or NEAREST
     *
     * @param readPreference
     */
    public void setReadPreference(String readPreference) {
        this.readPreference = readPreference;
    }

    public String getReadPreference() {
        return this.readPreference;
    }

    public ReadPreference getReadPreferenceBean() {
        // will throw an IllegalArgumentException if the input is incorrect
        return ReadPreference.valueOf(getReadPreference());
    }

    public String getUsername() {
        return username;
    }

    /**
     * Username for mongodb connection
     *
     * @param username
     */
    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    /**
     * User password for mongodb connection
     *
     * @param password
     */
    public void setPassword(String password) {
        this.password = password;
    }

    public String getHosts() {
        return hosts;
    }

    /**
     * Host address of mongodb server in `[host]:[port]` format. It's possible also use more than one address, as comma
     * separated list of hosts: `[host1]:[port1],[host2]:[port2]`. If the hosts parameter is specified, the provided
     * connectionBean is ignored.
     *
     * @param hosts
     */
    public void setHosts(String hosts) {
        this.hosts = hosts;
    }

}
