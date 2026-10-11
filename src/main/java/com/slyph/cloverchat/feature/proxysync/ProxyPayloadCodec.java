package com.slyph.cloverchat.feature.proxysync;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class ProxyPayloadCodec {

    public static final String VERSION = "2";
    public static final int MAX_PACKET_BYTES = 30000;
    private static final int MAX_SERVER_BYTES = 32;
    private static final int MAX_MESSAGE_ID_BYTES = 80;
    private static final int MAX_MODE_BYTES = 16;
    private static final int MAX_PERMISSION_BYTES = 255;
    private static final int MAX_COMPONENT_BYTES = 26000;
    private static final int SIGNATURE_BYTES = 32;
    private static final long FUTURE_TOLERANCE_MILLIS = 10000L;

    private final byte[] secret;

    public ProxyPayloadCodec(String secret) {
        if (!isStrongSecret(secret)) {
            throw new IllegalArgumentException("Proxy shared secret must contain at least 32 UTF-8 bytes");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public static boolean isStrongSecret(String secret) {
        if (secret == null || secret.isBlank() || secret.equalsIgnoreCase("change_me")) {
            return false;
        }
        return secret.getBytes(StandardCharsets.UTF_8).length >= 32;
    }

    public byte[] encode(Payload payload) {
        if (!isValid(payload)) {
            return null;
        }
        try {
            byte[] unsigned = encodeUnsigned(payload);
            byte[] signature = sign(unsigned);
            ByteArrayOutputStream stream = new ByteArrayOutputStream(unsigned.length + signature.length + 8);
            try (DataOutputStream output = new DataOutputStream(stream)) {
                output.writeInt(unsigned.length);
                output.write(unsigned);
                output.writeInt(signature.length);
                output.write(signature);
            }
            byte[] encoded = stream.toByteArray();
            return encoded.length <= MAX_PACKET_BYTES ? encoded : null;
        } catch (Exception exception) {
            return null;
        }
    }

    public Payload decode(byte[] data, long nowMillis, long maxAgeMillis) {
        if (data == null || data.length == 0 || data.length > MAX_PACKET_BYTES) {
            return null;
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(data))) {
            int unsignedLength = input.readInt();
            if (unsignedLength <= 0 || unsignedLength > MAX_PACKET_BYTES - SIGNATURE_BYTES - 8) {
                return null;
            }
            byte[] unsigned = new byte[unsignedLength];
            input.readFully(unsigned);
            int signatureLength = input.readInt();
            if (signatureLength != SIGNATURE_BYTES || input.available() != signatureLength) {
                return null;
            }
            byte[] signature = new byte[signatureLength];
            input.readFully(signature);
            if (!MessageDigest.isEqual(signature, sign(unsigned))) {
                return null;
            }

            Payload payload = decodeUnsigned(unsigned);
            if (!isValid(payload)) {
                return null;
            }
            long allowedAge = Math.max(5000L, Math.min(maxAgeMillis, 300000L));
            if (payload.timestampMillis <= 0L
                    || payload.timestampMillis > nowMillis + FUTURE_TOLERANCE_MILLIS) {
                return null;
            }
            if (payload.timestampMillis < nowMillis - allowedAge) {
                return null;
            }
            return payload;
        } catch (Exception exception) {
            return null;
        }
    }

    private byte[] encodeUnsigned(Payload payload) throws Exception {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(stream)) {
            writeString(output, payload.version, 8);
            output.writeLong(payload.timestampMillis);
            writeString(output, payload.sourceServer, MAX_SERVER_BYTES);
            writeString(output, payload.messageId, MAX_MESSAGE_ID_BYTES);
            writeString(output, payload.mode, MAX_MODE_BYTES);
            writeString(output, payload.viewPermission, MAX_PERMISSION_BYTES);
            writeString(output, payload.componentJson, MAX_COMPONENT_BYTES);
        }
        return stream.toByteArray();
    }

    private Payload decodeUnsigned(byte[] unsigned) throws Exception {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(unsigned))) {
            String version = readString(input, 8);
            long timestampMillis = input.readLong();
            String sourceServer = readString(input, MAX_SERVER_BYTES);
            String messageId = readString(input, MAX_MESSAGE_ID_BYTES);
            String mode = readString(input, MAX_MODE_BYTES);
            String viewPermission = readString(input, MAX_PERMISSION_BYTES);
            String componentJson = readString(input, MAX_COMPONENT_BYTES);
            if (input.available() != 0) {
                return null;
            }
            return new Payload(version, timestampMillis, sourceServer, messageId, mode, viewPermission, componentJson);
        }
    }

    private void writeString(DataOutputStream output, String value, int maxBytes) throws Exception {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) {
            throw new IllegalArgumentException("Proxy payload field is too large");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private String readString(DataInputStream input, int maxBytes) throws Exception {
        int length = input.readInt();
        if (length < 0 || length > maxBytes || length > input.available()) {
            throw new IllegalArgumentException("Invalid proxy payload field length");
        }
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private byte[] sign(byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private boolean isValid(Payload payload) {
        if (payload == null || !VERSION.equals(payload.version)) {
            return false;
        }
        if (!payload.sourceServer.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,23}")) {
            return false;
        }
        if (!payload.messageId.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,79}")) {
            return false;
        }
        if (!payload.mode.equals("GLOBAL")) {
            return false;
        }
        if (!payload.viewPermission.isEmpty() && !payload.viewPermission.matches("[A-Za-z0-9*_.-]{1,255}")) {
            return false;
        }
        return !payload.componentJson.isBlank()
                && payload.componentJson.getBytes(StandardCharsets.UTF_8).length <= MAX_COMPONENT_BYTES;
    }

    public static final class Payload {
        private final String version;
        private final long timestampMillis;
        private final String sourceServer;
        private final String messageId;
        private final String mode;
        private final String viewPermission;
        private final String componentJson;

        public Payload(
                String version,
                long timestampMillis,
                String sourceServer,
                String messageId,
                String mode,
                String viewPermission,
                String componentJson
        ) {
            this.version = version == null ? "" : version;
            this.timestampMillis = timestampMillis;
            this.sourceServer = sourceServer == null ? "" : sourceServer;
            this.messageId = messageId == null ? "" : messageId;
            this.mode = mode == null ? "" : mode;
            this.viewPermission = viewPermission == null ? "" : viewPermission;
            this.componentJson = componentJson == null ? "" : componentJson;
        }

        public String version() {
            return version;
        }

        public long timestampMillis() {
            return timestampMillis;
        }

        public String sourceServer() {
            return sourceServer;
        }

        public String messageId() {
            return messageId;
        }

        public String mode() {
            return mode;
        }

        public String viewPermission() {
            return viewPermission;
        }

        public String componentJson() {
            return componentJson;
        }
    }
}
