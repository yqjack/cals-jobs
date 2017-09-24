DROP TRIGGER CWSINT.trg_ocl_nm_t_upd;

CREATE TRIGGER CWSINT.trg_ocl_nm_t_upd
AFTER UPDATE ON CWSINT.OCL_NM_T
REFERENCING OLD AS OROW
            NEW AS NROW
FOR EACH ROW MODE DB2SQL
BEGIN ATOMIC
	UPDATE CWSRS1.OCL_NM_T SET 
		THIRD_ID = nrow.THIRD_ID,
		FIRST_NM = nrow.FIRST_NM,
		LAST_NM = nrow.LAST_NM,
		MIDDLE_NM = nrow.MIDDLE_NM,
		NMPRFX_DSC = nrow.NMPRFX_DSC,
		NAME_TPC = nrow.NAME_TPC,
		SUFX_TLDSC = nrow.SUFX_TLDSC,
		LST_UPD_ID = nrow.LST_UPD_ID,
		LST_UPD_TS = nrow.LST_UPD_TS,
		FKCLIENT_T = nrow.FKCLIENT_T,
		IBMSNAP_OPERATION = 'U',
		IBMSNAP_LOGMARKER = current timestamp
	WHERE FKCLIENT_T = nrow.FKCLIENT_T AND THIRD_ID = nrow.THIRD_ID;
END