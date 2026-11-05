package com.nuvio.vpn.android;

import com.nuvio.vpn.core.ProtectionMode;
import com.nuvio.vpn.core.VpnPreferences;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.json.JSONException;
import org.json.JSONObject;

/** A shared default plus explicit overrides keyed by the host's Nuvio profile index. */
final class ProfileSelections {
    VpnPreferences shared;
    final Map<Integer, VpnPreferences> overrides = new TreeMap<>();

    ProfileSelections(VpnPreferences shared) { this.shared = shared; }

    VpnPreferences forUser(int user) throws IOException {
        if (user == 0 && !overrides.isEmpty()) throw new IOException("Waiting for the active Nuvio profile.");
        return overrides.getOrDefault(user, shared);
    }

    void forgetVpn(String id) {
        if (id.equals(shared.getSelectedProfileId())) shared = shared.withSelectedProfile(null);
        overrides.replaceAll((user, value) -> id.equals(value.getSelectedProfileId())
                ? value.withSelectedProfile(null) : value);
    }

    void checkProfiles(Set<String> ids) throws IOException {
        checkProfile(shared, ids);
        for (VpnPreferences value : overrides.values()) checkProfile(value, ids);
    }

    private static void checkProfile(VpnPreferences value, Set<String> ids) throws IOException {
        String id = value.getSelectedProfileId();
        if (id != null && !ids.contains(id)) throw new IOException("Selected VPN profile is missing.");
    }

    JSONObject writeTo(JSONObject target) throws JSONException {
        putPreference(target, shared);
        if (!overrides.isEmpty()) {
            JSONObject users = new JSONObject();
            for (Map.Entry<Integer, VpnPreferences> entry : overrides.entrySet())
                users.put(entry.getKey().toString(), putPreference(new JSONObject(), entry.getValue()));
            target.put("profileOverrides", users);
        }
        return target;
    }

    static ProfileSelections parse(JSONObject data) throws IOException {
        try {
            ProfileSelections settings = new ProfileSelections(preference(data));
            if (data.has("profileOverrides")) {
                JSONObject users = data.getJSONObject("profileOverrides");
                if (users.length() > 32) throw new IOException("Too many Nuvio profile overrides.");
                for (Iterator<String> it = users.keys(); it.hasNext();) {
                    String key = it.next();
                    int id = Integer.parseInt(key);
                    if (id < 1 || !Integer.toString(id).equals(key)) throw new IOException("Invalid Nuvio profile.");
                    settings.overrides.put(id, preference(users.getJSONObject(key)));
                }
            }
            return settings;
        } catch (JSONException | IllegalArgumentException e) { throw new IOException("Invalid VPN selections."); }
    }

    private static JSONObject putPreference(JSONObject data, VpnPreferences value) throws JSONException {
        return data.put("selected", value.getSelectedProfileId() == null ? JSONObject.NULL : value.getSelectedProfileId())
                .put("scope", value.getMode().name());
    }

    private static VpnPreferences preference(JSONObject data) throws JSONException {
        Object id = data.get("selected");
        return new VpnPreferences(ProtectionMode.valueOf(data.getString("scope")), id == JSONObject.NULL ? null : data.getString("selected"));
    }
}
