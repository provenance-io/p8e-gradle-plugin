package io.provenance.p8e.plugin

import com.google.protobuf.Any
import com.google.protobuf.Message
import cosmos.base.v1beta1.CoinOuterClass
import cosmos.tx.v1beta1.ServiceOuterClass.BroadcastMode
import cosmos.tx.v1beta1.TxOuterClass.TxBody
import io.grpc.ManagedChannel
import io.provenance.client.common.extensions.toCoin
import io.provenance.client.common.gas.prices.constGasPrice
import io.provenance.client.grpc.BaseReqSigner
import io.provenance.client.grpc.GasEstimationMethod
import io.provenance.client.grpc.PbClient
import io.provenance.client.grpc.floatingGasPrices
import io.provenance.metadata.v1.ContractSpecificationRequest
import io.provenance.metadata.v1.ContractSpecificationResponse
import io.provenance.metadata.v1.ScopeSpecificationRequest
import io.provenance.metadata.v1.ScopeSpecificationResponse
import org.slf4j.Logger
import java.net.URI
import java.util.concurrent.TimeUnit

fun Collection<Message>.toTxBody(): TxBody = TxBody.newBuilder()
    .addAllMessages(this.map { it.toAny() })
    .build()
fun Message.toAny(typeUrlPrefix: String = "") = Any.pack(this, typeUrlPrefix)

class ProvenanceClient(channel: ManagedChannel, val logger: Logger, val location: P8eLocationExtension) {
    private val inner = PbClient(
        location.chainId!!,
        URI(location.provenanceUrl!!),
        floatingGasPrices(
            GasEstimationMethod.MSG_FEE_CALCULATION,
            constGasPrice(
                location.txGasPrice?.takeIf{ gasPriceString -> gasPriceString.isNotBlank() }?.toDouble()?.toCoin("nhash")?.also { logger.info("Using provided gas price of ${it.amount}${it.denom}") } ?:
                CoinOuterClass.Coin.newBuilder().setAmount("19050").setDenom("nhash").build().also { logger.info("Using default gas price of ${it.amount}${it.denom}") }
            )
        ),
        channel = channel
    )
    private val queryTimeoutSeconds = location.provenanceQueryTimeoutSeconds.toLong()

    fun scopeSpecification(request: ScopeSpecificationRequest): ScopeSpecificationResponse =
        inner.metadataClient.withDeadlineAfter(queryTimeoutSeconds, TimeUnit.SECONDS).scopeSpecification(request)

    fun contractSpecification(request: ContractSpecificationRequest): ContractSpecificationResponse =
        inner.metadataClient.withDeadlineAfter(queryTimeoutSeconds, TimeUnit.SECONDS).contractSpecification(request)

    private class SequenceMismatch(message: String): Exception(message)
    fun writeTx(signer: BaseReqSigner, txBody: TxBody) {
        retryForException(SequenceMismatch::class.java, 5) {
            val response = inner.estimateAndBroadcastTx(
                txBody,
                signers = listOf(signer),
                mode = BroadcastMode.BROADCAST_MODE_BLOCK, // faux block, will poll in background
                gasAdjustment = location.txFeeAdjustment.toDouble(),
                txHashHandler = { logger.trace("Preparing to broadcast $it") }
            )

            if (response.txResponse.code != 0) {
                val message = "error broadcasting tx (code ${response.txResponse.code}, rawLog: ${response.txResponse.rawLog})"
                if (response.txResponse.rawLog.contains("account sequence mismatch")) {
                    throw SequenceMismatch(message)
                }
                throw Exception(message)
            }

            logger.info("sent tx = ${response.txResponse.txhash}")
            logger.trace("tx response = ${response.txResponse}")
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
}