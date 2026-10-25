# TrustVault

TrustVault is a server-rendered Spring Boot document-sharing application. It uses Spring Security form login, Spring Data JPA, an H2 file database for metadata, and private local filesystem storage for document bytes.

## Run locally

Requirements: Java 25 and the included Maven wrapper.

```powershell
.\mvnw.cmd spring-boot:run
```

Open <http://localhost:8080> to view the welcome page, then select **Get started** to sign in. After successful sign-in, TrustVault opens the document workspace at `/documents`. On a new local database, the application seeds these **development-only** accounts:

| Role | Username | Password |
|---|---|---|
| Administrator | `admin` | `AdminDemo!234` |
| Document owner | `owner` | `OwnerDemo!234` |
| Viewer | `viewer` | `ViewerDemo!234` |

The owner can upload documents and explicitly grant the viewer read or edit access. The administrator sees the audit activity but does not receive document access by virtue of the admin role. There is no automatic sharing or public document URL.

Visitors can create an account from the sign-in page by providing their full name, a unique email address, a username, and a password, accepting the Terms and Conditions, and verifying the email with a six-digit OTP. The OTP expires after 10 minutes, allows five incorrect attempts, and can be resent no more than once per minute. Only after successful verification is the account created. The accepted terms version and timestamp are retained with the account. Leading/trailing whitespace is removed from names, emails, and usernames; usernames and email addresses are case-insensitive and stored in lowercase. Usernames may contain letters, numbers, dots, underscores, and hyphens and must start with a letter or number. Passwords must be at least 12 characters and no more than 72 UTF-8 bytes. Self-registered accounts are assigned the **VIEWER** role and receive no document access unless an owner grants it. SMTP must be configured for email verification to work.

The theme selector is available on public and signed-in pages. Light, Ocean, Forest, and Dark themes apply across the site; the choice is saved in that browser.

When a valid username is paired with a wrong password, sign-in reports an invalid password and offers password reset. Reset sends a one-time six-digit code to the registered email address; codes expire after 10 minutes and allow five attempts. Password reset is enabled by default and requires working SMTP configuration.

The H2 database is created under `./data`; uploaded files are kept under `./private-files`. Neither the database console nor the file directory is exposed over HTTP. To run tests:

```powershell
.\mvnw.cmd test
```

## Run with Docker

Requirements: Docker Desktop. From the project root, build the image and create/start the container:

```powershell
docker build -t trustvault:local .
docker run -d --name trustvault `
  -p 8081:8081 `
  -e "DB_URL=jdbc:h2:file:/app/data/trustvault;DB_CLOSE_ON_EXIT=FALSE" `
  -e "DB_USER=sa" `
  -e "DB_PASSWORD=" `
  -e "TRUSTVAULT_STORAGE_DIR=/app/private-files" `
  -v trustvault-data:/app/data `
  -v trustvault-files:/app/private-files `
  trustvault:local
```

Open <http://localhost:8081>. The named Docker volumes retain the database and uploaded files when the container is stopped or removed. Stop and start the existing container with:

```powershell
docker stop trustvault
docker start trustvault
```

To remove the container, run `docker rm -f trustvault`. This does not remove the named volumes; remove them separately with `docker volume rm trustvault-data trustvault-files` only if you also intend to permanently delete the stored database and uploaded files. This configuration uses the development defaults and is not suitable for a publicly exposed deployment; configure production secrets, database, and SMTP before deploying.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `TRUSTVAULT_STORAGE_DIR` | `./private-files` | Private document storage directory |
| `TRUSTVAULT_AUDIT_KEY` | `local-development-key-change-me` | HMAC key for audit chain |
| `TRUSTVAULT_SEED_ENABLED` | `true` | Seed the three demo users if the user table is empty |
| `TRUSTVAULT_BOOTSTRAP_ADMIN_*` | unset | Provision the first production administrator (username, password, full name, and email), only when there are no accounts |
| `TRUSTVAULT_PASSWORD_RESET_ENABLED` | `true` | Enable email OTP password reset |
| `TRUSTVAULT_PASSWORD_RESET_KEY` | local development value | Secret key for HMAC-protecting OTPs in the database; use a unique secret of at least 32 bytes |
| `TRUSTVAULT_PASSWORD_RESET_FROM` | `noreply@localhost` | Sender address for OTP email |
| `TRUSTVAULT_REGISTRATION_OTP_ENABLED` | `true` | Require email OTP verification for new accounts |
| `TRUSTVAULT_REGISTRATION_OTP_KEY` | local development value | Secret key for HMAC-protecting registration OTPs; use a unique secret of at least 32 bytes |
| `TRUSTVAULT_REGISTRATION_FROM` | `noreply@localhost` | Sender address for registration verification emails |
| `TRUSTVAULT_REGISTRATION_LOCAL_OTP` | `false` | Local-inbox development switch; usable only with the `dev` Spring profile |
| `MAIL_HOST`, `MAIL_PORT` | `localhost`, `25` | SMTP server configuration; the defaults require a local SMTP server and do not provide email delivery by themselves |
| `MAIL_USERNAME`, `MAIL_PASSWORD` | empty | SMTP credentials; provide through environment variables and never commit them |
| `MAIL_SMTP_AUTH`, `MAIL_SMTP_STARTTLS` | `false` | SMTP authentication and STARTTLS toggles; set both to `true` for Gmail |

