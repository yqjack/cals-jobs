-- DB2 View/MQT
-- ORDER BY clause is only valid on mainframe, remove it if running on other hosts.
CREATE TABLE CWSRS1.ES_CASE_HIST AS (
SELECT
CAS.IDENTIFIER AS CASE_ID,
CAS.START_DT AS START_DATE,
CAS.END_DT AS END_DATE,
CAS.GVR_ENTC AS COUNTY,
CAS.SRV_CMPC AS SERVICE_COMP,
CAS.IBMSNAP_LOGMARKER AS CASE_LAST_UPDATED,
CAS.IBMSNAP_OPERATION AS CASE_LAST_OPERATION,
CLC.COM_FST_NM AS FOCUS_CHLD_FIRST_NM,
CLC.COM_LST_NM AS FOCUS_CHLD_LAST_NM,
CLC.IDENTIFIER AS FOCUS_CHILD_ID,
CLC.IBMSNAP_LOGMARKER AS FOCUS_CHILD_LAST_UPDATED,
CLC.IBMSNAP_OPERATION AS FOCUS_CHILD_LAST_OPERATION,
CLP.COM_FST_NM AS PARENT_FIRST_NM,
CLP.COM_LST_NM AS PARENT_LAST_NM,
CLR.CLNTRELC AS PARENT_RELATIONSHIP,
CLP.IDENTIFIER AS PARENT_ID,
CLP.IBMSNAP_LOGMARKER AS PARENT_LAST_UPDATED,
CLP.IBMSNAP_OPERATION AS PARENT_LAST_OPERATION,
'CLIENT_T' AS PARENT_SOURCE_TABLE,
STF.FIRST_NM AS WORKER_FIRST_NM,
STF.LAST_NM AS WORKER_LAST_NM,
STF.IDENTIFIER AS WORKER_ID,
STF.IBMSNAP_LOGMARKER AS WORKER_LAST_UPDATED,
STF.IBMSNAP_OPERATION AS WORKER_LAST_OPERATION,
MAX(CAS.IBMSNAP_LOGMARKER,
  CLC.IBMSNAP_LOGMARKER,
  CLP.IBMSNAP_LOGMARKER,
  STF.IBMSNAP_LOGMARKER) LAST_CHG
FROM CWSRS1.CASE_T CAS
LEFT OUTER JOIN CWSRS1.CHLD_CLT CCL ON CCL.FKCLIENT_T = CAS.FKCHLD_CLT
LEFT OUTER JOIN CWSRS1.CLIENT_T CLC ON CLC.IDENTIFIER = CCL.FKCLIENT_T AND CLC.SOC158_IND ='N' AND CLC.SENSTV_IND = 'N'
JOIN CWSRS1.CLN_RELT CLR ON CLR.FKCLIENT_0 = CCL.FKCLIENT_T AND ((CLR.CLNTRELC BETWEEN 187 and 214) OR
(CLR.CLNTRELC BETWEEN 245 and 254) OR (CLR.CLNTRELC BETWEEN 282 and 294) OR (CLR.CLNTRELC IN (272, 273, 5620, 6360, 6361)))
JOIN CWSRS1.CLIENT_T CLP ON CLP.IDENTIFIER = CLR.FKCLIENT_T AND CLP.SOC158_IND ='N' AND CLP.SENSTV_IND = 'N'
LEFT OUTER JOIN CWSRS1.STFPERST STF ON STF.IDENTIFIER = CAS.FKSTFPERST
UNION
SELECT
CAS.IDENTIFIER AS CASE_ID,
CAS.START_DT AS START_DATE,
CAS.END_DT AS END_DATE,
CAS.GVR_ENTC AS COUNTY,
CAS.SRV_CMPC AS SERVICE_COMP,
CAS.IBMSNAP_LOGMARKER AS CASE_LAST_UPDATED,
CAS.IBMSNAP_OPERATION AS CASE_LAST_OPERATION,
CLC.COM_FST_NM AS FOCUS_CHLD_FIRST_NM,
CLC.COM_LST_NM AS FOCUS_CHLD_LAST_NM,
CLC.IDENTIFIER AS FOCUS_CHILD_ID,
CLC.IBMSNAP_LOGMARKER AS FOCUS_CHILD_LAST_UPDATED,
CLC.IBMSNAP_OPERATION AS FOCUS_CHILD_LAST_OPERATION,
CLP.COM_FST_NM AS PARENT_FIRST_NM,
CLP.COM_LST_NM AS PARENT_LAST_NM,
CLR.CLNTRELC AS PARENT_RELATIONSHIP,
CLP.IDENTIFIER AS PARENT_ID,
CLP.IBMSNAP_LOGMARKER AS PARENT_LAST_UPDATED,
CLP.IBMSNAP_OPERATION AS PARENT_LAST_OPERATION,
'CLIENT_T' AS PARENT_SOURCE_TABLE,
STF.FIRST_NM AS WORKER_FIRST_NM,
STF.LAST_NM AS WORKER_LAST_NM,
STF.IDENTIFIER AS WORKER_ID,
STF.IBMSNAP_LOGMARKER AS WORKER_LAST_UPDATED,
STF.IBMSNAP_OPERATION AS WORKER_LAST_OPERATION,
MAX(CAS.IBMSNAP_LOGMARKER,
  CLC.IBMSNAP_LOGMARKER,
  CLP.IBMSNAP_LOGMARKER,
  STF.IBMSNAP_LOGMARKER) LAST_CHG
FROM CWSRS1.CASE_T CAS
LEFT OUTER JOIN CWSRS1.CHLD_CLT CCL ON CCL.FKCLIENT_T = CAS.FKCHLD_CLT
LEFT OUTER JOIN CWSRS1.CLIENT_T CLC ON CLC.IDENTIFIER = CCL.FKCLIENT_T AND CLC.SOC158_IND ='N' AND CLC.SENSTV_IND = 'N'
JOIN CWSRS1.CLN_RELT CLR ON CLR.FKCLIENT_T = CCL.FKCLIENT_T AND ((CLR.CLNTRELC BETWEEN 187 and 214) OR
(CLR.CLNTRELC BETWEEN 245 and 254) OR (CLR.CLNTRELC BETWEEN 282 and 294) OR (CLR.CLNTRELC IN (272, 273, 5620, 6360, 6361)))
JOIN CWSRS1.CLIENT_T CLP ON CLP.IDENTIFIER = CLR.FKCLIENT_0 AND CLP.SOC158_IND ='N' AND CLP.SENSTV_IND = 'N'
LEFT OUTER JOIN CWSRS1.STFPERST STF ON STF.IDENTIFIER = CAS.FKSTFPERST
ORDER BY FOCUS_CHILD_ID
)
DATA INITIALLY DEFERRED
REFRESH DEFERRED
DISABLE QUERY OPTIMIZATION;

-- Execute following commands on linux hosts
SET INTEGRITY FOR CWSRS1.ES_CASE_HIST MATERIALIZED QUERY IMMEDIATE UNCHECKED;
REFRESH TABLE CWSRS1.ES_CASE_HIST;
