package dev.minelink.velocity.relay;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProfileValidatorTest {

    private static byte[] readFixture() throws IOException {
        try (InputStream in = ProfileValidatorTest.class.getResourceAsStream("/notch-profile-signed.json")) {
            if (in == null) {
                throw new IllegalStateException("Missing notch-profile-signed.json test fixture");
            }
            return in.readAllBytes();
        }
    }

    @Test
    void acceptsRealSignedProfileAndExtraFutureFields() throws IOException {
        String notch = new String(readFixture(), StandardCharsets.UTF_8).trim();
        String withExtra = notch.substring(0, notch.length() - 1)
            + ",\n  \"futureField\" : {\"x\": 1}\n}";
        assertTrue(ProfileValidator.isValid(withExtra.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void acceptsProfileWithLeadingUtf8Bom() throws IOException {
        byte[] raw = readFixture();
        byte[] withBom = new byte[raw.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(raw, 0, withBom, 3, raw.length);

        assertTrue(ProfileValidator.isValid(withBom));
    }

    @Test
    void acceptsUnsignedPropertiesAbsentOrNullSignature() {
        for (String prop : List.of("{\"name\":\"t\",\"value\":\"v\"}", "{\"name\":\"t\",\"value\":\"v\",\"signature\":null}")) {
            String json = "{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Notch\",\"properties\":[" + prop + "]}";
            assertTrue(ProfileValidator.isValid(json.getBytes(StandardCharsets.UTF_8)), prop);
        }
    }

    @Test
    void rejectsMalformedOrVelocityIncompatibleProfiles() {
        List<String> invalid = List.of(
            "",
            "<html>challenge</html>",
            "[]",
            "{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"properties\":[]}",
            "{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"\",\"properties\":[]}",
            "{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"" + "a".repeat(65) + "\",\"properties\":[]}",
            "{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":42,\"properties\":[]}",
            "{\"id\":\"069a79f4-44e9-4726-a5be-fca90e38aaf5\",\"name\":\"Notch\",\"properties\":[]}",
            "{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Notch\"}",
            "{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Notch\",\"properties\":[null]}",
            "{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Notch\",\"properties\":[{\"name\":\"textures\"}]}",
            "{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Notch\",\"properties\":[{\"name\":\"t\",\"value\":\"v\",\"signature\":5}]}"
        );

        for (String body : invalid) {
            assertFalse(ProfileValidator.isValid(body.getBytes(StandardCharsets.UTF_8)), "Expected invalid: " + body);
        }
    }
}
