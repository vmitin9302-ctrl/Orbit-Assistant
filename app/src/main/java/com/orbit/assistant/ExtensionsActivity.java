package com.orbit.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Orbit-native manager and SAF installer for declarative .orbitext packages. */
public final class ExtensionsActivity extends Activity {
    private static final String LOG_TAG = "OrbitExtensions";
    private static final int REQ_IMPORT_EXTENSION = 7010;
    private static final String FIRST_PARTY_ASSET_DIR = "orbit-extensions/";
    private static final String[] FIRST_PARTY_ASSETS = {
            "orbit-web-tools.orbitext",
            "developer-tools.orbitext",
            "quick-links.orbitext",
            "discord-webhook.orbitext",
            "ntfy-notifications.orbitext"
    };
    private ScrollView activeScroll;
    private int bundledLoadFailures;

    /** Interactive Back for this page. Its classification lives in OrbitNavigation. */
    private OrbitPredictiveBack navigation;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.syncTheme(this);
        navigation = OrbitPredictiveBack.install(this);
        Window window = getWindow();
        window.setStatusBarColor(UiKit.BG);
        window.setNavigationBarColor(UiKit.BG);
        // Re-establish the v0.7.0.2 manager shell before any v2 parser or
        // secure-configuration code can participate in Activity startup.
        try {
            renderContent(false, -1);
            window.getDecorView().post(() -> renderFullContent(-1));
        } catch (RuntimeException | LinkageError error) {
            showRecovery(error);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        UiPresence.enter(this);
    }

    @Override protected void onPause() {
        UiPresence.leave(this);
        super.onPause();
    }

    private void rebuild() {
        renderFullContent(-1);
    }

    private void rebuildPreservingPosition() {
        renderFullContent(activeScroll == null ? 0 : activeScroll.getScrollY());
    }

    private void renderFullContent(int requestedScrollY) {
        try {
            renderContent(true, requestedScrollY);
        } catch (RuntimeException | LinkageError error) {
            logFailure("screen_render", error);
            showRecovery(error);
        }
    }

    private void renderContent(boolean includeV2, int requestedScrollY) {
        View content = buildContent(includeV2);
        setContentView(content);
        UiKit.applyActivityInsets(this, content, true);
        if (requestedScrollY >= 0 && activeScroll != null) {
            ScrollView target = activeScroll;
            ViewTreeObserver.OnGlobalLayoutListener listener = new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() {
                    ViewTreeObserver observer = target.getViewTreeObserver();
                    if (observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
                    int childHeight = target.getChildCount() == 0 ? 0 : target.getChildAt(0).getHeight();
                    int maximum = Math.max(0, childHeight - target.getHeight());
                    target.scrollTo(0, Math.min(requestedScrollY, maximum));
                }
            };
            target.getViewTreeObserver().addOnGlobalLayoutListener(listener);
        }
    }

