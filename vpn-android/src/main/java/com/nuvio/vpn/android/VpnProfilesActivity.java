package com.nuvio.vpn.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import com.nuvio.vpn.core.ProfileImport;
import com.nuvio.vpn.core.ProfileEnvelope;
import com.nuvio.vpn.core.ProtectionMode;
import com.nuvio.vpn.core.VpnPreferences;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Module-owned screen: importing a profile does not enable protection or start a tunnel. */
public final class VpnProfilesActivity extends Activity {
    private static final int IMPORT_OPENVPN = 201;
    private static final int IMPORT_WIREGUARD = 202;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayList<Button> actions = new ArrayList<>();
    private AndroidProfileStore store;
    private AndroidVpnSettings settings;
    private RadioGroup scope, audience;
    private int allProfilesId, onlyProfileId;
    private ProtectionMode savedMode;
    private String selectedProfileId;
    private boolean needsCredentials;
    private int offId;
    private int httpId;
    private int httpsId;
    private int appId;
    private int persistentActionCount;
    private LinearLayout profiles;
    private TextView status;
    private boolean busy;
    private VpnAppearance appearance;
    private VpnSettingsViews ui;
    private View previousFocus;
    private Object previousFocusTag;
    private TextView connectionTitle;
    private TextView connectionDetail;
    private TextView connectionTraffic;
    private Button connectionAction;
    private final android.os.Handler connectionHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable connectionRefresh = new Runnable() {
        @Override public void run() {
            renderConnection();
            connectionHandler.postDelayed(this, 500);
        }
    };

    public static void open(Context context) {
        open(context, null);
    }

