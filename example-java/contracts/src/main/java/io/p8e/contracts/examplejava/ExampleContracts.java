package io.p8e.contracts.examplejava;

import io.p8e.annotations.*;
import io.p8e.proto.ContractScope.Scope;
import io.p8e.proto.ContractSpecs.PartyType;
import io.p8e.proto.example.HelloWorldExample.ExampleName;
import io.p8e.spec.P8eContract;
import io.p8e.spec.P8eScopeSpecification;

public class ExampleContracts {

    @Participants(roles = {PartyType.OWNER})
    @ScopeSpecification(names = {"io.p8e.contracts.examplejava.helloWorld"})
    public static class HelloWorldJavaContract extends P8eContract {
        @Function(invokedBy = PartyType.OWNER)
        @Fact(name = "name")
        public ExampleName name(@Input(name = "name") ExampleName name) {
            return name.toBuilder()
                       .setFirstName(name.getFirstName() + "-hello")
                       .setLastName(name.getLastName() + "-world")
                       .build();
        }
    }

    @ScopeSpecificationDefinition(
        name = "io.p8e.contracts.examplejava.helloWorld",
        description = "A generic scope that allows for a lot of example hello world contracts.",
        partiesInvolved = {PartyType.OWNER}
    )
    public class HelloWorldScopeSpecification extends P8eScopeSpecification { }
}

