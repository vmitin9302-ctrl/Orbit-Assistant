package com.orbit.assistant;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.PathInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Native, resumable first-run setup. All durable configuration stays in existing stores. */
public final class OnboardingActivity extends Activity {
    private static final String EXTRA_MANUAL = "manual";
    private static final int REQ_ASSISTANT_SETTINGS = 940;
    private static final int REQ_RESTORE_BACKUP = 941;
    private static final int REQ_NOTIFICATIONS = 942;
    private static final int REQ_LOCATION = 943;
    private static final int REQ_MICROPHONE = 944;
    private static final int REQ_CAMERA = 945;
    private static final int REQ_CONTACTS = 946;
    private static final int REQ_TRIGGER_ALERTS = 947;
    private static final int REQ_ROUTINE_FINE_LOCATION = 948;
    private static final int REQ_ROUTINE_BACKGROUND_LOCATION = 949;

    private int step;
    private boolean manual;
    private boolean resumedOnce;
    /** Whether the collapsed "More provider options" area is open. */
    private boolean advancedExpanded;
    private boolean completionCelebrationPlayed;
    private String appliedAppearance = "";
    private FrameLayout completionMarkHost;
    private View completionMark;

    private final ChatGptAuth.LoginCallback loginCallback = new ChatGptAuth.LoginCallback() {
        @Override public void onSuccess(ChatGptAuth.AccountInfo account) {
            runOnUiThread(() -> {
                if (!canUpdateUi()) return;
                Toast.makeText(OnboardingActivity.this,
                        getString(R.string.ui_chatgpt_connected_to_orbit_61c51250), Toast.LENGTH_LONG).show();
                render();
            });
        }

        @Override public void onError(String message) {
            runOnUiThread(() -> {
                if (!canUpdateUi()) return;
                render();
                showMessage(getString(R.string.ui_chatgpt_sign_in_could_not_complete_2ffa82b4), message);
            });
        }
    };

    /** Hears how a browser sign-in ended. Attached while onboarding is in front. */
    private final ChatGptBrowserAuth.Listener browserListener = new ChatGptBrowserAuth.Listener() {
         public void onSignedIn(ChatGptAuth.AccountInfo account) {
            if (!canUpdateUi()) return;
            Toast.makeText(OnboardingActivity.this, getString(R.string.ui_chatgpt_connected_to_orbit_61c51250), Toast.LENGTH_LONG).show();
            render();
        }

         public void onFailed(String message, boolean offerCodeSignIn) {
            if (!canUpdateUi()) return;
            render();
            AlertDialog dialog = new AlertDialog.Builder(OnboardingActivity.this)
                    .setTitle(getString(R.string.ui_chatgpt_sign_in_could_not_complete_2ffa82b4))
                    .setMessage(message)
                    .setPositiveButton(getString(R.string.ui_use_code_sign_in_25c62930), (d, w) -> startCodeSignIn())
                    .setNegativeButton(getString(R.string.ui_close_7d9eb7ac), null)
                    .create();
            UiKit.styleOrbitDialog(dialog, OnboardingActivity.this, false);
            dialog.show();
        }

         public void onCancelled() {
            if (canUpdateUi()) render();
        }
    };

