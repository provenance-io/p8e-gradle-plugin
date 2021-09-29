package io.p8e.contracts.examplejava;

import io.provenance.scope.contract.annotations.Record;
import io.provenance.scope.contract.annotations.Function;
import io.provenance.scope.contract.annotations.Input;
import io.provenance.scope.contract.annotations.Participants;
import io.provenance.scope.contract.annotations.ScopeSpecification;
import io.provenance.scope.contract.annotations.ScopeSpecificationDefinition;
import io.provenance.scope.contract.proto.Specifications.PartyType;
import io.provenance.scope.contract.spec.P8eContract;
import io.provenance.scope.contract.spec.P8eScopeSpecification;
import io.p8e.proto.example.HelloWorldExample.ExampleName;

public class ExampleContracts {

    @Participants(roles = {PartyType.OWNER})
    @ScopeSpecification(names = {"io.p8e.contracts.examplejava.helloWorld"})
    public static class HelloWorldJavaContract extends P8eContract {
        @Function(invokedBy = PartyType.OWNER)
        @Record(name = "name")
        public ExampleName name(@Input(name = "name") ExampleName name) {
            return name.toBuilder()
                       .setFirstName(name.getFirstName() + "-hello")
                       .setLastName(name.getLastName() + "-world")
                       .build();
        }
    }

    @ScopeSpecificationDefinition(
        uuid = "7d5ed7f4-d847-4780-8e4a-1d2e9f431e04",
        name = "io.p8e.contracts.examplejava.helloWorld",
        description = "A generic scope that allows for a lot of example hello world contracts.",
        partiesInvolved = {PartyType.OWNER}
    )
    public class HelloWorldScopeSpecification extends P8eScopeSpecification { }
}

