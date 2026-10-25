package com.example.trustvault.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "audit_events")
public class AuditEvent {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 80)
	private String actor;

	@Column(nullable = false, length = 40)
	private String action;

	@Column(length = 36)
	private String documentId;

	@Column(nullable = false, length = 2000)
	private String details;

	@Column(nullable = false)
	private boolean allowed;

	@Column(nullable = false)
	private Instant occurredAt;

	@Column(length = 64)
	private String previousHash;

	@Column(nullable = false, length = 64)
	private String eventHash;

	protected AuditEvent() {
	}

	public AuditEvent(String actor, String action, String documentId, String details, boolean allowed,
			Instant occurredAt, String previousHash, String eventHash) {
		this.actor = actor;
		this.action = action;
		this.documentId = documentId;
		this.details = details;
		this.allowed = allowed;
		this.occurredAt = occurredAt;
		this.previousHash = previousHash;
		this.eventHash = eventHash;
	}

	public Long getId() {
		return id;
	}

	public String getActor() {
		return actor;
	}

	public String getAction() {
		return action;
	}

	public String getDocumentId() {
		return documentId;
	}

	public String getDetails() {
		return details;
	}

	public boolean isAllowed() {
		return allowed;
	}

	public Instant getOccurredAt() {
		return occurredAt;
	}

	public String getPreviousHash() {
		return previousHash;
	}

	public String getEventHash() {
		return eventHash;
	}
}
