# p8e-gradle-plugin

p8e gradle plugin allows for publishing p8e Contracts against a [p8e](https://github.com/provenance-io/p8e) environment. See [p8e docs](https://docs.provenance.io/p8e/overview) for relevant background and associated material.

## Status

[![Latest Release][release-badge]][release-latest]
[![Code Coverage][code-coverage-badge]][code-coverage-report]
[![License][license-badge]][license-url]
[![LOC][loc-badge]][loc-report]

[code-coverage-badge]: https://codecov.io/gh/provenance-io/p8e-gradle-plugin/branch/main/graph/badge.svg
[code-coverage-report]: https://app.codecov.io/gh/provenance-io/p8e-gradle-plugin

[release-badge]: https://img.shields.io/github/v/tag/provenance-io/p8e-gradle-plugin.svg?sort=semver
[release-latest]: https://github.com/provenance-io/p8e-gradle-plugin/releases/latest

[license-badge]: https://img.shields.io/github/license/provenance-io/p8e-gradle-plugin.svg
[license-url]: https://github.com/provenance-io/p8e-gradle-plugin/blob/main/LICENSE

[loc-badge]: https://tokei.rs/b1/github/provenance-io/p8e-gradle-plugin
[loc-report]: https://github.com/provenance-io/p8e-gradle-plugin

NOTE: Versions prior to `0.5.0` are meant to bootstrap contracts with the legacy P8e Execution Environment.

WARNING: Versions prior to `1.0.0` should be considered unstable and API changes expected.

## Overview

Having an understanding of the [Provenance Metadata module](https://docs.provenance.io/modules/metadata-module) is strongly recommended.

In order to execute contracts with the [Provenance Scope SDK](https://github.com/provenance-io/p8e-scope-sdk), the contracts must be published into
your execution environment. This gradle plugin provides a set of tasks in order to accomplish that. Publishing contracts performs the following
high level actions in order to allow contracts to be executed:

- An uberjar is built which contains a set of contracts and all associated protobuf messages included in them. This uberjar is persisted to
p8e's encrypted [object-store](https://github.com/provenance-io/object-store). This allows p8e to later pull it and make use of a
[Class Loader](https://docs.oracle.com/javase/7/docs/api/java/lang/ClassLoader.html) to load it at runtime.
- A concrete implementation of [ContractHash](https://github.com/provenance-io/p8e-scope-sdk/blob/main/contract-base/src/main/kotlin/io/provenance/scope/contract/contracts/ContractHash.kt)
is generated and stored alongside your source code. Similarly, an implementation of
[ProtoHash](https://github.com/provenance-io/p8e-scope-sdk/blob/main/contract-proto/src/main/kotlin/io/provenance/scope/contract/proto/ProtoHash.kt)
is also generated. These classes will be built into the jars that are depended on by your application that will
execute contracts. These classes provide a mapping from P8eContracts and their associated protobuf messages to the hash of the
uberjar they are contained within. Ultimately, the sdk will make use of these classes via the
[service provider](https://docs.oracle.com/javase/8/docs/api/java/util/ServiceLoader.html) facility.

## Tasks

```text
P8e tasks
---------
p8eBootstrap - Bootstraps all scanned classes subclassing io.p8e.spec.P8eContract and com.google.protobuf.Message to one or more p8e locations.
p8eCheck - Checks contracts subclassing P8eContract against ruleset defined in the sdk.
p8eClean - Removes all generated hash files and java service provider files.
p8eJar - Builds jars for projects specified by "contractProject" and "protoProject".
```

## Usage

Below is a sample gradle block written in `groovy`.

```groovy
plugins {
    id "io.provenance.p8e.p8e-publish" version "<see latest release>"
}

// This block specifies the configuration needed to connect to a p8e instance as well as the audience list
// for all of the objects that will be created.
p8e {
    // Specifies the subproject names for the project containing P8eContract subclasses, and the associated protobuf messages
    // that make up those contracts.
    contractProject = "contracts" // defaults to "contract"
    protoProject = "protos" // defaults to "proto"

    // Output source classes will be written in this language.
    language = "java" // defaults to java - current options are ["java", "kt"]
    // Package locations that the ContractHash and ProtoHash source files will be written to.
    contractHashPackage = "io.p8e.contracts.example"
    protoHashPackage = "io.p8e.proto.example"
    
    // Specifies the root packages to search in when building contractHash and protoHash classes. Defaults to ["io", "com"]
    includePackages = ["io", "com"]
    
    // specifies all of the p8e locations that this plugin will bootstrap to.
    locations = [
        local: new io.provenance.p8e.plugin.P8eLocationExtension(
            osUrl: System.getenv('OS_GRPC_URL'),
            provenanceUrl: System.getenv('PROVENANCE_GRPC_URL'),
            chainId: System.getenv('CHAIN_ID'),
            encryptionPrivateKey: System.getenv('ENCRYPTION_PRIVATE_KEY'),
            signingPrivateKey: System.getenv('SIGNING_PRIVATE_KEY'),
            txBatchSize: "10",

            audience: [
                local1: new io.provenance.p8e.plugin.P8ePartyExtension(
                    publicKey: "0A41046C57E9E25101D5E553AE003E2F79025E389B51495607C796B4E95C0A94001FBC24D84CD0780819612529B803E8AD0A397F474C965D957D33DD64E642B756FBC4"
                ),
                local2: new io.provenance.p8e.plugin.P8ePartyExtension(
                    publicKey: "0A4104D630032378D56229DD20D08DBCC6D31F44A07D98175966F5D32CD2189FD748831FCB49266124362E56CC1FAF2AA0D3F362BF84CACBC1C0C74945041EB7327D54"
                ),
                local3: new io.provenance.p8e.plugin.P8ePartyExtension(
                    publicKey: "0A4104CD5F4ACFFE72D323CCCB2D784847089BBD80EC6D4F68608773E55B3FEADC812E4E2D7C4C647C8C30352141D2926130D10DFC28ACA5CA8A33B7BD7A09C77072CE"
                ),
                local4: new io.provenance.p8e.plugin.P8ePartyExtension(
                    publicKey: "0A41045E4B322ED16CD22465433B0427A4366B9695D7E15DD798526F703035848ACC8D2D002C1F25190454C9B61AB7B243E31E83BA2B48B8A4441F922A08AC3D0A3268"
                ),
                local5: new io.provenance.p8e.plugin.P8ePartyExtension(
                    publicKey: "0A4104A37653602DA20D27936AF541084869B2F751953CB0F0D25D320788EDA54FB4BC9FB96A281BFFD97E64B749D78C85871A8E14AFD48048537E45E16F3D2FDDB44B"
                )
            ]
        )
    ]
}
```
