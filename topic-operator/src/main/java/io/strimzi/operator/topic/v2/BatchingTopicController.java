/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.topic.v2;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.micrometer.core.instrument.Timer;
import io.strimzi.api.kafka.Crds;
import io.strimzi.api.kafka.model.KafkaTopic;
import io.strimzi.api.kafka.model.KafkaTopicBuilder;
import io.strimzi.api.kafka.model.status.Condition;
import io.strimzi.api.kafka.model.status.ConditionBuilder;
import io.strimzi.operator.common.Annotations;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.ReconciliationLogger;
import io.strimzi.operator.common.model.StatusUtils;
import io.strimzi.operator.topic.v2.metrics.TopicOperatorMetricsHolder;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.AlterConfigsResult;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.CreatePartitionsResult;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.admin.DeleteTopicsResult;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.clients.admin.DescribeConfigsResult;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.NewPartitions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.PartitionReassignment;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicCollection;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.ApiException;
import org.apache.kafka.common.errors.TopicDeletionDisabledException;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;

import java.io.InterruptedIOException;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A unidirectional operator
 */
@SuppressWarnings({"checkstyle:ClassFanOutComplexity", "checkstyle:ClassDataAbstractionCoupling"})
public class BatchingTopicController {

    static final ReconciliationLogger LOGGER = ReconciliationLogger.create(BatchingTopicController.class);

    static final String FINALIZER = "strimzi.io/topic-operator";
    static final String MANAGED = "strimzi.io/managed";
    static final String AUTO_CREATE_TOPICS_ENABLE = "auto.create.topics.enable";
    private static final int BROKER_DEFAULT = -1;
    private final boolean useFinalizer;
    private final boolean enableAdditionalMetrics;

    private final Admin admin;

    private final Map<String, String> selector;

    private final KubernetesClient kubeClient;

    // Key: topic name, Value: The KafkaTopics known to manage that topic
    /* test */ final Map<String, Set<KubeRef>> topics = new HashMap<>();

    private final TopicOperatorMetricsHolder metrics;
    private final String namespace;

    BatchingTopicController(Map<String, String> selector,
                            Admin admin,
                            KubernetesClient kubeClient,
                            boolean useFinalizer,
                            TopicOperatorMetricsHolder metrics,
                            String namespace,
                            boolean enableAdditionalMetrics) throws ExecutionException, InterruptedException {
        this.selector = Objects.requireNonNull(selector);
        this.useFinalizer = useFinalizer;
        this.admin = admin;
        DescribeClusterResult describeClusterResult = admin.describeCluster();
        // Get the config of some broker and check whether auto topic creation is enabled
        boolean hasAutoCreateTopics = false;
        var nodes = describeClusterResult.nodes().get();
        Map<ConfigResource, KafkaFuture<Map<ConfigResource, Config>>> futures = new HashMap<>();
        for (var node : nodes) {
            ConfigResource nodeResource = new ConfigResource(ConfigResource.Type.BROKER, node.idString());
            futures.put(nodeResource, admin.describeConfigs(Set.of(nodeResource)).all());
        }
        for (var entry : futures.entrySet()) {
            var nodeConfig = entry.getValue().get().get(entry.getKey());
            var autoCreateTopics = nodeConfig.get(AUTO_CREATE_TOPICS_ENABLE);
            hasAutoCreateTopics |= "true".equals(autoCreateTopics.value());
        }
        if (hasAutoCreateTopics) {
            LOGGER.warnOp(
                    "It is recommended that " + AUTO_CREATE_TOPICS_ENABLE + " is set to 'false' " +
                    "to avoid races between the operator and Kafka applications auto-creating topics");
        }
        this.kubeClient = kubeClient;
        this.metrics = metrics;
        this.namespace = namespace;
        this.enableAdditionalMetrics = enableAdditionalMetrics;
    }

    /* test */ static boolean isManaged(KafkaTopic kt) {
        return kt.getMetadata() == null
                || kt.getMetadata().getAnnotations() == null
                || kt.getMetadata().getAnnotations().get(MANAGED) == null
                || !"false".equals(kt.getMetadata().getAnnotations().get(MANAGED));
    }

    /* test */ static boolean isPaused(KafkaTopic kt) {
        return Annotations.isReconciliationPausedWithAnnotation(kt);
    }

    private static boolean isForDeletion(KafkaTopic kt) {
        if (kt.getMetadata().getDeletionTimestamp() != null) {
            var deletionTimestamp = StatusUtils.isoUtcDatetime(kt.getMetadata().getDeletionTimestamp());
            var now = Instant.now();
            return !deletionTimestamp.isAfter(now);
        } else {
            return false;
        }
    }

    static String topicName(KafkaTopic kt) {
        String tn = null;
        if (kt.getSpec() != null) {
            tn = kt.getSpec().getTopicName();
        }
        if (tn == null) {
            tn = kt.getMetadata().getName();
        }
        return tn;
    }

    static String resourceVersion(KafkaTopic kt) {
        return kt == null || kt.getMetadata() == null ? "null" : kt.getMetadata().getResourceVersion();
    }

    private List<ReconcilableTopic> addOrRemoveFinalizer(boolean useFinalizer, List<ReconcilableTopic> reconcilableTopics) {
        List<ReconcilableTopic> collect = reconcilableTopics.stream()
                .map(reconcilableTopic ->
                        new ReconcilableTopic(reconcilableTopic.reconciliation(), useFinalizer ? addFinalizer(reconcilableTopic) : removeFinalizer(reconcilableTopic), reconcilableTopic.topicName()))
                .collect(Collectors.toList());
        LOGGER.traceOp("{} {} topics", useFinalizer ? "Added finalizers to" : "Removed finalizers from", reconcilableTopics.size());
        return collect;
    }

    private KafkaTopic addFinalizer(ReconcilableTopic reconcilableTopic) {
        if (!reconcilableTopic.kt().getMetadata().getFinalizers().contains(FINALIZER)) {
            LOGGER.debugCr(reconcilableTopic.reconciliation(), "Adding finalizer {}", FINALIZER);
            Timer.Sample timerSample = startOperationTimer();
            KafkaTopic edit = Crds.topicOperation(kubeClient).resource(reconcilableTopic.kt()).edit(old ->
                    new KafkaTopicBuilder(old).editOrNewMetadata().addToFinalizers(FINALIZER).endMetadata().build());
            stopOperationTimer(timerSample, metrics::addFinalizerTimer);
            LOGGER.traceCr(reconcilableTopic.reconciliation(), "Added finalizer {}, resourceVersion now {}", FINALIZER, resourceVersion(edit));
            return edit;
        }
        return reconcilableTopic.kt();
    }

