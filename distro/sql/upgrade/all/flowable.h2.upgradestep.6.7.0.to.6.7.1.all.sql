update ACT_GE_PROPERTY set VALUE_ = '6.7.1.0' where NAME_ = 'common.schema.version';

update ACT_GE_PROPERTY set VALUE_ = '6.7.1.0' where NAME_ = 'entitylink.schema.version';

update ACT_GE_PROPERTY set VALUE_ = '6.7.1.0' where NAME_ = 'identitylink.schema.version';

update ACT_GE_PROPERTY set VALUE_ = '6.7.1.0' where NAME_ = 'job.schema.version';

update ACT_GE_PROPERTY set VALUE_ = '6.7.1.0' where NAME_ = 'batch.schema.version';

update ACT_GE_PROPERTY set VALUE_ = '6.7.1.0' where NAME_ = 'task.schema.version';

update ACT_GE_PROPERTY set VALUE_ = '6.7.1.0' where NAME_ = 'variable.schema.version';

update ACT_GE_PROPERTY set VALUE_ = '6.7.1.0' where NAME_ = 'eventsubscription.schema.version';
alter table ACT_RU_EXECUTION add column BUSINESS_STATUS_ varchar(255);

alter table ACT_HI_PROCINST add column BUSINESS_STATUS_ varchar(255);

update ACT_GE_PROPERTY set VALUE_ = '6.7.1.0' where NAME_ = 'schema.version';
update ACT_ID_PROPERTY set VALUE_ = '6.7.1.0' where NAME_ = 'schema.version';

UPDATE ACT_APP_DATABASECHANGELOGLOCK SET LOCKED = TRUE, LOCKEDBY = '192.168.68.111 (192.168.68.111)', LOCKGRANTED = '2021-11-11 12:26:47.96' WHERE ID = 1 AND LOCKED = FALSE;

UPDATE ACT_APP_DATABASECHANGELOGLOCK SET LOCKED = FALSE, LOCKEDBY = NULL, LOCKGRANTED = NULL WHERE ID = 1;


UPDATE ACT_CMMN_DATABASECHANGELOGLOCK SET LOCKED = TRUE, LOCKEDBY = '192.168.68.111 (192.168.68.111)', LOCKGRANTED = '2021-11-11 12:26:48.413' WHERE ID = 1 AND LOCKED = FALSE;

ALTER TABLE ACT_CMMN_RU_CASE_INST ADD BUSINESS_STATUS_ VARCHAR(255);

ALTER TABLE ACT_CMMN_HI_CASE_INST ADD BUSINESS_STATUS_ VARCHAR(255);

INSERT INTO ACT_CMMN_DATABASECHANGELOG (ID, AUTHOR, FILENAME, DATEEXECUTED, ORDEREXECUTED, MD5SUM, DESCRIPTION, COMMENTS, EXECTYPE, CONTEXTS, LABELS, LIQUIBASE, DEPLOYMENT_ID) VALUES ('16', 'flowable', 'org/flowable/cmmn/db/liquibase/flowable-cmmn-db-changelog.xml', NOW(), 14, '8:a697a222ddd99dd15b36516a252f1c63', 'addColumn tableName=ACT_CMMN_RU_CASE_INST; addColumn tableName=ACT_CMMN_HI_CASE_INST', '', 'EXECUTED', NULL, NULL, '4.3.5', '6630008529');

UPDATE ACT_CMMN_DATABASECHANGELOGLOCK SET LOCKED = FALSE, LOCKEDBY = NULL, LOCKGRANTED = NULL WHERE ID = 1;


UPDATE FLW_EV_DATABASECHANGELOGLOCK SET LOCKED = TRUE, LOCKEDBY = '192.168.68.111 (192.168.68.111)', LOCKGRANTED = '2021-11-11 12:26:48.646' WHERE ID = 1 AND LOCKED = FALSE;

UPDATE FLW_EV_DATABASECHANGELOGLOCK SET LOCKED = FALSE, LOCKEDBY = NULL, LOCKGRANTED = NULL WHERE ID = 1;


UPDATE ACT_DMN_DATABASECHANGELOGLOCK SET LOCKED = TRUE, LOCKEDBY = '192.168.68.111 (192.168.68.111)', LOCKGRANTED = '2021-11-11 12:26:48.746' WHERE ID = 1 AND LOCKED = FALSE;

UPDATE ACT_DMN_DATABASECHANGELOGLOCK SET LOCKED = FALSE, LOCKEDBY = NULL, LOCKGRANTED = NULL WHERE ID = 1;


UPDATE ACT_FO_DATABASECHANGELOGLOCK SET LOCKED = TRUE, LOCKEDBY = '192.168.68.111 (192.168.68.111)', LOCKGRANTED = '2021-11-11 12:26:48.862' WHERE ID = 1 AND LOCKED = FALSE;

UPDATE ACT_FO_DATABASECHANGELOGLOCK SET LOCKED = FALSE, LOCKEDBY = NULL, LOCKGRANTED = NULL WHERE ID = 1;


UPDATE ACT_CO_DATABASECHANGELOGLOCK SET LOCKED = TRUE, LOCKEDBY = '192.168.68.111 (192.168.68.111)', LOCKGRANTED = '2021-11-11 12:26:48.986' WHERE ID = 1 AND LOCKED = FALSE;

UPDATE ACT_CO_DATABASECHANGELOGLOCK SET LOCKED = FALSE, LOCKEDBY = NULL, LOCKGRANTED = NULL WHERE ID = 1;

