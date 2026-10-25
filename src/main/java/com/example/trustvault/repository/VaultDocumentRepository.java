package com.example.trustvault.repository;

import com.example.trustvault.model.VaultDocument;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VaultDocumentRepository extends JpaRepository<VaultDocument, String> {
	@Query("select d from VaultDocument d where d.owner.username = :username")
	List<VaultDocument> findAllByOwnerUsername(@Param("username") String username);

	List<VaultDocument> findAllByOrderByUploadedAtDesc();

	@Query("select d from VaultDocument d where d.folder.id = :folderId")
	List<VaultDocument> findAllByFolderId(@Param("folderId") String folderId);

	@Query("select d from VaultDocument d where d.folder.id in :folderIds")
	List<VaultDocument> findAllByFolderIdIn(@Param("folderIds") List<String> folderIds);
}
