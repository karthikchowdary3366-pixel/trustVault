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
@Table(name = "folders")
public class Folder {
	@Id
	@Column(length = 36)
	private String id;

	@Column(nullable = false, length = 180)
	private String name;

	@Column(length = 1000)
	private String description;

	@Column(nullable = false)
	private Instant createdAt;

	@ManyToOne(fetch = FetchType.EAGER, optional = false)
	@JoinColumn(name = "owner_username", nullable = false)
	private AppUser owner;

	protected Folder() {
	}

	public Folder(String id, String name, String description, Instant createdAt, AppUser owner) {
		this.id = id;
		this.name = name;
		this.description = description;
		this.createdAt = createdAt;
		this.owner = owner;
	}

	public void update(String name, String description) {
		this.name = name;
		this.description = description;
	}

	public String getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public AppUser getOwner() {
		return owner;
	}

	public void changeOwner(AppUser owner) {
		this.owner = owner;
	}
}
