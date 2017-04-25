CREATE TYPE scope AS ENUM ('private', 'public');
--;;
CREATE TYPE itemtype AS ENUM ('text','texta','label','license','attachment','referee','checkbox','dropdown','date');
--;;
CREATE TYPE license_status AS ENUM ('approved','rejected');
--;;
CREATE TYPE license_state AS ENUM ('created','approved','rejected');
--;;
CREATE TYPE reviewers_state AS ENUM ('created','commented');
--;;
CREATE TYPE application_event_type AS ENUM (
  'apply',   -- draft --> applied
  'approve', -- applied --> applied or approved
  'autoapprove', -- like approve but when there are no approvers for the round
  'reject',  -- applied --> rejected
  'return'   -- applied --> returned
);
--;;
CREATE TYPE application_state AS ENUM ('applied','approved','rejected','returned','closed','draft','onhold');
--;;
CREATE TYPE item_state AS ENUM ('disabled','enabled','copied');
--;;
CREATE TYPE prefix_state AS ENUM ('applied','approved','denied');
--;;
CREATE TYPE license_type AS ENUM ('text','attachment','link');
--;;
CREATE TABLE resource_prefix (
  id serial NOT NULL PRIMARY KEY,
  modifierUserId varchar(255) NOT NULL,
  prefix varchar(255) DEFAULT NULL,
  start timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  endt timestamp NULL DEFAULT NULL
);
--;;
CREATE TABLE resource (
  id serial NOT NULL PRIMARY KEY,
  modifierUserId varchar(255) NOT NULL,
  rsPrId integer DEFAULT NULL,
  prefix varchar(255) NOT NULL,
  resId varchar(255) NOT NULL,
  start timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  endt timestamp NULL DEFAULT NULL,
  CONSTRAINT resource_ibfk_1 FOREIGN KEY (rsPrId) REFERENCES resource_prefix (id)
);
--;;
CREATE TABLE workflow (
  id serial NOT NULL PRIMARY KEY,
  ownerUserId varchar(255) NOT NULL,
  modifierUserId varchar(255) NOT NULL,
  title varchar(256) NOT NULL,
  fnlround integer NOT NULL,
  visibility scope NOT NULL DEFAULT 'private',
  start timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  endt timestamp NULL DEFAULT NULL
);
--;;
CREATE TABLE application_form_meta (
  id serial NOT NULL PRIMARY KEY,
  ownerUserId varchar(255) NOT NULL,
  modifierUserId varchar(255) NOT NULL,
  title varchar(256) DEFAULT NULL,
  visibility scope NOT NULL,
  start timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  endt timestamp NULL DEFAULT NULL
);
--;;
CREATE TABLE catalogue_item (
  id serial NOT NULL PRIMARY KEY,
  title varchar(256) NOT NULL,
  resId integer DEFAULT NULL,
  wfId integer DEFAULT NULL,
  formId integer DEFAULT '1',
  start timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  endt timestamp NULL DEFAULT NULL,
  CONSTRAINT catalogue_item_ibfk_1 FOREIGN KEY (resId) REFERENCES resource (id),
  CONSTRAINT catalogue_item_ibfk_2 FOREIGN KEY (wfId) REFERENCES workflow (id),
  CONSTRAINT catalogue_item_ibfk_3 FOREIGN KEY (formId) REFERENCES application_form_meta (id)
);
--;;
CREATE TABLE catalogue_item_application (
  id serial NOT NULL PRIMARY KEY,
  catId integer DEFAULT NULL,
  applicantUserId varchar(255) NOT NULL,
  fnlround integer NOT NULL,
  start timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  endt timestamp NULL DEFAULT NULL,
  modifierUserId varchar(255) DEFAULT NULL,
  CONSTRAINT catalogue_item_application_ibfk_1 FOREIGN KEY (catId) REFERENCES catalogue_item (id)
);
--;;
CREATE TABLE application_form (
  id serial NOT NULL PRIMARY KEY,
  ownerUserId varchar(255) NOT NULL,
  modifierUserId varchar(255) NOT NULL,
  title varchar(256) NOT NULL,
  visibility scope NOT NULL,
  start timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  endt timestamp NULL DEFAULT NULL
);
--;;
CREATE TABLE application_form_item (
  id serial NOT NULL PRIMARY KEY,
  ownerUserId varchar(255) NOT NULL,
  modifierUserId varchar(255) NOT NULL,
  title varchar(256) NOT NULL,
  toolTip varchar(256) DEFAULT NULL,
  inputPrompt varchar(256) DEFAULT NULL,
  type itemtype DEFAULT NULL,
  value bigint NOT NULL,
  visibility scope NOT NULL,
  start timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  endt timestamp NULL DEFAULT NULL
);
--;;
CREATE TABLE application_form_item_map (
  id serial NOT NULL PRIMARY KEY,
  formId integer DEFAULT NULL,
  formItemId integer DEFAULT NULL,
  formItemOptional boolean NOT NULL DEFAULT FALSE,
  modifierUserId varchar(255) NOT NULL,
  itemOrder integer DEFAULT NULL,
  start timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  endt timestamp NULL DEFAULT NULL,
  CONSTRAINT application_form_item_map_ibfk_1 FOREIGN KEY (formId) REFERENCES application_form (id),
  CONSTRAINT application_form_item_map_ibfk_2 FOREIGN KEY (formItemId) REFERENCES application_form_item (id)
);
--;;
CREATE TABLE license (
  id serial NOT NULL PRIMARY KEY,
  ownerUserId varchar(255) NOT NULL,
  modifierUserId varchar(255) NOT NULL,
  title varchar(256) NOT NULL,
  type license_type NOT NULL,
  textContent varchar(16384) DEFAULT NULL,
  attId integer DEFAULT NULL,
  visibility scope NOT NULL DEFAULT 'private',
  start timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  endt timestamp NULL DEFAULT NULL
);
--;;
CREATE TABLE application_form_meta_map (
  id serial NOT NULL PRIMARY KEY,
  metaFormId integer DEFAULT NULL,
  modifierUserId varchar(255) NOT NULL,
  langCode varchar(64) DEFAULT NULL,
  formId integer DEFAULT NULL,
  start timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  endt timestamp NULL DEFAULT NULL,
  CONSTRAINT application_form_meta_map_ibfk_1 FOREIGN KEY (metaFormId) REFERENCES application_form_meta (id),
  CONSTRAINT application_form_meta_map_ibfk_2 FOREIGN KEY (formId) REFERENCES application_form (id)
);
--;;
CREATE TABLE application_license_approval_values (
  id serial NOT NULL PRIMARY KEY,
  catAppId integer DEFAULT NULL,
  formMapId integer DEFAULT NULL,
  licId integer NOT NULL,
  modifierUserId varchar(255) DEFAULT NULL,
  state license_status NOT NULL,
  start timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  endt timestamp NULL DEFAULT NULL,
  CONSTRAINT application_license_approval_values_ibfk_1 FOREIGN KEY (catAppId) REFERENCES catalogue_item_application (id),
  CONSTRAINT application_license_approval_values_ibfk_2 FOREIGN KEY (formMapId) REFERENCES application_form_item_map (id),
  CONSTRAINT application_license_approval_values_ibfk_3 FOREIGN KEY (licId) REFERENCES license (id)
);
--;;
CREATE TABLE application_text_values (
  id serial NOT NULL PRIMARY KEY,
  catAppId integer DEFAULT NULL,
  formMapId integer DEFAULT NULL,
  modifierUserId varchar(255) NOT NULL,
  value varchar(4096) NOT NULL,
  start timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  endt timestamp NULL DEFAULT NULL,
  CONSTRAINT application_text_values_ibfk_1 FOREIGN KEY (catAppId) REFERENCES catalogue_item_application (id),
  CONSTRAINT application_text_values_ibfk_2 FOREIGN KEY (formMapId) REFERENCES application_form_item_map (id),
  UNIQUE (catAppId, formMapId)
);
--;;
CREATE TABLE workflow_approvers (
  id serial NOT NULL PRIMARY KEY,
  wfId integer DEFAULT NULL,
  apprUserId varchar(255) NOT NULL,
  round integer NOT NULL,
  start timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  endt timestamp NULL DEFAULT NULL,
  CONSTRAINT workflow_approvers_ibfk_1 FOREIGN KEY (wfId) REFERENCES workflow (id)
);
--;;
CREATE TABLE catalogue_item_application_catid_overflow (
  id serial NOT NULL PRIMARY KEY,
  catAppId integer DEFAULT NULL,
  catId integer DEFAULT NULL,
  modifierUserId varchar(255) NOT NULL,
  start timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  endt timestamp NULL DEFAULT NULL,
  CONSTRAINT catalogue_item_application_catid_overflow_ibfk_1 FOREIGN KEY (catAppId) REFERENCES catalogue_item_application (id),
  CONSTRAINT catalogue_item_application_catid_overflow_ibfk_2 FOREIGN KEY (catId) REFERENCES catalogue_item (id)
);
--;;
CREATE TABLE catalogue_item_application_free_comment_values (
  id serial NOT NULL PRIMARY KEY,
  userId varchar(255) NOT NULL,
  catAppId integer DEFAULT NULL,
  comment varchar(4096) DEFAULT NULL,
  public boolean NOT NULL DEFAULT FALSE,
  start timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  endt timestamp NULL DEFAULT NULL,
  CONSTRAINT catalogue_item_application_free_comment_values_ibfk_1 FOREIGN KEY (catAppId) REFERENCES catalogue_item_application (id)
);
--;;
CREATE TABLE catalogue_item_application_licenses (
  id serial NOT NULL PRIMARY KEY,
  catAppId integer DEFAULT NULL,
  licId integer DEFAULT NULL,
  actorUserId varchar(255) NOT NULL,
  round integer NOT NULL,
  stalling boolean NOT NULL DEFAULT FALSE,
  state license_state NOT NULL DEFAULT 'created',
  start timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  endt timestamp NULL DEFAULT NULL,
  CONSTRAINT catalogue_item_application_licenses_ibfk_1 FOREIGN KEY (catAppId) REFERENCES catalogue_item_application (id),
  CONSTRAINT catalogue_item_application_licenses_ibfk_2 FOREIGN KEY (licId) REFERENCES license (id)
);
--;;
CREATE TABLE catalogue_item_application_members (
  id serial NOT NULL PRIMARY KEY,
  catAppId integer DEFAULT NULL,
  memberUserId varchar(255) NOT NULL,
  modifierUserId varchar(255) DEFAULT '-1',
  start timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  endt timestamp NULL DEFAULT NULL,
  CONSTRAINT catalogue_item_application_members_ibfk_1 FOREIGN KEY (catAppId) REFERENCES catalogue_item_application (id)
);
--;;
CREATE TABLE catalogue_item_application_metadata (
  id serial NOT NULL PRIMARY KEY,
  userId varchar(255) NOT NULL,
  catAppId integer DEFAULT NULL,
  key varchar(32) NOT NULL,
  value varchar(256) NOT NULL,
  start timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  endt timestamp NULL DEFAULT NULL,
  CONSTRAINT catalogue_item_application_metadata_ibfk_1 FOREIGN KEY (catAppId) REFERENCES catalogue_item_application (id)
);
--;;
CREATE TABLE catalogue_item_application_predecessor (
  id serial NOT NULL PRIMARY KEY,
  pre_catAppId integer DEFAULT NULL,
  suc_catAppId integer DEFAULT NULL,
  modifierUserId varchar(255) NOT NULL,
  start timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  endt timestamp NULL DEFAULT NULL,
  CONSTRAINT catalogue_item_application_predecessor_ibfk_1 FOREIGN KEY (pre_catAppId) REFERENCES catalogue_item_application (id),
  CONSTRAINT catalogue_item_application_predecessor_ibfk_2 FOREIGN KEY (suc_catAppId) REFERENCES catalogue_item_application (id)
);
--;;
CREATE TABLE catalogue_item_localization (
  id serial NOT NULL PRIMARY KEY,
  catId integer DEFAULT NULL,
  langCode varchar(64) NOT NULL,
  title varchar(256) NOT NULL,
  start timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  endt timestamp NULL DEFAULT NULL,
  CONSTRAINT catalogue_item_localization_ibfk_1 FOREIGN KEY (catId) REFERENCES catalogue_item (id)
);
--;;
CREATE TABLE catalogue_item_state (
  id serial NOT NULL PRIMARY KEY,
  catId integer DEFAULT NULL,
  modifierUserId varchar(255) NOT NULL,
  state item_state DEFAULT NULL,
  start timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  endt timestamp NULL DEFAULT NULL,
  CONSTRAINT catalogue_item_state_ibfk_1 FOREIGN KEY (catId) REFERENCES catalogue_item (id)
);
--;;
CREATE TABLE entitlement (
  id serial NOT NULL PRIMARY KEY,
  resId integer DEFAULT NULL,
  catAppId integer DEFAULT NULL,
  userId varchar(255) NOT NULL,
  start timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  endt timestamp NULL DEFAULT NULL,
  CONSTRAINT entitlement_ibfk_1 FOREIGN KEY (resId) REFERENCES resource (id),
  CONSTRAINT entitlement_ibfk_2 FOREIGN KEY (catAppId) REFERENCES catalogue_item_application (id)
);
--;;
CREATE TABLE license_localization (
  id serial NOT NULL PRIMARY KEY,
  licId integer DEFAULT NULL,
  langCode varchar(64) NOT NULL,
  title varchar(256) NOT NULL,
  textContent varchar(16384) DEFAULT NULL,
  attId integer DEFAULT NULL,
  start timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  endt timestamp NULL DEFAULT NULL,
  CONSTRAINT license_localization_ibfk_1 FOREIGN KEY (licId) REFERENCES license (id)
);
--;;
CREATE TABLE resource_close_period (
  id serial NOT NULL PRIMARY KEY,
  resId integer DEFAULT NULL,
  closePeriod integer DEFAULT NULL,
  modifierUserId varchar(255) NOT NULL,
  start timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  endt timestamp NULL DEFAULT NULL,
  CONSTRAINT resource_close_period_ibfk_1 FOREIGN KEY (resId) REFERENCES resource (id)
);
--;;
CREATE TABLE resource_licenses (
  id serial NOT NULL PRIMARY KEY,
  resId integer DEFAULT NULL,
  licId integer DEFAULT NULL,
  stalling boolean NOT NULL DEFAULT FALSE,
  start timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  endt timestamp NULL DEFAULT NULL,
  CONSTRAINT resource_licenses_ibfk_1 FOREIGN KEY (resId) REFERENCES resource (id),
  CONSTRAINT resource_licenses_ibfk_2 FOREIGN KEY (licId) REFERENCES license (id)
);
--;;
CREATE TABLE resource_prefix_allow_members (
  id serial NOT NULL PRIMARY KEY,
  rsPrId integer DEFAULT NULL,
  enabled bit(1) DEFAULT NULL,
  modifierUserId varchar(255) NOT NULL,
  start timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  endt timestamp NULL DEFAULT NULL,
  CONSTRAINT resource_prefix_allow_members_ibfk_1 FOREIGN KEY (rsPrId) REFERENCES resource_prefix (id)
);
--;;
CREATE TABLE resource_prefix_allow_updates (
  id serial NOT NULL PRIMARY KEY,
  rsPrId integer DEFAULT NULL,
  enabled bit(1) DEFAULT NULL,
  modifierUserId varchar(255) NOT NULL,
  start timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  endt timestamp NULL DEFAULT NULL,
  CONSTRAINT resource_prefix_allow_updates_ibfk_1 FOREIGN KEY (rsPrId) REFERENCES resource_prefix (id)
);
--;;
CREATE TABLE resource_prefix_application (
  id serial NOT NULL PRIMARY KEY,
  rsPrId integer DEFAULT NULL,
  application varchar(2048) DEFAULT NULL,
  modifierUserId varchar(255) NOT NULL,
  start timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  endt timestamp NULL DEFAULT NULL,
  CONSTRAINT resource_prefix_application_ibfk_1 FOREIGN KEY (rsPrId) REFERENCES resource_prefix (id)
);
--;;
CREATE TABLE resource_prefix_certificates (
  id serial NOT NULL PRIMARY KEY,
  rsPrId integer DEFAULT NULL,
  subjectDn varchar(256) DEFAULT NULL,
  base64content varchar(16384) DEFAULT NULL,
  modifierUserId varchar(255) NOT NULL,
  start timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  endt timestamp NULL DEFAULT NULL,
  CONSTRAINT resource_prefix_certificates_ibfk_1 FOREIGN KEY (rsPrId) REFERENCES resource_prefix (id)
);
--;;
CREATE TABLE resource_prefix_default_form (
  id serial NOT NULL PRIMARY KEY,
  rsPrId integer DEFAULT NULL,
  metaFormId integer DEFAULT NULL,
  modifierUserId varchar(255) NOT NULL,
  start timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  endt timestamp NULL DEFAULT NULL,
  CONSTRAINT resource_prefix_default_form_ibfk_1 FOREIGN KEY (rsPrId) REFERENCES resource_prefix (id),
  CONSTRAINT resource_prefix_default_form_ibfk_2 FOREIGN KEY (metaFormId) REFERENCES application_form_meta (id)
);
--;;
CREATE TABLE resource_prefix_owners (
  id serial NOT NULL PRIMARY KEY,
  rsPrId integer DEFAULT NULL,
  ownerUserId varchar(255) NOT NULL,
  modifierUserId varchar(255) NOT NULL,
  start timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  endt timestamp NULL DEFAULT NULL,
  CONSTRAINT resource_prefix_owners_ibfk_1 FOREIGN KEY (rsPrId) REFERENCES resource_prefix (id)
);
--;;
CREATE TABLE resource_prefix_reporters (
  id serial NOT NULL PRIMARY KEY,
  rsPrId integer DEFAULT NULL,
  reporterUserId varchar(255) NOT NULL,
  modifierUserId varchar(255) NOT NULL,
  start timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  endt timestamp NULL DEFAULT NULL,
  CONSTRAINT resource_prefix_reporters_ibfk_1 FOREIGN KEY (rsPrId) REFERENCES resource_prefix (id)
);
--;;
CREATE TABLE resource_prefix_state (
  id serial NOT NULL PRIMARY KEY,
  rsPrId integer DEFAULT NULL,
  modifierUserId varchar(255) NOT NULL,
  state prefix_state NOT NULL,
  start timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  endt timestamp NULL DEFAULT NULL,
  CONSTRAINT resource_prefix_state_ibfk_1 FOREIGN KEY (rsPrId) REFERENCES resource_prefix (id)
);
--;;
CREATE TABLE resource_refresh_period (
  id serial NOT NULL PRIMARY KEY,
  resId integer DEFAULT NULL,
  refreshPeriod integer DEFAULT NULL,
  modifierUserId varchar(255) NOT NULL,
  start timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  endt timestamp NULL DEFAULT NULL,
  CONSTRAINT resource_refresh_period_ibfk_1 FOREIGN KEY (resId) REFERENCES resource (id)
);
--;;
CREATE TABLE resource_state (
  id serial NOT NULL PRIMARY KEY,
  resId integer DEFAULT NULL,
  ownerUserId varchar(255) NOT NULL,
  modifierUserId varchar(255) NOT NULL,
  start timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  endt timestamp NULL DEFAULT NULL,
  CONSTRAINT resource_state_ibfk_1 FOREIGN KEY (resId) REFERENCES resource (id)
);
--;;
CREATE TABLE user_selection_names (
  id serial NOT NULL PRIMARY KEY,
  actionId bigint NOT NULL,
  groupId integer NOT NULL,
  listName varchar(32) DEFAULT NULL,
  start timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  endt timestamp NULL DEFAULT NULL
);
--;;
CREATE TABLE user_selections (
  id serial NOT NULL PRIMARY KEY,
  actionId bigint NOT NULL,
  groupId integer NOT NULL,
  userId varchar(255) NOT NULL,
  start timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  endt timestamp NULL DEFAULT NULL
);
--;;
CREATE TABLE workflow_approver_options (
  id serial NOT NULL PRIMARY KEY,
  wfApprId integer DEFAULT NULL,
  keyValue varchar(256) NOT NULL,
  optionValue varchar(256) NOT NULL,
  start timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  endt timestamp NULL DEFAULT NULL,
  CONSTRAINT workflow_approver_options_ibfk_1 FOREIGN KEY (wfApprId) REFERENCES workflow_approvers (id)
);
--;;
CREATE TABLE workflow_licenses (
  id serial NOT NULL PRIMARY KEY,
  wfId integer DEFAULT NULL,
  licId integer DEFAULT NULL,
  round integer NOT NULL,
  stalling boolean NOT NULL DEFAULT FALSE,
  start timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  endt timestamp NULL DEFAULT NULL,
  CONSTRAINT workflow_licenses_ibfk_1 FOREIGN KEY (wfId) REFERENCES workflow (id),
  CONSTRAINT workflow_licenses_ibfk_2 FOREIGN KEY (licId) REFERENCES license (id)
);
--;;
CREATE TABLE workflow_reviewers (
  id serial NOT NULL PRIMARY KEY,
  wfId integer DEFAULT NULL,
  revUserId varchar(255) NOT NULL,
  round integer NOT NULL,
  start timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  endt timestamp NULL DEFAULT NULL,
  CONSTRAINT workflow_reviewers_ibfk_1 FOREIGN KEY (wfId) REFERENCES workflow (id)
);
--;;
CREATE TABLE workflow_round_min (
  id serial NOT NULL PRIMARY KEY,
  wfId integer DEFAULT NULL,
  min integer NOT NULL,
  round integer NOT NULL,
  start timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  endt timestamp NULL DEFAULT NULL,
  CONSTRAINT workflow_round_min_ibfk_1 FOREIGN KEY (wfId) REFERENCES workflow (id)
);
--;;
-- TODO add foreign key constraints from other tables to user table
CREATE TABLE users (
  userId varchar(255) NOT NULL PRIMARY KEY,
  userAttrs jsonb
);
--;;
CREATE TABLE roles (
  -- TODO should this have an id for consistency with the other tables?
  userId varchar(255),
  role varchar(255),
  PRIMARY KEY (userId, role),
  FOREIGN KEY (userId) REFERENCES users
)
--;;
CREATE TABLE active_role (
  userId varchar(255) PRIMARY KEY,
  role varchar(255) NOT NULL,
  FOREIGN KEY (userId, role) REFERENCES roles
)
--;;
CREATE TABLE application_event (
  id serial NOT NULL PRIMARY KEY, -- for ordering events
  appId integer REFERENCES catalogue_item_application (id),
  userId varchar(255) REFERENCES users (userId),
  round integer NOT NULL,
  event application_event_type NOT NULL,
  comment varchar(4096) DEFAULT NULL,
  time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
)
--;;
