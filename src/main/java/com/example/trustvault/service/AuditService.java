package com.example.trustvault.service;

import com.example.trustvault.model.AuditEvent;
import com.example.trustvault.model.AuditHead;
import com.example.trustvault.repository.AuditEventRepository;
import com.example.trustvault.repository.AuditHeadRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {
	private final AuditEventRepository events;
	private final AuditHeadRepository heads;
	private final byte[] key;

	public AuditService(AuditEventRepository events, AuditHeadRepository heads,
			@Value("${trustvault.audit-key}") String key) {
		this.events = events;
		this.heads = heads;
		this.key = key.getBytes(StandardCharsets.UTF_8);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recordDenied(String actor, String action, String documentId, String details) {
		append(actor, action, documentId, details, false);
	}

	@Transactional
	public void recordSuccess(String actor, String action, String documentId, String details) {
		append(actor, action, documentId, details, true);
	}

	private void append(String actor, String action, String documentId, String details, boolean allowed) {
		Instant timestamp = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
		String safeDocumentId = documentId != null && documentId.length() <= 36 ? documentId : null;
		AuditHead head = heads.lockHead()
				.orElseThrow(() -> new IllegalStateException("Audit chain head has not been initialized"));
		String previousHash = head.getEventHash();
		String hash = calculateHash(actor, action, safeDocumentId, details, allowed, timestamp, previousHash);
		events.saveAndFlush(new AuditEvent(actor, action, safeDocumentId, details, allowed, timestamp, previousHash, hash));
		head.setEventHash(hash);
	}

	@Transactional
	public boolean verifyChain() {
		AuditHead head = heads.lockHead().orElse(null);
		if (head == null) {
			return false;
		}
		String previousHash = "";
		List<AuditEvent> chain = events.findAllByOrderByIdAsc();
		for (AuditEvent event : chain) {
			String expected = calculateHash(event.getActor(), event.getAction(), event.getDocumentId(),
					event.getDetails(), event.isAllowed(), event.getOccurredAt(), previousHash);
			if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
					event.getEventHash().getBytes(StandardCharsets.UTF_8))
					|| !previousHash.equals(event.getPreviousHash())) {
				return false;
			}
			previousHash = event.getEventHash();
		}
		return head.getEventHash().equals(previousHash);
	}

	@Transactional(readOnly = true)
	public List<AuditEvent> latestEvents() {
		return events.findAllByOrderByIdDesc();
	}

	@Transactional(readOnly = true)
	public List<AuditEvent> latestEventsForDocuments(Set<String> documentIds) {
		if (documentIds.isEmpty()) {
			return List.of();
		}
		Set<String> ownedDocumentIds = new HashSet<>(documentIds);
		return events.findAllByOrderByIdDesc().stream()
				.filter(event -> event.getDocumentId() != null && ownedDocumentIds.contains(event.getDocumentId()))
				.toList();
	}

	private String calculateHash(String actor, String action, String documentId, String details, boolean allowed,
			Instant timestamp, String previousHash) {
		String canonical = String.join("\n", previousHash, timestamp.toString(), actor, action,
				documentId == null ? "" : documentId, details, Boolean.toString(allowed));
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
		} catch (java.security.GeneralSecurityException exception) {
			throw new IllegalStateException("Unable to calculate audit event signature", exception);
		}
	}
}
