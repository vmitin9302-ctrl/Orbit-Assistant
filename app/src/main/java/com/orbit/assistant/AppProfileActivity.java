package com.orbit.assistant;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
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

import java.util.Arrays;

/** Per-app Behavior 2.0 editor. */
public class AppProfileActivity extends Activity {
    public static final String EXTRA_PACKAGE = "package";
    public static final String EXTRA_LABEL = "label";

    private String pkg, label;
    private OptionSelector category, privacy, screen, screenshot, action1, action2, action3;
    /** The profile this screen opened with, so it can tell whether anything has been changed. */
    private AppProfileStore.Profile loaded;
    /** Interactive Back for this page. Its classification lives in OrbitNavigation. */
    private OrbitPredictiveBack navigation;

    private static final class OptionSelector {
        final String[] labels;
        final String[] values;
        final LinearLayout field;
        final TextView valueView;
        int selectedIndex;

        OptionSelector(String[] labels, String[] values, LinearLayout field, TextView valueView, int selectedIndex) {
            this.labels = labels;
            this.values = values;
            this.field = field;
            this.valueView = valueView;
            this.selectedIndex = selectedIndex;
        }
    }

    private static final String[] CATEGORY_VALUES = {
            AppProfileStore.CATEGORY_AUTO, AppProfileStore.CATEGORY_CONVERSATION,
            AppProfileStore.CATEGORY_PRODUCT, AppProfileStore.CATEGORY_ARTICLE,
            AppProfileStore.CATEGORY_SETTINGS, AppProfileStore.CATEGORY_MEDIA,
            AppProfileStore.CATEGORY_MAP, AppProfileStore.CATEGORY_DOCUMENT,
            AppProfileStore.CATEGORY_EMAIL, AppProfileStore.CATEGORY_GENERIC
    };
    private static final int[] CATEGORY_LABELS = {
            R.string.appprof_auto, R.string.appprof_cat_conversation, R.string.appprof_cat_product,
            R.string.appprof_cat_article, R.string.ui_settings_74a883a0, R.string.appprof_cat_media,
            R.string.appprof_cat_map, R.string.appprof_cat_document, R.string.appprof_cat_email,
            R.string.appprof_cat_generic
    };
    private static final String[] PRIVACY_VALUES = {
            AppProfileStore.PRIVACY_AUTO, AppProfileStore.PRIVACY_NORMAL,
            AppProfileStore.PRIVACY_SENSITIVE, AppProfileStore.PRIVACY_NEVER
    };
    private static final int[] PRIVACY_LABELS = {
            R.string.appprof_auto, R.string.appprof_priv_normal,
            R.string.appprof_priv_sensitive_manual, R.string.appprof_priv_never
    };
    private static final String[] SCREEN_VALUES = {
            AppProfileStore.SCREEN_GLOBAL, AppProfileStore.SCREEN_ATTACH, AppProfileStore.SCREEN_NEVER
    };
    private static final int[] SCREEN_LABELS = {
            R.string.appprof_screen_global, R.string.appprof_screen_attach,
            R.string.appprof_screen_never
    };
    private static final String[] SHOT_VALUES = {
            AppProfileStore.SCREENSHOT_GLOBAL, AppProfileStore.SCREENSHOT_ALLOW, AppProfileStore.SCREENSHOT_BLOCK
    };
    private static final int[] SHOT_LABELS = {
            R.string.appprof_screen_global, R.string.appprof_shot_allow, R.string.appprof_shot_block
    };
    private static final String[] ACTION_VALUES = {
            AppProfileStore.ACTION_AUTO, AppProfileStore.ACTION_DRAFT, AppProfileStore.ACTION_SUMMARIZE,
            AppProfileStore.ACTION_EXPLAIN, AppProfileStore.ACTION_TONE, AppProfileStore.ACTION_NEEDS_ACTION,
            AppProfileStore.ACTION_WORTH, AppProfileStore.ACTION_COMPARE, AppProfileStore.ACTION_KEY_SPECS,
            AppProfileStore.ACTION_KEY_POINTS, AppProfileStore.ACTION_RECOMMEND,
            AppProfileStore.ACTION_WHAT_MATTERS, AppProfileStore.ACTION_WHICH_OPTION,
            AppProfileStore.ACTION_ROUTE, AppProfileStore.ACTION_WHAT_NEXT
    };
    private static final int[] ACTION_LABELS = {
            R.string.appprof_auto, R.string.appprof_act_draft, R.string.appprof_act_summarize,
            R.string.appprof_act_explain, R.string.appprof_act_tone, R.string.appprof_act_needs_action,
            R.string.appprof_act_worth, R.string.appprof_act_compare, R.string.appprof_act_key_specs,
            R.string.appprof_act_key_points, R.string.appprof_act_recommend,
            R.string.appprof_act_what_matters, R.string.appprof_act_which_option,
            R.string.appprof_act_route, R.string.appprof_act_what_next
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.syncTheme(this);
        pkg = getIntent().getStringExtra(EXTRA_PACKAGE);
        label = getIntent().getStringExtra(EXTRA_LABEL);
        if (pkg == null) pkg = "";
        if (label == null || label.isEmpty()) label = pkg;
        Window w = getWindow();
        w.setStatusBarColor(UiKit.BG);
        w.setNavigationBarColor(UiKit.BG);
        View root = build();
        setContentView(root);
        UiKit.applyActivityInsets(this, root, true);
        // Nothing is written until Save app behavior, so Back has always dropped a changed selection
        // without asking, and still does. The page only slides away while the selections still match
        // what is stored.
        navigation = OrbitPredictiveBack.install(this, new OrbitPredictiveBack.Screen() {
            @Override public boolean canNavigate() { return !hasUnsavedWork(); }
            @Override public void navigateBack() { finish(); }
            @Override public String screenName() {
                return OrbitNavigation.labelFor(AppProfileActivity.class);
            }
        });
    }

