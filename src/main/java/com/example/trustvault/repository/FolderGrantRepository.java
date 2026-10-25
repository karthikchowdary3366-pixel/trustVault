package com.example.trustvault.repository;

import com.example.trustvault.model.FolderGrant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FolderGrantRepository extends JpaRepository<FolderGrant, String> {
	List<FolderGrant> findAllByGranteeUsername(String granteeUsername);
	List<FolderGrant> findAllByFolderIdOrderByGranteeUsernameAsc(String folderId);
	Optional<FolderGrant> findByFolderIdAndGranteeUsername(String folderId, String granteeUsername);
	void deleteByFolderId(String folderId);
}
