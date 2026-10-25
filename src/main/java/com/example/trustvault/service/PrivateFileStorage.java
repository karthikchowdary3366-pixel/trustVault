package com.example.trustvault.service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class PrivateFileStorage {
	private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
	private final Path root;

	public PrivateFileStorage(@Value("${trustvault.storage-dir}") String storageDirectory) {
		this.root = Path.of(storageDirectory).toAbsolutePath().normalize();
		try {
			Files.createDirectories(root);
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to initialize private document storage", exception);
		}
	}

	public StoredFile store(MultipartFile upload) {
		if (upload == null || upload.isEmpty()) {
			throw new IllegalArgumentException("Choose a file to upload.");
		}
		if (upload.getSize() > MAX_FILE_SIZE) {
			throw new IllegalArgumentException("Files must be 10 MB or smaller.");
		}

		String originalName = upload.getOriginalFilename();
		String extension = originalName == null || originalName.lastIndexOf('.') < 0
				? "" : originalName.substring(originalName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
		byte[] bytes;
		try {
			bytes = upload.getBytes();
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to read uploaded file", exception);
		}
		if (bytes.length > MAX_FILE_SIZE) {
			throw new IllegalArgumentException("Files must be 10 MB or smaller.");
		}

		String contentType = validateAndDetect(extension, bytes);
		String storageKey = UUID.randomUUID() + "." + extension;
		Path destination = resolve(storageKey);
		try {
			Files.write(destination, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to store uploaded file", exception);
		}
		return new StoredFile(storageKey, contentType, bytes.length);
	}

	public byte[] read(String storageKey) {
		try {
			return Files.readAllBytes(resolve(storageKey));
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to read stored document", exception);
		}
	}

	public String detectType(MultipartFile upload) {
		if (upload == null || upload.isEmpty()) {
			throw new IllegalArgumentException("Choose a file to upload.");
		}
		String originalName = upload.getOriginalFilename();
		String extension = originalName == null || originalName.lastIndexOf('.') < 0
				? "" : originalName.substring(originalName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
		try {
			return validateAndDetect(extension, upload.getBytes());
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to read uploaded file", exception);
		}
	}

	public void overwrite(String storageKey, MultipartFile upload) {
		try {
			overwrite(storageKey, upload.getBytes());
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to read uploaded file", exception);
		}
	}

	public void overwrite(String storageKey, byte[] bytes) {
		if (bytes.length == 0 || bytes.length > MAX_FILE_SIZE) {
			throw new IllegalArgumentException("Content must be between 1 byte and 10 MB.");
		}
		try {
			Files.write(resolve(storageKey), bytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to save document content", exception);
		}
	}

	public void delete(String storageKey) {
		try {
			Files.deleteIfExists(resolve(storageKey));
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to delete stored document", exception);
		}
	}

	private Path resolve(String storageKey) {
		Path resolved = root.resolve(storageKey).normalize();
		if (!resolved.getParent().equals(root)) {
			throw new IllegalArgumentException("Invalid storage key.");
		}
		return resolved;
	}

	private String validateAndDetect(String extension, byte[] bytes) {
		if (bytes.length == 0) {
			throw new IllegalArgumentException("Empty files cannot be uploaded.");
		}
		if ("pdf".equals(extension) && startsWith(bytes, "%PDF-".getBytes(StandardCharsets.US_ASCII))) {
			return "application/pdf";
		}
		if ("png".equals(extension) && startsWith(bytes,
				new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a})) {
			return "image/png";
		}
		if (("jpg".equals(extension) || "jpeg".equals(extension))
				&& bytes.length >= 3 && (bytes[0] & 0xff) == 0xff
				&& (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff) {
			return "image/jpeg";
		}
		if ("txt".equals(extension) && !containsZeroByte(bytes)) {
			try {
				StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes));
				return "text/plain";
			} catch (CharacterCodingException exception) {
				throw new IllegalArgumentException("Text documents must be valid UTF-8.");
			}
		}
		if (("docx".equals(extension) && startsWith(bytes, ZIP_MAGIC) && contains(bytes, "word/document.xml"))) {
			return DOCX;
		}
		if (("xlsx".equals(extension) && startsWith(bytes, ZIP_MAGIC) && contains(bytes, "xl/workbook.xml"))) {
			return XLSX;
		}
		if ("doc".equals(extension) && startsWith(bytes, OLE_MAGIC)) {
			return "application/msword";
		}
		if ("xls".equals(extension) && startsWith(bytes, OLE_MAGIC)) {
			return "application/vnd.ms-excel";
		}
		throw new IllegalArgumentException(
				"Only valid PDF, PNG, JPEG, UTF-8 text, Word (.doc, .docx), and Excel (.xls, .xlsx) files are accepted.");
	}

	private static final byte[] ZIP_MAGIC = {0x50, 0x4b, 0x03, 0x04};
	private static final byte[] OLE_MAGIC = {(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0, (byte) 0xa1, (byte) 0xb1,
			0x1a, (byte) 0xe1};
	public static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
	public static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

	private boolean contains(byte[] value, String text) {
		return new String(value, StandardCharsets.ISO_8859_1).contains(text);
	}

	private boolean startsWith(byte[] value, byte[] prefix) {
		return value.length >= prefix.length && Arrays.equals(Arrays.copyOf(value, prefix.length), prefix);
	}

	private boolean containsZeroByte(byte[] value) {
		for (byte item : value) {
			if (item == 0) {
				return true;
			}
		}
		return false;
	}

	public record StoredFile(String storageKey, String contentType, long size) {
	}
}