    public static void open(Context context, VpnAppearance appearance) {
        Intent intent = new Intent(context, VpnProfilesActivity.class);
        if (appearance != null) appearance.putInto(intent);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        appearance = VpnAppearance.from(this, getIntent());
        ui = new VpnSettingsViews(this, appearance);
        getWindow().setStatusBarColor(appearance.color(0));
        getWindow().setNavigationBarColor(appearance.color(0));
        getWindow().getDecorView().setSystemUiVisibility(0);
        event("screen_opened");
        store = new AndroidProfileStore(getApplicationContext());
        settings = new AndroidVpnSettings(getApplicationContext(), VpnPlaybackProtection.activeUserProfile());
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(appearance.color(0));
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        LinearLayout content = new LinearLayout(this) {
            @Override protected void onMeasure(int width, int height) {
                int bounded = Math.min(MeasureSpec.getSize(width), dp(appearance.television() ? 960 : 720));
                super.onMeasure(MeasureSpec.makeMeasureSpec(bounded, MeasureSpec.EXACTLY), height);
            }
        };
        content.setOrientation(LinearLayout.VERTICAL);
        int margin = dp(appearance.television() ? 40 : 20);
        content.setPadding(margin, dp(appearance.television() ? 24 : 12), margin, dp(28));
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        FrameLayout frame = new FrameLayout(this);
        frame.addView(content, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        scroll.addView(frame);
        setContentView(scroll);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = new ImageButton(this);
        back.setImageDrawable(ui.icon(R.drawable.vpn_back, 3));
        back.setBackground(ui.controlBackground());
        back.setContentDescription(getString(R.string.vpn_profiles_back));
        back.setOnClickListener(view -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView heading = ui.text(getString(R.string.vpn_profiles_title), appearance.heading(), 3);
        heading.setTypeface(ui.font, android.graphics.Typeface.BOLD);
        heading.setPadding(dp(12), 0, 0, 0);
        if (android.os.Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(header);
        ui.gap(content, 20);
        LinearLayout notice = ui.card();
        connectionTitle = ui.title(getString(R.string.vpn_connection_unavailable));
        connectionDetail = ui.paragraph(getString(R.string.vpn_select_wireguard_profile));
        connectionTraffic = ui.paragraph("");
        connectionTraffic.setVisibility(View.GONE);
        connectionAction = ui.action(getString(R.string.vpn_turn_off), null);
        connectionAction.setOnClickListener(view -> connectionAction());
        notice.addView(connectionTitle);
        notice.addView(connectionDetail);
        notice.addView(connectionTraffic);
        notice.addView(connectionAction);
        content.addView(notice);
        ui.section(content, R.string.vpn_apply_title);
        LinearLayout audienceCard = ui.card();
        audience = new RadioGroup(this);
        audience.setSaveEnabled(false);
        allProfilesId = option(audience, R.string.vpn_apply_all, R.string.vpn_apply_all_detail);
        onlyProfileId = option(audience, R.string.vpn_apply_one, R.string.vpn_apply_one_detail);
        audience.setOnCheckedChangeListener((group, checkedId) -> {
            if (busy || checkedId == -1) return;
            setBusy(true);
            worker.execute(() -> {
                int outcome = R.string.vpn_scope_saved;
                try {
                    settings.setShared(checkedId == allProfilesId);
                    VpnConnectionService.settingsChanged(this);
                } catch (IOException failure) { outcome = R.string.vpn_scope_failed; }
                final int message = outcome;
                showResult(() -> refresh(message));
            });
        });
        audienceCard.addView(audience);
        content.addView(audienceCard);
        ui.section(content, R.string.vpn_scope_title);
        LinearLayout scopeCard = ui.card();
        scope = new RadioGroup(this);
        scope.setOrientation(RadioGroup.VERTICAL);
        scope.setSaveEnabled(false);
        offId = option(scope, R.string.vpn_scope_off, R.string.vpn_scope_off_detail);
        httpId = option(scope, R.string.vpn_scope_http, R.string.vpn_scope_http_detail);
        httpsId = option(scope, R.string.vpn_scope_http_https, R.string.vpn_scope_http_https_detail);
        appId = option(scope, R.string.vpn_scope_app, R.string.vpn_scope_app_detail);
        scope.setOnCheckedChangeListener((group, checkedId) -> {
            if (busy || checkedId == -1) return;
            ProtectionMode mode = checkedId == appId ? ProtectionMode.APP_WIDE
                    : checkedId == httpsId ? ProtectionMode.HTTP_HTTPS
                    : checkedId == httpId ? ProtectionMode.HTTP_ONLY : ProtectionMode.OFF;
            setBusy(true);
            worker.execute(() -> {
                try {
                    settings.saveMode(mode);
                    VpnConnectionService.settingsChanged(this);
                    event("scope_intent_saved: " + mode.name());
                    showResult(() -> { savedMode = mode; setBusy(false); status.setText(R.string.vpn_scope_saved); });
                } catch (Exception e) {
                    event("scope_save_failed: " + e.getClass().getSimpleName());
                    showResult(() -> {
                        if (savedMode == null) scope.clearCheck(); else scope.check(idForMode(savedMode));
                        setBusy(false);
                        status.setText(R.string.vpn_scope_failed);
                    });
                }
            });
        });
        scopeCard.addView(scope);
        content.addView(scopeCard);
        ui.section(content, R.string.vpn_import_title);
        LinearLayout imports = ui.card();
        imports.addView(button(R.string.vpn_wireguard_title, R.string.vpn_wireguard_detail, () -> choose(IMPORT_WIREGUARD)));
        ui.divider(imports);
        imports.addView(button(R.string.vpn_openvpn_title, R.string.vpn_openvpn_detail, () -> choose(IMPORT_OPENVPN)));
        content.addView(imports);
        content.addView(ui.paragraph(getString(R.string.vpn_connection_limits)));
        persistentActionCount = actions.size();
        status = ui.paragraph("");
        status.setVisibility(View.GONE);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                status.setVisibility(s.length() == 0 ? View.GONE : View.VISIBLE);
            }
            @Override public void afterTextChanged(Editable value) {}
        });
        content.addView(status);
        ui.section(content, R.string.vpn_saved_profiles_title);
        profiles = new LinearLayout(this);
        profiles.setOrientation(LinearLayout.VERTICAL);
        content.addView(profiles);
        TextView storage = ui.paragraph(getString(R.string.vpn_profiles_storage_notice));
        storage.setTextSize(appearance.body() - 1);
        content.addView(storage);
        refresh(0);
    }

