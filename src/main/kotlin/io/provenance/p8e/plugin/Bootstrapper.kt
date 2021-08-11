package io.provenance.p8e.plugin

import org.bouncycastle.crypto.digests.RIPEMD160Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

import org.kethereum.crypto.CURVE
import org.kethereum.crypto.impl.ec.EllipticCurveSigner
import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import java.io.IOException

import com.google.common.hash.Hashing
import com.google.common.io.BaseEncoding
import com.google.protobuf.Any
import com.google.protobuf.ByteString
import com.google.protobuf.Message
import cosmos.auth.v1beta1.Auth
import cosmos.auth.v1beta1.QueryOuterClass
import cosmos.base.v1beta1.CoinOuterClass
import cosmos.crypto.secp256k1.Keys
import cosmos.tx.signing.v1beta1.Signing
import cosmos.tx.v1beta1.ServiceGrpc
import cosmos.tx.v1beta1.ServiceOuterClass.BroadcastMode
import cosmos.tx.v1beta1.ServiceOuterClass.BroadcastTxRequest
import cosmos.tx.v1beta1.ServiceOuterClass.SimulateRequest
import cosmos.tx.v1beta1.TxOuterClass.*
import io.grpc.ManagedChannelBuilder
import io.provenance.metadata.v1.ContractSpecification
import io.provenance.metadata.v1.ContractSpecificationRequest
import io.provenance.metadata.v1.DefinitionType
import io.provenance.metadata.v1.Description
import io.provenance.metadata.v1.InputSpecification
import io.provenance.metadata.v1.MsgAddContractSpecToScopeSpecRequest
import io.provenance.metadata.v1.MsgWriteContractSpecificationRequest
import io.provenance.metadata.v1.MsgWriteRecordSpecificationRequest
import io.provenance.metadata.v1.MsgWriteScopeSpecificationRequest
import io.provenance.metadata.v1.QueryGrpc
import io.provenance.metadata.v1.RecordSpecification
import io.provenance.metadata.v1.PartyType as ProvenancePartyType
import io.provenance.metadata.v1.ScopeSpecification
import io.provenance.metadata.v1.ScopeSpecificationRequest
import io.provenance.scope.contract.annotations.ScopeSpecificationDefinition
import io.provenance.scope.contract.proto.Commons.ProvenanceReference
import io.provenance.scope.contract.proto.Specifications.ContractSpec
import io.provenance.scope.contract.annotations.ScopeSpecification as ScopeSpecificationReference
import io.provenance.scope.contract.proto.Specifications.PartyType
import io.provenance.scope.encryption.ecies.ECUtils
import io.provenance.scope.encryption.model.DirectKeyRef
import io.provenance.scope.encryption.proto.PK
import io.provenance.scope.encryption.util.ByteUtil
import io.provenance.scope.sdk.Affiliate
import io.provenance.scope.sdk.Client
import io.provenance.scope.sdk.ClientConfig
import io.provenance.scope.sdk.ContractSpecMapper
import io.provenance.scope.sdk.ObjectHash
import io.provenance.scope.sdk.SharedClient
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPrivateKey
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPublicKey
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jce.spec.ECPrivateKeySpec
import org.bouncycastle.jce.spec.ECPublicKeySpec
import org.bouncycastle.util.encoders.Hex
import org.gradle.api.Project
import org.kethereum.crypto.api.ec.ECDSASignature
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.math.BigInteger
import java.net.URI
import java.net.URLClassLoader
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.PublicKey
import java.security.Security
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

// TODO add flag to skip provenance save portion

// TODO can we do this better?
data class GasEstimate(val estimate: Long, val feeAdjustment: Double? = DEFAULT_FEE_ADJUSTMENT) {
    companion object {
        private const val DEFAULT_FEE_ADJUSTMENT = 1.25
        private const val DEFAULT_GAS_PRICE = 1905.00
    }

    private val adjustment = feeAdjustment ?: DEFAULT_FEE_ADJUSTMENT

    val limit
        get() = ceil(estimate * adjustment).toInt()
    val fees
        get() = ceil(limit * DEFAULT_GAS_PRICE).toInt()
}

fun Collection<Message>.toTxBody(): TxBody = TxBody.newBuilder()
    .addAllMessages(this.map { it.toAny() })
    .build()
fun Message.toAny(typeUrlPrefix: String = "") = Any.pack(this, typeUrlPrefix)

