package gov.ca.cwds.generic.data.persistence.cms.rep;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import gov.ca.cwds.data.es.ElasticSearchLegacyDescriptor;
import gov.ca.cwds.data.persistence.PersistentObject;
import gov.ca.cwds.data.persistence.cms.BaseReporter;
import gov.ca.cwds.data.std.ApiGroupNormalizer;
import gov.ca.cwds.generic.jobs.util.transform.ElasticTransformer;
import gov.ca.cwds.rest.api.domain.cms.LegacyTable;
import java.util.Date;
import java.util.Map;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Table;
import org.hibernate.annotations.NamedNativeQueries;
import org.hibernate.annotations.NamedNativeQuery;
import org.hibernate.annotations.Type;

/**
 * {@link PersistentObject} representing a Reporter as {@link CmsReplicatedEntity}.
 * 
 * @author CWDS API Team
 */
@NamedNativeQueries({
    @NamedNativeQuery(
        name = "gov.ca.cwds.data.persistence.cms.rep.ReplicatedReporter.findBucketRange",
        query = "SELECT trim(z.RPTR_BDGNO) RPTR_BDGNO, trim(z.RPTR_CTYNM) RPTR_CTYNM, "
            + "z.COL_RELC, z.CMM_MTHC, z.CNFWVR_IND, z.FDBACK_DOC, trim(z.RPTR_EMPNM) RPTR_EMPNM, "
            + "z.FEEDBCK_DT, z.FB_RQR_IND, trim(z.RPTR_FSTNM) RPTR_FSTNM, trim(z.RPTR_LSTNM) RPTR_LSTNM, "
            + "z.MNRPTR_IND, z.MSG_EXT_NO, z.MSG_TEL_NO, trim(z.MID_INI_NM) MID_INI_NM, "
            + "trim(z.NMPRFX_DSC) NMPRFX_DSC, z.PRM_TEL_NO, z.PRM_EXT_NO, z.STATE_C, "
            + "trim(z.RPTR_ST_NM) RPTR_ST_NM, trim(z.RPTR_ST_NO) RPTR_ST_NO, "
            + "trim(z.SUFX_TLDSC) SUFX_TLDSC, z.RPTR_ZIPNO, z.LST_UPD_ID, z.LST_UPD_TS, "
            + "z.FKREFERL_T, z.FKLAW_ENFT, z.ZIP_SFX_NO, z.CNTY_SPFCD "
            + ", z.IBMSNAP_OPERATION, z.IBMSNAP_LOGMARKER FROM {h-schema}REPTR_T z "
            + "WHERE z.FKREFERL_T > :min_id AND z.FKREFERL_T <= :max_id FOR READ ONLY WITH UR",
        resultClass = ReplicatedReporter.class, readOnly = true),
    @NamedNativeQuery(
        name = "gov.ca.cwds.data.persistence.cms.rep.ReplicatedReporter.findAllUpdatedAfter",
        query = "SELECT trim(z.RPTR_BDGNO) RPTR_BDGNO, trim(z.RPTR_CTYNM) RPTR_CTYNM, "
            + "z.COL_RELC, z.CMM_MTHC, z.CNFWVR_IND, z.FDBACK_DOC, trim(z.RPTR_EMPNM) RPTR_EMPNM, "
            + "z.FEEDBCK_DT, z.FB_RQR_IND, trim(z.RPTR_FSTNM) RPTR_FSTNM, trim(z.RPTR_LSTNM) RPTR_LSTNM, "
            + "z.MNRPTR_IND, z.MSG_EXT_NO, z.MSG_TEL_NO, trim(z.MID_INI_NM) MID_INI_NM, "
            + "trim(z.NMPRFX_DSC) NMPRFX_DSC, z.PRM_TEL_NO, z.PRM_EXT_NO, z.STATE_C, "
            + "trim(z.RPTR_ST_NM) RPTR_ST_NM, trim(z.RPTR_ST_NO) RPTR_ST_NO, "
            + "trim(z.SUFX_TLDSC) SUFX_TLDSC, z.RPTR_ZIPNO, z.LST_UPD_ID, z.LST_UPD_TS, "
            + "z.FKREFERL_T, z.FKLAW_ENFT, z.ZIP_SFX_NO, z.CNTY_SPFCD "
            + ", z.IBMSNAP_OPERATION, z.IBMSNAP_LOGMARKER FROM {h-schema}REPTR_T z "
            + "WHERE z.IBMSNAP_LOGMARKER >= :after FOR READ ONLY WITH UR ",
        resultClass = ReplicatedReporter.class)})
@Entity
@Table(name = "REPTR_T")
@JsonPropertyOrder(alphabetic = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReplicatedReporter extends BaseReporter
    implements CmsReplicatedEntity, ApiGroupNormalizer<ReplicatedReporter> {

  /**
   * Default.
   */
  private static final long serialVersionUID = 1L;

  @Enumerated(EnumType.STRING)
  @Column(name = "IBMSNAP_OPERATION", updatable = false)
  private CmsReplicationOperation replicationOperation;

  @Type(type = "timestamp")
  @Column(name = "IBMSNAP_LOGMARKER", updatable = false)
  private Date replicationDate;

  // =======================
  // CmsReplicatedEntity:
  // =======================

  @Override
  public CmsReplicationOperation getReplicationOperation() {
    return replicationOperation;
  }

  @Override
  public void setReplicationOperation(CmsReplicationOperation replicationOperation) {
    this.replicationOperation = replicationOperation;
  }

  @Override
  public Date getReplicationDate() {
    return replicationDate;
  }

  @Override
  public void setReplicationDate(Date replicationDate) {
    this.replicationDate = replicationDate;
  }

  // =======================
  // ApiGroupNormalizer:
  // =======================

  @SuppressWarnings("unchecked")
  @Override
  public Class<ReplicatedReporter> getNormalizationClass() {
    return (Class<ReplicatedReporter>) this.getClass();
  }

  @Override
  public ReplicatedReporter normalize(Map<Object, ReplicatedReporter> map) {
    return null;
  }

  @Override
  public String getNormalizationGroupKey() {
    return this.getPrimaryKey();
  }

  // =======================
  // ApiLegacyAware:
  // =======================

  @Override
  public String getLegacyId() {
    return getId();
  }

  @Override
  public String getId() {
    return getReferralId();
  }

  @Override
  public ElasticSearchLegacyDescriptor getLegacyDescriptor() {
    return ElasticTransformer.createLegacyDescriptor(getId(), getLastUpdatedTime(),
        LegacyTable.REPORTER);
  }
}