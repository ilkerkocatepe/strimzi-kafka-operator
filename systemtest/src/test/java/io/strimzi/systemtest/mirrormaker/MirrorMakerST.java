/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.mirrormaker;

import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.strimzi.api.kafka.model.CertSecretSource;
import io.strimzi.api.kafka.model.KafkaMirrorMaker;
import io.strimzi.api.kafka.model.KafkaMirrorMakerResources;
import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.api.kafka.model.PasswordSecretSource;
import io.strimzi.api.kafka.model.listener.KafkaListenerAuthenticationScramSha512;
import io.strimzi.api.kafka.model.listener.KafkaListenerAuthenticationTls;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListenerBuilder;
import io.strimzi.api.kafka.model.listener.arraylistener.KafkaListenerType;
import io.strimzi.api.kafka.model.status.KafkaMirrorMakerStatus;
import io.strimzi.api.kafka.model.template.DeploymentStrategy;
import io.strimzi.operator.common.model.Labels;
import io.strimzi.systemtest.AbstractST;
import io.strimzi.systemtest.TestConstants;
import io.strimzi.systemtest.Environment;
import io.strimzi.systemtest.kafkaclients.internalClients.KafkaClients;
import io.strimzi.systemtest.kafkaclients.internalClients.KafkaClientsBuilder;
import io.strimzi.systemtest.annotations.ParallelNamespaceTest;
import io.strimzi.systemtest.resources.crd.KafkaMirrorMakerResource;
import io.strimzi.systemtest.storage.TestStorage;
import io.strimzi.systemtest.templates.crd.KafkaMirrorMakerTemplates;
import io.strimzi.systemtest.templates.crd.KafkaTemplates;
import io.strimzi.systemtest.templates.crd.KafkaTopicTemplates;
import io.strimzi.systemtest.templates.crd.KafkaUserTemplates;
import io.strimzi.systemtest.utils.ClientUtils;
import io.strimzi.systemtest.utils.StUtils;
import io.strimzi.systemtest.utils.kafkaUtils.KafkaMirrorMakerUtils;
import io.strimzi.systemtest.utils.kubeUtils.controllers.DeploymentUtils;
import io.strimzi.systemtest.utils.kubeUtils.objects.PodUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hamcrest.CoreMatchers;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.rmi.UnexpectedException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.strimzi.systemtest.TestConstants.ACCEPTANCE;
import static io.strimzi.systemtest.TestConstants.COMPONENT_SCALING;
import static io.strimzi.systemtest.TestConstants.INTERNAL_CLIENTS_USED;
import static io.strimzi.systemtest.TestConstants.MIRROR_MAKER;
import static io.strimzi.systemtest.TestConstants.REGRESSION;
import static io.strimzi.systemtest.enums.CustomResourceStatus.Ready;
import static io.strimzi.test.k8s.KubeClusterResource.cmdKubeClient;
import static io.strimzi.test.k8s.KubeClusterResource.kubeClient;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag(REGRESSION)
@Tag(MIRROR_MAKER)
@Tag(INTERNAL_CLIENTS_USED)
public class MirrorMakerST extends AbstractST {

    private static final Logger LOGGER = LogManager.getLogger(MirrorMakerST.class);