// TODO do we want these in sdk?
fun PartyType.toProv() = when (this) {
    PartyType.SERVICER -> ProvenancePartyType.PARTY_TYPE_SERVICER
    PartyType.ORIGINATOR -> ProvenancePartyType.PARTY_TYPE_ORIGINATOR
    PartyType.OWNER -> ProvenancePartyType.PARTY_TYPE_OWNER
    PartyType.AFFILIATE -> ProvenancePartyType.PARTY_TYPE_AFFILIATE
    PartyType.CUSTODIAN -> ProvenancePartyType.PARTY_TYPE_CUSTODIAN
    PartyType.INVESTOR -> ProvenancePartyType.PARTY_TYPE_INVESTOR
    PartyType.OMNIBUS -> ProvenancePartyType.PARTY_TYPE_OMNIBUS
    PartyType.PROVENANCE -> ProvenancePartyType.PARTY_TYPE_PROVENANCE
    PartyType.NONE, PartyType.UNRECOGNIZED -> throw IllegalStateException("Invalid PartyType of ${this}.")
}
fun ByteString.base64Encode(): String {
    return this.toByteArray().base64Encode()
}
fun ByteArray.base64Encode(): String {
    return BaseEncoding.base64().encode(this)
}
// TODO add test to go to byte array and back to uuid
fun ByteString.toUuid(): UUID {
    return this.toByteArray().toUuid()
}
fun ByteArray.toUuid(): UUID {
    val buffer = ByteBuffer.wrap(this)

    val first = buffer.long
    val second = buffer.long

    return UUID(first, second)
}
fun UUID.toByteArray(): ByteArray {
    val buffer = ByteBuffer.wrap(ByteArray(16))

    buffer.putLong(this.leastSignificantBits)
    buffer.putLong(this.mostSignificantBits)

    return buffer.array()
}
fun ContractSpec.uuid(): UUID {
    return Hashing.sha256().hashBytes(this.toByteArray())
        .asBytes()
        .slice(0..16)
        .toByteArray()
        .toUuid()
}
fun ObjectHash.toReference(): ProvenanceReference {
    return ProvenanceReference.newBuilder()
        .setHash(this.value)
        .build()
}

