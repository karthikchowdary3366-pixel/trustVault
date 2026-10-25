# Access Control Flow - Quick Reference

## Document Download/View Flow

### When viewer requests document:
```
GET /documents/{documentId}/download

1. DocumentService.requirePermission()
   ├─ Validate documentId format
   ├─ Fetch document from DB
   └─ Call canAccessDocument()

2. canAccessDocument(username, document, edit=false)
   ├─ Owner? YES → Allow
   ├─ Has direct document grant? YES → Allow
   ├─ Document in folder?
   │  ├─ Viewer has folder grant?
   │  │  ├─ YES → Allow
   │  │  └─ NO → Deny
   │  └─ No folder → Deny
   └─ Final: Allow or Deny

3. If Allow:
   ├─ Read document from storage
   ├─ Audit: recordSuccess(username, "DOWNLOAD", documentId, ...)
   └─ Return file

4. If Deny:
   ├─ Audit: recordDenied(username, "DOWNLOAD", documentId, ...)
   └─ Throw: ResponseStatusException(HTTP 404)
```

### Code Implementation (DocumentService.java):
```java
private boolean canAccessDocument(String username, VaultDocument document, boolean edit) {
    if (document.getOwner().getUsername().equals(username)) {
        return true;  // Owner always has access
    }
    
    if (edit) {
        // For edit: only explicit EDIT grant or folder grant
        return grants.findByDocumentIdAndGranteeUsername(document.getId(), username)
                .filter(grant -> grant.getPermission() == DocumentPermission.EDIT)
                .isPresent();
    }
    
    // For view/download: explicit grant OR folder grant
    return grants.findByDocumentIdAndGranteeUsername(document.getId(), username).isPresent()
            || (document.getFolder() != null
                    && folderGrants.findByFolderIdAndGranteeUsername(
                        document.getFolder().getId(), username).isPresent());
}
```

## Folder Access Flow

### When viewer requests folder view:
```
GET /folders/{folderId}

1. FolderService.viewFolder(username, folderId)
   ├─ Validate folderId format
   ├─ Fetch folder from DB
   └─ Call canViewFolder()

2. canViewFolder(username, folder)
   ├─ Owner of folder? YES → Allow
   ├─ Has FolderGrant? YES → Allow
   └─ NO → Deny (403 Forbidden)

3. If Allow:
   ├─ Fetch all documents in folder
   ├─ For each document:
   │  └─ Check canAccessDocument()
   │     ├─ Document shows with ✓ if accessible
   │     └─ Document shows with ✗ if not accessible
   ├─ Audit: recordSuccess(username, "VIEW_FOLDER", folderId, ...)
   └─ Return folder view with document list

4. If Deny:
   ├─ Audit: recordDenied(username, "VIEW_FOLDER", folderId, ...)
   └─ Return 403 Forbidden (folder-level - prevented enumeration)
```

### Code Implementation (FolderService.java):
```java
@Transactional(readOnly = true)
public FolderViewResult viewFolder(String username, String folderId) {
    // ... validation ...
    
    Folder folder = folders.findById(folderId).orElse(null);
    if (folder == null || !canViewFolder(username, folder)) {
        audit.recordDenied(username, "VIEW_FOLDER", folderId, "Folder access denied");
        return new FolderViewResult(null, false, List.of());
    }

    // Viewer can see folder - now check document access
    List<VaultDocument> docsInFolder = documents.findAllByFolderId(folderId);
    List<DocumentAccessInfo> accessibleDocs = docsInFolder.stream()
            .map(doc -> new DocumentAccessInfo(doc, canAccessDocument(username, doc)))
            .toList();

    audit.recordSuccess(username, "VIEW_FOLDER", folderId, "Folder contents viewed");
    return new FolderViewResult(folder, true, accessibleDocs);
}

private boolean canViewFolder(String username, Folder folder) {
    return folder.getOwner().getUsername().equals(username)
            || grants.findByFolderIdAndGranteeUsername(folder.getId(), username).isPresent();
}
```

## Grant Propagation

### Folder Grant → Document Visibility
```
Owner grants folder access to Viewer
    ↓
FolderGrant created: folder_id, grantee_username
    ↓
When Viewer views folder:
    1. Check: canViewFolder() → TRUE (has FolderGrant)
    2. List all docs in folder
    3. For each doc: check canAccessDocument()
       ├─ Doc A: Owner? NO. Direct grant? NO. Folder grant? YES → Accessible
       ├─ Doc B: Owner? NO. Direct grant? YES (READ). Folder grant? YES → Accessible
       └─ Doc C: Owner? NO. Direct grant? NO. Folder grant? YES → Not accessible
                                                                    (no read permission)

Result: Viewer sees folder with mixed accessible/inaccessible documents
```

### Document Grant NOT affected by folder
```
Owner grants document READ access to Viewer
    ↓
DocumentGrant created: document_id, grantee_username, permission=READ
    ↓
Viewer can download document:
    ├─ Has document grant? YES → Can download
    ├─ Even if folder grant revoked → Can still download
    ├─ Folder-level access is independent
    └─ Document grant takes precedence
```

## Audit Trail Recording

### Success Path
```
User: viewer
Document: ContractA (accessible via folder grant)
Action: DOWNLOAD

AuditEvent created:
{
  actor: "viewer",
  action: "DOWNLOAD",
  documentId: "abc-123-def",
  details: "Document downloaded",
  allowed: true,
  occurredAt: 2024-01-15T10:30:00Z
}
```

