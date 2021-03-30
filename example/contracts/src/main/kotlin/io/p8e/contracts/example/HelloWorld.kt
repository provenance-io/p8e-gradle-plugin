package io.p8e.contracts.example

import io.p8e.annotations.Fact
import io.p8e.annotations.Function
import io.p8e.annotations.Input
import io.p8e.annotations.Participants
import io.p8e.annotations.ScopeSpecification
import io.p8e.proto.ContractScope.Scope
import io.p8e.proto.ContractSpecs.PartyType.*
import io.p8e.proto.example.HelloWorldExample.ExampleName
import io.p8e.spec.P8eContract

@Participants(roles = [OWNER])
@ScopeSpecification(names = ["io.p8e.contracts.example.helloWorld"])
open class HelloWorldContract(): P8eContract() {
    @Function(invokedBy = OWNER)
    @Fact(name = "name")
    open fun name(@Input(name = "name") name: ExampleName) =
        name.toBuilder()
            .setFirstName(name.firstName.plus("-hello"))
            .setLastName(name.lastName.plus("-world"))
            .build()
}


data class HelloWorldData(@Fact(name = "name") val name: ExampleName, val scope: Scope) {}