internal class Bootstrapper(
    private val project: Project,
    val extension: P8eExtension
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
        val contracts = findContracts(contractClassLoader)
        val scopes = findScopes(contractClassLoader)
        val protos = findProtos(contractClassLoader)

        extension.locations.forEach { name, location ->
            project.logger.info("Publishing contracts - location: $name object-store url: ${location.osUrl} provenance url: ${location.provenanceUrl}")

            val config = ClientConfig(
                cacheJarSizeInBytes = 0L,
                cacheSpecSizeInBytes = 0L,
                cacheRecordSizeInBytes = 0L,

                osGrpcUrl = URI(location.osUrl!!),
                osGrpcDeadlineMs = 60 * 1_000L,
            )
            val encryptionKeyPair = getKeyPair(location.encryptionPrivateKey!!)
            val signingKeyPair = getKeyPair(location.signingPrivateKey!!)
            val pbSigner = signingKeyPair.toSignerMeta()
            val pbAddress = getAddress(signingKeyPair.public)
            val affiliate = Affiliate(
                signingKeyRef = DirectKeyRef(signingKeyPair.public, signingKeyPair.private),
                encryptionKeyRef = DirectKeyRef(encryptionKeyPair.public, encryptionKeyPair.private),
                PartyType.OWNER,
            )
            val sdk = Client(SharedClient(config), affiliate)
            val provenanceUri = URI(location.provenanceUrl!!)
            val provenanceChannel = ManagedChannelBuilder
                .forAddress(provenanceUri.host, provenanceUri.port)
                .usePlaintext()
                .build()
            val tx = mutableListOf<Message>()

            val contractJarLocation = storeObject(sdk, contractJar, location)
                .also {
                    require (it.value == hashes.getOrDefault(contractKey, it.value)) {
                        "Received different hash for the same contract jar ${it.value} ${hashes.getValue(contractKey)}"
                    }

                    hashes[contractKey] = it.value
                }
            val protoJarLocation = storeObject(sdk, protoJar, location)
                .also {
                    require (it.value == hashes.getOrDefault(protoKey, it.value)) {
                        "Received different hash for the same proto jar ${it.value} ${hashes.getValue(protoKey)}"
                    }

                    hashes[protoKey] = it.value
                }


            val existingScopeSpecifications = mutableListOf<ScopeSpecification>()
            val scopeSpecificationNameToUuid = mutableMapOf<String, UUID>()
            val scopeSpecifications = scopes.map { clazz ->
                val definition = clazz.annotations
                    .filter { it is ScopeSpecificationDefinition }
                    .map { it as ScopeSpecificationDefinition }
                    .first()
                val id = MetadataAddress.forScopeSpecification(UUID.fromString(definition.uuid)).bytes

                scopeSpecificationNameToUuid[definition.name] = UUID.fromString(definition.uuid)

                ScopeSpecification.newBuilder()
                    .setSpecificationId(ByteString.copyFrom(id))
                    .setDescription(Description.newBuilder()
                        .setName(definition.name)
                        .setDescription(definition.description)
                        .also { builder -> definition.websiteUrl.takeIf { it.isNotBlank() }?.run { builder.websiteUrl = this } }
                        .also { builder -> definition.iconUrl.takeIf { it.isNotBlank() }?.run { builder.iconUrl = this } }
                        .build()
                    )
                    .addOwnerAddresses(pbAddress)
                    .addAllPartiesInvolved(definition.partiesInvolved.map { it.toProv() })
                    .build()
            }
            val newScopeSpecifications = scopeSpecifications.filter { spec ->
                val metadataClient = QueryGrpc.newBlockingStub(provenanceChannel).withDeadlineAfter(10, TimeUnit.SECONDS)
                val uuid = MetadataAddress.fromBytes(spec.specificationId.toByteArray()).getPrimaryUuid()

                val existingSpec = metadataClient.scopeSpecification(ScopeSpecificationRequest.newBuilder()
                    .setSpecificationId(uuid.toString())
                    .build()
                )

                project.logger.info("uuid = $uuid message = $existingSpec")

                // at this time we only post scope specifications when it doesn't already exist
                val isNew = existingSpec.scopeSpecification.specification.description.name.isEmpty()

                if (!isNew) {
                    existingScopeSpecifications.add(existingSpec.scopeSpecification.specification)
                }

                isNew
            }.also { specs ->
                project.logger.info("Adding ${specs.size} scope specification(s) to batch")

                if (specs.isNotEmpty()) {
                    tx.addAll(specs.map { spec ->
                        MsgWriteScopeSpecificationRequest.newBuilder()
                            .addSigners(pbAddress)
                            .setSpecification(spec)
                            .build()
                    })
                }
            }

            val existingContractSpecifications = mutableListOf<ContractSpecification>()
            val contractSpecificationUuidToScopeNames = mutableMapOf<UUID, List<String>>()
            val contractSpecifications = contracts.map { contract ->
                val scopeSpecificationNames = contract.annotations
                    .filterIsInstance<ScopeSpecificationReference>()
                    .first()

                ContractSpecMapper.dehydrateSpec(
                    contract.kotlin,
                    contractJarLocation.toReference(),
                    protoJarLocation.toReference(),
                ).also { contractSpecificationUuidToScopeNames[it.uuid()] = scopeSpecificationNames.names.toList() }
            }.also { specs ->
                project.logger.info("Adding ${specs.size} contract specification(s) to object-store")

                // TODO move to async and do more than one at a time eventually
                specs.forEach { spec -> storeObject(sdk, spec, location) }
            }
            val newContractSpecifications = contractSpecifications.filter { spec ->
                val metadataClient = QueryGrpc.newBlockingStub(provenanceChannel).withDeadlineAfter(10, TimeUnit.SECONDS)

                val existingSpec = metadataClient.contractSpecification(ContractSpecificationRequest.newBuilder()
                    .setSpecificationId(spec.uuid().toString())
                    .build()
                )

                // at this time we only post scope specifications when it doesn't already exist
                val isNew = existingSpec.contractSpecification.specification.className.isEmpty()

                if (!isNew) {
                    existingContractSpecifications.add(existingSpec.contractSpecification.specification)
                }

                isNew
            }.map { spec ->
                val recordSpecifications = mutableListOf<RecordSpecification>()
                val id = MetadataAddress.forContractSpecification(spec.uuid()).bytes

                val contractSpecification = ContractSpecification.newBuilder()
                    .setSpecificationId(ByteString.copyFrom(id))
                    .setDescription(Description.newBuilder()
                        .setDescription(spec.definition.resourceLocation.classname)
                        .setName(spec.definition.name)
                        .build()
                    )
                    .addOwnerAddresses(pbAddress)
                    .addAllPartiesInvolved(spec.partiesInvolvedList.map { p -> p.toProv() })
                    .setHash(spec.uuid().toByteArray().base64Encode())
                    .setClassName(spec.definition.resourceLocation.classname)
                    .build()

                spec.functionSpecsList.forEach { functionSpec ->
                    val id = MetadataAddress.forRecordSpecification(spec.uuid(), functionSpec.outputSpec.spec.name).bytes

                    val recordSpec = RecordSpecification.newBuilder()
                        .setSpecificationId(ByteString.copyFrom(id))
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
                        .addAllResponsibleParties(listOf(functionSpec.invokerParty.toProv()))
                        .build()

                    recordSpecifications.add(recordSpec)
                }

                Pair(contractSpecification, recordSpecifications)
            }.also { specs ->
                project.logger.info("Adding ${specs.size} contract specification(s) to batch")

                specs.map { (contractSpecification, recordSpecifications) ->
                    tx.add(
                        MsgWriteContractSpecificationRequest.newBuilder()
                            .addSigners(pbAddress)
                            .setSpecification(contractSpecification)
                            .build()
                    )

                    tx.addAll(recordSpecifications.map { spec ->
                        val contractSpecAddress = MetadataAddress.fromBytes(spec.specificationId.toByteArray())

                        MsgWriteRecordSpecificationRequest.newBuilder()
                            .addSigners(pbAddress)
                            .setSpecification(spec)
                            .setContractSpecUuid(contractSpecAddress.getPrimaryUuid().toString())
                            .build()
                    })
                }
            }

            // add all new scope spec to contract spec mappings
            newContractSpecifications.forEach { (spec, _) ->
                val address = MetadataAddress.fromBytes(spec.specificationId.toByteArray())

                contractSpecificationUuidToScopeNames.getValue(address.getPrimaryUuid()).forEach { scopeSpecName ->
                    val uuid = scopeSpecificationNameToUuid.getValue(scopeSpecName)
                    val scopeSpecificationAddress = MetadataAddress.forScopeSpecification(uuid)

                    tx.add(MsgAddContractSpecToScopeSpecRequest.newBuilder()
                        .setScopeSpecificationId(ByteString.copyFrom(scopeSpecificationAddress.bytes))
                        .setContractSpecificationId(spec.specificationId)
                        .addSigners(pbAddress)
                        .build()
                    )
                }
            }

            // TODO handle adding ContractSpecToScopeSpec's for existing ContractSpecifications that add a new ScopeSpecification

            // TODO move back to debug later
            project.logger.info("tx batch: $tx")

            if (!tx.isEmpty()) {
                val serviceClient = ServiceGrpc.newBlockingStub(provenanceChannel)
                val authClient = cosmos.auth.v1beta1.QueryGrpc.newBlockingStub(provenanceChannel)
                val txBody = tx.toTxBody()
                val accountInfo = authClient.withDeadlineAfter(10, TimeUnit.SECONDS)
                    .account(QueryOuterClass.QueryAccountRequest.newBuilder()
                        .setAddress(pbAddress)
                        .build()
                    ).run { account.unpack(Auth.BaseAccount::class.java) }
                val signedSimulateTx = signTx(location, txBody, accountInfo.accountNumber, accountInfo.sequence, pbSigner)
                val estimate = serviceClient.withDeadlineAfter(10, TimeUnit.SECONDS)
                    .simulate(SimulateRequest.newBuilder().setTx(signedSimulateTx).build())
                    .let { GasEstimate(it.gasInfo.gasUsed) }

                project.logger.info("signed tx = $signedSimulateTx")

                val signedTx = signTx(location, txBody, accountInfo.accountNumber, accountInfo.sequence, pbSigner, gasEstimate = estimate)
                val response = serviceClient.withDeadlineAfter(20, TimeUnit.SECONDS)
                    .broadcastTx(BroadcastTxRequest.newBuilder()
                        .setTxBytes(ByteString.copyFrom(signedTx.toByteArray()))
                        .setMode(BroadcastMode.BROADCAST_MODE_BLOCK)
                        .build()
                    )

                project.logger.info("tx response = $response")
                // TODO parse response and verify it was successful
            }

            provenanceChannel.shutdown()
            if (!provenanceChannel.awaitTermination(10, TimeUnit.SECONDS)) {
                project.logger.warn("Could not shutdown ManagedChannel for ${location.provenanceUrl} cleanly!")
            }
        }

        if(hashes.isEmpty()) {
            project.logger.warn("No p8e locations were detected!")
        } else {
            project.logger.info("Writing services providers")

            val currentTimeMillis = System.currentTimeMillis().toString()
            ServiceProvider.writeContractHash(contractProject, extension, currentTimeMillis, contracts, hashes.getValue(contractKey))
            ServiceProvider.writeProtoHash(protoProject, extension, currentTimeMillis, protos, hashes.getValue(protoKey))
        }
    }

    // TODO move on to scope specification saving/editing - probably need to add uuid to annotation for this
    // TODO add contract spec saving to chain? - what needs to be done here so that we don't need to save the contract spec to object-store in package contract like before

    fun storeObject(client: Client, jar: File, location: P8eLocationExtension): ObjectHash {
        val contentLength = jar.length()

        return client.inner.osClient
            .putJar(FileInputStream(jar), client.affiliate, contentLength, location.audience.values.map { it.toPublicKey() }.toSet()).get()
            .also { project.logger.info("Saved jar ${jar.path} with hash ${it.value} size = $contentLength") }
    }

    fun storeObject(client: Client, spec: ContractSpec, location: P8eLocationExtension): ObjectHash {
        // TODO move to 16 bytes
        return client.inner.osClient
            .putRecord(spec, client.affiliate, location.audience.values.map { it.toPublicKey() }.toSet()).get()
            // TODO move to debug later
            // TODO find the name field
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

    fun signTx(location: P8eLocationExtension, body: TxBody, accountNumber: Long, sequenceNumber: Long, signer: SignerMeta, gasEstimate: GasEstimate = GasEstimate(0)): Tx {
        val authInfo = AuthInfo.newBuilder()
            .setFee(Fee.newBuilder()
                .addAllAmount(listOf(
                    CoinOuterClass.Coin.newBuilder()
                        .setDenom("nhash")
                        .setAmount((gasEstimate.fees).toString())
                        .build()
                )).setGasLimit((gasEstimate.limit).toLong())
            )
            .addAllSignerInfos(listOf(
                SignerInfo.newBuilder()
                    .setPublicKey(
                        Keys.PubKey.newBuilder()
                            .setKey(ByteString.copyFrom(signer.compressedPublicKey))
                            .build()
                            .toAny()
                    )
                    .setModeInfo(ModeInfo.newBuilder()
                        .setSingle(ModeInfo.Single.newBuilder().setMode(Signing.SignMode.SIGN_MODE_DIRECT).build())
                        .build()
                    )
                    .setSequence(sequenceNumber)
                    .build()
            )).build()

        val signatures = SignDoc.newBuilder()
            .setBodyBytes(body.toByteString())
            .setAuthInfoBytes(authInfo.toByteString())
            .setChainId(location.chainId)
            .setAccountNumber(accountNumber)
            .build()
            .toByteArray()
            .let { signer.sign(it) }
            .map { ByteString.copyFrom(it.signature) }

        return Tx.newBuilder()
            .setBody(body)
            .setAuthInfo(authInfo)
            .addAllSignatures(signatures)
            .build()
    }
}

