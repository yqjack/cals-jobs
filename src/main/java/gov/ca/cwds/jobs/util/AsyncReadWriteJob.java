package gov.ca.cwds.jobs.util;

import java.util.LinkedList;
import java.util.List;

import gov.ca.cwds.jobs.Job;
import gov.ca.cwds.jobs.JobsException;

/**
 * Created by dmitry.rudenko on 4/28/2017.
 */
@SuppressWarnings("unchecked")
public class AsyncReadWriteJob extends ProducerConsumer implements Job, JobComponent {

  private int chunkSize = 100;
  private JobReader reader;
  private List chunk = new LinkedList<>();
  private JobProcessor processor;
  private JobWriter writer;

  /**
   * @param reader reader
   * @param processor processor
   * @param writer writer
   * @param <I> output reader type
   * @param <O> output writer type
   */
  public <I, O> AsyncReadWriteJob(JobReader<I> reader, JobProcessor<I, O> processor,
      JobWriter<O> writer) {
    this.reader = reader;
    this.processor = processor;
    this.writer = writer;
  }

  /**
   * Input type = Output Type, no mapping required
   * 
   * @param reader reader
   * @param writer writer
   * 
   * @param <I> output reader type
   */
  public <I> AsyncReadWriteJob(JobReader<I> reader, JobWriter<I> writer) {
    this.reader = reader;
    this.processor = item -> item;
    this.writer = writer;
  }

  public void setChunkSize(int chunkSize) {
    if (chunkSize > 0) {
      this.chunkSize = chunkSize;
    }
  }

  @Override
  public Object produce() {
    try {
      return reader.read();
    } catch (Exception e) {
      throw new JobsException(e);
    }
  }

  @Override
  public void consume(Object o) {
    try {
      Object out = processor.process(o);
      chunk.add(out);
      if (chunk.size() == chunkSize) {
        flush();
      }
    } catch (Exception e) {
      chunk.clear();
      throw new JobsException(e);
    }
  }

  private void flush() throws Exception {
    writer.write(chunk);
    chunk.clear();
  }

  @SuppressWarnings("ThrowFromFinallyBlock")
  @Override
  public void run() {
    try {
      init();
      super.run();
      if (!chunk.isEmpty()) {
        flush();
      }
    } catch (Exception e) {
      throw new JobsException(e);
    } finally {
      try {
        destroy();
      } catch (Exception e) {
        throw new JobsException(e);
      }
    }
  }

  @Override
  public void init() throws Exception {
    reader.init();
    writer.init();
  }

  @Override
  public void destroy() throws Exception {
    reader.destroy();
    writer.destroy();
  }
}