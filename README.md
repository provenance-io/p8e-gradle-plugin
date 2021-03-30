# p8e-gradle-plugin

P8e gradle plugin allows for publishing P8e Contracts against a [P8e](https://github.com/provenance-io/p8e) environment. See [P8e docs](https://app.gitbook.com/@provenance/s/provenance-docs/p8e/overview) for relevant background and associated material.

## Status

TODO status badges

WARNING: Versions prior to `1.0.0` should be considered unstable and API changes expected.

## Overview

Having an understanding of [P8e](https://app.gitbook.com/@provenance/s/provenance-docs/p8e/overview) is strongly recommended.

// TODO finish this section

## Tasks

```text
P8e tasks
---------
p8eBootstrap - Bootstraps all scanned classes subclassing io.p8e.spec.P8eContract and com.google.protobuf.Message to one or more p8e locations.
p8eCheck - Checks contracts subclassing P8eContract against ruleset defined in the p8e-sdk.
p8eClean - Removes all generated hash files and java service provider files.
p8eJar - Builds jars for projects specified by "contractProject" and "protoProject".
```

## Minimal Example

This example is runnable from [here](https://github.com/provenance-io/p8e-gradle-plugin/blob/main/example/build.gradle).

```groovy
// This block specifies the configuration needed to connect to a p8e instance as well as the audience list
// for all of the objects that will be created.
p8e {
    // Specifies the subproject names for the project containing P8eContract subclasses, and the associated protobuf messages
    // that make up those contracts.
    contractProject = "contracts" // defaults to "contract"
    protoProject = "protos" // defaults to "proto"

    // Package locations that the ContractHash and ProtoHash source files will be written to.
    contractHashPackage = "io.p8e.contracts.example"
    protoHashPackage = "io.p8e.proto.example"

    // specifies all of the p8e locations that this plugin will bootstrap to.
    locations = [
        local: new io.provenance.p8e.plugin.P8eLocationExtension(
            url: System.getenv('API_URL'),
            privateKey: System.getenv('PRIVATE_KEY'),

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
