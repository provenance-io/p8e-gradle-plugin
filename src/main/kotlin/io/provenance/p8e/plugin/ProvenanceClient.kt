package io.provenance.p8e.plugin

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.google.protobuf.Any
import com.google.protobuf.ByteString
import com.google.protobuf.Message
import cosmos.auth.v1beta1.Auth
import cosmos.auth.v1beta1.QueryOuterClass
import cosmos.base.v1beta1.CoinOuterClass
import cosmos.crypto.secp256k1.Keys
import cosmos.tx.signing.v1beta1.Signing
import cosmos.tx.v1beta1.ServiceGrpc
import cosmos.tx.v1beta1.ServiceOuterClass
import cosmos.tx.v1beta1.ServiceOuterClass.BroadcastMode
import cosmos.tx.v1beta1.ServiceOuterClass.BroadcastTxRequest
import cosmos.tx.v1beta1.ServiceOuterClass.SimulateRequest
import cosmos.tx.v1beta1.TxOuterClass.AuthInfo
import cosmos.tx.v1beta1.TxOuterClass.Fee
import cosmos.tx.v1beta1.TxOuterClass.ModeInfo
import cosmos.tx.v1beta1.TxOuterClass.SignDoc
import cosmos.tx.v1beta1.TxOuterClass.SignerInfo
import cosmos.tx.v1beta1.TxOuterClass.Tx
import cosmos.tx.v1beta1.TxOuterClass.TxBody
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.provenance.client.protobuf.extensions.getTx
import io.provenance.metadata.v1.ContractSpecificationRequest
import io.provenance.metadata.v1.ContractSpecificationResponse
import io.provenance.metadata.v1.QueryGrpc
import io.provenance.metadata.v1.ScopeSpecificationRequest
import io.provenance.metadata.v1.ScopeSpecificationResponse
import io.provenance.scope.objectstore.util.sha256
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPrivateKey
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPublicKey
import org.kethereum.crypto.CURVE
import org.kethereum.crypto.api.ec.ECDSASignature
import org.kethereum.crypto.impl.ec.EllipticCurveSigner
import org.slf4j.Logger
import java.io.IOException
import java.math.BigInteger
import java.security.KeyPair
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

    data class GasEstimate(val estimate: Long, val feeAdjustment: Double = DEFAULT_FEE_ADJUSTMENT, val gasPrice: Double? = null) {
    companion object {
        private const val DEFAULT_FEE_ADJUSTMENT = 1.25
        private const val DEFAULT_GAS_PRICE = 1905.00
    }

    fun limit() = ceil(estimate * feeAdjustment).toLong()
    fun fees() = ceil(estimate * feeAdjustment * (gasPrice ?: DEFAULT_GAS_PRICE)).toLong()
}

fun Collection<Message>.toTxBody(): TxBody = TxBody.newBuilder()
    .addAllMessages(this.map { it.toAny() })
    .build()
fun Message.toAny(typeUrlPrefix: String = "") = Any.pack(this, typeUrlPrefix)

class ProvenanceClient(channel: ManagedChannel, val logger: Logger, val location: P8eLocationExtension) {

    private val metadataClient = QueryGrpc.newBlockingStub(channel)
    private val serviceClient = ServiceGrpc.newBlockingStub(channel)
    private val authClient = cosmos.auth.v1beta1.QueryGrpc.newBlockingStub(channel)

    fun scopeSpecification(request: ScopeSpecificationRequest): ScopeSpecificationResponse =
        metadataClient.withDeadlineAfter(10, TimeUnit.SECONDS).scopeSpecification(request)

    fun contractSpecification(request: ContractSpecificationRequest): ContractSpecificationResponse =
        metadataClient.withDeadlineAfter(10, TimeUnit.SECONDS).contractSpecification(request)

