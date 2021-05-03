# Simple Kotlin p8e Execution

NOTE: Make sure a p8e environment is up and running. The `p8e` configuration present in `build.gradle`
is already configured to work with [p8e-docker-compose](https://github.com/provenance-io/p8e-docker-compose) out of the box.

## Steps

```bash
./gradlew clean build
./gradlew p8eClean p8eBootstrap --info
./gradlew runner:run # NOTE: we can't bootstrap and run on the same gradle command
```
