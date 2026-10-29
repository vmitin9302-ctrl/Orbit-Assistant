package com.orbit.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

/**
 * Orbit Local: the on-device AI management screen, and the only one there is.
 *
 * <p>The optional component is a separate package, but it deliberately has no UI of its own —
 * no launcher icon, no settings, no onboarding. Everything about Orbit Local is managed from
 * here, so from a user's point of view there is still just Orbit with a feature that can be
 * installed and removed.
 *
 * <p>Four cards, in the order the user needs them: what this device can do, the component that
 * runs the AI, the model it runs, and what all of it is costing in storage. Nothing here touches
 * the main thread with file, network, or cross-process work.
 */
public final class LocalAiActivity extends Activity {
    /**
     * Remembers which removal is waiting on Android's answer, if either is.
     *
     * <p>Persisted rather than held in a field because Android's uninstall confirmation can take
     * this Activity through a full destroy and recreate. A field would come back empty and the
     * Orbit-side cleanup would simply never happen.
     *
     * <p>Holds {@link #SCOPE_EVERYTHING}, {@link #SCOPE_COMPONENT}, or nothing at all. The two
     * scopes are genuinely different requests and are not allowed to borrow each other's cleanup:
     * uninstalling the component must not quietly delete a model an older Orbit still owns.
     */
    private static final String KEY_REMOVAL_PENDING = "orbit_local_removal_pending";
    /** Everything Orbit Local uses: component, its model, legacy model data, installer cache. */
    private static final String SCOPE_EVERYTHING = "everything";
    /** Only the component and the model Android takes with it. Legacy Orbit data is left alone. */
    private static final String SCOPE_COMPONENT = "component";

    /**
     * What the Google Play edition says where it would otherwise offer to install the component.
     *
     * <p>The component is a separate APK that Orbit from GitHub installs from its own release. An
     * app distributed through Google Play may not install executable code from anywhere else, so
     * until Orbit Local has a Play-delivered form the Play edition does not offer it. The Play
     * edition is also signed with a different key from the GitHub edition, so even a genuine
     * component that is already installed cannot serve it; it can only be removed.
     */
    static final String PLAY_UNAVAILABLE =
            "Orbit Local isn't available in the Google Play edition of Orbit yet.";

    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout cards;
    private String appearanceSignature;

    /** Last status read from the component, or null when it cannot be reached. */
    private OrbitLocalStatus status;
    private boolean installing;
    private int installPercent = -1;
    private String installMessage = "";
    private File readyComponentApk;
    private boolean waitingForInstallPermission;
    /** What the last removal attempt came to, shown once and never invented. */
    private String removalMessage = "";

    /**
     * The live view of the component while this screen is visible.
     *
     * <p>Started in onResume and stopped in onPause, so it never runs behind another screen and
     * never survives this one. Each reading is kept, and the cards are redrawn whenever anything
     * they show changed - either model's state, bytes, errors, pause flag or storage - or while
     * Orbit is downloading the component APK itself, whose percentage is Orbit's own to draw.
     */
    /** Package-visible so tests can observe the loop. */
    final OrbitLocalStatusPoller poller = new OrbitLocalStatusPoller(main,
            answer -> {
                if (!OrbitLocalComponent.isUsable(this)) {
                    // Nothing to ask across a process boundary: the component is absent.
                    answer.accept(null);
                    return;
                }
                OrbitLocalClient.statusAsync(this, answer::accept);
            },
            (fresh, changed) -> {
                if (isFinishing() || isDestroyed()) return;
                status = fresh;
                if (changed || installing) rebuild();
            },
            () -> installing);

