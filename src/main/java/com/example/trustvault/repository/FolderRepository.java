package com.example.trustvault.repository;

import com.example.trustvault.model.Folder;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FolderRepository extends JpaRepository<Folder, String> {
	List<Folder> findAllByOwnerUsername(String ownerUsername);
	List<Folder> findAllByOrderByCreatedAtDesc();
}
