## 2026-10-21 — Build the TrustVault Spring Boot application

**Prompt (fair summary):** Implement the supplied TrustVault document-sharing challenge as an end-to-end Spring Boot/Java web application in this project, including Spring Web, Data JPA, authentication/three roles, per-document permissions, uploads, search, audit trail, security tests, and H2.

**Produced:** A Spring Security form-login app with OWNER, VIEWER, and ADMIN roles; a Thymeleaf interface; H2/JPA entities and repositories; private filesystem storage; document-level read/edit grants; an HMAC-linked audit log; threat/API/setup documentation; and authorization-focused MockMvc tests.

**What was wrong or needed correction:** The first implementation pass needed further security scrutiny. An admin role must not imply document access, so the service checks only ownership or a current explicit grant and a regression test covers admin denial. Replacing a permission grant by deleting and reinserting it could collide with the document/user uniqueness constraint, so grant updates now mutate the existing grant. Thymeleaf's `#authentication` object would require an extra security dialect, so the authenticated username is explicitly added to the view model. A chain without a separately stored head does not detect ordinary deletion of its newest row, so a locked chain-head row is now checked; because that head is still in the same database, rolling both back remains an accepted limitation that is documented rather than claimed as tamper-proof.

**Correction/evidence:** Those changes are present in `DocumentService`, `DocumentController`, the templates, and `DocumentAuthorizationTests`. The tests cover unshared list/detail/download/edit/delete/grant routes, anonymous denied downloads, explicit read/edit behavior, immediate revocation, upload signature validation, and detection of a modified audit row. No commit link exists: this workspace is not a Git repository, and no commit was created.

**Reusable context used:** The challenge text provided in the user's request; no separate prompt file, system instruction, or external reusable context was imported.

## 2026-10-22 — Add restricted role management

**Prompt (fair summary):** Keep public sign-up safe, let an administrator promote a Viewer to Owner, and restrict how the initial administrator account is created.

**Produced:** Added an ADMIN-only user-management page and promotion action; role changes are checked in the service and audited. Added a one-time, environment-secret bootstrap path that can create the initial ADMIN only when the account database is empty. Public registration remains fixed to VIEWER.

**Security check:** The role is not accepted from registration form input; both the `/admin/**` route rule and method-level authorization restrict promotions; only a VIEWER can be promoted; no web endpoint creates ADMIN accounts; and denied role-management calls are recorded in the audit trail. Regression tests cover authorized promotion, role audit evidence, and denials for VIEWER/OWNER callers.

**Limitations:** The local demo still seeds known development credentials by default. Production must disable demo seeding, inject all bootstrap credentials through a secret manager, use an empty database for initial provisioning, and avoid committing secrets. Automated tests could not be run in this environment because PowerShell is unavailable.

**Correction/commit evidence:** No commit link exists because this workspace is not a Git repository and no commit was created.

## 2026-10-23 — Add administrator account status controls

**Prompt (fair summary):** Update the application to meet the TrustVault requirements, including administrator management of account status.

**What was missing:** Administrators could create, change roles, and delete accounts, but could not temporarily suspend them. Existing authenticated sessions also had no per-request check to make a suspension effective immediately.

**Change and verification evidence:** Added ADMIN-only deactivate/reactivate actions, persisted account status, sign-in rejection for inactive accounts, and a security filter that invalidates an inactive user's existing session on its next request. Status changes are included in the audit chain. Added an integration test for immediate session denial, reactivation, and audit events. Test execution could not be performed in this environment because PowerShell is unavailable.

**Correction/commit evidence:** No commit link exists because this workspace is not a Git repository and no commit was created.

## 2026-10-24 — Add wrong-password messaging and OTP recovery

**Prompt (fair summary):** When a username exists but its password is wrong, display an invalid-password warning and offer a password reset using OTP verification.

**Produced:** Login failure handling distinguishes existing usernames from unknown usernames. Added email-delivered six-digit OTP reset with a ten-minute expiry, one-minute per-user resend cooldown, five failed attempts, HMAC-protected OTP storage, CSRF-protected web forms, and audit events for sent, failed, and successful resets.

**Security review:** Reset requests use a generic response for known and unknown usernames and send OTP only to the registered email address. OTP reset is disabled until SMTP and a separate strong signing key are configured. The failed-login message intentionally reveals account existence to support the requested distinction; this accepted trade-off is documented in THREATS.md.

**Validation:** Added regression tests for known-vs-unknown login failures, successful OTP reset, unknown-user enumeration resistance, and the OTP attempt limit. Test execution is still blocked because PowerShell is unavailable in the workspace.

**Correction/commit evidence:** No commit link exists because this workspace is not a Git repository and no commit was created.

## 2026-10-25 — Remove administrator Viewer promotion

**Prompt (fair summary):** Remove the administrator's ability to promote Viewer accounts to Owner.

**Change and verification evidence:** Removed the promotion route, service method, and user-management button. Admins may still create Owner accounts directly, and Owner-to-Viewer demotion remains available. Updated the API, README, and threat model; added a regression test expecting the removed route to return 404 and the Viewer role to remain unchanged. Automated tests could not be run because PowerShell is unavailable in this environment.

**Correction/commit evidence:** No commit link exists because this workspace is not a Git repository and no commit was created.
