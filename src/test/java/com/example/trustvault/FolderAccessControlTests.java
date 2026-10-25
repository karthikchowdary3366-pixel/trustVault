package com.example.trustvault;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.trustvault.model.AppUser;
import com.example.trustvault.model.Folder;
import com.example.trustvault.model.Role;
import com.example.trustvault.model.VaultDocument;
import com.example.trustvault.repository.AppUserRepository;
import com.example.trustvault.repository.AuditEventRepository;
import com.example.trustvault.repository.DocumentGrantRepository;
import com.example.trustvault.repository.FolderGrantRepository;
import com.example.trustvault.repository.FolderRepository;
import com.example.trustvault.repository.VaultDocumentRepository;
import com.example.trustvault.service.DocumentService;
import com.example.trustvault.service.FolderService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FolderAccessControlTests {
	private static final byte[] PDF = "%PDF-1.7\nconfidential content\n%%EOF".getBytes(StandardCharsets.UTF_8);

	@Autowired
	private MockMvc mvc;

	@Autowired
	private FolderService folders;

	@Autowired
	private DocumentService documents;

	@Autowired
	private FolderRepository folderRepository;

	@Autowired
	private FolderGrantRepository folderGrants;

	@Autowired
	private DocumentGrantRepository documentGrants;

	@Autowired
	private VaultDocumentRepository documentRepository;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private AuditEventRepository auditEvents;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private AppUser owner;
	private AppUser viewer;
	private MockHttpSession ownerSession;
	private MockHttpSession viewerSession;

	@BeforeEach
	void setUp() throws Exception {
		// Create users
		owner = new AppUser("owner", "$2a$10$test", "Owner Name", "owner@example.com", Role.OWNER);
		viewer = new AppUser("viewer", "$2a$10$test", "Viewer Name", "viewer@example.com", Role.VIEWER);
		users.save(owner);
		users.save(viewer);

		// Login and create sessions
		ownerSession = authenticatedSession("owner", "OWNER");
		viewerSession = authenticatedSession("viewer", "VIEWER");
	}

	private static MockHttpSession authenticatedSession(String username, String role) {
		var principal = org.springframework.security.core.userdetails.User.withUsername(username)
				.password("n/a").roles(role).build();
		var context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
		context.setAuthentication(
				org.springframework.security.authentication.UsernamePasswordAuthenticationToken
						.authenticated(principal, "n/a", principal.getAuthorities()));
		MockHttpSession session = new MockHttpSession();
		session.setAttribute(
				org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
				context);
		return session;
	}

	@AfterEach
	void cleanUp() {
		auditEvents.deleteAll();
		jdbcTemplate.update("update audit_chain_head set event_hash = ''");
		documentGrants.deleteAll();
		folderGrants.deleteAll();
		documentRepository.deleteAll();
		folderRepository.deleteAll();
		users.deleteAll();
	}

	@Test
	void viewerCanSeeAllFilesInGrantedFolder() throws Exception {
		// Owner creates folder
		Folder folder = folders.createFolder("owner", "Q4 Contracts", "Quarterly contracts");

		// Owner uploads two documents to folder
		VaultDocument doc1 = documents.upload("owner", "ContractA", "Contracts", "First contract", 
				new MockMultipartFile("file", "test.pdf", "application/pdf", PDF), folder.getId());
		VaultDocument doc2 = documents.upload("owner", "ContractB", "Contracts", "Second contract",
				new MockMultipartFile("file", "test.pdf", "application/pdf", PDF), folder.getId());

		// Owner grants folder access to viewer
		folders.grantFolderAccess("owner", folder.getId(), "viewer");

		// Viewer can view folder
		mvc.perform(get("/folders/" + folder.getId()).session(viewerSession))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Q4 Contracts")))
				.andExpect(content().string(containsString("ContractA")))
				.andExpect(content().string(containsString("ContractB")));
	}

	@Test
	void viewerCanListAndOpenGrantedFolderToSeeItsDocuments() throws Exception {
		Folder folder = folders.createFolder("owner", "Q4 Contracts", "");
		documents.upload("owner", "ContractA", "Contracts", "",
				new MockMultipartFile("file", "contract.pdf", "application/pdf", PDF), folder.getId());
		folders.grantFolderAccess("owner", folder.getId(), "viewer");
		var viewerAuth = SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER");

		mvc.perform(get("/folders").with(viewerAuth))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Q4 Contracts")))
				.andExpect(content().string(containsString("/folders/" + folder.getId())));

		mvc.perform(get("/folders/" + folder.getId()).with(viewerAuth))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("ContractA")));
	}

	@Test
	void viewerCanAttemptToOpenFolderDocumentAndGetsAccessDeniedWhenNotGranted() throws Exception {
		Folder folder = folders.createFolder("owner", "Q4 Contracts", "");
		VaultDocument document = documents.upload("owner", "ContractA", "Contracts", "",
				new MockMultipartFile("file", "contract.pdf", "application/pdf", PDF), folder.getId());
		folders.grantFolderAccess("owner", folder.getId(), "viewer");
		var viewerAuth = SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER");

		mvc.perform(get("/folders/" + folder.getId()).with(viewerAuth))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("/documents/" + document.getId() + "/open")));

		mvc.perform(get("/documents/" + document.getId() + "/open").with(viewerAuth))
				.andExpect(status().isForbidden())
				.andExpect(content().string(containsString("Access is not granted for you")));
	}

	@Test
	void viewerDocumentsPageListsFolderContentsButOnlyOpensIndividuallyGrantedDocuments() throws Exception {
		Folder folder = folders.createFolder("owner", "Q4 Contracts", "");
		VaultDocument restrictedDocument = documents.upload("owner", "RestrictedContract", "Contracts", "",
				new MockMultipartFile("file", "restricted.pdf", "application/pdf", PDF), folder.getId());
		VaultDocument sharedDocument = documents.upload("owner", "SharedContract", "Contracts", "",
				new MockMultipartFile("file", "shared.pdf", "application/pdf", PDF), folder.getId());
		folders.grantFolderAccess("owner", folder.getId(), "viewer");
		documents.grant("owner", sharedDocument.getId(), "viewer",
				com.example.trustvault.model.DocumentPermission.READ);

		mvc.perform(get("/viewer/documents")
				.with(SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER")))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("RestrictedContract")))
				.andExpect(content().string(containsString("SharedContract")))
				.andExpect(content().string(not(containsString("Access not granted"))))
				.andExpect(content().string(containsString("/documents/" + sharedDocument.getId() + "/open")))
				.andExpect(content().string(containsString("/documents/" + restrictedDocument.getId() + "/open")));

		mvc.perform(get("/documents/" + restrictedDocument.getId() + "/open")
				.with(SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER")))
				.andExpect(status().isForbidden())
				.andExpect(content().string(containsString("Access is not granted")));
	}

	@Test
	void viewerCannotDownloadDocumentWithoutExplicitGrant() throws Exception {
		// Owner creates folder and documents
		Folder folder = folders.createFolder("owner", "Q4 Contracts", "");
		VaultDocument doc = documents.upload("owner", "ContractA", "Contracts", "",
				new MockMultipartFile("file", "test.pdf", "application/pdf", PDF), folder.getId());

		// Owner grants folder access but NOT document access
		folders.grantFolderAccess("owner", folder.getId(), "viewer");

		// Viewer can see folder but cannot download document
		mvc.perform(get("/documents/" + doc.getId() + "/download").session(viewerSession))
				.andExpect(status().isForbidden());

		// Verify access denial was audited
		long denialCount = auditEvents.findAll().stream()
				.filter(e -> "DOWNLOAD".equals(e.getAction()) && !e.isAllowed()).count();
		assert denialCount > 0 : "Expected denied download audit entry";
	}

	@Test
	void viewerWithDocumentGrantCanDownload() throws Exception {
		// Owner creates folder and document
		Folder folder = folders.createFolder("owner", "Q4 Contracts", "");
		VaultDocument doc = documents.upload("owner", "ContractA", "Contracts", "",
				new MockMultipartFile("file", "test.pdf", "application/pdf", PDF), folder.getId());

		// Owner grants folder access
		folders.grantFolderAccess("owner", folder.getId(), "viewer");

		// Owner also grants explicit document READ access
		documents.grant("owner", doc.getId(), "viewer", com.example.trustvault.model.DocumentPermission.READ);

		// Now viewer can download document
		mvc.perform(get("/documents/" + doc.getId() + "/download").session(viewerSession))
				.andExpect(status().isOk());

		// Verify successful download was audited
		long successCount = auditEvents.findAll().stream()
				.filter(e -> "DOWNLOAD".equals(e.getAction()) && e.isAllowed()).count();
		assert successCount > 0 : "Expected successful download audit entry";
	}

	@Test
	void viewerWithoutFolderGrantCannotSeeFolderOrDocuments() throws Exception {
		// Owner creates folder and document
		Folder folder = folders.createFolder("owner", "Q4 Contracts", "");
		documents.upload("owner", "ContractA", "Contracts", "",
				new MockMultipartFile("file", "test.pdf", "application/pdf", PDF), folder.getId());

		// Owner does NOT grant folder access to viewer

		// Viewer cannot view folder
		mvc.perform(get("/folders/" + folder.getId()).session(viewerSession))
				.andExpect(status().isForbidden());
	}

	@Test
	void ownerCanManageFolderAccess() throws Exception {
		// Owner creates folder
		Folder folder = folders.createFolder("owner", "Q4 Contracts", "");

		// Owner grants folder access
		mvc.perform(post("/folders/" + folder.getId() + "/grants")
				.session(ownerSession)
				.param("username", "viewer")
				.with(SecurityMockMvcRequestPostProcessors.csrf()))
				.andExpect(status().isFound());

		// Verify grant exists
		assert folders.listFolderGrants("owner", folder.getId()).size() > 0;

		// Owner revokes access
		mvc.perform(post("/folders/" + folder.getId() + "/grants/revoke")
				.session(ownerSession)
				.param("username", "viewer")
				.with(SecurityMockMvcRequestPostProcessors.csrf()))
				.andExpect(status().isFound());

		// Verify grant is removed
		assert folders.listFolderGrants("owner", folder.getId()).isEmpty();
	}

	@Test
	void ownerCannotDeleteNonEmptyFolder() throws Exception {
		// Owner creates folder and uploads document
		Folder folder = folders.createFolder("owner", "Q4 Contracts", "");
		documents.upload("owner", "ContractA", "Contracts", "",
				new MockMultipartFile("file", "test.pdf", "application/pdf", PDF), folder.getId());

		// Owner cannot delete folder
		try {
			folders.deleteFolder("owner", folder.getId());
			assert false : "Expected exception for deleting non-empty folder";
		} catch (ResponseStatusException e) {
			assert e.getStatusCode().value() == 400 : "Expected 400 Bad Request";
		}
	}

	@Test
	void viewerFoldersPageListsGrantedFoldersOrShowsEmptyMessage() throws Exception {
		mvc.perform(get("/viewer/folders")
				.with(SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER")))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("No folder to show")));

		Folder folder = folders.createFolder("owner", "Granted Folder", "");
		folders.grantFolderAccess("owner", folder.getId(), "viewer");

		mvc.perform(get("/viewer/folders")
				.with(SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER")))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Granted Folder")))
				.andExpect(content().string(not(containsString("No folder to show"))));
	}

	@Test
	void ownerCanDeleteFolderContainingDocuments() throws Exception {
		Folder folder = folders.createFolder("owner", "Q4 Contracts", "");
		VaultDocument doc = documents.upload("owner", "ContractA", "Contracts", "",
				new MockMultipartFile("file", "test.pdf", "application/pdf", PDF), folder.getId());

		mvc.perform(post("/folders/" + folder.getId() + "/delete")
				.with(SecurityMockMvcRequestPostProcessors.user("owner").roles("OWNER"))
				.with(SecurityMockMvcRequestPostProcessors.csrf()))
				.andExpect(status().isFound());

		assert !folderRepository.existsById(folder.getId());
		assert !documentRepository.existsById(doc.getId());
	}

	@Test
	void ownerCanDeleteAllDocumentsInFolderWithoutDeletingFolder() throws Exception {
		Folder folder = folders.createFolder("owner", "Q4 Contracts", "");
		documents.upload("owner", "ContractA", "Contracts", "",
				new MockMultipartFile("file", "contract-a.pdf", "application/pdf", PDF), folder.getId());
		documents.upload("owner", "ContractB", "Contracts", "",
				new MockMultipartFile("file", "contract-b.pdf", "application/pdf", PDF), folder.getId());

		mvc.perform(post("/folders/" + folder.getId() + "/documents/delete-all")
				.with(SecurityMockMvcRequestPostProcessors.user("owner").roles("OWNER"))
				.with(SecurityMockMvcRequestPostProcessors.csrf()))
				.andExpect(status().isFound())
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
						.redirectedUrl("/folders/" + folder.getId()));

		assert documentRepository.findAllByFolderId(folder.getId()).isEmpty();
		assert folderRepository.findById(folder.getId()).isPresent();
	}

	@Test
	void auditTrailRecordsAccessAttempts() throws Exception {
		// Owner creates folder and document
		Folder folder = folders.createFolder("owner", "Q4 Contracts", "");
		VaultDocument doc = documents.upload("owner", "ContractA", "Contracts", "",
				new MockMultipartFile("file", "test.pdf", "application/pdf", PDF), folder.getId());

		// Owner grants folder but not document access
		folders.grantFolderAccess("owner", folder.getId(), "viewer");

		// Viewer attempts to download (should be denied)
		mvc.perform(get("/documents/" + doc.getId() + "/download").session(viewerSession))
				.andExpect(status().isForbidden());

		// Verify denied access is in audit trail
		var denialEvents = auditEvents.findAll().stream()
				.filter(e -> "DOWNLOAD".equals(e.getAction()) && !e.isAllowed() 
						&& "viewer".equals(e.getActor()))
				.toList();
		assert !denialEvents.isEmpty() : "Expected denied access in audit trail";
	}
}
