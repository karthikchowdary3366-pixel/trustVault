package com.example.trustvault.web;

import com.example.trustvault.model.AppUser;
import com.example.trustvault.model.Role;
import com.example.trustvault.model.VaultDocument;
import com.example.trustvault.service.DocumentService;
import com.example.trustvault.service.FolderService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class DocumentController {
	private final DocumentService documents;
	private final FolderService folders;

	public DocumentController(DocumentService documents, FolderService folders) {
		this.documents = documents;
		this.folders = folders;
	}

	@GetMapping("/login")
	public String login() {
		return "login";
	}

	@GetMapping("/documents")
	public String index(Authentication authentication, Model model, jakarta.servlet.http.HttpSession session,
			@RequestParam(required = false) String clear) {
		model.addAttribute("username", authentication.getName());
		Role currentRole = documents.currentRole(authentication.getName());
		model.addAttribute("role", currentRole.name());
		model.addAttribute("passwordChangeRequired", documents.passwordChangeRequired(authentication.getName()));
		if (currentRole == Role.OWNER) {
			model.addAttribute("uploads", documents.uploadedByOwner(authentication.getName()));
		}
		if (clear != null) {
			session.removeAttribute(LAST_SEARCH);
		} else if (session.getAttribute(LAST_SEARCH) instanceof SearchFilters f) {
			model.addAttribute("documents",
					documents.searchAll(authentication.getName(), f.name(), f.owner(), f.category(), f.from(), f.to()));
			model.addAttribute("name", f.name());
			model.addAttribute("owner", f.owner());
			model.addAttribute("category", f.category());
			model.addAttribute("from", f.from());
			model.addAttribute("to", f.to());
		}
		return "documents";
	}

	private static final String LAST_SEARCH = "lastDocumentSearch";

	private record SearchFilters(String name, String owner, String category, LocalDate from, LocalDate to)
			implements java.io.Serializable {
	}

	@GetMapping("/documents/upload")
	@PreAuthorize("hasRole('OWNER')")
	public String uploadPage(Authentication authentication, Model model,
			@RequestParam(required = false) String folderId) {
		String username = authentication.getName();
		model.addAttribute("username", username);
		folders.foldersOwnedByOwner(username).stream()
				.filter(folder -> folder.getId().equals(folderId))
				.findFirst()
				.ifPresent(folder -> model.addAttribute("targetFolder", folder));
		model.addAttribute("grantTargets", documents.uploadGrantTargets(username));
		return "document-upload";
	}

	@GetMapping("/viewer/folders")
	@PreAuthorize("hasRole('VIEWER')")
	public String viewerFolders(Authentication authentication, Model model) {
		String username = authentication.getName();
		model.addAttribute("folders", folders.grantedFoldersForViewer(username));
		model.addAttribute("username", username);
		return "viewer-folders";
	}

	@GetMapping("/viewer/documents")
	@PreAuthorize("hasRole('VIEWER')")
	public String viewerDocuments(Authentication authentication, Model model,
			@RequestParam(required = false) String folder) {
		String username = authentication.getName();
		var all = documents.documentsForViewer(username);
		var folderNames = all.stream().map(d -> d.document().getFolder())
				.filter(java.util.Objects::nonNull).map(f -> f.getName())
				.distinct().sorted().toList();
		var filtered = (folder == null || folder.isBlank()) ? all : all.stream()
				.filter(d -> d.document().getFolder() != null
						&& d.document().getFolder().getName().equals(folder))
				.toList();
		model.addAttribute("documents", filtered);
		model.addAttribute("folderNames", folderNames);
		model.addAttribute("selectedFolder", folder);
		model.addAttribute("username", username);
		return "viewer-documents";
	}

	@PostMapping("/documents/search")
	public String search(Authentication authentication,
			@RequestParam(required = false) String name,
			@RequestParam(required = false) String owner,
			@RequestParam(required = false) String category,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			jakarta.servlet.http.HttpSession session) {
		session.setAttribute(LAST_SEARCH, new SearchFilters(name, owner, category, from, from));
		return "redirect:/documents";
	}

	@PostMapping("/documents/upload")
	@PreAuthorize("hasRole('OWNER')")
	public String upload(Authentication authentication, @RequestParam String name, @RequestParam String category,
			@RequestParam(required = false) String description, @RequestParam("file") MultipartFile file,
			@RequestParam(required = false) String folderId,
			@RequestParam(name = "grantees", required = false) java.util.List<String> grantees,
			@RequestParam(name = "permission", defaultValue = "READ") String permission,
			RedirectAttributes redirect) {
		com.example.trustvault.model.DocumentPermission level;
		try {
			level = com.example.trustvault.model.DocumentPermission.valueOf(permission);
		} catch (IllegalArgumentException exception) {
			throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Choose read or edit access.");
		}
		VaultDocument document = documents.upload(authentication.getName(), name, category, description, file,
				folderId, grantees, level);
		redirect.addFlashAttribute("notice", "Document uploaded.");
		return "redirect:/documents/" + document.getId();
	}

	@GetMapping("/documents/{id}")
	public String detail(@PathVariable String id, Authentication authentication, Model model,
			HttpServletResponse response,
			@org.springframework.web.bind.annotation.RequestHeader(value = "Referer", required = false) String referer,
			jakarta.servlet.http.HttpServletRequest request) {
		DocumentService.DocumentViewResult result = documents.view(authentication.getName(), id);
		if (!result.accessible()) {
			response.setStatus(HttpStatus.FORBIDDEN.value());
			model.addAttribute("username", authentication.getName());
			model.addAttribute("backUrl", backUrl(referer, request));
			model.addAttribute("role", documents.currentRole(authentication.getName()).name());
			return "document-access-denied";
		}
		VaultDocument document = result.document();
		boolean isOwner = document.getOwner().getUsername().equals(authentication.getName());
		model.addAttribute("document", document);
		model.addAttribute("username", authentication.getName());
		model.addAttribute("role", documents.currentRole(authentication.getName()).name());
		model.addAttribute("isOwner", isOwner);
		String back = backUrl(referer, request);
		if (back == null || back.startsWith("/documents/" + id) || back.contains("/edit")) {
			back = isOwner ? "/owner/documents"
					: documents.currentRole(authentication.getName()) == Role.VIEWER ? "/viewer/documents" : "/documents";
		}
		model.addAttribute("backLink", back);
		model.addAttribute("canEdit", documents.canEdit(authentication.getName(), id));
		model.addAttribute("canDelete", documents.canDelete(authentication.getName(), id));
		model.addAttribute("grants", isOwner ? documents.listGrants(authentication.getName(), id) : java.util.List.of());
		model.addAttribute("grantTargets", isOwner
				? documents.grantTargets(authentication.getName(), id).stream()
						.filter(user -> !user.getUsername().equals(authentication.getName())).toList()
				: java.util.List.<AppUser>of());
		return "document";
	}

	private static String backUrl(String referer, jakarta.servlet.http.HttpServletRequest request) {
		if (referer == null || referer.isBlank()) {
			return null;
		}
		try {
			java.net.URI uri = java.net.URI.create(referer);
			if (uri.getHost() == null || !uri.getHost().equalsIgnoreCase(request.getServerName())
					|| uri.getPath() == null || !uri.getPath().startsWith("/")) {
				return null;
			}
			return uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}

	private static String fileNameWithExtension(String name, String contentType) {
		String extension = switch (contentType == null ? "" : contentType.toLowerCase(java.util.Locale.ROOT)) {
			case "application/pdf" -> ".pdf";
			case "image/png" -> ".png";
			case "image/jpeg" -> ".jpg";
			case "text/plain" -> ".txt";
			case "application/msword" -> ".doc";
			case "application/vnd.ms-excel" -> ".xls";
			case com.example.trustvault.service.PrivateFileStorage.DOCX -> ".docx";
			case com.example.trustvault.service.PrivateFileStorage.XLSX -> ".xlsx";
			default -> "";
		};
		String safeName = name.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
		if (extension.isEmpty() || safeName.toLowerCase(java.util.Locale.ROOT).endsWith(extension)
				|| (".jpg".equals(extension) && safeName.toLowerCase(java.util.Locale.ROOT).endsWith(".jpeg"))) {
			return safeName;
		}
		return safeName + extension;
	}

	@GetMapping("/documents/{id}/download")
	public String download(@PathVariable String id, Authentication authentication, Model model,
			HttpServletResponse response,
			@org.springframework.web.bind.annotation.RequestHeader(value = "Referer", required = false) String referer,
			jakarta.servlet.http.HttpServletRequest request) throws IOException {
		DocumentService.OpenedDocumentResult result = documents.download(authentication.getName(), id);
		if (!result.accessible()) {
			response.setStatus(HttpStatus.FORBIDDEN.value());
			model.addAttribute("username", authentication.getName());
			model.addAttribute("role", documents.currentRole(authentication.getName()).name());
			model.addAttribute("backUrl", backUrl(referer, request));
			return "document-access-denied";
		}
		DocumentService.DownloadedDocument download = result.document();
		response.setContentType(download.contentType());
		response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
				.filename(fileNameWithExtension(download.name(), download.contentType()), StandardCharsets.UTF_8)
				.build().toString());
		response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
		response.setHeader("X-Content-Type-Options", "nosniff");
		response.setContentLength(download.content().length);
		response.getOutputStream().write(download.content());
		response.flushBuffer();
		return null;
	}

	@GetMapping("/documents/{id}/open")
	public String open(@PathVariable String id, Authentication authentication, Model model,
			HttpServletResponse response,
			@org.springframework.web.bind.annotation.RequestHeader(value = "Referer", required = false) String referer,
			jakarta.servlet.http.HttpServletRequest request) throws IOException {
		DocumentService.OpenedDocumentResult result = documents.open(authentication.getName(), id);
		if (!result.accessible()) {
			response.setStatus(HttpStatus.FORBIDDEN.value());
			model.addAttribute("username", authentication.getName());
			model.addAttribute("backUrl", backUrl(referer, request));
			return "document-access-denied";
		}
		DocumentService.DownloadedDocument document = result.document();
		response.setContentType(document.contentType());
		response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
				.filename(fileNameWithExtension(document.name(), document.contentType()), StandardCharsets.UTF_8)
				.build().toString());
		response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
		response.setHeader("X-Content-Type-Options", "nosniff");
		response.setContentLength(document.content().length);
		response.getOutputStream().write(document.content());
		response.flushBuffer();
		return null;
	}

	@PostMapping("/documents/{id}/upload-edit")
	public String uploadEdited(@PathVariable String id, Authentication authentication,
			@RequestParam("file") org.springframework.web.multipart.MultipartFile file, RedirectAttributes redirect,
			Model model, HttpServletResponse response,
			@org.springframework.web.bind.annotation.RequestHeader(value = "Referer", required = false) String referer,
			jakarta.servlet.http.HttpServletRequest request) {
		if (!documents.hasPermission(authentication.getName(), id, "EDIT")) {
			return accessDenied(authentication, model, response, referer, request);
		}
		documents.replaceContent(authentication.getName(), id, file);
		redirect.addFlashAttribute("notice", "Edited file uploaded. The changes are now visible to everyone with access.");
		return "redirect:/documents/" + id;
	}

	@PostMapping("/documents/{id}/edit")
	public String edit(@PathVariable String id, Authentication authentication,
			@RequestParam String name, @RequestParam String category,
			@RequestParam(required = false) String description,
			@RequestParam(required = false) String content, RedirectAttributes redirect) {
		documents.update(authentication.getName(), id, name, category, description, content);
		redirect.addFlashAttribute("notice", "Document details updated.");
		return "redirect:/documents/" + id;
	}

	@GetMapping("/documents/{id}/edit")
	public String editPage(@PathVariable String id, Authentication authentication, Model model,
			HttpServletResponse response,
			@org.springframework.web.bind.annotation.RequestHeader(value = "Referer", required = false) String referer,
			jakarta.servlet.http.HttpServletRequest request) {
		if (!documents.hasPermission(authentication.getName(), id, "EDIT")) {
			return accessDenied(authentication, model, response, referer, request);
		}
		return renderEditor(id, authentication, model);
	}

	private String renderEditor(String id, Authentication authentication, Model model) {
		VaultDocument document = documents.editableDocument(authentication.getName(), id);
		model.addAttribute("document", document);
		model.addAttribute("text", documents.editableText(authentication.getName(), id));
		model.addAttribute("username", authentication.getName());
		model.addAttribute("role", documents.currentRole(authentication.getName()).name());
		return "document-edit";
	}

	private String accessDenied(Authentication authentication, Model model, HttpServletResponse response,
			String referer, jakarta.servlet.http.HttpServletRequest request) {
		response.setStatus(HttpStatus.FORBIDDEN.value());
		model.addAttribute("username", authentication.getName());
		model.addAttribute("role", documents.currentRole(authentication.getName()).name());
		model.addAttribute("backUrl", backUrl(referer, request));
		return "document-access-denied";
	}

	@PostMapping("/documents/{id}/delete")
	public String delete(@PathVariable String id, Authentication authentication, RedirectAttributes redirect,
			Model model, HttpServletResponse response,
			@org.springframework.web.bind.annotation.RequestHeader(value = "Referer", required = false) String referer,
			jakarta.servlet.http.HttpServletRequest request) {
		if (!documents.hasPermission(authentication.getName(), id, "DELETE")) {
			return accessDenied(authentication, model, response, referer, request);
		}
		documents.delete(authentication.getName(), id);
		redirect.addFlashAttribute("notice", "Document deleted.");
		return documents.currentRole(authentication.getName()) == Role.VIEWER
				? "redirect:/viewer/documents" : "redirect:/documents";
	}

	@PostMapping("/documents/{id}/grants")
	public String grant(@PathVariable String id, Authentication authentication,
			@RequestParam String username, @RequestParam String permission, RedirectAttributes redirect) {
		com.example.trustvault.model.DocumentPermission level;
		try {
			level = com.example.trustvault.model.DocumentPermission.valueOf(permission);
		} catch (IllegalArgumentException exception) {
			level = null;
		}
		documents.grant(authentication.getName(), id, username, level);
		redirect.addFlashAttribute("notice", "Access granted.");
		return "redirect:/documents/" + id;
	}

	@PostMapping("/documents/{id}/grants/bulk")
	public String grantMultiple(@PathVariable String id, Authentication authentication,
			@RequestParam(name = "grantees", required = false) java.util.List<String> grantees,
			@RequestParam String permission, RedirectAttributes redirect) {
		com.example.trustvault.model.DocumentPermission level;
		try {
			level = com.example.trustvault.model.DocumentPermission.valueOf(permission);
		} catch (IllegalArgumentException exception) {
			throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Choose read or edit access.");
		}
		documents.grantMultiple(authentication.getName(), id, grantees, level);
		redirect.addFlashAttribute("notice", "Access granted to selected people.");
		return "redirect:/documents/" + id;
	}

	@PostMapping("/documents/{id}/grants/revoke")
	public String revoke(@PathVariable String id, Authentication authentication,
			@RequestParam String username, RedirectAttributes redirect) {
		documents.revoke(authentication.getName(), id, username);
		redirect.addFlashAttribute("notice", "Access revoked immediately.");
		return "redirect:/documents/" + id;
	}
}
