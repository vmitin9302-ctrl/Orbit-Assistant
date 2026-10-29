package com.orbit.assistant;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Future-only product direction, kept separate from completed release history. */
public final class RoadmapActivity extends Activity {
    /** Interactive Back for this page. Its classification lives in OrbitNavigation. */
    private OrbitPredictiveBack navigation;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.syncTheme(this);
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
    }

    @Override protected void onPause() {
        UiPresence.leave(this);
        super.onPause();
    }

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
        back.setContentDescription(getString(R.string.roadmap_back_desc));
        back.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 10),
                UiKit.dp(this, 10), UiKit.dp(this, 10));
        back.setOnClickListener(v -> navigation.performBack());
        UiKit.pressScale(back);
        header.addView(back, new LinearLayout.LayoutParams(UiKit.dp(this, 44), UiKit.dp(this, 44)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(UiKit.dp(this, 13), 0, 0, 0);
        titles.addView(UiKit.text(this, getString(R.string.roadmap_title), 24, UiKit.TEXT, true));
        titles.addView(UiKit.text(this, getString(R.string.roadmap_subtitle), 13, UiKit.MUTED, false));
        header.addView(titles, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(header);

        TextView intro = UiKit.text(this,
                getString(R.string.roadmap_intro),
                13, UiKit.MUTED, false);
        intro.setLineSpacing(0, 1.14f);
        LinearLayout.LayoutParams introLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        introLp.setMargins(UiKit.dp(this, 3), UiKit.dp(this, 18), UiKit.dp(this, 3), 0);
        page.addView(intro, introLp);

        // Future-only, and audited against release history each time it changes. Everything Orbit
        // has actually released belongs to What's New and to CHANGELOG.md, never here.
        //
        // Rebuilt in v0.7.8.4-beta.4. The page had drifted badly: it still opened with 0.7.7-era
        // priorities under NEXT UP while the whole project had moved on to Orbit Vault, so a user
        // reading it came away with a confident and wrong idea of what Orbit was working on. The
        // shape is now the same shape ROADMAP.md uses - what is being built and what is genuinely
        // further out - and the active milestone name is read from OrbitRoadmap so the two
        // documents cannot silently disagree about it again. Rich Answers, Settings search and
        // Astra left this page when v0.7.8.5 reached Stable: a shipped feature belongs to What's
        // New, never here.
        //
        // v0.8.0.0-beta.7 completed the planned Deck Pro expansion, so Smart Vault returns to NOW.
        // It remains a Free feature. The shipped Orbit Pro work belongs in What's New and the
        // repository history rather than on this future-only page.
        //
        // Older entries were not deleted. Local device actions, branching conditions, the cooking
        // session, Orbit-managed timers, OpenRouter chat and the rest are all real unfinished work
        // and are still listed; they have simply stopped being presented as the next thing to
        // happen, which they had not been for several releases.
        // v0.8.1.0-beta.1 began the journey to Orbit 0.9 with Smart Vault's first public Beta.
        // v0.8.1.0 made it Stable, so the entry now lists only what is still ahead for it: what
        // shipped belongs to What's New, never here.
        // v0.8.2.0-beta.1 made Orbit Local 2.0 the active line. What its first Beta shipped belongs
        // to What's New; this entry names only what is still ahead for it.
        // v0.8.2.0 made Orbit Local 2.0 Stable. It stays NOW for the work still ahead, and the
        // entry already listed only that, so its text is unchanged.
        // v0.8.3.0-beta.1 made AI Control the active line. What its first Beta shipped belongs to
        // What's New; this entry names only what later Betas are planned to add.
        // v0.8.3.0-beta.2 polished that shipped foundation and added automatic titles; neither is
        // future work, so the user-facing entry remains limited to the later AI Control plan.
        // v0.8.3.0-beta.3 shipped Conversation Control: editing into branches, kept answer versions,
        // Send with, kept chat context, the context meter and Continue in new chat. Those belong to
        // What's New now, as does the Edit & resend promise it fulfils, so both leave this page.
        // v0.8.3.0-beta.4 shipped Claude, Grok, the Model Library, Favorites, Recents and
        // provider-aware context metadata. Those are now release history, not future promises.
        // v0.8.3.0-beta.5 shipped Smart Routing: Auto as a per-chat selection with provider
        // permissions. It is release history too, so this page still lists only what is ahead.
        // v0.8.3.0 made AI Control Stable. It stays NOW for the work still ahead, and the entry
        // already listed only that, so its text is unchanged.
        addGroup(page, getString(R.string.roadmap_group_now), new String[][]{
                {getString(R.string.roadmap_ms_ai_control), getString(R.string.roadmap_ai_control_text)},
                {getString(R.string.roadmap_ms_orbit_local), getString(R.string.roadmap_orbit_local_text)},
                {getString(R.string.roadmap_ms_smart_vault), getString(R.string.roadmap_smart_vault_text)}
        });
        addGroup(page, getString(R.string.roadmap_group_later), new String[][]{
                {getString(R.string.roadmap_l1_t), getString(R.string.roadmap_l1_d)},
                {getString(R.string.roadmap_l2_t), getString(R.string.roadmap_l2_d)},
                {getString(R.string.roadmap_l3_t), getString(R.string.roadmap_l3_d)},
                {getString(R.string.roadmap_l4_t), getString(R.string.roadmap_l4_d)},
                {getString(R.string.roadmap_l5_t), getString(R.string.roadmap_l5_d)},
                {getString(R.string.roadmap_l6_t), getString(R.string.roadmap_l6_d)},
                {getString(R.string.roadmap_l7_t), getString(R.string.roadmap_l7_d)},
                {getString(R.string.roadmap_l8_t), getString(R.string.roadmap_l8_d)},
                {getString(R.string.roadmap_l9_t), getString(R.string.roadmap_l9_d)},
                {getString(R.string.roadmap_l10_t), getString(R.string.roadmap_l10_d)},
                {getString(R.string.roadmap_l11_t), getString(R.string.roadmap_l11_d)},
                {getString(R.string.roadmap_l12_t), getString(R.string.roadmap_l12_d)},
                {getString(R.string.roadmap_l13_t), getString(R.string.roadmap_l13_d)}
        });
        // v0.8.3.0-beta.6 shipped OpenRouter: browser sign-in, its model catalog in the Model
        // Library, and an opt-in place in Auto. It was the only DEFERRED entry, so that group is
        // gone; OpenRouter is release history now, not a promise.
        addGroup(page, getString(R.string.roadmap_group_exploring), new String[][]{
                {getString(R.string.roadmap_e1_t), getString(R.string.roadmap_e1_d)},
                {getString(R.string.roadmap_e2_t), getString(R.string.roadmap_e2_d)},
                {getString(R.string.roadmap_e3_t), getString(R.string.roadmap_e3_d)},
                {getString(R.string.roadmap_e4_t), getString(R.string.roadmap_e4_d)}
        });

        UiKit.applyTypography(page);
        return scroll;
    }

    private void addGroup(LinearLayout page, String name, String[][] items) {
        TextView section = UiKit.text(this, name, 12, UiKit.MUTED, true);
        section.setLetterSpacing(0.13f);
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sectionLp.setMargins(UiKit.dp(this, 4), UiKit.dp(this, 24), 0, UiKit.dp(this, 9));
        page.addView(section, sectionLp);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(UiKit.dp(this, 17), UiKit.dp(this, 10),
                UiKit.dp(this, 17), UiKit.dp(this, 10));
        card.setBackground(UiKit.outlined(UiKit.SURFACE,
                UiKit.withAlpha(UiKit.accent(this), 38), 22, this));
        card.setElevation(UiKit.dp(this, 2));
        for (int i = 0; i < items.length; i++) {
            card.addView(item(items[i][0], items[i][1], i > 0));
        }
        page.addView(card);
    }

    private View item(String title, String description, boolean separated) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.TOP);
        row.setPadding(0, separated ? UiKit.dp(this, 10) : UiKit.dp(this, 6),
                0, UiKit.dp(this, 8));

        View dot = new View(this);
        dot.setBackground(UiKit.rounded(UiKit.accent(this), 99, this));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                UiKit.dp(this, 7), UiKit.dp(this, 7));
        dotLp.setMargins(0, UiKit.dp(this, 7), UiKit.dp(this, 11), 0);
        row.addView(dot, dotLp);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.addView(UiKit.text(this, title, 15, UiKit.TEXT, true));
        TextView detail = UiKit.text(this, description, 12, UiKit.MUTED, false);
        detail.setLineSpacing(0, 1.12f);
        detail.setPadding(0, UiKit.dp(this, 3), 0, 0);
        copy.addView(detail);
        row.addView(copy, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }
}
