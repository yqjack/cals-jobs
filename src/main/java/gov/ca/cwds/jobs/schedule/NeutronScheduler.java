package gov.ca.cwds.jobs.schedule;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.TriggerKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;

import gov.ca.cwds.jobs.BasePersonIndexerJob;
import gov.ca.cwds.jobs.component.AtomJobScheduler;
import gov.ca.cwds.jobs.component.AtomRocketFactory;
import gov.ca.cwds.jobs.component.FlightRecord;
import gov.ca.cwds.jobs.config.JobOptions;
import gov.ca.cwds.jobs.exception.NeutronException;
import gov.ca.cwds.jobs.util.JobLogs;

public class NeutronScheduler implements AtomJobScheduler {

  private static final Logger LOGGER = LoggerFactory.getLogger(NeutronScheduler.class);

  private Scheduler scheduler;

  /**
   * For unit tests where resources either may not close properly or where expensive resources
   * should be mocked.
   */
  private boolean testMode = false;

  private final FlightRecorder flightRecorder;

  private final AtomRocketFactory rocketFactory;

  private JobOptions opts;

  /**
   * Scheduled jobs.
   */
  private final Map<Class<?>, NeutronJobMgtFacade> scheduleRegistry = new ConcurrentHashMap<>();

  /**
   * Possibly not necessary. Listeners and running jobs should handle this, but we still need a
   * single place to track rockets in flight.
   * 
   * <p>
   * OPTION: Quartz scheduler can track this too. Obsolete implementation?
   * </p>
   */
  private final Map<TriggerKey, NeutronInterruptableJob> executingJobs = new ConcurrentHashMap<>();

  @Inject
  public NeutronScheduler(final FlightRecorder jobHistory, final AtomRocketFactory rocketFactory) {
    this.flightRecorder = jobHistory;
    this.rocketFactory = rocketFactory;
  }

  /**
   * Build a registered job.
   * 
   * @param klass batch job class
   * @param opts command line arguments
   * @return the job
   * @throws NeutronException unexpected runtime error
   */
  @SuppressWarnings("rawtypes")
  public BasePersonIndexerJob createJob(final Class<?> klass, final JobOptions opts)
      throws NeutronException {
    return this.rocketFactory.createJob(klass, opts);
  }

  /**
   * Build a registered job.
   * 
   * @param jobName batch job class
   * @param opts command line arguments
   * @return the job
   * @throws NeutronException unexpected runtime error
   */
  @SuppressWarnings("rawtypes")
  public BasePersonIndexerJob createJob(final String jobName, final JobOptions opts)
      throws NeutronException {
    return this.rocketFactory.createJob(jobName, opts);
  }

  @Override
  public FlightRecord runScheduledJob(Class<?> klass, JobOptions opts) throws NeutronException {
    try {
      LOGGER.info("Run scheduled job: {}", klass.getName());
      final BasePersonIndexerJob<?, ?> job = createJob(klass, opts);
      job.run();
      return job.getTrack();
    } catch (Exception e) {
      throw JobLogs.checked(LOGGER, e, "ON-DEMAND JOB RUN FAILED!: {}", e.getMessage());
    }
  }

  @Override
  public FlightRecord runScheduledJob(String jobName, JobOptions opts) throws NeutronException {
    try {
      final Class<?> klass = Class.forName(jobName);
      return runScheduledJob(klass, opts);
    } catch (Exception e) {
      throw JobLogs.checked(LOGGER, e, "ON-DEMAND JOB RUN FAILED!: {}", e.getMessage());
    }
  }

  @Override
  public NeutronJobMgtFacade scheduleJob(Class<?> klazz, NeutronDefaultJobSchedule sched) {
    final NeutronJobMgtFacade nj =
        new NeutronJobMgtFacade(this.getScheduler(), sched, flightRecorder);
    this.getScheduleRegistry().put(klazz, nj);
    return nj;
  }

  public void stopScheduler(boolean waitForJobsToComplete) throws NeutronException {
    LOGGER.warn("STOP SCHEDULER! wait for jobs to complete: {}", waitForJobsToComplete);
    try {
      this.getScheduler().shutdown(waitForJobsToComplete);
    } catch (SchedulerException e) {
      throw JobLogs.checked(LOGGER, e, "FAILED TO STOP SCHEDULER! {}", e.getMessage());
    }
  }

  public void startScheduler() throws NeutronException {
    LOGGER.warn("START SCHEDULER!");
    try {
      this.getScheduler().start();
    } catch (SchedulerException e) {
      LOGGER.error("FAILED TO START SCHEDULER! {}", e.getMessage(), e);
      throw JobLogs.checked(LOGGER, e, "FAILED TO START SCHEDULER! {}", e.getMessage());
    }
  }

  @Override
  public void addExecutingJob(final TriggerKey key, NeutronInterruptableJob job) {
    executingJobs.put(key, job);
  }

  public void removeExecutingJob(final TriggerKey key) {
    if (executingJobs.containsKey(key)) {
      executingJobs.remove(key);
    }
  }

  public Map<TriggerKey, NeutronInterruptableJob> getExecutingJobs() {
    return executingJobs;
  }

  public AtomRocketFactory getRocketFactory() {
    return rocketFactory;
  }

  public JobOptions getOpts() {
    return opts;
  }

  public void setOpts(JobOptions opts) {
    this.opts = opts;
  }

  public Scheduler getScheduler() {
    return scheduler;
  }

  public void setScheduler(Scheduler scheduler) {
    this.scheduler = scheduler;
  }

  public Map<Class<?>, NeutronJobMgtFacade> getScheduleRegistry() {
    return scheduleRegistry;
  }

  @Override
  public boolean isJobVetoed(String className) throws NeutronException {
    Class<?> klazz = null;
    try {
      klazz = Class.forName(className);
    } catch (Exception e) {
      throw JobLogs.buildCheckedException(LOGGER, e, "UNKNOWN JOB CLASS! {}", className, e);
    }
    return this.getScheduleRegistry().get(klazz).isVetoExecution();
  }

}
