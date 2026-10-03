package bikesched.core.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bikesched.core.BikeschedException;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StepStoreTest extends AbstractStoreTest {

  @Test
  void stepLifecycleStoresResultAndCountsAttempts() throws SQLException {
    UUID node = registerNode("node-1");
    insertJobs(1, 0, "now()");
    UUID jobId = database.jobs().claimJobs(node, 1).getFirst().id();

    assertThat(database.steps().findCompletedStep(jobId, "a")).isEmpty();
    assertThat(database.steps().startStep(jobId, "a")).contains(1);
    database.steps().failStep(jobId, "a", "boom");
    assertThat(database.steps().findCompletedStep(jobId, "a"))
        .as("failed is not completed")
        .isEmpty();
    assertThat(
            countSteps(
                "name = 'a' and state = 'FAILED' and last_error = 'boom' and finished_at is not null"))
        .isEqualTo(1);

    assertThat(database.steps().startStep(jobId, "a")).as("second attempt").contains(2);
    assertThat(countSteps("name = 'a' and state = 'RUNNING' and finished_at is null")).isEqualTo(1);
    database.steps().completeStep(jobId, "a", "{\"x\": 1}");

    assertThat(database.steps().findCompletedStep(jobId, "a"))
        .contains(new StepStore.CompletedStep("{\"x\": 1}", false, 2));
    assertThat(database.steps().startStep(jobId, "a")).as("no restart once succeeded").isEmpty();
    assertThat(
            countSteps(
                "name = 'a' and state = 'SUCCEEDED' and attempts = 2 and finished_at is not null"))
        .isEqualTo(1);

    assertThat(database.steps().startStep(jobId, "b")).contains(1);
    database.steps().completeStep(jobId, "b", null);
    assertThat(database.steps().findCompletedStep(jobId, "b"))
        .contains(new StepStore.CompletedStep(null, false, 1));
  }

  @Test
  void completeStepRejectsInvalidJson() throws SQLException {
    UUID node = registerNode("node-1");
    insertJobs(1, 0, "now()");
    UUID jobId = database.jobs().claimJobs(node, 1).getFirst().id();
    database.steps().startStep(jobId, "a");

    assertThatThrownBy(() -> database.steps().completeStep(jobId, "a", "not json"))
        .isInstanceOf(BikeschedException.class)
        .hasMessage("Storing the result of step 'a' of bikesched job " + jobId + " failed");
    assertThat(countSteps("name = 'a' and state = 'RUNNING'")).isEqualTo(1);
  }
}