    @ParallelNamespaceTest
    void testMirrorMaker(ExtensionContext extensionContext) {
        final TestStorage testStorage = new TestStorage(extensionContext, Environment.TEST_SUITE_NAMESPACE);

        Map<String, String> jvmOptionsXX = new HashMap<>();
        jvmOptionsXX.put("UseG1GC", "true");

        resourceManager.createResourceWithWait(extensionContext,
            KafkaTemplates.kafkaEphemeral(testStorage.getSourceClusterName(), 1, 1).build(),
            KafkaTemplates.kafkaEphemeral(testStorage.getTargetClusterName(), 1, 1).build()
        );

        resourceManager.createResourceWithWait(extensionContext, KafkaTopicTemplates.topic(testStorage.getSourceClusterName(), testStorage.getTopicName(), testStorage.getNamespaceName()).build());

        KafkaClients clients = new KafkaClientsBuilder()
            .withProducerName(testStorage.getProducerName())
            .withConsumerName(testStorage.getConsumerName())
            .withBootstrapAddress(KafkaResources.plainBootstrapAddress(testStorage.getSourceClusterName()))
            .withNamespaceName(testStorage.getNamespaceName())
            .withTopicName(testStorage.getTopicName())
            .withMessageCount(testStorage.getMessageCount())
            .build();

        resourceManager.createResourceWithWait(extensionContext, clients.producerStrimzi(), clients.consumerStrimzi());
        ClientUtils.waitForClientsSuccess(testStorage);

        // Deploy MirrorMaker
        resourceManager.createResourceWithWait(extensionContext, KafkaMirrorMakerTemplates.kafkaMirrorMaker(testStorage.getClusterName(), testStorage.getSourceClusterName(), testStorage.getTargetClusterName(), ClientUtils.generateRandomConsumerGroup(), 1, false)
            .editSpec()
            .withResources(new ResourceRequirementsBuilder()
                    .addToLimits("memory", new Quantity("400M"))
                    .addToLimits("cpu", new Quantity("2"))
                    .addToRequests("memory", new Quantity("300M"))
                    .addToRequests("cpu", new Quantity("1"))
                    .build())
            .withNewJvmOptions()
                .withXmx("200m")
                .withXms("200m")
                .withXx(jvmOptionsXX)
            .endJvmOptions()
            .endSpec()
            .build());

        verifyLabelsOnPods(testStorage.getNamespaceName(), testStorage.getClusterName(), "mirror-maker", KafkaMirrorMaker.RESOURCE_KIND);

        verifyLabelsForConfigMaps(testStorage.getNamespaceName(), testStorage.getSourceClusterName(), null, testStorage.getTargetClusterName());
        verifyLabelsForServiceAccounts(testStorage.getNamespaceName(), testStorage.getSourceClusterName(), null);

        String mmDepName = KafkaMirrorMakerResources.deploymentName(testStorage.getClusterName());
        String mirrorMakerPodName = kubeClient(testStorage.getNamespaceName()).listPodsByPrefixInName(mmDepName).get(0).getMetadata().getName();
        String kafkaMirrorMakerLogs = kubeClient(testStorage.getNamespaceName()).logs(mirrorMakerPodName);

        assertThat(kafkaMirrorMakerLogs,
            not(containsString("keytool error: java.io.FileNotFoundException: /opt/kafka/consumer-oauth-certs/**/* (No such file or directory)")));

        String podName = kubeClient(testStorage.getNamespaceName()).listPodsByNamespace(testStorage.getNamespaceName(), testStorage.getClusterName()).stream().filter(n -> n.getMetadata().getName().startsWith(KafkaMirrorMakerResources.deploymentName(testStorage.getClusterName()))).findFirst().orElseThrow().getMetadata().getName();
        assertResources(testStorage.getNamespaceName(), podName, mmDepName,
                "400M", "2", "300M", "1");
        assertExpectedJavaOpts(testStorage.getNamespaceName(), podName, KafkaMirrorMakerResources.deploymentName(testStorage.getClusterName()),
                "-Xmx200m", "-Xms200m", "-XX:+UseG1GC");

        clients = new KafkaClientsBuilder(clients)
            .withBootstrapAddress(KafkaResources.plainBootstrapAddress(testStorage.getTargetClusterName()))
            .build();

        resourceManager.createResourceWithWait(extensionContext, clients.consumerStrimzi());
        ClientUtils.waitForConsumerClientSuccess(testStorage);
    }