    private View buildContent(boolean includeV2) {
        ScrollView scroll = new ScrollView(this);
        activeScroll = scroll;
        scroll.setFillViewport(true);
        OrbitBackground.applyPage(scroll);
        scroll.setForceDarkAllowed(false);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int padding = UiKit.dp(this, 20);
        page.setPadding(padding, UiKit.dp(this, 30), padding, UiKit.dp(this, 48));
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.ic_back);
        back.setImageTintList(ColorStateList.valueOf(UiKit.accent(this)));
        back.setBackground(UiKit.ripple(UiKit.SURFACE_2, UiKit.accent(this), 18, this));
        back.setContentDescription(getString(R.string.ui_back_to_settings_ea37ec29));
        back.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 10),
                UiKit.dp(this, 10), UiKit.dp(this, 10));
        back.setOnClickListener(v -> navigation.performBack());
        UiKit.pressScale(back);
        header.addView(back, new LinearLayout.LayoutParams(UiKit.dp(this, 44), UiKit.dp(this, 44)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(UiKit.dp(this, 13), 0, 0, 0);
        titles.addView(UiKit.text(this, getString(R.string.ext_title), 25, UiKit.TEXT, true));
        titles.addView(UiKit.text(this, getString(R.string.ext_subtitle), 12, UiKit.MUTED, false));
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(header);

        TextView intro = UiKit.text(this,
                getString(R.string.ext_intro),
                13, UiKit.MUTED, false);
        intro.setLineSpacing(0, 1.13f);
        LinearLayout.LayoutParams introLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        introLp.setMargins(UiKit.dp(this, 2), UiKit.dp(this, 17), UiKit.dp(this, 2), UiKit.dp(this, 14));
        page.addView(intro, introLp);

        OrbitExtensionStore.ManagerSnapshot snapshot =
                OrbitExtensionStore.managerSnapshot(this, includeV2);

        page.addView(sectionHeading(getString(R.string.ext_first_party_header)));
        List<OrbitExtension> firstParty = loadFirstPartyExtensions(includeV2);
        for (int i = 0; i < firstParty.size(); i++) {
            LinearLayout.LayoutParams lp = cardSpacing(i > 0);
            try {
                OrbitExtension extension = firstParty.get(i);
                page.addView(firstPartyCard(extension,
                        findInstalled(snapshot, extension.id)), lp);
            } catch (RuntimeException | LinkageError e) {
                bundledLoadFailures++;
                logFailure("first_party_card", e);
            }
        }
        if (bundledLoadFailures > 0) {
            LinearLayout unavailable = card();
            unavailable.addView(UiKit.text(this,
                    getResources().getQuantityString(R.plurals.ext_official_unavailable,
                            bundledLoadFailures, bundledLoadFailures),
                    13, UiKit.TEXT, true));
            page.addView(unavailable, cardSpacing(!firstParty.isEmpty()));
        }

        page.addView(sectionHeading(getString(R.string.ext_import_header)));
        LinearLayout importCard = card();
        importCard.addView(UiKit.text(this, getString(R.string.ext_import_title), 16, UiKit.TEXT, true));
        TextView importNote = UiKit.text(this,
                getString(R.string.ext_import_note),
                12, UiKit.MUTED, false);
        importNote.setLineSpacing(0, 1.12f);
        importNote.setPadding(0, UiKit.dp(this, 5), 0, UiKit.dp(this, 11));
        importCard.addView(importNote);
        Button install = secondaryButton(getString(R.string.ext_import_title));
        install.setContentDescription(getString(R.string.ext_import_desc));
        install.setOnClickListener(v -> chooseExtension());
        importCard.addView(install, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 44)));
        page.addView(importCard);

        page.addView(sectionHeading(getString(R.string.ext_installed_header)));

        if (snapshot.entries.isEmpty() && snapshot.unreadableStoreToken == null) {
            LinearLayout empty = card();
            empty.addView(UiKit.text(this, getString(R.string.ext_none_installed), 16, UiKit.TEXT, true));
            TextView note = UiKit.text(this,
                    getString(R.string.ext_none_note),
                    12, UiKit.MUTED, false);
            note.setPadding(0, UiKit.dp(this, 5), 0, 0);
            empty.addView(note);
            page.addView(empty);
        } else {
            int rendered = 0;
            for (OrbitExtensionStore.ManagerEntry entry : snapshot.entries) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (rendered++ > 0) lp.topMargin = UiKit.dp(this, 10);
                if (entry.isUnavailable()) {
                    page.addView(unavailableExtensionCard(
                            entry.removalToken, false, entry.displayId), lp);
                    continue;
                }
                try {
                    page.addView(extensionCard(entry.installed), lp);
                } catch (RuntimeException | LinkageError e) {
                    logFailure("installed_card", e);
                    page.addView(unavailableExtensionCard(
                            entry.installed.extension.id, true,
                            entry.installed.extension.id), lp);
                }
            }
            if (snapshot.unreadableStoreToken != null) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (rendered > 0) lp.topMargin = UiKit.dp(this, 10);
                page.addView(unavailableExtensionCard(
                        snapshot.unreadableStoreToken, false, ""), lp);
            }
        }

        TextView footer = UiKit.text(this,
                getString(R.string.ext_footer),
                11, UiKit.MUTED, false);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(UiKit.dp(this, 6), UiKit.dp(this, 17), UiKit.dp(this, 6), 0);
        page.addView(footer);
        UiKit.applyTypography(page);
        return scroll;
    }

    private LinearLayout extensionCard(OrbitExtensionStore.Installed installed) {
        OrbitExtension extension = installed.extension;
        boolean requiresSetup = !extension.setupFields.isEmpty();
        LinearLayout card = card();

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.addView(UiKit.text(this, extension.name, 17, UiKit.TEXT, true));
        copy.addView(UiKit.text(this,
                "v" + extension.version + " · " + extension.author, 12, UiKit.MUTED, false));
        top.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        String stateText = !installed.enabled ? getString(R.string.ext_state_disabled) :
                requiresSetup ? getString(R.string.ext_state_needs_setup) : getString(R.string.ext_state_enabled);
        TextView state = UiKit.text(this, stateText, 11,
                installed.enabled ? UiKit.accent(this) : UiKit.MUTED, true);
        state.setLetterSpacing(0.08f);
        top.addView(state);
        card.addView(top);

        TextView description = UiKit.text(this, extension.description, 13, UiKit.MUTED, false);
        description.setLineSpacing(0, 1.12f);
        description.setPadding(0, UiKit.dp(this, 9), 0, UiKit.dp(this, 7));
        card.addView(description);
        for (OrbitExtension.Action action : extension.actions) {
            TextView actionView = UiKit.text(this,
                    "• " + action.name + " · " + action.capabilityLabel(),
                    12, UiKit.TEXT, false);
            actionView.setPadding(0, UiKit.dp(this, 3), 0, 0);
            card.addView(actionView);
        }

        if (requiresSetup) {
            Button configure = secondaryButton(getString(R.string.ext_setup_configure));
            configure.setContentDescription(getString(R.string.ext_setup_configure_desc, extension.name));
            configure.setOnClickListener(v -> showConfiguration(installed));
            LinearLayout.LayoutParams configureLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 44));
            configureLp.topMargin = UiKit.dp(this, 14);
            card.addView(configure, configureLp);
        }

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, extension.setupFields.isEmpty() ? UiKit.dp(this, 14) :
                UiKit.dp(this, 9), 0, 0);
        Button toggle = installed.enabled ? warningButton(getString(R.string.ext_disable)) : primaryButton(getString(R.string.ext_enable));
        toggle.setContentDescription(installed.enabled ? getString(R.string.ext_disable_desc, extension.name)
                : getString(R.string.ext_enable_desc, extension.name));
        toggle.setOnClickListener(v -> {
            if (OrbitExtensionStore.setEnabled(this, extension.id, !installed.enabled))
                rebuildPreservingPosition();
        });
        buttons.addView(toggle, new LinearLayout.LayoutParams(0, UiKit.dp(this, 44), 1));
        Button remove = destructiveButton(getString(R.string.ext_remove));
        remove.setContentDescription(getString(R.string.ext_remove_desc, extension.name));
        LinearLayout.LayoutParams removeLp = new LinearLayout.LayoutParams(0, UiKit.dp(this, 44), 1);
        removeLp.leftMargin = UiKit.dp(this, 9);
        buttons.addView(remove, removeLp);
        remove.setOnClickListener(v -> confirmRemove(extension));
        card.addView(buttons);
        return card;
    }

    private LinearLayout firstPartyCard(OrbitExtension extension,
                                        OrbitExtensionStore.Installed installed) {
        boolean requiresSetup = !extension.setupFields.isEmpty();
        LinearLayout card = card();
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.addView(UiKit.text(this, extension.name, 16, UiKit.TEXT, true));
        copy.addView(UiKit.text(this,
                "v" + extension.version + " · " + extension.author, 12, UiKit.MUTED, false));
        top.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        String stateText = installed == null ? getString(R.string.ext_state_not_installed) :
                !installed.enabled ? getString(R.string.ext_state_disabled)
                        : requiresSetup ? getString(R.string.ext_state_needs_setup)
                        : getString(R.string.ext_state_installed);
        TextView state = UiKit.text(this, stateText, 10,
                installed == null ? UiKit.MUTED : UiKit.accent(this), true);
        state.setLetterSpacing(0.07f);
        top.addView(state);
        card.addView(top);

        TextView description = UiKit.text(this, extension.description, 12, UiKit.MUTED, false);
        description.setLineSpacing(0, 1.12f);
        description.setPadding(0, UiKit.dp(this, 7), 0, UiKit.dp(this, 12));
        card.addView(description);

        Button action;
        if (installed == null) {
            action = primaryButton(getString(R.string.ext_install));
            action.setContentDescription(getString(R.string.ext_install_desc, extension.name));
            action.setOnClickListener(v -> showInstallReview(extension));
        } else {
            action = installedButton(!installed.enabled ? getString(R.string.ext_installed_disabled) :
                    requiresSetup ? getString(R.string.ext_installed_needs_setup)
                    : getString(R.string.ext_installed_enabled));
            action.setContentDescription(!installed.enabled
                    ? getString(R.string.ext_installed_desc_disabled, extension.name)
                    : requiresSetup ? getString(R.string.ext_installed_desc_needs_setup, extension.name)
                    : getString(R.string.ext_installed_desc_enabled, extension.name));
        }
        card.addView(action, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 44)));
        return card;
    }

    private void chooseExtension() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"application/json", "application/octet-stream", "text/plain"});
        startActivityForResult(intent, REQ_IMPORT_EXTENSION);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMPORT_EXTENSION || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            String displayName = displayName(uri);
            if (!displayName.toLowerCase(java.util.Locale.US).endsWith(".orbitext"))
                throw new IllegalArgumentException(getString(R.string.ext_err_file_type));
            OrbitExtension extension = OrbitExtension.parse(readManifest(uri));
            if (OrbitExtensionStore.find(this, extension.id) != null)
                throw new IllegalArgumentException(getString(R.string.ext_err_duplicate));
            showInstallReview(extension);
        } catch (Exception e) {
            showError(e.getMessage() == null ? getString(R.string.ext_err_read) : e.getMessage());
        }
    }

    private void showInstallReview(OrbitExtension extension) {
        LinearLayout review = new LinearLayout(this);
        review.setOrientation(LinearLayout.VERTICAL);
        review.setPadding(UiKit.dp(this, 22), UiKit.dp(this, 8),
                UiKit.dp(this, 22), UiKit.dp(this, 4));
        review.addView(UiKit.text(this, extension.name + " · v" + extension.version,
                17, UiKit.TEXT, true));
        review.addView(spacedText(getString(R.string.ext_review_by, extension.author), UiKit.MUTED));
        review.addView(spacedText(extension.description, UiKit.TEXT));

        if (!extension.setupFields.isEmpty()) {
            TextView setupTitle = spacedText(getString(R.string.ext_requires_setup), UiKit.MUTED);
            setupTitle.setLetterSpacing(0.1f);
            review.addView(setupTitle);
            for (OrbitExtension.SetupField field : extension.setupFields) {
                boolean urlKind = OrbitExtension.SETUP_SECRET_URL.equals(field.type) ||
                        OrbitExtension.SETUP_URL.equals(field.type);
                String kind = field.isSecret()
                        ? getString(urlKind ? R.string.ext_kind_secret_url : R.string.ext_kind_secret_text)
                        : getString(urlKind ? R.string.ext_kind_url : R.string.ext_kind_text);
                review.addView(spacedText(getString(field.required ? R.string.ext_field_required
                        : R.string.ext_field_optional, field.label, kind), UiKit.TEXT));
            }
            review.addView(spacedText(
                    getString(R.string.ext_install_first),
                    UiKit.TEXT));
        }

        TextView actionsTitle = spacedText(getString(R.string.ext_actions_being_added), UiKit.MUTED);
        actionsTitle.setLetterSpacing(0.1f);
        review.addView(actionsTitle);
        for (OrbitExtension.Action action : extension.actions) {
            String fixedBody = OrbitExtension.TYPE_HTTPS_REQUEST.equals(action.type) &&
                    "POST".equals(action.method)
                    ? "\n  " + getString(action.parameters.isEmpty() ? R.string.ext_json_body
                    : R.string.ext_json_body_templated,
                    action.body == null ? "{}" : action.body.toString()) : "";
            String parameters = action.parameters.isEmpty() ? "" :
                    "\n  " + getString(R.string.ext_routine_parameters, parameterLabels(action));
            String headers = action.headers.isEmpty() ? "" :
                    "\n  " + getString(R.string.ext_declared_headers, headerNames(action));
            review.addView(spacedText("• " + action.name + " — " + action.capabilityLabel() +
                    "\n  " + action.url + parameters + headers + fixedBody, UiKit.TEXT));
        }
        List<String> hosts = extension.contactedHosts();
        String destinations = hosts.isEmpty() ? getString(R.string.ext_no_fixed_hosts) :
                String.join("\n", hosts);
        if (extension.hasDynamicDestination())
            destinations += "\n" + getString(R.string.ext_user_destination);
        review.addView(spacedText(getString(R.string.ext_network_destinations, destinations), UiKit.MUTED));
        TextView isolation = spacedText(
                getString(R.string.ext_isolation),
                UiKit.TEXT);
        isolation.setBackground(UiKit.rounded(UiKit.SURFACE_2, 14, this));
        isolation.setPadding(UiKit.dp(this, 13), UiKit.dp(this, 11),
                UiKit.dp(this, 13), UiKit.dp(this, 11));
        review.addView(isolation);
        UiKit.applyTypography(review);

        ScrollView wrapper = new ScrollView(this);
        wrapper.addView(review);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.ext_review_title))
                .setView(wrapper)
                .setNegativeButton(getString(R.string.ui_cancel_19766ed6), null)
                .setPositiveButton(getString(R.string.ext_install), (d, w) -> {
                    if (OrbitExtensionStore.install(this, extension)) {
                        Toast.makeText(this, getString(R.string.ext_installed_toast, extension.name), Toast.LENGTH_SHORT).show();
                        rebuildPreservingPosition();
                    } else {
                        showError(getString(R.string.ext_install_failed));
                    }
                }).create();
        UiKit.styleOrbitDialog(dialog, this, false);
        dialog.show();
    }

    private void showConfiguration(OrbitExtensionStore.Installed installed) {
        OrbitExtension extension = installed.extension;
        boolean initiallyConfigured = isConfiguredSafely(installed);
        JSONObject saved = OrbitExtensionStore.configuration(this, extension.id);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(UiKit.dp(this, 22), UiKit.dp(this, 5),
                UiKit.dp(this, 22), UiKit.dp(this, 5));

        TextView note = UiKit.text(this,
                getString(R.string.ext_secret_note),
                12, UiKit.MUTED, false);
        note.setLineSpacing(0, 1.12f);
        note.setPadding(0, 0, 0, UiKit.dp(this, 8));
        form.addView(note);

        Map<String, EditText> inputs = new LinkedHashMap<>();
        Map<String, Boolean> hadSecret = new HashMap<>();
        Set<String> clearRequested = new HashSet<>();
        for (OrbitExtension.SetupField field : extension.setupFields) {
            TextView label = UiKit.text(this, getString(field.required ? R.string.ext_label_required
                    : R.string.ext_label_optional, field.label),
                    12, UiKit.MUTED, true);
            label.setPadding(UiKit.dp(this, 2), UiKit.dp(this, 10), 0, UiKit.dp(this, 5));
            form.addView(label);
            if (!field.description.isEmpty()) {
                TextView description = UiKit.text(this, field.description,
                        11, UiKit.MUTED, false);
                description.setPadding(UiKit.dp(this, 2), 0, 0, UiKit.dp(this, 6));
                form.addView(description);
            }

            boolean secret = field.isSecret();
            String storedSecret = secret ? OrbitExtensionSecretStore.load(
                    this, extension.id, field.id) : null;
            boolean hasSecret = storedSecret != null;
            hadSecret.put(field.id, hasSecret);
            EditText input = configurationInput(field, secret ? "" :
                    saved.optString(field.id, field.defaultValue));
            if (secret) input.setHint(hasSecret ? safeSecretStatus(field, storedSecret) :
                    getString(R.string.ext_enter_securely));
            inputs.put(field.id, input);
            form.addView(input, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 52)));

            if (secret && hasSecret) {
                Button clear = secondaryButton(getString(R.string.ext_clear_saved));
                LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 40));
                clearLp.topMargin = UiKit.dp(this, 6);
                form.addView(clear, clearLp);
                clear.setOnClickListener(v -> {
                    if (clearRequested.remove(field.id)) {
                        clear.setText(getString(R.string.ext_clear_saved));
                        input.setHint(safeSecretStatus(field,
                                OrbitExtensionSecretStore.load(this, extension.id, field.id)));
                    } else {
                        clearRequested.add(field.id);
                        input.setText("");
                        input.setHint(getString(R.string.ext_will_be_cleared));
                        clear.setText(getString(R.string.ext_keep_saved));
                    }
                });
                input.addTextChangedListener(new TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                        if (s != null && s.length() > 0 && clearRequested.remove(field.id)) {
                            clear.setText(getString(R.string.ext_clear_saved));
                        }
                    }
                    @Override public void afterTextChanged(Editable s) {}
                });
            }
        }
        UiKit.applyTypography(form);
        ScrollView wrapper = new ScrollView(this);
        wrapper.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(initiallyConfigured ? R.string.ext_configure_title
                        : R.string.ext_setup_title, extension.name))
                .setView(wrapper)
                .setNegativeButton(getString(R.string.ui_cancel_19766ed6), null)
                .setPositiveButton(getString(R.string.ui_save_1509f561), null)
                .create();
        UiKit.styleOrbitDialog(dialog, this, false, () -> {
            Button saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (saveButton != null) saveButton.setOnClickListener(v -> {
                JSONObject nonSecret = new JSONObject();
                Map<String, String> secretReplacements = new LinkedHashMap<>();
                try {
                    for (OrbitExtension.SetupField field : extension.setupFields) {
                        EditText input = inputs.get(field.id);
                        String entered = input == null ? "" : input.getText().toString();
                        if (field.isSecret()) {
                            String safe = entered.trim().isEmpty() ? "" :
                                    OrbitExtensionV2.validateSetupValue(field, entered);
                            if (!safe.isEmpty()) secretReplacements.put(field.id, safe);
                            boolean willExist = !safe.isEmpty() ||
                                    (Boolean.TRUE.equals(hadSecret.get(field.id)) &&
                                            !clearRequested.contains(field.id));
                            if (field.required && !willExist &&
                                    !(initiallyConfigured && clearRequested.contains(field.id)))
                                throw new IllegalArgumentException(getString(R.string.ext_enter_field, field.label));
                        } else {
                            String safe = OrbitExtensionV2.validateSetupValue(field, entered);
                            if (field.required && safe.isEmpty())
                                throw new IllegalArgumentException(getString(R.string.ext_enter_field, field.label));
                            if (!safe.isEmpty()) nonSecret.put(field.id, safe);
                        }
                    }
                } catch (IllegalArgumentException e) {
                    Toast.makeText(this, e.getMessage() == null ?
                            getString(R.string.ext_check_values) : e.getMessage(), Toast.LENGTH_SHORT).show();
                    return;
                } catch (Exception e) {
                    Toast.makeText(this, getString(R.string.ext_check_values), Toast.LENGTH_SHORT).show();
                    return;
                }

                JSONObject previous = OrbitExtensionStore.configuration(this, extension.id);
                if (!OrbitExtensionStore.setConfiguration(this, extension.id, nonSecret)) {
                    configurationSaveFailed();
                    return;
                }
                boolean secretsSaved = secretReplacements.isEmpty() && clearRequested.isEmpty() ||
                        OrbitExtensionSecretStore.save(this, extension.id,
                                secretReplacements, clearRequested);
                if (!secretsSaved) {
                    OrbitExtensionStore.setConfiguration(this, extension.id, previous);
                    configurationSaveFailed();
                    return;
                }
                dialog.dismiss();
                Toast.makeText(this, getString(R.string.ext_config_saved, extension.name),
                        Toast.LENGTH_SHORT).show();
                rebuildPreservingPosition();
            });
        });
        dialog.show();
    }

    private EditText configurationInput(OrbitExtension.SetupField field, String value) {
        EditText input = new EditText(this);
        input.setText(value == null ? "" : value);
        input.setTextColor(UiKit.TEXT);
        input.setHintTextColor(UiKit.MUTED);
        input.setTextSize(14);
        input.setSingleLine(true);
        input.setPadding(UiKit.dp(this, 15), 0, UiKit.dp(this, 15), 0);
        input.setBackground(UiKit.outlined(UiKit.SURFACE_2,
                Color.rgb(53, 58, 72), 15, this));
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(field.maxLength)});
        if (field.isSecret()) {
            input.setInputType(InputType.TYPE_CLASS_TEXT |
                    InputType.TYPE_TEXT_VARIATION_PASSWORD |
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        } else if (OrbitExtension.SETUP_URL.equals(field.type)) {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        } else {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        }
        return input;
    }

    private String safeSecretStatus(OrbitExtension.SetupField field, String value) {
        if (value == null || value.isEmpty()) return getString(R.string.ext_enter_securely);
        if (OrbitExtension.SETUP_SECRET_URL.equals(field.type)) {
            try {
                String host = new java.net.URI(value).getHost();
                if (host != null && !host.trim().isEmpty()) return getString(R.string.ext_configured_host, host);
            } catch (Exception ignored) {}
        }
        return getString(R.string.ext_saved);
    }

    private String parameterLabels(OrbitExtension.Action action) {
        List<String> labels = new ArrayList<>();
        for (OrbitExtension.ActionParameter parameter : action.parameters)
            labels.add(getString(parameter.required ? R.string.ext_param_required
                    : R.string.ext_param_optional, parameter.label));
        return String.join(", ", labels);
    }

    private String headerNames(OrbitExtension.Action action) {
        List<String> names = new ArrayList<>();
        for (OrbitExtension.RequestHeader header : action.headers) names.add(header.name);
        return String.join(", ", names);
    }

    private void configurationSaveFailed() {
        AlertDialog error = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.ext_config_not_saved))
                .setMessage(getString(R.string.ext_config_not_saved_msg))
                .setPositiveButton(getString(R.string.ui_ok_565339bc), null)
                .create();
        UiKit.styleOrbitDialog(error, this, false);
        error.show();
    }

    private void confirmRemove(OrbitExtension extension) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.ext_remove_title, extension.name))
                .setMessage(getString(R.string.ext_remove_msg))
                .setNegativeButton(getString(R.string.ui_cancel_19766ed6), null)
                .setPositiveButton(getString(R.string.ext_remove), (d, w) -> {
                    if (OrbitExtensionStore.remove(this, extension.id))
                        rebuildPreservingPosition();
                    else Toast.makeText(this, getString(R.string.ext_remove_failed),
                            Toast.LENGTH_SHORT).show();
                }).create();
        UiKit.styleOrbitDialog(dialog, this, true);
        dialog.show();
    }

    private LinearLayout unavailableExtensionCard(String removalKey, boolean validExtension,
                                                   String displayId) {
        LinearLayout card = card();
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = UiKit.text(this, getString(R.string.ext_unavailable_title), 16, UiKit.TEXT, true);
        top.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView state = UiKit.text(this, getString(R.string.ext_unavailable_state), 10, UiKit.MUTED, true);
        state.setLetterSpacing(0.07f);
        top.addView(state);
        card.addView(top);

        TextView guidance = UiKit.text(this,
                getString(R.string.ext_unavailable_msg),
                12, UiKit.MUTED, false);
        guidance.setLineSpacing(0, 1.12f);
        guidance.setPadding(0, UiKit.dp(this, 8), 0, UiKit.dp(this, 12));
        card.addView(guidance);
        if (displayId != null && !displayId.isEmpty()) {
            TextView id = UiKit.text(this, getString(R.string.ext_stored_id, displayId),
                    11, UiKit.MUTED, false);
            id.setPadding(0, 0, 0, UiKit.dp(this, 10));
            card.addView(id);
        }
        Button remove = destructiveButton(getString(R.string.ext_remove));
        remove.setContentDescription(getString(R.string.ext_remove_unavailable_desc));
        remove.setOnClickListener(v -> confirmRemoveUnavailable(removalKey, validExtension));
        card.addView(remove, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 44)));
        return card;
    }

    private void confirmRemoveUnavailable(String removalKey, boolean validExtension) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.ext_remove_unavailable_title))
                .setMessage(getString(R.string.ext_remove_unavailable_msg))
                .setNegativeButton(getString(R.string.ui_cancel_19766ed6), null)
                .setPositiveButton(getString(R.string.ext_remove), (d, w) -> {
                    boolean removed = validExtension
                            ? OrbitExtensionStore.remove(this, removalKey)
                            : OrbitExtensionStore.removeUnavailable(this, removalKey);
                    if (removed) rebuildPreservingPosition();
                    else Toast.makeText(this, getString(R.string.ext_remove_failed),
                            Toast.LENGTH_SHORT).show();
                }).create();
        UiKit.styleOrbitDialog(dialog, this, true);
        dialog.show();
    }

    private boolean isConfiguredSafely(OrbitExtensionStore.Installed installed) {
        try { return OrbitExtensionStore.isConfigured(this, installed); }
        catch (RuntimeException | LinkageError e) {
            logFailure("configuration_state", e);
            return false;
        }
    }

    private void logFailure(String stage, Throwable error) {
        Log.w(LOG_TAG, "stage=" + stage + " error=" +
                (error == null ? "unknown" : error.getClass().getSimpleName()));
    }

    private OrbitExtensionStore.Installed findInstalled(
            OrbitExtensionStore.ManagerSnapshot snapshot, String extensionId) {
        if (snapshot == null || extensionId == null) return null;
        for (OrbitExtensionStore.ManagerEntry entry : snapshot.entries) {
            if (entry.installed != null &&
                    extensionId.equals(entry.installed.extension.id)) return entry.installed;
        }
        return null;
    }

    private void showRecovery(Throwable error) {
        final String diagnostic = safeDiagnostic(error);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_VERTICAL);
        OrbitBackground.applyPage(page);
        page.setPadding(UiKit.dp(this, 28), UiKit.dp(this, 40),
                UiKit.dp(this, 28), UiKit.dp(this, 40));

        page.addView(UiKit.text(this, getString(R.string.ext_recovery_title), 24, UiKit.TEXT, true));
        TextView message = UiKit.text(this,
                getString(R.string.ext_recovery_msg),
                14, UiKit.MUTED, false);
        message.setLineSpacing(0, 1.15f);
        message.setPadding(0, UiKit.dp(this, 12), 0, UiKit.dp(this, 22));
        page.addView(message);

        Button retry = primaryButton(getString(R.string.ui_retry_942087cc));
        retry.setOnClickListener(v -> {
            try {
                renderContent(false, -1);
                getWindow().getDecorView().post(() -> renderFullContent(-1));
            } catch (RuntimeException | LinkageError retryError) {
                logFailure("recovery_retry", retryError);
                showRecovery(retryError);
            }
        });
        page.addView(retry, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 46)));

        Button back = secondaryButton(getString(R.string.ui_back_76900f1b));
        back.setOnClickListener(v -> navigation.performBack());
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 46));
        backLp.topMargin = UiKit.dp(this, 9);
        page.addView(back, backLp);

        Button copy = secondaryButton(getString(R.string.ext_copy_diagnostic));
        copy.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) clipboard.setPrimaryClip(
                    ClipData.newPlainText("Orbit Extensions diagnostic", diagnostic));
            Toast.makeText(this, getString(R.string.ext_diagnostic_copied), Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 46));
        copyLp.topMargin = UiKit.dp(this, 9);
        page.addView(copy, copyLp);

        UiKit.applyTypography(page);
        setContentView(page);
        UiKit.applyActivityInsets(this, page, true);
    }

    private String safeDiagnostic(Throwable error) {
        String type = error == null ? "Unknown" : error.getClass().getName();
        String location = "unavailable";
        if (error != null) {
            for (StackTraceElement frame : error.getStackTrace()) {
                if (frame.getClassName().startsWith("com.orbit.assistant")) {
                    location = frame.getClassName() + "." + frame.getMethodName() +
                            ":" + frame.getLineNumber();
                    break;
                }
            }
        }
        return "Orbit " + BuildConfig.VERSION_NAME + "\n" +
                "Exception: " + type + "\n" +
                "Location: " + location;
    }

    private void showError(String message) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.ext_not_installed_title))
                .setMessage(message)
                .setPositiveButton(getString(R.string.ui_ok_565339bc), null).create();
        UiKit.styleOrbitDialog(dialog, this, false);
        dialog.show();
    }

    private String readManifest(Uri uri) throws Exception {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IllegalArgumentException(getString(R.string.ext_err_open_file));
            return readManifest(input);
        }
    }

    private List<OrbitExtension> loadFirstPartyExtensions(boolean includeV2) {
        List<OrbitExtension> out = new ArrayList<>();
        bundledLoadFailures = 0;
        int count = includeV2 ? FIRST_PARTY_ASSETS.length : 3;
        for (int i = 0; i < count; i++) {
            String assetName = FIRST_PARTY_ASSETS[i];
            try (InputStream input = getAssets().open(FIRST_PARTY_ASSET_DIR + assetName)) {
                // First-party packages use the exact same size limit and untrusted
                // OrbitExtension parser as files selected through Android SAF.
                out.add(OrbitExtension.parse(readManifest(input)));
            } catch (Exception | LinkageError error) {
                // A damaged bundled manifest must remain unavailable, never privileged.
                bundledLoadFailures++;
                Log.w(LOG_TAG, "stage=first_party_manifest asset=" + assetName +
                        " error=" + error.getClass().getSimpleName());
            }
        }
        return out;
    }

    private String readManifest(InputStream input) throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > OrbitExtension.MAX_MANIFEST_BYTES)
                    throw new IllegalArgumentException(getString(R.string.ext_err_too_large));
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null) return name;
            }
        } catch (Exception ignored) {}
        return uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment();
    }

    private TextView spacedText(String text, int color) {
        TextView view = UiKit.text(this, text, 12, color, false);
        view.setLineSpacing(0, 1.12f);
        view.setPadding(0, UiKit.dp(this, 8), 0, UiKit.dp(this, 2));
        return view;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(UiKit.dp(this, 17), UiKit.dp(this, 16),
                UiKit.dp(this, 17), UiKit.dp(this, 16));
        card.setBackground(UiKit.outlined(UiKit.SURFACE,
                UiKit.withAlpha(UiKit.accent(this), 38), 22, this));
        card.setElevation(UiKit.dp(this, 2));
        return card;
    }

    private TextView sectionHeading(String text) {
        TextView heading = UiKit.text(this, text, 12, UiKit.MUTED, true);
        heading.setLetterSpacing(0.13f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(UiKit.dp(this, 4), UiKit.dp(this, 24), 0, UiKit.dp(this, 9));
        heading.setLayoutParams(lp);
        return heading;
    }

    private LinearLayout.LayoutParams cardSpacing(boolean separated) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (separated) lp.topMargin = UiKit.dp(this, 10);
        return lp;
    }

    private Button primaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(UiKit.onAccent(this));
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setBackground(UiKit.ripple(UiKit.accent(this), UiKit.onAccent(this), 15, this));
        button.setMinHeight(0); button.setMinimumHeight(0); button.setStateListAnimator(null);
        UiKit.pressScale(button);
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(UiKit.TEXT);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2,
                Color.rgb(53, 58, 72), UiKit.accent(this), 14, this));
        button.setMinHeight(0); button.setMinimumHeight(0); button.setStateListAnimator(null);
        UiKit.pressScale(button);
        return button;
    }

    private Button warningButton(String text) {
        int warning = Color.rgb(224, 157, 78);
        Button button = outlinedActionButton(text, warning,
                UiKit.blend(warning, UiKit.SURFACE_2, 0.10f));
        return button;
    }

    private Button destructiveButton(String text) {
        int destructive = Color.rgb(226, 100, 108);
        return outlinedActionButton(text, destructive,
                UiKit.blend(destructive, UiKit.SURFACE_2, 0.08f));
    }

    private Button installedButton(String text) {
        Button button = secondaryButton(text);
        button.setEnabled(false);
        button.setAlpha(0.72f);
        return button;
    }

    private Button outlinedActionButton(String text, int color, int fill) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(color);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setBackground(UiKit.rippleOutlined(fill,
                UiKit.withAlpha(color, 190), color, 14, this));
        button.setMinHeight(0); button.setMinimumHeight(0); button.setStateListAnimator(null);
        UiKit.pressScale(button);
        return button;
    }
}
