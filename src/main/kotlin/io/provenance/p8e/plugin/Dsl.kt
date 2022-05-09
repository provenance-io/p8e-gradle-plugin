package io.provenance.p8e.plugin

// TODO implement type safe builders
// https://kotlinlang.org/docs/type-safe-builders.html#how-it-works
open class P8ePartyExtension {
    var publicKey: String = ""
}

open class P8eLocationExtension {
    var encryptionPrivateKey: String? = ""
    var signingPrivateKey: String? = ""
    var osUrl: String? = ""
    var provenanceUrl: String? = ""
    var audience: Map<String, P8ePartyExtension> = emptyMap()
    var chainId: String? = ""
    var mainNet: Boolean = chainId == "pio-mainnet-1"
    var txBatchSize: String = "10"
    var txFeeAdjustment: String = "1.25"
    var osHeaders: Map<String, String> = emptyMap()
}

open class P8eExtension {
    var contractProject: String = "contract"
    var protoProject: String = "proto"
    // TODO what is a good default package path that is somehow derived from the current project?
    var contractHashPackage: String = ""
    var protoHashPackage: String = ""
    var includePackages: Array<String> = arrayOf("io", "com")
    var language: String = "java"
    var locations: Map<String, P8eLocationExtension> = emptyMap()
}
