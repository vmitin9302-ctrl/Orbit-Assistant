package com.orbit.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
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
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/**
 * Smart Vault's setup and settings: one screen, with every privacy boundary its own switch.
 *
 * <p>Before Smart Vault is on, the switches are the choices the user is about to make, and nothing
 * happens until they press "Turn on Smart Vault". Afterwards each switch acts immediately. Each
 * says in one line what leaves the phone and where it goes - nothing, Google Play services, a model
 * server once, a website, or the user's AI provider - and the longer technical detail sits under
 * Privacy details.
 *
 * <p>Since v0.8.1.0-beta.2 the page is grouped into Search, Capture, AI suggestions, Saved items
 * and Manage, and is built once. A switch, the model download and the index counts all update the
 * views already on screen. Beta 1 rebuilt the whole page on every tap, which destroyed the switch
 * mid-animation and swallowed its haptic tick.
 */
public final class SmartVaultActivity extends Activity {

    static final int TITLE = R.string.d_sv_title;
    static final int TURN_ON = R.string.d_sv_turn_on;
    static final int TURN_OFF = R.string.d_sv_turn_off;

    static final int SECTION_SEARCH = R.string.d_sv_section_search;
    static final int SECTION_CAPTURE = R.string.d_sv_section_capture;
    static final int SECTION_AI = R.string.d_sv_section_ai;
    static final int SECTION_ITEMS = R.string.d_sv_section_items;
    static final int SECTION_MANAGE = R.string.d_sv_section_manage;

    static final int OCR_LABEL = R.string.d_sv_ocr_label;
    static final int OCR_HELP = R.string.d_sv_ocr_help;
    static final int MEANING_LABEL = R.string.d_sv_meaning_label;
    static final int MEANING_HELP = R.string.d_sv_meaning_help;
    static final int LINKS_LABEL = R.string.d_sv_links_label;
    static final int LINKS_HELP = R.string.d_sv_links_help;
    static final int AI_LABEL = R.string.d_sv_ai_label;
    static final int AI_HELP = R.string.d_sv_ai_help;

    static final int DETAILS_LABEL = R.string.d_sv_details_label;
    static final int DETAILS_BODY = R.string.d_sv_details_body;

    /** Model states, as the row under Search by meaning words them. */
    static final int MODEL_READY = R.string.d_sv_model_ready;
    static final int MODEL_PREPARING = R.string.d_sv_model_preparing;
    static final int MODEL_DOWNLOADING = R.string.d_sv_model_downloading;
    static final int MODEL_WAITING = R.string.d_sv_model_waiting;
    static final int MODEL_FAILED = R.string.d_sv_model_failed;
    static final int MODEL_MISSING = R.string.d_sv_model_missing;
    static final int ACTION_RETRY = R.string.d_sv_action_retry;
    static final int ACTION_DOWNLOAD = R.string.d_sv_action_download;
    static final int ACTION_REMOVE = R.string.d_sv_action_remove;

    private LinearLayout page;
    private boolean choiceOcr = true;
    private boolean choiceMeaning = true;
    private boolean choiceLinks = false;
    private boolean choiceAi = false;
    private String appearanceSignature = "";
    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::tick;
    private OrbitPredictiveBack navigation;
    private ScrollView scroll;