    private KafkaTopic removeFinalizer(ReconcilableTopic reconcilableTopic) {
        if (reconcilableTopic.kt().getMetadata().getFinalizers().contains(FINALIZER)) {
            LOGGER.debugCr(reconcilableTopic.reconciliation(), "Removing finalizer {}", FINALIZER);
            Timer.Sample timerSample = startOperationTimer();
            var result = Crds.topicOperation(kubeClient).resource(reconcilableTopic.kt()).edit(old ->
                    new KafkaTopicBuilder(old).editOrNewMetadata().removeFromFinalizers(FINALIZER).endMetadata().build());
            stopOperationTimer(timerSample, metrics::removeFinalizerTimer);
            LOGGER.traceCr(reconcilableTopic.reconciliation(), "Removed finalizer {}, resourceVersion now {}", FINALIZER, resourceVersion(result));
            return result;
        } else {
            return reconcilableTopic.kt();
        }
    }

    private Either<TopicOperatorException, Boolean> validate(ReconcilableTopic reconcilableTopic) {
        var doReconcile = Either.<TopicOperatorException, Boolean>ofRight(true);
        doReconcile = doReconcile.flatMapRight((Boolean x) -> x ? validateUnchangedTopicName(reconcilableTopic) : Either.ofRight(false));
        doReconcile = doReconcile.mapRight((Boolean x) -> x ? rememberTopic(reconcilableTopic) : false);
        return doReconcile;
    }

    private boolean rememberTopic(ReconcilableTopic reconcilableTopic) {
        String tn = reconcilableTopic.topicName();
        var existing = topics.computeIfAbsent(tn, k -> new HashSet<>());
        KubeRef thisRef = new KubeRef(reconcilableTopic.kt());
        existing.add(thisRef);
        return true;
    }

