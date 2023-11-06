package io.provenance.p8e.plugin

import com.google.protobuf.ByteString
import com.google.protobuf.Message
import io.grpc.ManagedChannelBuilder
import io.provenance.client.grpc.BaseReqSigner
import io.provenance.client.grpc.ChannelOpts
import io.provenance.client.grpc.createChannel
import io.provenance.metadata.v1.ContractSpecification
import io.provenance.metadata.v1.ContractSpecificationRequest
import io.provenance.metadata.v1.DefinitionType
import io.provenance.metadata.v1.Description
import io.provenance.metadata.v1.InputSpecification
import io.provenance.metadata.v1.MsgAddContractSpecToScopeSpecRequest
import io.provenance.metadata.v1.MsgWriteContractSpecificationRequest
import io.provenance.metadata.v1.MsgWriteRecordSpecificationRequest
import io.provenance.metadata.v1.MsgWriteScopeSpecificationRequest
import io.provenance.metadata.v1.RecordSpecification
import io.provenance.metadata.v1.ScopeSpecification
import io.provenance.metadata.v1.ScopeSpecificationRequest
import io.provenance.scope.contract.annotations.ScopeSpecificationDefinition
import io.provenance.scope.contract.proto.Commons.ProvenanceReference
import io.provenance.scope.contract.proto.Specifications.ContractSpec
import io.provenance.scope.contract.proto.Specifications.PartyType
import io.provenance.scope.encryption.ecies.ECUtils
import io.provenance.scope.encryption.model.DirectKeyRef
import io.provenance.scope.encryption.util.ByteUtil
import io.provenance.scope.objectstore.client.ObjectHash
import io.provenance.scope.objectstore.util.base64EncodeString
import io.provenance.scope.objectstore.util.sha256LoBytes
import io.provenance.scope.objectstore.util.toUuid
import io.provenance.scope.proto.PK
import io.provenance.scope.sdk.Affiliate
import io.provenance.scope.sdk.Client
import io.provenance.scope.sdk.ClientConfig
import io.provenance.scope.sdk.ContractSpecMapper
import io.provenance.scope.sdk.SharedClient
import io.provenance.scope.util.MetadataAddress
import io.provenance.scope.util.crypto.Hash
import io.provenance.scope.util.crypto.toBech32Data
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPrivateKey
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPublicKey
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jce.spec.ECPrivateKeySpec
import org.bouncycastle.jce.spec.ECPublicKeySpec
import org.bouncycastle.util.encoders.Hex
import org.gradle.api.GradleException
import org.gradle.api.Project
import java.io.File
import java.io.FileInputStream
import java.net.URI
import java.net.URLClassLoader
import java.security.KeyFactory
import java.security.KeyPair
import java.security.PublicKey
import java.security.Security
import java.util.UUID
import java.util.concurrent.TimeUnit
import io.provenance.scope.contract.annotations.ScopeSpecification as ScopeSpecificationReference

fun ContractSpec.uuid(): UUID = toByteArray().sha256LoBytes().toUuid()
fun ContractSpec.hashString(): String = toByteArray().sha256LoBytes().base64EncodeString()

fun ObjectHash.toReference(): ProvenanceReference {
    return ProvenanceReference.newBuilder()
        .setHash(this.value)
        .build()
}

fun getAddress(publicKey: PublicKey, mainNet: Boolean): String {
    val bytes = (publicKey as BCECPublicKey).q.getEncoded(true)
    val prefix = if (mainNet) "pb" else "tp"

    return Hash.sha256hash160(bytes).toBech32Data(prefix).address
}