typealias SignerFn = (ByteArray) -> List<StdSignature>
object PbSigner {
    fun signerFor(keyPair: KeyPair): SignerFn = { bytes ->
        bytes.let {
            Hashing.sha256().hashBytes(it).asBytes()
        }.let {
            val privateKey = (keyPair.private as BCECPrivateKey).s
            StdSignature(
                pub_key = StdPubKey("tendermint/PubKeySecp256k1", (keyPair.public as BCECPublicKey).q.getEncoded(true)) ,
                signature = EllipticCurveSigner().sign(it, privateKey, true).encodeAsBTC()
            )
        }.let {
            listOf(it)
        }
    }
}

data class SignerMeta(val compressedPublicKey: ByteArray, val sign: SignerFn) {
    override fun equals(other: kotlin.Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SignerMeta

        if (!compressedPublicKey.contentEquals(other.compressedPublicKey)) return false
        if (sign != other.sign) return false

        return true
    }

    override fun hashCode(): Int {
        var result = compressedPublicKey.contentHashCode()
        result = 31 * result + sign.hashCode()
        return result
    }
}

fun KeyPair.toSignerMeta() = SignerMeta((public as BCECPublicKey).q.getEncoded(true), PbSigner.signerFor(this))

data class StdSignature(
    val pub_key: StdPubKey,
    val signature: ByteArray
)