    /**
     * Test mirroring messages by MirrorMaker over tls transport using mutual tls auth
     */
    @ParallelNamespaceTest
    @Tag(ACCEPTANCE)
    @SuppressWarnings({"checkstyle:MethodLength"})
    void testMirrorMakerTlsAuthenticated(ExtensionContext extensionContext) {
        final TestStorage testStorage = new TestStorage(extensionContext, Environment.TEST_SUITE_NAMESPACE);

        // Deploy source kafka with tls listener and mutual tls auth
        resourceManager.createResourceWithWait(extensionContext, KafkaTemplates.kafkaEphemeral(testStorage.getSourceClusterName(), 1, 1)
            .editSpec()
                .editKafka()
                    .withListeners(new GenericKafkaListenerBuilder()
                            .withName(TestConstants.TLS_LISTENER_DEFAULT_NAME)
                            .withPort(9093)
                            .withType(KafkaListenerType.INTERNAL)
                            .withTls(true)
                            .withAuth(new KafkaListenerAuthenticationTls())
                            .build())
                .endKafka()
            .endSpec()
            .build());

        // Deploy target kafka with tls listener and mutual tls auth
        resourceManager.createResourceWithWait(extensionContext, KafkaTemplates.kafkaEphemeral(testStorage.getTargetClusterName(), 1, 1)
            .editSpec()
                .editKafka()
                    .withListeners(new GenericKafkaListenerBuilder()
                            .withName(TestConstants.TLS_LISTENER_DEFAULT_NAME)
                            .withPort(9093)
                            .withType(KafkaListenerType.INTERNAL)
                            .withTls(true)
                            .withAuth(new KafkaListenerAuthenticationTls())
                            .build())
                .endKafka()
            .endSpec()
            .build());

        resourceManager.createResourceWithWait(extensionContext,
            KafkaTopicTemplates.topic(testStorage.getSourceClusterName(), testStorage.getTopicName(), testStorage.getNamespaceName()).build(),
            KafkaUserTemplates.tlsUser(testStorage.getNamespaceName(), testStorage.getSourceClusterName(), testStorage.getSourceUsername()).build(),
            KafkaUserTemplates.tlsUser(testStorage.getNamespaceName(), testStorage.getTargetClusterName(), testStorage.getTargetUsername()).build()
        );

        // Initialize CertSecretSource with certificate and secret names for consumer
        CertSecretSource certSecretSource = new CertSecretSource();
        certSecretSource.setCertificate("ca.crt");
        certSecretSource.setSecretName(KafkaResources.clusterCaCertificateSecretName(testStorage.getSourceClusterName()));

        // Initialize CertSecretSource with certificate and secret names for producer
        CertSecretSource certSecretTarget = new CertSecretSource();
        certSecretTarget.setCertificate("ca.crt");
        certSecretTarget.setSecretName(KafkaResources.clusterCaCertificateSecretName(testStorage.getTargetClusterName()));

        KafkaClients clients = new KafkaClientsBuilder()
            .withProducerName(testStorage.getProducerName())
            .withConsumerName(testStorage.getConsumerName())
            .withBootstrapAddress(KafkaResources.tlsBootstrapAddress(testStorage.getSourceClusterName()))
            .withNamespaceName(testStorage.getNamespaceName())
            .withUsername(testStorage.getSourceUsername())
            .withTopicName(testStorage.getTopicName())
            .withMessageCount(testStorage.getMessageCount())
            .build();

        resourceManager.createResourceWithWait(extensionContext, clients.producerTlsStrimzi(testStorage.getSourceClusterName()), clients.consumerTlsStrimzi(testStorage.getSourceClusterName()));
        ClientUtils.waitForClientsSuccess(testStorage);

        // Deploy MirrorMaker with tls listener and mutual tls auth
        resourceManager.createResourceWithWait(extensionContext, KafkaMirrorMakerTemplates.kafkaMirrorMaker(testStorage.getClusterName(), testStorage.getSourceClusterName(), testStorage.getTargetClusterName(), ClientUtils.generateRandomConsumerGroup(), 1, true)
            .editSpec()
                .editConsumer()
                    .withNewTls()
                        .withTrustedCertificates(certSecretSource)
                    .endTls()
                    .withNewKafkaClientAuthenticationTls()
                        .withNewCertificateAndKey()
                            .withSecretName(testStorage.getSourceUsername())
                            .withCertificate("user.crt")
                            .withKey("user.key")
                        .endCertificateAndKey()
                    .endKafkaClientAuthenticationTls()
                .endConsumer()
                .editProducer()
                    .withNewTls()
                        .withTrustedCertificates(certSecretTarget)
                    .endTls()
                    .withNewKafkaClientAuthenticationTls()
                        .withNewCertificateAndKey()
                            .withSecretName(testStorage.getTargetUsername())
                            .withCertificate("user.crt")
                            .withKey("user.key")
                        .endCertificateAndKey()
                    .endKafkaClientAuthenticationTls()
                .endProducer()
            .endSpec()
            .build());

        clients = new KafkaClientsBuilder(clients)
            .withBootstrapAddress(KafkaResources.tlsBootstrapAddress(testStorage.getTargetClusterName()))
            .withUsername(testStorage.getTargetUsername())
            .build();

        resourceManager.createResourceWithWait(extensionContext, clients.consumerTlsStrimzi(testStorage.getTargetClusterName()));
        ClientUtils.waitForConsumerClientSuccess(testStorage);
    }