For any shared or deployed environment, use a unique, high-entropy audit and password-reset key kept outside the database and disable demo account seeding (`TRUSTVAULT_SEED_ENABLED=false`). Enable OTP reset only after configuring a real SMTP provider. On a fresh database, provide all four bootstrap administrator variables through a deployment secret manager; the application creates that initial ADMIN account only if the user table is empty, then does not use or modify the bootstrap credentials on subsequent starts. Do not expose admin self-registration or put these values in source control. Replace the H2 development database with a managed database, put the app behind HTTPS, and mount document storage on a private persistent volume. The default demo accounts and audit key are not suitable for deployment. Self-service registration creates Viewer accounts only; administrators can create Viewer or Owner accounts with an initial password, demote Owners to Viewers, deactivate/reactivate accounts, and delete accounts from **Admin → Users**. The user list has separate **Add user** and **User details** pages. Administrators cannot promote existing Viewers to Owners. Deactivated accounts cannot sign in or continue using an existing session; their document grants remain stored and apply again if the account is reactivated. Admin-created accounts are immediately usable and see a reminder to change the initial password until they do; account holders manage usernames, passwords, and pending profile tasks from the gear-icon **Account settings** page. Deletion removes grants to the account and requires transferring any owned documents to another active Owner. Admin roles can only be provisioned through this one-time operator bootstrap.

Example PowerShell configuration:

```powershell
$env:TRUSTVAULT_AUDIT_KEY = "<unique-long-random-secret>"
$env:TRUSTVAULT_SEED_ENABLED = "false"
$env:TRUSTVAULT_STORAGE_DIR = "D:\trustvault-private-files"
$env:TRUSTVAULT_BOOTSTRAP_ADMIN_USERNAME = "vault-admin"
$env:TRUSTVAULT_BOOTSTRAP_ADMIN_PASSWORD = "<unique-12+-character-secret>"
$env:TRUSTVAULT_BOOTSTRAP_ADMIN_FULL_NAME = "Vault Administrator"
$env:TRUSTVAULT_BOOTSTRAP_ADMIN_EMAIL = "vault-admin@example.com"
$env:TRUSTVAULT_REGISTRATION_OTP_ENABLED = "true"
$env:TRUSTVAULT_REGISTRATION_OTP_KEY = "<unique-random-secret-at-least-32-bytes>"
$env:TRUSTVAULT_REGISTRATION_FROM = "no-reply@example.com"
$env:TRUSTVAULT_PASSWORD_RESET_ENABLED = "true"
$env:TRUSTVAULT_PASSWORD_RESET_KEY = "<unique-random-secret-at-least-32-bytes>"
$env:TRUSTVAULT_PASSWORD_RESET_FROM = "no-reply@example.com"
$env:MAIL_HOST = "smtp.example.com"
$env:MAIL_PORT = "587"
$env:MAIL_USERNAME = "<smtp-username>"
$env:MAIL_PASSWORD = "<smtp-password>"
$env:MAIL_SMTP_AUTH = "true"
$env:MAIL_SMTP_STARTTLS = "true"
.\mvnw.cmd spring-boot:run
```

The registration page links to the current [Terms and Conditions](/terms) and [Privacy Notice](/privacy). Outside the local `dev` profile, if SMTP is unavailable or email delivery fails, registration does not create an account; configure a working SMTP server before enabling registration.

For local UI development without an SMTP server, start with the `dev` profile:

```powershell
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=dev
```

That profile enables a local OTP inbox on the registration page and only accepts registration-code requests from loopback addresses. The displayed code is for local development only and does **not** prove mailbox ownership. Never expose the `dev` profile to a network or use it in a deployed environment; production must use SMTP and keep `TRUSTVAULT_REGISTRATION_LOCAL_OTP=false`.

### Gmail SMTP for real email verification

For Gmail, enable 2-Step Verification on the sending Google account and create a Google **App Password** for TrustVault. Use that 16-character app password, not the regular Google account password. Do not share it in chat, store it in source files, or commit it. Keep `MAIL_USERNAME` equal to the full sending Gmail address.

Set the credentials in the same terminal/IDE launch configuration that starts the application. The `gmail` profile selects `smtp.gmail.com:587`, SMTP authentication, STARTTLS, and connection timeouts. Password reset is enabled by default; set a separate signing key and sender address for it:

