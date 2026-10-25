package com.example.trustvault.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(name = "folder_grants", uniqueConstraints = @UniqueConstraint(columnNames = {"folder_id", "grantee_username"}))
public class FolderGrant {
	@Id
	@Column(length = 36)
	private String id;

	@ManyToOne(fetch = FetchType.EAGER, optional = false)
	@JoinColumn(name = "folder_id", nullable = false)
	private Folder folder;

	@ManyToOne(fetch = FetchType.EAGER, optional = false)
	@JoinColumn(name = "grantee_username", nullable = false)
	private AppUser grantee;

	@Column(nullable = false)
	private Instant grantedAt;

	protected FolderGrant() {
	}

	public FolderGrant(String id, Folder folder, AppUser grantee, Instant grantedAt) {
		this.id = id;
		this.folder = folder;
		this.grantee = grantee;
		this.grantedAt = grantedAt;
	}

	public String getId() {
		return id;
	}

	public Folder getFolder() {
		return folder;
	}

	public AppUser getGrantee() {
		return grantee;
	}

	public void changeGrantee(AppUser grantee) {
		this.grantee = grantee;
	}

	public Instant getGrantedAt() {
		return grantedAt;
	}
}
