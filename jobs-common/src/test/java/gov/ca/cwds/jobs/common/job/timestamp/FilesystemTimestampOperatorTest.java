package gov.ca.cwds.jobs.common.job.timestamp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.time.LocalDateTime;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Created by Alexander Serbin on 2/6/2018.
 */
public class FilesystemTimestampOperatorTest {

  private LastRunDirHelper lastRunDirHelper = new LastRunDirHelper("temp");

  @Test
  public void readWriteTimestampTest() throws Exception {
    LocalDateTime timestamp = LocalDateTime.of(2018, 2, 6, 4, 14, 20);
    FilesystemTimestampOperator timestampOperator = new FilesystemTimestampOperator(
        lastRunDirHelper.getLastRunDir().toString());
    assertFalse(timestampOperator.timeStampExists());
    timestampOperator.writeTimestamp(timestamp);
    assertEquals(timestamp, timestampOperator.readTimestamp());
    assertTrue(timestampOperator.timeStampExists());
  }

  @Before
  public void beforeMethod() throws IOException {
    lastRunDirHelper.createTimestampDirectory();
  }

  @After
  public void afterMethod() throws IOException {
    lastRunDirHelper.deleteTimestampDirectory();
  }

}