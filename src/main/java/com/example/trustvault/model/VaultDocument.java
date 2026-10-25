package com.example.trustvault.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "vault_documents")
public class VaultDocument {
	@Id
	@Column(length = 36)
	private String id;

	@Column(nullable = false, length = 180)
	private String name;

	@Column(nullable = false, length = 80)
	private String category;

	@Column(length = 1000)
	private String description;

	@Column(nullable = false, length = 100)
	private String storageKey;

	@Column(nullable = false, length = 100)
	private String contentType;

	@Column(nullable = false)
	private long size;

	@Column(nullable = false)
	private Instant uploadedAt;

	@ManyToOne(fetch = FetchType.EAGER, optional = false)
	@JoinColumn(name = "owner_username", nullable = false)
	private AppUser owner;

	@ManyToOne(fetch = FetchType.EAGER, optional = true)
	@JoinColumn(name = "folder_id", nullable = true)
	private Folder folder;

	protected VaultDocument() {
	}

	public VaultDocument(String id, String name, String category, String description, String storageKey,
			String contentType, long size, Instant uploadedAt, AppUser owner) {
		this.id = id;
		this.name = name;
		this.category = category;
		this.description = description;
		this.storageKey = storageKey;
		this.contentType = contentType;
		this.size = size;
		this.uploadedAt = uploadedAt;
		this.owner = owner;
		this.folder = null;
	}

	public VaultDocument(String id, String name, String category, String description, String storageKey,
			String contentType, long size, Instant uploadedAt, AppUser owner, Folder folder) {
		this.id = id;
		this.name = name;
		this.category = category;
		this.description = description;
		this.storageKey = storageKey;
		this.contentType = contentType;
		this.size = size;
		this.uploadedAt = uploadedAt;
		this.owner = owner;
		this.folder = folder;
	}

	public void updateSize(long size) {
		this.size = size;
	}

	public void update(String name, String category, String description) {
		this.name = name;
		this.category = category;
		this.description = description;
	}

	public String getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getCategory() {
		return category;
	}

	public String getDescription() {
		return description;
	}

	public String getStorageKey() {
		return storageKey;
	}

	public String getContentType() {
		return contentType;
	}

	public long getSize() {
		return size;
	}

	public Instant getUploadedAt() {
		return uploadedAt;
	}

	public AppUser getOwner() {
		return owner;
	}

	public void changeOwner(AppUser owner) {
		this.owner = owner;
	}

	public Folder getFolder() {
		return folder;
	}

	public void setFolder(Folder folder) {
		this.folder = folder;
	}
}
