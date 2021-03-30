package io.provenance.p8e.plugin

import io.p8e.ContractManager
import io.p8e.proto.Common.ProvenanceReference
import io.p8e.proto.PK
import io.p8e.spec.ContractSpecMapper
import io.provenance.p8e.encryption.ecies.ECUtils
import io.provenance.p8e.encryption.util.ByteUtil
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPrivateKey
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPublicKey
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jce.spec.ECPrivateKeySpec
import org.bouncycastle.jce.spec.ECPublicKeySpec
import org.bouncycastle.util.encoders.Hex
import org.gradle.api.Project
import java.io.File
import java.io.FileInputStream
import java.net.URLClassLoader
import java.security.KeyFactory
import java.security.KeyPair
import java.security.PublicKey
import java.security.Security

internal class Bootstrapper(
    private val project: Project,
    val extension: P8eExtension
) {

    init {
        Security.addProvider(BouncyCastleProvider())
    }

    private fun validate() {
        extension.locations.forEach { name, location ->
            require(!location.privateKey.isNullOrBlank()) { "privateKey is required for location $name" }
            require(!location.url.isNullOrBlank()) { "url is required for location $name" }
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
        val protos = findProtos(contractClassLoader)

        extension.locations.forEach { name, location ->
            project.logger.info("Publishing contracts - location: $name url: ${location.url}")

            val manager = ContractManager.create(getKeyPair(location.privateKey!!), location.url!!)
            val contractJarLocation = storeObject(manager, contractJar, location)
                .also {
                    require (it.hash == hashes.getOrDefault(contractKey, it.hash)) {
                        "Received different hash for the same contract jar ${it.hash} ${hashes.getValue(contractKey)}"
                    }

                    hashes.put(contractKey, it.hash)
                }
            val protoJarLocation = storeObject(manager, protoJar, location)
                .also {
                    require (it.hash == hashes.getOrDefault(protoKey, it.hash)) {
                        "Received different hash for the same proto jar ${it.hash} ${hashes.getValue(protoKey)}"
                    }

                    hashes.put(protoKey, it.hash)
                }

            contracts
                .map { clazz -> ContractSpecMapper.dehydrateSpec(clazz.kotlin, contractJarLocation, protoJarLocation) }
                .takeUnless { it.isNullOrEmpty() }
                .also { specs -> project.logger.info("Saving ${specs?.size ?: 0} contract specifications") }
                ?.let { specs -> manager.client.addSpec(specs) }
                ?: throw IllegalStateException("Could not find any subclasses of io.p8e.spec.P8eContract in ${contractJar.path}")
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

    fun storeObject(manager: ContractManager, jar: File, location: P8eLocationExtension): ProvenanceReference {
        return manager.client.storeObject(FileInputStream(jar), location.audience.values.map { it.toPublicKey() }.toSet())
            .ref
            .also { project.logger.info("Saved jar ${jar.path} with hash ${it.hash}") }
    }

    fun getKeyPair(privateKey: String): KeyPair {
        // compute private key from string
        val protoPrivateKey = PK.PrivateKey.parseFrom(Hex.decode(privateKey))
        val keyFactory = KeyFactory.getInstance("ECDH", "BC")
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
        val keyFactory = KeyFactory.getInstance("ECDH", "BC")
        val ecSpec = ECNamedCurveTable.getParameterSpec(ECUtils.LEGACY_DIME_CURVE)
        val point = ecSpec.curve.decodePoint(protoPublicKey.publicKeyBytes.toByteArray())
        val publicKeySpec = ECPublicKeySpec(point, ecSpec)

        return BCECPublicKey(keyFactory.algorithm, publicKeySpec, BouncyCastleProvider.CONFIGURATION)
    }
}
