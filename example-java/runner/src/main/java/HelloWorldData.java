package io.p8e.examplejava;

import io.p8e.annotations.Fact;
import io.p8e.proto.example.HelloWorldExample;

public class HelloWorldData {
    private HelloWorldExample.ExampleName name;
    private io.p8e.proto.ContractScope.Scope scope;

    public HelloWorldData(
            @Fact(name = "name") HelloWorldExample.ExampleName name,
            io.p8e.proto.ContractScope.Scope scope
    ) {
        this.name = name;
    }

    public HelloWorldExample.ExampleName getName() {
        return name;
    }

    public io.p8e.proto.ContractScope.Scope getScope() {
        return scope;
    }
}