@JsonDeserialize(using = PubKeyDeserializer::class)
data class StdPubKey(
    val type: String,
    @JsonAlias("data")
    val value: ByteArray?= ByteArray(0)
)

/**
 * Public key used to be a string, now it is an object, since all Markers are accounts,
 * and they still send the public_key is sent as a empty string.
 * This deserialize just checks that if the json string is empty, it returns a null so that the
 * default value can be used.
 */
internal class PubKeyDeserializer : JsonDeserializer<StdPubKey?>() {
    @Throws(IOException::class, JsonProcessingException::class)
    override fun deserialize(jsonParser: JsonParser, context: DeserializationContext?): StdPubKey? {
        val node: JsonNode = jsonParser.readValueAsTree()
        return if (node.asText().isEmpty() && node.toList().isEmpty()) {
            null
        } else {
            StdPubKey(node.get("type").asText(),node.get("value").asText().toByteArray(Charsets.UTF_8))
        }
    }
}

/**
 * encodeAsBTC returns the ECDSA signature as a ByteArray of r || s,
 * where both r and s are encoded into 32 byte big endian integers.
 */
fun ECDSASignature.encodeAsBTC(): ByteArray {
    // Canonicalize - In order to remove malleability,
    // we set s = curve_order - s, if s is greater than curve.Order() / 2.
    var sigS = this.s
    if (sigS > HALF_CURVE_ORDER) {
        sigS = CURVE.n.subtract(sigS)
    }

    val sBytes = sigS.getUnsignedBytes()
    val rBytes = this.r.getUnsignedBytes()

    require(rBytes.size <= 32) { "cannot encode r into BTC Format, size overflow (${rBytes.size} > 32)" }
    require(sBytes.size <= 32) { "cannot encode s into BTC Format, size overflow (${sBytes.size} > 32)" }

    val signature = ByteArray(64)
    // 0 pad the byte arrays from the left if they aren't big enough.
    System.arraycopy(rBytes, 0, signature, 32 - rBytes.size, rBytes.size)
    System.arraycopy(sBytes, 0, signature, 64 - sBytes.size, sBytes.size)

    return signature
}


private val HALF_CURVE_ORDER = CURVE.n.shiftRight(1)
const val ZERO = 0x0.toByte()

/**
 * Returns the bytes from a BigInteger as an unsigned version by truncating a byte if needed.
 */
fun BigInteger.getUnsignedBytes(): ByteArray {
    val bytes = this.toByteArray();

    if (bytes[0] == ZERO)
    {
        return bytes.drop(1).toByteArray()
    }

    return bytes;
}

fun getAddress(publicKey: PublicKey): String {
    val bytes = (publicKey as BCECPublicKey).q.getEncoded(true)

    // TODO get prefix from location config
    return Hash.sha256hash160(bytes).toBech32Data("tp").address
}

/** Cryptographic hash functions.  */
object Hash {
    /**
     * Generates a digest for the given `input`.
     *
     * @param input The input to digest
     * @param algorithm The hash algorithm to use
     * @return The hash value for the given input
     * @throws RuntimeException If we couldn't find any provider for the given algorithm
     */
    // fun hash(input: ByteArray?, algorithm: String): ByteArray {
    //     return try {
    //         val digest = MessageDigest.getInstance(algorithm.toUpperCase())
    //         digest.digest(input)
    //     } catch (e: NoSuchAlgorithmException) {
    //         throw RuntimeException("Couldn't find a $algorithm provider", e)
    //     }
    // }

    /**
     * Keccak-256 hash function.
     *
     * @param hexInput hex encoded input data with optional 0x prefix
     * @return hash value as hex encoded string
     */
    // fun sha3(hexInput: String): String {
    //     val bytes: ByteArray = Numeric.hexStringToByteArray(hexInput)
    //     val result = sha3(bytes)
    //     return Numeric.toHexString(result)
    // }
    /**
     * Keccak-256 hash function.
     *
     * @param input binary encoded input data
     * @param offset of start of data
     * @param length of data
     * @return hash value
     */
    /**
     * Keccak-256 hash function.
     *
     * @param input binary encoded input data
     * @return hash value
     */
    // @JvmOverloads
    // fun sha3(input: ByteArray, offset: Int = 0, length: Int = input.size): ByteArray {
    //     val kecc: Keccak.DigestKeccak =
    //         Keccak.Digest256()
    //     kecc.update(input, offset, length)
    //     return kecc.digest()
    // }

    /**
     * Keccak-256 hash function that operates on a UTF-8 encoded String.
     *
     * @param utf8String UTF-8 encoded string
     * @return hash value as hex encoded string
     */
    // fun sha3String(utf8String: String): String {
    //     return Numeric.toHexString(sha3(utf8String.toByteArray(StandardCharsets.UTF_8)))
    // }