    /** Interactive Back for this page. Its classification lives in OrbitNavigation. */
    private OrbitPredictiveBack navigation;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.syncTheme(this);
        appearanceSignature = UiKit.appearanceSignature(this);
        Window window = getWindow();
        window.setStatusBarColor(UiKit.BG);
        window.setNavigationBarColor(UiKit.BG);
        View content = buildContent();
        setContentView(content);
        UiKit.applyActivityInsets(this, content, true);
        navigation = OrbitPredictiveBack.install(this);
    }

    @Override protected void onResume() {
        super.onResume();
        UiPresence.enter(this);
        if (!UiKit.appearanceSignature(this).equals(appearanceSignature)) {
            recreate();
            return;
        }
        // Coming back from Android's installer or uninstaller: what actually happened is
        // whatever the package manager now reports, never what Orbit asked for.
        reconcileComponentRemoval();
        reconcileComponentInstall();
        reconcileProviderSelection();
        if (waitingForInstallPermission && readyComponentApk != null
                && OrbitUpdater.canRequestPackageInstalls(this)) {
            waitingForInstallPermission = false;
            launchComponentInstaller(readyComponentApk);
        }
        rebuild();
        // An immediate read, then live updates for as long as the page stays visible.
        poller.start();
    }

    /**
     * Settles a "Remove Orbit Local" that was waiting on Android's own uninstall confirmation.
     *
     * <p>The ordering v0.7.7.5 got wrong. It deleted the model, the legacy copy, and the installer
     * cache <em>before</em> asking Android to remove the package — so cancelling Android's
     * confirmation left a component installed with its 1.6 GB model already thrown away, and Orbit
     * had no way to tell that from success.
     *
     * <p>Now nothing is deleted until the package manager says the component is genuinely gone.
     * The observed package is the whole decision; the status broadcast only supplies the sentence
     * shown to the user. A cancelled removal leaves the component, the model, the legacy data, and
     * the provider selection exactly as they were.
     */
    /** What resuming after an uninstall request must do. Nothing else decides this. */
    enum RemovalOutcome {
        /** No removal was in flight. Nothing to settle. */
        NOTHING_PENDING,
        /** The package is still there, so nothing of the user's may be touched. */
        CANCELLED,
        /** The component went. Orbit's own legacy model data was not part of the request. */
        REMOVED_COMPONENT,
        /** The component went, and everything Orbit still holds for Orbit Local goes with it. */
        REMOVED_EVERYTHING
    }

    /**
     * The removal decision, on plain values.
     *
     * <p>Separated out because it is the exact judgement v0.7.7.5 never made: it deleted first and
     * asked Android second, so a cancelled uninstall was indistinguishable from a successful one
     * and the model was already gone either way. A package that is still installed can only ever
     * produce {@link RemovalOutcome#CANCELLED}, and that outcome deletes nothing.
     */
    static RemovalOutcome removalOutcome(String pendingScope, boolean componentStillInstalled) {
        if (pendingScope == null || pendingScope.isEmpty()) return RemovalOutcome.NOTHING_PENDING;
        if (componentStillInstalled) return RemovalOutcome.CANCELLED;
        return SCOPE_COMPONENT.equals(pendingScope)
                ? RemovalOutcome.REMOVED_COMPONENT : RemovalOutcome.REMOVED_EVERYTHING;
    }

    private void reconcileComponentRemoval() {
        String scope = pendingRemovalScope(this);
        RemovalOutcome outcome = removalOutcome(scope, OrbitLocalComponent.isInstalled(this));
        if (outcome == RemovalOutcome.NOTHING_PENDING) return;
        clearRemovalPending();
        if (outcome == RemovalOutcome.CANCELLED) {
            // Still here. Either the user backed out, or Android refused — and in both cases the
            // honest thing is to change nothing and say so.
            String reported = OrbitLocalUninstaller.message(this, lastRemovalStage(this));
            removalMessage = reported.isEmpty()
                    ? getString(R.string.localai_not_removed) : reported;
            return;
        }
        // Confirmed absent. Android took the component and, with it, the model that lived in the
        // component's own private storage — so what is left is only what belongs to Orbit.
        finishOrbitSideRemoval(scope);
        removalMessage = outcome == RemovalOutcome.REMOVED_COMPONENT
                ? getString(R.string.localai_component_uninstalled)
                : getString(R.string.localai_removed);
        // A complete removal takes the card that message would have been shown on with it, so the
        // confirmation is said out loud instead of being written somewhere that no longer exists.
        if (!hasAnythingToRemove()) {
            Toast.makeText(this, removalMessage, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * The Orbit-side remainder, run only after the component is confirmed gone.
     *
     * <p>Deliberately small, because Android took the large part with it. What is left is the
     * cached component installer, a provider selection that can no longer answer, and — for the
     * full removal only — a model left behind by an Orbit from before Orbit Local was modular.
     * An "Uninstall component" must not reach that far: nobody asked it to.
     */
    private void finishOrbitSideRemoval(String scope) {
        if (SCOPE_EVERYTHING.equals(scope)) LocalModelStore.deleteLegacy(this);
        OrbitLocalInstaller.cleanup(this);
        OrbitLocalClient.disconnect(this);
        OrbitLocalProvider.invalidateStatus();
        status = null;
        if (Prefs.PROVIDER_LOCAL.equals(Prefs.provider(this))) {
            AiProviders.select(this, Prefs.PROVIDER_CHATGPT);
        }
    }

    /** Which removal, if any, is waiting on Android. Package-visible so tests can drive it. */
    static String pendingRemovalScope(android.content.Context context) {
        return Prefs.get(context).getString(KEY_REMOVAL_PENDING, "");
    }

    static OrbitLocalUninstaller.Stage lastRemovalStage(android.content.Context context) {
        try {
            return OrbitLocalUninstaller.Stage.valueOf(
                    DiagnosticStore.lastComponentUninstallStage(context));
        } catch (Exception e) {
            return OrbitLocalUninstaller.Stage.CANCELLED;
        }
    }

    /**
     * Written synchronously, because Android's uninstaller is opened in the very next breath.
     *
     * <p>An asynchronous write that lost its race with the process going away would leave a
     * component removed and Orbit's own side of the cleanup never run.
     */
    private void setRemovalPending(String scope) {
        Prefs.get(this).edit().putString(KEY_REMOVAL_PENDING, scope).commit();
    }

    private void clearRemovalPending() {
        Prefs.get(this).edit().putString(KEY_REMOVAL_PENDING, "").commit();
    }

    /**
     * Re-reads the component from the package manager, and settles everything that follows from it.
     *
     * <p>This is the fix for the v0.7.7.5-beta.2 device report where Android said "Install success"
     * and Orbit still said "Not installed". The manifest change is what lets the query see the
     * package at all; this is what makes Orbit ask again at the one moment it matters, instead of
     * carrying forward whatever it believed before the installer opened.
     *
     * <p>Deliberately keyed on the observed package, not on a remembered "install requested" flag.
     * A cancelled install and a successful one are indistinguishable from Orbit's side except by
     * asking Android, so Orbit asks: the cancelled case simply reports the component still missing
     * and keeps the verified download for Retry, and the successful case is the only thing that
     * ever clears the installer cache.
     */
    private void reconcileComponentInstall() {
        OrbitLocalProvider.invalidateStatus();
        if (installing) return; // A download owns the cache directory until it finishes.

        OrbitLocalComponent.State state = OrbitLocalComponent.state(this);
        if (state == OrbitLocalComponent.State.INSTALLED) {
            // Confirmed by the package manager, so the downloaded APK is now a duplicate of a
            // package Android is storing itself, and the pending install is genuinely over.
            waitingForInstallPermission = false;
            readyComponentApk = null;
            installMessage = "";
            OrbitLocalInstaller.cleanupAfterInstall(this);
            return;
        }
        // Still not usable: drop any stale binding to a component that changed underneath Orbit,
        // and clear out installers that can no longer be handed to Android — old Beta APKs and
        // interrupted downloads — while keeping this version's verified one for Retry.
        OrbitLocalClient.disconnect(this);
        OrbitLocalInstaller.prune(this, readyComponentApk);
    }

    /**
     * Leaving this screen stops watching the download. It never stops the download.
     *
     * <p>The work belongs to the component's own WorkManager job in its own process, so locking
     * the phone, opening another app, or destroying this Activity has no bearing on it. All that
     * ends here is Orbit looking.
     */
    @Override protected void onPause() {
        poller.stop();
        UiPresence.leave(this);
        super.onPause();
    }

    /**
     * Orbit Local must never sit there claiming to be active when it cannot answer.
     *
     * <p>The component can be uninstalled from Android Settings at any moment, entirely outside
     * Orbit, so its absence is reconciled whenever this screen is shown rather than assumed.
     */
    private void reconcileProviderSelection() {
        if (!Prefs.PROVIDER_LOCAL.equals(Prefs.provider(this))) return;
        if (AiProviders.byId(Prefs.PROVIDER_LOCAL).selectable(this)) return;
        AiProviders.select(this, Prefs.PROVIDER_CHATGPT);
    }

    // ---- layout -----------------------------------------------------------------------------------

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
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
        back.setContentDescription(getString(R.string.localai_back_to_providers));
        back.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 10),
                UiKit.dp(this, 10), UiKit.dp(this, 10));
        back.setOnClickListener(v -> navigation.performBack());
        UiKit.pressScale(back);
        header.addView(back, new LinearLayout.LayoutParams(UiKit.dp(this, 44), UiKit.dp(this, 44)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(UiKit.dp(this, 13), 0, 0, 0);
        titles.addView(UiKit.text(this, "Orbit Local", 24, UiKit.TEXT, true));
        titles.addView(UiKit.text(this, getString(R.string.localai_tagline), 13, UiKit.MUTED, false));
        header.addView(titles, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(header);

        TextView intro = UiKit.text(this,
                getString(R.string.localai_intro),
                13, UiKit.MUTED, false);
        intro.setLineSpacing(0, 1.14f);
        LinearLayout.LayoutParams introLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        introLp.setMargins(UiKit.dp(this, 3), UiKit.dp(this, 18), UiKit.dp(this, 3), UiKit.dp(this, 4));
        page.addView(intro, introLp);

        cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.VERTICAL);
        page.addView(cards, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        rebuild();

        UiKit.applyTypography(page);
        return scroll;
    }

    private void rebuild() {
        if (cards == null) return;
        cards.removeAllViews();
        cards.addView(deviceCard(), cardLp());
        cards.addView(componentCard(), cardLp());
        // Only once there is a trusted component to describe; before that every line would be
        // "not yet", which the component card already says in one sentence.
        if (OrbitLocalComponent.isUsable(this)) cards.addView(capabilityCard(), cardLp());
        cards.addView(modelCard(), cardLp());
        cards.addView(actionModelCard(), cardLp());
        cards.addView(storageCard(), cardLp());
        if (hasAnythingToRemove()) cards.addView(removeCard(), cardLp());
        UiKit.applyTypography(cards);
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, UiKit.dp(this, 10), 0, 0);
        return lp;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(UiKit.dp(this, 17), UiKit.dp(this, 15), UiKit.dp(this, 17), UiKit.dp(this, 15));
        card.setBackground(UiKit.outlined(UiKit.SURFACE,
                UiKit.withAlpha(UiKit.accent(this), 38), 22, this));
        card.setElevation(UiKit.dp(this, 2));
        return card;
    }

    private TextView pill(String label, boolean accent) {
        TextView pill = UiKit.text(this, label, 11,
                accent ? UiKit.onAccent(this) : UiKit.TEXT, true);
        pill.setBackground(accent
                ? UiKit.rounded(UiKit.accent(this), 99, this)
                : UiKit.outlined(UiKit.SURFACE_2, Color.rgb(53, 58, 72), 99, this));
        pill.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 4), UiKit.dp(this, 10), UiKit.dp(this, 4));
        return pill;
    }

    private LinearLayout titleRow(String title, String pillLabel, boolean accentPill) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, UiKit.dp(this, 7), 0, 0);
        row.addView(UiKit.text(this, title, 17, UiKit.TEXT, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(pill(pillLabel, accentPill));
        return row;
    }

    // ---- this device ------------------------------------------------------------------------------

    private View deviceCard() {
        DeviceCapabilityCheck.Assessment assessment = DeviceCapabilityCheck.assess(this);
        LinearLayout card = card();
        card.addView(UiKit.text(this, getString(R.string.localai_this_device), 12, UiKit.MUTED, true));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, UiKit.dp(this, 7), 0, 0);
        View dot = new View(this);
        dot.setBackground(UiKit.rounded(tierColor(assessment.tier), 99, this));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                UiKit.dp(this, 9), UiKit.dp(this, 9));
        dotLp.rightMargin = UiKit.dp(this, 8);
        row.addView(dot, dotLp);
        row.addView(UiKit.text(this, assessment.tierLabel(this), 16, UiKit.TEXT, true));
        card.addView(row);

        TextView summary = UiKit.text(this, assessment.summary, 13, UiKit.MUTED, false);
        summary.setLineSpacing(0, 1.13f);
        summary.setPadding(0, UiKit.dp(this, 6), 0, 0);
        card.addView(summary);

        long free = LocalModelStore.freeStorageBytes(this);
        if (free >= 0) {
            TextView storage = UiKit.text(this,
                    getString(R.string.localai_free_storage, LocalModelStore.formatBytes(free)), 12, UiKit.MUTED, false);
            storage.setPadding(0, UiKit.dp(this, 5), 0, 0);
            card.addView(storage);
        }
        return card;
    }

    private int tierColor(DeviceCapabilityCheck.Tier tier) {
        switch (tier) {
            case EXCELLENT:
            case SUPPORTED: return UiKit.SUCCESS;
            case LIMITED: return Color.rgb(240, 193, 100);
            default: return UiKit.DANGER;
        }
    }

    // ---- what works here ---------------------------------------------------------------------------

    /**
     * What Orbit Local does on this phone right now, and what still needs a cloud provider.
     *
     * <p>Every line comes from {@link OrbitLocalCapabilities}, which reads real readiness - installed
     * models, switches, permissions - so nothing is listed as working because the code for it exists.
     */
    private View capabilityCard() {
        List<OrbitLocalCapabilities.Capability> all = OrbitLocalCapabilities.evaluate(this);
        LinearLayout card = card();
        card.addView(UiKit.text(this, getString(R.string.localai_what_works), 12, UiKit.MUTED, true));
        LinearLayout local = new LinearLayout(this);
        local.setOrientation(LinearLayout.VERTICAL);
        local.setPadding(0, UiKit.dp(this, 6), 0, 0);
        LinearLayout cloud = new LinearLayout(this);
        cloud.setOrientation(LinearLayout.VERTICAL);
        for (OrbitLocalCapabilities.Capability capability : all) {
            if (capability.availability == OrbitLocalCapabilities.Availability.CLOUD_ONLY) {
                cloud.addView(capabilityRow(capability));
            } else {
                local.addView(capabilityRow(capability));
            }
        }
        card.addView(local);
        TextView cloudTitle = UiKit.text(this, getString(R.string.localai_needs_cloud), 12, UiKit.MUTED, true);
        cloudTitle.setPadding(0, UiKit.dp(this, 12), 0, UiKit.dp(this, 2));
        card.addView(cloudTitle);
        card.addView(cloud);
        TextView promise = UiKit.text(this,
                getString(R.string.localai_cloud_promise), 12, UiKit.MUTED, false);
        promise.setLineSpacing(0, 1.12f);
        promise.setPadding(0, UiKit.dp(this, 10), 0, 0);
        card.addView(promise);
        return card;
    }

    private View capabilityRow(OrbitLocalCapabilities.Capability capability) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, UiKit.dp(this, 5), 0, UiKit.dp(this, 1));
        View dot = new View(this);
        int color;
        switch (capability.availability) {
            case READY: color = UiKit.SUCCESS; break;
            case NEEDS_SETUP: color = Color.rgb(240, 193, 100); break;
            default: color = UiKit.withAlpha(UiKit.MUTED, 150); break;
        }
        dot.setBackground(UiKit.rounded(color, 99, this));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                UiKit.dp(this, 7), UiKit.dp(this, 7));
        dotLp.rightMargin = UiKit.dp(this, 10);
        row.addView(dot, dotLp);
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        boolean cloudOnly = capability.availability == OrbitLocalCapabilities.Availability.CLOUD_ONLY;
        words.addView(UiKit.text(this, capability.label(this), 14,
                cloudOnly ? UiKit.MUTED : UiKit.TEXT, !cloudOnly));
        if (!capability.detail(this).isEmpty()) {
            words.addView(UiKit.text(this, capability.detail(this), 12, UiKit.MUTED, false));
        }
        row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        String state = capability.availability == OrbitLocalCapabilities.Availability.READY
                ? getString(R.string.localai_state_works) : cloudOnly ? getString(R.string.localai_state_needs_cloud) : getString(R.string.localai_state_not_ready);
        row.setContentDescription(capability.label(this) + ", " + state
                + (capability.detail(this).isEmpty() ? "" : ". " + capability.detail(this)));
        return row;
    }

    // ---- the component ----------------------------------------------------------------------------

    private View componentCard() {
        OrbitLocalComponent.State state = OrbitLocalComponent.state(this);
        LinearLayout card = card();
        card.addView(UiKit.text(this, getString(R.string.localai_component_header), 12, UiKit.MUTED, true));
        card.addView(titleRow(getString(R.string.localai_component_title),
                OrbitLocalComponent.stateLabel(this, state), state == OrbitLocalComponent.State.INSTALLED));

        TextView details = UiKit.text(this, componentDetails(state), 13, UiKit.MUTED, false);
        details.setLineSpacing(0, 1.13f);
        details.setPadding(0, UiKit.dp(this, 6), 0, 0);
        card.addView(details);

        if (installing) {
            ProgressBar bar = UiKit.horizontalProgress(this);
            bar.setMax(100);
            bar.setProgress(Math.max(0, installPercent));
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 8));
            barLp.topMargin = UiKit.dp(this, 11);
            card.addView(bar, barLp);
            TextView progressText = UiKit.text(this,
                    installPercent < 0 ? getString(R.string.localai_preparing) : installPercent + "%",
                    12, UiKit.MUTED, false);
            progressText.setPadding(0, UiKit.dp(this, 6), 0, 0);
            card.addView(progressText);
        }

        if (!installMessage.isEmpty()) {
            TextView message = UiKit.text(this, installMessage, 13, UiKit.DANGER, false);
            message.setLineSpacing(0, 1.13f);
            message.setPadding(0, UiKit.dp(this, 8), 0, 0);
            card.addView(message);
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(0, UiKit.dp(this, 12), 0, 0);
        buildComponentActions(actions, state);
        if (actions.getChildCount() > 0) {
            card.addView(actions, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return card;
    }

    private String componentDetails(OrbitLocalComponent.State state) {
        if (!OrbitDistribution.supportsOrbitLocal()) return playComponentDetails(this, state);
        String size = componentSizeText();
        switch (state) {
            case INSTALLED:
                return getString(R.string.localai_component_installed, size, OrbitVersion.displayName(
                                OrbitLocalComponent.installedVersionName(this)));
            case UPDATE_REQUIRED:
                return getString(R.string.localai_component_update_required);
            case UNTRUSTED:
                return getString(R.string.localai_untrusted_install);
            default:
                return getString(R.string.localai_component_required, size);
        }
    }

    /**
     * The Google Play edition's whole view of the component.
     *
     * <p>Orbit from Google Play is signed with a different key from Orbit on GitHub, and the
     * component only serves an Orbit carrying the GitHub certificate, so no installed component
     * can ever work with it. What is left to offer is removing one that is present.
     */
    static String playComponentDetails(android.content.Context context,
            OrbitLocalComponent.State state) {
        switch (state) {
            case NOT_INSTALLED:
                return context.getString(R.string.localai_play_unavailable);
            case UNTRUSTED:
                return context.getString(R.string.localai_untrusted_play);
            default:
                return context.getString(R.string.localai_github_component_play);
        }
    }

    private String componentSizeText() {
        long bytes = OrbitLocalInstaller.knownComponentBytes(this);
        return bytes > 0L ? LocalModelStore.formatBytes(bytes) : getString(R.string.localai_small_download);
    }

    private void buildComponentActions(LinearLayout actions, OrbitLocalComponent.State state) {
        boolean allowed = DeviceCapabilityCheck.allowsLocalAi(DeviceCapabilityCheck.assess(this));
        if (installing) {
            Button working = secondaryButton(getString(R.string.localai_downloading));
            working.setEnabled(false);
            working.setAlpha(0.62f);
            addPrimaryAction(actions, working);
            return;
        }
        if (!OrbitDistribution.supportsOrbitLocal()) {
            // Nothing to set up or update in the Play edition; see playComponentDetails. Only a
            // component that is actually present can be removed, through Android's own flow.
            if (state == OrbitLocalComponent.State.UNTRUSTED) {
                Button remove = dangerButton(getString(R.string.localai_remove_untrusted));
                remove.setOnClickListener(v -> removeOrbitLocal(SCOPE_COMPONENT));
                addPrimaryAction(actions, remove);
            } else if (state != OrbitLocalComponent.State.NOT_INSTALLED) {
                Button uninstall = dangerButton(getString(R.string.localai_uninstall_component));
                uninstall.setOnClickListener(v -> confirmUninstallComponent());
                addDestructiveAction(actions, uninstall);
            }
            return;
        }
        switch (state) {
            case INSTALLED: {
                // The component is a real installed package, so removing just it is a real thing
                // to want — separate from Remove Orbit Local, which also clears what Orbit holds.
                Button uninstall = dangerButton(getString(R.string.localai_uninstall_component));
                uninstall.setOnClickListener(v -> confirmUninstallComponent());
                addDestructiveAction(actions, uninstall);
                return;
            }
            case UPDATE_REQUIRED: {
                Button update = primaryButton(getString(R.string.localai_update_component));
                update.setOnClickListener(v -> startComponentDownload());
                addPrimaryAction(actions, update);
                return;
            }
            case UNTRUSTED: {
                // A package pretending to be Orbit Local. Removing it goes through exactly the
                // same Android flow, and its outcome is read back from the package manager too.
                Button remove = dangerButton(getString(R.string.localai_remove_untrusted));
                remove.setOnClickListener(v -> removeOrbitLocal(SCOPE_COMPONENT));
                addPrimaryAction(actions, remove);
                return;
            }
            default: {
                // Nothing installed at all: present one Orbit Local setup, not two technical
                // downloads. The component always comes first, because a model without it cannot
                // run and downloading 1.6 GB into that situation would be indefensible.
                boolean firstTime = !LocalModelStore.hasLegacyModel(this);
                Button install = primaryButton(firstTime ? getString(R.string.ui_set_up_orbit_local_6660808d) : getString(R.string.localai_install_component));
                install.setEnabled(allowed);
                if (!allowed) install.setAlpha(0.5f);
                install.setOnClickListener(v -> {
                    if (firstTime) showSetupSheet();
                    else startComponentDownload();
                });
                addPrimaryAction(actions, install);
            }
        }
    }

    /**
     * The one place a new user is told what Orbit Local actually costs.
     *
     * <p>Both downloads, both sizes, stated up front and in that order, with the plain fact that
     * they can be removed again. Nothing starts until Continue.
     */
    private void showSetupSheet() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(UiKit.dp(this, 24), UiKit.dp(this, 20),
                UiKit.dp(this, 24), UiKit.dp(this, 6));
        TextView title = UiKit.text(this, getString(R.string.ui_set_up_orbit_local_6660808d), 20, UiKit.TEXT, true);
        title.setAccessibilityHeading(true);
        content.addView(title);

        TextView lead = UiKit.text(this, getString(R.string.localai_needs_two),
                14, UiKit.TEXT, false);
        lead.setPadding(0, UiKit.dp(this, 12), 0, 0);
        content.addView(lead);

        content.addView(setupItem(getString(R.string.localai_setup_component, componentSizeText()),
                getString(R.string.localai_setup_component_desc)));
        content.addView(setupItem(getString(R.string.localai_setup_model,
                        LocalModelStore.formatBytes(LocalModelStore.MODEL_SIZE_BYTES)),
                getString(R.string.localai_setup_model_desc)));

        TextView footer = UiKit.text(this,
                getString(R.string.localai_setup_footer),
                13, UiKit.MUTED, false);
        footer.setLineSpacing(0, 1.13f);
        footer.setPadding(0, UiKit.dp(this, 12), 0, 0);
        content.addView(footer);
        UiKit.applyTypography(content);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setForceDarkAllowed(false);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(scroll)
                .setNegativeButton(R.string.ui_cancel_19766ed6, null)
                .setPositiveButton(R.string.ui_continue_31fbef16, (d, w) -> startComponentDownload())
                .create();
        UiKit.styleOrbitDialog(dialog, this, false);
        dialog.show();
    }

    private View setupItem(String title, String description) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 11),
                UiKit.dp(this, 14), UiKit.dp(this, 11));
        item.setBackground(UiKit.outlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.accent(this), 38), 16, this));
        item.addView(UiKit.text(this, title, 14, UiKit.TEXT, true));
        TextView detail = UiKit.text(this, description, 12, UiKit.MUTED, false);
        detail.setPadding(0, UiKit.dp(this, 3), 0, 0);
        detail.setLineSpacing(0, 1.1f);
        item.addView(detail);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, UiKit.dp(this, 11), 0, 0);
        item.setLayoutParams(lp);
        return item;
    }

    private void startComponentDownload() {
        installing = true;
        installPercent = -1;
        installMessage = "";
        rebuild();
        poller.nudge();
        OrbitLocalInstaller.downloadAsync(this, new OrbitLocalInstaller.Callback() {
            @Override public void onProgress(int percent) {
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    installPercent = percent;
                });
            }

            @Override public void onVerifying() {
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    installPercent = 100;
                    rebuild();
                });
            }

            @Override public void onReady(File apk) {
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    installing = false;
                    readyComponentApk = apk;
                    rebuild();
                    launchComponentInstaller(apk);
                });
            }

            @Override public void onError(String message) {
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    installing = false;
                    installMessage = message;
                    rebuild();
                });
            }
        });
    }

    /** Android confirms every install. Orbit only ever hands it a fully verified file. */
    private void launchComponentInstaller(File apk) {
        if (!OrbitUpdater.canRequestPackageInstalls(this)) {
            waitingForInstallPermission = true;
            installMessage = getString(R.string.localai_need_install_permission);
            rebuild();
            OrbitUpdater.openUnknownSourcesSettings(this);
            return;
        }
        try {
            OrbitLocalInstaller.launchInstaller(this, apk);
        } catch (Exception e) {
            // The installer now reports which step of the handoff failed, so show that rather than
            // one message that was only ever accurate for the last of them.
            String message = e.getMessage();
            installMessage = message == null || message.trim().isEmpty()
                    ? getString(R.string.localai_installer_failed) : message;
            rebuild();
        }
    }

    // ---- the model --------------------------------------------------------------------------------

    private View modelCard() {
        boolean componentReady = OrbitLocalComponent.isUsable(this);
        boolean active = Prefs.PROVIDER_LOCAL.equals(AiProviders.active(this).id());
        LinearLayout card = card();
        if (active) {
            card.setBackground(UiKit.outlined(
                    UiKit.blend(UiKit.SURFACE, UiKit.accent(this), 0.93f),
                    UiKit.accent(this), 22, this));
        }
        card.addView(UiKit.text(this, getString(R.string.localai_model_header), 12, UiKit.MUTED, true));

        String pillLabel;
        boolean accentPill;
        if (!componentReady) {
            pillLabel = getString(R.string.localai_requires_component);
            accentPill = false;
        } else if (status == null) {
            pillLabel = getString(R.string.localai_checking);
            accentPill = false;
        } else if (active) {
            pillLabel = getString(R.string.localai_active);
            accentPill = true;
        } else {
            pillLabel = status.stateLabel(this);
            accentPill = status.modelReady();
        }
        card.addView(titleRow(LocalModelStore.MODEL_DISPLAY_NAME, pillLabel, accentPill));

        TextView details = UiKit.text(this, modelDetails(componentReady), 13, UiKit.MUTED, false);
        details.setLineSpacing(0, 1.13f);
        details.setPadding(0, UiKit.dp(this, 6), 0, 0);
        card.addView(details);

        if (componentReady && status != null && status.showsProgress()) {
            ProgressBar bar = UiKit.horizontalProgress(this);
            bar.setMax(1000);
            // Straight from the component's own byte count. Orbit keeps no second counter that
            // could drift away from the file actually on disk.
            bar.setProgress(OrbitLocalStatus.VALIDATING.equals(status.modelState)
                    ? 1000 : status.progressPerMille());
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 8));
            barLp.topMargin = UiKit.dp(this, 11);
            card.addView(bar, barLp);

            TextView progress = UiKit.text(this, progressText(), 12, UiKit.MUTED, false);
            progress.setPadding(0, UiKit.dp(this, 6), 0, 0);
            card.addView(progress);
        }

        if (componentReady && status != null && !status.modelError.isEmpty()) {
            TextView error = UiKit.text(this, status.modelError, 13, UiKit.DANGER, false);
            error.setLineSpacing(0, 1.13f);
            error.setPadding(0, UiKit.dp(this, 8), 0, 0);
            card.addView(error);
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(0, UiKit.dp(this, 12), 0, 0);
        buildModelActions(actions, componentReady);
        if (actions.getChildCount() > 0) {
            card.addView(actions, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return card;
    }

    /**
     * The line under the progress bar: how much has arrived, out of how much.
     *
     * <p>Both figures come from the component's real on-disk state, so during a download this
     * reads like "682 MB of 1.60 GB · 42%" and advances with the file rather than with a timer.
     */
    private String progressText() {
        String have = LocalModelStore.formatBytes(status.modelBytes);
        String total = LocalModelStore.formatBytes(status.modelSizeBytes);
        switch (status.modelState) {
            case OrbitLocalStatus.VALIDATING:
                return getString(R.string.localai_verifying);
            case OrbitLocalStatus.IMPORTING:
                return getString(R.string.localai_moving_progress, have, total);
            case OrbitLocalStatus.QUEUED:
                return getString(R.string.localai_progress_starting, have, total);
            case OrbitLocalStatus.WAITING_FOR_NETWORK:
                return getString(R.string.localai_progress_waiting, have, total);
            default:
                return getString(R.string.localai_progress_percent, have, total, status.progressPerMille() / 10);
        }
    }

    private String modelDetails(boolean componentReady) {
        String size = LocalModelStore.formatBytes(LocalModelStore.MODEL_SIZE_BYTES);
        if (!componentReady) {
            String base = getString(R.string.localai_model_about, size);
            if (!OrbitDistribution.installsOrbitLocalComponent()) return base + "\n" + getString(R.string.localai_play_unavailable);
            if (LocalModelStore.hasLegacyModel(this)) {
                return base + getString(R.string.localai_legacy_hint_a);
            }
            return base + getString(R.string.localai_install_first);
        }
        if (status == null) return getString(R.string.localai_checking_component);
        switch (status.modelState) {
            case OrbitLocalStatus.READY:
                // The action model is a separate install and does not change what *this* model can
                // do, so the sentence changes only to stop claiming device commands are impossible
                // on a phone where they demonstrably are not.
                return getString(R.string.localai_model_ready, size)
                        + (status.actionModelReady()
                                ? getString(R.string.localai_model_ready_actions)
                                : getString(R.string.localai_model_ready_no_actions));
            case OrbitLocalStatus.DOWNLOADING:
                return getString(R.string.localai_model_downloading);
            case OrbitLocalStatus.QUEUED:
                return getString(R.string.localai_model_queued);
            case OrbitLocalStatus.WAITING_FOR_NETWORK:
                return getString(R.string.localai_model_waiting);
            case OrbitLocalStatus.VALIDATING:
                return getString(R.string.localai_model_validating);
            case OrbitLocalStatus.IMPORTING:
                return getString(R.string.localai_model_importing);
            case OrbitLocalStatus.PAUSED:
                return getString(R.string.localai_paused);
            case OrbitLocalStatus.INTERRUPTED:
                return getString(R.string.localai_model_interrupted);
            case OrbitLocalStatus.ERROR:
                return getString(R.string.localai_one_time, size);
            default:
                if (LocalModelStore.hasLegacyModel(this)) {
                    return getString(R.string.localai_model_legacy_found, size);
                }
                return getString(R.string.localai_model_default, size);
        }
    }

    private void buildModelActions(LinearLayout actions, boolean componentReady) {
        if (!componentReady || status == null) return;
        boolean allowed = DeviceCapabilityCheck.allowsLocalAi(DeviceCapabilityCheck.assess(this));
        switch (status.modelState) {
            case OrbitLocalStatus.NOT_INSTALLED:
            case OrbitLocalStatus.ERROR: {
                if (LocalModelStore.hasLegacyModel(this)) {
                    Button move = primaryButton(getString(R.string.localai_move_model));
                    move.setOnClickListener(v -> confirmMigration());
                    addPrimaryAction(actions, move);
                }
                Button download = primaryButton(
                        OrbitLocalStatus.ERROR.equals(status.modelState) ? getString(R.string.localai_try_again) : getString(R.string.localai_download_model));
                download.setEnabled(allowed);
                if (!allowed) download.setAlpha(0.5f);
                download.setOnClickListener(v -> {
                    OrbitLocalClient.startModelDownload(this);
                    bumpRefresh();
                });
                addPrimaryAction(actions, download);
                break;
            }
            case OrbitLocalStatus.PAUSED:
            case OrbitLocalStatus.INTERRUPTED: {
                // Whoever stopped it, the same two things are worth offering: continue from the
                // bytes already here, or give them up deliberately.
                Button resume = primaryButton(getString(R.string.localai_resume));
                resume.setOnClickListener(v -> {
                    OrbitLocalClient.startModelDownload(this);
                    bumpRefresh();
                });
                addPrimaryAction(actions, resume);
                Button discard = dangerButton(getString(R.string.localai_remove_downloaded));
                discard.setOnClickListener(v -> confirmDeleteModel(true));
                addDestructiveAction(actions, discard);
                break;
            }
            case OrbitLocalStatus.DOWNLOADING:
            case OrbitLocalStatus.QUEUED:
            case OrbitLocalStatus.WAITING_FOR_NETWORK: {
                Button pause = secondaryButton(getString(R.string.localai_pause));
                pause.setOnClickListener(v -> {
                    OrbitLocalClient.pauseModelDownload(this);
                    bumpRefresh();
                });
                addCompactAction(actions, pause);
                break;
            }
            case OrbitLocalStatus.IMPORTING: {
                Button stop = secondaryButton(getString(R.string.localai_stop_moving));
                stop.setOnClickListener(v -> {
                    OrbitLocalClient.abortModelImport(this);
                    bumpRefresh();
                });
                addCompactAction(actions, stop);
                break;
            }
            case OrbitLocalStatus.VALIDATING:
                break;
            case OrbitLocalStatus.READY: {
                if (!Prefs.PROVIDER_LOCAL.equals(AiProviders.active(this).id())) {
                    Button use = primaryButton(getString(R.string.ui_use_orbit_local_144ca50f));
                    use.setOnClickListener(v -> {
                        if (AiProviders.select(this, Prefs.PROVIDER_LOCAL)) {
                            Toast.makeText(this, getString(R.string.localai_now_active),
                                    Toast.LENGTH_SHORT).show();
                            rebuild();
                        }
                    });
                    addPrimaryAction(actions, use);
                }
                if (LocalModelStore.hasLegacyModel(this)) {
                    // The component has its own verified copy now, so Orbit's old one is pure
                    // duplication. Offered, never done silently.
                    Button reclaim = secondaryButton(getString(R.string.localai_free_old,
                            LocalModelStore.formatBytes(LocalModelStore.legacyBytes(this))));
                    reclaim.setOnClickListener(v -> confirmReclaimLegacy());
                    addPrimaryAction(actions, reclaim);
                }
                Button delete = dangerButton(getString(R.string.localai_delete_model));
                delete.setOnClickListener(v -> confirmDeleteModel(false));
                addDestructiveAction(actions, delete);
                break;
            }
            default:
                break;
        }
    }

    private void bumpRefresh() {
        poller.nudge();
    }

    // ---- migration --------------------------------------------------------------------------------

    /**
     * Moving the existing model, or — when there is no room to hold two copies — replacing it.
     *
     * <p>Copying needs space for a second complete model until the original goes away, so on a
     * full device Orbit says so plainly and offers the honest alternative instead of starting a
     * transfer that cannot finish.
     */
    private void confirmMigration() {
        if (!LocalModelStore.enoughStorageToMigrate(this)) {
            confirmLowStorageReplace();
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.localai_migrate_title))
                .setMessage(getString(R.string.localai_migrate_message))
                .setNegativeButton(R.string.ui_cancel_19766ed6, null)
                .setPositiveButton(getString(R.string.localai_move_model), (d, w) -> startMigration())
                .create();
        UiKit.styleOrbitDialog(dialog, this, false);
        dialog.show();
    }

    private void confirmLowStorageReplace() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.localai_lowspace_title))
                .setMessage(getString(R.string.localai_lowspace_message))
                .setNegativeButton(R.string.ui_cancel_19766ed6, null)
                .setPositiveButton(getString(R.string.localai_replace_copy), (d, w) -> {
                    LocalModelStore.deleteLegacy(this);
                    OrbitLocalClient.startModelDownload(this);
                    Toast.makeText(this, getString(R.string.localai_downloading_model_toast),
                            Toast.LENGTH_SHORT).show();
                    bumpRefresh();
                })
                .create();
        UiKit.styleOrbitDialog(dialog, this, true);
        dialog.show();
    }

    private void startMigration() {
        File legacy = LocalModelStore.legacyModelFile(this);
        new Thread(() -> {
            boolean started = OrbitLocalClient.startModelImport(
                    this, legacy, LocalModelStore.MODEL_SIZE_BYTES);
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (!started) {
                    Toast.makeText(this, getString(R.string.localai_migrate_failed),
                            Toast.LENGTH_LONG).show();
                }
                bumpRefresh();
            });
        }, "orbit-local-migrate").start();
    }

    /** Removing Orbit's now-duplicate copy, only after the component verified its own. */
    private void confirmReclaimLegacy() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.localai_reclaim_title))
                .setMessage(getString(R.string.localai_reclaim_message,
                        LocalModelStore.formatBytes(LocalModelStore.legacyBytes(this))))
                .setNegativeButton(getString(R.string.localai_keep_it), null)
                .setPositiveButton(getString(R.string.localai_free_up), (d, w) -> {
                    LocalModelStore.deleteLegacy(this);
                    Toast.makeText(this, getString(R.string.localai_old_removed_toast), Toast.LENGTH_SHORT).show();
                    rebuild();
                })
                .create();
        UiKit.styleOrbitDialog(dialog, this, true);
        dialog.show();
    }

    // ---- storage ----------------------------------------------------------------------------------

    // ---- the device-action model --------------------------------------------------------------------

    /**
     * The second, much smaller model: the one that turns a spoken instruction into an Orbit action.
     *
     * <p>Deliberately its own card, with its own state, its own storage figure and its own removal.
     * It is optional in exactly the way the chat model is optional, and Orbit Local's chat works
     * with or without it — as does every cloud provider, which never touches it at all.
     */
    private View actionModelCard() {
        boolean componentReady = OrbitLocalComponent.isUsable(this);
        LinearLayout card = card();
        card.addView(UiKit.text(this, getString(R.string.localai_actions_header), 12, UiKit.MUTED, true));

        String pillLabel;
        boolean accentPill = false;
        if (!componentReady) {
            pillLabel = getString(R.string.localai_requires_component);
        } else if (status == null) {
            pillLabel = getString(R.string.localai_checking);
        } else if (status.actionModelReady()) {
            boolean enabled = Prefs.localDeviceActions(this);
            pillLabel = enabled ? getString(R.string.localai_on) : getString(R.string.localai_off);
            accentPill = enabled;
        } else {
            pillLabel = status.actionStateLabel(this);
        }
        card.addView(titleRow(
                status == null || status.actionModelDisplayName.isEmpty()
                        ? getString(R.string.localai_action_model_title) : status.actionModelDisplayName,
                pillLabel, accentPill));

        TextView details = UiKit.text(this, actionModelDetails(componentReady), 13, UiKit.MUTED, false);
        details.setLineSpacing(0, 1.13f);
        details.setPadding(0, UiKit.dp(this, 6), 0, 0);
        card.addView(details);

        if (componentReady && status != null && status.actionShowsProgress()) {
            ProgressBar bar = UiKit.horizontalProgress(this);
            bar.setMax(1000);
            bar.setProgress(OrbitLocalStatus.VALIDATING.equals(status.actionModelState)
                    ? 1000 : status.actionProgressPerMille());
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 8));
            barLp.topMargin = UiKit.dp(this, 11);
            card.addView(bar, barLp);
            card.addView(actionProgressLine());
        }

        if (componentReady && status != null && !status.actionModelError.isEmpty()) {
            TextView error = UiKit.text(this, status.actionModelError, 13, UiKit.DANGER, false);
            error.setLineSpacing(0, 1.13f);
            error.setPadding(0, UiKit.dp(this, 8), 0, 0);
            card.addView(error);
        }

        if (componentReady && status != null && status.actionModelReady()) {
            card.addView(actionsEnabledRow());
            TextView covers = UiKit.text(this,
                    getString(R.string.localai_understands),
                    12, UiKit.MUTED, false);
            covers.setLineSpacing(0, 1.13f);
            covers.setPadding(0, UiKit.dp(this, 10), 0, 0);
            card.addView(covers);
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(0, UiKit.dp(this, 12), 0, 0);
        buildActionModelActions(actions, componentReady);
        if (actions.getChildCount() > 0) {
            card.addView(actions, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return card;
    }

    /** The on/off control for consulting the model, separate from having it installed. */
    private View actionsEnabledRow() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, UiKit.dp(this, 12), 0, 0);
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(UiKit.text(this, getString(R.string.localai_use_for_commands), 15, UiKit.TEXT, false));
        labels.addView(UiKit.text(this,
                getString(R.string.localai_own_first),
                12, UiKit.MUTED, false));
        row.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        OrbitSwitch toggle = new OrbitSwitch(this);
        toggle.setChecked(Prefs.localDeviceActions(this), false);
        toggle.setOnCheckedChangeListener((view, checked) -> {
            Prefs.get(this).edit().putBoolean(Prefs.LOCAL_DEVICE_ACTIONS, checked).apply();
            rebuild();
        });
        row.addView(toggle);
        return row;
    }

    private TextView actionProgressLine() {
        String have = LocalModelStore.formatBytes(status.actionModelBytes);
        String total = LocalModelStore.formatBytes(status.actionModelSizeBytes);
        String text;
        switch (status.actionModelState) {
            case OrbitLocalStatus.VALIDATING: text = getString(R.string.localai_verifying); break;
            case OrbitLocalStatus.QUEUED: text = getString(R.string.localai_progress_starting, have, total); break;
            case OrbitLocalStatus.WAITING_FOR_NETWORK:
                text = getString(R.string.localai_progress_waiting, have, total); break;
            default:
                text = getString(R.string.localai_progress_percent, have, total, status.actionProgressPerMille() / 10);
        }
        TextView line = UiKit.text(this, text, 12, UiKit.MUTED, false);
        line.setPadding(0, UiKit.dp(this, 6), 0, 0);
        return line;
    }

    private String actionModelDetails(boolean componentReady) {
        String size = status == null || status.actionModelSizeBytes <= 0L
                ? getString(R.string.localai_about_520)
                : LocalModelStore.formatBytes(status.actionModelSizeBytes);
        if (!componentReady) {
            if (!OrbitDistribution.installsOrbitLocalComponent()) {
                return getString(R.string.localai_action_lets_play, getString(R.string.localai_play_unavailable));
            }
            return getString(R.string.localai_action_lets_install);
        }
        if (status == null) return getString(R.string.localai_checking_component);
        switch (status.actionModelState) {
            case OrbitLocalStatus.READY:
                return getString(R.string.localai_action_ready, size);
            case OrbitLocalStatus.DOWNLOADING:
                return getString(R.string.localai_action_downloading);
            case OrbitLocalStatus.QUEUED:
                return getString(R.string.localai_action_queued);
            case OrbitLocalStatus.WAITING_FOR_NETWORK:
                return getString(R.string.localai_action_waiting);
            case OrbitLocalStatus.VALIDATING:
                return getString(R.string.localai_model_validating);
            case OrbitLocalStatus.PAUSED:
                return getString(R.string.localai_paused);
            case OrbitLocalStatus.INTERRUPTED:
                return getString(R.string.localai_action_interrupted);
            case OrbitLocalStatus.ERROR:
                return getString(R.string.localai_one_time, size);
            default:
                return getString(R.string.localai_action_default, size);
        }
    }

    private void buildActionModelActions(LinearLayout actions, boolean componentReady) {
        if (!componentReady || status == null) return;
        boolean allowed = DeviceCapabilityCheck.allowsLocalAi(DeviceCapabilityCheck.assess(this));
        switch (status.actionModelState) {
            case OrbitLocalStatus.NOT_INSTALLED:
            case OrbitLocalStatus.ERROR: {
                Button download = primaryButton(
                        OrbitLocalStatus.ERROR.equals(status.actionModelState)
                                ? getString(R.string.localai_try_again) : getString(R.string.localai_download_action));
                download.setEnabled(allowed);
                if (!allowed) download.setAlpha(0.5f);
                download.setOnClickListener(v -> {
                    OrbitLocalClient.startActionModelDownload(this);
                    bumpRefresh();
                });
                addPrimaryAction(actions, download);
                break;
            }
            case OrbitLocalStatus.PAUSED:
            case OrbitLocalStatus.INTERRUPTED: {
                Button resume = primaryButton(getString(R.string.localai_resume));
                resume.setOnClickListener(v -> {
                    OrbitLocalClient.startActionModelDownload(this);
                    bumpRefresh();
                });
                addPrimaryAction(actions, resume);
                Button discard = dangerButton(getString(R.string.localai_remove_downloaded));
                discard.setOnClickListener(v -> confirmDeleteActionModel(true));
                addDestructiveAction(actions, discard);
                break;
            }
            case OrbitLocalStatus.DOWNLOADING:
            case OrbitLocalStatus.QUEUED:
            case OrbitLocalStatus.WAITING_FOR_NETWORK: {
                Button pause = secondaryButton(getString(R.string.localai_pause));
                pause.setOnClickListener(v -> {
                    OrbitLocalClient.pauseActionModelDownload(this);
                    bumpRefresh();
                });
                addCompactAction(actions, pause);
                break;
            }
            case OrbitLocalStatus.VALIDATING:
                break;
            case OrbitLocalStatus.READY: {
                Button delete = dangerButton(getString(R.string.localai_delete_action));
                delete.setOnClickListener(v -> confirmDeleteActionModel(false));
                addDestructiveAction(actions, delete);
                break;
            }
            default:
                break;
        }
    }

    private void confirmDeleteActionModel(boolean partialOnly) {
        long bytes = status == null ? 0L : status.actionModelTotalBytes;
        String size = LocalModelStore.formatBytes(bytes);
        String title = partialOnly ? getString(R.string.localai_remove_downloaded_q) : getString(R.string.localai_remove_action_q);
        String message = partialOnly
                ? getString(R.string.localai_partial_message, size)
                : getString(R.string.localai_action_remove_message, size);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton(R.string.ui_cancel_19766ed6, null)
                .setPositiveButton(partialOnly ? getString(R.string.localai_remove) : getString(R.string.localai_remove_model), (d, w) -> {
                    OrbitLocalClient.deleteActionModel(this);
                    OrbitLocalProvider.invalidateStatus();
                    if (!partialOnly) {
                        Toast.makeText(this, getString(R.string.localai_action_removed_toast), Toast.LENGTH_SHORT).show();
                    }
                    bumpRefresh();
                })
                .create();
        UiKit.styleOrbitDialog(dialog, this, true);
        dialog.show();
    }

    private View storageCard() {
        LinearLayout card = card();
        card.addView(UiKit.text(this, getString(R.string.localai_storage_header), 12, UiKit.MUTED, true));

        long componentBytes = OrbitLocalComponent.installedApkBytes(this);
        long modelBytes = status == null ? 0L : status.modelTotalBytes;
        long actionBytes = status == null ? 0L : status.actionModelTotalBytes;
        long legacyBytes = LocalModelStore.legacyBytes(this);
        long total = componentBytes + modelBytes + actionBytes + legacyBytes;

        LinearLayout totalRow = new LinearLayout(this);
        totalRow.setGravity(Gravity.CENTER_VERTICAL);
        totalRow.setPadding(0, UiKit.dp(this, 7), 0, 0);
        totalRow.addView(UiKit.text(this, total > 0L ? LocalModelStore.formatBytes(total) : getString(R.string.localai_nothing_installed),
                17, UiKit.TEXT, true), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(totalRow);

        if (total <= 0L) {
            TextView none = UiKit.text(this,
                    getString(R.string.localai_no_storage),
                    13, UiKit.MUTED, false);
            none.setLineSpacing(0, 1.13f);
            none.setPadding(0, UiKit.dp(this, 6), 0, 0);
            card.addView(none);
            return card;
        }

        if (componentBytes > 0L) {
            card.addView(storageLine(getString(R.string.localai_storage_component), LocalModelStore.formatBytes(componentBytes)));
        }
        if (modelBytes > 0L) {
            String label = status != null && status.modelReady()
                    ? LocalModelStore.MODEL_DISPLAY_NAME
                    : getString(R.string.localai_model_partial, LocalModelStore.MODEL_DISPLAY_NAME);
            card.addView(storageLine(label, LocalModelStore.formatBytes(modelBytes)));
        }
        if (actionBytes > 0L) {
            String label = status != null && status.actionModelReady()
                    ? getString(R.string.localai_storage_action) : getString(R.string.localai_storage_action_partial);
            card.addView(storageLine(label, LocalModelStore.formatBytes(actionBytes)));
        }
        if (legacyBytes > 0L) {
            card.addView(storageLine(
                    LocalModelStore.hasLegacyModel(this) ? getString(R.string.localai_earlier_copy) : getString(R.string.localai_earlier_partial),
                    LocalModelStore.formatBytes(legacyBytes)));
        }
        return card;
    }

    private View storageLine(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, UiKit.dp(this, 7), 0, 0);
        row.addView(UiKit.text(this, "· " + label, 13, UiKit.MUTED, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(UiKit.text(this, value, 13, UiKit.TEXT, false));
        return row;
    }

    // ---- removal ----------------------------------------------------------------------------------

    /** True when there is anything left of Orbit Local on this device to remove. */
    private boolean hasAnythingToRemove() {
        return OrbitLocalComponent.isInstalled(this) || LocalModelStore.legacyBytes(this) > 0L;
    }

    private View removeCard() {
        boolean componentInstalled = OrbitLocalComponent.isInstalled(this);
        LinearLayout card = card();
        card.addView(UiKit.text(this, getString(R.string.localai_remove_orbit_local), 12, UiKit.MUTED, true));
        TextView details = UiKit.text(this,
                componentInstalled
                        ? getString(R.string.localai_remove_details_full)
                        : getString(R.string.localai_remove_details_legacy),
                13, UiKit.MUTED, false);
        details.setLineSpacing(0, 1.13f);
        details.setPadding(0, UiKit.dp(this, 7), 0, 0);
        card.addView(details);

        if (!removalMessage.isEmpty()) {
            TextView outcome = UiKit.text(this, removalMessage, 13, UiKit.MUTED, false);
            outcome.setLineSpacing(0, 1.13f);
            outcome.setPadding(0, UiKit.dp(this, 9), 0, 0);
            card.addView(outcome);
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(0, UiKit.dp(this, 12), 0, 0);
        Button remove = dangerButton(componentInstalled
                ? getString(R.string.localai_remove_orbit_local) : getString(R.string.localai_remove_old_data));
        remove.setOnClickListener(v -> {
            if (componentInstalled) confirmRemoveOrbitLocal();
            else confirmRemoveLegacyOnly();
        });
        addPrimaryAction(actions, remove);
        card.addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    void confirmRemoveOrbitLocal() {
        long freed = OrbitLocalComponent.installedApkBytes(this)
                + (status == null ? 0L : status.modelTotalBytes)
                + LocalModelStore.legacyBytes(this);
        boolean active = Prefs.PROVIDER_LOCAL.equals(AiProviders.active(this).id());
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.localai_remove_q))
                .setMessage(getString(R.string.localai_remove_message,
                        LocalModelStore.formatBytes(freed))
                        + (active ? getString(R.string.localai_chatgpt_active_inline) : "")
                        + getString(R.string.localai_android_confirm))
                .setNegativeButton(R.string.ui_cancel_19766ed6, null)
                .setPositiveButton(getString(R.string.localai_remove_orbit_local), null)
                .create();
        UiKit.styleOrbitDialog(dialog, this, true, () -> {
            Button remove = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (remove != null) {
                remove.setOnClickListener(v -> UiKit.handoffOrbitDialogToSystemSurface(
                        dialog, () -> removeOrbitLocal(SCOPE_EVERYTHING)));
            }
        });
        dialog.show();
    }

    /**
     * Android's uninstall first. Nothing of the user's is deleted before it answers.
     *
     * <p>Orbit never claims the component was removed. Whether it actually went is read back from
     * the package manager when this screen resumes, and only a confirmed absence lets any cleanup
     * run at all.
     */
    private void removeOrbitLocal(String scope) {
        removalMessage = "";
        setRemovalPending(scope);
        OrbitLocalUninstaller.Launch launch = OrbitLocalUninstaller.request(this);
        if (launch == OrbitLocalUninstaller.Launch.LAUNCHED) return;

        clearRemovalPending();
        if (launch == OrbitLocalUninstaller.Launch.NOT_INSTALLED) {
            // Removed from Android Settings while this screen was open. Settle the rest.
            finishOrbitSideRemoval(scope);
            removalMessage = getString(R.string.localai_already_removed);
        } else {
            // Never silent again. The button did nothing, and the user is told so along with the
            // one thing they can do about it.
            removalMessage = OrbitLocalUninstaller.message(this, 
                    OrbitLocalUninstaller.Stage.NOT_LAUNCHED);
        }
        rebuild();
    }

    /**
     * The case where a model outlived the component: nothing to uninstall, only data to delete.
     *
     * <p>Happens on a phone that downloaded the 1.6 GB model before Orbit Local was modular and
     * never installed the component, or one where the component was removed from Android Settings
     * with the migration never run. Android has no part to play, so this deletes directly — after
     * the same explicit confirmation.
     */
    private void confirmRemoveLegacyOnly() {
        long bytes = LocalModelStore.legacyBytes(this);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.localai_remove_old_q))
                .setMessage(getString(R.string.localai_remove_old_message, LocalModelStore.formatBytes(bytes)))
                .setNegativeButton(R.string.ui_cancel_19766ed6, null)
                .setPositiveButton(getString(R.string.localai_remove), (d, w) -> {
                    LocalModelStore.deleteLegacy(this);
                    OrbitLocalInstaller.cleanup(this);
                    OrbitLocalProvider.invalidateStatus();
                    if (Prefs.PROVIDER_LOCAL.equals(Prefs.provider(this))) {
                        AiProviders.select(this, Prefs.PROVIDER_CHATGPT);
                    }
                    removalMessage = getString(R.string.localai_old_removed);
                    rebuild();
                })
                .create();
        UiKit.styleOrbitDialog(dialog, this, true);
        dialog.show();
    }

    // ---- removing just the component ---------------------------------------------------------------

    /**
     * Removing the component while leaving the rest of Orbit alone.
     *
     * <p>Separate from the global removal because they are genuinely different requests, and the
     * dialog says the one thing a person could not otherwise know: the model lives in the
     * component's own private app storage, so Android takes it along with the package. Pretending
     * the model could be kept would be a promise the platform's storage model cannot honour.
     */
    void confirmUninstallComponent() {
        long modelBytes = status == null ? 0L : status.modelTotalBytes;
        String modelWarning = modelBytes > 0L
                ? getString(R.string.localai_uninstall_model_warning,
                        LocalModelStore.formatBytes(modelBytes))
                : "";
        boolean active = Prefs.PROVIDER_LOCAL.equals(AiProviders.active(this).id());
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.localai_uninstall_q))
                .setMessage(getString(R.string.localai_uninstall_message) + modelWarning
                        + (active ? getString(R.string.localai_chatgpt_active_para) : "")
                        + getString(R.string.localai_android_confirm))
                .setNegativeButton(R.string.ui_cancel_19766ed6, null)
                .setPositiveButton(getString(R.string.localai_uninstall_component), null)
                .create();
        UiKit.styleOrbitDialog(dialog, this, true, () -> {
            Button uninstall = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (uninstall != null) {
                uninstall.setOnClickListener(v -> UiKit.handoffOrbitDialogToSystemSurface(
                        dialog, () -> removeOrbitLocal(SCOPE_COMPONENT)));
            }
        });
        dialog.show();
    }

    private void confirmDeleteModel(boolean partialOnly) {
        boolean active = Prefs.PROVIDER_LOCAL.equals(AiProviders.active(this).id());
        long bytes = status == null ? LocalModelStore.MODEL_SIZE_BYTES : status.modelTotalBytes;
        String title;
        String message;
        if (partialOnly) {
            title = getString(R.string.localai_remove_downloaded_q);
            message = getString(R.string.localai_partial_message, LocalModelStore.formatBytes(bytes));
        } else {
            title = getString(R.string.localai_remove_named_q, LocalModelStore.MODEL_DISPLAY_NAME);
            message = getString(R.string.localai_model_remove_message, LocalModelStore.formatBytes(bytes))
                    + (active ? getString(R.string.localai_chatgpt_active_inline) : "")
                    + getString(R.string.localai_model_remove_tail);
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton(R.string.ui_cancel_19766ed6, null)
                .setPositiveButton(partialOnly ? getString(R.string.localai_remove) : getString(R.string.localai_remove_model), (d, w) -> {
                    boolean wasActive = Prefs.PROVIDER_LOCAL.equals(AiProviders.active(this).id());
                    OrbitLocalClient.deleteModel(this);
                    OrbitLocalProvider.invalidateStatus();
                    if (wasActive) {
                        AiProviders.select(this, Prefs.PROVIDER_CHATGPT);
                        Toast.makeText(this, getString(R.string.localai_removed_chatgpt_toast),
                                Toast.LENGTH_LONG).show();
                    } else if (!partialOnly) {
                        Toast.makeText(this, getString(R.string.localai_local_removed_toast), Toast.LENGTH_SHORT).show();
                    }
                    bumpRefresh();
                })
                .create();
        UiKit.styleOrbitDialog(dialog, this, true);
        dialog.show();
    }

    // ---- shared button layout ---------------------------------------------------------------------

    /** Full-width primary, in Orbit's stacked settings button language. */
    private void addPrimaryAction(LinearLayout actions, Button button) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 46));
        if (actions.getChildCount() > 0) lp.topMargin = UiKit.dp(this, 8);
        actions.addView(button, lp);
    }

    /** Compact right-aligned secondary action; never squeezed beside a primary. */
    private void addCompactAction(LinearLayout actions, Button button) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.END);
        row.addView(button, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(this, 40)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (actions.getChildCount() > 0) lp.topMargin = UiKit.dp(this, 8);
        actions.addView(row, lp);
    }

    /** Restrained destructive row, separated from the primary action by deliberate space. */
    private void addDestructiveAction(LinearLayout actions, Button button) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 44));
        if (actions.getChildCount() > 0) lp.topMargin = UiKit.dp(this, 14);
        actions.addView(button, lp);
    }

    private Button primaryButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(UiKit.onAccent(this));
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setBackground(UiKit.ripple(UiKit.accent(this), UiKit.onAccent(this), 15, this));
        b.setMinHeight(0); b.setMinimumHeight(0); b.setStateListAnimator(null);
        UiKit.pressScale(b);
        return b;
    }

    private Button secondaryButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(UiKit.TEXT);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2, Color.rgb(53, 58, 72), UiKit.accent(this), 15, this));
        b.setMinHeight(0); b.setMinimumHeight(0); b.setStateListAnimator(null);
        UiKit.pressScale(b);
        return b;
    }

    /** Restrained destructive treatment, matching the Extensions manager's Remove control. */
    private Button dangerButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(UiKit.DANGER);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setBackground(UiKit.rippleOutlined(
                UiKit.blend(UiKit.DANGER, UiKit.SURFACE_2, 0.08f),
                UiKit.withAlpha(UiKit.DANGER, 110), UiKit.DANGER, 15, this));
        b.setMinHeight(0); b.setMinimumHeight(0); b.setStateListAnimator(null);
        UiKit.pressScale(b);
        return b;
    }
}
