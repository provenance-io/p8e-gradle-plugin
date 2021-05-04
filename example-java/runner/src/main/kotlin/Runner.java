import arrow.core.Either;
import io.p8e.ContractManager;
import io.p8e.contracts.example.HelloWorldContract;
import io.p8e.exception.P8eError;
import io.p8e.functional.ContractErrorHandler;
import io.p8e.functional.ContractEventHandler;
import io.p8e.proto.ContractSpecs;
import io.p8e.proto.contract.HelloWorldExample;
import io.p8e.proxy.Contract;
import io.provenance.core.extensions.LoggerExtensionsKt;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import kotlin.jvm.functions.Function1;
import org.slf4j.Logger;
import io.provenance.core.extensions.ProvenanceExtensionsKt;

public class Runner {

    public static void main(String[] args) throws InterruptedException {
        Logger log = LoggerExtensionsKt.logger(HelloWorldExample.class.getName());

        String p8eUrl = System.getenv("ENV_URL");
        String key = System.getenv("AFFILIATE_KEY");

        CountDownLatch latch = new CountDownLatch(1);
        final boolean[] success = {false};
        UUID executionUuid = UUID.randomUUID();
        UUID scopeUuid = UUID.randomUUID();
        String fistName = "First Name for " + scopeUuid;
        String middleName = "Middle Name for " + scopeUuid;
        String lastName = "Last Name for " + scopeUuid;

        ContractEventHandler<HelloWorldContract> completeHandler = contract -> {
            if(ProvenanceExtensionsKt.toUuidProv(contract.getStagedExecutionUuid()) == executionUuid) {
                log.info("Contract Execution is successful, starting latch countdown.");
                success[0] = true;
                latch.countDown();
            } else {
                log.info("ACKing contract from previous run.");
            }
            return true;
        };

        ContractErrorHandler errorHandler = envelopeError -> {
            if(ProvenanceExtensionsKt.toUuidProv(envelopeError.getExecutionUuid()) == executionUuid) {
                log.error("Contract Execution failed, starting latch countdown. " + envelopeError.getMessage());
            }
            return true;
        };

        ContractManager cm = ContractManager.Companion.create(key, p8eUrl);
        cm.watchBuilder(HelloWorldContract.class)
            .stepCompletion(completeHandler)
            .error(errorHandler)
            .watch();

        Contract<HelloWorldContract> contract = cm.newContract(HelloWorldContract.class, ProvenanceExtensionsKt.toProtoUuidProv(scopeUuid), ContractSpecs.PartyType.OWNER, executionUuid);
        contract.addProposedFact(
            "name",
            HelloWorldExample.ExampleName.newBuilder()
                .setFirstName(fistName)
                .setMiddleName(middleName)
                .setLastName(lastName)
                .build()
        );

        Either<P8eError, Contract<HelloWorldContract>> result = contract.execute();
        result.map(new Function1<Contract<HelloWorldContract>, Object>() {
            @Override
            public Object invoke(Contract<HelloWorldContract> helloWorldContractContract) {
                log.info("Accepted with scope " + scopeUuid + " and execution " + executionUuid);

                boolean latchSuccess = false;
                try {
                    latchSuccess = latch.await(120, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                if(latchSuccess && success[0]) {
                    log.info("Contract completed successfully!");
                } else if (!latchSuccess) {
                    log.error("Contract errored!");
                } else {
                    log.error("Contract timed out!");
                }

                return null;
            }
        }).mapLeft(new Function1<P8eError, Object>() {
            @Override
            public Object invoke(P8eError p8eError) {
                log.error("Error with envelope " + HelloWorldContract.class.getName() + " " + p8eError);
                return null;
            }
        }
        );

        Thread.sleep(2_500);
        cm.close();
    }
}