    /**
     * Generates SHA-256 digest for the given `input`.
     *
     * @param input The input to digest
     * @return The hash value for the given input
     * @throws RuntimeException If we couldn't find any SHA-256 provider
     */
    fun sha256(input: ByteArray?): ByteArray {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.digest(input)
        } catch (e: NoSuchAlgorithmException) {
            throw RuntimeException("Couldn't find a SHA-256 provider", e)
        }
    }

    fun hmacSha512(key: ByteArray?, input: ByteArray): ByteArray {
        val hMac =
            HMac(SHA512Digest())
        hMac.init(KeyParameter(key))
        hMac.update(input, 0, input.size)
        val out = ByteArray(64)
        hMac.doFinal(out, 0)
        return out
    }

    fun sha256hash160(input: ByteArray?): ByteArray {
        val sha256 = sha256(input)
        val digest = RIPEMD160Digest()
        digest.update(sha256, 0, sha256.size)
        val out = ByteArray(20)
        digest.doFinal(out, 0)
        return out
    }
}

infix fun Int.min(b: Int): Int = b.takeIf { this > b } ?: this
infix fun UByte.shl(bitCount: Int) = ((this.toInt() shl bitCount) and 0xff).toUByte()
infix fun UByte.shr(bitCount: Int) = (this.toInt() shr bitCount).toUByte()

/**
 * Given an array of bytes, associate an HRP and return a Bech32Data instance.
 */
fun ByteArray.toBech32Data(hrp: String) =
    Bech32Data(hrp, this)

/**
 * Using a string in bech32 encoded address format, parses out and returns a Bech32Data instance
 */
fun String.toBech32Data() = Bech32.decode(this)

/** Data involved with a Bech32 address */
data class Bech32Data(val hrp: String, val data: ByteArray) {

    /**
     * The encapsulated data returned as a Hexadecimal string
     */
    val hexData = this.data.joinToString("") { "%02x".format(it) }

    /**
     * The Bech32 encoded value of the data prefixed with the human readable portion and
     * protected by an appended checksum.
     */
    val address = Bech32.encode(hrp, data)

    /**
     * The Bech32 Address toString prints state information for debugging purposes.
     * @see address() for the bech32 encoded address string output.
     */
    override fun toString(): String {
        return "bech32 : ${this.address}\nhuman: ${this.hrp} \nbytes: ${this.hexData}"
        /*
        bech32 : provenance1gx58vp8pryh3jkvxnkvzmd0hqmqqnyqxrtvheq
        human: provenance
        bytes: 41A87604E1192F1959869D982DB5F706C0099006
         */
    }

    /** equals implementation for a Bech32Data object. */
    override fun equals(other: kotlin.Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Bech32Data
        return this.hrp == other.hrp &&
            this.data.contentEquals(other.data)
    }

    /** equals implementation for a Bech32Data object. */
    override fun hashCode(): Int {
        var result = hrp.hashCode()
        result = 31 * result + this.data.contentHashCode()
        return result
    }
}