    /**
     * Test mirroring messages by MirrorMaker over tls transport using scram-sha auth
     */
    @ParallelNamespaceTest
    @SuppressWarnings("checkstyle:methodlength")
    void testMirrorMakerTlsScramSha(ExtensionContext extensionContext) {
        final TestStorage testStorage = new TestStorage(extensionContext, Environment.TEST_SUITE_NAMESPACE);

        // Deploy source kafka with tls listener and SCRAM-SHA authentication
        resourceManager.createResourceWithWait(extensionContext, KafkaTemplates.kafkaEphemeral(testStorage.getSourceClusterName(), 1, 1)
            .editSpec()
                .editKafka()
                    .withListeners(new GenericKafkaListenerBuilder()
                            .withName(TestConstants.TLS_LISTENER_DEFAULT_NAME)
                            .withPort(9093)
                            .withType(KafkaListenerType.INTERNAL)
                            .withTls(true)
                            .withAuth(new KafkaListenerAuthenticationScramSha512())
                            .build())
                .endKafka()
            .endSpec()
            .build());

        // Deploy target kafka with tls listener and SCRAM-SHA authentication
        resourceManager.createResourceWithWait(extensionContext, KafkaTemplates.kafkaEphemeral(testStorage.getTargetClusterName(), 1, 1)
            .editSpec()
                .editKafka()
                    .withListeners(new GenericKafkaListenerBuilder()
                            .withName(TestConstants.TLS_LISTENER_DEFAULT_NAME)
                            .withPort(9093)
                            .withType(KafkaListenerType.INTERNAL)
                            .withTls(true)
                            .withAuth(new KafkaListenerAuthenticationScramSha512())
                            .build())
                .endKafka()
            .endSpec()
            .build());

        // Deploy topic
        resourceManager.createResourceWithWait(extensionContext,
            KafkaTopicTemplates.topic(testStorage.getSourceClusterName(), testStorage.getTopicName(), testStorage.getNamespaceName()).build(),
            KafkaUserTemplates.scramShaUser(testStorage.getNamespaceName(), testStorage.getSourceClusterName(), testStorage.getSourceUsername()).build(),
            KafkaUserTemplates.scramShaUser(testStorage.getNamespaceName(), testStorage.getTargetClusterName(), testStorage.getTargetUsername()).build()
        );

        // Initialize PasswordSecretSource to set this as PasswordSecret in MirrorMaker spec
        PasswordSecretSource passwordSecretSource = new PasswordSecretSource();
        passwordSecretSource.setSecretName(testStorage.getSourceUsername());
        passwordSecretSource.setPassword("password");

        // Initialize PasswordSecretSource to set this as PasswordSecret in MirrorMaker spec
        PasswordSecretSource passwordSecretTarget = new PasswordSecretSource();
        passwordSecretTarget.setSecretName(testStorage.getTargetUsername());
        passwordSecretTarget.setPassword("password");

        // Initialize CertSecretSource with certificate and secret names for consumer
        CertSecretSource certSecretSource = new CertSecretSource();
        certSecretSource.setCertificate("ca.crt");
        certSecretSource.setSecretName(KafkaResources.clusterCaCertificateSecretName(testStorage.getSourceClusterName()));

        // Initialize CertSecretSource with certificate and secret names for producer
        CertSecretSource certSecretTarget = new CertSecretSource();
        certSecretTarget.setCertificate("ca.crt");
        certSecretTarget.setSecretName(KafkaResources.clusterCaCertificateSecretName(testStorage.getTargetClusterName()));

        KafkaClients clients = new KafkaClientsBuilder()
            .withProducerName(testStorage.getProducerName())
            .withConsumerName(testStorage.getConsumerName())
            .withBootstrapAddress(KafkaResources.tlsBootstrapAddress(testStorage.getSourceClusterName()))
            .withNamespaceName(testStorage.getNamespaceName())
            .withUsername(testStorage.getSourceUsername())
            .withTopicName(testStorage.getTopicName())
            .withMessageCount(testStorage.getMessageCount())
            .build();

        resourceManager.createResourceWithWait(extensionContext, clients.producerScramShaTlsStrimzi(testStorage.getSourceClusterName()), clients.consumerScramShaTlsStrimzi(testStorage.getSourceClusterName()));
        ClientUtils.waitForClientsSuccess(testStorage);

        // Deploy MirrorMaker with TLS and ScramSha512
        resourceManager.createResourceWithWait(extensionContext, KafkaMirrorMakerTemplates.kafkaMirrorMaker(testStorage.getClusterName(), testStorage.getSourceClusterName(), testStorage.getTargetClusterName(), ClientUtils.generateRandomConsumerGroup(), 1, true)
            .editSpec()
                .editConsumer()
                    .withNewKafkaClientAuthenticationScramSha512()
                        .withUsername(testStorage.getSourceUsername())
                        .withPasswordSecret(passwordSecretSource)
                    .endKafkaClientAuthenticationScramSha512()
                    .withNewTls()
                        .withTrustedCertificates(certSecretSource)
                    .endTls()
                .endConsumer()
                .editProducer()
                    .withNewKafkaClientAuthenticationScramSha512()
                        .withUsername(testStorage.getTargetUsername())
                        .withPasswordSecret(passwordSecretTarget)
                    .endKafkaClientAuthenticationScramSha512()
                    .withNewTls()
                        .withTrustedCertificates(certSecretTarget)
                    .endTls()
                .endProducer()
            .endSpec()
            .build());

        clients = new KafkaClientsBuilder(clients)
            .withBootstrapAddress(KafkaResources.tlsBootstrapAddress(testStorage.getTargetClusterName()))
            .withUsername(testStorage.getTargetUsername())
            .build();

        resourceManager.createResourceWithWait(extensionContext, clients.consumerScramShaTlsStrimzi(testStorage.getTargetClusterName()));
        ClientUtils.waitForConsumerClientSuccess(testStorage);
    }

