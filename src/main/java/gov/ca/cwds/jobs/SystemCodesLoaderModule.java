package gov.ca.cwds.jobs;

import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;

import gov.ca.cwds.data.cms.SystemCodeDao;
import gov.ca.cwds.data.cms.SystemMetaDao;
import gov.ca.cwds.inject.CmsSessionFactory;
import gov.ca.cwds.inject.NsSessionFactory;
import gov.ca.cwds.jobs.SystemCodesLoaderJob.NsSystemCodeDao;
import gov.ca.cwds.rest.api.domain.cms.SystemCodeCache;
import gov.ca.cwds.rest.services.cms.CachingSystemCodeService;

//
// ============================================================================
// System codes loader Guice module
// ============================================================================
//
/**
 * System codes loader Guice module
 */
public class SystemCodesLoaderModule extends AbstractModule {

  /**
   * Default constructor.
   */
  public SystemCodesLoaderModule() {
    // Default constructor
  }

  @Override
  protected void configure() {
    // DB2 session factory:
    bind(SessionFactory.class).annotatedWith(CmsSessionFactory.class)
        .toInstance(new Configuration().configure("jobs-cms-hibernate.cfg.xml")
            .addAnnotatedClass(gov.ca.cwds.data.persistence.cms.SystemCode.class)
            .addAnnotatedClass(gov.ca.cwds.data.persistence.cms.SystemMeta.class)
            .buildSessionFactory());

    // PostgreSQL session factory:
    bind(SessionFactory.class).annotatedWith(NsSessionFactory.class)
        .toInstance(new Configuration().configure("jobs-ns-hibernate.cfg.xml")
            .addAnnotatedClass(NsSystemCode.class).buildSessionFactory());

    // DB2 tables:
    bind(SystemCodeDao.class);
    bind(SystemMetaDao.class);

    // PostgreSQL:
    bind(NsSystemCodeDao.class);
  }

  @Provides
  public SystemCodeCache provideSystemCodeCache(SystemCodeDao systemCodeDao,
      SystemMetaDao systemMetaDao) {
    final long secondsToRefreshCache = 15 * 24 * 60 * (long) 60; // 15 days
    SystemCodeCache systemCodeCache =
        new CachingSystemCodeService(systemCodeDao, systemMetaDao, secondsToRefreshCache, true);
    systemCodeCache.register();
    return systemCodeCache;
  }

}