class Bech32 {
    companion object {
        private const val CHECKSUM_SIZE = 6
        private const val MIN_VALID_LENGTH = 8
        private const val MAX_VALID_LENGTH = 90
        private const val MIN_VALID_CODEPOINT = 33
        private const val MAX_VALID_CODEPOINT = 126

        private const val charset = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
        private val gen = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

        /** Decodes a Bech32 String */
        fun decode(bech32: String): Bech32Data {
            require(bech32.length in MIN_VALID_LENGTH..MAX_VALID_LENGTH) { "invalid bech32 string length" }
            require(bech32.toCharArray().none { c -> c.toInt() < MIN_VALID_CODEPOINT || c.toInt() > MAX_VALID_CODEPOINT })
            { "invalid character in bech32: ${bech32.toCharArray().map { c -> c.toInt() }
                .filter { c -> c < MIN_VALID_CODEPOINT || c > MAX_VALID_CODEPOINT }}" }

            require(bech32 == bech32.toLowerCase() || bech32 == bech32.toUpperCase())
            { "bech32 must be either all upper or lower case" }
            require(bech32.substring(1).dropLast(CHECKSUM_SIZE).contains('1')) { "invalid index of '1'" }

            val hrp = bech32.substringBeforeLast('1').toLowerCase()
            val dataString = bech32.substringAfterLast('1').toLowerCase()

            require(dataString.toCharArray().all { c -> charset.contains(c) }) { "invalid data encoding character in bech32"}

            val dataBytes = dataString.map { c -> charset.indexOf(c).toByte() }.toByteArray()
            val checkBytes = dataString.takeLast(CHECKSUM_SIZE).map { c -> charset.indexOf(c).toByte() }.toByteArray()

            val actualSum = checksum(hrp, dataBytes.dropLast(CHECKSUM_SIZE).toTypedArray())
            require(1 == polymod(expandHrp(hrp).plus(dataBytes.map { d -> d.toInt() }))) { "checksum failed: $checkBytes != $actualSum" }

            return Bech32Data(hrp, convertBits(dataBytes.dropLast(CHECKSUM_SIZE).toByteArray(), 5, 8, false))
        }

        /**
         * Encodes the provided hrp and data to a Bech32 address string.
         * @param hrp the human readable portion (prefix) to use.
         * @param eightBitData an array of 8-bit encoded bytes.
         */
        fun encode(hrp: String, eightBitData: ByteArray) =
            encodeFiveBitData(hrp, convertBits(eightBitData, 8, 5, true))

        /** Encodes 5-bit bytes (fiveBitData) with a given human readable portion (hrp) into a bech32 string. */
        private fun encodeFiveBitData(hrp: String, fiveBitData: ByteArray): String {
            return (fiveBitData.plus(checksum(hrp, fiveBitData.toTypedArray()))
                .map { b -> charset[b.toInt()] }).joinToString("", hrp + "1")
        }

        /**
         * ConvertBits regroups bytes with toBits set based on reading groups of bits as a continuous stream group by fromBits.
         * This process is used to convert from base64 (from 8) to base32 (to 5) or the inverse.
         */
        private fun convertBits(data: ByteArray, fromBits: Int, toBits: Int, pad: Boolean): ByteArray {
            require (fromBits in 1..8 && toBits in 1..8) { "only bit groups between 1 and 8 are supported"}

            var acc = 0
            var bits = 0
            val out = ByteArrayOutputStream(64)
            val maxv = (1 shl toBits) - 1
            val maxAcc = (1 shl (fromBits + toBits - 1)) - 1

            for (b in data) {
                val value = b.toInt() and 0xff
                if ((value ushr fromBits) != 0) {
                    throw IllegalArgumentException(String.format("Input value '%X' exceeds '%d' bit size", value, fromBits))
                }
                acc = ((acc shl fromBits) or value) and maxAcc
                bits += fromBits
                while (bits >= toBits) {
                    bits -= toBits
                    out.write((acc ushr bits) and maxv)
                }
            }
            if (pad) {
                if (bits > 0) {
                    out.write((acc shl (toBits - bits)) and maxv)
                }
            } else if (bits >= fromBits || ((acc shl (toBits - bits)) and maxv) != 0) {
                throw IllegalArgumentException("Could not convert bits, invalid padding")
            }
            return out.toByteArray()
        }

        /** Calculates a bech32 checksum based on BIP 173 specification */
        private fun checksum(hrp: String, data: Array<Byte>): ByteArray {
            val values = expandHrp(hrp)
                .plus(data.map { d -> d.toInt() })
                .plus(Array(6){ 0 }.toIntArray())

            val poly = polymod(values) xor 1

            return (0..5).map {
                ((poly shr (5 * (5-it))) and 31).toByte()
            }.toByteArray()
        }

        /** Expands the human readable prefix per BIP173 for Checksum encoding */
        private fun expandHrp(hrp: String) =
            hrp.map { c -> c.toInt() shr 5 }
                .plus(0)
                .plus(hrp.map { c -> c.toInt() and 31 })
                .toIntArray()

        /** Polynomial division function for checksum calculation.  For details see BIP173 */
        private fun polymod(values: IntArray): Int {
            var chk = 1
            return values.map { v ->
                val b = chk shr 25
                chk = ((chk and 0x1ffffff) shl 5) xor v
                (0..4).map {
                    if (((b shr it) and 1) == 1) {
                        chk = chk xor gen[it]
                    }
                }
            }.let { chk }
        }
    }
}

const val PREFIX_SCOPE = "scope"
const val PREFIX_SESSION = "session"
const val PREFIX_RECORD = "record"
const val PREFIX_SCOPE_SPECIFICATION = "scopespec"
const val PREFIX_CONTRACT_SPECIFICATION = "contractspec"
const val PREFIX_RECORD_SPECIFICATION = "recspec"

const val KEY_SCOPE: Byte = 0x00
const val KEY_SESSION: Byte = 0x01
const val KEY_RECORD: Byte = 0x02
const val KEY_SCOPE_SPECIFICATION: Byte = 0x04 // Note that this is not in numerical order.
const val KEY_CONTRACT_SPECIFICATION: Byte = 0x03
const val KEY_RECORD_SPECIFICATION: Byte = 0x05