    @ParallelNamespaceTest
    void testIncludeList(ExtensionContext extensionContext) throws UnexpectedException {
        final TestStorage testStorage = new TestStorage(extensionContext, Environment.TEST_SUITE_NAMESPACE);

        String topicName = "included-topic";
        String topicNotIncluded = "not-included-topic";

        resourceManager.createResourceWithWait(extensionContext,
            KafkaTemplates.kafkaEphemeral(testStorage.getSourceClusterName(), 1, 1).build(),
            KafkaTemplates.kafkaEphemeral(testStorage.getTargetClusterName(), 1, 1).build()
        );

        resourceManager.createResourceWithWait(extensionContext,
            KafkaTopicTemplates.topic(testStorage.getSourceClusterName(), topicName, testStorage.getNamespaceName()).build(),
            KafkaTopicTemplates.topic(testStorage.getSourceClusterName(), topicNotIncluded, testStorage.getNamespaceName()).build()
        );

        KafkaClients clients = new KafkaClientsBuilder()
            .withProducerName(testStorage.getProducerName())
            .withConsumerName(testStorage.getConsumerName())
            .withBootstrapAddress(KafkaResources.plainBootstrapAddress(testStorage.getSourceClusterName()))
            .withNamespaceName(testStorage.getNamespaceName())
            .withTopicName(topicName)
            .withMessageCount(testStorage.getMessageCount())
            .build();

        resourceManager.createResourceWithWait(extensionContext, clients.producerStrimzi(), clients.consumerStrimzi());
        ClientUtils.waitForClientsSuccess(testStorage);

        clients = new KafkaClientsBuilder(clients)
            .withTopicName(topicNotIncluded)
            .build();

        resourceManager.createResourceWithWait(extensionContext, clients.producerStrimzi(), clients.consumerStrimzi());
        ClientUtils.waitForClientsSuccess(testStorage);

        resourceManager.createResourceWithWait(extensionContext, KafkaMirrorMakerTemplates.kafkaMirrorMaker(testStorage.getClusterName(), testStorage.getSourceClusterName(), testStorage.getTargetClusterName(), ClientUtils.generateRandomConsumerGroup(), 1, false)
            .editMetadata()
                .withNamespace(testStorage.getNamespaceName())
            .endMetadata()
            .editSpec()
                .withInclude(topicName)
            .endSpec()
            .build());

        clients = new KafkaClientsBuilder(clients)
            .withTopicName(topicName)
            .withBootstrapAddress(KafkaResources.plainBootstrapAddress(testStorage.getTargetClusterName()))
            .build();

        resourceManager.createResourceWithWait(extensionContext, clients.consumerStrimzi());
        ClientUtils.waitForConsumerClientSuccess(testStorage);

        clients = new KafkaClientsBuilder(clients)
            .withTopicName(topicNotIncluded)
            .build();

        LOGGER.info("Becuase {} is not included, we should not receive any message", topicNotIncluded);

        resourceManager.createResourceWithWait(extensionContext, clients.consumerStrimzi());
        ClientUtils.waitForConsumerClientTimeout(testStorage);
    }

