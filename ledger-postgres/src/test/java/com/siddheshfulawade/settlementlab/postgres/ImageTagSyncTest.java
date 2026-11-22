package com.siddheshfulawade.settlementlab.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The CI job pre-pulls the PostgreSQL image so tests do not wait on a download. If its tag drifts
 * from the one the tests use, the pre-pull silently stops helping; this keeps the two equal. Needs
 * no container, so it runs on every OS.
 */
class ImageTagSyncTest {

    private static final Path WORKFLOW = Path.of("..", ".github", "workflows", "verify.yml");

    @Test
    void ciPrePullsTheSameImageTheTestsUse() throws IOException {
        String workflow = Files.readString(WORKFLOW);

        assertThat(workflow).contains("docker pull " + PostgresTestBase.POSTGRES_IMAGE);
    }
}
