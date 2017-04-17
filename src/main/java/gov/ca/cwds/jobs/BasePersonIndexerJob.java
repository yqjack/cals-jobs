package gov.ca.cwds.jobs;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.persistence.Table;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.bulk.BulkProcessor;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.hibernate.HibernateException;
import org.hibernate.Query;
import org.hibernate.SQLQuery;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider;
import org.hibernate.query.NativeQuery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.inject.CreationException;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;

import gov.ca.cwds.dao.cms.BatchBucket;
import gov.ca.cwds.data.BaseDaoImpl;
import gov.ca.cwds.data.DaoException;
import gov.ca.cwds.data.cms.ClientDao;
import gov.ca.cwds.data.es.ElasticSearchPerson;
import gov.ca.cwds.data.es.ElasticSearchPerson.ElasticSearchPersonAddress;
import gov.ca.cwds.data.es.ElasticSearchPerson.ElasticSearchPersonPhone;
import gov.ca.cwds.data.es.ElasticsearchDao;
import gov.ca.cwds.data.model.cms.JobResultSetAware;
import gov.ca.cwds.data.persistence.PersistentObject;
import gov.ca.cwds.data.persistence.cms.ApiSystemCodeCache;
import gov.ca.cwds.data.persistence.cms.rep.ReplicatedClient;
import gov.ca.cwds.data.std.ApiAddressAware;
import gov.ca.cwds.data.std.ApiGroupNormalizer;
import gov.ca.cwds.data.std.ApiLanguageAware;
import gov.ca.cwds.data.std.ApiMultipleAddressesAware;
import gov.ca.cwds.data.std.ApiMultipleLanguagesAware;
import gov.ca.cwds.data.std.ApiMultiplePhonesAware;
import gov.ca.cwds.data.std.ApiPersonAware;
import gov.ca.cwds.data.std.ApiPhoneAware;
import gov.ca.cwds.inject.CmsSessionFactory;
import gov.ca.cwds.inject.SystemCodeCache;
import gov.ca.cwds.jobs.inject.JobsGuiceInjector;
import gov.ca.cwds.jobs.inject.LastRunFile;
import gov.ca.cwds.rest.api.domain.DomainChef;
import gov.ca.cwds.rest.api.domain.es.Person;

/**
 * Base person batch job to load clients from CMS into ElasticSearch.
 * 
 * <p>
 * This class implements {@link AutoCloseable} and automatically closes common resources, such as
 * {@link ElasticsearchDao} and Hibernate {@link SessionFactory}.
 * </p>
 * 
 * <p>
 * <strong>Auto mode ("smart" mode)</strong> takes the same parameters as last run and determines
 * whether the job has never been run. If the last run date is older than 50 years, then then assume
 * that the job is populating ElasticSearch for the first time and run all initial batch loads.
 * </p>
 * 
 * <h3>Command Line:</h3>
 * 
 * <pre>
 * {@code java gov.ca.cwds.jobs.ClientIndexerJob -c config/local.yaml -l /Users/CWS-NS3/client_indexer_time.txt}
 * </pre>
 * 
 * @author CWDS API Team
 * @param <T> storable, replicated Person persistence class
 * @param <M> MQT entity class, if any, or T
 * @see JobOptions
 */