    @ParallelNamespaceTest
    void testCustomAndUpdatedValues(ExtensionContext extensionContext) {
        final TestStorage testStorage = storageMap.get(extensionContext);

        resourceManager.createResourceWithWait(extensionContext, KafkaTemplates.kafkaEphemeral(testStorage.getClusterName(), 1, 1)
            .editSpec()
                .withNewEntityOperator()
                .endEntityOperator()
            .endSpec()
            .build());

        String usedVariable = "KAFKA_MIRRORMAKER_CONFIGURATION_PRODUCER";

        LinkedHashMap<String, String> envVarGeneral = new LinkedHashMap<>();
        envVarGeneral.put("TEST_ENV_1", "test.env.one");
        envVarGeneral.put("TEST_ENV_2", "test.env.two");
        envVarGeneral.put(usedVariable, "test.value");

        LinkedHashMap<String, String> envVarUpdated = new LinkedHashMap<>();
        envVarUpdated.put("TEST_ENV_2", "updated.test.env.two");
        envVarUpdated.put("TEST_ENV_3", "test.env.three");

        Map<String, Object> producerConfig = new HashMap<>();
        producerConfig.put("acks", "all");

        Map<String, Object> updatedProducerConfig = new HashMap<>();
        updatedProducerConfig.put("acks", "0");

        Map<String, Object> consumerConfig = new HashMap<>();
        consumerConfig.put("auto.offset.reset", "latest");

        Map<String, Object> updatedConsumerConfig = new HashMap<>();
        updatedConsumerConfig.put("auto.offset.reset", "earliest");

        int initialDelaySeconds = 30;
        int timeoutSeconds = 10;
        int updatedInitialDelaySeconds = 31;
        int updatedTimeoutSeconds = 11;
        int periodSeconds = 10;
        int successThreshold = 1;
        int failureThreshold = 3;
        int updatedPeriodSeconds = 5;
        int updatedFailureThreshold = 1;

        resourceManager.createResourceWithWait(extensionContext, KafkaMirrorMakerTemplates.kafkaMirrorMaker(testStorage.getClusterName(), testStorage.getClusterName(), testStorage.getClusterName(), ClientUtils.generateRandomConsumerGroup(), 1, false)
            .editSpec()
                .editProducer()
                    .withConfig(producerConfig)
                .endProducer()
                .editConsumer()
                    .withConfig(consumerConfig)
                .endConsumer()
                .withNewTemplate()
                    .withNewMirrorMakerContainer()
                        .withEnv(StUtils.createContainerEnvVarsFromMap(envVarGeneral))
                    .endMirrorMakerContainer()
                .endTemplate()
                .withNewReadinessProbe()
                    .withInitialDelaySeconds(initialDelaySeconds)
                    .withTimeoutSeconds(timeoutSeconds)
                    .withPeriodSeconds(periodSeconds)
                    .withSuccessThreshold(successThreshold)
                    .withFailureThreshold(failureThreshold)
                .endReadinessProbe()
                .withNewLivenessProbe()
                    .withInitialDelaySeconds(initialDelaySeconds)
                    .withTimeoutSeconds(timeoutSeconds)
                    .withPeriodSeconds(periodSeconds)
                    .withSuccessThreshold(successThreshold)
                    .withFailureThreshold(failureThreshold)
                .endLivenessProbe()
            .endSpec()
            .build());

        Map<String, String> mirrorMakerSnapshot = DeploymentUtils.depSnapshot(testStorage.getNamespaceName(), KafkaMirrorMakerResources.deploymentName(testStorage.getClusterName()));

        // Remove variable which is already in use
        envVarGeneral.remove(usedVariable);
        LOGGER.info("Verifying values before update");
        checkReadinessLivenessProbe(testStorage.getNamespaceName(), KafkaMirrorMakerResources.deploymentName(testStorage.getClusterName()),
            KafkaMirrorMakerResources.deploymentName(testStorage.getClusterName()), initialDelaySeconds, timeoutSeconds, periodSeconds,
            successThreshold, failureThreshold);
        checkSpecificVariablesInContainer(testStorage.getNamespaceName(), KafkaMirrorMakerResources.deploymentName(testStorage.getClusterName()),
            KafkaMirrorMakerResources.deploymentName(testStorage.getClusterName()), envVarGeneral);
        checkComponentConfiguration(testStorage.getNamespaceName(), KafkaMirrorMakerResources.deploymentName(testStorage.getClusterName()),
            KafkaMirrorMakerResources.deploymentName(testStorage.getClusterName()), "KAFKA_MIRRORMAKER_CONFIGURATION_PRODUCER", producerConfig);
        checkComponentConfiguration(testStorage.getNamespaceName(), KafkaMirrorMakerResources.deploymentName(testStorage.getClusterName()),
            KafkaMirrorMakerResources.deploymentName(testStorage.getClusterName()), "KAFKA_MIRRORMAKER_CONFIGURATION_CONSUMER", consumerConfig);

        LOGGER.info("Check if actual env variable {} has different value than {}", usedVariable, "test.value");
        assertThat(StUtils.checkEnvVarInPod(testStorage.getNamespaceName(), kubeClient().listPods(testStorage.getNamespaceName(), testStorage.getClusterName(), Labels.STRIMZI_KIND_LABEL,
            KafkaMirrorMaker.RESOURCE_KIND).get(0).getMetadata().getName(), usedVariable), CoreMatchers.is(not("test.value")));

        LOGGER.info("Updating values in MirrorMaker container");
        KafkaMirrorMakerResource.replaceMirrorMakerResourceInSpecificNamespace(testStorage.getClusterName(), kmm -> {
            kmm.getSpec().getTemplate().getMirrorMakerContainer().setEnv(StUtils.createContainerEnvVarsFromMap(envVarUpdated));
            kmm.getSpec().getProducer().setConfig(updatedProducerConfig);
            kmm.getSpec().getConsumer().setConfig(updatedConsumerConfig);
            kmm.getSpec().getLivenessProbe().setInitialDelaySeconds(updatedInitialDelaySeconds);
            kmm.getSpec().getReadinessProbe().setInitialDelaySeconds(updatedInitialDelaySeconds);
            kmm.getSpec().getLivenessProbe().setTimeoutSeconds(updatedTimeoutSeconds);
            kmm.getSpec().getReadinessProbe().setTimeoutSeconds(updatedTimeoutSeconds);
            kmm.getSpec().getLivenessProbe().setPeriodSeconds(updatedPeriodSeconds);
            kmm.getSpec().getReadinessProbe().setPeriodSeconds(updatedPeriodSeconds);
            kmm.getSpec().getLivenessProbe().setFailureThreshold(updatedFailureThreshold);
            kmm.getSpec().getReadinessProbe().setFailureThreshold(updatedFailureThreshold);
        }, testStorage.getNamespaceName());

        DeploymentUtils.waitTillDepHasRolled(testStorage.getNamespaceName(), KafkaMirrorMakerResources.deploymentName(testStorage.getClusterName()), 1, mirrorMakerSnapshot);

        LOGGER.info("Verifying values after update");
        checkReadinessLivenessProbe(testStorage.getNamespaceName(), KafkaMirrorMakerResources.deploymentName(testStorage.getClusterName()),
            KafkaMirrorMakerResources.deploymentName(testStorage.getClusterName()), updatedInitialDelaySeconds, updatedTimeoutSeconds,
                updatedPeriodSeconds, successThreshold, updatedFailureThreshold);
        checkSpecificVariablesInContainer(testStorage.getNamespaceName(), KafkaMirrorMakerResources.deploymentName(testStorage.getClusterName()),
            KafkaMirrorMakerResources.deploymentName(testStorage.getClusterName()), envVarUpdated);
        checkComponentConfiguration(testStorage.getNamespaceName(), KafkaMirrorMakerResources.deploymentName(testStorage.getClusterName()),
            KafkaMirrorMakerResources.deploymentName(testStorage.getClusterName()), "KAFKA_MIRRORMAKER_CONFIGURATION_PRODUCER", updatedProducerConfig);
        checkComponentConfiguration(testStorage.getNamespaceName(), KafkaMirrorMakerResources.deploymentName(testStorage.getClusterName()),
            KafkaMirrorMakerResources.deploymentName(testStorage.getClusterName()), "KAFKA_MIRRORMAKER_CONFIGURATION_CONSUMER", updatedConsumerConfig);
    }

