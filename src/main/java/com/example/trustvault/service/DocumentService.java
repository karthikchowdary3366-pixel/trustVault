package com.example.trustvault.service;

import com.example.trustvault.model.AppUser;
import com.example.trustvault.model.DocumentGrant;
import com.example.trustvault.model.DocumentPermission;
import com.example.trustvault.model.Folder;
import com.example.trustvault.model.FolderGrant;
import com.example.trustvault.model.Role;
import com.example.trustvault.model.VaultDocument;
import com.example.trustvault.repository.AppUserRepository;
import com.example.trustvault.repository.DocumentGrantRepository;
import com.example.trustvault.repository.FolderGrantRepository;
import com.example.trustvault.repository.FolderRepository;
import com.example.trustvault.repository.VaultDocumentRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentService {
	private final VaultDocumentRepository documents;
	private final DocumentGrantRepository grants;
	private final FolderGrantRepository folderGrants;
	private final FolderRepository folders;
	private final AppUserRepository users;
	private final PrivateFileStorage storage;
	private final AuditService audit;

	public DocumentService(VaultDocumentRepository documents, DocumentGrantRepository grants,
			FolderGrantRepository folderGrants, FolderRepository folders, AppUserRepository users,
			PrivateFileStorage storage, AuditService audit) {
		this.documents = documents;
		this.grants = grants;
		this.folderGrants = folderGrants;
		this.folders = folders;
		this.users = users;
		this.storage = storage;
		this.audit = audit;
	}

	@Transactional(readOnly = true)
	public List<DocumentSearchResult> searchAll(String username, String name, String owner, String category,
			LocalDate from, LocalDate to) {
		return documents.findAllByOrderByUploadedAtDesc().stream()
				.filter(document -> contains(document.getName(), name))
				.filter(document -> contains(document.getOwner().getUsername(), owner))
				.filter(document -> contains(document.getCategory(), category))
				.filter(document -> from == null || !document.getUploadedAt()
						.isBefore(from.atStartOfDay().toInstant(ZoneOffset.UTC)))
				.filter(document -> to == null || document.getUploadedAt()
						.isBefore(to.equals(LocalDate.MAX) ? Instant.MAX
								: to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)))
				.map(document -> new DocumentSearchResult(document, canView(username, document)))
				.toList();
	}

	@Transactional(readOnly = true)
	public List<VaultDocument> uploadedByOwner(String username) {
		if (currentRole(username) != Role.OWNER) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only document owners can view their uploads.");
		}
		return documents.findAllByOwnerUsername(username).stream()
				.sorted((left, right) -> right.getUploadedAt().compareTo(left.getUploadedAt()))
				.toList();
	}

	@Transactional(readOnly = true)
	public List<ViewerDocument> documentsForViewer(String username) {
		if (currentRole(username) != Role.VIEWER) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only viewers can view their granted documents.");
		}
		Map<String, ViewerDocument> viewerDocuments = new LinkedHashMap<>();
		grants.findAllByGranteeUsername(username).stream()
				.map(DocumentGrant::getDocument)
				.forEach(document -> viewerDocuments.put(document.getId(), new ViewerDocument(document, true)));

		List<String> folderIds = folderGrants.findAllByGranteeUsername(username).stream()
				.map(FolderGrant::getFolder)
				.map(Folder::getId)
				.toList();
		if (!folderIds.isEmpty()) {
			documents.findAllByFolderIdIn(folderIds).forEach(document -> viewerDocuments.putIfAbsent(
					document.getId(), new ViewerDocument(document, canView(username, document))));
		}

		return viewerDocuments.values().stream()
				.sorted((left, right) -> right.document().getUploadedAt().compareTo(left.document().getUploadedAt()))
				.toList();
	}

	@Transactional
	public VaultDocument upload(String username, String name, String category, String description, MultipartFile file) {
		return upload(username, name, category, description, file, null);
	}

	@Transactional
	public VaultDocument upload(String username, String name, String category, String description, MultipartFile file,
			String folderId) {
		return upload(username, name, category, description, file, folderId, List.of(), DocumentPermission.READ);
	}

	@Transactional(readOnly = true)
	public List<AppUser> uploadGrantTargets(String username) {
		if (currentRole(username) != Role.OWNER) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only document owners can upload.");
		}
		return users.findAllByActiveTrueOrderByUsernameAsc().stream()
				.filter(user -> !user.getUsername().equals(username)).toList();
	}

	@Transactional
	public VaultDocument upload(String username, String name, String category, String description, MultipartFile file,
			String folderId, List<String> granteeNames, DocumentPermission permission) {
		AppUser owner = users.findById(username)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Account is unavailable."));
		if (owner.getRole() != Role.OWNER) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only document owners can upload.");
		}
		validateMetadata(name, category, description);

		Folder folder = null;
		if (folderId != null && !folderId.isBlank()) {
			folder = folders.findById(folderId).orElse(null);
			if (folder == null || !folder.getOwner().getUsername().equals(username)) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid folder selected.");
			}
		}

		java.util.Set<AppUser> grantees = new java.util.LinkedHashSet<>();
		if (granteeNames != null) {
			for (String granteeName : granteeNames) {
				AppUser grantee = users.findById(granteeName == null ? "" : granteeName.trim()).orElse(null);
				if (grantee == null || !grantee.isActive() || grantee.getUsername().equals(username)
						|| permission == null) {
					audit.recordDenied(username, "GRANT", null, "Invalid access grant target at upload");
					throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
							"Choose existing active accounts other than yourself.");
				}
				grantees.add(grantee);
			}
		}

		PrivateFileStorage.StoredFile stored;
		try {
			stored = storage.store(file);
		} catch (IllegalArgumentException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
		}
		String documentId = UUID.randomUUID().toString();
		VaultDocument document = new VaultDocument(documentId, name.trim(), category.trim(),
				normalizeDescription(description), stored.storageKey(), stored.contentType(), stored.size(),
				Instant.now(), owner, folder);
		try {
			documents.save(document);
			audit.recordSuccess(username, "UPLOAD", document.getId(), "Document uploaded");
			for (AppUser grantee : grantees) {
				grants.save(new DocumentGrant(UUID.randomUUID().toString(), document, grantee, permission,
						Instant.now()));
				audit.recordSuccess(username, "GRANT", document.getId(),
						"Access granted to " + grantee.getUsername() + " with " + permission.name());
			}
		} catch (RuntimeException exception) {
			storage.delete(stored.storageKey());
			throw exception;
		}
		return document;
	}

	@Transactional
	public DocumentViewResult view(String username, String documentId) {
		if (documentId == null || documentId.length() != 36) {
			audit.recordDenied(username, "VIEW", documentId, "Document access denied");
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found.");
		}
		VaultDocument document = documents.findById(documentId).orElse(null);
		if (document == null) {
			audit.recordDenied(username, "VIEW", documentId, "Document access denied");
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found.");
		}
		if (!canView(username, document)) {
			audit.recordDenied(username, "VIEW", documentId, "Document access denied");
			return new DocumentViewResult(null, false);
		}
		audit.recordSuccess(username, "VIEW", documentId, "Document details viewed");
		return new DocumentViewResult(document, true);
	}

	@Transactional
	public OpenedDocumentResult download(String username, String documentId) {
		if (documentId == null || documentId.length() != 36) {
			audit.recordDenied(username, "DOWNLOAD", documentId, "Document access denied");
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found.");
		}
		VaultDocument document = documents.findById(documentId).orElse(null);
		if (document == null) {
			audit.recordDenied(username, "DOWNLOAD", documentId, "Document access denied");
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found.");
		}
		if (!canView(username, document)) {
			audit.recordDenied(username, "DOWNLOAD", documentId, "Document access denied");
			return new OpenedDocumentResult(false, null);
		}
		byte[] content = storage.read(document.getStorageKey());
		audit.recordSuccess(username, "DOWNLOAD", documentId, "Document downloaded");
		return new OpenedDocumentResult(true,
				new DownloadedDocument(document.getName(), document.getContentType(), content));
	}

	@Transactional
	public OpenedDocumentResult open(String username, String documentId) {
		if (documentId == null || documentId.length() != 36) {
			audit.recordDenied(username, "OPEN", documentId, "Document access denied");
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found.");
		}
		VaultDocument document = documents.findById(documentId).orElse(null);
		if (document == null) {
			audit.recordDenied(username, "OPEN", documentId, "Document access denied");
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found.");
		}
		if (!canView(username, document)) {
			audit.recordDenied(username, "OPEN", documentId, "Document access denied");
			return new OpenedDocumentResult(false, null);
		}
		byte[] content = storage.read(document.getStorageKey());
		audit.recordSuccess(username, "OPEN", documentId, "Document opened");
		return new OpenedDocumentResult(true,
				new DownloadedDocument(document.getName(), document.getContentType(), content));
	}

	@Transactional
	public void update(String username, String documentId, String name, String category, String description) {
		update(username, documentId, name, category, description, null);
	}

	@Transactional
	public void update(String username, String documentId, String name, String category, String description,
			String content) {
		VaultDocument document = requirePermission(username, documentId, true, "EDIT");
		validateMetadata(name, category, description);
		boolean contentChanged = false;
		if (content != null) {
			if (!"text/plain".equals(document.getContentType())) {
				audit.recordDenied(username, "EDIT", documentId, "Online editing unsupported for this file type");
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
						"Only text documents can be edited online.");
			}
			byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
			try {
				storage.overwrite(document.getStorageKey(), bytes);
			} catch (IllegalArgumentException exception) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
			}
			document.updateSize(bytes.length);
			contentChanged = true;
		}
		document.update(name.trim(), category.trim(), normalizeDescription(description));
		audit.recordSuccess(username, "EDIT", documentId,
				contentChanged ? "Document content and metadata edited online" : "Document metadata changed");
	}

	@Transactional
	public void replaceContent(String username, String documentId, org.springframework.web.multipart.MultipartFile file) {
		VaultDocument document = requirePermission(username, documentId, true, "EDIT");
		String type;
		try {
			type = storage.detectType(file);
			if (!type.equals(document.getContentType())) {
				throw new IllegalArgumentException("Upload the edited version of the same type of file.");
			}
			storage.overwrite(document.getStorageKey(), file);
		} catch (IllegalArgumentException exception) {
			audit.recordDenied(username, "EDIT", documentId, "Edited file upload rejected");
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
		}
		document.updateSize(file.getSize());
		audit.recordSuccess(username, "EDIT", documentId, "Edited file uploaded and content replaced");
	}

	@Transactional(readOnly = true)
	public String editableText(String username, String documentId) {
		VaultDocument document = requirePermission(username, documentId, true, "EDIT");
		if (!"text/plain".equals(document.getContentType())) {
			return null;
		}
		return new String(storage.read(document.getStorageKey()), java.nio.charset.StandardCharsets.UTF_8);
	}

	@Transactional(readOnly = true)
	public VaultDocument editableDocument(String username, String documentId) {
		return requirePermission(username, documentId, true, "EDIT");
	}

	@Transactional
	public void delete(String username, String documentId) {
		VaultDocument document = requireDeletePermission(username, documentId);
		storage.delete(document.getStorageKey());
		grants.deleteByDocumentId(documentId);
		documents.delete(document);
		audit.recordSuccess(username, "DELETE", documentId, "Document deleted");
	}

	@Transactional
	public void deleteFolderDocuments(String username, String folderId) {
		Folder folder = folderId == null || folderId.length() != 36
				? null
				: folders.findById(folderId).orElse(null);
		if (folder == null || !folder.getOwner().getUsername().equals(username)) {
			audit.recordDenied(username, "DELETE_FOLDER_DOCUMENTS", folderId, "Owner-only action denied");
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Folder not found.");
		}
		for (VaultDocument document : documents.findAllByFolderId(folderId)) {
			delete(username, document.getId());
		}
	}

	@Transactional
	public void grant(String username, String documentId, String granteeName, DocumentPermission permission) {
		VaultDocument document = requireOwner(username, documentId, "GRANT");
		AppUser grantee = users.findById(granteeName == null ? "" : granteeName.trim()).orElse(null);
		if (grantee == null || !grantee.isActive() || grantee.getUsername().equals(username)) {
			audit.recordDenied(username, "GRANT", documentId, "Invalid or self access grant target");
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose another existing active account.");
		}
		if (permission == null) {
			audit.recordDenied(username, "GRANT", documentId, "Invalid access permission");
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose read or edit access.");
		}
		DocumentGrant grant = grants.findByDocumentIdAndGranteeUsername(documentId, grantee.getUsername())
				.orElseGet(() -> new DocumentGrant(UUID.randomUUID().toString(), document, grantee, permission, Instant.now()));
		if (grant.getAccessLevel() != permission) {
			grant.changePermission(permission);
		}
		grants.save(grant);
		audit.recordSuccess(username, "GRANT", documentId,
				"Access granted to " + grantee.getUsername() + " with " + permission.name());
	}

	@Transactional
	public void grantMultiple(String username, String documentId, List<String> granteeNames,
			DocumentPermission permission) {
		requireOwner(username, documentId, "GRANT");
		if (granteeNames == null || granteeNames.isEmpty()) {
			audit.recordDenied(username, "GRANT", documentId, "No access grant targets selected");
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select at least one person to grant access.");
		}
		if (permission == null) {
			audit.recordDenied(username, "GRANT", documentId, "Invalid access permission");
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose read or edit access.");
		}
		java.util.Set<String> distinctGrantees = new java.util.LinkedHashSet<>();
		for (String granteeName : granteeNames) {
			if (granteeName == null || granteeName.isBlank()) {
				audit.recordDenied(username, "GRANT", documentId, "Invalid access grant target");
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose existing active accounts other than yourself.");
			}
			distinctGrantees.add(granteeName.trim());
		}
		for (String granteeName : distinctGrantees) {
			grant(username, documentId, granteeName, permission);
		}
	}

	@Transactional
	public void revoke(String username, String documentId, String granteeName) {
		requireOwner(username, documentId, "REVOKE");
		DocumentGrant grant = grants.findByDocumentIdAndGranteeUsername(documentId, granteeName)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Grant not found."));
		grants.delete(grant);
		audit.recordSuccess(username, "REVOKE", documentId, "Access revoked from " + granteeName);
	}

	@Transactional(readOnly = true)
	public List<DocumentGrant> listGrants(String username, String documentId) {
		requireOwner(username, documentId, "LIST_GRANTS");
		return grants.findAllByDocumentIdOrderByGranteeUsernameAsc(documentId);
	}

	@Transactional(readOnly = true)
	public List<AppUser> grantTargets(String username, String documentId) {
		requireOwner(username, documentId, "LIST_GRANT_TARGETS");
		return users.findAllByActiveTrueOrderByUsernameAsc();
	}

	@Transactional(readOnly = true)
	public Role currentRole(String username) {
		return users.findById(username)
				.map(AppUser::getRole)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Account is unavailable."));
	}

	@Transactional(readOnly = true)
	public boolean passwordChangeRequired(String username) {
		return users.findById(username)
				.map(AppUser::isPasswordChangeRequired)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Account is unavailable."));
	}

	@Transactional(readOnly = true)
	public boolean canEdit(String username, String documentId) {
		VaultDocument document = documents.findById(documentId).orElseThrow();
		return canAccessDocument(username, document, true);
	}

	@Transactional(readOnly = true)
	public boolean canDelete(String username, String documentId) {
		VaultDocument document = documents.findById(documentId).orElseThrow();
		return mayDelete(username, document);
	}

	@Transactional
	public boolean hasPermission(String username, String documentId, String action) {
		VaultDocument document = documentId == null || documentId.length() != 36
				? null
				: documents.findById(documentId).orElse(null);
		if (document == null) {
			audit.recordDenied(username, action, documentId, "Document access denied");
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found.");
		}
		boolean allowed = "DELETE".equals(action) ? mayDelete(username, document)
				: canAccessDocument(username, document, true);
		if (!allowed) {
			audit.recordDenied(username, action, documentId, "Document access denied");
		}
		return allowed;
	}

	private boolean mayDelete(String username, VaultDocument document) {
		return document.getOwner().getUsername().equals(username)
				|| grants.findByDocumentIdAndGranteeUsername(document.getId(), username)
						.filter(DocumentGrant::isDeleteAllowed).isPresent();
	}

	private VaultDocument requireDeletePermission(String username, String documentId) {
		VaultDocument document = documentId == null || documentId.length() != 36
				? null
				: documents.findById(documentId).orElse(null);
		if (document == null || !mayDelete(username, document)) {
			audit.recordDenied(username, "DELETE", documentId, "Document access denied");
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found.");
		}
		return document;
	}

	private VaultDocument requirePermission(String username, String documentId, boolean edit, String action) {
		if (documentId == null || documentId.length() != 36) {
			audit.recordDenied(username, action, documentId, "Document access denied");
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found.");
		}
		VaultDocument document = documents.findById(documentId).orElse(null);
		if (document == null || (!canAccessDocument(username, document, edit))) {
			audit.recordDenied(username, action, documentId, "Document access denied");
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found.");
		}
		return document;
	}

	private boolean canAccessDocument(String username, VaultDocument document, boolean edit) {
		if (document.getOwner().getUsername().equals(username)) {
			return true;
		}
		if (edit) {
			return grants.findByDocumentIdAndGranteeUsername(document.getId(), username)
					.filter(grant -> grant.getPermission() == DocumentPermission.EDIT).isPresent();
		}
		return grants.findByDocumentIdAndGranteeUsername(document.getId(), username).isPresent();
	}

	private boolean canView(String username, VaultDocument document) {
		return document.getOwner().getUsername().equals(username)
				|| grants.findByDocumentIdAndGranteeUsername(document.getId(), username).isPresent();
	}

	private VaultDocument requireOwner(String username, String documentId, String action) {
		if (documentId == null || documentId.length() != 36) {
			audit.recordDenied(username, action, documentId, "Owner-only action denied");
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found.");
		}
		VaultDocument document = documents.findById(documentId).orElse(null);
		if (document == null || !document.getOwner().getUsername().equals(username)) {
			audit.recordDenied(username, action, documentId, "Owner-only action denied");
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found.");
		}
		return document;
	}

	private boolean contains(String value, String query) {
		return query == null || query.isBlank()
				|| value.toLowerCase(Locale.ROOT).contains(query.trim().toLowerCase(Locale.ROOT));
	}

	private void validateMetadata(String name, String category, String description) {
		if (name == null || name.isBlank() || name.trim().length() > 180) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name is required and must be at most 180 characters.");
		}
		if (category == null || category.isBlank() || category.trim().length() > 80) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Category is required and must be at most 80 characters.");
		}
		if (description != null && description.length() > 1000) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Description must be at most 1000 characters.");
		}
	}

	private String normalizeDescription(String description) {
		return description == null || description.isBlank() ? null : description.trim();
	}

	public record DownloadedDocument(String name, String contentType, byte[] content) {
	}

	public record ViewerDocument(VaultDocument document, boolean accessible) {
	}

	public record OpenedDocumentResult(boolean accessible, DownloadedDocument document) {
	}

	public static final class DocumentSearchResult {
		private final VaultDocument document;
		private final boolean accessible;

		public DocumentSearchResult(VaultDocument document, boolean accessible) {
			this.document = document;
			this.accessible = accessible;
		}

		public VaultDocument getDocument() {
			return document;
		}

		public boolean isAccessible() {
			return accessible;
		}
	}

	public record DocumentViewResult(VaultDocument document, boolean accessible) {
	}
}
