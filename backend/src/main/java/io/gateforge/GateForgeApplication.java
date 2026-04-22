package io.gateforge;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;

@QuarkusMain
public class GateForgeApplication {
    public static void main(String... args) {
        Quarkus.run(args);
    }
}
