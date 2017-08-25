-- DROP VIEW CWSRS1.VW_MQT_ALGTN_ONLY ;

CREATE VIEW CWSRS1.VW_MQT_ALGTN_ONLY (
	REFERRAL_ID,
	ALLEGATION_ID,
	ALLEGATION_DISPOSITION,
	ALLEGATION_TYPE,
	ALLEGATION_LAST_UPDATED,
	PERPETRATOR_ID,
	PERPETRATOR_SENSITIVITY_IND,
	PERPETRATOR_FIRST_NM,
	PERPETRATOR_LAST_NM,
	PERPETRATOR_LAST_UPDATED,
	VICTIM_ID,
	VICTIM_SENSITIVITY_IND,
	VICTIM_FIRST_NM,
	VICTIM_LAST_NM,
	VICTIM_LAST_UPDATED,
	LAST_CHG
) AS 
SELECT 
	RC.FKREFERL_T         AS REFERRAL_ID,
	ALG.IDENTIFIER        AS ALLEGATION_ID,
	ALG.ALG_DSPC          AS ALLEGATION_DISPOSITION,
	ALG.ALG_TPC           AS ALLEGATION_TYPE,
	ALG.LST_UPD_TS        AS ALLEGATION_LAST_UPDATED,
	CLP.IDENTIFIER        AS PERPETRATOR_ID,
	CLP.SENSTV_IND        AS PERPETRATOR_SENSITIVITY_IND,
	TRIM(CLP.COM_FST_NM)  AS PERPETRATOR_FIRST_NM,
	TRIM(CLP.COM_LST_NM)  AS PERPETRATOR_LAST_NM,
	CLP.LST_UPD_TS        AS PERPETRATOR_LAST_UPDATED,
	CLV.IDENTIFIER        AS VICTIM_ID,
	CLV.SENSTV_IND        AS VICTIM_SENSITIVITY_IND,
	TRIM(CLV.COM_FST_NM)  AS VICTIM_FIRST_NM,
	TRIM(CLV.COM_LST_NM)  AS VICTIM_LAST_NM,
	CLV.LST_UPD_TS        AS VICTIM_LAST_UPDATED,
	CURRENT TIMESTAMP     AS LAST_CHG
FROM (SELECT DISTINCT rc1.FKREFERL_T FROM CWSRS1.GT_REFR_CLT rc1) RC
JOIN CWSRS1.VICTIM_ALLGTN    ALG  ON ALG.FKREFERL_T = RC.FKREFERL_T
JOIN CWSRS1.CMP_CLIENT       CLV  ON CLV.IDENTIFIER = ALG.FKCLIENT_T
LEFT JOIN CWSRS1.CMP_CLIENT  CLP  ON CLP.IDENTIFIER = ALG.FKCLIENT_0
;

-- GRANT SELECT ON CWSRS1.VW_MQT_ALGTN_ONLY TO CWDSIN1;
-- GRANT SELECT ON CWSRS1.VW_MQT_ALGTN_ONLY TO CWDSDSM;
