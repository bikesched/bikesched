package bikesched.core;

import java.time.Duration;
import java.time.Instant;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * Starts bikesched against the Postgres from {@code compose.yaml} ({@code docker compose up -d}),
 * registers a {@code print} job handler, schedules a few demo jobs (now and in 15 seconds) and
 * keeps the node running until the JVM is stopped, e.g. with Ctrl+C.
 *
 * <p>Run with {@code ./gradlew :bikesched-core:runStarter} or {@code ... runStarter --args=my-node}
 * to set the node name (default: host name). Honors {@code POSTGRES_PORT} like the compose file
 * (default 5432).
 */
public final class Starter {

    private Starter() {}

    public static void main(String[] args) throws InterruptedException {
        var dataSource = new PGSimpleDataSource();
        dataSource.setServerNames(new String[] {"localhost"});
        dataSource.setPortNumbers(new int[] {Integer.parseInt(System.getenv().getOrDefault("POSTGRES_PORT", "5432"))});
        dataSource.setDatabaseName("bikesched");
        dataSource.setUser("bikesched");
        dataSource.setPassword("bikesched");

        var builder = Bikesched.builder().dataSource(dataSource);
        if (args.length > 0) {
            builder.node(args[0]);
        }
        Bikesched bikesched = builder.build();
        Runtime.getRuntime().addShutdownHook(new Thread(bikesched::close));

        bikesched.registerHandler("print", job -> {
            System.out.println(
                    "[" + Thread.currentThread().getName() + "] printing job " + job.id()
                            + " (attempt " + job.attempts() + "/" + job.maxAttempts() + "): " + job.payload());
            int randomOneOrZero = (int) (Math.random() * 2);
            if (randomOneOrZero == 1)
                throw new RuntimeException("random failure");
        });

        System.out.println("bikesched node '" + bikesched.nodeName() + "' (" + bikesched.nodeId()
                + ") running, press Ctrl+C to stop");

        for (int i = 1; i <= 3; i++) {
            bikesched.schedule("print", "{\"n\": " + i + "}");
        }
        bikesched.schedule("print", "{\"delayed\": true}", Instant.now().plus(Duration.ofSeconds(15)));

        Thread.currentThread().join();
    }
}