    private class SequenceMismatch(message: String): Exception(message)
    fun writeTx(address: String, signer: SignerMeta, txBody: TxBody) {
        retryForException(SequenceMismatch::class.java, 5) {
            val accountInfo = authClient.withDeadlineAfter(10, TimeUnit.SECONDS)
                .account(
                    QueryOuterClass.QueryAccountRequest.newBuilder()
                        .setAddress(address)
                        .build()
                ).run { account.unpack(Auth.BaseAccount::class.java) }
            val signedSimulateTx =
                signTx(txBody, accountInfo.accountNumber, accountInfo.sequence, signer)
            val estimate = serviceClient.withDeadlineAfter(10, TimeUnit.SECONDS)
                .simulate(SimulateRequest.newBuilder().setTx(signedSimulateTx).build())
                .let { GasEstimate(it.gasInfo.gasUsed, gasPrice = location.txGasPrice?.takeIf{ gasPriceString -> gasPriceString.isNotBlank() }?.toDouble()) }

            logger.trace("signed tx = $signedSimulateTx")

            val signedTx = signTx(
                txBody,
                accountInfo.accountNumber,
                accountInfo.sequence,
                signer,
                gasEstimate = estimate.copy(feeAdjustment = location.txFeeAdjustment.toDouble())
            )
            val response = serviceClient.withDeadlineAfter(20, TimeUnit.SECONDS)
                .broadcastTx(
                    BroadcastTxRequest.newBuilder()
                        .setTxBytes(ByteString.copyFrom(signedTx.toByteArray()))
                        .setMode(BroadcastMode.BROADCAST_MODE_SYNC)
                        .build()
                )
            if (response.txResponse.code != 0) {
                val message = "error broadcasting tx (code ${response.txResponse.code}, rawLog: ${response.txResponse.rawLog})"
                if (response.txResponse.rawLog.contains("account sequence mismatch")) {
                    throw SequenceMismatch(message)
                }
                throw Exception(message)
            }

            logger.info("sent tx = ${response.txResponse.txhash}")
            lateinit var tx: ServiceOuterClass.GetTxResponse
            var numPolls = 0
            do {
                tx = try {
                    if (++numPolls > 25) {
                        throw Exception("Exceeded maximum number of polls for transaction ${response.txResponse.txhash}")
                    }
                    serviceClient.getTx(response.txResponse.txhash)
                } catch (e: StatusRuntimeException) {
                    if (e.status.code != Status.NOT_FOUND.code) {
                        throw e
                    }
                    ServiceOuterClass.GetTxResponse.getDefaultInstance()
                }
                if (tx.txResponse.code > 0) {
                    // transaction errored
                    logger.warn("Could not persist batch: ${tx.txResponse}")
                    throw Exception("transaction error (code ${tx.txResponse.code}, rawLog: ${tx.txResponse.rawLog})")
                }
                Thread.sleep(1000)
            } while (tx.txResponse.height <= 0)

            logger.trace("tx response = ${tx.txResponse}")
        }
    }

    private fun <E: Throwable, R> retryForException(exceptionClass: Class<E>, numTries: Int, block: () -> R): R {
        var lastException: Throwable? = null
        for (n in 1..numTries) {
            if (lastException != null) {
                logger.warn("retrying due to exception: ${lastException.message}")
            }
            try {
                return block()
            } catch (e: Throwable) {
                if (e.javaClass == exceptionClass) {
                    lastException = e
                    continue
                }
                throw e
            }
        }
        throw lastException ?: Exception("retry limit reached without a last exception: should not get here")
    }

    private fun signTx(
        body: TxBody,
        accountNumber: Long,
        sequenceNumber: Long,
        signer: SignerMeta,
        gasEstimate: GasEstimate = GasEstimate(0),
    ): Tx {
        val authInfo = AuthInfo.newBuilder()
            .setFee(
                Fee.newBuilder()
                .addAmount(
                    CoinOuterClass.Coin.newBuilder()
                    .setDenom("nhash")
                    .setAmount(gasEstimate.fees().toString())
                    .build()
                ).setGasLimit(gasEstimate.limit())
            )
            .addSignerInfos(
                SignerInfo.newBuilder()
                .setPublicKey(
                    Keys.PubKey.newBuilder()
                        .setKey(ByteString.copyFrom(signer.compressedPublicKey))
                        .build()
                        .toAny()
                )
                .setModeInfo(
                    ModeInfo.newBuilder()
                    .setSingle(ModeInfo.Single.newBuilder().setMode(Signing.SignMode.SIGN_MODE_DIRECT).build())
                    .build()
                )
                .setSequence(sequenceNumber)
                .build()
            ).build()

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
        bytes.sha256().let {
            val privateKey = (keyPair.private as BCECPrivateKey).s
            StdSignature(
                pub_key = StdPubKey("tendermint/PubKeySecp256k1", (keyPair.public as BCECPublicKey).q.getEncoded(true)),
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