```powershell
$env:MAIL_USERNAME = "your-sending-address@gmail.com"
$env:MAIL_PASSWORD = "<Google App Password without spaces>"
$env:TRUSTVAULT_REGISTRATION_OTP_ENABLED = "true"
$env:TRUSTVAULT_REGISTRATION_OTP_KEY = "<unique-random-secret-at-least-32-bytes>"
$env:TRUSTVAULT_REGISTRATION_FROM = "your-sending-address@gmail.com"
$env:TRUSTVAULT_PASSWORD_RESET_ENABLED = "true"
$env:TRUSTVAULT_PASSWORD_RESET_KEY = "<different-unique-random-secret-at-least-32-bytes>"
$env:TRUSTVAULT_PASSWORD_RESET_FROM = "your-sending-address@gmail.com"
.\mvnw.cmd -Dspring-boot.run.profiles=gmail spring-boot:run
```

If you launch from IntelliJ, add `gmail` as the active Spring profile and set the variables above in the run configuration's environment variables. Password-reset requests ask for the account's email address; the verification code is sent there. If `dev` is also active, TrustVault displays a local code instead of sending email, so do not activate the `dev` profile when testing actual mailbox verification. After startup, request a code for an address you can access and check the inbox and spam folder. If SMTP still fails, the application logs the root SMTP exception type; verify the App Password, sender address, and Google account's 2-Step Verification. Never log or paste the password.

Hibernate creates/updates the schema from the JPA entities (`spring.jpa.hibernate.ddl-auto=update`). The test profile uses a disposable in-memory H2 database. File content is never stored as a database BLOB and is never served from a static-resource directory.

## Roles and permission model

* **ADMIN**: may read the administrative audit view. Admin status does not bypass document permissions.
* **Role and account administration**: only an ADMIN may create VIEWER/OWNER accounts, demote OWNER accounts to VIEWER, or deactivate/reactivate accounts. Administrators cannot promote existing VIEWER accounts to OWNER. Public registration never accepts a role. The first production admin is created by an operator-provided bootstrap credential set on an empty database; admin accounts cannot be created through the web app, and an admin cannot deactivate themselves or deactivate/delete the last active admin account. Every account creation, deletion, role change, and account-status change is included in the audit trail.
* **OWNER**: may upload documents; the owner of each document may read, download, edit metadata, delete, grant access, and revoke access to that document.
* **VIEWER**: has no document access by default. A per-document **READ** grant permits viewing metadata and downloading. An **EDIT** grant also permits metadata updates, but cannot manage grants or delete.

The account settings page is available from the gear icon in the signed-in header. It always allows the account holder to change username or password, and offers adding a full name or verifying email only when those details are missing or unverified. If no email was provided during registration or account creation, the user can add one from settings and verify it with the emailed OTP; the verification action disappears after success. Changing a username requires the current password, preserves document ownership and grants, and requires signing in again. Email verification uses a single-use six-digit code with a ten-minute expiry, five attempts, HMAC-protected storage, and a one-minute resend cooldown.

Authenticated users can search across all document names, categories, owner usernames, and upload dates. Search results appear only after submission and are cleared by refreshing the documents page. This is an intentional metadata disclosure: search does not reveal descriptions or file contents. Search results indicate whether access is granted; opening an unshared document returns an audited 403 message. Downloads and all changes continue to require current document-level authorization.

Owners have **My uploads** and **Audit activity** tabs. My uploads lists only documents currently owned by that account. Owner audit activity is limited to events for those currently owned documents; administrators retain access to the full audit trail.

Every document operation checks the authenticated username against the document owner or its current grant in the database. The list/search query includes only documents visible to that user. Revocation deletes the grant; authorization is re-evaluated on each request and no share token or cached permission is issued, so the next request is denied. A missing document and an inaccessible document both return 404 to reduce identifier probing.

## Upload and audit

Uploads are limited to 10 MiB and accepted only when the extension and file signature/encoding match PDF, PNG, JPEG, or UTF-8 plain text. Stored names are random server-generated identifiers, not client filenames. Downloads use attachment disposition and `nosniff`.

The application records successful upload, view, download, metadata edit, deletion, grant, and revocation operations, plus denied authenticated and unauthenticated authorization/security-filter attempts on document-specific routes. Successful action records commit in the same transaction as their changes. Each audit row contains the previous row's HMAC-SHA-256 and its own HMAC; a database-stored chain head lets the admin activity page detect ordinary row edits and truncation. The signing key must be configured outside the database. Because the head is stored in the same database, a privileged database attacker can roll back both events and head to a prior valid state; external anchoring is needed for stronger evidence. See [THREATS.md](THREATS.md) for limitations and mitigations.

## Documentation and status

* [API access matrix](API.md)
* [Threat model and accepted risks](THREATS.md)
* [AI prompt log](PROMPTS.md)

This workspace does not include a deployed URL, public Git history, or a demo video. Those deliverables must be created by the project owner; no deployment or commit history is implied by this local implementation. The prompt log is intentionally honest about that limitation.