    private Either<TopicOperatorException, Boolean> validateSingleManagingResource(ReconcilableTopic reconcilableTopic) {
        String tn = reconcilableTopic.topicName();
        var existing = topics.get(tn);
        KubeRef thisRef = new KubeRef(reconcilableTopic.kt());
        if (existing.size() != 1) {
            var byCreationTime = existing.stream().sorted(Comparator.comparing(KubeRef::creationTime)).toList();

            var oldest = byCreationTime.get(0);
            var nextOldest = byCreationTime.size() >= 2 ? byCreationTime.get(1) : null;
            TopicOperatorException e = new TopicOperatorException.ResourceConflict("Managed by " + oldest);
            if (nextOldest == null) {
                // This is only resource for that topic => it is the unique oldest
                return Either.ofRight(true);
            } else if (thisRef.equals(oldest) && nextOldest.creationTime() != oldest.creationTime()) {
                // This resource is the unique oldest, so it's OK.
                // The others will eventually get reconciled and put into ResourceConflict
                return Either.ofRight(true);
            } else if (thisRef.equals(oldest)
                    && reconcilableTopic.kt().getStatus() != null
                    && reconcilableTopic.kt().getStatus().getConditions() != null
                    && reconcilableTopic.kt().getStatus().getConditions().stream().anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()))) {
                return Either.ofRight(true);
            } else {
                // Return an error putting this resource into ResourceConflict
                return Either.ofLeft(e);
            }

        }
        return Either.ofRight(true);
    }

    /* test */ static boolean matchesSelector(Map<String, String> selector, Map<String, String> resourceLabels) {
        if (!selector.isEmpty()) {
            for (var selectorEntry : selector.entrySet()) {
                String resourceValue = resourceLabels.get(selectorEntry.getKey());
                if (resourceValue == null
                        || !resourceValue.equals(selectorEntry.getValue())) {
                    return false;
                }
            }
        }
        return resourceLabels.keySet().containsAll(selector.keySet());
    }

    private static Either<TopicOperatorException, Boolean> validateUnchangedTopicName(ReconcilableTopic reconcilableTopic) {
        if (reconcilableTopic.kt().getStatus() != null
                && reconcilableTopic.kt().getStatus().getTopicName() != null
                && !topicName(reconcilableTopic.kt()).equals(reconcilableTopic.kt().getStatus().getTopicName())) {
            return Either.ofLeft(new TopicOperatorException.NotSupported("Changing spec.topicName is not supported"
            ));
        }
        return Either.ofRight(true);
    }

    private PartitionedByError<ReconcilableTopic, Void> createTopics(List<ReconcilableTopic> kts) {
        var newTopics = kts.stream().map(reconcilableTopic -> {
            // Admin create
            return buildNewTopic(reconcilableTopic.kt(), reconcilableTopic.topicName());
        }).collect(Collectors.toSet());

        LOGGER.debugOp("Admin.createTopics({})", newTopics);
        Timer.Sample timerSample = startOperationTimer();
        CreateTopicsResult ctr = admin.createTopics(newTopics);
        ctr.all().whenComplete((i, e) -> {
            stopOperationTimer(timerSample, metrics::createTopicsTimer);
            if (e != null) {
                LOGGER.traceOp("Admin.createTopics({}) failed with {}", newTopics, String.valueOf(e));
            } else {
                LOGGER.traceOp("Admin.createTopics({}) completed", newTopics);
            }
        });
        Map<String, KafkaFuture<Void>> values = ctr.values();
        return partitionedByError(kts.stream().map(reconcilableTopic -> {
            try {
                values.get(reconcilableTopic.topicName()).get();
                return pair(reconcilableTopic, Either.ofRight((null)));
            } catch (ExecutionException e) {
                if (e.getCause() != null && e.getCause() instanceof TopicExistsException) {
                    // we treat this as a success, the next reconciliation checks the configuration
                    return pair(reconcilableTopic, Either.ofRight((null)));
                } else {
                    return pair(reconcilableTopic, Either.ofLeft(handleAdminException(e)));
                }
            } catch (InterruptedException e) {
                throw new UncheckedInterruptedException(e);
            }
        }));
    }

    private static TopicOperatorException handleAdminException(ExecutionException e) {
        var cause = e.getCause();
        if (cause instanceof ApiException) {
            return new TopicOperatorException.KafkaError((ApiException) cause);
        } else {
            return new TopicOperatorException.InternalError(cause);
        }
    }

    private static NewTopic buildNewTopic(KafkaTopic kt, String topicName) {
        return new NewTopic(topicName, partitions(kt), replicas(kt)).configs(buildConfigsMap(kt));
    }

    private static int partitions(KafkaTopic kt) {
        return kt.getSpec().getPartitions() != null ? kt.getSpec().getPartitions() : BROKER_DEFAULT;
    }

    private static short replicas(KafkaTopic kt) {
        return kt.getSpec().getReplicas() != null ? kt.getSpec().getReplicas().shortValue() : BROKER_DEFAULT;
    }

    private static Map<String, String> buildConfigsMap(KafkaTopic kt) {
        Map<String, String> configs = new HashMap<>();
        if (hasConfig(kt)) {
            for (var entry : kt.getSpec().getConfig().entrySet()) {
                configs.put(entry.getKey(), configValueAsString(entry.getValue()));
            }
        }
        return configs;
    }

    private static String configValueAsString(Object value) {
        String valueStr;
        if (value instanceof String
                || value instanceof Boolean) {
            valueStr = value.toString();
        } else if (value instanceof Number) {
            valueStr = value.toString();
        } else if (value instanceof List) {
            valueStr = ((List<?>) value).stream()
                    .map(BatchingTopicController::configValueAsString)
                    .collect(Collectors.joining(","));
        } else {
            throw new RuntimeException("Cannot convert " + value);
        }
        return valueStr;
    }

    record CurrentState(TopicDescription topicDescription, Config configs) {
        /**
         * @return The number of partitions.
         */
        int numPartitions() {
            return topicDescription.partitions().size();
        }

        /**
         * @return the unique replication factor for all partitions of this topic, or
         * {@link Integer#MIN_VALUE} if there is no unique replication factor
         */
        int uniqueReplicationFactor() {
            int uniqueRf = Integer.MIN_VALUE;
            for (var partition : topicDescription.partitions()) {
                int thisPartitionRf = partition.replicas().size();
                if (uniqueRf != Integer.MIN_VALUE && uniqueRf != thisPartitionRf) {
                    return Integer.MIN_VALUE;
                }
                uniqueRf = thisPartitionRf;
            }
            return uniqueRf;
        }

        Set<Integer> partitionsWithDifferentRfThan(int rf) {
            return topicDescription.partitions().stream()
                    .filter(partition -> rf != partition.replicas().size())
                    .map(TopicPartitionInfo::partition)
                    .collect(Collectors.toSet());
        }
    }

    record PartitionedByError<K, X>(List<Pair<K, Either<TopicOperatorException, X>>> okList,
                                    List<Pair<K, Either<TopicOperatorException, X>>> errorsList) {

        public Stream<Pair<K, X>> ok() {
            return okList.stream().map(x -> pair(x.getKey(), x.getValue().right()));
        }

        public Stream<Pair<K, TopicOperatorException>> errors() {
            return errorsList.stream().map(x -> pair(x.getKey(), x.getValue().left()));
        }

    }

    private static <K, X> PartitionedByError<K, X> partitionedByError(Stream<Pair<K, Either<TopicOperatorException, X>>> stream) {
        var collect = stream.collect(Collectors.partitioningBy(x -> x.getValue().isRight()));
        return new PartitionedByError<>(
                collect.get(true),
                collect.get(false));
    }

    /**
     * @param topics The topics to reconcile
     * @throws InterruptedException If the thread was interrupted while blocking
     */
    void onUpdate(List<ReconcilableTopic> topics) throws InterruptedException {
        try {
            updateInternal(topics);
        } catch (UncheckedInterruptedException e) {
            throw e.getCause();
        } catch (KubernetesClientException e) {
            if (e.getCause() instanceof InterruptedIOException) {
                throw new InterruptedException();
            } else {
                throw e;
            }
        }
    }

    private void updateInternal(List<ReconcilableTopic> topics) {
        LOGGER.debugOp("Reconciling batch {}", topics);
        var partitionedByDeletion = topics.stream().filter(reconcilableTopic -> {
            var kt = reconcilableTopic.kt();
            if (!matchesSelector(selector, kt.getMetadata().getLabels())) {
                forgetTopic(reconcilableTopic);
                LOGGER.debugCr(reconcilableTopic.reconciliation(), "Ignoring KafkaTopic with labels {} not selected by selector {}",
                    kt.getMetadata().getLabels(), selector);
                return false;
            }
            return true;
        }).collect(Collectors.partitioningBy(reconcilableTopic -> {
            boolean forDeletion = isForDeletion(reconcilableTopic.kt());

            if (forDeletion) {
                LOGGER.debugCr(reconcilableTopic.reconciliation(), "metadata.deletionTimestamp is set, so try onDelete()");
            }
            return forDeletion;
        }));

        var toBeDeleted = partitionedByDeletion.get(true);
        if (!toBeDeleted.isEmpty()) {
            deleteInternal(toBeDeleted, false);
        }

        var remainingAfterDeletions = partitionedByDeletion.get(false);
        remainingAfterDeletions.forEach(this::startReconciliationTimer);
        Map<ReconcilableTopic, Either<TopicOperatorException, Object>> results = new HashMap<>();

        var partitionedByManaged = remainingAfterDeletions.stream().collect(Collectors.partitioningBy(reconcilableTopic -> isManaged(reconcilableTopic.kt())));
        var unmanaged = partitionedByManaged.get(false);
        addOrRemoveFinalizer(useFinalizer, unmanaged).forEach(rt -> putResult(results, rt, Either.ofRight(null)));

        // skip reconciliation of paused KafkaTopics
        var partitionedByPaused = validateManagedTopics(partitionedByManaged).stream().filter(hasTopicSpec)
            .collect(Collectors.partitioningBy(reconcilableTopic -> isPaused(reconcilableTopic.kt())));
        partitionedByPaused.get(true).forEach(reconcilableTopic -> putResult(results, reconcilableTopic, Either.ofRight(null)));

        var mayNeedUpdate = partitionedByPaused.get(false);
        metrics.reconciliationsCounter(namespace).increment(mayNeedUpdate.size());
        var addedFinalizer = addOrRemoveFinalizer(useFinalizer, mayNeedUpdate);

        var currentStatesOrError = describeTopic(addedFinalizer);

        createMissingTopics(results, currentStatesOrError);

        // figure out necessary updates
        List<Pair<ReconcilableTopic, Collection<AlterConfigOp>>> someAlterConfigs = configChanges(results, currentStatesOrError);
        List<Pair<ReconcilableTopic, NewPartitions>> someCreatePartitions = partitionChanges(results, currentStatesOrError);

        // execute those updates
        var alterConfigsResults = alterConfigs(someAlterConfigs);
        var createPartitionsResults = createPartitions(someCreatePartitions);

        accumulateResults(results, currentStatesOrError, alterConfigsResults, createPartitionsResults);

        updateStatuses(results);
        remainingAfterDeletions.forEach(this::stopReconciliationTimer);

        LOGGER.traceOp("Reconciled batch of {} KafkaTopics", results.size());
    }

    private Predicate<ReconcilableTopic> hasTopicSpec = reconcilableTopic -> {
        var hasSpec = reconcilableTopic.kt().getSpec() != null;
        if (!hasSpec) {
            LOGGER.warnCr(reconcilableTopic.reconciliation(), "Topic has no spec.");
        }
        return hasSpec;
    };

    private List<ReconcilableTopic> validateManagedTopics(Map<Boolean, List<ReconcilableTopic>> partitionedByManaged) {
        var mayNeedUpdate = partitionedByManaged.get(true).stream().filter(reconcilableTopic -> {
            var e = validate(reconcilableTopic);
            if (e.isRightEqual(false)) {
                // Do nothing
                return false;
            } else if (e.isRightEqual(true)) {
                return true;
            } else {
                updateStatusForException(reconcilableTopic, e.left());
                return false;
            }
        }).filter(reconcilableTopic -> {
            var e = validateSingleManagingResource(reconcilableTopic);
            if (e.isRightEqual(false)) {
                // Do nothing
                return false;
            } else if (e.isRightEqual(true)) {
                return true;
            } else {
                updateStatusForException(reconcilableTopic, e.left());
                return false;
            }
        }).toList();
        return mayNeedUpdate;
    }

    private static void putResult(Map<ReconcilableTopic, Either<TopicOperatorException, Object>> results, ReconcilableTopic key, Either<TopicOperatorException, Object> result) {
        results.compute(key, (k, v) -> {
            if (v == null) {
                return result;
            } else if (v.isRight()) {
                return result;
            } else {
                return v;
            }
        });
    }

    private void createMissingTopics(Map<ReconcilableTopic, Either<TopicOperatorException, Object>> results, PartitionedByError<ReconcilableTopic, CurrentState> currentStatesOrError) {
        var partitionedByUnknownTopic = currentStatesOrError.errors().collect(Collectors.partitioningBy(pair -> {
            var ex = pair.getValue();
            return ex instanceof TopicOperatorException.KafkaError
                    && ex.getCause() instanceof UnknownTopicOrPartitionException;
        }));
        partitionedByUnknownTopic.get(false).forEach(pair -> putResult(results, pair.getKey(), Either.ofLeft(pair.getValue())));

        if (!partitionedByUnknownTopic.get(true).isEmpty()) {
            var createResults = createTopics(partitionedByUnknownTopic.get(true).stream().map(Pair::getKey).toList());
            createResults.ok().forEach(pair -> putResult(results, pair.getKey(), Either.ofRight(null)));
            createResults.errors().forEach(pair -> putResult(results, pair.getKey(), Either.ofLeft(pair.getValue())));
        }
    }

    private void updateStatuses(Map<ReconcilableTopic, Either<TopicOperatorException, Object>> results) {
        // Update statues with the overall results.
        results.entrySet().stream().forEach(entry -> {
            var reconcilableTopic = entry.getKey();
            var either = entry.getValue();
            if (either.isRight()) {
                updateStatusOk(reconcilableTopic);
            } else {
                updateStatusForException(reconcilableTopic, either.left());
            }
        });
        LOGGER.traceOp("Updated status of {} KafkaTopics", results.size());
    }

    private void accumulateResults(Map<ReconcilableTopic, Either<TopicOperatorException, Object>> results,
                                   PartitionedByError<ReconcilableTopic, CurrentState> currentStatesOrError,
                                   PartitionedByError<ReconcilableTopic, Void> alterConfigsResults,
                                   PartitionedByError<ReconcilableTopic, Void> createPartitionsResults) {
        // add the successes to the results
        alterConfigsResults.ok().forEach(pair -> putResult(results, pair.getKey(), Either.ofRight(null)));
        createPartitionsResults.ok().forEach(pair -> putResult(results, pair.getKey(), Either.ofRight(null)));

        // add to errors (potentially overwriting some successes, e.g. if configs succeeded but partitions failed)
        alterConfigsResults.errors().forEach(pair -> putResult(results, pair.getKey(), Either.ofLeft(pair.getValue())));
        createPartitionsResults.errors().forEach(pair -> putResult(results, pair.getKey(), Either.ofLeft(pair.getValue())));
        var apparentlyDifferentRf = currentStatesOrError.ok().filter(pair -> {
            var reconcilableTopic = pair.getKey();
            var currentState = pair.getValue();
            return reconcilableTopic.kt().getSpec().getReplicas() != null
                && currentState.uniqueReplicationFactor() != reconcilableTopic.kt().getSpec().getReplicas();
        }).toList();

        var actuallyDifferentRf = partitionedByError(filterByReassignmentTargetReplicas(apparentlyDifferentRf).stream());
        actuallyDifferentRf.errors().forEach(pair -> {
            putResult(results, pair.getKey(), Either.ofLeft(pair.getValue()));
        });
        actuallyDifferentRf.ok().forEach(pair -> {
            var reconcilableTopic = pair.getKey();
            var specPartitions = partitions(reconcilableTopic.kt());
            var partitions = pair.getValue().partitionsWithDifferentRfThan(specPartitions);
            putResult(results, reconcilableTopic, Either.ofLeft(new TopicOperatorException.NotSupported("Replication factor change not supported, but required for partitions " + partitions)));
        });
    }

    private static List<Pair<ReconcilableTopic, Collection<AlterConfigOp>>> configChanges(Map<ReconcilableTopic, Either<TopicOperatorException, Object>> results, PartitionedByError<ReconcilableTopic, CurrentState> currentStatesOrError) {
        // Determine config changes
        Map<Boolean, List<Pair<ReconcilableTopic, Collection<AlterConfigOp>>>> alterConfigs = currentStatesOrError.ok().map(pair -> {
            var reconcilableTopic = pair.getKey();
            var currentState = pair.getValue();
            // determine config changes
            return pair(reconcilableTopic, buildAlterConfigOps(reconcilableTopic.reconciliation(), reconcilableTopic.kt(), currentState.configs()));
        }).collect(Collectors.partitioningBy(pair -> pair.getValue().isEmpty()));

        // add topics which don't require configs changes to the results (may be overwritten later)
        alterConfigs.get(true).forEach(pair -> putResult(results, pair.getKey(), Either.ofRight(null)));
        var someAlterConfigs = alterConfigs.get(false);
        return someAlterConfigs;
    }

    private static List<Pair<ReconcilableTopic, NewPartitions>> partitionChanges(Map<ReconcilableTopic, Either<TopicOperatorException, Object>> results, PartitionedByError<ReconcilableTopic, CurrentState> currentStatesOrError) {
        // Determine partition changes
        PartitionedByError<ReconcilableTopic, NewPartitions> newPartitionsOrError = partitionedByError(currentStatesOrError.ok().map(pair -> {
            var reconcilableTopic = pair.getKey();
            var currentState = pair.getValue();
            // determine config changes
            return BatchingTopicController.pair(reconcilableTopic, buildNewPartitions(reconcilableTopic.reconciliation(), reconcilableTopic.kt(), currentState.numPartitions()));
        }));
        newPartitionsOrError.errors().forEach(pair -> putResult(results, pair.getKey(), Either.ofLeft(pair.getValue())));

        var createPartitions = newPartitionsOrError.ok().collect(
                Collectors.partitioningBy(pair -> pair.getValue() == null));
        // add topics which don't require partitions changes to the results (may be overwritten later)
        createPartitions.get(true).forEach(pair -> putResult(results, pair.getKey(), Either.ofRight(null)));
        var someCreatePartitions = createPartitions.get(false);
        return someCreatePartitions;
    }

    private List<Pair<ReconcilableTopic, Either<TopicOperatorException, CurrentState>>> filterByReassignmentTargetReplicas(
            List<Pair<ReconcilableTopic, CurrentState>> apparentlyDifferentRfTopics) {
        if (apparentlyDifferentRfTopics.isEmpty()) {
            return List.of();
        }
        Set<TopicPartition> apparentDifferentRfPartitions = apparentlyDifferentRfTopics.stream().flatMap(pair -> {
            return pair.getValue().topicDescription.partitions().stream()
                    .filter(pi -> {
                        // includes only the partitions of the topic with a RF that mismatches the desired RF
                        var desiredRf = pair.getKey().kt().getSpec().getReplicas();
                        return desiredRf != pi.replicas().size();
                    })
                    .map(pi -> new TopicPartition(pair.getKey().topicName(), pi.partition()));
        }).collect(Collectors.toSet());

        Map<TopicPartition, PartitionReassignment> reassignments;
        LOGGER.traceOp("Admin.listPartitionReassignments({})", apparentDifferentRfPartitions);
        Timer.Sample timerSample = startOperationTimer();
        try {
            reassignments = admin.listPartitionReassignments(apparentDifferentRfPartitions).reassignments().get();
            stopOperationTimer(timerSample, metrics::listReassignmentsTimer);
            LOGGER.traceOp("Admin.listPartitionReassignments({}) completed", apparentDifferentRfPartitions);
        } catch (ExecutionException e) {
            stopOperationTimer(timerSample, metrics::listReassignmentsTimer);
            LOGGER.traceOp("Admin.listPartitionReassignments({}) failed with {}", apparentDifferentRfPartitions, e);
            return apparentlyDifferentRfTopics.stream().map(pair ->
                    pair(pair.getKey, Either.<TopicOperatorException, CurrentState>ofLeft(handleAdminException(e)))).toList();
        } catch (InterruptedException e) {
            throw new UncheckedInterruptedException(e);
        }

        var partitionToTargetRf = reassignments.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> {
            var tp = entry.getKey();
            var partitionReassignment = entry.getValue();
            // See https://cwiki.apache.org/confluence/display/KAFKA/KIP-455%3A+Create+an+Administrative+API+for+Replica+Reassignment#KIP455:CreateanAdministrativeAPIforReplicaReassignment-Algorithm
            // for a full description of the algorithm
            // but in essence replicas() will include addingReplicas() from the beginning
            // so the target rf will be the replicas minus the removing
            var target = new HashSet<>(partitionReassignment.replicas());
            target.removeAll(partitionReassignment.removingReplicas());
            return target.size();
        }));

        return apparentlyDifferentRfTopics.stream().filter(pair -> {
            boolean b = pair.getValue.topicDescription.partitions().stream().anyMatch(pi -> {
                TopicPartition tp = new TopicPartition(pair.getKey.topicName(), pi.partition());
                Integer targetRf = partitionToTargetRf.get(tp);
                Integer desiredRf = pair.getKey.kt().getSpec().getReplicas();
                return !Objects.equals(targetRf, desiredRf);
            });
            return b;
        }).map(pair -> pair(pair.getKey, Either.<TopicOperatorException, CurrentState>ofRight(pair.getValue))).toList();
    }

    // A pair of values. We can't use Map.entry because it forbids null values, which we want to allow.
    record Pair<K, V>(K getKey, V getValue) { }

    static <K, V> Pair<K, V> pair(K key, V value) {
        return new Pair<>(key, value);
    }

    private PartitionedByError<ReconcilableTopic, Void> alterConfigs(List<Pair<ReconcilableTopic, Collection<AlterConfigOp>>> someAlterConfigs) {
        if (someAlterConfigs.isEmpty()) {
            return new PartitionedByError<>(List.of(), List.of());
        }
        Map<ConfigResource, Collection<AlterConfigOp>> alteredConfigs = someAlterConfigs.stream().collect(Collectors.toMap(entry -> topicConfigResource(entry.getKey().topicName()), Pair::getValue));
        LOGGER.debugOp("Admin.incrementalAlterConfigs({})", alteredConfigs);
        Timer.Sample timerSample = startOperationTimer();
        AlterConfigsResult acr = admin.incrementalAlterConfigs(alteredConfigs);
        stopOperationTimer(timerSample, metrics::alterConfigsTimer);
        acr.all().whenComplete((i, e) -> {
            stopOperationTimer(timerSample, metrics::alterConfigsTimer);
            if (e != null) {
                LOGGER.traceOp("Admin.incrementalAlterConfigs({}) failed with {}", alteredConfigs, String.valueOf(e));
            } else {
                LOGGER.traceOp("Admin.incrementalAlterConfigs({}) completed", alteredConfigs);
            }
        });
        var alterConfigsResult = acr.values();
        Stream<Pair<ReconcilableTopic, Either<TopicOperatorException, Void>>> entryStream = someAlterConfigs.stream().map(entry -> {
            try {
                return pair(entry.getKey(), Either.ofRight(alterConfigsResult.get(topicConfigResource(entry.getKey().topicName())).get()));
            } catch (ExecutionException e) {
                return pair(entry.getKey(), Either.ofLeft(handleAdminException(e)));
            } catch (InterruptedException e) {
                throw new UncheckedInterruptedException(e);
            }
        });
        return partitionedByError(entryStream);
    }

    private PartitionedByError<ReconcilableTopic, Void> createPartitions(List<Pair<ReconcilableTopic, NewPartitions>> someCreatePartitions) {
        if (someCreatePartitions.isEmpty()) {
            return new PartitionedByError<>(List.of(), List.of());
        }
        Map<String, NewPartitions> newPartitions = someCreatePartitions.stream().collect(Collectors.toMap(pair -> pair.getKey().topicName(), Pair::getValue));
        LOGGER.debugOp("Admin.createPartitions({})", newPartitions);
        Timer.Sample timerSample = startOperationTimer();
        CreatePartitionsResult cpr = admin.createPartitions(newPartitions);
        cpr.all().whenComplete((i, e) -> {
            stopOperationTimer(timerSample, metrics::createPartitionsTimer);
            if (e != null) {
                LOGGER.traceOp("Admin.createPartitions({}) failed with {}", newPartitions, String.valueOf(e));
            } else {
                LOGGER.traceOp("Admin.createPartitions({}) completed", newPartitions);
            }
        });
        var createPartitionsResult = cpr.values();
        var entryStream = someCreatePartitions.stream().map(entry -> {
            try {
                createPartitionsResult.get(entry.getKey().topicName()).get();
                return pair(entry.getKey(), Either.<TopicOperatorException, Void>ofRight(null));
            } catch (ExecutionException e) {
                return pair(entry.getKey(), Either.<TopicOperatorException, Void>ofLeft(handleAdminException(e)));
            } catch (InterruptedException e) {
                throw new UncheckedInterruptedException(e);
            }
        });
        return partitionedByError(entryStream);
    }

    private static ConfigResource topicConfigResource(String tn) {
        return new ConfigResource(ConfigResource.Type.TOPIC, tn);
    }

    private PartitionedByError<ReconcilableTopic, CurrentState> describeTopic(List<ReconcilableTopic> batch) {
        if (batch.isEmpty()) {
            return new PartitionedByError<>(List.of(), List.of());
        }
        Set<ConfigResource> configResources = batch.stream()
                .map(reconcilableTopic -> topicConfigResource(reconcilableTopic.topicName()))
                .collect(Collectors.toSet());
        Set<String> tns = batch.stream().map(ReconcilableTopic::topicName).collect(Collectors.toSet());

        DescribeTopicsResult describeTopicsResult;
        {
            LOGGER.debugOp("Admin.describeTopics({})", tns);
            Timer.Sample timerSample = startOperationTimer();
            describeTopicsResult = admin.describeTopics(tns);
            describeTopicsResult.allTopicNames().whenComplete((i, e) -> {
                stopOperationTimer(timerSample, metrics::describeTopicsTimer);
                if (e != null) {
                    LOGGER.traceOp("Admin.describeTopics({}) failed with {}", tns, String.valueOf(e));
                } else {
                    LOGGER.traceOp("Admin.describeTopics({}) completed", tns);
                }
            });
        }
        DescribeConfigsResult describeConfigsResult;
        {
            LOGGER.debugOp("Admin.describeConfigs({})", configResources);
            Timer.Sample timerSample = startOperationTimer();
            describeConfigsResult = admin.describeConfigs(configResources);
            describeConfigsResult.all().whenComplete((i, e) -> {
                stopOperationTimer(timerSample, metrics::describeConfigsTimer);
                if (e != null) {
                    LOGGER.traceOp("Admin.describeConfigs({}) failed with {}", configResources, String.valueOf(e));
                } else {
                    LOGGER.traceOp("Admin.describeConfigs({}) completed", configResources);
                }
            });
        }

        var cs1 = describeTopicsResult.topicNameValues();
        var cs2 = describeConfigsResult.values();
        return partitionedByError(batch.stream().map(reconcilableTopic -> {
            Config configs = null;
            TopicDescription description = null;
            ExecutionException exception = null;
            try {
                description = cs1.get(reconcilableTopic.topicName()).get();
            } catch (ExecutionException e) {
                exception = e;
            } catch (InterruptedException e) {
                throw new UncheckedInterruptedException(e);
            }

            try {
                configs = cs2.get(topicConfigResource(reconcilableTopic.topicName())).get();
            } catch (ExecutionException e) {
                exception = e;
            } catch (InterruptedException e) {
                throw new UncheckedInterruptedException(e);
            }
            if (exception != null) {
                return pair(reconcilableTopic, Either.ofLeft(handleAdminException(exception)));
            } else {
                return pair(reconcilableTopic, Either.ofRight(new CurrentState(description, configs)));
            }
        }));
    }

    void onDelete(List<ReconcilableTopic> batch) throws InterruptedException {
        try {
            deleteInternal(batch, true);
        } catch (UncheckedInterruptedException e) {
            throw e.getCause();
        } catch (KubernetesClientException e) {
            if (e.getCause() instanceof InterruptedIOException) {
                throw new InterruptedException();
            } else {
                throw e;
            }
        }
    }

    private void deleteInternal(List<ReconcilableTopic> batch, boolean onDeletePath) {
        var partitionedByManaged = batch.stream().filter(reconcilableTopic -> {
            if (isManaged(reconcilableTopic.kt())) {
                var e = validate(reconcilableTopic);
                if (e.isRightEqual(true)) {
                    // adminDelete, removeFinalizer, forgetTopic, updateStatus
                    return true;
                } else if (e.isRightEqual(false)) {
                    // do nothing
                    return false;
                } else {
                    updateStatusForException(reconcilableTopic, e.left());
                    return false;
                }
            } else {
                // removeFinalizer, forgetTopic, updateStatus
                removeFinalizer(reconcilableTopic);
                forgetTopic(reconcilableTopic);
                return false;
            }
        });

        Set<String> topicNames = partitionedByManaged.map(reconcilableTopic -> {
            metrics.reconciliationsCounter(namespace).increment();
            startReconciliationTimer(reconcilableTopic);
            return reconcilableTopic.topicName();
        }).collect(Collectors.toSet());

        PartitionedByError<ReconcilableTopic, Object> deleteResult = deleteTopics(batch, topicNames);


        // join the success with not-managed: remove the finalizer and forget the topic
        deleteResult.ok().forEach(pair -> {
            try {
                removeFinalizer(pair.getKey());
            } catch (KubernetesClientException e) {
                // If this method be being called because the resource was deleted
                // then we expect the PATCH will error with Not Found
                if (!(onDeletePath && e.getCode() == 404)) { // 404 = Not Found
                    throw e;
                }
            }
            forgetTopic(pair.getKey());
            metrics.successfulReconciliationsCounter(namespace).increment();
            stopReconciliationTimer(pair.getKey());
        });

        // join that to fail
        deleteResult.errors().forEach(entry -> {
            if (!this.useFinalizer
                    && onDeletePath) {
                // When not using finalizers and a topic is deleted there will be no KafkaTopic to update
                // the status of, so we have to log errors here.
                if (entry.getValue().getCause() instanceof TopicDeletionDisabledException) {
                    LOGGER.warnCr(entry.getKey().reconciliation(),
                            "Unable to delete topic '{}' from Kafka because topic deletion is disabled on the Kafka controller.",
                            entry.getKey().topicName());
                } else {
                    LOGGER.warnCr(entry.getKey().reconciliation(),
                            "Unable to delete topic '{}' from Kafka.",
                            entry.getKey().topicName(),
                            entry.getValue());
                }
                metrics.failedReconciliationsCounter(namespace).increment();
            } else {
                updateStatusForException(entry.getKey(), entry.getValue());
            }
            stopReconciliationTimer(entry.getKey());
        });

    }

    private PartitionedByError<ReconcilableTopic, Object> deleteTopics(List<ReconcilableTopic> batch, Set<String> topicNames) {
        if (topicNames.isEmpty()) {
            return new PartitionedByError<>(List.of(), List.of());
        }
        var someDeleteTopics = TopicCollection.ofTopicNames(topicNames);
        LOGGER.debugOp("Admin.deleteTopics({})", someDeleteTopics.topicNames());

        // Admin delete
        Timer.Sample timerSample = startOperationTimer();
        DeleteTopicsResult dtr = admin.deleteTopics(someDeleteTopics);
        dtr.all().whenComplete((i, e) -> {
            stopOperationTimer(timerSample, metrics::deleteTopicsTimer);
            if (e != null) {
                LOGGER.traceOp("Admin.deleteTopics({}) failed with {}", someDeleteTopics.topicNames(), String.valueOf(e));
            } else {
                LOGGER.traceOp("Admin.deleteTopics({}) completed", someDeleteTopics.topicNames());
            }
        });
        var futuresMap = dtr.topicNameValues();
        var deleteResult = partitionedByError(batch.stream().map(reconcilableTopic -> {
            try {
                futuresMap.get(reconcilableTopic.topicName()).get();
                return pair(reconcilableTopic, Either.ofRight(null));
            } catch (ExecutionException e) {
                if (e.getCause() instanceof UnknownTopicOrPartitionException) {
                    return pair(reconcilableTopic, Either.ofRight(null));
                } else {
                    return pair(reconcilableTopic, Either.ofLeft(handleAdminException(e)));
                }
            } catch (InterruptedException e) {
                throw new UncheckedInterruptedException(e);
            }
        }));
        return deleteResult;
    }

    private void forgetTopic(ReconcilableTopic reconcilableTopic) {
        topics.compute(reconcilableTopic.topicName(), (k, v) -> {
            if (v != null) {
                v.remove(new KubeRef(reconcilableTopic.kt()));
                if (v.isEmpty()) {
                    return null;
                } else {
                    return v;
                }
            } else {
                return null;
            }
        });
    }

    private static Either<TopicOperatorException, NewPartitions> buildNewPartitions(Reconciliation reconciliation, KafkaTopic kt, int currentNumPartitions) {
        int requested = kt.getSpec() == null || kt.getSpec().getPartitions() == null ? BROKER_DEFAULT : kt.getSpec().getPartitions();
        if (requested > currentNumPartitions) {
            LOGGER.debugCr(reconciliation, "Partition increase from {} to {}", currentNumPartitions, requested);
            return Either.ofRight(NewPartitions.increaseTo(requested));
        } else if (requested != BROKER_DEFAULT && requested < currentNumPartitions) {
            LOGGER.debugCr(reconciliation, "Partition decrease from {} to {}", currentNumPartitions, requested);
            return Either.ofLeft(new TopicOperatorException.NotSupported("Decreasing partitions not supported"));
        } else {
            LOGGER.debugCr(reconciliation, "No partition change");
            return Either.ofRight(null);
        }
    }

    private static Collection<AlterConfigOp> buildAlterConfigOps(Reconciliation reconciliation, KafkaTopic kt, Config configs) {
        Set<AlterConfigOp> alterConfigOps = new HashSet<>();
        if (hasConfig(kt)) {
            for (var specConfigEntry : kt.getSpec().getConfig().entrySet()) {
                String key = specConfigEntry.getKey();
                var specValueStr = configValueAsString(specConfigEntry.getValue());
                var kafkaConfigEntry = configs.get(key);
                if (kafkaConfigEntry == null
                        || !Objects.equals(specValueStr, kafkaConfigEntry.value())) {
                    alterConfigOps.add(new AlterConfigOp(
                            new ConfigEntry(key, specValueStr),
                            AlterConfigOp.OpType.SET));
                }
            }
        }
        HashSet<String> keysToRemove = configs.entries().stream()
                .filter(configEntry -> configEntry.source() == ConfigEntry.ConfigSource.DYNAMIC_TOPIC_CONFIG)
                .map(ConfigEntry::name).collect(Collectors.toCollection(HashSet::new));
        if (hasConfig(kt)) {
            keysToRemove.removeAll(kt.getSpec().getConfig().keySet());
        }
        for (var key : keysToRemove) {
            alterConfigOps.add(new AlterConfigOp(
                    new ConfigEntry(key, null),
                    AlterConfigOp.OpType.DELETE));
        }
        if (alterConfigOps.isEmpty()) {
            LOGGER.debugCr(reconciliation, "No config change");
        } else {
            LOGGER.debugCr(reconciliation, "Config changes {}", alterConfigOps);
        }
        return alterConfigOps;
    }

    private static boolean hasConfig(KafkaTopic kt) {
        return kt.getSpec() != null
                && kt.getSpec().getConfig() != null;
    }

    private void updateStatusForException(ReconcilableTopic reconcilableTopic, Exception e) {
        String message = e.getMessage();
        String reason;
        if (e instanceof TopicOperatorException) {
            LOGGER.debugCr(reconcilableTopic.reconciliation(), "Updating status for exception {}", e.toString());
            reason = ((TopicOperatorException) e).reason();
        } else {
            LOGGER.warnCr(reconcilableTopic.reconciliation(), "Updating status for unexpected exception", e);
            reason = e.getClass().getSimpleName();
        }
        Condition condition = new ConditionBuilder()
            .withType("Ready")
            .withStatus("False")
            .withReason(reason)
            .withMessage(message)
            .withLastTransitionTime(StatusUtils.iso8601Now())
            .build();
        updateStatus(reconcilableTopic.reconciliation(), reconcilableTopic.kt(), condition);
        metrics.failedReconciliationsCounter(namespace).increment();
    }

    private void updateStatusOk(ReconcilableTopic reconcilableTopic) {
        Condition condition = new ConditionBuilder()
            .withType(isPaused(reconcilableTopic.kt()) ? "ReconciliationPaused" : "Ready")
            .withStatus("True")
            .withLastTransitionTime(StatusUtils.iso8601Now())
            .build();
        updateStatus(reconcilableTopic.reconciliation(), reconcilableTopic.kt(), condition);
        metrics.successfulReconciliationsCounter(namespace).increment();
    }

    private void updateStatus(Reconciliation reconciliation,
                              KafkaTopic kt,
                              Condition condition) {
        var oldStatus = kt.getStatus();
        Condition oldReadyCondition = oldStatus == null || oldStatus.getConditions() == null ?
            null : oldStatus.getConditions().stream().findFirst().orElse(null);
        if (oldStatus == null
                || (!isPaused(kt) && oldStatus.getObservedGeneration() != kt.getMetadata().getGeneration())
                || oldStatus.getTopicName() == null
                || oldReadyCondition == null
                || isDifferentCondition(oldReadyCondition, condition)) {
            // the observedGeneration is initialized to 0 when creating a paused user (oldStatus null, paused true)
            // this will result in metadata.generation: 1 > status.observedGeneration: 0 (not reconciled)
            long observedGeneration = oldStatus != null
                ? !isPaused(kt) ? kt.getMetadata().getGeneration() : oldStatus.getObservedGeneration()
                : !isPaused(kt) ? kt.getMetadata().getGeneration() : 0L;
            String newTopicName = !isManaged(kt) ? null
                    : oldStatus != null && oldStatus.getTopicName() != null ? oldStatus.getTopicName()
                    : topicName(kt);
            var updatedTopic = new KafkaTopicBuilder(kt)
                    .editOrNewMetadata()
                        .withResourceVersion(null)
                    .endMetadata()
                    .editOrNewStatus()
                        .withObservedGeneration(observedGeneration)
                        .withTopicName(newTopicName)
                        .withConditions(condition)
                    .endStatus().build();
            LOGGER.debugCr(reconciliation, "Updating status with {}", updatedTopic.getStatus());
            Timer.Sample timerSample = startOperationTimer();
            var got = Crds.topicOperation(kubeClient)
                    .resource(updatedTopic)
                    .updateStatus();
            stopOperationTimer(timerSample, metrics::updateStatusTimer);
            LOGGER.traceCr(reconciliation, "Updated status to observedGeneration {}, resourceVersion now {}",
                    got.getStatus().getObservedGeneration());
        } else {
            LOGGER.traceCr(reconciliation, "Unchanged status of {}", kt.getStatus());
        }
    }

    private static boolean isDifferentCondition(Condition oldReadyCondition,
                                                Condition condition) {
        return !Objects.equals(oldReadyCondition.getType(), condition.getType())
                || !Objects.equals(oldReadyCondition.getStatus(), condition.getStatus())
                || !Objects.equals(oldReadyCondition.getReason(), condition.getReason())
                || !Objects.equals(oldReadyCondition.getMessage(), condition.getMessage());
    }

    private void startReconciliationTimer(ReconcilableTopic topic) {
        if (topic.reconciliationTimerSample() == null) {
            topic.reconciliationTimerSample(Timer.start(metrics.metricsProvider().meterRegistry()));
        }
    }

    private void stopReconciliationTimer(ReconcilableTopic topic) {
        if (topic.reconciliationTimerSample() != null) {
            topic.reconciliationTimerSample().stop(metrics.reconciliationsTimer(namespace));
        }
    }

    private Timer.Sample startOperationTimer() {
        if (enableAdditionalMetrics) {
            return Timer.start(metrics.metricsProvider().meterRegistry());
        } else {
            return null;
        }
    }

    private void stopOperationTimer(Timer.Sample sample, Function<String, Timer> addMetric) {
        if (sample != null && enableAdditionalMetrics) {
            sample.stop(addMetric.apply(namespace));
        }
    }
}