    /** Views that update in place, present only while Smart Vault is on. */
    private OrbitSwitch meaningSwitch;
    private ModelRow modelRow;
    private LinearLayout itemsBody;
    private String itemsSignature = "";
    private boolean detailsOpen;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.syncTheme(this);
        appearanceSignature = UiKit.appearanceSignature(this);
        Window window = getWindow();
        window.setStatusBarColor(UiKit.BG);
        window.setNavigationBarColor(UiKit.BG);
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        OrbitBackground.applyPage(scroll);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int side = UiKit.dp(this, 18);
        page.setPadding(side, UiKit.dp(this, 10), side, UiKit.dp(this, 40));
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        UiKit.applyActivityInsets(this, scroll, true);
        navigation = OrbitPredictiveBack.install(this);
        rebuild();
    }

    @Override protected void onResume() {
        super.onResume();
        UiPresence.enter(this);
        if (!UiKit.appearanceSignature(this).equals(appearanceSignature)) {
            recreate();
            return;
        }
        if (Prefs.smartVaultEnabled(this) && Prefs.smartVaultMeaning(this)) {
            SmartVaultModel.ensureScheduled(this);
        }
        // Rebuilt at the same scroll position: coming back from the Vault or Settings may have
        // changed what the page shows, but it should not move under the user.
        int y = scroll == null ? 0 : scroll.getScrollY();
        rebuild();
        if (scroll != null && y > 0) scroll.post(() -> scroll.scrollTo(0, y));
        ticker.removeCallbacks(tick);
        ticker.postDelayed(tick, 1000);
    }

    @Override protected void onPause() {
        ticker.removeCallbacks(tick);
        UiPresence.leave(this);
        super.onPause();
    }

    /**
     * Keeps the model row and the index counts current while the screen is visible. Only those two
     * change, in place, so nothing else on the page moves or is rebuilt.
     */
    private void tick() {
        if (isFinishing() || isDestroyed()) return;
        refreshLive();
        SmartVaultModel.Phase phase = SmartVaultModel.phase(this);
        boolean busy = phase == SmartVaultModel.Phase.DOWNLOADING
                || phase == SmartVaultModel.Phase.PREPARING;
        ticker.postDelayed(tick, busy ? 700 : 3000);
    }

    /** Brings the model row and the Saved items card up to date without rebuilding either card. */
    void refreshLive() {
        if (!Prefs.smartVaultEnabled(this)) return;
        if (modelRow != null) modelRow.bind();
        if (itemsBody != null && !itemsSignature().equals(itemsSignature)) fillItems();
    }

    private String itemsSignature() {
        StringBuilder s = new StringBuilder();
        s.append(OrbitVaultStore.count(this)).append('|').append(SmartVaultModel.isReady(this))
                .append('|').append(Prefs.smartVaultMeaning(this))
                .append(Prefs.smartVaultOcr(this)).append(Prefs.smartVaultReadLinks(this));
        if (!SmartVault.databaseExists(this)) return s.toString();
        try {
            SmartVaultDb db = SmartVaultDb.get(this);
            s.append('|').append(db.vectorCount()).append('|').append(db.queuedCount())
                    .append('|').append(db.derivedCount(SmartVaultDb.KIND_OCR))
                    .append('|').append(db.derivedCount(SmartVaultDb.KIND_PAGE))
                    .append('|').append(SmartVault.itemsWithoutSuggestions(this).size());
        } catch (Exception ignored) {
        }
        return s.toString();
    }

    private void rebuild() {
        if (page == null) return;
        page.removeAllViews();
        meaningSwitch = null;
        modelRow = null;
        itemsBody = null;
        boolean on = Prefs.smartVaultEnabled(this);
        page.addView(header(on));

        if (!Prefs.vaultEnabled(this)) {
            LinearLayout off = card();
            off.addView(UiKit.text(this, getString(R.string.d_sv_vault_off), 15, UiKit.TEXT, true));
            off.addView(muted(getString(R.string.d_sv_vault_off_hint)));
            page.addView(off, cardLp());
            return;
        }

        if (!on) {
            LinearLayout intro = card();
            intro.addView(UiKit.text(this, getString(R.string.d_sv_intro_title), 15, UiKit.TEXT, true));
            intro.addView(muted(getString(R.string.d_sv_intro_body)));
            page.addView(intro, cardLp());
        }

        page.addView(sectionLabel(getString(SECTION_SEARCH), !on));
        LinearLayout search = card();
        meaningSwitch = new OrbitSwitch(this);
        search.addView(option(meaningSwitch, getString(MEANING_LABEL),
                getString(MEANING_HELP, SmartVaultModel.sizeLabel(SmartVaultActivity.this)),
                on ? Prefs.smartVaultMeaning(this) : choiceMeaning, Prefs.SMART_VAULT_MEANING,
                v -> choiceMeaning = v));
        if (on) {
            modelRow = new ModelRow();
            search.addView(modelRow.root);
            modelRow.bind();
        }
        page.addView(search, cardLp());

        page.addView(sectionLabel(getString(SECTION_CAPTURE), true));
        LinearLayout capture = card();
        capture.addView(option(new OrbitSwitch(this), getString(OCR_LABEL), getString(OCR_HELP),
                on ? Prefs.smartVaultOcr(this) : choiceOcr, Prefs.SMART_VAULT_OCR,
                v -> choiceOcr = v));
        capture.addView(option(new OrbitSwitch(this), getString(LINKS_LABEL), getString(LINKS_HELP),
                on ? Prefs.smartVaultReadLinks(this) : choiceLinks, Prefs.SMART_VAULT_READ_LINKS,
                v -> choiceLinks = v));
        page.addView(capture, cardLp());

        page.addView(sectionLabel(getString(SECTION_AI), true));
        LinearLayout ai = card();
        ai.addView(option(new OrbitSwitch(this), getString(AI_LABEL), getString(AI_HELP),
                on ? Prefs.smartVaultAiForNewItems(this) : choiceAi, Prefs.SMART_VAULT_AI_NEW,
                v -> choiceAi = v));
        ai.addView(providerLine());
        page.addView(ai, cardLp());

        if (!on) {
            page.addView(details());
            Button turnOn = UiKit.button(this, getString(TURN_ON), true);
            turnOn.setOnClickListener(v -> {
                SmartVault.enable(this, choiceOcr, choiceMeaning, choiceLinks, choiceAi);
                Toast.makeText(this, getString(R.string.d_sv_toast_on),
                        Toast.LENGTH_LONG).show();
                rebuild();
                scroll.post(() -> scroll.scrollTo(0, 0));
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 50));
            lp.setMargins(0, UiKit.dp(this, 16), 0, 0);
            page.addView(turnOn, lp);
            return;
        }

        page.addView(sectionLabel(getString(SECTION_ITEMS), true));
        LinearLayout items = card();
        itemsBody = new LinearLayout(this);
        itemsBody.setOrientation(LinearLayout.VERTICAL);
        items.addView(itemsBody);
        fillItems();
        page.addView(items, cardLp());

        page.addView(sectionLabel(getString(SECTION_MANAGE), true));
        LinearLayout manage = card();
        Button off = UiKit.button(this, getString(TURN_OFF), false);
        off.setOnClickListener(v -> {
            SmartVault.disable(this);
            Toast.makeText(this, getString(R.string.d_sv_toast_off),
                    Toast.LENGTH_SHORT).show();
            rebuild();
        });
        manage.addView(off, buttonLp(0));
        Button delete = UiKit.button(this, getString(R.string.d_sv_delete_data), false);
        delete.setTextColor(UiKit.DANGER);
        delete.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.DANGER, 90), UiKit.DANGER, 15, this));
        delete.setOnClickListener(v -> confirmDeleteData());
        manage.addView(delete, buttonLp(9));
        manage.addView(muted(getString(R.string.d_sv_delete_hint)));
        page.addView(manage, cardLp());

        page.addView(details());
    }

    private View providerLine() {
        String blocker = SmartVault.suggestionBlocker(this);
        String text = blocker.isEmpty()
                ? getString(R.string.d_sv_provider, SmartVault.providerName(this))
                : blocker + (blocker.endsWith(".") ? "" : ".");
        TextView line = UiKit.text(this, text, 12, UiKit.MUTED, false);
        line.setPadding(UiKit.dp(this, 2), UiKit.dp(this, 2), UiKit.dp(this, 2), UiKit.dp(this, 4));
        return line;
    }

    interface Choice {
        void set(boolean value);
    }

    /**
     * One Smart Vault switch, as an ordinary Orbit switch row.
     *
     * <p>The listener records the choice and updates only what depends on it. It never rebuilds
     * the page, so the control that was tapped stays on screen: its thumb slides with Orbit's
     * standard motion (or jumps, with animations off), and {@link OrbitSwitch#toggle()} gives the
     * one light tick every Settings switch gives, from a view that is still attached.
     */
    private View option(OrbitSwitch control, String label, String help, boolean checked,
                        String key, Choice choice) {
        control.setChecked(checked, false);
        control.setOnCheckedChangeListener((button, value) -> {
            if (!Prefs.smartVaultEnabled(this)) {
                choice.set(value);
                return;
            }
            SmartVault.setOption(this, key, value);
            if (Prefs.SMART_VAULT_MEANING.equals(key) && modelRow != null) modelRow.bind();
            refreshLive();
        });
        return UiKit.switchRow(this, label, help, control);
    }

    /**
     * The search model's state under Search by meaning: a small accent ring with its percentage
     * while downloading, and a single restrained line once ready.
     */
    final class ModelRow {
        final LinearLayout root = new LinearLayout(SmartVaultActivity.this);
        final OrbitProgressRing ring = new OrbitProgressRing(SmartVaultActivity.this);
        final TextView title;
        final TextView detail;
        final TextView trailing;
        SmartVaultModel.Phase shown;

        ModelRow() {
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPadding(UiKit.dp(SmartVaultActivity.this, 2), UiKit.dp(SmartVaultActivity.this, 4),
                    UiKit.dp(SmartVaultActivity.this, 2), UiKit.dp(SmartVaultActivity.this, 8));
            ring.setContentDescription(getString(R.string.d_sv_ring_desc));
            LinearLayout.LayoutParams ringLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ringLp.setMargins(0, 0, UiKit.dp(SmartVaultActivity.this, 12), 0);
            root.addView(ring, ringLp);

            LinearLayout texts = new LinearLayout(SmartVaultActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            title = UiKit.text(SmartVaultActivity.this, "", 13, UiKit.TEXT, false);
            // Announced when the state changes, not on every percent.
            title.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            detail = UiKit.text(SmartVaultActivity.this, "", 12, UiKit.MUTED, false);
            detail.setPadding(0, UiKit.dp(SmartVaultActivity.this, 1), 0, 0);
            texts.addView(title);
            texts.addView(detail);
            root.addView(texts, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            trailing = UiKit.text(SmartVaultActivity.this, "", 13, UiKit.accent(SmartVaultActivity.this), true);
            int pad = UiKit.dp(SmartVaultActivity.this, 10);
            trailing.setPadding(pad, pad, UiKit.dp(SmartVaultActivity.this, 4), pad);
            trailing.setMinWidth(UiKit.dp(SmartVaultActivity.this, 44));
            trailing.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            root.addView(trailing, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        void bind() {
            boolean meaning = Prefs.smartVaultMeaning(SmartVaultActivity.this);
            root.setVisibility(meaning ? View.VISIBLE : View.GONE);
            if (!meaning) return;
            SmartVaultModel.Phase phase = SmartVaultModel.phase(SmartVaultActivity.this);
            int percent = SmartVaultModel.percent(SmartVaultActivity.this);
            String size = getString(R.string.d_sv_size_of,
                    mb(SmartVaultModel.doneBytes(SmartVaultActivity.this)), SmartVaultModel.sizeLabel(SmartVaultActivity.this));
            int accent = UiKit.accent(SmartVaultActivity.this);
            String titleText;
            String detailText;
            String action = "";
            View.OnClickListener onAction = null;
            boolean showRing = true;
            int titleColor = UiKit.TEXT;
            int trailingColor = accent;
            switch (phase) {
                case PREPARING:
                    titleText = getString(MODEL_PREPARING);
                    detailText = getString(R.string.d_sv_preparing_detail, SmartVaultModel.sizeLabel(SmartVaultActivity.this));
                    ring.setIndeterminate();
                    break;
                case DOWNLOADING:
                    titleText = getString(MODEL_DOWNLOADING);
                    detailText = getString(R.string.d_sv_downloading_detail, size);
                    ring.setProgress(percent);
                    action = percent + "%";
                    break;
                case WAITING:
                    titleText = getString(MODEL_WAITING);
                    detailText = percent > 0 ? getString(R.string.d_sv_waiting_detail_size, size)
                            : getString(R.string.d_sv_waiting_detail);
                    ring.setProgress(percent);
                    action = percent > 0 ? percent + "%" : "";
                    trailingColor = UiKit.MUTED;
                    break;
                case FAILED:
                    titleText = getString(MODEL_FAILED);
                    detailText = SmartVaultModel.error(SmartVaultActivity.this);
                    showRing = false;
                    action = getString(ACTION_RETRY);
                    onAction = v -> {
                        SmartVaultModel.requestDownload(SmartVaultActivity.this);
                        bind();
                    };
                    break;
                case READY:
                    titleText = "✓  " + getString(MODEL_READY);
                    detailText = "";
                    titleColor = accent;
                    showRing = false;
                    action = getString(ACTION_REMOVE);
                    onAction = v -> {
                        SmartVaultModel.delete(SmartVaultActivity.this);
                        SmartVault.setOption(SmartVaultActivity.this, Prefs.SMART_VAULT_MEANING, false);
                        if (meaningSwitch != null) meaningSwitch.setChecked(false);
                        bind();
                        refreshLive();
                    };
                    break;
                case MISSING:
                default:
                    titleText = getString(MODEL_MISSING);
                    detailText = getString(R.string.d_sv_missing_detail, SmartVaultModel.sizeLabel(SmartVaultActivity.this));
                    showRing = false;
                    action = getString(ACTION_DOWNLOAD);
                    onAction = v -> {
                        SmartVaultModel.requestDownload(SmartVaultActivity.this);
                        bind();
                    };
                    break;
            }
            ring.setVisibility(showRing ? View.VISIBLE : View.GONE);
            title.setText(titleText);
            title.setTextColor(titleColor);
            detail.setText(detailText);
            detail.setVisibility(detailText.isEmpty() ? View.GONE : View.VISIBLE);
            trailing.setText(action);
            trailing.setTextColor(trailingColor);
            trailing.setVisibility(action.isEmpty() ? View.GONE : View.VISIBLE);
            trailing.setOnClickListener(onAction);
            trailing.setClickable(onAction != null);
            trailing.setBackground(onAction == null ? null
                    : UiKit.ripple(android.graphics.Color.TRANSPARENT, accent, 12, SmartVaultActivity.this));
            trailing.setContentDescription(onAction == null ? null
                    : getString(phase == SmartVaultModel.Phase.READY
                            ? R.string.d_sv_action_model : R.string.d_sv_action_model_download, action));
            shown = phase;
        }
    }

    static String mb(long bytes) {
        return String.format(Locale.US, "%.0f MB", bytes / 1_000_000.0);
    }

    /** Index counts, then suggestions for the items saved before Smart Vault. */
    private void fillItems() {
        if (itemsBody == null) return;
        itemsSignature = itemsSignature();
        itemsBody.removeAllViews();
        int total = OrbitVaultStore.count(this);
        itemsBody.addView(stat(getString(R.string.d_sv_stat_saved), String.valueOf(total)));
        int queued = 0;
        try {
            SmartVaultDb db = SmartVaultDb.get(this);
            if (Prefs.smartVaultMeaning(this) && SmartVaultModel.isReady(this)) {
                itemsBody.addView(stat(getString(R.string.d_sv_stat_searchable),
                        getString(R.string.d_sv_x_of_y, Math.min(total, db.vectorCount()), total)));
            }
            if (Prefs.smartVaultOcr(this)) {
                itemsBody.addView(stat(getString(R.string.d_sv_stat_pictures),
                        String.valueOf(db.derivedCount(SmartVaultDb.KIND_OCR))));
            }
            if (Prefs.smartVaultReadLinks(this)) {
                itemsBody.addView(stat(getString(R.string.d_sv_stat_links),
                        String.valueOf(db.derivedCount(SmartVaultDb.KIND_PAGE))));
            }
            queued = db.queuedCount();
            if (queued > 0) {
                itemsBody.addView(stat(getString(R.string.d_sv_stat_waiting), getString(R.string.d_sv_needs_connection, queued)));
            }
        } catch (Exception ignored) {
        }

        // Suggestions for items saved before, only when the user asks, with the exact number of
        // items that would be sent and to which provider.
        List<String> without = SmartVault.itemsWithoutSuggestions(this);
        if (without.isEmpty()) {
            itemsBody.addView(muted(getString(R.string.d_sv_all_have)));
        } else {
            itemsBody.addView(muted(getResources().getQuantityString(
                    R.plurals.d_sv_without_suggestions, without.size(), without.size())));
            Button suggest = UiKit.button(this, getString(R.string.d_sv_suggest_these), false);
            suggest.setOnClickListener(v -> confirmBatch(without));
            itemsBody.addView(suggest, buttonLp(10));
        }
        if (queued > 0) {
            Button stop = UiKit.button(this, getResources().getQuantityString(
                    R.plurals.d_sv_stop_waiting, queued, queued), false);
            stop.setOnClickListener(v -> {
                try { SmartVaultDb.get(this).cancelQueued(); } catch (Exception ignored) { }
                fillItems();
            });
            itemsBody.addView(stop, buttonLp(9));
        }
    }

    private View stat(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, UiKit.dp(this, 3), 0, UiKit.dp(this, 3));
        row.addView(UiKit.text(this, label, 13, UiKit.MUTED, false), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(UiKit.text(this, value, 13, UiKit.TEXT, true));
        return row;
    }

    /**
     * The longer technical detail, one tap away instead of under every switch. Each switch still
     * says in its own line what leaves the phone; this holds the model's name and licence, ML Kit's
     * statistics, and what happens to items saved before.
     */
    private View details() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView toggle = UiKit.text(this, "", 13, UiKit.accent(this), true);
        int pad = UiKit.dp(this, 12);
        toggle.setPadding(UiKit.dp(this, 4), pad, UiKit.dp(this, 4), pad);
        toggle.setBackground(UiKit.ripple(android.graphics.Color.TRANSPARENT, UiKit.accent(this), 12, this));
        TextView body = muted(getString(DETAILS_BODY, SmartVaultModel.sizeLabel(SmartVaultActivity.this)));
        body.setPadding(UiKit.dp(this, 4), 0, UiKit.dp(this, 4), UiKit.dp(this, 6));
        Runnable apply = () -> {
            toggle.setText((detailsOpen ? "▾  " : "▸  ") + getString(DETAILS_LABEL));
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                toggle.setStateDescription(getString(detailsOpen ? R.string.d_sv_expanded : R.string.d_sv_collapsed));
            }
            body.setVisibility(detailsOpen ? View.VISIBLE : View.GONE);
        };
        toggle.setOnClickListener(v -> {
            detailsOpen = !detailsOpen;
            apply.run();
        });
        apply.run();
        box.addView(toggle);
        box.addView(body);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, UiKit.dp(this, 8), 0, 0);
        box.setLayoutParams(lp);
        return box;
    }

    private void confirmBatch(List<String> ids) {
        String blocker = SmartVault.suggestionBlocker(this);
        if (!blocker.isEmpty()) {
            Toast.makeText(this, blocker, Toast.LENGTH_LONG).show();
            return;
        }
        String providerName = SmartVault.providerName(this);
        List<String> batch = ids.size() > SmartVault.MAX_BATCH
                ? ids.subList(0, SmartVault.MAX_BATCH) : ids;
        String message = getResources().getQuantityString(R.plurals.d_sv_confirm_message,
                batch.size(), batch.size(), providerName)
                + (ids.size() > batch.size() ? "\n\n" + getString(R.string.d_sv_confirm_newest, SmartVault.MAX_BATCH) : "");
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getResources().getQuantityString(R.plurals.d_sv_confirm_title,
                        batch.size(), batch.size()))
                .setMessage(message)
                .setNegativeButton(getString(R.string.ui_cancel_19766ed6), null)
                .setPositiveButton(getString(R.string.d_sv_send, batch.size()), (d, w) -> {
                    int queued = SmartVault.queueSuggestions(this, batch);
                    Toast.makeText(this, getString(R.string.d_sv_queued, queued),
                            Toast.LENGTH_LONG).show();
                    fillItems();
                })
                .create();
        UiKit.styleOrbitDialog(dialog, this, false);
        dialog.show();
    }

    private void confirmDeleteData() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.d_sv_delete_title))
                .setMessage(getString(R.string.d_sv_delete_message))
                .setNegativeButton(getString(R.string.ui_cancel_19766ed6), null)
                .setPositiveButton(getString(R.string.ui_delete_e2d0a549), (d, w) -> {
                    int cleared = SmartVault.deleteData(this);
                    Toast.makeText(this, cleared > 0 ? getResources().getQuantityString(R.plurals.d_sv_deleted_with, cleared, cleared)
                                    : getString(R.string.d_sv_deleted),
                            Toast.LENGTH_SHORT).show();
                    fillItems();
                })
                .create();
        UiKit.styleOrbitDialog(dialog, this, true);
        dialog.show();
    }

    // ---- small helpers ----------------------------------------------------------------------------

    private View header(boolean on) {
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.ic_back);
        back.setImageTintList(ColorStateList.valueOf(UiKit.accent(this)));
        back.setBackground(UiKit.ripple(UiKit.SURFACE, UiKit.accent(this), 18, this));
        back.setContentDescription(getString(R.string.ui_back_76900f1b));
        back.setPadding(UiKit.dp(this, 11), UiKit.dp(this, 11), UiKit.dp(this, 11), UiKit.dp(this, 11));
        back.setOnClickListener(v -> navigation.performBack());
        UiKit.pressScale(back);
        top.addView(back, new LinearLayout.LayoutParams(UiKit.dp(this, 48), UiKit.dp(this, 48)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(UiKit.text(this, getString(TITLE), 24, UiKit.TEXT, true));
        titles.addView(UiKit.text(this, getString(on ? R.string.d_sv_subtitle_on
                : R.string.d_sv_subtitle_off), 12, UiKit.MUTED, false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.setMargins(UiKit.dp(this, 14), 0, 0, UiKit.dp(this, 6));
        top.addView(titles, lp);
        return top;
    }

    /** The same small spaced capitals the Vault uses to head its groups. */
    private TextView sectionLabel(String name, boolean spacedAbove) {
        TextView label = UiKit.text(this, name, 12, UiKit.MUTED, true);
        label.setLetterSpacing(0.18f);
        label.setPadding(UiKit.dp(this, 4), UiKit.dp(this, spacedAbove ? 16 : 8), 0, 0);
        label.setAccessibilityHeading(true);
        return label;
    }

    private TextView muted(String text) {
        TextView view = UiKit.text(this, text, 12, UiKit.MUTED, false);
        view.setPadding(0, UiKit.dp(this, 6), 0, 0);
        view.setLineSpacing(0, 1.12f);
        return view;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 8), UiKit.dp(this, 14), UiKit.dp(this, 10));
        c.setBackground(UiKit.outlined(UiKit.SURFACE,
                UiKit.withAlpha(UiKit.accent(this), 34), 20, this));
        return c;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, UiKit.dp(this, 8), 0, 0);
        return lp;
    }

    private LinearLayout.LayoutParams buttonLp(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 44));
        lp.setMargins(0, UiKit.dp(this, topDp), 0, 0);
        return lp;
    }
}
