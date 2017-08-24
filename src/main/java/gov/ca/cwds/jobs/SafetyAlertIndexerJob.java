package gov.ca.cwds.jobs;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.update.UpdateRequest;
import org.hibernate.SessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;

import gov.ca.cwds.dao.cms.ReplicatedSafetyAlertsDao;
import gov.ca.cwds.data.es.ElasticSearchPerson;
import gov.ca.cwds.data.es.ElasticSearchSafetyAlert;
import gov.ca.cwds.data.es.ElasticsearchDao;
import gov.ca.cwds.data.persistence.PersistentObject;
import gov.ca.cwds.data.persistence.cms.EsSafetyAlert;
import gov.ca.cwds.data.persistence.cms.ReplicatedSafetyAlerts;
import gov.ca.cwds.data.std.ApiGroupNormalizer;
import gov.ca.cwds.inject.CmsSessionFactory;
import gov.ca.cwds.jobs.exception.JobsException;
import gov.ca.cwds.jobs.inject.LastRunFile;
import gov.ca.cwds.jobs.util.jdbc.JobResultSetAware;
import gov.ca.cwds.jobs.util.transform.EntityNormalizer;

/**
 * Job to load person referrals from CMS into ElasticSearch.
 * 
 * @author CWDS API Team
 */
public class SafetyAlertIndexerJob
    extends BasePersonIndexerJob<ReplicatedSafetyAlerts, EsSafetyAlert>
    implements JobResultSetAware<EsSafetyAlert> {

  private static final Logger LOGGER = LoggerFactory.getLogger(SafetyAlertIndexerJob.class);

  /**
   * Construct the object
   * 
   * @param clientDao DAO
   * @param esDao ES SAO
   * @param lastJobRunTimeFilename Last runtime file
   * @param mapper Object mapper
   * @param sessionFactory Session factory
   */
  @Inject
  public SafetyAlertIndexerJob(ReplicatedSafetyAlertsDao clientDao, ElasticsearchDao esDao,
      @LastRunFile String lastJobRunTimeFilename, ObjectMapper mapper,
      @CmsSessionFactory SessionFactory sessionFactory) {
    super(clientDao, esDao, lastJobRunTimeFilename, mapper, sessionFactory);
  }

  @Override
  protected Class<? extends ApiGroupNormalizer<? extends PersistentObject>> getDenormalizedClass() {
    return EsSafetyAlert.class;
  }

  @Override
  public String getInitialLoadViewName() {
    return "VW_LST_SAFETY_ALERT";
  }

  @Override
  public String getJdbcOrderBy() {
    return " ORDER BY CLIENT_ID ";
  }

  @Override
  protected String getLegacySourceTable() {
    return "SAF_ALRT";
  }

  @Override
  public String getInitialLoadQuery(String dbSchemaName) {
    StringBuilder buf = new StringBuilder();
    buf.append("SELECT x.* FROM ");
    buf.append(dbSchemaName);
    buf.append(".");
    buf.append(getInitialLoadViewName());
    buf.append(" x ");

    if (!getOpts().isLoadSealedAndSensitive()) {
      buf.append(" WHERE x.CLIENT_SENSITIVITY_IND = 'N' ");
    }

    buf.append(getJdbcOrderBy()).append(" FOR READ ONLY");
    return buf.toString();
  }

  @Override
  public boolean mustDeleteLimitedAccessRecords() {
    /**
     * If sealed or sensitive data must NOT be loaded then any records indexed with sealed or
     * sensitive flag must be deleted.
     */
    return !getOpts().isLoadSealedAndSensitive();
  }

  @Override
  protected ReplicatedSafetyAlerts normalizeSingle(List<EsSafetyAlert> recs) {
    return normalize(recs).get(0);
  }

  @Override
  protected List<ReplicatedSafetyAlerts> normalize(List<EsSafetyAlert> recs) {
    return EntityNormalizer.<ReplicatedSafetyAlerts, EsSafetyAlert>normalizeList(recs);
  }

  @Override
  protected UpdateRequest prepareUpsertRequest(ElasticSearchPerson esp,
      ReplicatedSafetyAlerts safetyAlerts) throws IOException {
    StringBuilder buf = new StringBuilder();
    buf.append("{\"safety_alerts\":[");

    List<ElasticSearchSafetyAlert> esSafetyAlerts = safetyAlerts.getSafetyAlerts();
    esp.setSafetyAlerts(esSafetyAlerts);

    if (esSafetyAlerts != null && !esSafetyAlerts.isEmpty()) {
      try {
        buf.append(esSafetyAlerts.stream().map(this::jsonify).sorted(String::compareTo)
            .collect(Collectors.joining(",")));
      } catch (Exception e) {
        LOGGER.error("ERROR SERIALIZING SAFETY ALERTS", e);
        throw new JobsException(e);
      }
    }

    buf.append("]}");

    final String updateJson = buf.toString();
    final String insertJson = mapper.writeValueAsString(esp);
    LOGGER.trace("insertJson: {}", insertJson);
    LOGGER.trace("updateJson: {}", updateJson);

    final String alias = esDao.getConfig().getElasticsearchAlias();
    final String docType = esDao.getConfig().getElasticsearchDocType();

    return new UpdateRequest(alias, docType, esp.getId()).doc(updateJson)
        .upsert(new IndexRequest(alias, docType, esp.getId()).source(insertJson));
  }

  @Override
  public EsSafetyAlert extract(ResultSet rs) throws SQLException {
    EsSafetyAlert safetyAlert = new EsSafetyAlert();

    safetyAlert.setClientId(ifNull(rs.getString("CLIENT_ID")));
    safetyAlert.setAlertId(ifNull(rs.getString("ALERT_ID")));
    safetyAlert.setActivationCountyCode(rs.getInt("ACTIVATION_COUNTY_CD"));
    safetyAlert.setActivationDate(rs.getDate("ACTIVATION_DATE"));
    safetyAlert.setActivationExplanation(ifNull(rs.getString("ACTIVATION_EXPLANATION")));
    safetyAlert.setActivationReasonCode(rs.getInt("ACTIVATION_REASON_CD"));
    safetyAlert.setDeactivationCountyCode(rs.getInt("DEACTIVATION_COUNTY_CD"));
    safetyAlert.setDeactivationDate(rs.getDate("DEACTIVATION_DATE"));
    safetyAlert.setDeactivationExplanation(ifNull(rs.getString("DEACTIVATION_EXPLANATION")));
    safetyAlert.setLastUpdatedId(ifNull(rs.getString("LAST_UPDATED_ID")));
    safetyAlert.setLastUpdatedTimestamp(rs.getTimestamp("LAST_UPDATED_TS"));
    safetyAlert.setLastUpdatedOperation(ifNull(rs.getString("ALERT_IBMSNAP_OPERATION")));
    safetyAlert.setReplicationTimestamp(rs.getTimestamp("ALERT_IBMSNAP_LOGMARKER"));
    safetyAlert.setLastChanged(rs.getTimestamp("LAST_CHANGED"));

    return safetyAlert;
  }

  /**
   * Batch job entry point.
   * 
   * @param args command line arguments
   */
  public static void main(String... args) {
    runMain(SafetyAlertIndexerJob.class, args);
  }
}