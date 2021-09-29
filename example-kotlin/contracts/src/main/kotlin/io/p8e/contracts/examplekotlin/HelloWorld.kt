package io.p8e.contracts.examplekotlin

import io.provenance.scope.contract.annotations.Record
import io.provenance.scope.contract.annotations.Function
import io.provenance.scope.contract.annotations.Input
import io.provenance.scope.contract.annotations.Participants
import io.provenance.scope.contract.annotations.ScopeSpecification
import io.provenance.scope.contract.annotations.ScopeSpecificationDefinition
import io.provenance.scope.contract.proto.Specifications.PartyType.*
import io.provenance.scope.contract.spec.P8eContract
import io.provenance.scope.contract.spec.P8eScopeSpecification
import io.p8e.proto.example.HelloWorldExample.ExampleName

@Participants(roles = [OWNER])
@ScopeSpecification(names = ["io.p8e.contracts.examplekotlin.helloWorld"])
open class HelloWorldContract(): P8eContract() {
    @Function(invokedBy = OWNER)
    @Record(name = "name")
    open fun name(@Input(name = "name") name: ExampleName) =
        name.toBuilder()
            .setFirstName(name.firstName.plus("-hello"))
            .setLastName(name.lastName.plus("-world"))
            .build()
}

@ScopeSpecificationDefinition(
    uuid = "ac40a8f0-fb4d-4197-99e9-818a75a3c51d",
    name = "io.p8e.contracts.examplekotlin.helloWorld",
    description = "A generic scope that allows for a lot of example hello world contracts.",
    partiesInvolved = [OWNER],
)
open class HelloWorldScopeSpecification() : P8eScopeSpecification()

data class HelloWorldData(@Record(name = "name") val name: ExampleName) {}