    public static Intent freshInstallIntent(Context context) {
        return new Intent(context, OnboardingActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    public static Intent manualIntent(Context context) {
        return new Intent(context, OnboardingActivity.class).putExtra(EXTRA_MANUAL, true)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        manual = getIntent() != null && getIntent().getBooleanExtra(EXTRA_MANUAL, false);
        if (savedInstanceState != null) {
            step = savedInstanceState.getInt("step", 0);
            advancedExpanded = savedInstanceState.getBoolean("advancedExpanded", false);
            completionCelebrationPlayed = savedInstanceState.getBoolean(
                    "completionCelebrationPlayed", false);
        }
        else if (manual) step = 0;
        else step = OnboardingState.currentStep(this);
        // Someone already on an advanced provider should not have to go looking for it.
        if (savedInstanceState == null
                && (Prefs.PROVIDER_RELAY.equals(Prefs.provider(this))
                        || Prefs.PROVIDER_OPENROUTER.equals(Prefs.provider(this)))) {
            advancedExpanded = true;
        }
        persistStep();
        render();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra(EXTRA_MANUAL, false)) {
            manual = true;
            step = 0;
            completionCelebrationPlayed = false;
            persistStep();
            render();
        }
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        outState.putInt("step", step);
        outState.putBoolean("advancedExpanded", advancedExpanded);
        outState.putBoolean("completionCelebrationPlayed", completionCelebrationPlayed);
        super.onSaveInstanceState(outState);
    }

    @Override protected void onResume() {
        super.onResume();
        UiPresence.enter(this);
        String appearance = UiKit.appearanceSignature(this);
        if (resumedOnce || !appearance.equals(appliedAppearance)) render();
        resumedOnce = true;
        ChatGptBrowserAuth.attach(browserListener);
        if (step == 1 && !ChatGptAuth.isSignedIn(this) &&
                ChatGptAuth.resumePendingDeviceCode(this, loginCallback)) {
            Toast.makeText(this, getString(R.string.ui_finishing_secure_chatgpt_sign_in_7777d7c7), Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onPause() {
        UiPresence.leave(this);
        ChatGptBrowserAuth.detach(browserListener);
        super.onPause();
    }

    @Override public void onBackPressed() {
        if (step > 0) {
            if (step == 7) completionCelebrationPlayed = false;
            step--;
            persistStep();
            render();
        } else if (manual) {
            confirmExitSetup();
        } else {
            confirmSkip();
        }
    }

    private void render() {
        UiKit.syncTheme(this);
        Window window = getWindow();
        window.setStatusBarColor(UiKit.BG);
        window.setNavigationBarColor(UiKit.BG);
        View root = buildPage();
        setContentView(root);
        UiKit.applyActivityInsets(this, root, true);
        appliedAppearance = UiKit.appearanceSignature(this);
        if (step == 7 && !completionCelebrationPlayed) {
            completionCelebrationPlayed = true;
            root.post(this::playCompletionCelebration);
        }
    }

    private View buildPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        OrbitBackground.applyPage(scroll);
        scroll.setForceDarkAllowed(false);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int horizontal = UiKit.dp(this, 20);
        page.setPadding(horizontal, UiKit.dp(this, 22), horizontal, UiKit.dp(this, 38));
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int contentWidth = Math.min(screenWidth, UiKit.dp(this, 720));
        ScrollView.LayoutParams pageLp = new ScrollView.LayoutParams(
                Math.max(UiKit.dp(this, 280), contentWidth), ViewGroup.LayoutParams.WRAP_CONTENT);
        pageLp.gravity = Gravity.CENTER_HORIZONTAL;
        scroll.addView(page, pageLp);

        page.addView(header());
        if (step == 0) buildWelcome(page);
        else if (step == 1) buildConnect(page);
        else if (step == 2) buildAssistant(page);
        else if (step == 3) buildQuickAccess(page);
        else if (step == 4) buildCapabilities(page);
        else if (step == 5) buildPersonalize(page);
        else if (step == 6) buildStarterRoutine(page);
        else buildFinish(page);

        addNavigation(page);
        UiKit.applyTypography(page);
        return scroll;
    }

    private View header() {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout markHost = new FrameLayout(this);
        markHost.setClipChildren(false);
        markHost.setClipToPadding(false);
        View mark = UiKit.orbitMark(this, 38);
        markHost.addView(mark, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        int markBoxSize = UiKit.dp(this, step == 7 ? 64 : 44);
        LinearLayout.LayoutParams markLp = new LinearLayout.LayoutParams(
                markBoxSize, markBoxSize);
        markLp.rightMargin = UiKit.dp(this, 10);
        header.addView(markHost, markLp);
        completionMarkHost = step == 7 ? markHost : null;
        completionMark = step == 7 ? mark : null;

        LinearLayout progress = new LinearLayout(this);
        progress.setOrientation(LinearLayout.VERTICAL);
        progress.addView(UiKit.text(this, step < 7 ? (step + 1) + getString(R.string.ui_of_7_42c4647a) : getString(R.string.ui_setup_complete_d0e3858f),
                11, UiKit.accent(this), true));
        TextView bar = UiKit.text(this, progressDots(), 16, UiKit.MUTED, false);
        bar.setLetterSpacing(.08f);
        progress.addView(bar);
        header.addView(progress, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button skip = quietButton(manual ? getString(R.string.ui_exit_setup_a8b459d5) : getString(R.string.ui_skip_setup_59e693cd));
        skip.setOnClickListener(v -> {
            if (manual) confirmExitSetup(); else confirmSkip();
        });
        header.addView(skip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(this, 42)));
        return header;
    }

    private String progressDots() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            if (i > 0) out.append("  ");
            out.append(i <= Math.min(step, 6) ? "●" : "○");
        }
        return out.toString();
    }

    private void buildWelcome(LinearLayout page) {
        addTitle(page, getString(R.string.ui_welcome_to_orbit_9c4a3ca1),
                getString(R.string.ui_a_customizable_android_assistant_for_quick_an_26b8d046));
        LinearLayout card = card();
        card.addView(feature(getString(R.string.ui_ask_quickly_110e1409), getString(R.string.ui_use_the_app_quick_settings_or_your_system_ass_e45e0cde)));
        card.addView(feature(getString(R.string.ui_act_locally_c0e2f78d), getString(R.string.ui_run_device_actions_reminders_and_editable_rou_2737e70c)));
        card.addView(feature(getString(R.string.ui_keep_control_7971a6c2), getString(R.string.ui_choose_what_orbit_can_access_and_change_it_la_dabef9e7)));
        page.addView(card, cardLp());

        Button restore = secondaryButton(getString(R.string.ui_restore_orbit_backup_f0a34e16));
        restore.setOnClickListener(v -> chooseBackup());
        page.addView(restore, buttonLp());
        TextView note = UiKit.text(this,
                getString(R.string.ui_a_restored_backup_brings_back_supported_local_dcfde50f),
                12, UiKit.MUTED, false);
        note.setPadding(UiKit.dp(this, 3), UiKit.dp(this, 8), UiKit.dp(this, 3), 0);
        page.addView(note);
    }

    /**
     * Provider setup, presented as one page with a clear hierarchy rather than as a longer flow.
     *
     * <p>Orbit genuinely has four backends now, and the old copy described only two of them, one
     * of which it still presented as an OpenAI-API fallback. A first-run page cannot explain all
     * four without becoming a settings screen, so this shows the recommended choice, the optional
     * on-device one, and collapses everything advanced.
     *
     * <p>Availability, status text, and what each provider can and cannot do all come from
     * {@link AiProviders} rather than from a second set of hardcoded facts here. Onboarding writes
     * the curated sentences; the provider layer decides whether they are currently true.
     */
    private void buildConnect(LinearLayout page) {
        addTitle(page, getString(R.string.ui_connect_orbit_51a3d76f),
                getString(R.string.ui_choose_how_orbit_thinks_chatgpt_is_the_recomm_7f0fb2f0));
        page.addView(chatGptCard(), cardLp());
        page.addView(orbitLocalCard(), cardLp());

        Button more = quietButton(advancedExpanded
                ? getString(R.string.ui_hide_more_provider_options_e2abd48f) : getString(R.string.ui_more_provider_options_3fa79cbc));
        more.setOnClickListener(v -> {
            advancedExpanded = !advancedExpanded;
            render();
        });
        page.addView(more, buttonLp());

        if (advancedExpanded) {
            page.addView(openRouterCard(), cardLp());
            buildRelayFallback(page);
        }
    }

    /** The recommended provider. Its existing secure device-code sign-in is untouched. */
    private LinearLayout chatGptCard() {
        AiProvider provider = AiProviders.byId(Prefs.PROVIDER_CHATGPT);
        boolean connected = provider.status(this) == AiProvider.Status.READY;
        boolean active = Prefs.PROVIDER_CHATGPT.equals(Prefs.provider(this));

        LinearLayout card = card();
        card.addView(providerHeading(provider.displayName(), getString(R.string.ui_recommended_d70604e8)));
        card.addView(providerStatusLine(connected, provider.statusDetail(this)));
        addCardDescription(card,
                getString(R.string.ui_orbit_s_fullest_experience_stronger_answers_h_a7ced39f));
        addCapabilityLine(card, provider);

        TextView help = UiKit.text(this, connected
                ? getString(R.string.ui_orbit_keeps_using_the_securely_stored_session_aabaafe4)
                : getString(R.string.ui_your_browser_handles_authorization_orbit_neve_ad28b253),
                12, UiKit.MUTED, false);
        help.setPadding(0, 0, 0, UiKit.dp(this, 12));
        card.addView(help);

        Button action = primaryButton(connected
                ? (active ? getString(R.string.ui_chatgpt_active_a6ed644f) : getString(R.string.ui_use_chatgpt_f8f99c44))
                : getString(R.string.ui_sign_in_with_chatgpt_f6de6480));
        action.setEnabled(!connected || !active);
        action.setAlpha(connected && active ? .68f : 1f);
        action.setOnClickListener(v -> {
            if (connected) {
                AiProviders.select(this, Prefs.PROVIDER_CHATGPT);
                render();
            } else startChatGptLogin();
        });
        card.addView(action, new LinearLayout.LayoutParams(-1, UiKit.dp(this, 48)));
        if (!connected) {
            // The fallback, quiet and always present: never the recommended path, never hidden.
            Button code = new Button(this);
            code.setText(getString(R.string.ui_having_trouble_use_code_sign_in_62f97b7e));
            code.setAllCaps(false);
            code.setTextSize(13);
            code.setTextColor(UiKit.accent(this));
            code.setBackground(UiKit.ripple(android.graphics.Color.TRANSPARENT, UiKit.accent(this), 14, this));
            code.setStateListAnimator(null);
            code.setOnClickListener(v -> startCodeSignIn());
            card.addView(code, new LinearLayout.LayoutParams(-1, UiKit.dp(this, 44)));
        }
        return card;
    }

    /**
     * The optional on-device provider.
     *
     * <p>Never forced, and never a second downloader: setup opens the dedicated Orbit Local screen
     * that already owns the component install, the model download, and their progress, and this
     * page simply reflects whatever state that screen left behind when the user returns.
     */
    private LinearLayout orbitLocalCard() {
        AiProvider provider = AiProviders.byId(Prefs.PROVIDER_LOCAL);
        AiProvider.Status status = provider.status(this);
        boolean ready = status == AiProvider.Status.READY;
        boolean active = ready && Prefs.PROVIDER_LOCAL.equals(Prefs.provider(this));
        boolean supported = status != AiProvider.Status.UNSUPPORTED;

        LinearLayout card = card();
        card.addView(providerHeading(provider.displayName(), getString(R.string.ui_optional_59be7133)));
        card.addView(providerStatusLine(ready, provider.statusDetail(this)));
        addCardDescription(card,
                getString(R.string.ui_runs_privately_on_this_phone_and_keeps_workin_98f90f9a));
        addCapabilityLine(card, provider);

        String missing = missingCapabilitySentence(provider.capabilities());
        if (!missing.isEmpty() && supported) {
            TextView limits = UiKit.text(this, missing, 12, UiKit.MUTED, false);
            limits.setLineSpacing(0, 1.12f);
            limits.setPadding(0, 0, 0, UiKit.dp(this, 12));
            card.addView(limits);
        }

        if (!supported) return card;
        if (!ready && !OrbitDistribution.installsOrbitLocalComponent()) {
            // A setup button here would lead to a screen that cannot set anything up.
            TextView unavailable = UiKit.text(this, LocalAiActivity.PLAY_UNAVAILABLE,
                    12, UiKit.MUTED, false);
            unavailable.setLineSpacing(0, 1.12f);
            card.addView(unavailable);
            return card;
        }

        Button action = primaryButton(active ? getString(R.string.ui_orbit_local_active_38d55fa2)
                : ready ? getString(R.string.ui_use_orbit_local_144ca50f) : getString(R.string.ui_set_up_orbit_local_6660808d));
        action.setEnabled(!active);
        action.setAlpha(active ? .68f : 1f);
        action.setOnClickListener(v -> {
            if (ready) {
                AiProviders.select(this, Prefs.PROVIDER_LOCAL);
                render();
            } else {
                // The one screen that owns the 35 MB component and the model download. Setup
                // returns here, and onResume re-reads the real state.
                startActivity(new Intent(this, LocalAiActivity.class));
            }
        });
        card.addView(action, new LinearLayout.LayoutParams(-1, UiKit.dp(this, 48)));
        return card;
    }

    /**
     * OpenRouter (0.8.3.0-beta.6+): a real optional provider, kept behind More provider options so
     * ChatGPT and Orbit Local stay the first choices. Connecting happens in AI Providers, where
     * Sign in with OpenRouter lives; nothing here connects anything on the user's behalf.
     */
    private LinearLayout openRouterCard() {
        AiProvider provider = AiProviders.byId(Prefs.PROVIDER_OPENROUTER);
        LinearLayout card = card();
        card.addView(providerHeading(provider.displayName(), getString(R.string.ui_optional_59be7133)));
        boolean ready = provider.status(this) == AiProvider.Status.READY;
        card.addView(providerStatusLine(ready, provider.statusDetail(this)));
        addCardDescription(card,
                getString(R.string.ui_many_ai_models_through_one_openrouter_account_d936f413));
        Button manage = secondaryButton(ready ? getString(R.string.ui_open_provider_settings_bbcae464) : getString(R.string.ui_set_up_openrouter_b9f8c5d0));
        manage.setOnClickListener(v -> startActivity(new Intent(this, AiProvidersActivity.class)));
        card.addView(manage, new LinearLayout.LayoutParams(-1, UiKit.dp(this, 46)));
        return card;
    }

    private LinearLayout providerHeading(String name, String tag) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(UiKit.text(this, name, 17, UiKit.TEXT, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView pill = UiKit.text(this, tag, 11, UiKit.accent(this), true);
        pill.setBackground(UiKit.outlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.accent(this), 82), 99, this));
        pill.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 4), UiKit.dp(this, 10), UiKit.dp(this, 4));
        row.addView(pill);
        return row;
    }

    private TextView providerStatusLine(boolean ready, String detail) {
        TextView status = UiKit.text(this, (ready ? "✓ " : "○ ") + detail, 13,
                ready ? UiKit.SUCCESS : UiKit.TEXT, true);
        status.setPadding(0, UiKit.dp(this, 7), 0, 0);
        return status;
    }

    /** Positive capabilities, read from the provider layer so this copy cannot go stale. */
    private void addCapabilityLine(LinearLayout card, AiProvider provider) {
        java.util.List<String> chips = AiProvidersActivity.capabilityChips(provider.capabilities());
        if (chips.isEmpty()) return;
        TextView line = UiKit.text(this, String.join(" · ", chips), 12,
                UiKit.withAlpha(UiKit.TEXT, 205), false);
        line.setLineSpacing(0, 1.12f);
        line.setPadding(0, 0, 0, UiKit.dp(this, 10));
        card.addView(line);
    }

    /**
     * One honest sentence about what a provider cannot do yet, derived from its declared
     * capabilities rather than written down a second time here.
     */
    static String missingCapabilitySentence(AiCapabilities caps) {
        if (caps == null) return "";
        java.util.List<String> missing = new java.util.ArrayList<>();
        if (!caps.hostedWebSearch) missing.add("web search");
        if (!caps.images) missing.add("screens and images");
        if (!caps.deviceActions) missing.add("device actions");
        if (!caps.routinePlanning) missing.add("Routine planning");
        if (missing.isEmpty()) return "";
        StringBuilder out = new StringBuilder("Not available in this mode yet: ");
        for (int i = 0; i < missing.size(); i++) {
            if (i > 0) out.append(i == missing.size() - 1 ? ", or " : ", ");
            out.append(missing.get(i));
        }
        return out.append(".").toString();
    }

    private void buildRelayFallback(LinearLayout page) {
        LinearLayout relayCard = card();
        relayCard.addView(providerHeading(getString(R.string.ui_private_api_relay_d6ca6fb6), getString(R.string.ui_advanced_9f088dbe)));
        boolean configured = Prefs.relayConfigured(this);
        boolean relayActive = Prefs.PROVIDER_RELAY.equals(Prefs.provider(this)) && configured;
        relayCard.addView(providerStatusLine(configured, relayActive
                ? getString(R.string.ui_active_provider_4cee0d82) : configured ? getString(R.string.ui_relay_saved_29654bda) : getString(R.string.ui_relay_url_required_68955a3c)));
        addCardDescription(relayCard,
                getString(R.string.ui_for_advanced_users_running_their_own_https_re_6d06e9df));

        EditText url = field("https://your-relay.example.com", Prefs.backendUrl(this), false);
        relayCard.addView(url, new LinearLayout.LayoutParams(-1, UiKit.dp(this, 52)));
        EditText token = field(getString(R.string.ui_relay_access_token_optional_dffc0006), Prefs.token(this), true);
        LinearLayout.LayoutParams tokenLp = new LinearLayout.LayoutParams(-1, UiKit.dp(this, 52));
        tokenLp.topMargin = UiKit.dp(this, 9);
        relayCard.addView(token, tokenLp);

        Button save = secondaryButton(getString(R.string.ui_save_and_use_private_relay_cfc7865b));
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(-1, UiKit.dp(this, 46));
        saveLp.topMargin = UiKit.dp(this, 11);
        relayCard.addView(save, saveLp);
        save.setOnClickListener(v -> {
            try {
                String requestedUrl = url.getText().toString().trim();
                if (requestedUrl.isEmpty()) {
                    Toast.makeText(this, getString(R.string.ui_enter_an_https_relay_url_first_a096cb2f), Toast.LENGTH_LONG).show();
                    return;
                }
                String error = Prefs.saveRelaySettings(this, requestedUrl,
                        token.getText().toString());
                if (!error.isEmpty()) {
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show();
                    return;
                }
                AiProviders.select(this, Prefs.PROVIDER_RELAY);
                Toast.makeText(this, getString(R.string.ui_private_relay_saved_and_selected_64aea371), Toast.LENGTH_SHORT).show();
                render();
            } catch (Exception error) {
                showMessage(getString(R.string.ui_could_not_save_relay_settings_d1d62e26),
                        getString(R.string.ui_orbit_could_not_securely_save_the_optional_re_3b8093b1));
            }
        });
        page.addView(relayCard, cardLp());
    }

    private void buildAssistant(LinearLayout page) {
        addTitle(page, getString(R.string.ui_make_orbit_your_assistant_25633010),
                getString(R.string.ui_choosing_orbit_as_android_s_digital_assistant_3fda0183));
        boolean active = OrbitSetupHelper.isOrbitAssistantActive(this);
        LinearLayout card = card();
        card.addView(UiKit.text(this, active ? getString(R.string.ui_orbit_is_your_default_assistant_7bea32a3) :
                getString(R.string.ui_orbit_is_not_currently_your_default_assistant_fd04f0b1), 16,
                active ? UiKit.SUCCESS : UiKit.TEXT, true));
        TextView help = UiKit.text(this,
                getString(R.string.ui_android_controls_this_setting_available_butto_98cf13fd),
                13, UiKit.MUTED, false);
        help.setPadding(0, UiKit.dp(this, 7), 0, UiKit.dp(this, 13));
        card.addView(help);
        Button action = primaryButton(active ? getString(R.string.ui_manage_assistant_settings_66c4945b) : getString(R.string.ui_open_assistant_settings_44fda956));
        action.setOnClickListener(v -> {
            if (!OrbitSetupHelper.openAssistantSettings(this, REQ_ASSISTANT_SETTINGS, true))
                showMessage(getString(R.string.ui_assistant_settings_unavailable_7db001cf),
                        getString(R.string.ui_android_could_not_open_a_supported_default_as_c372b8db));
        });
        card.addView(action, new LinearLayout.LayoutParams(-1, UiKit.dp(this, 48)));
        page.addView(card, cardLp());
    }

    private void buildQuickAccess(LinearLayout page) {
        addTitle(page, getString(R.string.ui_choose_how_to_open_orbit_583ded88),
                getString(R.string.ui_pick_the_access_methods_that_fit_your_phone_n_90ea8df6));
        LinearLayout ask = card();
        ask.addView(UiKit.text(this, getString(R.string.ui_ask_orbit_quick_settings_tile_cf969c5f), 16, UiKit.TEXT, true));
        addCardDescription(ask, getString(R.string.ui_open_orbit_instantly_from_android_quick_setti_ceaa1bcf));
        Button add = primaryButton(OnboardingState.askTileConfirmed(this) ?
                getString(R.string.ui_orbit_tile_added_bd0fc37f) : getString(R.string.ui_add_orbit_tile_b63bc190));
        add.setOnClickListener(v -> QuickSettingsTiles.requestAddAskTile(this, confirmed -> {
            if (confirmed) OnboardingState.setAskTileConfirmed(this, true);
            runOnUiThread(() -> { if (canUpdateUi()) render(); });
        }));
        ask.addView(add, new LinearLayout.LayoutParams(-1, UiKit.dp(this, 46)));
        page.addView(ask, cardLp());

        LinearLayout routine = card();
        routine.addView(UiKit.text(this, getString(R.string.ui_routine_tile_6ce3113a), 16, UiKit.TEXT, true));
        addCardDescription(routine,
                getString(R.string.ui_you_can_assign_a_saved_routine_to_the_routine_d47c9435));
        page.addView(routine, cardLp());

        TextView widgetNote = UiKit.text(this,
                getString(R.string.ui_orbit_also_supports_home_screen_widgets_add_t_e478c9a8),
                12, UiKit.MUTED, false);
        widgetNote.setLineSpacing(0, 1.12f);
        LinearLayout.LayoutParams widgetNoteLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        widgetNoteLp.setMargins(UiKit.dp(this, 4), UiKit.dp(this, 2), UiKit.dp(this, 4),
                UiKit.dp(this, 10));
        page.addView(widgetNote, widgetNoteLp);

        LinearLayout side = card();
        side.addView(UiKit.text(this, getString(R.string.ui_side_button_and_assistant_gesture_22694cb5), 16, UiKit.TEXT, true));
        addCardDescription(side, OrbitSetupHelper.isOrbitAssistantActive(this)
                ? getString(R.string.ui_orbit_is_selected_as_your_assistant_your_devi_47ec47a0)
                : getString(R.string.ui_after_choosing_orbit_as_the_default_assistant_b3cab39f));
        page.addView(side, cardLp());
    }

    private void buildCapabilities(LinearLayout page) {
        addTitle(page, getString(R.string.ui_choose_what_orbit_can_do_74f9a229),
                getString(R.string.ui_set_up_only_the_access_you_want_every_action__5fc768b0));

        LinearLayout recommended = capabilityCard(page, getString(R.string.ui_recommended_access_a5d8e826));
        addCapability(recommended, getString(R.string.ui_notifications_78801183),
                getString(R.string.ui_reminders_routine_alerts_updates_and_backgrou_bbd63bc8),
                ReminderNotifier.notificationsAllowed(this), v ->
                        CapabilityAccessHelper.requestOrManageNotifications(this, REQ_NOTIFICATIONS));
        addRuntimeCapability(recommended, getString(R.string.ui_microphone_186352a8), getString(R.string.ui_used_for_orbit_voice_33d1df94),
                Manifest.permission.RECORD_AUDIO, REQ_MICROPHONE);
        addRuntimeCapability(recommended, getString(R.string.ui_approximate_location_a3162151),
                getString(R.string.ui_local_weather_and_location_aware_features_cd8c9a09),
                Manifest.permission.ACCESS_COARSE_LOCATION, REQ_LOCATION);
        addRuntimeCapability(recommended, getString(R.string.ui_flashlight_control_2b1b2637),
                getString(R.string.ui_android_camera_access_lets_orbit_control_the__6bc49b66),
                Manifest.permission.CAMERA, REQ_CAMERA);

        LinearLayout context = capabilityCard(page, getString(R.string.ui_context_a8bf80b5));
        context.addView(capabilityToggle(getString(R.string.ui_allow_orbit_to_read_current_screen_text_560f7375),
                Prefs.SCREEN_CONTEXT, Prefs.screenContext(this)));
        context.addView(capabilityToggle(getString(R.string.ui_allow_screenshots_for_visual_context_and_scre_13e63642),
                Prefs.SCREENSHOT, Prefs.screenshot(this)));
        addCapability(context, getString(R.string.ui_notification_intelligence_b32b4ba2),
                getString(R.string.ui_understand_notifications_only_when_this_acces_60483955),
                NotificationAccess.enabled(this), v ->
                        CapabilityAccessHelper.openNotificationIntelligence(this));

        LinearLayout controls = capabilityCard(page, getString(R.string.ui_device_controls_1c1afb89));
        addCapability(controls, getString(R.string.ui_brightness_control_fcfe685e),
                getString(R.string.ui_allows_orbit_to_change_system_brightness_d3f28a3b),
                OrbitPermissionHelper.canWriteSystemSettings(this), v ->
                        CapabilityAccessHelper.openWriteSettings(this));
        addCapability(controls, getString(R.string.ui_do_not_disturb_control_eaafd178),
                getString(R.string.ui_allows_orbit_to_control_do_not_disturb_376b1b83),
                OrbitPermissionHelper.hasDndAccess(this), v ->
                        CapabilityAccessHelper.openDndAccess(this));
        addRuntimeCapability(controls, getString(R.string.ui_contact_lookup_e8d9dae8),
                getString(R.string.ui_resolve_saved_contact_names_for_voice_and_dev_6c039701),
                Manifest.permission.READ_CONTACTS, REQ_CONTACTS);

        LinearLayout automation = capabilityCard(page, getString(R.string.ui_automation_9273c578));
        boolean exact = RoutineTriggerScheduler.canScheduleExact(this);
        addCapability(automation, getString(R.string.ui_precise_timing_ffd890c4),
                getString(R.string.ui_accurate_reminders_and_routine_time_triggers_ac0b3cea), exact, v ->
                        CapabilityAccessHelper.openExactAlarmAccess(this));
        // Arrive/leave triggers need background location, which the Google Play edition does not
        // request, so a first run there is not asked to set up something it cannot use.
        if (OrbitDistribution.supportsLocationTriggers()) {
            boolean locationReady = RoutineLocationTriggerScheduler.ready(this);
            automation.addView(capabilityRow(getString(R.string.ui_location_automation_3a5979c6),
                    getString(R.string.ui_arrival_and_leave_routine_triggers_e3e3e201),
                    CapabilityAccessHelper.locationAutomationStatus(this), locationReady, v ->
                            CapabilityAccessHelper.setupLocationAutomation(this,
                                    REQ_ROUTINE_FINE_LOCATION, REQ_ROUTINE_BACKGROUND_LOCATION)));
        }
        addCapability(automation, getString(R.string.ui_trigger_alerts_db53cdd6),
                getString(R.string.ui_visible_handoffs_when_an_automatic_routine_ne_b040e40b),
                RoutineTriggerNotifier.notificationsAllowed(this), v ->
                        CapabilityAccessHelper.requestOrManageTriggerAlerts(this, REQ_TRIGGER_ALERTS));

        Button all = secondaryButton(getString(R.string.ui_view_full_capabilities_dashboard_9786e312));
        all.setOnClickListener(v -> openCapabilities());
        page.addView(all, buttonLp());
    }

    /** The line that tells a first-run user where the rest of the appearance controls live. */
    static final String THEME_STUDIO_NOTE =
            getString(R.string.ui_you_can_fine_tune_colors_messages_cards_and_b_e08c6dc3);

    /**
     * Choose a good starting appearance quickly.
     *
     * <p>Before Theme Studio existed this page was where Orbit's appearance was configured, so it
     * grew its own Accent picker and its own two bubble-colour pickers. Theme Studio now owns all
     * three, and a first run that asks somebody to pick an accent, then a user bubble colour, then
     * an Orbit bubble colour - before they have seen a single conversation - is asking them to
     * design a theme before they know what one looks like. Worse, those controls wrote the same
     * preference keys from a second place, so first-run setup and the editor were two authorities
     * over one appearance.
     *
     * <p>So the colour half of this page is now one question with a visible answer: pick a preset.
     * The presets are Orbit's own shipped built-ins read straight from {@link OrbitTheme}, never a
     * second catalogue, and the choice is written through {@link OrbitThemeStore#applyActive} like
     * every other appearance change in Orbit - which is what makes Theme Studio, opened afterwards,
     * show exactly the theme the user picked here.
     *
     * <p>What stays is what Theme Studio does not own: the app font and the chat text size are
     * typography rather than colour, they survive every preset change, and they are the two
     * settings most likely to matter before somebody has read anything.
     */
    private void buildPersonalize(LinearLayout page) {
        addTitle(page, getString(R.string.ui_make_orbit_yours_d8e930a7),
                getString(R.string.ui_pick_a_starting_look_everything_here_can_be_c_bc1b22df));

        OrbitTheme active = OrbitThemeStore.active(this);

        LinearLayout presetCard = card();
        presetCard.addView(UiKit.text(this, getString(R.string.ui_choose_a_preset_2f311e68), 16, UiKit.TEXT, true));
        addCardDescription(presetCard, getString(R.string.ui_orbit_s_own_themes_pick_the_one_you_like_the__280953e3));
        presetCard.addView(presetGrid(active), matchWrapMargin(2));

        // AMOLED is its own switch rather than a second row of near-identical cards. A true-black
        // page is a property of a screen, not a colour scheme, and every preset is valid with it on
        // or off; making the user choose between "Orbit Default" and "Orbit AMOLED" cards was the
        // same question asked twice.
        OrbitSwitch amoled = new OrbitSwitch(this);
        amoled.setChecked(active.amoled, false);
        amoled.setOnCheckedChangeListener((button, checked) -> {
            // Only the flag moves. The colours the user just chose are carried over exactly, so
            // turning AMOLED on never quietly swaps them for a different-looking preset.
            applyOnboardingAppearance(OrbitThemeStore.active(this).withAmoled(checked));
        });
        LinearLayout amoledRow = UiKit.switchRow(this, getString(R.string.ui_use_true_black_amoled_backgrounds_ebcfe7fb),
                getString(R.string.ui_works_with_any_preset_fc69d41c), amoled);
        LinearLayout.LayoutParams amoledLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        amoledLp.setMargins(0, UiKit.dp(this, 6), 0, 0);
        presetCard.addView(amoledRow, amoledLp);

        // Education, not a step. Nobody is sent to Theme Studio during setup.
        TextView studio = UiKit.text(this, THEME_STUDIO_NOTE, 12, UiKit.MUTED, false);
        studio.setPadding(0, UiKit.dp(this, 10), 0, 0);
        presetCard.addView(studio);
        page.addView(presetCard, cardLp());

        LinearLayout readingCard = card();
        readingCard.addView(UiKit.text(this, getString(R.string.ui_text_and_readability_b4345cc3), 16, UiKit.TEXT, true));
        addCardDescription(readingCard,
                getString(R.string.ui_how_orbit_s_words_are_set_these_are_independe_b8b6bc90));
        readingCard.addView(UiKit.text(this, getString(R.string.ui_app_font_cad0292d), 12, UiKit.MUTED, true));
        String[] fontKeys = {"orbit_default", "times_new_roman", "light", "condensed", "monospace", "casual"};
        String[] fontLabels = {getString(R.string.ui_orbit_default_85a4dcf3), "Times New Roman", getString(R.string.ui_light_dbcd5e7b), getString(R.string.ui_condensed_ec5ac7c3), getString(R.string.ui_monospace_64c223c2), getString(R.string.ui_casual_f32445a7)};
        int selectedFont = indexOf(fontKeys, Prefs.appFont(this));
        Button selector = secondaryButton(fontLabels[selectedFont] + "  ▾");
        selector.setOnClickListener(v -> UiKit.showOrbitFontMenu(this, selector,
                fontKeys, fontLabels, selectedFont, (index, label) -> {
                    Prefs.get(this).edit().putString(Prefs.APP_FONT, fontKeys[index]).apply();
                    UiKit.notifyAppearanceChanged(this);
                    render();
                }));
        readingCard.addView(selector, new LinearLayout.LayoutParams(-1, UiKit.dp(this, 46)));

        TextView chatSizeLabel = UiKit.text(this, getString(R.string.ui_chat_text_size_dff7935f), 12, UiKit.MUTED, true);
        chatSizeLabel.setPadding(0, UiKit.dp(this, 12), 0, 0);
        readingCard.addView(chatSizeLabel);
        String[] chatSizeKeys = {Prefs.CHAT_TEXT_SMALL, Prefs.CHAT_TEXT_DEFAULT,
                Prefs.CHAT_TEXT_LARGE, Prefs.CHAT_TEXT_EXTRA_LARGE};
        String[] chatSizeLabels = {getString(R.string.ui_small_5263293f), getString(R.string.ui_default_21b111cb), getString(R.string.ui_large_ab80540d), getString(R.string.ui_extra_large_cc16912b)};
        readingCard.addView(preferenceSelector(chatSizeKeys, chatSizeLabels,
                Prefs.chatTextSize(this), Prefs.CHAT_TEXT_SIZE));
        page.addView(readingCard, cardLp());

        LinearLayout everydayCard = card();
        everydayCard.addView(UiKit.text(this, getString(R.string.ui_everyday_preferences_5a70e1a5), 16, UiKit.TEXT, true));
        addCardDescription(everydayCard,
                getString(R.string.ui_choose_the_apps_and_units_orbit_uses_for_ever_c3b5c812));
        everydayCard.addView(UiKit.text(this, getString(R.string.ui_gallery_app_eabbd23f), 12, UiKit.MUTED, true));
        everydayCard.addView(galleryAppSelector());
        TextView weatherUnitsLabel = UiKit.text(this, getString(R.string.ui_weather_units_4887463b), 12, UiKit.MUTED, true);
        weatherUnitsLabel.setPadding(0, UiKit.dp(this, 12), 0, 0);
        everydayCard.addView(weatherUnitsLabel);
        String[] weatherUnitKeys = {Prefs.WEATHER_UNITS_SYSTEM,
                Prefs.WEATHER_UNITS_FAHRENHEIT, Prefs.WEATHER_UNITS_CELSIUS};
        String[] weatherUnitLabels = {getString(R.string.ui_system_default_b0459211), getString(R.string.ui_fahrenheit_f_9c9497df), getString(R.string.ui_celsius_c_400b949d)};
        everydayCard.addView(preferenceSelector(weatherUnitKeys, weatherUnitLabels,
                Prefs.weatherUnits(this), Prefs.WEATHER_UNITS));
        page.addView(everydayCard, cardLp());
    }

    private void buildStarterRoutine(LinearLayout page) {
        addTitle(page, getString(R.string.ui_start_with_something_useful_e4bb6568),
                getString(R.string.ui_templates_create_normal_editable_routines_not_2de69d9b));
        for (String id : new String[]{"gaming", "bedtime", "focus", "morning"}) {
            RoutineTemplateCatalog.Template template = findTemplate(id);
            if (template == null) continue;
            LinearLayout card = card();
            card.addView(UiKit.text(this, template.displayName, 16, UiKit.TEXT, true));
            addCardDescription(card, template.description);
            if (!template.recommendedCommandPhrase.isEmpty()) {
                TextView phrase = UiKit.text(this,
                        getString(R.string.ui_suggested_custom_command_61f89281) + template.recommendedCommandPhrase + "”",
                        11, UiKit.accent(this), true);
                phrase.setPadding(0, 0, 0, UiKit.dp(this, 9));
                card.addView(phrase);
            }
            Button use = secondaryButton(getString(R.string.ui_use_template_351dd54e));
            use.setOnClickListener(v -> {
                RoutineStore.Routine routine = RoutineTemplatesActivity.createAndEdit(this, template);
                if (routine != null) OnboardingState.setStarterRoutineId(this, routine.id);
            });
            card.addView(use, new LinearLayout.LayoutParams(-1, UiKit.dp(this, 44)));
            page.addView(card, cardLp());
        }
        Button all = secondaryButton(getString(R.string.ui_see_all_templates_b852c331));
        all.setOnClickListener(v -> startActivity(new Intent(this, RoutineTemplatesActivity.class)));
        page.addView(all, buttonLp());
    }

    private void buildFinish(LinearLayout page) {
        addTitle(page, getString(R.string.ui_orbit_is_ready_539e6f58),
                getString(R.string.ui_you_re_all_set_orbit_is_ready_when_you_are_9d102dc1));
        LinearLayout summary = card();
        List<String> items = new ArrayList<>();
        if (ChatGptAuth.isSignedIn(this)) items.add(getString(R.string.ui_chatgpt_connected_eafcf1bf));
        if (OrbitSetupHelper.isOrbitAssistantActive(this)) items.add(getString(R.string.ui_orbit_set_as_assistant_bdc4052f));
        if (OnboardingState.askTileConfirmed(this)) items.add(getString(R.string.ui_quick_settings_tile_added_01e46d5c));
        RoutineStore.Routine starter = RoutineStore.findById(this,
                OnboardingState.starterRoutineId(this));
        if (starter != null) items.add("✓ " + starter.name + getString(R.string.ui_routine_created_db380da8));
        if (items.isEmpty()) items.add(getString(R.string.ui_orbit_is_ready_to_configure_at_your_pace_93e58d65));
        for (String item : items) {
            TextView row = UiKit.text(this, item, 14,
                    item.startsWith("✓") ? UiKit.SUCCESS : UiKit.TEXT, true);
            row.setPadding(0, UiKit.dp(this, 5), 0, UiKit.dp(this, 5));
            summary.addView(row);
        }
        page.addView(summary, cardLp());

        LinearLayout examples = card();
        examples.addView(UiKit.text(this, getString(R.string.ui_try_asking_695b5a73), 16, UiKit.TEXT, true));
        for (String example : new String[]{getString(R.string.ui_what_s_on_my_screen_f2f68c6f),
                getString(R.string.ui_remind_me_in_20_minutes_to_check_the_oven_3a9d7d7a),
                getString(R.string.ui_run_my_gaming_mode_routine_b5063e7b)}) {
            TextView text = UiKit.text(this, example, 13, UiKit.MUTED, false);
            text.setPadding(0, UiKit.dp(this, 9), 0, 0);
            examples.addView(text);
        }
        page.addView(examples, cardLp());
    }

    /** Decorative, non-blocking finish-page moment using the current Orbit accent. */
    private void playCompletionCelebration() {
        FrameLayout host = completionMarkHost;
        View mark = completionMark;
        if (host == null || mark == null || !mark.isAttachedToWindow()) return;

        if (Prefs.haptics(this)) {
            int feedback = Build.VERSION.SDK_INT >= 30
                    ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.CLOCK_TICK;
            try { mark.performHapticFeedback(feedback); }
            catch (Exception ignored) { }
        }
        if (!ValueAnimator.areAnimatorsEnabled()) return;

        List<Animator> animations = new ArrayList<>();
        ObjectAnimator pulse = ObjectAnimator.ofPropertyValuesHolder(mark,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, .90f, 1.16f, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, .90f, 1.16f, 1f));
        pulse.setDuration(920L);
        pulse.setInterpolator(new PathInterpolator(.2f, 0f, 0f, 1f));
        animations.add(pulse);

        int accent = UiKit.accent(this);
        int hostSize = UiKit.dp(this, 64);
        int ringSize = UiKit.dp(this, 44);
        View ring = new View(this);
        GradientDrawable ringShape = new GradientDrawable();
        ringShape.setShape(GradientDrawable.OVAL);
        ringShape.setColor(Color.TRANSPARENT);
        ringShape.setStroke(UiKit.dp(this, 2), UiKit.withAlpha(accent, 190));
        ring.setBackground(ringShape);
        ring.setAlpha(0f);
        ring.setScaleX(.62f);
        ring.setScaleY(.62f);
        ring.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams ringLp = new FrameLayout.LayoutParams(ringSize, ringSize);
        ringLp.leftMargin = (hostSize - ringSize) / 2;
        ringLp.topMargin = (hostSize - ringSize) / 2;
        host.addView(ring, 0, ringLp);
        ObjectAnimator ringBurst = ObjectAnimator.ofPropertyValuesHolder(ring,
                PropertyValuesHolder.ofFloat(View.ALPHA, 0f, .82f, .52f, 0f),
                PropertyValuesHolder.ofFloat(View.SCALE_X, .62f, .88f, 1.38f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, .62f, .88f, 1.38f));
        ringBurst.setDuration(920L);
        ringBurst.setInterpolator(new PathInterpolator(.2f, 0f, 0f, 1f));
        animations.add(ringBurst);

        List<View> particles = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            double angle = (Math.PI * 2d * i / 8d) - Math.PI / 2d;
            int dotSize = UiKit.dp(this, i % 2 == 0 ? 6 : 5);
            int center = hostSize / 2 - dotSize / 2;
            int initialRadius = UiKit.dp(this, 10);
            int travel = UiKit.dp(this, i % 2 == 0 ? 19 : 18);
            View dot = new View(this);
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.OVAL);
            shape.setColor(i % 2 == 0 ? accent : UiKit.orbitSatelliteColor(this));
            dot.setBackground(shape);
            dot.setAlpha(0f);
            dot.setScaleX(.58f);
            dot.setScaleY(.58f);
            dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(dotSize, dotSize);
            dotLp.leftMargin = center + (int) Math.round(Math.cos(angle) * initialRadius);
            dotLp.topMargin = center + (int) Math.round(Math.sin(angle) * initialRadius);
            host.addView(dot, 1, dotLp);
            particles.add(dot);

            PropertyValuesHolder x = PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f,
                    (float) (Math.cos(angle) * travel));
            PropertyValuesHolder y = PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f,
                    (float) (Math.sin(angle) * travel));
            PropertyValuesHolder alpha = PropertyValuesHolder.ofFloat(
                    View.ALPHA, 0f, 1f, .92f, 0f);
            PropertyValuesHolder scaleX = PropertyValuesHolder.ofFloat(
                    View.SCALE_X, .58f, 1.18f, .35f);
            PropertyValuesHolder scaleY = PropertyValuesHolder.ofFloat(
                    View.SCALE_Y, .58f, 1.18f, .35f);
            ObjectAnimator particle = ObjectAnimator.ofPropertyValuesHolder(
                    dot, x, y, alpha, scaleX, scaleY);
            particle.setStartDelay(80L + i * 10L);
            particle.setDuration(820L);
            particle.setInterpolator(new PathInterpolator(.2f, 0f, 0f, 1f));
            animations.add(particle);
        }

        AnimatorSet ignition = new AnimatorSet();
        ignition.playTogether(animations);
        ignition.addListener(new AnimatorListenerAdapter() {
            private boolean cleaned;

            private void cleanUp() {
                if (cleaned) return;
                cleaned = true;
                host.removeView(ring);
                for (View particle : particles) host.removeView(particle);
                mark.setScaleX(1f);
                mark.setScaleY(1f);
            }

            @Override public void onAnimationEnd(Animator animation) {
                cleanUp();
            }

            @Override public void onAnimationCancel(Animator animation) {
                cleanUp();
            }
        });
        ignition.start();
    }

    private void addNavigation(LinearLayout page) {
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams navLp = new LinearLayout.LayoutParams(-1, -2);
        navLp.setMargins(0, UiKit.dp(this, 18), 0, 0);
        if (step > 0 && step < 7) {
            Button back = secondaryButton(getString(R.string.ui_back_76900f1b));
            back.setOnClickListener(v -> onBackPressed());
            nav.addView(back, new LinearLayout.LayoutParams(0, UiKit.dp(this, 48), .36f));
        }
        Button next = primaryButton(step == 0 ? getString(R.string.ui_get_started_61e8d44a) : step == 7 ?
                getString(R.string.ui_start_using_orbit_4fd85706) : adaptiveNavigationLabel());
        next.setOnClickListener(v -> {
            if (step == 7) completeAndExit();
            else {
                step++;
                persistStep();
                render();
            }
        });
        LinearLayout.LayoutParams nextLp = new LinearLayout.LayoutParams(0, UiKit.dp(this, 48), 1);
        if (step > 0 && step < 7) nextLp.leftMargin = UiKit.dp(this, 9);
        nav.addView(next, nextLp);
        page.addView(nav, navLp);
    }

    private String adaptiveNavigationLabel() {
        if (step == 1 && !connectionConfigured()) return getString(R.string.ui_skip_for_now_b58eb52c);
        if (step == 2 && !OrbitSetupHelper.isOrbitAssistantActive(this)) return getString(R.string.ui_skip_for_now_b58eb52c);
        if (step == 6 && RoutineStore.findById(this,
                OnboardingState.starterRoutineId(this)) == null) return getString(R.string.ui_skip_for_now_b58eb52c);
        return getString(R.string.ui_continue_31fbef16);
    }

    private boolean connectionConfigured() {
        return ChatGptAuth.isSignedIn(this) ||
                (Prefs.PROVIDER_RELAY.equals(Prefs.provider(this)) && Prefs.relayConfigured(this));
    }

    private void addTitle(LinearLayout page, String title, String subtitle) {
        TextView heading = UiKit.text(this, title, 28, UiKit.TEXT, true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
        titleLp.setMargins(0, UiKit.dp(this, 27), 0, 0);
        page.addView(heading, titleLp);
        TextView sub = UiKit.text(this, subtitle, 14, UiKit.MUTED, false);
        sub.setLineSpacing(0, 1.13f);
        sub.setPadding(0, UiKit.dp(this, 8), 0, UiKit.dp(this, 18));
        page.addView(sub);
    }

    private View feature(String title, String description) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, UiKit.dp(this, 6), 0, UiKit.dp(this, 8));
        row.addView(UiKit.text(this, title, 15, UiKit.TEXT, true));
        TextView sub = UiKit.text(this, description, 12, UiKit.MUTED, false);
        sub.setPadding(0, UiKit.dp(this, 3), 0, 0);
        row.addView(sub);
        return row;
    }

    private LinearLayout capabilityCard(LinearLayout page, String title) {
        TextView heading = UiKit.text(this, title, 11, UiKit.MUTED, true);
        heading.setLetterSpacing(.11f);
        LinearLayout.LayoutParams headingLp = new LinearLayout.LayoutParams(-1, -2);
        headingLp.setMargins(UiKit.dp(this, 4), UiKit.dp(this, 6), 0, UiKit.dp(this, 7));
        page.addView(heading, headingLp);
        LinearLayout group = card();
        page.addView(group, cardLp());
        return group;
    }

    private void addRuntimeCapability(LinearLayout group, String title, String description,
                                      String permission, int requestCode) {
        boolean ready = CapabilityAccessHelper.permissionGranted(this, permission);
        addCapability(group, title, description, ready, v ->
                CapabilityAccessHelper.requestOrManageRuntimePermission(
                        this, permission, requestCode));
    }

    private void addCapability(LinearLayout group, String title, String description,
                               boolean ready, View.OnClickListener listener) {
        group.addView(capabilityRow(title, description, ready ? getString(R.string.ui_ready_5fa7aac5) : getString(R.string.ui_needs_access_67b0e137),
                ready, listener));
    }

    private View capabilityRow(String title, String description, String status, boolean ready,
                               View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, UiKit.dp(this, 8), 0, UiKit.dp(this, 8));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.addView(UiKit.text(this, title, 14, UiKit.TEXT, true));
        TextView descriptionView = UiKit.text(this, description, 11, UiKit.MUTED, false);
        descriptionView.setPadding(0, UiKit.dp(this, 2), 0, UiKit.dp(this, 3));
        copy.addView(descriptionView);
        copy.addView(UiKit.text(this, status,
                11, ready ? UiKit.SUCCESS : UiKit.MUTED, true));
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        Button action = quietButton(ready ? getString(R.string.ui_manage_5a234448) : getString(R.string.ui_set_up_4da10f1f));
        action.setOnClickListener(listener);
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(-2, UiKit.dp(this, 42));
        actionLp.leftMargin = UiKit.dp(this, 8);
        row.addView(action, actionLp);
        return row;
    }

    private View capabilityToggle(String label, String prefKey, boolean checked) {
        OrbitSwitch toggle = new OrbitSwitch(this);
        toggle.setChecked(checked, false);
        toggle.setOnCheckedChangeListener((button, enabled) ->
                Prefs.get(this).edit().putBoolean(prefKey, enabled).apply());
        return UiKit.switchRow(this, label, null, toggle);
    }

    private void addCardDescription(LinearLayout card, String description) {
        TextView text = UiKit.text(this, description, 12, UiKit.MUTED, false);
        text.setPadding(0, UiKit.dp(this, 6), 0, UiKit.dp(this, 12));
        card.addView(text);
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(UiKit.dp(this, 17), UiKit.dp(this, 15),
                UiKit.dp(this, 17), UiKit.dp(this, 15));
        card.setBackground(UiKit.outlined(UiKit.SURFACE,
                UiKit.withAlpha(UiKit.accent(this), 40), 20, this));
        card.setElevation(UiKit.dp(this, 2));
        return card;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, UiKit.dp(this, 10));
        return lp;
    }

    private LinearLayout.LayoutParams buttonLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, UiKit.dp(this, 46));
        lp.setMargins(0, UiKit.dp(this, 8), 0, 0);
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
                Color.rgb(53, 58, 72), UiKit.accent(this), 15, this));
        button.setMinHeight(0); button.setMinimumHeight(0); button.setStateListAnimator(null);
        UiKit.pressScale(button);
        return button;
    }

    private Button quietButton(String text) {
        Button button = secondaryButton(text);
        button.setBackground(UiKit.ripple(Color.TRANSPARENT, UiKit.accent(this), 13, this));
        button.setTextColor(UiKit.accent(this));
        return button;
    }

    private EditText field(String hint, String value, boolean secret) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setHintTextColor(Color.rgb(115, 120, 135));
        field.setText(value);
        field.setTextColor(UiKit.TEXT);
        field.setTextSize(14);
        field.setSingleLine(true);
        field.setPadding(UiKit.dp(this, 15), 0, UiKit.dp(this, 15), 0);
        field.setBackground(UiKit.outlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.accent(this), 72), 15, this));
        if (secret) field.setInputType(InputType.TYPE_CLASS_TEXT |
                InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return field;
    }

    /**
     * The preset choices, two to a row on a phone and three where there is room.
     *
     * <p>Compact on purpose. Theme Studio's live preview belongs in Theme Studio; what somebody
     * needs here is enough of each theme to tell them apart at a glance, which is its page colour,
     * its cards, its two bubble fills and its accent. Four bands and a name does that in a card
     * small enough that all of them are on screen at once.
     */
    private View presetGrid(OrbitTheme active) {
        List<OrbitTheme> presets = OrbitThemeStore.onboardingPresets();
        int columns = getResources().getConfiguration().screenWidthDp >= 600 ? 3 : 2;
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        for (int i = 0; i < presets.size(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row, matchWrapMargin(i == 0 ? 0 : 10));
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i % columns != 0) lp.leftMargin = UiKit.dp(this, 10);
            row.addView(presetChoice(presets.get(i), active), lp);
        }
        // A short final row must not stretch its cards across the whole width.
        int remainder = presets.size() % columns;
        if (remainder != 0 && row != null) {
            for (int i = remainder; i < columns; i++) {
                View filler = new View(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 1, 1f);
                lp.leftMargin = UiKit.dp(this, 10);
                row.addView(filler, lp);
            }
        }
        return grid;
    }

    /**
     * One preset, shown as it would look with the AMOLED switch as it currently stands.
     *
     * <p>Selection is worked out from colours rather than from a stored name, exactly as Theme
     * Studio does it: the active appearance with its AMOLED flag set aside is the base the user
     * chose, and whichever preset matches that is the one drawn as selected. So turning AMOLED on
     * does not make the page forget which preset is highlighted.
     */
    private View presetChoice(OrbitTheme preset, OrbitTheme active) {
        boolean selected = preset.sameColours(active.withAmoled(false));
        OrbitThemeTokens tokens = OrbitThemeTokens.resolve(this, preset.withAmoled(active.amoled));

        LinearLayout choice = new LinearLayout(this);
        choice.setOrientation(LinearLayout.VERTICAL);
        int pad = UiKit.dp(this, 9);
        choice.setPadding(pad, pad, pad, UiKit.dp(this, 10));
        choice.setBackground(UiKit.rippleOutlined(
                selected ? UiKit.blend(UiKit.accent(this), UiKit.SURFACE_2, 0.18f) : UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.accent(this), selected ? 190 : 54),
                UiKit.accent(this), 16, this));
        // Stated, not only drawn: a ring around a card of colours is exactly the kind of state that
        // disappears for anybody who cannot rely on colour to carry it.
        choice.setContentDescription(preset.name + getString(R.string.ui_orbit_preset_a2d9179f)
                + (preset.note().isEmpty() ? "" : ", " + preset.note())
                + (selected ? getString(R.string.ui_selected_bd44f3ef) : ""));
        UiKit.pressScale(choice);
        choice.setOnClickListener(v -> {
            UiKit.haptic(v, HapticFeedbackConstants.VIRTUAL_KEY);
            // The AMOLED switch is left exactly where the user set it.
            applyOnboardingAppearance(preset.withAmoled(OrbitThemeStore.active(this).amoled));
        });

        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setBackground(UiKit.rounded(tokens.background, 9, this));
        int stripPad = UiKit.dp(this, 4);
        strip.setPadding(stripPad, stripPad, stripPad, stripPad);
        int[] bands = {tokens.surface, tokens.userBubble, tokens.assistantBubble, tokens.accent};
        for (int i = 0; i < bands.length; i++) {
            View band = new View(this);
            band.setBackground(UiKit.rounded(bands[i], 5, this));
            LinearLayout.LayoutParams bandLp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            if (i > 0) bandLp.leftMargin = UiKit.dp(this, 3);
            strip.addView(band, bandLp);
        }
        choice.addView(strip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 30)));

        TextView name = UiKit.text(this, preset.name, 13, UiKit.TEXT, selected);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding(0, UiKit.dp(this, 8), 0, 0);
        choice.addView(name);

        choice.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        for (int i = 0; i < choice.getChildCount(); i++) {
            choice.getChildAt(i).setImportantForAccessibility(
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
        return choice;
    }

    /**
     * Writes an appearance the way every other part of Orbit writes one.
     *
     * <p>There is no onboarding-only theme state and no second set of colour preferences. The
     * chosen colours are labelled by the canonical rule - so Orbit Default with AMOLED on records
     * itself as Orbit AMOLED, and Nebula with AMOLED on records itself as a theme of the user's own
     * that started at Nebula - and then applied through the store every other surface reads. Opening
     * Theme Studio afterwards therefore shows what the user actually picked here, with no reconciling
     * step and nothing to keep in sync.
     *
     * <p>The page is rebuilt rather than the Activity recreated, which is what keeps the step
     * counter, the resumable step state and the rest of the flow exactly where they were.
     */
    private void applyOnboardingAppearance(OrbitTheme theme) {
        OrbitThemeStore.applyActive(this, OrbitThemeStore.canonicalIdentity(this, theme));
        UiKit.notifyAppearanceChanged(this);
        render();
    }

    private LinearLayout.LayoutParams matchWrapMargin(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, UiKit.dp(this, topDp), 0, 0);
        return lp;
    }

    private View preferenceSelector(String[] keys, String[] labels, String selectedKey,
                                    String prefKey) {
        int selectedIndex = indexOf(keys, selectedKey);
        Button selector = secondaryButton(labels[selectedIndex] + "  ▾");
        selector.setOnClickListener(v -> UiKit.showOrbitMenu(this, selector, labels,
                selectedIndex, (index, label) -> {
                    Prefs.get(this).edit().putString(prefKey, keys[index]).apply();
                    render();
                }));
        selector.setLayoutParams(new LinearLayout.LayoutParams(-1, UiKit.dp(this, 46)));
        return selector;
    }

    private View galleryAppSelector() {
        List<GalleryAppPreference.Option> options = GalleryAppPreference.options(this);
        String preferred = GalleryAppPreference.storedPackage(this);
        String[] labels = new String[options.size()];
        int selected = 0;
        for (int i = 0; i < options.size(); i++) {
            GalleryAppPreference.Option option = options.get(i);
            labels[i] = option.label;
            if (option.packageName.equals(preferred)) selected = i;
        }
        final int selectedIndex = selected;
        Button selector = secondaryButton(labels[selectedIndex] + "  ▾");
        selector.setOnClickListener(v -> UiKit.showOrbitMenu(this, selector, labels,
                selectedIndex, (index, label) -> {
                    // Saves the exact discovered picker, matching Settings.
                    GalleryAppPreference.setPreferredOption(this, options.get(index));
                    render();
                }));
        selector.setLayoutParams(new LinearLayout.LayoutParams(-1, UiKit.dp(this, 46)));
        return selector;
    }

    private int indexOf(String[] values, String target) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(target)) return i;
        return 0;
    }

    private RoutineTemplateCatalog.Template findTemplate(String id) {
        for (RoutineTemplateCatalog.Template template : RoutineTemplateCatalog.list())
            if (template.id.equals(id)) return template;
        return null;
    }

    private void openCapabilities() {
        startActivity(new Intent(this, CapabilitiesActivity.class));
    }

    /** Sign in with ChatGPT: the browser flow, as in Settings. */
    private void startChatGptLogin() {
        ChatGptBrowserAuth.Started started = ChatGptBrowserAuth.start(this, browserListener);
        if (!started.ok()) {
            browserListener.onFailed(started.error, true);
            return;
        }
        ChatGptSignInReturnActivity.returnTo = OnboardingActivity.class;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(started.authorizeUrl))
                    .addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (Exception e) {
            ChatGptBrowserAuth.cancel();
            browserListener.onFailed(getString(R.string.ui_orbit_could_not_open_a_web_browser_on_this_ph_3ed952a5), true);
        }
    }

    /** The device-code fallback, unchanged. */
    private void startCodeSignIn() {
        ChatGptAuth.requestDeviceCode(this, new ChatGptAuth.StartCallback() {
            @Override public void onSuccess(ChatGptAuth.DeviceCode code) {
                runOnUiThread(() -> { if (canUpdateUi()) showDeviceCode(code); });
                ChatGptAuth.completeDeviceCode(OnboardingActivity.this, code, loginCallback);
            }

            @Override public void onError(String message) {
                runOnUiThread(() -> { if (canUpdateUi())
                    showMessage(getString(R.string.ui_could_not_start_chatgpt_sign_in_41b65366), message); });
            }
        });
    }

    private void showDeviceCode(ChatGptAuth.DeviceCode code) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null)
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.ui_orbit_chatgpt_code_30d0c7fe), code.userCode));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.ui_sign_in_with_chatgpt_f6de6480))
                .setMessage(getString(R.string.ui_your_one_time_code_is_fe769007) + code.userCode +
                        getString(R.string.ui_it_has_been_copied_complete_authorization_in__e08721d9))
                .setPositiveButton(getString(R.string.ui_open_chatgpt_sign_in_d7f07761), (d, which) -> {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(code.verificationUrl))); }
                    catch (Exception e) { Toast.makeText(this, code.verificationUrl, Toast.LENGTH_LONG).show(); }
                })
                .setNeutralButton(getString(R.string.ui_copy_code_49a0053f), (d, which) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(
                            ClipData.newPlainText(getString(R.string.ui_orbit_chatgpt_code_30d0c7fe), code.userCode));
                })
                .setNegativeButton(getString(R.string.ui_close_7d9eb7ac), null)
                .create();
        UiKit.styleOrbitDialog(dialog, this, false);
        dialog.show();
    }

    private void chooseBackup() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQ_RESTORE_BACKUP);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ASSISTANT_SETTINGS) {
            getWindow().getDecorView().postDelayed(this::render, 200L);
            return;
        }
        if (requestCode != REQ_RESTORE_BACKUP || resultCode != RESULT_OK || data == null ||
                data.getData() == null) return;
        Uri uri = data.getData();
        new Thread(() -> {
            try {
                OrbitBackupManager.PreparedRestore prepared =
                        OrbitBackupManager.prepareRestore(getApplicationContext(), uri);
                runOnUiThread(() -> { if (canUpdateUi()) confirmRestore(prepared); });
            } catch (Exception e) {
                runOnUiThread(() -> { if (canUpdateUi())
                    showMessage(getString(R.string.ui_could_not_open_backup_7902f459), safeError(e)); });
            }
        }, "orbit-onboarding-backup-validate").start();
    }

    private void confirmRestore(OrbitBackupManager.PreparedRestore prepared) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.ui_restore_orbit_backup_add4c9b6))
                .setMessage(prepared.confirmationMessage())
                .setNegativeButton(getString(R.string.ui_cancel_19766ed6), null)
                .setPositiveButton(getString(R.string.ui_restore_a76e13b9), (d, which) -> runRestore(prepared))
                .create();
        UiKit.styleOrbitDialog(dialog, this, true);
        dialog.show();
    }

    private void runRestore(OrbitBackupManager.PreparedRestore prepared) {
        Toast.makeText(this, getString(R.string.ui_restoring_orbit_backup_0f159073), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                OrbitBackupManager.restore(getApplicationContext(), prepared);
                runOnUiThread(() -> {
                    if (!canUpdateUi()) return;
                    UiKit.syncTheme(this);
                    Toast.makeText(this,
                            getString(R.string.ui_backup_restored_continue_setup_to_reconnect_a_3fb2aa17),
                            Toast.LENGTH_LONG).show();
                    render();
                });
            } catch (Exception e) {
                runOnUiThread(() -> { if (canUpdateUi())
                    showMessage(getString(R.string.ui_could_not_restore_backup_eaf9f738), safeError(e)); });
            }
        }, "orbit-onboarding-backup-restore").start();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                     int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if ((requestCode == REQ_NOTIFICATIONS || requestCode == REQ_TRIGGER_ALERTS) &&
                CapabilityAccessHelper.permissionGranted(this,
                        Manifest.permission.POST_NOTIFICATIONS)) {
            NotificationHelper.ensureChannel(this);
        }
        if (requestCode == REQ_TRIGGER_ALERTS || requestCode == REQ_ROUTINE_FINE_LOCATION ||
                requestCode == REQ_ROUTINE_BACKGROUND_LOCATION) {
            RoutineTriggerScheduler.rescheduleAll(this);
        }
        render();
    }

    private void confirmSkip() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.ui_skip_setup_ef1de933))
                .setMessage(getString(R.string.ui_you_can_finish_setup_anytime_from_settings_as_9df1931d))
                .setNegativeButton(getString(R.string.ui_keep_setting_up_f0000e34), null)
                .setPositiveButton(getString(R.string.ui_skip_setup_59e693cd), (d, which) -> completeAndExit())
                .create();
        UiKit.styleOrbitDialog(dialog, this, true);
        dialog.show();
    }

    private void confirmExitSetup() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.ui_exit_setup_3f28c148))
                .setMessage(getString(R.string.ui_your_current_orbit_settings_are_already_saved_f9d771fc))
                .setNegativeButton(getString(R.string.ui_keep_setting_up_f0000e34), null)
                .setPositiveButton(getString(R.string.ui_exit_setup_a8b459d5), (d, which) -> finish())
                .create();
        UiKit.styleOrbitDialog(dialog, this, true);
        dialog.show();
    }

    private void showMessage(String title, String message) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title).setMessage(message).setPositiveButton(getString(R.string.ui_ok_565339bc), null).create();
        UiKit.styleOrbitDialog(dialog, this, false);
        dialog.show();
    }

    private void completeAndExit() {
        OnboardingState.markCompleted(this);
        finish();
    }

    private void persistStep() {
        OnboardingState.setCurrentStep(this, step);
    }

    private boolean canUpdateUi() {
        return !isFinishing() && !isDestroyed();
    }

    private String safeError(Exception error) {
        return error == null || error.getMessage() == null || error.getMessage().trim().isEmpty()
                ? getString(R.string.ui_orbit_could_not_complete_this_operation_9507ae61) : error.getMessage();
    }
}