public abstract class BasePersonIndexerJob<T extends PersistentObject, M extends ApiGroupNormalizer<?>>
    extends JobBasedOnLastSuccessfulRunTime implements AutoCloseable, JobResultSetAware<M> {

  private static final Logger LOGGER = LogManager.getLogger(BasePersonIndexerJob.class);

  private static final String INDEX_PERSON = ElasticsearchDao.DEFAULT_PERSON_IDX_NM;
  private static final String DOCUMENT_TYPE_PERSON = ElasticsearchDao.DEFAULT_PERSON_DOC_TYPE;
  private static final int DEFAULT_BATCH_WAIT = 10;
  private static final int DEFAULT_BUCKETS = 1;

  private static final int LOG_EVERY = 5000;
  private static final int ES_BULK_SIZE = 1500;

  private static final String QUERY_BUCKET_LIST =
      "SELECT z.bucket, MIN(z.identifier) AS minId, MAX(z.identifier) AS maxId, COUNT(*) AS bucketCount "
          + "FROM (SELECT (y.rn / (total_cnt/THE_TOTAL_BUCKETS)) + 1 AS bucket, y.rn, y.identifier FROM ( "
          + "SELECT c.identifier, ROW_NUMBER() OVER (ORDER BY 1) AS rn, COUNT(*) OVER (ORDER BY 1) AS total_cnt "
          + "FROM {h-schema}THE_TABLE c ORDER BY c.IDENTIFIER) y ORDER BY y.rn "
          + ") z GROUP BY z.bucket FOR READ ONLY ";

  private static ApiSystemCodeCache systemCodes;

  /**
   * Guice Injector used for all Job instances during the life of this batch JVM.
   */
  protected static Injector injector;

  /**
   * Jackson ObjectMapper.
   */
  protected final ObjectMapper mapper;

  /**
   * Main DAO for the supported persistence class.
   */
  protected final BaseDaoImpl<T> jobDao;

  /**
   * Elasticsearch client DAO.
   */
  protected final ElasticsearchDao esDao;

  /**
   * Hibernate session factory.
   */
  protected final SessionFactory sessionFactory;

  /**
   * Command line options for this job.
   */
  protected JobOptions opts;

  /**
   * Thread-safe count across all worker threads.
   */
  protected AtomicInteger recsProcessed = new AtomicInteger(0);

  /**
   * Official start time.
   */
  protected final long startTime = System.currentTimeMillis();

  protected LinkedBlockingDeque<M> denormalizedQueue = new LinkedBlockingDeque<>(150000);

  protected LinkedBlockingDeque<T> normalizedQueue = new LinkedBlockingDeque<>(50000);

  /**
   * Completion flag for method {@link #initLoadStage1ReadMaterializedRecords()}.
   */
  protected boolean isReaderDone = false;

  /**
   * Completion flag for method {@link #initLoadStage2Normalize()}.
   */
  protected boolean isNormalizerDone = false;

  /**
   * Completion flag for method {@link #initLoadStep3PushToElasticsearch()}.
   */
  protected boolean isPublisherDone = false;

  /**
   * Construct batch job instance with all required dependencies.
   * 
   * @param jobDao Person DAO, such as {@link ClientDao}
   * @param elasticsearchDao ElasticSearch DAO
   * @param lastJobRunTimeFilename last run date in format yyyy-MM-dd HH:mm:ss
   * @param mapper Jackson ObjectMapper
   * @param sessionFactory Hibernate session factory
   */
  @Inject
  public BasePersonIndexerJob(final BaseDaoImpl<T> jobDao, final ElasticsearchDao elasticsearchDao,
      @LastRunFile final String lastJobRunTimeFilename, final ObjectMapper mapper,
      @CmsSessionFactory SessionFactory sessionFactory) {
    super(lastJobRunTimeFilename);
    this.jobDao = jobDao;
    this.esDao = elasticsearchDao;
    this.mapper = mapper;
    this.sessionFactory = sessionFactory;
  }

  /**
   * Get the MQT name, if used. All child classes that rely on an MQT must define the name.
   * 
   * @return MQT name, if any
   */
  public String getMqtName() {
    return null;
  }

  @Override
  public M pullFromResultSet(ResultSet rs) throws SQLException {
    return null;
  }

  /**
   * Instantiate one Elasticsearch BulkProcessor per working thread.
   * 
   * @return Elasticsearch BulkProcessor
   */
  protected BulkProcessor buildBulkProcessor() {
    return BulkProcessor.builder(esDao.getClient(), new BulkProcessor.Listener() {
      @Override
      public void beforeBulk(long executionId, BulkRequest request) {
        LOGGER.debug("Ready to execute bulk of {} actions", request.numberOfActions());
      }

      @Override
      public void afterBulk(long executionId, BulkRequest request, BulkResponse response) {
        LOGGER.debug("Executed bulk of {} actions", request.numberOfActions());
      }

      @Override
      public void afterBulk(long executionId, BulkRequest request, Throwable failure) {
        LOGGER.error("Error executing bulk", failure);
      }
    }).setBulkActions(ES_BULK_SIZE).build();
  }

  /**
   * Build the Guice Injector once, which is used for all Job instances during the life of this
   * batch JVM.
   * 
   * @param opts command line options
   * @return Guice Injector
   * @throws JobsException if unable to construct dependencies
   */
  protected static synchronized Injector buildInjector(final JobOptions opts) throws JobsException {
    if (injector == null) {
      try {
        injector = Guice
            .createInjector(new JobsGuiceInjector(new File(opts.esConfigLoc), opts.lastRunLoc));
      } catch (CreationException e) {
        LOGGER.error("Unable to create dependencies: {}", e.getMessage(), e);
        throw new JobsException("Unable to create dependencies: " + e.getMessage(), e);
      }
    }

    return injector;
  }

  /**
   * Prepare a batch job with all required dependencies.
   * 
   * @param klass batch job class
   * @param args command line arguments
   * @return batch job, ready to run
   * @param <T> Person persistence type
   * @throws JobsException if unable to parse command line or load dependencies
   */
  public static <T extends BasePersonIndexerJob<?, ?>> T newJob(final Class<T> klass,
      String... args) throws JobsException {
    try {
      final JobOptions opts = JobOptions.parseCommandLine(args);
      final T ret = buildInjector(opts).getInstance(klass);
      ret.setOpts(opts);
      return ret;
    } catch (CreationException e) {
      LOGGER.error("Unable to create dependencies: {}", e.getMessage(), e);
      throw new JobsException("Unable to create dependencies: " + e.getMessage(), e);
    }
  }

  /**
   * Batch job entry point.
   * 
   * <p>
   * This method closes Hibernate session factory and ElasticSearch DAO automatically.
   * </p>
   * 
   * @param klass batch job class
   * @param args command line arguments
   * @param <T> Person persistence type
   * @throws JobsException unexpected runtime error
   */
  public static <T extends BasePersonIndexerJob<?, ?>> void runJob(final Class<T> klass,
      String... args) throws JobsException {
    try (final T job = newJob(klass, args)) { // Close resources automatically.
      job.run();
    } catch (IOException e) {
      LOGGER.error("UNABLE TO CLOSE RESOURCE: {}", e.getMessage(), e);
      throw new JobsException("Unable to close resource: " + e.getMessage(), e);
    } catch (JobsException e) {
      LOGGER.error("Unable to complete job: {}", e.getMessage(), e);
      throw e;
    }
  }

  /**
   * Produce an ElasticSearchPerson suitable as an Elasticsearch person document.
   * 
   * @param p ApiPersonAware persistence object
   * @return populated ElasticSearchPerson
   * @throws JsonProcessingException if unable to serialize JSON
   */
  protected ElasticSearchPerson buildESPerson(T p) throws JsonProcessingException {
    ApiPersonAware pa = (ApiPersonAware) p;
    List<String> languages = null;
    List<ElasticSearchPerson.ElasticSearchPersonPhone> phones = null;
    List<ElasticSearchPersonAddress> addresses = null;

    if (p instanceof ApiMultipleLanguagesAware) {
      ApiMultipleLanguagesAware mlx = (ApiMultipleLanguagesAware) p;
      languages = new ArrayList<>();
      for (ApiLanguageAware lx : mlx.getLanguages()) {
        final ElasticSearchPerson.ElasticSearchPersonLanguage lang =
            ElasticSearchPerson.ElasticSearchPersonLanguage.findBySysId(lx.getLanguageSysId());
        if (lang != null) {
          languages.add(lang.getDescription());
        }
      }
    } else if (p instanceof ApiLanguageAware) {
      languages = new ArrayList<>();
      ApiLanguageAware lx = (ApiLanguageAware) p;
      final ElasticSearchPerson.ElasticSearchPersonLanguage lang =
          ElasticSearchPerson.ElasticSearchPersonLanguage.findBySysId(lx.getLanguageSysId());
      if (lang != null) {
        languages.add(lang.getDescription());
      }
    }

    if (p instanceof ApiMultiplePhonesAware) {
      phones = new ArrayList<>();
      ApiMultiplePhonesAware mphx = (ApiMultiplePhonesAware) p;
      for (ApiPhoneAware phx : mphx.getPhones()) {
        phones.add(new ElasticSearchPersonPhone(phx));
      }
    } else if (p instanceof ApiPhoneAware) {
      phones = new ArrayList<>();
      ApiPhoneAware phx = (ApiPhoneAware) p;
      phones.add(new ElasticSearchPersonPhone(phx));
    }

    if (p instanceof ApiMultipleAddressesAware) {
      addresses = new ArrayList<>();
      ApiMultipleAddressesAware madrx = (ApiMultipleAddressesAware) p;
      for (ApiAddressAware adrx : madrx.getAddresses()) {
        // AutoCompletePerson.AutoCompletePersonAddress(adrx).toESPersonAddress());
        addresses.add(new ElasticSearchPersonAddress(adrx));
      }
    } else if (p instanceof ApiAddressAware) {
      addresses = new ArrayList<>();
      // addresses.add(new AutoCompletePerson.AutoCompletePersonAddress((ApiAddressAware) p)
      // .toESPersonAddress());
      addresses.add(new ElasticSearchPersonAddress((ApiAddressAware) p));
    }

    // Write persistence object to Elasticsearch Person document.
    return new ElasticSearchPerson(p.getPrimaryKey().toString(), // id
        pa.getFirstName(), // first name
        pa.getLastName(), // last name
        pa.getMiddleName(), // middle name
        pa.getNameSuffix(), // name suffix
        pa.getGender(), // gender
        DomainChef.cookDate(pa.getBirthDate()), // birth date
        pa.getSsn(), // SSN
        pa.getClass().getName(), // type
        this.mapper.writeValueAsString(p), // source
        null, // omit highlights
        addresses, // address
        phones, languages);
  }

  /**
   * Pull records changed since the last successful run.
   * 
   * <p>
   * If this job defines a denormalized (MQT) entity, then pull from that. Otherwise, pull from the
   * regular entity.
   * </p>
   * 
   * @param lastRunTime last successful run time
   * @return List of normalized entities
   */
  protected List<T> pullLastRunRecsNormal(Date lastRunTime) {
    LOGGER.info("last successful run: {}", lastRunTime);
    final Class<?> entityClass = jobDao.getEntityClass();
    final String namedQueryName = entityClass.getName() + ".findAllUpdatedAfter";
    Session session = jobDao.getSessionFactory().getCurrentSession();

    Transaction txn = null;
    try {
      txn = session.beginTransaction();
      NativeQuery<T> q = session.getNamedNativeQuery(namedQueryName);
      q.setTimestamp("after", new Timestamp(lastRunTime.getTime()));

      ImmutableList.Builder<T> results = new ImmutableList.Builder<>();
      final List<T> recs = q.list();

      LOGGER.warn("FOUND {} RECORDS", recs.size());
      results.addAll(recs);

      session.clear();
      txn.commit();
      return results.build();
    } catch (HibernateException h) {
      LOGGER.error("BATCH ERROR! {}", h.getMessage(), h);
      if (txn != null) {
        txn.rollback();
      }
      throw new DaoException(h);
    }
  }

  /**
   * @param lastRunTime last successful run time
   * @return List of normalized entities
   */
  protected List<T> pullLastRunRecsMQT(Date lastRunTime) {
    LOGGER.info("PULL MQT: last successful run: {}", lastRunTime);

    final Class<?> entityClass = getDenormalizedClass();
    final String namedQueryName = entityClass.getName() + ".findAllUpdatedAfter";
    Session session = jobDao.getSessionFactory().getCurrentSession();

    Object lastId = new Object();
    Transaction txn = null;

    try {
      txn = session.beginTransaction();
      NativeQuery<M> q = session.getNamedNativeQuery(namedQueryName);
      q.setTimestamp("after", new Timestamp(lastRunTime.getTime()));

      ImmutableList.Builder<T> results = new ImmutableList.Builder<>();
      final List<M> recs = q.list();
      LOGGER.warn("FOUND {} RECORDS", recs.size());

      // Convert from denormalized MQT to normalized.
      List<M> groupRecs = new ArrayList<>();
      for (M m : recs) {
        if (!lastId.equals(m.getGroupKey()) && !groupRecs.isEmpty()) {
          results.add(reduceSingle(groupRecs));
          groupRecs.clear();
        }

        groupRecs.add(m);
        lastId = m.getGroupKey();
      }

      if (!groupRecs.isEmpty()) {
        results.add(reduceSingle(groupRecs));
      }

      session.clear();
      txn.commit();
      return results.build();
    } catch (HibernateException h) {
      LOGGER.error("BATCH ERROR! {}", h.getMessage(), h);
      if (txn != null) {
        txn.rollback();
      }
      throw new DaoException(h);
    }
  }

  /**
   * ENTRY POINT FOR LAST RUN.
   *
   * <p>
   * Fetch all records for the next batch run, either by bucket or last successful run date.
   * </p>
   * 
   * @param lastRunDt last time the batch ran successfully.
   * @return List of results to process
   * @see gov.ca.cwds.jobs.JobBasedOnLastSuccessfulRunTime#_run(java.util.Date)
   */
  protected Date doLastRun(Date lastRunDt) {
    try {
      // One bulk processor for "last run" operations. BulkProcessor is thread-safe.
      final BulkProcessor bp = buildBulkProcessor();
      final List<T> results =
          this.isMQTNormalizer() ? pullLastRunRecsMQT(lastRunDt) : pullLastRunRecsNormal(lastRunDt);

      if (results != null && !results.isEmpty()) {
        LOGGER.info(MessageFormat.format("Found {0} people to index", results.size()));

        // Spawn a reasonable number of threads to process all results.
        results.parallelStream().forEach(p -> {
          try {
            // Write persistence object to Elasticsearch Person document.
            final ElasticSearchPerson esp = buildESPerson(p);

            // Bulk indexing! MUCH faster than indexing one doc at a time.
            bp.add(this.esDao.bulkAdd(this.mapper, esp.getId(), esp));
          } catch (JsonProcessingException e) {
            LOGGER.error("ERROR WRITING JSON: {}", e.getMessage(), e);
            throw new JobsException("ERROR WRITING JSON", e);
          }
        });

        // Track counts.
        recsProcessed.getAndAdd(results.size());
      }

      // Give it time to finish the last batch.
      LOGGER.warn("Waiting on ElasticSearch to finish last batch");
      bp.awaitClose(DEFAULT_BATCH_WAIT, TimeUnit.SECONDS);

      return new Date(this.startTime);
    } catch (JobsException e) {
      LOGGER.error("JobsException: {}", e.getMessage(), e);
      throw e;
    } catch (Exception e) {
      LOGGER.error("General Exception: {}", e.getMessage(), e);
      throw new JobsException("General Exception: " + e.getMessage(), e);
    } finally {
      isPublisherDone = true;
    }
  }

  /**
   * Build the bucket list at runtime.
   * 
   * @param table the driver table
   * @return batch buckets
   */
  @SuppressWarnings("unchecked")
  protected List<BatchBucket> buildBucketList(String table) {
    List<BatchBucket> ret = new ArrayList<>();

    Session session = jobDao.getSessionFactory().getCurrentSession();
    Transaction txn = null;
    try {
      LOGGER.info("FETCH DYNAMIC BUCKET LIST FOR TABLE {}", table);
      txn = session.beginTransaction();
      final long totalBuckets = opts.getTotalBuckets() < getJobTotalBuckets() ? getJobTotalBuckets()
          : opts.getTotalBuckets();
      final javax.persistence.Query q = jobDao.getSessionFactory().createEntityManager()
          .createNativeQuery(QUERY_BUCKET_LIST.replaceAll("THE_TABLE", table)
              .replaceAll("THE_TOTAL_BUCKETS", String.valueOf(totalBuckets)), BatchBucket.class);

      ret = q.getResultList();
      session.clear();
      txn.commit();
    } catch (HibernateException h) {
      LOGGER.error("BATCH ERROR! ", h);
      if (txn != null) {
        txn.rollback();
      }
      throw new DaoException(h);
    }

    return ret;
  }

  /**
   * Getter for the job's MQT entity class, if any, or null if none.
   * 
   * @return MQT entity class
   */
  protected Class<? extends ApiGroupNormalizer<? extends PersistentObject>> getDenormalizedClass() {
    return null;
  }

  /**
   * Default reduce method just returns the input. Child classes may customize this method to reduce
   * denormalized result sets to normalized entities.
   * 
   * @param recs entity records
   * @return unmodified entity records
   */
  @SuppressWarnings("unchecked")
  protected List<T> reduce(List<M> recs) {
    return (List<T>) recs;
  }

  /**
   * Reduce/normalize MQT records for a single grouping (such as all the same client) into a
   * normalized entity bean.
   * 
   * @param recs denormalized MQT beans
   * @return normalized entity bean instance
   */
  protected T reduceSingle(List<M> recs) {
    final List<T> list = reduce(recs);
    return list != null && !list.isEmpty() ? list.get(0) : null;
  }

  /**
   * Override to customize the default number of buckets by job.
   * 
   * @return default total buckets
   */
  protected int getJobTotalBuckets() {
    return DEFAULT_BUCKETS;
  }

  /**
   * True if the Job class intends to reduce denormalized results to normalized ones.
   * 
   * @return true if class overrides {@link #reduce(List)}
   */
  protected final boolean isMQTNormalizer() {
    return getDenormalizedClass() != null;
  }

  /**
   * Single producer, staged consumers.
   */
  protected void initLoadStage1ReadMaterializedRecords() {
    Thread.currentThread().setName("reader");
    LOGGER.warn("BEGIN: Stage #1: MQT Reader");

    try {
      Connection con = jobDao.getSessionFactory().getSessionFactoryOptions().getServiceRegistry()
          .getService(ConnectionProvider.class).getConnection();
      con.setSchema(System.getProperty("DB_CMS_SCHEMA"));
      con.setAutoCommit(false);
      con.setReadOnly(true);

      // TODO: Linux MQT lacks ORDER BY clause. Must sort manually.
      // Detect platform!
      // .append("ORDER BY x.clt_identifier ")

      StringBuilder buf = new StringBuilder();
      buf.append("SELECT x.* FROM ").append(System.getProperty("DB_CMS_SCHEMA")).append(".")
          .append(getMqtName()).append(" x FOR READ ONLY");
      final String query = buf.toString();

      try (Statement stmt = con.createStatement()) {
        stmt.setFetchSize(10000); // faster
        stmt.setMaxRows(0);
        stmt.setQueryTimeout(100000);
        ResultSet rs = stmt.executeQuery(query); // NOSONAR

        int cntr = 0;
        while (rs.next()) {
          if (++cntr > 0 && (cntr % LOG_EVERY) == 0) {
            LOGGER.info("Pulled {} MQT records", cntr);
          }
          denormalizedQueue.putLast(pullFromResultSet(rs));
        }

        con.commit();
      } finally {
        // The statement closes automatically.
      }

    } catch (Exception e) {
      LOGGER.error("BATCH ERROR! {}", e.getMessage(), e);
      throw new JobsException(e.getMessage(), e);
    } finally {
      isReaderDone = true;
    }

    LOGGER.warn("DONE: Stage #1: MQT Reader");
  }

  /**
   * Single thread consumer, second stage of initial load. Convert denormalized MQT records to
   * normalized ones and hand off to the next queue.
   */
  protected void initLoadStage2Normalize() {
    Thread.currentThread().setName("normalizer");
    LOGGER.warn("BEGIN: Stage #2: Normalizer");

    int cntr = 0;
    Object lastId = new Object();
    M m;
    List<M> groupRecs = new ArrayList<>();

    while (!(isReaderDone && denormalizedQueue.isEmpty())) {
      try {
        while ((m = denormalizedQueue.pollFirst(1, TimeUnit.SECONDS)) != null) {
          if (++cntr > 0 && (cntr % LOG_EVERY) == 0) {
            LOGGER.info("normalize {} recs", cntr);
          }

          if (!lastId.equals(m.getGroupKey()) && !groupRecs.isEmpty()) {
            normalizedQueue.putLast(reduceSingle(groupRecs));
            groupRecs.clear();
          }

          groupRecs.add(m);
          lastId = m.getGroupKey();
        }
      } catch (InterruptedException e) { // NOSONAR
        // Can safely ignore.
        LOGGER.warn("Normalizer interrupted!");
      } finally {
        isNormalizerDone = true;
      }
    }

    LOGGER.warn("DONE: Stage #2: Normalizer");
  }

  /**
   * Read from normalized record queue and push to ES.
   */
  protected void initLoadStep3PushToElasticsearch() {
    Thread.currentThread().setName("publisher");
    final BulkProcessor bp = buildBulkProcessor();
    int cntr = 0;
    T t;

    LOGGER.warn("BEGIN: Stage #3: ES Publisher");
    try {
      while (!(isReaderDone && isNormalizerDone && normalizedQueue.isEmpty())) {
        while ((t = normalizedQueue.pollFirst(5, TimeUnit.SECONDS)) != null) {
          if (++cntr > 0 && (cntr % LOG_EVERY) == 0) {
            LOGGER.info("Published {} recs to ES", cntr);
          }
          publishPerson(bp, t);
        }
      }

      LOGGER.warn("Waiting to close ES bulk processor");
      bp.awaitClose(DEFAULT_BATCH_WAIT, TimeUnit.SECONDS);

    } catch (InterruptedException e) { // NOSONAR
      LOGGER.warn("Publisher interrupted!");
      Thread.interrupted();
    } catch (JsonProcessingException e) {
      LOGGER.error("JsonProcessingException! {}", e.getMessage(), e);
      throw new JobsException("JSON error", e);
    } catch (Exception e) {
      LOGGER.error("WHAT IS THIS??? {}", e.getMessage(), e);
      throw new JobsException("Vat ist zis??", e);
    } finally {
      isPublisherDone = true;
    }

    LOGGER.warn("DONE: Stage #3: ES Publisher");
  }

  /**
   * Publish a Person record to Elasticsearch with a bulk processor.
   * 
   * @param bp {@link #buildBulkProcessor()} for this thread
   * @param t Person record to write
   * @throws JsonProcessingException if unable to serialize JSON
   */
  protected final void publishPerson(BulkProcessor bp, T t) throws JsonProcessingException {
    final ElasticSearchPerson esp = buildESPerson(t);
    bp.add(esDao.bulkAdd(mapper, esp.getId(), esp));
    recsProcessed.getAndIncrement();
  }

  /**
   * ENTRY POINT FOR INITIAL LOAD.
   */
  protected void doInitialLoadViaJdbc() {
    Thread.currentThread().setName("main");
    try {
      new Thread(this::initLoadStage1ReadMaterializedRecords).start();
      new Thread(this::initLoadStage2Normalize).start();
      new Thread(this::initLoadStep3PushToElasticsearch).start();

      while (!(isReaderDone && isNormalizerDone && isPublisherDone)) {
        LOGGER.warn("runInitialLoad: sleep");
        Thread.sleep(6000);
        try {
          this.jobDao.find("abc123"); // dummy call, keep pool alive.
        } catch (HibernateException he) {
          LOGGER.warn("USING DIRECT JDBC. IGNORE HIBERNATE ERROR: {}", he.getMessage(), he);
        } catch (Exception e) {
          LOGGER.error("initial load error: {}", e.getMessage(), e);
        }
      }

      this.close();
      final long endTime = System.currentTimeMillis();
      LOGGER.warn("TOTAL ELAPSED TIME: " + ((endTime - startTime) / 1000) + " SECONDS");
      LOGGER.warn("DONE: runInitialLoad");

    } catch (InterruptedException ie) {
      LOGGER.warn("interrupted: {}", ie.getMessage(), ie);
    } catch (Exception e) {
      LOGGER.error("GENERAL EXCEPTION: {}", e.getMessage(), e);
      throw new JobsException(e);
    }
  }

  /**
   * Lambda runs a number of threads up to max processor cores. Queued jobs wait until a worker
   * thread is available.
   * 
   * <p>
   * Auto mode ("smart" mode) takes the same parameters as last run and determines whether the job
   * has never been run. If the last run date is older than 50 years, then then assume that the job
   * is populating ElasticSearch for the first time and run all initial batch loads.
   * </p>
   * 
   * {@inheritDoc}
   * 
   * @see gov.ca.cwds.jobs.JobBasedOnLastSuccessfulRunTime#_run(java.util.Date)
   */
  @Override
  public Date _run(Date lastSuccessfulRunTime) {
    try {
      // TODO: GUICE NOT INJECTING THE CRITICAL SYSCODE TRANSLATOR. WHY???
      // ElasticSearchPerson
      // .setSystemCodes(Guice.createInjector().getInstance(ApiSystemCodeCache.class));

      ElasticSearchPerson.setSystemCodes(injector.getInstance(ApiSystemCodeCache.class));
      ElasticSearchPerson.getSystemCodes();

      // If the people index is missing, create it.
      LOGGER.debug("Create people index if missing");
      esDao.createIndexIfNeeded(ElasticsearchDao.DEFAULT_PERSON_IDX_NM);
      LOGGER.debug("availableProcessors={}", Runtime.getRuntime().availableProcessors());

      // Smart/auto mode:
      final Calendar cal = Calendar.getInstance();
      cal.add(Calendar.YEAR, -50);
      final boolean autoMode = this.opts.lastRunMode && lastSuccessfulRunTime.before(cal.getTime());

      if (autoMode) {
        LOGGER.warn("AUTO MODE!");
        getOpts().setStartBucket(1);
        getOpts().setEndBucket(1);
        getOpts().setTotalBuckets(getJobTotalBuckets());

        if (this.getDenormalizedClass() != null) {
          LOGGER.warn("LOAD FROM MQT VIA JDBC!");
          doInitialLoadViaJdbc();
        } else {
          LOGGER.warn("LOAD REPLICATED TABLE QUERY VIA HIBERNATE!");
          doInitialLoadViaHibernate();
        }

      } else if (this.opts == null || this.opts.lastRunMode) {
        LOGGER.warn("LAST RUN MODE!");
        doLastRun(lastSuccessfulRunTime);
      } else {
        LOGGER.warn("DIRECT BUCKET MODE!");
        doInitialLoadViaHibernate();
      }

      // Result stats:
      LOGGER.info("Indexed {} people", recsProcessed);
      LOGGER.info("Updating last successful run time to {}", jobDateFormat.format(startTime));
      return new Date(this.startTime);
    } catch (JobsException e) {
      LOGGER.error("JobsException: {}", e.getMessage(), e);
      throw e;
    } catch (Exception e) {
      LOGGER.error("General Exception: {}", e.getMessage(), e);
      throw new JobsException("General Exception: " + e.getMessage(), e);
    } finally {
      try {
        isReaderDone = true;
        isNormalizerDone = true;
        isPublisherDone = true;
        this.close();
      } catch (IOException io) {
        LOGGER.warn("IOException on close! {}", io.getMessage(), io);
      }
    }
  }

  @Override
  public void close() throws IOException {
    if (isReaderDone && isNormalizerDone && isPublisherDone) {
      LOGGER.warn("CLOSING CONNECTIONS!!");

      if (this.esDao != null) {
        LOGGER.warn("CLOSING ES DAO");
        this.esDao.close();
      }

      if (this.sessionFactory != null) {
        LOGGER.warn("CLOSING SESSION FACTORY");
        this.sessionFactory.close();
      }
    } else {
      LOGGER.warn("CLOSE CALL: FALSE ALARM");
    }
  }

  /**
   * Getter for this job's options.
   * 
   * @return this job's options
   */
  public JobOptions getOpts() {
    return opts;
  }

  /**
   * Setter for this job's options.
   * 
   * @param opts this job's options
   */
  public void setOpts(JobOptions opts) {
    this.opts = opts;
  }

  /**
   * Getter for CMS system code cache.
   * 
   * @return reference to CMS system code cache
   */
  public static ApiSystemCodeCache getSystemCodes() {
    return systemCodes;
  }

  /**
   * Store a reference to the singleton CMS system code cache for quick convenient access.
   * 
   * @param sysCodeCache CMS system code cache
   */
  @Inject
  public static void setSystemCodes(@SystemCodeCache ApiSystemCodeCache sysCodeCache) {
    systemCodes = sysCodeCache;
  }

  /**
   * Get the table or view used to allocate bucket ranges. Called on full load only.
   * 
   * @return the table or view used to allocate bucket ranges
   */
  protected String getBucketDriverTable() {
    String ret = null;
    final Table tbl = this.jobDao.getEntityClass().getDeclaredAnnotation(Table.class);
    if (tbl != null) {
      ret = tbl.name();
    }

    return ret;
  }

  /**
   * Return a list of partition keys to optimize batch SELECT statements. See ReplicatedClient
   * native named query, "findPartitionedBuckets".
   * 
   * @return list of partition key pairs
   * @see ReplicatedClient
   */
  protected List<Pair<String, String>> getPartitionRanges() {
    LOGGER.warn("DETERMINE BUCKET RANGES ...");
    List<Pair<String, String>> ret = new ArrayList<>();
    List<BatchBucket> buckets = buildBucketList(getBucketDriverTable());

    for (BatchBucket b : buckets) {
      LOGGER.warn("BUCKET RANGE: {} to {}", b.getMinId(), b.getMaxId());
      ret.add(Pair.of(b.getMinId(), b.getMaxId()));
    }

    return ret;
  }

  // ===========================
  // DEPRECATED:
  // ===========================

  /**
   * Divide work into buckets: pull a unique range of identifiers so that no bucket results overlap.
   * 
   * @param minId start of identifier range
   * @param maxId end of identifier range
   * @return collection of entity results
   */
  protected List<T> pullBucketRange(String minId, String maxId) {
    LOGGER.info("PULL BUCKET RANGE {} to {}", minId, maxId);
    final Class<?> entityClass =
        getDenormalizedClass() != null ? getDenormalizedClass() : jobDao.getEntityClass();
    final String namedQueryName = entityClass.getName() + ".findBucketRange";
    Session session = jobDao.getSessionFactory().getCurrentSession();

    Transaction txn = null;
    try {
      txn = session.beginTransaction();
      NativeQuery<T> q = session.getNamedNativeQuery(namedQueryName);
      q.setString("min_id", minId).setString("max_id", maxId);

      // No reduction/normalization.
      ImmutableList.Builder<T> results = new ImmutableList.Builder<>();
      results.addAll(q.list());

      session.clear();
      txn.commit();
      return results.build();
    } catch (HibernateException h) {
      LOGGER.error("BATCH ERROR! {}", h.getMessage(), h);
      if (txn != null) {
        txn.rollback();
      }
      throw new DaoException(h);
    }
  }

  /**
   * Pull replicated records from named query "findBucketRange".
   * 
   * <p>
   * Thread safety: both BulkProcessor are ElasticsearchDao are thread-safe.
   * </p>
   * 
   * @return number of records processed
   */
  protected int doInitialLoadViaHibernate() {
    final List<Pair<String, String>> buckets = getPartitionRanges();

    for (Pair<String, String> b : buckets) {
      final String minId = b.getLeft();
      final String maxId = b.getRight();
      final List<T> results = pullBucketRange(minId, maxId);
      pullBucketRange(b.getLeft(), b.getRight());

      if (results != null && !results.isEmpty()) {
        final BulkProcessor bp = buildBulkProcessor();
        results.stream().forEach(p -> {
          try {
            final ElasticSearchPerson esp = buildESPerson(p);
            bp.add(esDao.bulkAdd(mapper, esp.getId(), esp));
          } catch (JsonProcessingException e) {
            throw new JobsException("JSON error", e);
          }
        });

        try {
          bp.awaitClose(DEFAULT_BATCH_WAIT, TimeUnit.SECONDS);
        } catch (Exception e2) {
          throw new JobsException("ES bulk processor interrupted!", e2);
        }

        // Track counts.
        recsProcessed.getAndAdd(results.size());
      }
    }

    return recsProcessed.get();
  }

  /**
   * Working approach tells Hibernate to collect results from native query into parent/child
   * objects.
   * 
   * <p>
   * Down side: complicated syntax. Must tell Hibernate how to join aliased tables.
   * </p>
   * 
   * @param jobDao DAO for T
   * @param rootEntity root parent entity class, typically type T
   * @param minId start of identifier range
   * @param maxId end of identifier range
   * @return List of T entity
   * @deprecated use method {@link #doInitialLoadViaHibernate()}
   */
  @Deprecated
  protected List<T> partitionedBucketEntities(BaseDaoImpl<T> jobDao, Class<?> rootEntity,
      String minId, String maxId) {
    final String namedQueryName = jobDao.getEntityClass().getName() + ".findPartitionedBuckets";
    Session session = jobDao.getSessionFactory().getCurrentSession();

    Transaction txn = null;
    try {
      txn = session.beginTransaction();
      final Query q = session.getNamedQuery(namedQueryName);
      SQLQuery query = session.createSQLQuery(q.getQueryString());
      query.setString("min_id", minId).setString("max_id", maxId);

      // NamedQuery comments describe join conditions.
      boolean hibernateJoins =
          StringUtils.isNotBlank(q.getComment()) && !q.getComment().equals(namedQueryName);

      if (hibernateJoins) {
        query.addEntity("a", rootEntity);
        final String[] blocks = q.getComment().trim().split(";");
        for (String block : blocks) {
          final String[] terms = block.trim().split(",");
          query.addJoin(terms[0], terms[1]);
        }
      } else {
        query.addEntity("z", rootEntity);
      }

      ImmutableList.Builder<T> results = new ImmutableList.Builder<>();
      final List<Object[]> raw = query.list();
      List<T> answers = new ArrayList<>(raw.size());

      if (hibernateJoins) {
        for (Object[] arr : raw) {
          answers.add((T) arr[0]);
        }
      } else {
        for (Object obj : raw) {
          answers.add((T) obj);
        }
      }

      results.addAll(answers);
      session.clear();
      txn.commit();
      return results.build();

    } catch (HibernateException h) {
      LOGGER.error("BATCH ERROR! ", h);
      if (txn != null) {
        txn.rollback();
      }
      throw new DaoException(h);
    }
  }

  /**
   * Indexes a <strong>SINGLE</strong> document. Prefer batch mode.
   * 
   * <p>
   * <strong>TODO:</strong> Add document mapping after creating index.
   * </p>
   * 
   * @param person {@link Person} document to index
   * @throws JsonProcessingException if JSON cannot be read
   * @deprecated
   */
  @Deprecated
  protected void indexDocument(T person) throws JsonProcessingException {
    esDao.index(INDEX_PERSON, DOCUMENT_TYPE_PERSON, mapper.writeValueAsString(person),
        person.getPrimaryKey().toString());
  }

}

