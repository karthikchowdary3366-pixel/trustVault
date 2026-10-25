package com.example.trustvault.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(name = "document_grants", uniqueConstraints = @UniqueConstraint(columnNames = {"document_id", "grantee_username"}))
public class DocumentGrant {
	@Id
	@Column(length = 36)
	private String id;

	@ManyToOne(fetch = FetchType.EAGER, optional = false)
	@JoinColumn(name = "document_id", nullable = false)
	private VaultDocument document;

	@ManyToOne(fetch = FetchType.EAGER, optional = false)
	@JoinColumn(name = "grantee_username", nullable = false)
	private AppUser grantee;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private DocumentPermission permission;

	@Column(name = "can_delete")
	private Boolean canDelete;

	@Column(nullable = false)
	private Instant grantedAt;

	protected DocumentGrant() {
	}

	public DocumentGrant(String id, VaultDocument document, AppUser grantee, DocumentPermission permission,
			Instant grantedAt) {
		this.id = id;
		this.document = document;
		this.grantee = grantee;
		applyPermission(permission);
		this.grantedAt = grantedAt;
	}

	private void applyPermission(DocumentPermission level) {
		this.canDelete = level == DocumentPermission.DELETE;
		this.permission = level == DocumentPermission.DELETE ? DocumentPermission.EDIT : level;
	}

	public boolean isDeleteAllowed() {
		return Boolean.TRUE.equals(canDelete);
	}

	public DocumentPermission getAccessLevel() {
		return isDeleteAllowed() ? DocumentPermission.DELETE : permission;
	}

	public String getId() {
		return id;
	}

	public VaultDocument getDocument() {
		return document;
	}

	public AppUser getGrantee() {
		return grantee;
	}

	public void changeGrantee(AppUser grantee) {
		this.grantee = grantee;
	}

	public DocumentPermission getPermission() {
		return permission;
	}

	public void changePermission(DocumentPermission permission) {
		applyPermission(permission);
	}

	public Instant getGrantedAt() {
		return grantedAt;
	}
}
