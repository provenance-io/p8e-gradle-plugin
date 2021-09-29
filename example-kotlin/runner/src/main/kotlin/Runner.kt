import io.p8e.ContractManager
import io.p8e.contracts.examplekotlin.HelloWorldContract
import io.p8e.exception.message
import io.p8e.proto.ContractSpecs
import io.p8e.proto.example.HelloWorldExample
import io.p8e.util.toUuidProv
import io.provenance.p8e.shared.extension.logger
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

fun main() {
    val log = logger()
    val p8eUrl = System.getenv("API_URL")
    val key = System.getenv("PRIVATE_KEY")

    val latch = CountDownLatch(1)
    var success = false
    val executionUuid = UUID.randomUUID()
    val scopeUuid = UUID.randomUUID()
    val firstName = "First Name For $scopeUuid"
    val middleName = "Middle Name For $scopeUuid"
    val lastName = "Last Name For $scopeUuid"

    val cm = ContractManager.create(key, p8eUrl)
    cm.watchBuilder(HelloWorldContract::class.java)
        .stepCompletion {
            if(it.getStagedExecutionUuid().toUuidProv() == executionUuid) {
                log.info("Contract Execution is successful, starting latch countdown.")

                success = true
                latch.countDown()
            } else {
                log.info("ACKing contract from previous run.")
            }

            true
        }
        .error {
            if(it.error.executionUuid.toUuidProv() == executionUuid) {
                log.error("Contract Execution failed, starting latch countdown. ${it.error.message}")

                latch.countDown()
            }
            true
        }
        .also { it.watch() }

    val contract = cm.newContract(
        contractClazz = HelloWorldContract::class.java,
        scopeUuid = scopeUuid,
        executionUuid = executionUuid,
        invokerRole = ContractSpecs.PartyType.OWNER,
    ).apply {
        addProposedFact(
            "name",
            HelloWorldExample.ExampleName.newBuilder()
                .setFirstName(firstName)
                .setMiddleName(middleName)
                .setLastName(lastName)
                .build()
        )
    }

    contract.execute().map {
        log.info("Accepted with scope $scopeUuid and execution $executionUuid")

        val latchSuccess = latch.await(120, TimeUnit.SECONDS)

        if (latchSuccess && success) {
            log.info("Contract completed successfully!")
        } else if (latchSuccess) {
            log.error("Contract errored!")
        } else {
            log.error("Contract timed out!")
        }
    }.mapLeft {
        log.error("Error with envelope ${it::class.java.name} ${it.message()}")
    }

    // allow final ACK to be sent to server
    Thread.sleep(2_500)

    cm.close()
}