    /** True while any selector differs from the profile this screen opened with. */
    private boolean hasUnsavedWork() {
        if (loaded == null || category == null) return false;
        return !selectedValue(category).equals(loaded.category)
                || !selectedValue(privacy).equals(loaded.privacyPolicy)
                || !selectedValue(screen).equals(loaded.screenPolicy)
                || !selectedValue(screenshot).equals(loaded.screenshotPolicy)
                || !selectedValue(action1).equals(loaded.action1)
                || !selectedValue(action2).equals(loaded.action2)
                || !selectedValue(action3).equals(loaded.action3);
    }

    @Override protected void onResume(){ super.onResume(); UiPresence.enter(this); }
    @Override protected void onPause(){ UiPresence.leave(this); super.onPause(); }

    private View build() {
        ScrollView scroll = new ScrollView(this);
        OrbitBackground.applyPage(scroll);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(UiKit.dp(this,18),UiKit.dp(this,10),UiKit.dp(this,18),UiKit.dp(this,38));
        scroll.addView(root,new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = iconButton(R.drawable.ic_back,getString(R.string.ui_back_76900f1b));
        back.setOnClickListener(v -> navigation.performBack());
        top.addView(back,new LinearLayout.LayoutParams(UiKit.dp(this,48),UiKit.dp(this,48)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = UiKit.text(this,label,22,UiKit.TEXT,true);
        title.setMaxLines(1);
        titles.addView(title);
        TextView p = UiKit.text(this,pkg,11,UiKit.MUTED,false);
        p.setMaxLines(1);
        titles.addView(p);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1);
        titleLp.setMargins(UiKit.dp(this,14),0,0,0);
        top.addView(titles,titleLp);
        root.addView(top);

        AppProfileStore.Profile current = AppProfileStore.get(this,pkg);
        loaded = current;
        ScreenContextClassifier.Result auto = ScreenContextClassifier.classify(this,"",false,pkg,label);

        LinearLayout preview = card();
        TextView previewTitle = UiKit.text(this, getString(R.string.appprof_orbit_behavior), 11, UiKit.accent(this), true);
        preview.addView(previewTitle);
        String detected = AppProfileStore.CATEGORY_AUTO.equals(current.category)
                ? (auto.confidence >= 65 ? AppProfileStore.categoryDisplayLabel(this, auto.category)
                        : getString(R.string.appprof_auto))
                : AppProfileStore.categoryDisplayLabel(this, current.category);
        preview.addView(infoLine(getString(R.string.appprof_context), detected));
        preview.addView(infoLine(getString(R.string.appprof_privacy), AppProfileStore.effectivePrivacyDisplayLabel(this,pkg)));
        TextView previewNote = UiKit.text(this,
                getString(R.string.appprof_auto_note),
                12, UiKit.MUTED, false);
        previewNote.setPadding(0,UiKit.dp(this,8),0,0);
        preview.addView(previewNote);
        root.addView(preview, cardLp());

        root.addView(section(getString(R.string.appprof_behavior_header)));
        LinearLayout behavior = card();
        category = addSelector(behavior,getString(R.string.appprof_screen_type),CATEGORY_LABELS,CATEGORY_VALUES,current.category);
        root.addView(behavior,cardLp());

        root.addView(section(getString(R.string.appprof_privacy_header)));
        LinearLayout privacyCard = card();
        privacy = addSelector(privacyCard,getString(R.string.appprof_privacy_level),PRIVACY_LABELS,PRIVACY_VALUES,current.privacyPolicy);
        screen = addSelector(privacyCard,getString(R.string.appprof_screen_context),SCREEN_LABELS,SCREEN_VALUES,current.screenPolicy);
        screenshot = addSelector(privacyCard,getString(R.string.appprof_screenshots),SHOT_LABELS,SHOT_VALUES,current.screenshotPolicy);
        TextView privacyNote = UiKit.text(this,
                getString(R.string.appprof_privacy_note),
                12,UiKit.MUTED,false);
        privacyNote.setPadding(0,UiKit.dp(this,8),0,0);
        privacyCard.addView(privacyNote);
        root.addView(privacyCard,cardLp());

        root.addView(section(getString(R.string.appprof_quick_header)));
        LinearLayout actions = card();
        TextView actionNote = UiKit.text(this,
                getString(R.string.appprof_quick_note),
                12, UiKit.MUTED, false);
        actionNote.setPadding(0,0,0,UiKit.dp(this,5));
        actions.addView(actionNote);
        action1 = addSelector(actions,getString(R.string.appprof_action_n, 1),ACTION_LABELS,ACTION_VALUES,current.action1);
        action2 = addSelector(actions,getString(R.string.appprof_action_n, 2),ACTION_LABELS,ACTION_VALUES,current.action2);
        action3 = addSelector(actions,getString(R.string.appprof_action_n, 3),ACTION_LABELS,ACTION_VALUES,current.action3);
        root.addView(actions,cardLp());

        Button save = primaryButton(getString(R.string.appprof_save));
        save.setOnClickListener(v -> {
            AppProfileStore.save(this,new AppProfileStore.Profile(pkg,label,
                    selectedValue(category),selectedValue(privacy),
                    selectedValue(screen),selectedValue(screenshot),
                    current.intelligenceMode,selectedValue(action1),
                    selectedValue(action2),selectedValue(action3),
                    System.currentTimeMillis()));
            Toast.makeText(this,getString(R.string.appprof_saved),Toast.LENGTH_SHORT).show();
            finish();
        });
        root.addView(save,new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,UiKit.dp(this,50)));

        Button reset = secondaryButton(getString(R.string.appprof_reset));
        reset.setOnClickListener(v -> {
            AppProfileStore.reset(this,pkg);
            Toast.makeText(this,getString(R.string.appprof_reset_done),Toast.LENGTH_SHORT).show();
            finish();
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,UiKit.dp(this,48));
        rlp.setMargins(0,UiKit.dp(this,10),0,0);
        root.addView(reset,rlp);
        return scroll;
    }

    private TextView section(String text) {
        TextView t = UiKit.text(this,text,11,UiKit.MUTED,true);
        t.setPadding(UiKit.dp(this,4),UiKit.dp(this,2),0,UiKit.dp(this,7));
        return t;
    }

    private TextView infoLine(String key, String value) {
        TextView t = UiKit.text(this,key + "  ·  " + value,13,UiKit.TEXT,false);
        t.setPadding(0,UiKit.dp(this,7),0,0);
        return t;
    }

    private OptionSelector addSelector(LinearLayout card,String labelText,int[] labelRes,
                                     String[] values,String selected) {
        String[] labels = new String[labelRes.length];
        for (int i = 0; i < labels.length; i++) labels[i] = getString(labelRes[i]);
        TextView l = UiKit.text(this,labelText,13,UiKit.MUTED,true);
        l.setPadding(0,UiKit.dp(this,8),0,UiKit.dp(this,5));
        card.addView(l);

        LinearLayout field = new LinearLayout(this);
        field.setOrientation(LinearLayout.HORIZONTAL);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setPadding(UiKit.dp(this,16),0,UiKit.dp(this,14),0);
        field.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.accent(this),72),UiKit.accent(this),16,this));

