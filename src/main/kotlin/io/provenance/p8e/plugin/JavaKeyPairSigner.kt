package io.provenance.p8e.plugin

import com.google.protobuf.ByteString
import cosmos.crypto.secp256k1.Keys
import io.provenance.client.grpc.Signer
import io.provenance.scope.encryption.util.getAddress
import tech.figure.hdwallet.common.hashing.sha256
import tech.figure.hdwallet.ec.extensions.toECPrivateKey
import tech.figure.hdwallet.ec.extensions.toECPublicKey
import tech.figure.hdwallet.signer.BCECSigner
import java.security.KeyPair

class JavaKeyPairSigner(private val keyPair: KeyPair, private val mainNet: Boolean) : Signer {
    override fun address(): String = keyPair.public.getAddress(mainNet)

    override fun pubKey(): Keys.PubKey = Keys.PubKey
        .newBuilder()
        .setKey(ByteString.copyFrom(keyPair.public.toECPublicKey().compressed()))
        .build()

    override fun sign(data: ByteArray): ByteArray =
        BCECSigner()
            .sign(keyPair.private.toECPrivateKey(), data.sha256())
            .encodeAsBTC()
            .toByteArray()
}