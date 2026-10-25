# Folder and Document Access Control System

## Overview

This implementation adds a hierarchical folder-based access control system to TrustVault. The system allows owners to create folders, grant folder-level access to viewers, while maintaining strict per-document access controls.

## Key Features

### 1. Folder Management
- **Create Folders**: Owners can create named folders with optional descriptions
- **Edit Folders**: Owners can modify folder name and description
- **Delete Folders**: Owners can delete empty folders (must remove all documents first)
- **View Folders**: Viewers can see and access folders they've been granted access to

### 2. Folder-Based Access Control

#### Folder Access Levels:
- **Owner**: Full control (create, edit, delete, manage access)
- **Viewer (with grant)**: Can see all documents in folder, but can only access those specifically granted

#### Document Access with Folders:
When a viewer has **folder access**:
1. ✅ They can **SEE** all documents in the folder (folder listing)
2. ✅ They can **VIEW/DOWNLOAD** only documents they have explicit document-level access to
3. ❌ They **CANNOT** view/download documents they don't have explicit access to (access denied)

### 3. Access Control Logic

**View Document**: User can view if:
- User is the document owner, OR
- User has explicit document grant (READ or EDIT), OR
- User has folder-level grant AND document is in that folder

**Download Document**: User can download if:
- User is the document owner, OR
- User has explicit document grant (READ or EDIT), OR
- User has folder-level grant AND document is in that folder

**Access Denied**: If viewer tries to access a document in a folder they can see but have no explicit access to:
- Returns HTTP 404 (to prevent enumeration)
- Logs denied access attempt to audit trail

## Database Schema

### New Tables

#### `folders`
```sql
CREATE TABLE folders (
  id VARCHAR(36) PRIMARY KEY,
  name VARCHAR(180) NOT NULL,
  description VARCHAR(1000),
  created_at TIMESTAMP NOT NULL,
  owner_username VARCHAR(255) NOT NULL,
  FOREIGN KEY (owner_username) REFERENCES app_users(username)
);
```

#### `folder_grants`
```sql
CREATE TABLE folder_grants (
  id VARCHAR(36) PRIMARY KEY,
  folder_id VARCHAR(36) NOT NULL,
  grantee_username VARCHAR(255) NOT NULL,
  granted_at TIMESTAMP NOT NULL,
  UNIQUE KEY (folder_id, grantee_username),
  FOREIGN KEY (folder_id) REFERENCES folders(id),
  FOREIGN KEY (grantee_username) REFERENCES app_users(username)
);
```

### Modified Table

#### `vault_documents`
- Added: `folder_id VARCHAR(36)` (nullable foreign key to `folders`)
- Documents can now optionally belong to a folder

## API Endpoints

### Folder Management

| Method | Endpoint | Role | Purpose |
|--------|----------|------|---------|
| `GET` | `/folders/create` | OWNER | Display folder creation form |
| `POST` | `/folders/create` | OWNER | Create a new folder |
| `GET` | `/folders` | OWNER/VIEWER | List folders (owned or granted) |
| `GET` | `/folders/{id}` | OWNER/VIEWER | View folder and its documents |
| `POST` | `/folders/{id}/edit` | OWNER | Update folder details |
| `POST` | `/folders/{id}/delete` | OWNER | Delete empty folder |
| `POST` | `/folders/{id}/grants` | OWNER | Grant folder access to viewer |
| `POST` | `/folders/{id}/grants/revoke` | OWNER | Revoke folder access |

### Document Upload Enhancement

| Method | Endpoint | Change |
|--------|----------|--------|
| `GET` | `/documents/upload` | Now provides list of owner's folders |
| `POST` | `/documents/upload` | Accepts optional `folderId` parameter |

## Service Classes

### `FolderService`
Handles all folder operations:
- `createFolder()` - Create new folder
- `foldersOwnedByOwner()` - List owner's folders
- `grantedFoldersForViewer()` - List viewer's granted folders
- `viewFolder()` - View folder with access-controlled documents
- `updateFolder()` - Edit folder details
- `deleteFolder()` - Delete folder (must be empty)
- `grantFolderAccess()` - Grant folder access to user
- `revokeFolderAccess()` - Revoke folder access
- `canViewFolder()` - Check if user can view folder