internal class Bootstrapper(
    private val project: Project,
    private val extension: P8eExtension
) {

    init {
        Security.addProvider(BouncyCastleProvider())
    }

    private fun validate() {
        extension.locations.forEach { name, location ->
            require(!location.encryptionPrivateKey.isNullOrBlank()) { "encryptionPrivateKey is required for location $name" }
            require(!location.signingPrivateKey.isNullOrBlank()) { "signingPrivateKey is required for location $name" }
            require(!location.osUrl.isNullOrBlank()) { "object-store url is required for location $name" }
            require(!location.provenanceUrl.isNullOrBlank()) { "provenance url is required for location $name" }
            require(!location.chainId.isNullOrBlank()) { "chainId is required for location $name" }
            require(location.txBatchSize.isNotBlank()) { "txBatchSize is required for location $name" }

            try {
                location.txBatchSize.toInt()
            } catch (e: Exception) {
                throw IllegalStateException("txBatchSize must be a valid int32 for location $name")
            }

            try {
                location.txFeeAdjustment.toDouble()
            } catch (e: Exception) {
                throw IllegalStateException("txFeeAdjustment must be a valid double for location $name")
            }
        }

        // TODO: add additional validation checks that configuration is good enough to publish
    }

    @Synchronized
    fun execute() {
        validate()

        val hashes = mutableMapOf<String, String>()
        val contractKey = "contractKey"
        val protoKey = "protoKey"
        val contractProject = getProject(project, extension.contractProject)
        val protoProject = getProject(project, extension.protoProject)
        val contractJar = getJar(contractProject, "shadowJar")
        val protoJar = getJar(protoProject)
        val contractClassLoader = URLClassLoader(arrayOf(contractJar.toURI().toURL()), javaClass.classLoader)
        val contracts = findContracts(contractClassLoader, extension.includePackages)
        val scopes = findScopes(contractClassLoader, extension.includePackages)
        val protos = findProtos(contractClassLoader, extension.includePackages)

        var errored = false

        extension.locations.forEach { name, location ->
            project.logger.info("Publishing contracts - location: $name object-store url: ${location.osUrl} provenance url: ${location.provenanceUrl}")

            val config = ClientConfig(
                cacheJarSizeInBytes = 0L,
                cacheSpecSizeInBytes = 0L,
                cacheRecordSizeInBytes = 0L,
                osGrpcUrl = URI(location.osUrl!!),
                osGrpcDeadlineMs = 60 * 1_000L,
                mainNet = location.mainNet,
                extraHeaders = location.osHeaders,
            )

            val encryptionKeyPair = getKeyPair(location.encryptionPrivateKey!!)
            val signingKeyPair = getKeyPair(location.signingPrivateKey!!)
            val pbSigner = BaseReqSigner(JavaKeyPairSigner(signingKeyPair, config.mainNet))
            val pbAddress = getAddress(signingKeyPair.public, config.mainNet)
            val affiliate = Affiliate(
                signingKeyRef = DirectKeyRef(signingKeyPair.public, signingKeyPair.private),
                encryptionKeyRef = DirectKeyRef(encryptionKeyPair.public, encryptionKeyPair.private),
                PartyType.OWNER,
            )
            val sdk = Client(SharedClient(config), affiliate)
            val provenanceUri = URI(location.provenanceUrl!!)
            val channel = createChannel(provenanceUri, ChannelOpts()) { }
            val client = ProvenanceClient(channel, project.logger, location)

            try {
                val contractJarLocation = storeObject(sdk, contractJar, location)
                    .also {
                        require(it.value == hashes.getOrDefault(contractKey, it.value)) {
                            "Received different hash for the same contract jar ${it.value} ${hashes.getValue(contractKey)}"
                        }

                        hashes[contractKey] = it.value
                    }
                val protoJarLocation = storeObject(sdk, protoJar, location)
                    .also {
                        require(it.value == hashes.getOrDefault(protoKey, it.value)) {
                            "Received different hash for the same proto jar ${it.value} ${hashes.getValue(protoKey)}"
                        }

                        hashes[protoKey] = it.value
                    }

                val scopeSpecificationNameToUuid = mutableMapOf<String, UUID>()

                // write brand new scope specifications
                scopes.map { clazz ->
                    val definition = clazz.annotations
                        .filterIsInstance<ScopeSpecificationDefinition>()
                        .first()
                    val id = MetadataAddress.forScopeSpecification(UUID.fromString(definition.uuid)).bytes

                    scopeSpecificationNameToUuid[definition.name] = UUID.fromString(definition.uuid)

                    ScopeSpecification.newBuilder()
                        .setSpecificationId(ByteString.copyFrom(id))
                        .setDescription(Description.newBuilder()
                            .setName(definition.name)
                            .setDescription(definition.description)
                            .also { builder ->
                                definition.websiteUrl.takeIf { it.isNotBlank() }?.run { builder.websiteUrl = this }
                            }
                            .also { builder ->
                                definition.iconUrl.takeIf { it.isNotBlank() }?.run { builder.iconUrl = this }
                            }
                            .build()
                        )
                        .addOwnerAddresses(pbAddress)
                        .addAllPartiesInvolvedValue(definition.partiesInvolved.map { it.valueDescriptor.number })
                        .build()
                }.filter { spec ->
                    val uuid = MetadataAddress.fromBytes(spec.specificationId.toByteArray()).getPrimaryUuid()

                    val existingSpec = client.scopeSpecification(
                        ScopeSpecificationRequest.newBuilder()
                            .setSpecificationId(uuid.toString())
                            .build()
                    )

                    // at this time we only post scope specifications when it doesn't already exist
                    existingSpec.scopeSpecification.specification.description.name.isEmpty()
                }.also { specs ->
                    project.logger.info("Adding ${specs.size} scope specification(s) to batch for provenance")

                    if (specs.isNotEmpty()) {
                        specs.map { spec ->
                            MsgWriteScopeSpecificationRequest.newBuilder()
                                .addSigners(pbAddress)
                                .setSpecification(spec)
                                .build()
                        }.chunked(location.txBatchSize.toInt()).forEach { batch ->
                            try {
                                client.writeTx(pbSigner, batch.toTxBody())
                            } catch (e: Exception) {
                                project.logger.info("sent messages = $batch")
                                throw e
                            }
                        }
                    }
                }

                val scopeSpecificationUuidToContractSpecUuids = mutableMapOf<UUID, List<UUID>>()

                val contractSpecifications = contracts.map { contract ->
                    val scopeSpecificationNames = contract.annotations
                        .filterIsInstance<ScopeSpecificationReference>()
                        .first()

                    ContractSpecMapper.dehydrateSpec(
                        contract.kotlin,
                        contractJarLocation.toReference(),
                        protoJarLocation.toReference(),
                    ).also { contractSpec ->
                        scopeSpecificationNames.names.forEach { name ->
                            val uuid = scopeSpecificationNameToUuid.getValue(name)

                            scopeSpecificationUuidToContractSpecUuids.compute(uuid) { _, value: List<UUID>? ->
                                value?.plus(contractSpec.uuid()) ?: listOf(contractSpec.uuid())
                            }
                        }
                    }
                }.also { specs ->
                    project.logger.info("Adding ${specs.size} contract specification(s) to object-store")

                    // TODO move to async and do more than one at a time eventually
                    specs.forEach { spec -> storeObject(sdk, spec, location) }
                }

                // write brand new contract specifications
                contractSpecifications.map { spec ->
                    val existingSpec = client.contractSpecification(
                        ContractSpecificationRequest.newBuilder()
                            .setSpecificationId(spec.uuid().toString())
                            .setIncludeRecordSpecs(true)
                            .build()
                    )
                    // at this time we only post contract specifications when it doesn't already exist
                    // but we can still write record specifications for them
                    val specDoesNotExist = existingSpec.contractSpecification.specification.className.isEmpty()

                    val recordSpecifications = mutableListOf<RecordSpecification>()
                    val id = MetadataAddress.forContractSpecification(spec.uuid()).bytes

                    val contractSpecification = if (specDoesNotExist) {
                        ContractSpecification.newBuilder()
                            .setSpecificationId(ByteString.copyFrom(id))
                            .setDescription(
                                Description.newBuilder()
                                    .setDescription(spec.definition.resourceLocation.classname)
                                    .setName(spec.definition.name)
                                    .build()
                            )
                            .addOwnerAddresses(pbAddress)
                            .addAllPartiesInvolvedValue(spec.partiesInvolvedValueList)
                            .setHash(spec.hashString())
                            .setClassName(spec.definition.resourceLocation.classname)
                            .build()
                    } else null

                    val existingRecordSpecs = existingSpec.recordSpecificationsList.map { it.specification.name }.toHashSet()

                    spec.functionSpecsList.filterNot { functionSpec ->
                        // filter out specifications that already exist
                        existingRecordSpecs.contains(functionSpec.outputSpec.spec.name)
                    }.forEach { functionSpec ->
                        val recordSpecId =
                            MetadataAddress.forRecordSpecification(spec.uuid(), functionSpec.outputSpec.spec.name).bytes

                        val recordSpec = RecordSpecification.newBuilder()
                            .setSpecificationId(ByteString.copyFrom(recordSpecId))
                            .setName(functionSpec.outputSpec.spec.name)
                            .addAllInputs(functionSpec.inputSpecsList.map { inputSpec ->
                                InputSpecification.newBuilder()
                                    .setName(inputSpec.name)
                                    .setTypeName(inputSpec.resourceLocation.classname)
                                    .setHash(inputSpec.resourceLocation.ref.hash)
                                    .build()
                            })
                            .setTypeName(functionSpec.outputSpec.spec.resourceLocation.classname)
                            .setResultType(DefinitionType.DEFINITION_TYPE_PROPOSED)
                            .addResponsiblePartiesValue(functionSpec.invokerPartyValue)
                            .build()

                        recordSpecifications.add(recordSpec)
                    }

                    Pair(contractSpecification, recordSpecifications.toList())
                }.also { specs ->
                    val (numContractSpecsToAdd, numRecordSpecsToAdd) = specs.fold(0 to 0) { acc, curr ->
                        acc.first + (curr.first?.let { 1 } ?: 0) to acc.second + curr.second.size
                    }
                    project.logger.info("Adding $numContractSpecsToAdd contract specification(s) and $numRecordSpecsToAdd record specification(s) to batch for provenance")

                    val messages = mutableListOf<Message>()

                    specs.map { (contractSpecification, recordSpecifications) ->
                        if (contractSpecification != null) {
                            messages.add(
                                MsgWriteContractSpecificationRequest.newBuilder()
                                    .addSigners(pbAddress)
                                    .setSpecification(contractSpecification)
                                    .build()
                            )
                        }

                        messages.addAll(recordSpecifications.map { spec ->
                            val contractSpecAddress = MetadataAddress.fromBytes(spec.specificationId.toByteArray())

                            MsgWriteRecordSpecificationRequest.newBuilder()
                                .addSigners(pbAddress)
                                .setSpecification(spec)
                                .setContractSpecUuid(contractSpecAddress.getPrimaryUuid().toString())
                                .build()
                        })
                    }

                    messages.chunked(location.txBatchSize.toInt()).forEach { batch ->
                        try {
                            client.writeTx(pbSigner, batch.toTxBody())
                        } catch (e: Exception) {
                            project.logger.info("sent messages = $batch")
                            throw e
                        }
                    }
                }

                // write contract specification ids to scope specifications they are missing from
                scopes.flatMap { clazz ->
                    val definition = clazz.annotations
                        .filterIsInstance<ScopeSpecificationDefinition>()
                        .first()
                    val desiredContractSpecIds = scopeSpecificationUuidToContractSpecUuids
                        .getValue(UUID.fromString(definition.uuid))
                        .map { MetadataAddress.forContractSpecification(it) }
                        .map { ByteString.copyFrom(it.bytes) }
                    val response = client.scopeSpecification(
                        ScopeSpecificationRequest.newBuilder()
                            .setSpecificationId(definition.uuid)
                            .build()
                    )
                    val existingContractSpecIdsSet = response.scopeSpecification.specification.contractSpecIdsList.toSet()

                    desiredContractSpecIds.filterNot(existingContractSpecIdsSet::contains)
                        .map { contractSpecId ->
                            MsgAddContractSpecToScopeSpecRequest.newBuilder()
                                .setScopeSpecificationId(response.scopeSpecification.specification.specificationId)
                                .setContractSpecificationId(contractSpecId)
                                .addSigners(pbAddress)
                                .build()
                        }
                }.also { contractSpecToScopeSpecMessages ->
                    project.logger.info("Adding ${contractSpecToScopeSpecMessages.size} contract specification(s) to provenance for existing scope specification id(s)")

                    contractSpecToScopeSpecMessages.chunked(location.txBatchSize.toInt()).forEach { batch ->
                        try {
                            client.writeTx(pbSigner, batch.toTxBody())
                        } catch (e: Exception) {
                            project.logger.info("sent messages = $batch")
                            throw e
                        }
                    }
                }
            } catch (e: Exception) {
                project.logger.error("", e)
                errored = true
            } finally {
                channel.shutdown()
                if (!channel.awaitTermination(10, TimeUnit.SECONDS)) {
                    project.logger.warn("Could not shutdown ManagedChannel for ${location.provenanceUrl} cleanly!")
                }

                sdk.inner.close()
                if (!sdk.inner.awaitTermination(10, TimeUnit.SECONDS)) {
                    project.logger.warn("Could not shutdown ManagedChannel for ${location.provenanceUrl} cleanly!")
                }
            }
        }

        if (errored) {
            throw GradleException("Bootstrap FAILED!")
        } else if(hashes.isEmpty()) {
            project.logger.warn("No p8e locations were detected!")
        } else {
            project.logger.info("Writing services providers")

            val currentTimeMillis = System.currentTimeMillis().toString()
            ServiceProvider.writeContractHash(contractProject, extension, currentTimeMillis, contracts, hashes.getValue(contractKey))
            ServiceProvider.writeProtoHash(protoProject, extension, currentTimeMillis, protos, hashes.getValue(protoKey))
        }
    }

    fun storeObject(client: Client, jar: File, location: P8eLocationExtension): ObjectHash {
        val contentLength = jar.length()

        return client.inner.osClient
            .putJar(FileInputStream(jar), client.affiliate.signingKeyRef, client.affiliate.encryptionKeyRef, contentLength, location.audience.values.map { it.toPublicKey() }.toSet()).get()
            .also { project.logger.info("Saved jar ${jar.path} with hash ${it.value} size = $contentLength") }
    }

    fun storeObject(client: Client, spec: ContractSpec, location: P8eLocationExtension): ObjectHash {
        return client.inner.osClient
            .putRecord(spec, client.affiliate.signingKeyRef, client.affiliate.encryptionKeyRef, location.audience.values.map { it.toPublicKey() }.toSet())
            .get()
            .also { project.logger.info("Saved contract specification ${spec.definition.resourceLocation.classname} with hash ${it.value} size = ${spec.serializedSize}") }
    }

    fun getKeyPair(privateKey: String): KeyPair {
        // compute private key from string
        val protoPrivateKey = PK.PrivateKey.parseFrom(Hex.decode(privateKey))
        val keyFactory = KeyFactory.getInstance("ECDH", BouncyCastleProvider.PROVIDER_NAME)
        val ecSpec = ECNamedCurveTable.getParameterSpec(ECUtils.LEGACY_DIME_CURVE)
        val privateKeySpec = ECPrivateKeySpec(
            ByteUtil.unsignedBytesToBigInt(protoPrivateKey.keyBytes.toByteArray()),
            ecSpec
        )
        val typedPrivateKey = BCECPrivateKey(keyFactory.algorithm, privateKeySpec, BouncyCastleProvider.CONFIGURATION)

        // compute public key from private key
        val point = ecSpec.g.multiply(typedPrivateKey.d)
        val publicKeySpec = ECPublicKeySpec(point, ecSpec)
        val publicKey = BCECPublicKey(keyFactory.algorithm, publicKeySpec, BouncyCastleProvider.CONFIGURATION)

        return KeyPair(publicKey, typedPrivateKey)
    }

    fun P8ePartyExtension.toPublicKey(): PublicKey {
        val protoPublicKey = PK.PublicKey.parseFrom(Hex.decode(this.publicKey))
        val keyFactory = KeyFactory.getInstance("ECDH", BouncyCastleProvider.PROVIDER_NAME)
        val ecSpec = ECNamedCurveTable.getParameterSpec(ECUtils.LEGACY_DIME_CURVE)
        val point = ecSpec.curve.decodePoint(protoPublicKey.publicKeyBytes.toByteArray())
        val publicKeySpec = ECPublicKeySpec(point, ecSpec)

        return BCECPublicKey(keyFactory.algorithm, publicKeySpec, BouncyCastleProvider.CONFIGURATION)
    }
}
