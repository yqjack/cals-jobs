package gov.ca.cwds.jobs.common.job;

import gov.ca.cwds.jobs.common.RecordChangeOperation;
import gov.ca.cwds.jobs.common.batch.JobBatch;
import gov.ca.cwds.jobs.common.batch.JobBatchIterator;
import gov.ca.cwds.jobs.common.identifier.ChangedEntityIdentifier;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * Created by Alexander Serbin on 3/7/2018.
 */
public class BatchTestSavePointBatchIterator implements JobBatchIterator {

  private static LocalDateTime FIRST_TIMESTAMP = null;
  public static LocalDateTime SECOND_TIMESTAMP = LocalDateTime.of(2013, 1, 1, 1, 1, 1);
  private static LocalDateTime THIRD_TIMESTAMP = LocalDateTime.of(2014, 2, 2, 2, 2, 2);
  private static LocalDateTime FOURTH_TIMESTAMP = LocalDateTime.of(2015, 3, 3, 3, 3, 3);
  public static BrokenEntity BROKEN_ENTITY = new BrokenEntity("brokenId", RecordChangeOperation.I,
      THIRD_TIMESTAMP);

  private LinkedList<JobBatch> batches;

  @Override
  public void init() {
    batches = new LinkedList<>(
        Arrays.asList(new JobBatch(Collections.emptyList(), FIRST_TIMESTAMP),
            new JobBatch(Collections.emptyList(), SECOND_TIMESTAMP),
            new BrokenBatch(THIRD_TIMESTAMP),
            new JobBatch(Collections.emptyList(), FOURTH_TIMESTAMP)));
  }

  @Override
  public List<JobBatch> getNextPortion() {
    JobBatch nextBatch = batches.poll();
    return nextBatch != null ? Collections.singletonList(nextBatch) : Collections.emptyList();
  }

  private static class BrokenBatch extends JobBatch {

    public BrokenBatch(LocalDateTime timestamp) {
      super(Collections.singletonList(BROKEN_ENTITY), timestamp);
    }
  }

  private static class BrokenEntity extends ChangedEntityIdentifier {

    public BrokenEntity(String id, RecordChangeOperation recordChangeOperation,
        LocalDateTime timestamp) {
      super(id, recordChangeOperation, timestamp);
    }
  }

}