### Denial Path
```
User: viewer
Document: SecretDoc (NOT granted, even though folder granted)
Action: DOWNLOAD attempt

AuditEvent created:
{
  actor: "viewer",
  action: "DOWNLOAD",
  documentId: "xyz-789-uvw",
  details: "Document access denied",
  allowed: false,
  occurredAt: 2024-01-15T10:31:00Z
}

Response: HTTP 404 (not 403 - prevents enumeration)
```

## Access Control Decision Tree

### For Document Operations:

```
Is user the document owner?
├─ YES → ALLOW (full access)
└─ NO → Continue

Is it an EDIT operation?
├─ YES → Check for explicit EDIT grant only
│  └─ Has DocumentGrant with EDIT permission?
│     ├─ YES → ALLOW
│     └─ NO → DENY (404)
└─ NO → Check for VIEW/READ

For VIEW/READ operations:
├─ Has explicit document grant?
│  ├─ YES → ALLOW
│  └─ NO → Continue
├─ Document in folder?
│  ├─ YES → Viewer has folder grant?
│  │  ├─ YES → ALLOW
│  │  └─ NO → DENY (404)
│  └─ NO → DENY (404)
```

### For Folder Operations:

```
Viewing folder contents:
├─ User is folder owner?
│  ├─ YES → ALLOW (403 if denied at folder level)
│  └─ NO → Has FolderGrant?
│     ├─ YES → ALLOW (403 if denied at folder level)
│     └─ NO → DENY (403 Forbidden)

For each document in folder:
├─ User is doc owner? YES → Show accessible
├─ Has direct doc grant? YES → Show accessible
├─ Has folder grant? YES → Show accessible
└─ NO → Show as inaccessible
```

## HTTP Status Codes

| Scenario | Status | Reason |
|----------|--------|--------|
| Document accessible (viewer has grant) | 200 OK | Content returned |
| Document not found (wrong ID) | 404 NOT FOUND | Consistent 404 |
| Document denied (no grant, not owner) | 404 NOT FOUND | Enumeration prevention |
| Folder not found (wrong ID) | 404 NOT FOUND | Consistent 404 |
| Folder denied (no folder grant, not owner) | 403 FORBIDDEN | Folder access explicitly checked |
| User not authenticated | 401 UNAUTHORIZED | Spring Security |
| User not authorized (wrong role) | 403 FORBIDDEN | @PreAuthorize check |
| Invalid input (bad format) | 400 BAD REQUEST | Validation failed |

## Key Design Decisions

### 1. Why 404 for denied document access?
- **Prevents enumeration**: Attacker can't determine if document exists
- **Consistent**: Also used for truly missing documents
- **Security**: If status code reveals existence, attack surface expands

### 2. Why 403 for denied folder access?
- **Folder is structural**: Users know it exists if granted folder
- **Different semantics**: Folder grant is explicit permission check
- **User feedback**: Clear that access is denied vs document missing

### 3. Why separate folder and document grants?
- **Flexibility**: Owner can grant folder visibility without document access
- **Performance**: One query per level instead of checking all documents
- **Intent**: Folder grant = "you can see what's here", Doc grant = "you can access this"

### 4. Why audit all attempts?
- **Compliance**: Legal requirement to track access
- **Security**: Detects unauthorized access patterns
- **Debugging**: Helps troubleshoot access issues
- **Non-blocking**: Separate transaction doesn't slow down main operation

## Example Scenarios

### Scenario A: Folder visibility without document access
```
Owner: Alice
Viewer: Bob
Folder: "Q4 Contracts"

Action: Alice grants folder access to Bob
Result: Bob can see "Q4 Contracts" folder
        Bob can see all documents in folder
        Bob cannot download any document (no document grants)

Action: Bob tries to download Contract01
Result: HTTP 404
        Audit: DOWNLOAD / DENIED / Bob / Contract01
        No enumeration: Bob can't tell if doc exists or if access denied
```

### Scenario B: Mixed folder + document access
```
Owner: Alice
Viewer: Charlie
Folder: "Q4 Contracts" (docs: ContractA, ContractB, ContractC)

Action: Alice grants folder access to Charlie
        Alice grants READ access to ContractA only
Result: Charlie views folder → sees all 3 contracts
        - ContractA: ✓ (has folder grant + doc grant)
        - ContractB: ✗ (has folder grant, no doc grant)
        - ContractC: ✗ (has folder grant, no doc grant)

Action: Charlie tries to download ContractB
Result: HTTP 404 (enumeration prevention)

Action: Charlie downloads ContractA
Result: HTTP 200 OK (has access)
```

### Scenario C: Revocation
```
Owner: Alice
Viewer: Dave
Document: SecretDoc

Grants in effect:
  - Dave has folder grant (can see folder)
  - Dave has READ grant on SecretDoc
  
Action: Alice revokes folder grant
Result: Dave can no longer see folder
        Dave cannot download SecretDoc (folder grant gone)
        But: Document-level READ grant still exists
        
Action: Dave somehow gets document ID, tries download
Result: HTTP 200 OK (document grant still valid)
        Audit: DOWNLOAD / SUCCESS / Dave / SecretDoc
        
Note: Dave can access doc because of direct grant, not folder grant
```

## Security Properties Guaranteed

1. **No Anonymous Access**: All operations require authentication
2. **No Privilege Escalation**: Viewer cannot become owner
3. **No Data Leakage**: Denied docs return 404, no content
4. **No Enumeration**: Attacker cannot list documents via brute force
5. **No Bypass**: Folder grant doesn't give document access
6. **Immutable History**: Audit events cannot be deleted
7. **Atomic Operations**: No partial grants possible
8. **Role-Based**: Different users have different capabilities