    @ParallelNamespaceTest
    @Tag(COMPONENT_SCALING)
    void testScaleMirrorMakerUpAndDownToZero(ExtensionContext extensionContext) {
        final TestStorage testStorage = new TestStorage(extensionContext, Environment.TEST_SUITE_NAMESPACE);

        resourceManager.createResourceWithWait(extensionContext,
            KafkaTemplates.kafkaEphemeral(testStorage.getSourceClusterName(), 1, 1).build(),
            KafkaTemplates.kafkaEphemeral(testStorage.getTargetClusterName(), 1, 1).build()
        );

        resourceManager.createResourceWithWait(extensionContext, KafkaMirrorMakerTemplates.kafkaMirrorMaker(testStorage.getClusterName(), testStorage.getTargetClusterName(), testStorage.getSourceClusterName(), ClientUtils.generateRandomConsumerGroup(), 1, false).build());

        int scaleTo = 2;
        long mmObsGen = KafkaMirrorMakerResource.kafkaMirrorMakerClient().inNamespace(testStorage.getNamespaceName()).withName(testStorage.getClusterName()).get().getStatus().getObservedGeneration();
        String mmDepName = KafkaMirrorMakerResources.deploymentName(testStorage.getClusterName());
        String mmGenName = kubeClient().listPods(testStorage.getNamespaceName(), testStorage.getClusterName(), Labels.STRIMZI_KIND_LABEL, KafkaMirrorMaker.RESOURCE_KIND).get(0).getMetadata().getGenerateName();

        LOGGER.info("-------> Scaling KafkaMirrorMaker up <-------");

        LOGGER.info("Scaling subresource replicas to {}", scaleTo);
        cmdKubeClient().namespace(testStorage.getNamespaceName()).scaleByName(KafkaMirrorMaker.RESOURCE_KIND, testStorage.getClusterName(), scaleTo);
        DeploymentUtils.waitForDeploymentAndPodsReady(testStorage.getNamespaceName(), KafkaMirrorMakerResources.deploymentName(testStorage.getClusterName()), scaleTo);

        LOGGER.info("Check if replicas is set to {}, naming prefix should be same and observed generation higher", scaleTo);

        StUtils.waitUntilSupplierIsSatisfied(() -> kubeClient().listPodNames(testStorage.getNamespaceName(), testStorage.getClusterName(), Labels.STRIMZI_KIND_LABEL, KafkaMirrorMaker.RESOURCE_KIND).size() == scaleTo &&
            KafkaMirrorMakerResource.kafkaMirrorMakerClient().inNamespace(testStorage.getNamespaceName()).withName(testStorage.getClusterName()).get().getSpec().getReplicas() == scaleTo &&
            KafkaMirrorMakerResource.kafkaMirrorMakerClient().inNamespace(testStorage.getNamespaceName()).withName(testStorage.getClusterName()).get().getStatus().getReplicas() == scaleTo);

        /*
        observed generation should be higher than before scaling -> after change of spec and successful reconciliation,
        the observed generation is increased
        */
        long actualObsGen = KafkaMirrorMakerResource.kafkaMirrorMakerClient().inNamespace(testStorage.getNamespaceName()).withName(testStorage.getClusterName()).get().getStatus().getObservedGeneration();
        assertTrue(mmObsGen < actualObsGen);

        List<String> mmPods = kubeClient().listPodNames(testStorage.getNamespaceName(), testStorage.getClusterName(), Labels.STRIMZI_KIND_LABEL, KafkaMirrorMaker.RESOURCE_KIND);

        for (String pod : mmPods) {
            assertTrue(pod.contains(mmGenName));
        }

        mmObsGen = actualObsGen;

        LOGGER.info("-------> Scaling KafkaMirrorMaker down <-------");

        LOGGER.info("Scaling MirrorMaker to zero");
        KafkaMirrorMakerResource.replaceMirrorMakerResourceInSpecificNamespace(testStorage.getClusterName(), mm -> mm.getSpec().setReplicas(0), testStorage.getNamespaceName());

        PodUtils.waitForPodsReady(testStorage.getNamespaceName(), kubeClient().getDeploymentSelectors(testStorage.getNamespaceName(), mmDepName), 0, true);

        mmPods = kubeClient().listPodNames(testStorage.getClusterName(), Labels.STRIMZI_KIND_LABEL, KafkaMirrorMaker.RESOURCE_KIND);
        KafkaMirrorMakerStatus mmStatus = KafkaMirrorMakerResource.kafkaMirrorMakerClient().inNamespace(testStorage.getNamespaceName()).withName(testStorage.getClusterName()).get().getStatus();
        actualObsGen = KafkaMirrorMakerResource.kafkaMirrorMakerClient().inNamespace(testStorage.getNamespaceName()).withName(testStorage.getClusterName()).get().getStatus().getObservedGeneration();

        assertThat(mmPods.size(), is(0));
        assertThat(mmStatus.getConditions().get(0).getType(), is(Ready.toString()));
        assertThat(actualObsGen, is(not(mmObsGen)));

    }

