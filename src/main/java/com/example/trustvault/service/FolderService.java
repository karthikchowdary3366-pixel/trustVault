package com.example.trustvault.service;

import com.example.trustvault.model.AppUser;
import com.example.trustvault.model.Folder;
import com.example.trustvault.model.FolderGrant;
import com.example.trustvault.model.Role;
import com.example.trustvault.model.VaultDocument;
import com.example.trustvault.repository.AppUserRepository;
import com.example.trustvault.repository.FolderGrantRepository;
import com.example.trustvault.repository.FolderRepository;
import com.example.trustvault.repository.VaultDocumentRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FolderService {
	private final FolderRepository folders;
	private final FolderGrantRepository grants;
	private final VaultDocumentRepository documents;
	private final AppUserRepository users;
	private final AuditService audit;
	private final com.example.trustvault.repository.DocumentGrantRepository documentGrants;

	public FolderService(FolderRepository folders, FolderGrantRepository grants, VaultDocumentRepository documents,
			AppUserRepository users, AuditService audit,
			com.example.trustvault.repository.DocumentGrantRepository documentGrants) {
		this.documentGrants = documentGrants;
		this.folders = folders;
		this.grants = grants;
		this.documents = documents;
		this.users = users;
		this.audit = audit;
	}

	@Transactional
	public Folder createFolder(String username, String name, String description) {
		AppUser owner = users.findById(username)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Account is unavailable."));
		if (owner.getRole() != Role.OWNER) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only document owners can create folders.");
		}
		validateFolderName(name, description);

		String folderId = UUID.randomUUID().toString();
		Folder folder = new Folder(folderId, name.trim(), normalizeDescription(description), Instant.now(), owner);
		folders.save(folder);
		audit.recordSuccess(username, "CREATE_FOLDER", folderId, "Folder created");
		return folder;
	}

	@Transactional(readOnly = true)
	public List<Folder> foldersOwnedByOwner(String username) {
		if (currentRole(username) != Role.OWNER) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only document owners can view their folders.");
		}
		return folders.findAllByOwnerUsername(username).stream()
				.sorted((left, right) -> right.getCreatedAt().compareTo(left.getCreatedAt()))
				.toList();
	}

	@Transactional(readOnly = true)
	public List<Folder> grantedFoldersForViewer(String username) {
		if (currentRole(username) != Role.VIEWER) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only viewers can view their granted folders.");
		}
		return grants.findAllByGranteeUsername(username).stream()
				.map(FolderGrant::getFolder)
				.sorted((left, right) -> right.getCreatedAt().compareTo(left.getCreatedAt()))
				.toList();
	}

	@Transactional
	public FolderViewResult viewFolder(String username, String folderId) {
		if (folderId == null || folderId.length() != 36) {
			audit.recordDenied(username, "VIEW_FOLDER", folderId, "Folder access denied");
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Folder not found.");
		}
		Folder folder = folders.findById(folderId).orElse(null);
		if (folder == null) {
			audit.recordDenied(username, "VIEW_FOLDER", folderId, "Folder access denied");
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Folder not found.");
		}
		if (!canViewFolder(username, folder)) {
			audit.recordDenied(username, "VIEW_FOLDER", folderId, "Folder access denied");
			return new FolderViewResult(null, false, List.of());
		}

		List<VaultDocument> docsInFolder = documents.findAllByFolderId(folderId);
		List<DocumentAccessInfo> accessibleDocs = docsInFolder.stream()
				.map(doc -> new DocumentAccessInfo(doc, canAccessDocument(username, doc)))
				.toList();

		audit.recordSuccess(username, "VIEW_FOLDER", folderId, "Folder contents viewed");
		return new FolderViewResult(folder, true, accessibleDocs);
	}

	@Transactional
	public void updateFolder(String username, String folderId, String name, String description) {
		Folder folder = requireFolderOwner(username, folderId, "EDIT_FOLDER");
		validateFolderName(name, description);
		folder.update(name.trim(), normalizeDescription(description));
		audit.recordSuccess(username, "EDIT_FOLDER", folderId, "Folder details updated");
	}

	@Transactional
	public void deleteFolder(String username, String folderId) {
		Folder folder = requireFolderOwner(username, folderId, "DELETE_FOLDER");
		List<VaultDocument> docsInFolder = documents.findAllByFolderId(folderId);
		if (!docsInFolder.isEmpty()) {
			audit.recordDenied(username, "DELETE_FOLDER", folderId, "Folder not empty");
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Cannot delete folder with documents. Remove all documents first.");
		}
		grants.deleteByFolderId(folderId);
		folders.delete(folder);
		audit.recordSuccess(username, "DELETE_FOLDER", folderId, "Folder deleted");
	}

	@Transactional
	public void grantFolderAccess(String username, String folderId, String granteeName) {
		Folder folder = requireFolderOwner(username, folderId, "GRANT_FOLDER");
		AppUser grantee = users.findById(granteeName == null ? "" : granteeName.trim()).orElse(null);
		if (grantee == null || !grantee.isActive() || grantee.getUsername().equals(username)) {
			audit.recordDenied(username, "GRANT_FOLDER", folderId, "Invalid or self access grant target");
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose another existing active account.");
		}

		FolderGrant grant = grants.findByFolderIdAndGranteeUsername(folderId, grantee.getUsername())
				.orElseGet(() -> new FolderGrant(UUID.randomUUID().toString(), folder, grantee, Instant.now()));
		grants.save(grant);
		audit.recordSuccess(username, "GRANT_FOLDER", folderId,
				"Folder access granted to " + grantee.getUsername());
	}

	@Transactional
	public void revokeFolderAccess(String username, String folderId, String granteeName) {
		requireFolderOwner(username, folderId, "REVOKE_FOLDER");
		FolderGrant grant = grants.findByFolderIdAndGranteeUsername(folderId, granteeName)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Grant not found."));
		grants.delete(grant);
		audit.recordSuccess(username, "REVOKE_FOLDER", folderId, "Folder access revoked from " + granteeName);
	}

	@Transactional(readOnly = true)
	public List<FolderGrant> listFolderGrants(String username, String folderId) {
		requireFolderOwner(username, folderId, "LIST_FOLDER_GRANTS");
		return grants.findAllByFolderIdOrderByGranteeUsernameAsc(folderId);
	}

	@Transactional(readOnly = true)
	public List<AppUser> folderGrantTargets(String username, String folderId) {
		requireFolderOwner(username, folderId, "LIST_FOLDER_TARGETS");
		return users.findAllByActiveTrueOrderByUsernameAsc();
	}

	@Transactional(readOnly = true)
	public boolean canViewFolder(String username, Folder folder) {
		return folder.getOwner().getUsername().equals(username)
				|| grants.findByFolderIdAndGranteeUsername(folder.getId(), username).isPresent();
	}

	@Transactional(readOnly = true)
	public boolean canAccessDocument(String username, VaultDocument document) {
		return document.getOwner().getUsername().equals(username)
				|| documentGrants.findByDocumentIdAndGranteeUsername(document.getId(), username).isPresent();
	}

	private Folder requireFolderOwner(String username, String folderId, String action) {
		if (folderId == null || folderId.length() != 36) {
			audit.recordDenied(username, action, folderId, "Owner-only action denied");
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Folder not found.");
		}
		Folder folder = folders.findById(folderId).orElse(null);
		if (folder == null || !folder.getOwner().getUsername().equals(username)) {
			audit.recordDenied(username, action, folderId, "Owner-only action denied");
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Folder not found.");
		}
		return folder;
	}

	@Transactional(readOnly = true)
	public Role currentRole(String username) {
		return users.findById(username)
				.map(AppUser::getRole)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Account is unavailable."));
	}

	private void validateFolderName(String name, String description) {
		if (name == null || name.isBlank() || name.trim().length() > 180) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Folder name is required and must be at most 180 characters.");
		}
		if (description != null && description.length() > 1000) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Description must be at most 1000 characters.");
		}
	}

	private String normalizeDescription(String description) {
		return description == null || description.isBlank() ? null : description.trim();
	}

	public record DocumentAccessInfo(VaultDocument document, boolean accessible) {
	}

	public record FolderViewResult(Folder folder, boolean accessible, List<DocumentAccessInfo> documents) {
	}
}
