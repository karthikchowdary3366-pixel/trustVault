package com.example.trustvault.web;

import com.example.trustvault.model.AppUser;
import com.example.trustvault.model.Folder;
import com.example.trustvault.service.DocumentService;
import com.example.trustvault.service.FolderService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import jakarta.servlet.http.HttpServletResponse;

@Controller
@RequestMapping("/folders")
public class FolderController {
	private final FolderService folders;
	private final DocumentService documents;

	public FolderController(FolderService folders, DocumentService documents) {
		this.folders = folders;
		this.documents = documents;
	}

	@GetMapping("/create")
	@PreAuthorize("hasRole('OWNER')")
	public String createPage(Authentication authentication, Model model) {
		model.addAttribute("username", authentication.getName());
		return "folder-create";
	}

	@PostMapping("/create")
	@PreAuthorize("hasRole('OWNER')")
	public String create(Authentication authentication, @RequestParam String name,
			@RequestParam(required = false) String description, RedirectAttributes redirect) {
		Folder folder = folders.createFolder(authentication.getName(), name, description);
		redirect.addFlashAttribute("notice", "Folder created.");
		return "redirect:/folders/" + folder.getId();
	}

	@GetMapping
	public String listFolders(Authentication authentication, Model model) {
		String username = authentication.getName();
		model.addAttribute("username", username);
		String role = documents.currentRole(username).name();
		model.addAttribute("role", role);

		if ("OWNER".equals(role)) {
			model.addAttribute("folders", folders.foldersOwnedByOwner(username));
		} else if ("VIEWER".equals(role)) {
			model.addAttribute("folders", folders.grantedFoldersForViewer(username));
		}
		return "folders-list";
	}

	@GetMapping("/{id}")
	public String viewFolder(@PathVariable String id, Authentication authentication, Model model,
			HttpServletResponse response) {
		FolderService.FolderViewResult result = folders.viewFolder(authentication.getName(), id);
		if (!result.accessible()) {
			response.setStatus(HttpStatus.FORBIDDEN.value());
			model.addAttribute("username", authentication.getName());
			model.addAttribute("role", documents.currentRole(authentication.getName()).name());
			return "folder-access-denied";
		}

		Folder folder = result.folder();
		boolean isOwner = folder.getOwner().getUsername().equals(authentication.getName());
		model.addAttribute("folder", folder);
		model.addAttribute("documents", result.documents());
		model.addAttribute("username", authentication.getName());
		model.addAttribute("role", documents.currentRole(authentication.getName()).name());
		model.addAttribute("isOwner", isOwner);
		model.addAttribute("grants", isOwner ? folders.listFolderGrants(authentication.getName(), id) : List.of());
		model.addAttribute("grantTargets",
				isOwner ? folders.folderGrantTargets(authentication.getName(), id).stream()
						.filter(user -> !user.getUsername().equals(authentication.getName())).toList()
						: List.<AppUser>of());
		return "folder";
	}

	@PostMapping("/{id}/edit")
	@PreAuthorize("hasRole('OWNER')")
	public String edit(@PathVariable String id, Authentication authentication, @RequestParam String name,
			@RequestParam(required = false) String description, RedirectAttributes redirect) {
		folders.updateFolder(authentication.getName(), id, name, description);
		redirect.addFlashAttribute("notice", "Folder details updated.");
		return "redirect:/folders/" + id;
	}

	@PostMapping("/{id}/delete")
	@PreAuthorize("hasRole('OWNER')")
	public String delete(@PathVariable String id, Authentication authentication, RedirectAttributes redirect) {
		documents.deleteFolderDocuments(authentication.getName(), id);
		folders.deleteFolder(authentication.getName(), id);
		redirect.addFlashAttribute("notice", "Folder deleted.");
		return "redirect:/folders";
	}

	@PostMapping("/{id}/documents/delete-all")
	@PreAuthorize("hasRole('OWNER')")
	public String deleteAllDocuments(@PathVariable String id, Authentication authentication,
			RedirectAttributes redirect) {
		documents.deleteFolderDocuments(authentication.getName(), id);
		redirect.addFlashAttribute("notice", "All documents in the folder were deleted.");
		return "redirect:/folders/" + id;
	}

	@PostMapping("/{id}/grants")
	@PreAuthorize("hasRole('OWNER')")
	public String grantAccess(@PathVariable String id, Authentication authentication, @RequestParam String username,
			RedirectAttributes redirect) {
		folders.grantFolderAccess(authentication.getName(), id, username);
		redirect.addFlashAttribute("notice", "Folder access granted.");
		return "redirect:/folders/" + id;
	}

	@PostMapping("/{id}/grants/revoke")
	@PreAuthorize("hasRole('OWNER')")
	public String revokeAccess(@PathVariable String id, Authentication authentication, @RequestParam String username,
			RedirectAttributes redirect) {
		folders.revokeFolderAccess(authentication.getName(), id, username);
		redirect.addFlashAttribute("notice", "Folder access revoked immediately.");
		return "redirect:/folders/" + id;
	}
}