        TextView value = UiKit.text(this, "", 15, UiKit.TEXT, false);
        value.setMaxLines(2);
        field.addView(value,new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView arrow = UiKit.text(this, "▾", 18, UiKit.MUTED, true);
        arrow.setPadding(UiKit.dp(this,12),0,0,0);
        field.addView(arrow);

        int index = Arrays.asList(values).indexOf(selected);
        OptionSelector selector = new OptionSelector(labels, values, field, value, index < 0 ? 0 : index);
        updateSelector(selector);

        field.setOnClickListener(v -> UiKit.showOrbitMenu(this, field, labels,
                selector.selectedIndex, (choiceIndex, choiceLabel) -> {
                    selector.selectedIndex = choiceIndex;
                    updateSelector(selector);
                }));
        UiKit.pressScale(field);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this,54));
        lp.setMargins(0, 0, 0, UiKit.dp(this,8));
        card.addView(field, lp);
        return selector;
    }

    private void updateSelector(OptionSelector selector) {
        if (selector == null) return;
        int index = selector.selectedIndex;
        if (index < 0 || index >= selector.labels.length) index = 0;
        selector.selectedIndex = index;
        selector.valueView.setText(selector.labels[index]);
    }

    private String selectedValue(OptionSelector selector) {
        int i = selector == null ? 0 : selector.selectedIndex;
        if (selector == null || i < 0 || i >= selector.values.length) return selector == null ? "" : selector.values[0];
        return selector.values[i];
    }

    private LinearLayout card() {
        LinearLayout c=new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(UiKit.dp(this,16),UiKit.dp(this,12),UiKit.dp(this,16),UiKit.dp(this,16));
        c.setBackground(UiKit.outlined(UiKit.SURFACE,UiKit.withAlpha(UiKit.accent(this),34),20,this));
        return c;
    }
    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0,0,0,UiKit.dp(this,14));
        return lp;
    }
    private Button primaryButton(String t) {
        Button b=new Button(this); b.setText(t); b.setAllCaps(false); b.setTextSize(14);
        b.setTextColor(UiKit.onAccent(this)); b.setMinHeight(0); b.setMinimumHeight(0);
        b.setStateListAnimator(null); b.setBackground(UiKit.ripple(UiKit.accent(this),UiKit.onAccent(this),18,this));
        UiKit.pressScale(b); return b;
    }
    private Button secondaryButton(String t) {
        Button b=primaryButton(t); b.setTextColor(UiKit.TEXT);
        b.setBackground(UiKit.rippleOutlined(UiKit.SURFACE,UiKit.withAlpha(UiKit.accent(this),80),UiKit.accent(this),18,this));
        return b;
    }
    private ImageButton iconButton(int res,String desc) {
        ImageButton b=new ImageButton(this); b.setImageResource(res);
        b.setImageTintList(ColorStateList.valueOf(UiKit.accent(this)));
        b.setBackground(UiKit.ripple(UiKit.SURFACE,UiKit.accent(this),18,this));
        b.setContentDescription(desc);
        b.setPadding(UiKit.dp(this,11),UiKit.dp(this,11),UiKit.dp(this,11),UiKit.dp(this,11));
        UiKit.pressScale(b); return b;
    }
}
