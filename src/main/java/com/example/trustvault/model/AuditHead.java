package com.example.trustvault.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "audit_chain_head")
public class AuditHead {
	@Id
	private int id;

	@Column(nullable = false, length = 64)
	private String eventHash;

	protected AuditHead() {
	}

	public AuditHead(int id, String eventHash) {
		this.id = id;
		this.eventHash = eventHash;
	}

	public String getEventHash() {
		return eventHash;
	}

	public void setEventHash(String eventHash) {
		this.eventHash = eventHash;
	}
}
