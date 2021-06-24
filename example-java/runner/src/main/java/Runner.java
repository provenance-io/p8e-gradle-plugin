import arrow.core.Either;
import io.p8e.ContractManager;
import io.p8e.contracts.examplejava.ExampleContracts.HelloWorldJavaContract;
import io.p8e.exception.P8eError;
import io.p8e.functional.ContractErrorHandler;
import io.p8e.functional.ContractEventHandler;
import io.p8e.proto.ContractSpecs;
import io.p8e.proto.example.HelloWorldExample;
import io.p8e.proxy.Contract;
import io.provenance.p8e.shared.extension.LoggerExtensionsKt;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;

public class Runner {

    public static void main(String[] args) throws InterruptedException {
        Logger log = LoggerExtensionsKt.logger(HelloWorldExample.class.getName());

        String p8eUrl = System.getenv("API_URL");
        String key = System.getenv("PRIVATE_KEY");

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(false);
        UUID executionUuid = UUID.randomUUID();
        UUID scopeUuid = UUID.randomUUID();
        String fistName = "First Name for " + scopeUuid;
        String middleName = "Middle Name for " + scopeUuid;
        String lastName = "Last Name for " + scopeUuid;

        ContractEventHandler<HelloWorldJavaContract> completeHandler = contract -> {
            if(UUID.fromString(contract.getStagedExecutionUuid().getValue()).equals(executionUuid)) {
                log.info("Contract Execution is successful, starting latch countdown.");
                success.set(true);
                latch.countDown();
            } else {
                log.info("ACKing contract from previous run.");
            }
            return true;
        };

        ContractErrorHandler errorHandler = envelopeError -> {
            if(UUID.fromString(envelopeError.getError().getExecutionUuid().getValue()).equals(executionUuid)) {
                log.error("Contract Execution failed, starting latch countdown. " + envelopeError.getError().getMessage());
            }
            return true;
        };

        ContractManager cm = ContractManager.Companion.create(key, p8eUrl, 60_000);
        cm.watchBuilder(HelloWorldJavaContract.class)
                .stepCompletion(completeHandler)
                .error(errorHandler)
                .watch();

        Contract<HelloWorldJavaContract> contract = cm.newContract(
                HelloWorldJavaContract.class,
                scopeUuid,
                executionUuid,
                ContractSpecs.PartyType.OWNER,
                "io.p8e.contracts.examplejava.helloWorld"
        );
        contract.addProposedFact(
                "name",
                HelloWorldExample.ExampleName.newBuilder()
                        .setFirstName(fistName)
                        .setMiddleName(middleName)
                        .setLastName(lastName)
                        .build()
        );

        Either<P8eError, Contract<HelloWorldJavaContract>> result = contract.execute();
        result.map(resultInner -> {
            log.info("Accepted with scope " + scopeUuid + " and execution " + executionUuid);

            boolean latchSuccess = false;

            try {
                latchSuccess = latch.await(120, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            if(latchSuccess && success.get()) {
                log.info("Contract completed successfully!");
            } else if (!latchSuccess) {
                log.error("Contract errored!");
            } else {
                log.error("Contract timed out!");
            }

            return null;
        }).mapLeft(p8eError -> {
            log.error("Error with envelope " + HelloWorldJavaContract.class.getName() + " " + p8eError);
            return null;
        });

        Thread.sleep(2_500);
        cm.close();
    }
}
