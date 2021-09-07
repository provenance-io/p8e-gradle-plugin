package io.p8e.examplejava;

import io.p8e.annotations.Fact;
import io.p8e.proto.example.HelloWorldExample;
import io.p8e.proto.ContractScope.Scope;

public class HelloWorldData {
    private HelloWorldExample.ExampleName name;
    private Scope scope;

    public HelloWorldData(
            @Fact(name = "name") HelloWorldExample.ExampleName name,
            Scope scope
    ) {
        this.name = name;
    }

    public HelloWorldExample.ExampleName getName() {
        return name;
    }

    public Scope getScope() {
        return scope;
    }
}