data class MetadataAddress internal constructor(val bytes: ByteArray) {
    companion object {
        /** Create a MetadataAddress for a Scope. */
        fun forScope(scopeUuid: UUID) =
            MetadataAddress(byteArrayOf(KEY_SCOPE).plus(uuidAsByteArray(scopeUuid)))

        /** Create a MetadataAddress for a Session. */
        fun forSession(scopeUuid: UUID, sessionUuid: UUID) =
            MetadataAddress(byteArrayOf(KEY_SESSION).plus(uuidAsByteArray(scopeUuid)).plus(uuidAsByteArray(sessionUuid)))

        /** Create a MetadataAddress for a Record. */
        fun forRecord(scopeUuid: UUID, recordName: String): MetadataAddress {
            if (recordName.isBlank()) {
                throw IllegalArgumentException("Invalid recordName: cannot be empty or blank.")
            }
            return MetadataAddress(byteArrayOf(KEY_RECORD).plus(uuidAsByteArray(scopeUuid)).plus(asHashedBytes(recordName)))
        }

        /** Create a MetadataAddress for a Scope Specification. */
        fun forScopeSpecification(scopeSpecUuid: UUID) =
            MetadataAddress(byteArrayOf(KEY_SCOPE_SPECIFICATION).plus(uuidAsByteArray(scopeSpecUuid)))

        /** Create a MetadataAddress for a Contract Specification. */
        fun forContractSpecification(contractSpecUuid: UUID) =
            MetadataAddress(byteArrayOf(KEY_CONTRACT_SPECIFICATION).plus(uuidAsByteArray(contractSpecUuid)))

        /** Create a MetadataAddress for a Record Specification. */
        fun forRecordSpecification(contractSpecUuid: UUID, recordSpecName: String): MetadataAddress {
            if (recordSpecName.isBlank()) {
                throw IllegalArgumentException("Invalid recordSpecName: cannot be empty or blank.")
            }
            return MetadataAddress(byteArrayOf(KEY_RECORD_SPECIFICATION).plus(uuidAsByteArray(contractSpecUuid)).plus(asHashedBytes(recordSpecName)))
        }

        /** Create a MetadataAddress object from a bech32 address representation of a MetadataAddress. */
        fun fromBech32(bech32Value: String): MetadataAddress {
            val (hrp, data) = Bech32.decode(bech32Value)
            validateBytes(data)
            val prefix = getPrefixFromKey(data[0])
            if (hrp != prefix) {
                throw IllegalArgumentException("Incorrect HRP: Expected ${prefix}, Actual: ${hrp}.")
            }
            return MetadataAddress(data)
        }

        /** Create a MetadataAddress from a ByteArray. */
        fun fromBytes(bytes: ByteArray): MetadataAddress {
            validateBytes(bytes)
            return MetadataAddress(bytes)
        }

        /** Get the prefix that corresponds to the provided key Byte. */
        private fun getPrefixFromKey(key: Byte) =
            when (key) {
                KEY_SCOPE -> PREFIX_SCOPE
                KEY_SESSION -> PREFIX_SESSION
                KEY_RECORD -> PREFIX_RECORD
                KEY_SCOPE_SPECIFICATION -> PREFIX_SCOPE_SPECIFICATION
                KEY_CONTRACT_SPECIFICATION -> PREFIX_CONTRACT_SPECIFICATION
                KEY_RECORD_SPECIFICATION -> PREFIX_RECORD_SPECIFICATION
                else -> {
                    throw IllegalArgumentException("Invalid key: $key")
                }
            }

        /** Checks that the data has a correct key and length. Throws IllegalArgumentException if not. */
        private fun validateBytes(bytes: ByteArray) {
            val expectedLength = when (bytes[0]) {
                KEY_SCOPE -> 17
                KEY_SESSION -> 33
                KEY_RECORD -> 33
                KEY_SCOPE_SPECIFICATION -> 17
                KEY_CONTRACT_SPECIFICATION -> 17
                KEY_RECORD_SPECIFICATION -> 33
                else -> {
                    throw IllegalArgumentException("Invalid key: ${bytes[0]}")
                }
            }
            if (expectedLength != bytes.size) {
                throw IllegalArgumentException("Incorrect data length for type ${getPrefixFromKey(bytes[0])}: Expected ${expectedLength}, Actual: ${bytes.size}.")
            }
        }

        /** Converts a UUID to a ByteArray. */
        private fun uuidAsByteArray(uuid: UUID): ByteArray {
            val b = ByteBuffer.wrap(ByteArray(16))
            b.putLong(uuid.mostSignificantBits)
            b.putLong(uuid.leastSignificantBits)
            return b.array()
        }

        /** Converts a ByteArray to a UUID. */
        private fun byteArrayAsUuid(data: ByteArray): UUID {
            val uuidBytes = ByteArray(16)
            if (data.size >= 16) {
                data.copyInto(uuidBytes, 0, 0, 16)
            } else if (data.isNotEmpty()) {
                data.copyInto(uuidBytes, 0, 0, data.size)
            }
            val bb = ByteBuffer.wrap(uuidBytes)
            val mostSig = bb.long
            val leastSig = bb.long
            return UUID(mostSig, leastSig)
        }

        /** Hashes a string and gets the bytes desired for a MetadataAddress. */
        private fun asHashedBytes(str: String) =
            MessageDigest.getInstance("SHA-256").digest(str.trim().toLowerCase().toByteArray()).copyOfRange(0, 16)
    }

    /** Gets the key byte for this MetadataAddress. */
    fun getKey() = this.bytes[0]

    /** Gets the prefix string for this MetadataAddress, e.g. "scope". */
    fun getPrefix() = getPrefixFromKey(this.bytes[0])

    /** Gets the set of bytes for the primary uuid part of this MetadataAddress as a UUID. */
    fun getPrimaryUuid() = byteArrayAsUuid(this.bytes.copyOfRange(1,17))

    /** Gets the set of bytes for the secondary part of this MetadataAddress. */
    fun getSecondaryBytes() = if (this.bytes.size <= 17) byteArrayOf() else bytes.copyOfRange(17, this.bytes.size)

    /** returns this MetadataAddress as a bech32 address string, e.g. "scope1qzge0zaztu65tx5x5llv5xc9ztsqxlkwel" */
    override fun toString() = Bech32.encode(getPrefixFromKey(this.bytes[0]), this.bytes)

    /** hashCode implementation for a MetadataAddress. */
    override fun hashCode() = this.bytes.contentHashCode()

    override fun equals(other: kotlin.Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is MetadataAddress) {
            return false
        }
        return this.bytes.contentEquals(other.bytes)
    }
}
