package com.example.trustvault;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.trustvault.model.AuditEvent;
import com.example.trustvault.model.AppUser;
import com.example.trustvault.model.Role;
import com.example.trustvault.model.VaultDocument;
import com.example.trustvault.repository.AppUserRepository;
import com.example.trustvault.repository.AuditEventRepository;
import com.example.trustvault.repository.VaultDocumentRepository;
import com.example.trustvault.service.AuditService;
import com.example.trustvault.service.DocumentService;
import com.example.trustvault.service.FolderService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentAuthorizationTests {
	private static final byte[] PDF = "%PDF-1.7\nconfidential content\n%%EOF".getBytes(StandardCharsets.UTF_8);

	@Autowired
	private MockMvc mvc;

	@Autowired
	private DocumentService documents;

	@Autowired
	private FolderService folders;

	@Autowired
	private VaultDocumentRepository documentRepository;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private AuditEventRepository auditEvents;

	@Autowired
	private AuditService auditService;

	@Autowired
	private JdbcTemplate jdbc;

	private VaultDocument document;

	@BeforeEach
	void createPrivateDocument() {
		document = documents.upload("owner", "Private salary letter", "HR", "Owner-only test document",
				new MockMultipartFile("file", "letter.pdf", MediaType.APPLICATION_PDF_VALUE, PDF));
	}

	@AfterEach
	void removePrivateDocument() {
		if (documentRepository.existsById(document.getId())) {
			documents.delete("owner", document.getId());
		}
	}

	@Test
	void viewerCannotViewOrDownloadUnsharedDocumentAndAttemptIsAudited() throws Exception {
		mvc.perform(get("/documents/{id}", document.getId())
				.with(SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER")))
				.andExpect(status().isForbidden())
				.andExpect(content().string(containsString("Access is not granted for you")))
				.andExpect(content().string(not(containsString("Owner-only test document"))));

		mvc.perform(get("/documents/{id}/download", document.getId())
				.with(SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER")))
				.andExpect(status().isForbidden())
				.andExpect(content().string(not(containsString("confidential content"))));

		mvc.perform(get("/documents/{id}/open", document.getId())
				.with(SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER")))
				.andExpect(status().isForbidden())
				.andExpect(content().string(containsString("Access is not granted for you")))
				.andExpect(content().string(not(containsString("confidential content"))));

		List<AuditEvent> deniedEvents = auditEvents.findAllByOrderByIdDesc().stream()
				.filter(event -> "viewer".equals(event.getActor()) && !event.isAllowed()
						&& document.getId().equals(event.getDocumentId()))
				.toList();
		org.junit.jupiter.api.Assertions.assertEquals(3, deniedEvents.size());
		org.junit.jupiter.api.Assertions.assertTrue(deniedEvents.stream()
				.allMatch(event -> document.getId().equals(event.getDocumentId())));
	}

	@Test
	void viewerCannotEditDeleteOrGrantOnUnsharedDocument() throws Exception {
		mvc.perform(post("/documents/{id}/edit", document.getId())
				.with(SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER"))
				.with(SecurityMockMvcRequestPostProcessors.csrf())
				.param("name", "Stolen").param("category", "HR"))
				.andExpect(status().isNotFound());

		mvc.perform(post("/documents/{id}/grants", document.getId())
				.with(SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER"))
				.with(SecurityMockMvcRequestPostProcessors.csrf())
				.param("username", "admin").param("permission", "EDIT"))
				.andExpect(status().isNotFound());

		mvc.perform(post("/documents/{id}/delete", document.getId())
				.with(SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER"))
				.with(SecurityMockMvcRequestPostProcessors.csrf()))
				.andExpect(status().isForbidden());

		org.junit.jupiter.api.Assertions.assertTrue(documentRepository.existsById(document.getId()));
	}

	@Test
	void viewerCanFindUnsharedDocumentButCannotOpenIt() throws Exception {
		mvc.perform(get("/documents")
				.with(SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER")))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Search documents")))
				.andExpect(content().string(not(containsString("Search results"))))
				.andExpect(content().string(not(containsString("Private salary letter"))));

		MvcResult searchResult = mvc.perform(post("/documents/search")
				.with(SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER"))
				.with(SecurityMockMvcRequestPostProcessors.csrf())
				.param("name", "Private salary"))
				.andExpect(status().is3xxRedirection())
				.andReturn();

		MvcResult resultsPage = mvc.perform(get("/documents")
				.session((MockHttpSession) searchResult.getRequest().getSession(false)))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Search results")))
				.andExpect(content().string(containsString("Private salary letter")))
				.andExpect(content().string(not(containsString("Access not granted"))))
				.andExpect(content().string(not(containsString("Owner-only test document"))))
				.andReturn();

		mvc.perform(get("/documents").session((MockHttpSession) resultsPage.getRequest().getSession(false)))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Search results")))
				.andExpect(content().string(containsString("Private salary letter")));

		mvc.perform(get("/documents/{id}", document.getId())
				.with(SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER")))
				.andExpect(status().isForbidden())
				.andExpect(content().string(containsString("Access is not granted for you")));
	}

	@Test
	void viewerMyDocumentsShowsOnlyGrantedDocumentsWithOpenAction() throws Exception {
		documents.grant("owner", document.getId(), "viewer", com.example.trustvault.model.DocumentPermission.READ);
		VaultDocument unsharedDocument = documents.upload("owner", "Not shared with viewer", "Finance", null,
				new MockMultipartFile("file", "unshared.txt", MediaType.TEXT_PLAIN_VALUE,
						"not shared".getBytes(StandardCharsets.UTF_8)));
		try {
			mvc.perform(get("/viewer/documents")
					.with(SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER")))
					.andExpect(status().isOk())
					.andExpect(content().string(containsString("Private salary letter")))
					.andExpect(content().string(not(containsString("Not shared with viewer"))))
					.andExpect(content().string(containsString("/documents/" + document.getId() + "/open")))
					.andExpect(content().string(containsString("target=\"_blank\"")))
					.andExpect(content().string(containsString("/documents/" + document.getId() + "/download")));

			mvc.perform(get("/viewer/documents")
					.with(SecurityMockMvcRequestPostProcessors.user("owner").roles("OWNER")))
					.andExpect(status().isForbidden());
		} finally {
			documents.delete("owner", unsharedDocument.getId());
		}
	}

	@Test
	void allRolesCanSearchDocumentsAndUploadEndpointIsOwnerOnly() throws Exception {
		mvc.perform(get("/documents")
				.with(SecurityMockMvcRequestPostProcessors.user("owner").roles("OWNER")))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Search documents")))
				.andExpect(content().string(containsString("name=\"name\"")))
				.andExpect(content().string(containsString("href=\"/documents/upload\"")))
				.andExpect(content().string(containsString("href=\"/folders/create\"")))
				.andExpect(content().string(containsString("Create a folder")))
				.andExpect(content().string(not(containsString("Upload privately"))));
		mvc.perform(get("/documents")
				.with(SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN")))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Search documents")))
				.andExpect(content().string(containsString("name=\"name\"")));

		mvc.perform(get("/documents/{id}/open", document.getId())
				.with(SecurityMockMvcRequestPostProcessors.user("owner").roles("OWNER")))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Disposition", containsString("inline")))
				.andExpect(content().bytes(PDF));

		mvc.perform(get("/documents/upload")
				.with(SecurityMockMvcRequestPostProcessors.user("owner").roles("OWNER")))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Document name")))
				.andExpect(content().string(containsString("name=\"category\"")))
				.andExpect(content().string(containsString("name=\"description\"")))
				.andExpect(content().string(containsString("name=\"file\"")))
				.andExpect(content().string(containsString("id=\"select-all-grantees\"")))
				.andExpect(content().string(containsString("Select all matching people")))
				.andExpect(content().string(containsString("id=\"grantee-search\"")))
				.andExpect(content().string(containsString("class=\"grantee-list\"")))
				.andExpect(content().string(containsString("action=\"/documents/upload\"")));

		mvc.perform(get("/documents/upload")
				.with(SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER")))
				.andExpect(status().isForbidden());

		MvcResult uploadResult = mvc.perform(multipart("/documents/upload")
				.file(new MockMultipartFile("file", "route-test.txt", MediaType.TEXT_PLAIN_VALUE,
						"upload endpoint test".getBytes(StandardCharsets.UTF_8)))
				.param("name", "Upload endpoint test")
				.param("category", "HR")
				.param("description", "Uploaded through the dedicated endpoint")
				.with(SecurityMockMvcRequestPostProcessors.user("owner").roles("OWNER"))
				.with(SecurityMockMvcRequestPostProcessors.csrf()))
		.andExpect(status().is3xxRedirection())
				.andReturn();
		String uploadedDocumentId = uploadResult.getResponse().getRedirectedUrl()
				.substring("/documents/".length());
		documents.delete("owner", uploadedDocumentId);
	}

	@Test
	void ownerCanUploadIntoFolderAndGrantAccessInOneSubmission() throws Exception {
		var folder = folders.createFolder("owner", "Upload destination", null);
		String uploadedDocumentId = null;
		try {
			mvc.perform(get("/documents/upload")
					.with(SecurityMockMvcRequestPostProcessors.user("owner").roles("OWNER")))
					.andExpect(status().isOk())
					.andExpect(content().string(not(containsString("name=\"folderId\""))));

			mvc.perform(get("/documents/upload").param("folderId", folder.getId())
					.with(SecurityMockMvcRequestPostProcessors.user("owner").roles("OWNER")))
					.andExpect(status().isOk())
					.andExpect(content().string(containsString("Upload destination")))
					.andExpect(content().string(containsString("name=\"folderId\"")))
				.andExpect(content().string(containsString("name=\"grantees\"")))
				.andExpect(content().string(containsString("name=\"permission\"")))
				.andExpect(content().string(containsString("id=\"grantee-search\"")))
				.andExpect(content().string(containsString("class=\"grantee-list\"")))
				.andExpect(content().string(not(containsString("Admins</legend>"))));

			MvcResult uploadResult = mvc.perform(multipart("/documents/upload")
					.file(new MockMultipartFile("file", "shared.pdf", MediaType.APPLICATION_PDF_VALUE, PDF))
					.param("name", "Shared folder document")
					.param("category", "Contracts")
					.param("folderId", folder.getId())
					.param("grantees", "viewer")
					.param("permission", "EDIT")
					.with(SecurityMockMvcRequestPostProcessors.user("owner").roles("OWNER"))
					.with(SecurityMockMvcRequestPostProcessors.csrf()))
					.andExpect(status().is3xxRedirection())
					.andReturn();
			uploadedDocumentId = uploadResult.getResponse().getRedirectedUrl().substring("/documents/".length());

			VaultDocument uploaded = documentRepository.findById(uploadedDocumentId).orElseThrow();
			org.junit.jupiter.api.Assertions.assertEquals(folder.getId(), uploaded.getFolder().getId());
			var grants = documents.listGrants("owner", uploadedDocumentId);
			org.junit.jupiter.api.Assertions.assertEquals(1, grants.size());
			org.junit.jupiter.api.Assertions.assertEquals("viewer", grants.get(0).getGrantee().getUsername());
			org.junit.jupiter.api.Assertions.assertEquals(com.example.trustvault.model.DocumentPermission.EDIT,
					grants.get(0).getPermission());

			mvc.perform(get("/documents/{id}", uploadedDocumentId)
					.with(SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER")))
					.andExpect(status().isOk())
					.andExpect(content().string(containsString("Edit document details")));
		} finally {
			if (uploadedDocumentId != null && documentRepository.existsById(uploadedDocumentId)) {
				documents.delete("owner", uploadedDocumentId);
			}
			folders.deleteFolder("owner", folder.getId());
		}
	}

	@Test
	void ownerCanSearchAndGrantAccessToMultiplePeopleFromDocumentEditView() throws Exception {
		mvc.perform(get("/documents/{id}", document.getId())
				.with(SecurityMockMvcRequestPostProcessors.user("owner").roles("OWNER")))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("id=\"grantee-search\"")))
				.andExpect(content().string(containsString("Select all matching people")))
				.andExpect(content().string(containsString("name=\"grantees\"")))
				.andExpect(content().string(containsString("/documents/" + document.getId() + "/grants/bulk")));

		mvc.perform(post("/documents/{id}/grants/bulk", document.getId())
				.param("grantees", "viewer", "admin")
				.param("permission", "READ")
				.with(SecurityMockMvcRequestPostProcessors.user("owner").roles("OWNER"))
				.with(SecurityMockMvcRequestPostProcessors.csrf()))
				.andExpect(status().is3xxRedirection());

		var grants = documents.listGrants("owner", document.getId());
		org.junit.jupiter.api.Assertions.assertEquals(2, grants.size());
		org.junit.jupiter.api.Assertions.assertTrue(grants.stream().allMatch(grant ->
				grant.getPermission() == com.example.trustvault.model.DocumentPermission.READ));
	}

	@Test
	void ownerCanCreateFolderAndOpenIt() throws Exception {
		var result = mvc.perform(post("/folders/create")
				.param("name", "Repro folder")
				.with(SecurityMockMvcRequestPostProcessors.user("owner").roles("OWNER"))
				.with(SecurityMockMvcRequestPostProcessors.csrf()))
				.andExpect(status().is3xxRedirection()).andReturn();
		String location = result.getResponse().getRedirectedUrl();
		mvc.perform(get(location)
				.with(SecurityMockMvcRequestPostProcessors.user("owner").roles("OWNER")))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Repro folder")));
	}

	@Test
	void ownerCanSeeOnlyTheirUploadsAndRelatedAuditActivity() throws Exception {
		AppUser otherOwner = users.saveAndFlush(new AppUser("other-owner", "unused-hash", "Other Owner",
				"other-owner@example.test", Role.OWNER));
		VaultDocument otherDocument = documents.upload(otherOwner.getUsername(), "Other private file", "Finance",
				null, new MockMultipartFile("file", "other.txt", MediaType.TEXT_PLAIN_VALUE,
						"other owner content".getBytes(StandardCharsets.UTF_8)));
		try {
			mvc.perform(get("/owner/documents")
					.with(SecurityMockMvcRequestPostProcessors.user("owner").roles("OWNER")))
					.andExpect(status().isOk())
					.andExpect(content().string(containsString("Private salary letter")))
					.andExpect(content().string(not(containsString("Other private file"))));

			mvc.perform(get("/owner/activity")
					.with(SecurityMockMvcRequestPostProcessors.user("owner").roles("OWNER")))
					.andExpect(status().isOk())
					.andExpect(content().string(containsString(document.getId())))
					.andExpect(content().string(not(containsString(otherDocument.getId()))));

			mvc.perform(get("/owner/documents")
					.with(SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER")))
					.andExpect(status().isForbidden());
			mvc.perform(get("/owner/activity")
					.with(SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN")))
					.andExpect(status().isForbidden());
		} finally {
			documents.delete(otherOwner.getUsername(), otherDocument.getId());
			users.delete(otherOwner);
		}
	}

	@Test
	void administratorDoesNotAutomaticallyGainDocumentReadAccess() throws Exception {
		mvc.perform(get("/documents")
				.with(SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN")))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Search documents")));

		mvc.perform(get("/documents/{id}/download", document.getId())
				.with(SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN")))
				.andExpect(status().isForbidden())
				.andExpect(content().string(not(containsString("confidential content"))));
	}

	@Test
	void unauthenticatedDocumentAttemptIsDeniedAndAudited() throws Exception {
		mvc.perform(get("/documents/{id}/download", document.getId()))
				.andExpect(status().isNotFound());

		org.junit.jupiter.api.Assertions.assertTrue(auditEvents.findAllByOrderByIdDesc().stream()
				.anyMatch(event -> "anonymous".equals(event.getActor())
						&& "DOWNLOAD".equals(event.getAction())
						&& !event.isAllowed()
						&& document.getId().equals(event.getDocumentId())));
	}

	@Test
	void csrfRejectedDocumentChangeIsAudited() throws Exception {
		mvc.perform(post("/documents/{id}/delete", document.getId())
				.with(SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER")))
				.andExpect(status().isNotFound());

		org.junit.jupiter.api.Assertions.assertTrue(auditEvents.findAllByOrderByIdDesc().stream()
				.anyMatch(event -> "viewer".equals(event.getActor())
						&& "DELETE".equals(event.getAction())
						&& !event.isAllowed()
						&& document.getId().equals(event.getDocumentId())));
		org.junit.jupiter.api.Assertions.assertTrue(documentRepository.existsById(document.getId()));
	}

	@Test
	void grantAndRevocationTakeEffectOnTheNextRequest() throws Exception {
		documents.grant("owner", document.getId(), "viewer", com.example.trustvault.model.DocumentPermission.READ);
		mvc.perform(get("/documents/{id}/download", document.getId())
				.with(SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER")))
				.andExpect(status().isOk())
				.andExpect(content().bytes(PDF));

		documents.revoke("owner", document.getId(), "viewer");
		mvc.perform(get("/documents/{id}/download", document.getId())
				.with(SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER")))
				.andExpect(status().isForbidden());
	}

	@Test
	void readGrantCannotEditButEditGrantCan() throws Exception {
		documents.grant("owner", document.getId(), "viewer", com.example.trustvault.model.DocumentPermission.READ);
		mvc.perform(get("/documents")
				.with(SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER")))
				.andExpect(status().isOk())
				.andExpect(content().string(not(containsString("Access granted"))));
		mvc.perform(get("/documents/{id}", document.getId())
				.with(SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER")))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Download")))
				.andExpect(content().string(not(containsString("Edit document details"))));
		mvc.perform(post("/documents/{id}/edit", document.getId())
				.with(SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER"))
				.with(SecurityMockMvcRequestPostProcessors.csrf())
				.param("name", "Unauthorized change").param("category", "HR"))
				.andExpect(status().isNotFound());

		documents.grant("owner", document.getId(), "viewer", com.example.trustvault.model.DocumentPermission.EDIT);
		mvc.perform(get("/documents/{id}", document.getId())
				.with(SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER")))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Edit document details")));
		mvc.perform(post("/documents/{id}/edit", document.getId())
				.with(SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER"))
				.with(SecurityMockMvcRequestPostProcessors.csrf())
				.param("name", "Approved change").param("category", "HR"))
				.andExpect(status().is3xxRedirection());
		org.junit.jupiter.api.Assertions.assertEquals("Approved change",
				documentRepository.findById(document.getId()).orElseThrow().getName());
	}

	@Test
	void editGranteeCanUploadEditedFileAndOwnerSeesItAudited() throws Exception {
		byte[] edited = "%PDF-1.7\nedited by viewer\n%%EOF".getBytes(StandardCharsets.UTF_8);
		documents.grant("owner", document.getId(), "viewer", com.example.trustvault.model.DocumentPermission.EDIT);

		mvc.perform(multipart("/documents/{id}/upload-edit", document.getId())
				.file(new MockMultipartFile("file", "letter.pdf", MediaType.APPLICATION_PDF_VALUE, edited))
				.with(SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER"))
				.with(SecurityMockMvcRequestPostProcessors.csrf()))
				.andExpect(status().is3xxRedirection());

		mvc.perform(get("/documents/{id}/download", document.getId())
				.with(SecurityMockMvcRequestPostProcessors.user("owner").roles("OWNER")))
				.andExpect(status().isOk())
				.andExpect(content().bytes(edited));
		org.junit.jupiter.api.Assertions.assertTrue(auditEvents.findAllByOrderByIdDesc().stream()
				.anyMatch(event -> "viewer".equals(event.getActor()) && "EDIT".equals(event.getAction())
						&& event.isAllowed() && document.getId().equals(event.getDocumentId())));
	}

	@Test
	void uploadEditIsDeniedWithoutEditGrantAndRejectsDifferentFileType() throws Exception {
		documents.grant("owner", document.getId(), "viewer", com.example.trustvault.model.DocumentPermission.READ);
		mvc.perform(multipart("/documents/{id}/upload-edit", document.getId())
				.file(new MockMultipartFile("file", "letter.pdf", MediaType.APPLICATION_PDF_VALUE, PDF))
				.with(SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER"))
				.with(SecurityMockMvcRequestPostProcessors.csrf()))
				.andExpect(status().isForbidden());

		ResponseStatusException mismatch = org.junit.jupiter.api.Assertions.assertThrows(ResponseStatusException.class,
				() -> documents.replaceContent("owner", document.getId(),
						new MockMultipartFile("file", "notes.txt", MediaType.TEXT_PLAIN_VALUE,
								"plain text".getBytes(StandardCharsets.UTF_8))));
		org.junit.jupiter.api.Assertions.assertEquals(400, mismatch.getStatusCode().value());
		org.junit.jupiter.api.Assertions.assertArrayEquals(PDF,
				documents.download("owner", document.getId()).document().content());
	}

	@Test
	void deleteGrantAllowsViewerToDeleteButReadGrantDoesNot() throws Exception {
		documents.grant("owner", document.getId(), "viewer", com.example.trustvault.model.DocumentPermission.READ);
		mvc.perform(post("/documents/{id}/delete", document.getId())
				.with(SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER"))
				.with(SecurityMockMvcRequestPostProcessors.csrf()))
				.andExpect(status().isForbidden());
		org.junit.jupiter.api.Assertions.assertTrue(documentRepository.existsById(document.getId()));

		documents.grant("owner", document.getId(), "viewer", com.example.trustvault.model.DocumentPermission.DELETE);
		mvc.perform(post("/documents/{id}/delete", document.getId())
				.with(SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER"))
				.with(SecurityMockMvcRequestPostProcessors.csrf()))
				.andExpect(status().is3xxRedirection());
		org.junit.jupiter.api.Assertions.assertFalse(documentRepository.existsById(document.getId()));
	}

	@Test
	void revokedViewerLosesEditPageAndUploadEditImmediately() throws Exception {
		documents.grant("owner", document.getId(), "viewer", com.example.trustvault.model.DocumentPermission.EDIT);
		mvc.perform(get("/documents/{id}/edit", document.getId())
				.with(SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER")))
				.andExpect(status().isOk());
		documents.revoke("owner", document.getId(), "viewer");
		mvc.perform(get("/documents/{id}/edit", document.getId())
				.with(SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER")))
				.andExpect(status().isForbidden())
				.andExpect(content().string(containsString("Access is not granted for you")));
	}

	@Test
	void documentBackLinkIgnoresForeignRefererAndUsesRoleDefault() throws Exception {
		documents.grant("owner", document.getId(), "viewer", com.example.trustvault.model.DocumentPermission.READ);
		mvc.perform(get("/documents/{id}", document.getId())
				.header("Referer", "http://evil.example/steal")
				.with(SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER")))
				.andExpect(status().isOk())
				.andExpect(content().string(not(containsString("evil.example"))))
				.andExpect(content().string(containsString("/viewer/documents")));
		mvc.perform(get("/documents/{id}", document.getId())
				.with(SecurityMockMvcRequestPostProcessors.user("owner").roles("OWNER")))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("/owner/documents")));
	}

	@Test
	void aliceAndBobCannotTouchEachOthersDocuments() throws Exception {
		AppUser bob = users.saveAndFlush(new AppUser("bob-owner", "unused-hash", "Bob",
				"bob-owner@example.test", Role.OWNER));
		VaultDocument bobsDocument = documents.upload("bob-owner", "Bob private plan", "Finance", "Bob secret notes",
				new MockMultipartFile("file", "bob.pdf", MediaType.APPLICATION_PDF_VALUE,
						"%PDF-1.7\nbob only\n%%EOF".getBytes(StandardCharsets.UTF_8)));
		try {
			var alice = SecurityMockMvcRequestPostProcessors.user("owner").roles("OWNER");
			var bobUser = SecurityMockMvcRequestPostProcessors.user("bob-owner").roles("OWNER");

			mvc.perform(get("/documents/{id}", document.getId()).with(alice)).andExpect(status().isOk());
			mvc.perform(get("/documents/{id}/download", document.getId()).with(alice))
					.andExpect(status().isOk()).andExpect(content().bytes(PDF));
			mvc.perform(get("/documents/{id}", bobsDocument.getId()).with(bobUser)).andExpect(status().isOk());

			mvc.perform(get("/documents/{id}", document.getId()).with(bobUser))
					.andExpect(status().isForbidden())
					.andExpect(content().string(not(containsString("Owner-only test document"))));
			mvc.perform(get("/documents/{id}/download", document.getId()).with(bobUser))
					.andExpect(status().isForbidden())
					.andExpect(content().string(not(containsString("confidential content"))));
			mvc.perform(get("/documents/{id}/open", document.getId()).with(bobUser))
					.andExpect(status().isForbidden());
			mvc.perform(get("/documents/{id}/edit", document.getId()).with(bobUser))
					.andExpect(status().isForbidden());
			mvc.perform(post("/documents/{id}/edit", document.getId()).with(bobUser)
					.with(SecurityMockMvcRequestPostProcessors.csrf())
					.param("name", "Hijacked").param("category", "HR"))
					.andExpect(status().isNotFound());
			mvc.perform(post("/documents/{id}/delete", document.getId()).with(bobUser)
					.with(SecurityMockMvcRequestPostProcessors.csrf()))
					.andExpect(status().isForbidden());
			mvc.perform(post("/documents/{id}/grants", document.getId()).with(bobUser)
					.with(SecurityMockMvcRequestPostProcessors.csrf())
					.param("username", "bob-owner").param("permission", "READ"))
					.andExpect(status().isNotFound());

			VaultDocument unchanged = documentRepository.findById(document.getId()).orElseThrow();
			org.junit.jupiter.api.Assertions.assertEquals("Private salary letter", unchanged.getName());
			org.junit.jupiter.api.Assertions.assertTrue(documents.listGrants("owner", document.getId()).isEmpty());
			org.junit.jupiter.api.Assertions.assertTrue(auditEvents.findAllByOrderByIdDesc().stream()
					.filter(e -> "bob-owner".equals(e.getActor()) && document.getId().equals(e.getDocumentId()))
					.allMatch(e -> !e.isAllowed()));
		} finally {
			documents.delete("bob-owner", bobsDocument.getId());
			users.delete(bob);
		}
	}

	@Test
	void searchByAnotherUserDoesNotDiscloseDescriptionOrContent() throws Exception {
		MvcResult search = mvc.perform(post("/documents/search")
				.with(SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER"))
				.with(SecurityMockMvcRequestPostProcessors.csrf())
				.param("name", "salary"))
				.andReturn();
		mvc.perform(get("/documents").session((MockHttpSession) search.getRequest().getSession(false)))
				.andExpect(status().isOk())
				.andExpect(content().string(not(containsString("Owner-only test document"))))
				.andExpect(content().string(not(containsString("confidential content"))));
		mvc.perform(get("/documents/{id}/download", document.getId())
				.with(SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER")))
				.andExpect(status().isForbidden())
				.andExpect(content().string(not(containsString("confidential content"))));
	}

	@Test
	void uploadLargerThanConfiguredLimitIsRejectedAndNothingIsStored() {
		byte[] oversized = new byte[10 * 1024 * 1024 + 1];
		byte[] header = "%PDF-1.7\n".getBytes(StandardCharsets.UTF_8);
		System.arraycopy(header, 0, oversized, 0, header.length);
		long before = documentRepository.count();
		ResponseStatusException exception = org.junit.jupiter.api.Assertions.assertThrows(ResponseStatusException.class,
				() -> documents.upload("owner", "Too big", "HR", null,
						new MockMultipartFile("file", "big.pdf", MediaType.APPLICATION_PDF_VALUE, oversized)));
		org.junit.jupiter.api.Assertions.assertEquals(400, exception.getStatusCode().value());
		org.junit.jupiter.api.Assertions.assertEquals(before, documentRepository.count());
	}

	@Test
	void uploadRejectsFileWhoseContentDoesNotMatchAllowedPdfType() {
		ResponseStatusException exception = org.junit.jupiter.api.Assertions.assertThrows(ResponseStatusException.class,
				() -> documents.upload("owner", "Invalid document", "HR", null,
						new MockMultipartFile("file", "fake.pdf", MediaType.APPLICATION_PDF_VALUE,
								"not a PDF".getBytes(StandardCharsets.UTF_8))));
		org.junit.jupiter.api.Assertions.assertEquals(400, exception.getStatusCode().value());
	}

	@Test
	void hmacAuditChainDetectsModifiedEventData() {
		auditService.recordSuccess("owner", "TEST", document.getId(), "integrity fixture");
		org.junit.jupiter.api.Assertions.assertTrue(auditService.verifyChain());
		AuditEvent event = auditEvents.findTopByOrderByIdDesc().orElseThrow();
		jdbc.update("update audit_events set details = ? where id = ?", "tampered", event.getId());
		org.junit.jupiter.api.Assertions.assertFalse(auditService.verifyChain());
		jdbc.update("update audit_events set details = ? where id = ?", event.getDetails(), event.getId());
		org.junit.jupiter.api.Assertions.assertTrue(auditService.verifyChain());
	}
}
