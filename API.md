# TrustVault web endpoint access

All routes except login, registration and its legal pages, CSS, password recovery, and the generic error route are authenticated by Spring Security. POST requests require the session's CSRF token. Document-level access is checked by the server for every content or modification request; knowing an identifier does not confer access. The document search intentionally shows document names, categories, owners, and upload dates to all authenticated users, but not descriptions or file contents. Existing documents that the user cannot open return a 403 access-denied page; nonexistent IDs return 404.

| Method and path | Who may call it | Server-side rule / effect |
|---|---|---|
| `GET /login` | Anyone | Render login form |
| `POST /login` | Anyone | Spring Security authentication |
| `GET /register` | Anyone | Render the self-registration form |
| `POST /register/verification/request` | Anyone | Validate account fields and required terms acceptance; send a six-digit email OTP and store a temporary pending registration |
| `POST /register/verification/resend` | Anyone | Resend a pending registration OTP, subject to the one-minute per-email cooldown |
| `POST /register/verification/confirm` | Anyone | Verify the OTP (10-minute expiry, five attempts) and create the account; role is fixed to `VIEWER`; CSRF required |
| `GET /terms` | Anyone | Read the Terms and Conditions shown during registration |
| `GET /privacy` | Anyone | Read the Privacy Notice shown during registration |
| `GET /password-reset` | Anyone | Render email-code password recovery form |
| `POST /password-reset/request` | Anyone | Send a 10-minute OTP to the account email, if it exists; response does not disclose account existence |
| `POST /password-reset/confirm` | Anyone | Verify OTP and replace password; up to five code attempts; CSRF required |
| `GET /error` | Anyone | Generic framework error response |
| `POST /logout` | Authenticated user | End session |
| `GET /` or `GET /documents` | Any authenticated user | Show the normal documents page; search results do not appear on initial load or refresh |
| `POST /documents/search` | Any authenticated user | Search document names, categories, owners, and upload dates across all documents; filters: `name`, `owner`, `category`, `from`, `to`. Results are shown after submission and cleared on refresh. This intentionally discloses metadata, not descriptions or content |
| `POST /documents` | OWNER account | Validate upload and create a document owned by the caller |
| `GET /documents/{id}` | Any authenticated user | Owner or READ/EDIT grantee sees document details; others see an audited 403 access-denied message |
| `GET /documents/{id}/open` | Owner or READ/EDIT grantee | Open private file inline; unauthorized users receive an audited access-denied prompt |
| `GET /documents/{id}/download` | Owner or READ/EDIT grantee | Download private file; audit download |
| `POST /documents/{id}/edit` | Owner or EDIT grantee | Change name, category, and description |
| `POST /documents/{id}/delete` | Document owner only | Delete file, grants, and document record |
| `POST /documents/{id}/grants` | Document owner only | Grant a named account `READ` or `EDIT` |
| `POST /documents/{id}/grants/revoke` | Document owner only | Revoke a named account's current grant |
| `GET /owner/documents` | OWNER only | List documents uploaded and currently owned by the signed-in owner |
| `GET /viewer/documents` | VIEWER only | List only documents currently granted to the signed-in viewer |
| `GET /owner/activity` | OWNER only | View audit events for documents currently owned by the signed-in owner; ADMIN retains access to the full audit trail |
| `GET /admin/activity` | ADMIN only | View event records and HMAC-chain verification status; conveys no document access |
| `GET /admin/users` | ADMIN only | List accounts and current roles |
| `GET /admin/users/new` | ADMIN only | Open the separate user-creation form |
| `POST /admin/users` | ADMIN only | Create a VIEWER or OWNER account with an admin-set initial password; the first document-page visit prompts the user to change it; ADMIN accounts cannot be created here |
| `GET /admin/users/{username}` | ADMIN only | View account details and available account-management actions; password hashes are not displayed |
| `POST /admin/users/{username}/demote` | ADMIN only | Demote an OWNER to VIEWER; records the role transition in the audit trail |
| `POST /admin/users/{username}/deactivate` | ADMIN only | Deactivate an account; blocks new logins and existing sessions on their next request, while retaining grants for possible reactivation; audited |
| `POST /admin/users/{username}/activate` | ADMIN only | Reactivate a deactivated account; audited |
| `POST /admin/users/{username}/delete` | ADMIN only | Delete an account, remove grants to it, and transfer any owned documents to a selected OWNER |
| `GET /account/password` | Any authenticated user | Legacy route; redirects to account settings |
| `POST /account/password` | Any authenticated user | Legacy password-change route; verifies the current password and redirects to account settings |
| `GET /account/settings` | Any authenticated user | View personal account settings; missing full name and unverified email actions are shown only when applicable |
| `POST /account/settings/username` | Any authenticated user | Change username; document ownership and grants are retained and the user must sign in again |
| `POST /account/settings/full-name` | Any authenticated user without a full name | Add a missing full name; an existing full name cannot be overwritten here |
| `POST /account/settings/email-verification` | Authenticated user with an unverified email | Send a six-digit email verification code; codes expire in ten minutes and are rate-limited. If the account has no email, provide the `email` form field to add it and send the code |
| `POST /account/settings/email-verification/confirm` | Authenticated user with an unverified email | Verify the email using the sent code |
| `POST /account/settings/password` | Any authenticated user | Change the password from the settings page after verifying the current password |

The first production administrator is provisioned from operator-supplied environment secrets only when the account database is empty. There is no public ADMIN signup or web endpoint for creating/promoting administrators. Administrators cannot delete their own account or the last administrator account. The demo intentionally exposes a server-rendered web interface rather than a separate JSON API. There are no public file URLs, bearer download tokens, or administrative document-access bypass.
