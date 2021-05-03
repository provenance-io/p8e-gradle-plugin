package io.p8e.contracts.examplekotlin

import io.p8e.annotations.Fact
import io.p8e.annotations.Function
import io.p8e.annotations.Input
import io.p8e.annotations.Participants
import io.p8e.annotations.ScopeSpecification
import io.p8e.annotations.ScopeSpecificationDefinition
import io.p8e.proto.ContractScope.Scope
import io.p8e.proto.ContractSpecs.PartyType.*
import io.p8e.proto.example.HelloWorldExample.ExampleName
import io.p8e.spec.P8eContract
import io.p8e.spec.P8eScopeSpecification

@Participants(roles = [OWNER])
@ScopeSpecification(names = ["io.p8e.contracts.examplekotlin.helloWorld"])
open class HelloWorldContract(): P8eContract() {
    @Function(invokedBy = OWNER)
    @Fact(name = "name")
    open fun name(@Input(name = "name") name: ExampleName) =
        name.toBuilder()
            .setFirstName(name.firstName.plus("-hello"))
            .setLastName(name.lastName.plus("-world"))
            .build()
}

@ScopeSpecificationDefinition(
    name = "io.p8e.contracts.examplekotlin.helloWorld",
    description = "A generic scope that allows for a lot of example hello world contracts.",
    partiesInvolved = [OWNER],
)
open class HelloWorldScopeSpecification() : P8eScopeSpecification()

data class HelloWorldData(@Fact(name = "name") val name: ExampleName, val scope: Scope) {}
