package com.example.trustvault.web;

import com.example.trustvault.model.VaultDocument;
import com.example.trustvault.service.AuditService;
import com.example.trustvault.service.DocumentService;
import com.example.trustvault.service.FolderService;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/owner")
@PreAuthorize("hasRole('OWNER')")
public class OwnerController {
	private final DocumentService documents;
	private final AuditService audit;
	private final FolderService folders;

	public OwnerController(DocumentService documents, AuditService audit, FolderService folders) {
		this.documents = documents;
		this.audit = audit;
		this.folders = folders;
	}

	@GetMapping("/documents")
	public String documents(Authentication authentication, Model model) {
		String username = authentication.getName();
		model.addAttribute("documents", documents.uploadedByOwner(username));
		model.addAttribute("username", username);
		return "owner-documents";
	}

	@GetMapping("/activity")
	public String activity(Authentication authentication, Model model) {
		String username = authentication.getName();
		List<VaultDocument> ownedDocuments = documents.uploadedByOwner(username);
		Set<String> ownedIds = ownedDocuments.stream().map(VaultDocument::getId).collect(Collectors.toSet());
		folders.foldersOwnedByOwner(username).forEach(folder -> ownedIds.add(folder.getId()));
		model.addAttribute("events", audit.latestEventsForOwner(username, ownedIds));
		model.addAttribute("chainValid", audit.verifyChain());
		model.addAttribute("username", username);
		model.addAttribute("isAdmin", false);
		return "activity";
	}
}