    @ParallelNamespaceTest
    void testConfigureDeploymentStrategy(ExtensionContext extensionContext) {
        final TestStorage testStorage = storageMap.get(extensionContext);
        final String namespaceName = StUtils.getNamespaceBasedOnRbac(Environment.TEST_SUITE_NAMESPACE, extensionContext);

        // Deploy source kafka
        resourceManager.createResourceWithWait(extensionContext, KafkaTemplates.kafkaEphemeral(testStorage.getSourceClusterName(), 1, 1).build());
        // Deploy target kafka
        resourceManager.createResourceWithWait(extensionContext, KafkaTemplates.kafkaEphemeral(testStorage.getTargetClusterName(), 1, 1).build());

        resourceManager.createResourceWithWait(extensionContext, KafkaMirrorMakerTemplates.kafkaMirrorMaker(testStorage.getClusterName(), testStorage.getTargetClusterName(), testStorage.getSourceClusterName(), ClientUtils.generateRandomConsumerGroup(), 1, false)
            .editSpec()
                .editOrNewTemplate()
                    .editOrNewDeployment()
                        .withDeploymentStrategy(DeploymentStrategy.RECREATE)
                    .endDeployment()
                .endTemplate()
            .endSpec()
            .build());

        String mmDepName = KafkaMirrorMakerResources.deploymentName(testStorage.getClusterName());

        LOGGER.info("Adding label to MirrorMaker resource, the CR should be recreateAndWaitForReadinessd");
        KafkaMirrorMakerResource.replaceMirrorMakerResourceInSpecificNamespace(testStorage.getClusterName(),
            mm -> mm.getMetadata().setLabels(Collections.singletonMap("some", "label")), namespaceName);
        DeploymentUtils.waitForDeploymentAndPodsReady(testStorage.getNamespaceName(), mmDepName, 1);

        KafkaMirrorMaker kmm = KafkaMirrorMakerResource.kafkaMirrorMakerClient().inNamespace(testStorage.getNamespaceName()).withName(testStorage.getClusterName()).get();

        LOGGER.info("Checking that observed gen. is still on 1 (recreation) and new label is present");
        assertThat(kmm.getStatus().getObservedGeneration(), is(1L));
        assertThat(kmm.getMetadata().getLabels().toString(), Matchers.containsString("some=label"));
        assertThat(kmm.getSpec().getTemplate().getDeployment().getDeploymentStrategy(), is(DeploymentStrategy.RECREATE));

        LOGGER.info("Changing Deployment strategy to {}", DeploymentStrategy.ROLLING_UPDATE);
        KafkaMirrorMakerResource.replaceMirrorMakerResourceInSpecificNamespace(testStorage.getClusterName(),
            mm -> mm.getSpec().getTemplate().getDeployment().setDeploymentStrategy(DeploymentStrategy.ROLLING_UPDATE), namespaceName);
        KafkaMirrorMakerUtils.waitForKafkaMirrorMakerReady(testStorage.getNamespaceName(), testStorage.getClusterName());

        LOGGER.info("Adding another label to MirrorMaker resource, Pods should be rolled");
        KafkaMirrorMakerResource.replaceMirrorMakerResourceInSpecificNamespace(testStorage.getClusterName(), mm -> mm.getMetadata().getLabels().put("another", "label"), namespaceName);
        DeploymentUtils.waitForDeploymentAndPodsReady(testStorage.getNamespaceName(), mmDepName, 1);

        LOGGER.info("Checking that observed gen. higher (rolling update) and label is changed");
        StUtils.waitUntilSupplierIsSatisfied(
            () -> {
                final KafkaMirrorMaker kMM = KafkaMirrorMakerResource.kafkaMirrorMakerClient().inNamespace(testStorage.getNamespaceName()).withName(testStorage.getClusterName()).get();

                return kMM.getStatus().getObservedGeneration() == 2L &&
                        kMM.getMetadata().getLabels().toString().contains("another=label") &&
                        kMM.getSpec().getTemplate().getDeployment().getDeploymentStrategy().equals(DeploymentStrategy.ROLLING_UPDATE);
            }
        );
    }

    @BeforeAll
    void setupEnvironment(ExtensionContext extensionContext) {
        clusterOperator = clusterOperator.defaultInstallation(extensionContext)
            .withOperationTimeout(TestConstants.CO_OPERATION_TIMEOUT_MEDIUM)
            .createInstallation()
            .runInstallation();
    }
}
