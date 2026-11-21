package dev.minelink.velocity.relay;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

public final class ProfileValidator {

    // Mojang's session server uses an undashed 32-hex UUID. Velocity's GameProfile parser rejects any other length.
    private static final Pattern UNDASHED_UUID = Pattern.compile("^[0-9a-fA-F]{32}$");

    private ProfileValidator() {}

    public static boolean isValid(byte[] rawBytes) {
        if (rawBytes == null || rawBytes.length == 0) {
            return false;
        }
        String decoded = new String(rawBytes, StandardCharsets.UTF_8);
        if (!decoded.isEmpty() && decoded.charAt(0) == '\uFEFF') {
            decoded = decoded.substring(1);
        }

        JsonElement root;
        try {
            root = JsonParser.parseString(decoded);
        } catch (JsonParseException | IllegalStateException e) {
            return false;
        }
        if (root == null || !root.isJsonObject()) {
            return false;
        }

        JsonObject obj = root.getAsJsonObject();
        String id = strictString(obj.get("id"));
        if (id == null || !UNDASHED_UUID.matcher(id).matches()) {
            return false;
        }

        String name = strictString(obj.get("name"));
        if (name == null || name.isEmpty() || name.length() > 64) {
            return false;
        }

        JsonElement properties = obj.get("properties");
        if (properties == null || !properties.isJsonArray()) {
            return false;
        }
        for (JsonElement element : properties.getAsJsonArray()) {
            if (!isValidProperty(element)) {
                return false;
            }
        }
        return true;
    }

    // Velocity copies properties into an ImmutableList (no nulls) and requires string name and value.
    // ProtocolUtils.writeProperties writes "unsigned" for both an absent signature and a null one.
    private static boolean isValidProperty(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return false;
        }
        JsonObject prop = element.getAsJsonObject();
        if (strictString(prop.get("name")) == null || strictString(prop.get("value")) == null) {
            return false;
        }
        JsonElement sig = prop.get("signature");
        return sig == null || sig.isJsonNull() || strictString(sig) != null;
    }

    private static String strictString(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        var prim = element.getAsJsonPrimitive();
        return prim.isString() ? prim.getAsString() : null;
    }
}