    private void choose(int request) {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, request);
            event("picker_opened: " + (request == IMPORT_OPENVPN ? "OPENVPN" : "WIREGUARD"));
        }
        catch (ActivityNotFoundException e) {
            event("document_picker_unavailable");
            status.setText(R.string.vpn_profiles_no_picker);
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (request != IMPORT_OPENVPN && request != IMPORT_WIREGUARD) return;
        Uri uri = data.getData();
        if (!"content".equalsIgnoreCase(uri.getScheme())) {
            status.setText(R.string.vpn_profiles_import_failed);
            return;
        }
        ProfileImport.Protocol protocol = request == IMPORT_OPENVPN
                ? ProfileImport.Protocol.OPENVPN : ProfileImport.Protocol.WIREGUARD;
        setBusy(true);
        status.setText(R.string.vpn_profiles_importing);
        worker.execute(() -> {
            int message = R.string.vpn_profiles_imported;
            String needsCredentials = null;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("Configuration is unavailable.");
                try (ProfileImport.Candidate candidate = ProfileImport.read(input, protocol)) {
                    if (protocol == ProfileImport.Protocol.WIREGUARD) WireGuardProfiles.parse(candidate);
                    String id = java.util.UUID.randomUUID().toString();
                    if (protocol == ProfileImport.Protocol.OPENVPN && OpenVpnSession.evaluate(candidate.copyContents()) == 2)
                        needsCredentials = id;
                    store.save(id, displayName(uri, protocol), candidate);
                    event("import_saved: " + protocol.name());
                }
                // Keep no URI grant, plaintext copy, or persisted document reference.
            } catch (Exception | LinkageError e) {
                event("import_failed: " + protocol.name() + "; " + e.getClass().getSimpleName());
                message = R.string.vpn_profiles_import_failed;
            }
            final int notice = message;
            final String prompt = needsCredentials;
            showResult(() -> {
                setBusy(false);
                refresh(notice);
                if (notice == R.string.vpn_profiles_imported && prompt != null) editCredentials(prompt);
            });
        });
    }

    private void editCredentials(String id) {
        worker.execute(() -> {
            try {
                byte[] revision = store.revision(id);
                showResult(() -> {
                    LinearLayout fields = ui.column();
                    fields.setPadding(dp(20), dp(8), dp(20), 0);
                    EditText username = new EditText(this);
                    username.setSingleLine(); username.setHint(R.string.vpn_service_username);
                    EditText password = new EditText(this);
                    password.setSingleLine();
                    password.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
                    password.setHint(R.string.vpn_service_password);
                    username.setSaveEnabled(false); password.setSaveEnabled(false);
                    fields.addView(username); fields.addView(password);
                    AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.vpn_service_credentials)
                            .setView(fields).setNegativeButton(android.R.string.cancel,null)
                            .setPositiveButton(R.string.vpn_save_credentials,(ignored,which) -> {
                                String user = username.getText().toString(), secret = password.getText().toString();
                                setBusy(true);
                                worker.execute(() -> {
                                    int message;
                                    try { VpnBackup.updateCredentials(this,id,revision,user,secret); message = R.string.vpn_credentials_saved; }
                                    catch (Exception | LinkageError failure) { message = R.string.vpn_credentials_failed; }
                                    int notice = message;
                                    showResult(() -> { setBusy(false); refresh(notice); });
                                });
                            }).create();
                    dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
                    dialog.setOnDismissListener(ignored -> { username.getText().clear(); password.getText().clear(); });
                    dialog.show();
                });
            } catch (Exception changed) { showResult(() -> status.setText(R.string.vpn_credentials_failed)); }
        });
    }

    private String displayName(Uri uri, ProfileImport.Protocol protocol) {
        String name = null;
        try (Cursor cursor = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        } catch (Exception ignored) { /* Display names are optional/untrusted metadata. */ }
        if (name != null) {
            name = name.replaceFirst("(?i)\\.(ovpn|conf)$", "")
                    .replaceAll("[^\\p{L}\\p{N} ._()-]", "_").trim();
            if (name.length() > 60) name = name.substring(0, 60);
        }
        return name == null || name.isEmpty() ? (protocol == ProfileImport.Protocol.OPENVPN ? "OpenVPN" : "WireGuard") : name;
    }

    private void refresh(int notice) {
        setBusy(true);
        worker.execute(() -> {
            try {
                List<AndroidProfileStore.Entry> entries = store.list();
                VpnPreferences preferences = settings.read();
                String selected = preferences.getSelectedProfileId();
                boolean available = selected == null;
                for (AndroidProfileStore.Entry entry : entries) {
                    if (entry.readable && entry.id.equals(selected)) available = true;
                }
                if (!available) {
                    // Never silently switch to another profile after loss, corruption or key invalidation.
                    settings.selectProfile(null);
                    VpnConnectionService.settingsChanged(this);
                    event("unavailable_selection_cleared; scope_off");
                    preferences = settings.read();
                }
                final VpnPreferences snapshot = preferences;
                final boolean shared = !settings.snapshot().overrides.containsKey(VpnPlaybackProtection.activeUserProfile());
                final int message = available ? notice : R.string.vpn_profiles_selection_unavailable;
                showResult(() -> {
                    savedMode = snapshot.getMode();
                    selectedProfileId = snapshot.getSelectedProfileId();
                    needsCredentials = false;
                    for (AndroidProfileStore.Entry entry : entries) if (entry.id.equals(selectedProfileId))
                        needsCredentials = entry.login == OpenVpnCredentials.Login.REQUIRED;
                    audience.check(shared ? allProfilesId : onlyProfileId);
                    scope.check(idForMode(savedMode));
                    profiles.removeAllViews();
                    while (actions.size() > persistentActionCount) actions.remove(actions.size() - 1);
                    if (entries.isEmpty()) {
                        LinearLayout empty = ui.card();
                        empty.addView(ui.title(getString(R.string.vpn_profiles_empty)));
                        empty.addView(ui.paragraph(getString(R.string.vpn_profiles_empty_detail)));
                        profiles.addView(empty);
                    }
                    for (AndroidProfileStore.Entry entry : entries) {
                        LinearLayout row = ui.card();
                        String label = entry.readable ? entry.name : getString(R.string.vpn_profiles_unreadable);
                        TextView profileTitle = ui.title(label);
                        row.addView(profileTitle);
                        if (entry.readable) row.addView(ui.paragraph(getString(R.string.vpn_profiles_pending,
                                entry.protocol == ProfileImport.Protocol.OPENVPN ? "OpenVPN" : "WireGuard")));
                        if (entry.readable) {
                            boolean selectedEntry = entry.id.equals(selectedProfileId);
                            if (selectedEntry) {
                                TextView selectedLabel = ui.paragraph(getString(R.string.vpn_profiles_selected));
                                selectedLabel.setTextColor(appearance.color(5));
                                row.addView(selectedLabel);
                            }
                            if (entry.login != OpenVpnCredentials.Login.NONE) {
                                Button credentials = button(R.string.vpn_service_credentials, () -> editCredentials(entry.id));
                                credentials.setTag("credentials:" + entry.id);
                                row.addView(credentials);
                            }
                            Button select = button(selectedEntry ? R.string.vpn_profiles_clear_selection : R.string.vpn_profiles_select,
                                    () -> selectProfile(selectedEntry ? null : entry));
                            select.setContentDescription(getString(selectedEntry ? R.string.vpn_profiles_clear_named
                                    : R.string.vpn_profiles_select_named, label));
                            select.setTag("select:" + entry.id);
                            row.addView(select);
                        }
                        ui.divider(row);
                        Button remove = button(R.string.vpn_profiles_remove, () -> confirmDelete(entry));
                        remove.setTextColor(appearance.color(9));
                        remove.setContentDescription(getString(R.string.vpn_profiles_remove_named, label));
                        remove.setTag("remove:" + entry.id);
                        row.addView(remove);
                        profiles.addView(row);
                        ui.gap(profiles, 10);
                    }
                    setBusy(false);
                    status.setText(message == 0 ? "" : getString(message));
                    if (getCurrentFocus() == null) actions.get(0).requestFocus();
                });
            } catch (Exception e) {
                event("storage_read_failed: " + e.getClass().getSimpleName());
                showResult(() -> { setBusy(false); status.setText(R.string.vpn_profiles_storage_failed); });
            }
        });
    }

    private void selectProfile(AndroidProfileStore.Entry entry) {
        if (busy) return;
        setBusy(true);
        worker.execute(() -> {
            int message = R.string.vpn_profiles_selection_saved;
            try {
                if (entry == null) settings.selectProfile(null);
                else {
                    // Re-authenticate storage immediately before selecting; the list may be stale.
                    try (ProfileEnvelope.Opened opened = store.open(entry.id)) {
                        if (opened.getProtocol() != entry.protocol) throw new IOException("Profile changed.");
                        settings.selectProfile(entry.id);
                    }
                }
                VpnConnectionService.settingsChanged(this);
                event(entry == null ? "selection_cleared; scope_off" : "selection_saved");
            } catch (Exception e) {
                event("selection_failed: " + e.getClass().getSimpleName());
                message = R.string.vpn_profiles_selection_failed;
            }
            final int notice = message;
            showResult(() -> { setBusy(false); refresh(notice); });
        });
    }

    private void confirmDelete(AndroidProfileStore.Entry entry) {
        if (busy) return;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setCustomTitle(ui.title(getString(R.string.vpn_profiles_remove_question)))
                .setMessage(entry.readable ? entry.name : getString(R.string.vpn_profiles_unreadable))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.vpn_profiles_remove, (confirmation, which) -> {
                    setBusy(true);
                    worker.execute(() -> {
                        int message = R.string.vpn_profiles_removed;
                        try {
                            // Clear the reference first so an interrupted deletion cannot leave a selected target.
                            settings.forgetVpn(entry.id);
                            VpnConnectionService.settingsChanged(this);
                            store.delete(entry.id);
                            event("profile_removed");
                        }
                        catch (Exception e) {
                            event("profile_removal_failed: " + e.getClass().getSimpleName());
                            message = R.string.vpn_profiles_remove_failed;
                        }
                        final int notice = message;
                        showResult(() -> { setBusy(false); refresh(notice); });
                    });
                }).create();
        dialog.setOnShowListener(ignored -> {
            if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(
                    ui.shape(appearance.color(1), appearance.color(2), appearance.cardRadius(), .5f));
            TextView message = dialog.findViewById(android.R.id.message);
            if (message != null) { message.setTextColor(appearance.color(4)); message.setTypeface(ui.font); }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(appearance.color(3));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(appearance.color(9));
        });
        dialog.show();
    }

    private Button button(int label, Runnable action) { return button(label, 0, action); }

    private Button button(int label, int detail, Runnable action) {
        Button button = ui.action(getString(label), detail == 0 ? null : getString(detail));
        button.setEnabled(!busy);
        button.setOnClickListener(view -> { if (!busy) action.run(); });
        actions.add(button);
        return button;
    }

    private int option(RadioGroup group, int label, int detail) {
        RadioButton button = ui.option(getString(label), getString(detail));
        button.setId(View.generateViewId());
        button.setSaveEnabled(false);
        actions.add(button);
        if (group.getChildCount() != 0) ui.divider(group);
        group.addView(button);
        return button.getId();
    }

    private int idForMode(ProtectionMode mode) {
        return mode == ProtectionMode.APP_WIDE ? appId : mode == ProtectionMode.HTTP_HTTPS ? httpsId : mode == ProtectionMode.HTTP_ONLY ? httpId : offId;
    }

    private void setBusy(boolean value) {
        if (value && !busy) {
            previousFocus = getCurrentFocus();
            previousFocusTag = previousFocus == null ? null : previousFocus.getTag();
        }
        busy = value;
        for (Button button : actions) { button.setEnabled(!value); button.setAlpha(value ? .5f : 1f); }
        renderConnection();
        if (!value && appearance.television() && previousFocus != null && previousFocus.isAttachedToWindow())
            previousFocus.requestFocus();
        else if (!value && appearance.television() && previousFocusTag != null) {
            for (Button button : actions) if (previousFocusTag.equals(button.getTag())) { button.requestFocus(); break; }
        }
    }

    private void showResult(Runnable action) {
        runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) action.run(); });
    }

    private void connectionAction() {
        if (busy) return;
        if (savedMode == ProtectionMode.OFF) VpnConnectionService.disconnect(this);
        else scope.check(offId);
    }

    private void renderConnection() {
        if (connectionTitle == null) return;
        VpnConnectionService.Snapshot state = VpnConnectionService.snapshot();
        boolean active = VpnConnectionService.isRunning();
        boolean verifying = active && state.phase != VpnConnectionService.Phase.STANDBY;
        int title = state.phase == VpnConnectionService.Phase.CONNECTED
                ? (state.mode.isPlaybackScoped() ? (state.mode == ProtectionMode.HTTP_HTTPS ? R.string.vpn_connected_web : R.string.vpn_connected_http) : R.string.vpn_connected_app)
                : state.phase == VpnConnectionService.Phase.CONNECTING ? R.string.vpn_connecting
                : state.phase == VpnConnectionService.Phase.WAITING ? R.string.vpn_waiting_handshake
                : state.phase == VpnConnectionService.Phase.ERROR ? R.string.vpn_connection_failed
                : R.string.vpn_connection_unavailable;
        connectionTitle.setText(title);
        int detail = state.phase == VpnConnectionService.Phase.CONNECTED
                ? (state.mode.isPlaybackScoped() ? (state.mode == ProtectionMode.HTTP_HTTPS ? R.string.vpn_active_web_detail : R.string.vpn_active_http_detail) : R.string.vpn_active_app_detail)
                : active && state.authRejected ? R.string.vpn_auth_rejected
                : active && state.phase == VpnConnectionService.Phase.ERROR ? R.string.vpn_connection_error_detail
                : verifying ? R.string.vpn_verification_pending
                : selectedProfileId == null ? R.string.vpn_select_wireguard_profile
                : needsCredentials ? R.string.vpn_credentials_required
                : savedMode == ProtectionMode.OFF ? R.string.vpn_requires_app_scope
                : savedMode.isPlaybackScoped() ? (savedMode == ProtectionMode.HTTP_HTTPS ? R.string.vpn_ready_web : R.string.vpn_ready_http) : R.string.vpn_ready_to_connect;
        connectionDetail.setText(detail);
        boolean canTurnOff = active || (savedMode != null && savedMode != ProtectionMode.OFF);
        connectionAction.setVisibility(canTurnOff ? View.VISIBLE : View.GONE);
        connectionAction.setEnabled(!busy && canTurnOff);
        connectionAction.setAlpha(busy ? .4f : 1f);
        connectionTraffic.setVisibility(active ? View.VISIBLE : View.GONE);
        if (active) {
            String traffic = getString(R.string.vpn_connection_traffic,
                    android.text.format.Formatter.formatShortFileSize(this, state.received),
                    android.text.format.Formatter.formatShortFileSize(this, state.sent));
            connectionTraffic.setText(state.egress == null ? traffic : traffic + "\n"
                    + getString(R.string.vpn_egress_address, state.egress));
        }
    }

    @Override protected void onResume() {
        super.onResume();
        connectionHandler.post(connectionRefresh);
    }
    @Override protected void onPause() {
        connectionHandler.removeCallbacks(connectionRefresh);
        super.onPause();
    }

    private void event(String event) {
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
            android.util.Log.i("NuvioVpn", event);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        connectionHandler.removeCallbacks(connectionRefresh);
        worker.shutdown();
        super.onDestroy();
    }
}