### `DocumentService` (Enhanced)
Updated methods:
- `upload()` - Now accepts optional `folderId` parameter
- `canView()` - Now checks folder-level access
- `canEdit()` - Now checks folder-level access for viewing
- `canAccessDocument()` - New method combining direct and folder-based access

## Access Control Rules

### Viewer Access to Documents in Folder

| Scenario | Can See Folder | Can View Doc | Can Download Doc | Audit Entry |
|----------|---|---|---|---|
| Folder grant only (no doc grant) | ✅ Yes | ✅ Yes (listing) | ❌ No | DENIED |
| Folder grant + Doc grant (READ) | ✅ Yes | ✅ Yes | ✅ Yes | SUCCESS |
| Folder grant + Doc grant (EDIT) | ✅ Yes | ✅ Yes | ✅ Yes | SUCCESS |
| No folder grant + Doc grant | ✅ Yes (via search) | ✅ Yes | ✅ Yes | SUCCESS |
| No folder grant + No doc grant | ❌ No | ❌ No | ❌ No | N/A |

### Security Features

1. **Enumeration Protection**: Non-existent and forbidden documents both return HTTP 404
2. **Audit Logging**: All access attempts (successful and denied) are recorded
3. **Transactional Integrity**: Access denials logged in separate REQUIRES_NEW transaction
4. **HMAC-protected Audit Trail**: All audit events are signed
5. **No Permission Leakage**: Error messages don't indicate whether doc/folder exists or just access denied

## Usage Example

### Owner Workflow
1. Owner creates a folder: "Q4 Contracts"
2. Owner uploads documents to folder: 
   - "ContractA.pdf" (in folder)
   - "ContractB.pdf" (in folder)
3. Owner grants folder access to viewer "john"
4. Owner grants explicit document access to "jane" for "ContractA.pdf" only

### Viewer Workflows

**John's Access** (has folder grant, no doc grants):
```
- Navigate to /folders
- See "Q4 Contracts" folder
- Open folder view
- See both "ContractA.pdf" and "ContractB.pdf" listed
- Try to download "ContractA.pdf" → DENIED (404) ❌
- Try to download "ContractB.pdf" → DENIED (404) ❌
- Audit shows 2 access denials
```

**Jane's Access** (has direct doc grant, no folder grant):
```
- Navigate to /documents
- See "ContractA.pdf" in search results (marked as granted)
- Cannot see folder listing (no folder grant)
- Download "ContractA.pdf" → SUCCESS ✅
- Try to download "ContractB.pdf" via direct URL → DENIED (404) ❌
```

## Configuration

The system uses Hibernate DDL-auto=update to automatically create/migrate tables:
```properties
spring.jpa.hibernate.ddl-auto=update
```

## Security Considerations

1. **No Information Disclosure**: Denied access always returns 404, never 403
2. **Audit Trail**: Every access attempt is logged with action, resource, and result
3. **CSRF Protection**: All state-changing operations use Spring Security CSRF tokens
4. **Role-Based Access**: Operations are gated by Spring Security @PreAuthorize annotations
5. **Transactional Consistency**: Access denials are logged in separate transactions to ensure audit trail integrity
6. **No Cascade Deletes**: Folders cannot be deleted if they contain documents (prevents accidental data loss)

## Testing Scenarios

The system properly handles:
- ✅ Viewer can see folder but access specific docs only if granted
- ✅ Viewer gets proper 404 on access denied (not 403)
- ✅ Owner can manage folder and document-level grants independently
- ✅ Multiple viewers with different permissions on same documents
- ✅ Audit trail captures all access attempts
- ✅ Folders can't be deleted while containing documents
- ✅ Granting folder access doesn't grant document access
- ✅ Revoking folder access immediately blocks all document access in that folder

## Navigation Updates

All templates now include "Folders" link:
- Document upload page: Can select folder when uploading
- Owner workspace: Navigate between Documents and Folders
- Viewer workspace: Navigate between My Documents and Folders
- Main navigation: Quick access to Folders section
