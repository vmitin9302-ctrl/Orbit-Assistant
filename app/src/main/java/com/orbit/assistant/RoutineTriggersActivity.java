package com.orbit.assistant;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Build;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Lists and manages automatic time and location triggers for one saved routine. */
public class RoutineTriggersActivity extends Activity {
    public static final String EXTRA_ROUTINE_ID = "routine_id";
    private static final int REQ_TRIGGER_ALERTS = 863;
    private static final int REQ_FINE_LOCATION = 865;
    private static final int REQ_BACKGROUND_LOCATION = 866;

    private String routineId;
    private RoutineStore.Routine routine;
    private LinearLayout timeTriggerList;
    private LinearLayout locationTriggerList;
    /** The LOCATION TRIGGERS heading, hidden in the Play edition when there is nothing to show. */
    private TextView locationSectionTitle;
    private LinearLayout precisionCard;
    private LinearLayout locationAccessCard;

    /** Interactive Back for this page. Its classification lives in OrbitNavigation. */
    private OrbitPredictiveBack navigation;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        UiKit.syncTheme(this);
        routineId = getIntent().getStringExtra(EXTRA_ROUTINE_ID);
        routine = RoutineStore.findById(this, routineId);
        if (routine == null) { finish(); return; }
        Window w = getWindow();
        w.setStatusBarColor(UiKit.BG);
        w.setNavigationBarColor(UiKit.BG);
        View content = buildContent();
        setContentView(content);
        UiKit.applyActivityInsets(this, content, true);
        navigation = OrbitPredictiveBack.install(this);
    }

    @Override protected void onResume() {
        super.onResume();
        UiPresence.enter(this);
        routine = RoutineStore.findById(this, routineId);
        if (routine == null) { finish(); return; }
        RoutineTriggerScheduler.rescheduleAll(this);
        refresh();
    }

    @Override protected void onPause() { UiPresence.leave(this); super.onPause(); }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_TRIGGER_ALERTS) refreshPrecisionCard();
        if (requestCode == REQ_FINE_LOCATION || requestCode == REQ_BACKGROUND_LOCATION) {
            RoutineTriggerScheduler.rescheduleAll(this);
            refreshLocationAccessCard();
            refresh();
        }
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        OrbitBackground.applyPage(scroll);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 20);
        page.setPadding(p, UiKit.dp(this, 26), p, UiKit.dp(this, 48));
        scroll.addView(page, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = iconButton(R.drawable.ic_back, getString(R.string.ui_back_76900f1b));
        back.setOnClickListener(v -> navigation.performBack());
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(UiKit.dp(this, 48), UiKit.dp(this, 48));
        backLp.rightMargin = UiKit.dp(this, 12);
        header.addView(back, backLp);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(UiKit.text(this, getString(R.string.d_rtrg_title), 25, UiKit.TEXT, true));
        titles.addView(UiKit.text(this, routine.name, 12, UiKit.MUTED, false));
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(header);

        TextView intro = UiKit.text(this, OrbitDistribution.supportsLocationTriggers()
                ? getString(R.string.d_rtrg_intro_location)
                : getString(R.string.d_rtrg_intro),
                14, UiKit.MUTED, false);
        intro.setLineSpacing(0, 1.13f);
        LinearLayout.LayoutParams introLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        introLp.setMargins(UiKit.dp(this, 2), UiKit.dp(this, 16), UiKit.dp(this, 2), UiKit.dp(this, 14));
        page.addView(intro, introLp);

        precisionCard = card();
        page.addView(precisionCard, cardLp());

        locationAccessCard = card();
        page.addView(locationAccessCard, cardLp());

        Button addTime = primaryButton(getString(R.string.d_rtrg_new_time));
        addTime.setOnClickListener(v -> openNewTimeTrigger());
        page.addView(addTime, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 50)));

        // Only where location triggers exist. The Play edition explains their absence in
        // locationAccessCard instead of offering a button that could never arm anything.
        if (OrbitDistribution.supportsLocationTriggers()) {
            Button addLocation = secondaryButton(getString(R.string.d_rtrg_new_location));
            addLocation.setOnClickListener(v -> openNewLocationTrigger());
            LinearLayout.LayoutParams locationAddLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 50));
            locationAddLp.topMargin = UiKit.dp(this, 9);
            page.addView(addLocation, locationAddLp);
        }

        page.addView(sectionTitle(getString(R.string.d_rtrg_time_section)));
        timeTriggerList = new LinearLayout(this);
        timeTriggerList.setOrientation(LinearLayout.VERTICAL);
        page.addView(timeTriggerList);

        locationSectionTitle = sectionTitle(getString(R.string.d_rtrg_location_section));
        page.addView(locationSectionTitle);
        locationTriggerList = new LinearLayout(this);
        locationTriggerList.setOrientation(LinearLayout.VERTICAL);
        page.addView(locationTriggerList);
        return scroll;
    }

    private void openNewTimeTrigger() {
        if (!canAddTrigger()) return;
        startActivity(new Intent(this, TimeTriggerEditorActivity.class)
                .putExtra(TimeTriggerEditorActivity.EXTRA_ROUTINE_ID, routineId));
    }

    private void openNewLocationTrigger() {
        if (!OrbitDistribution.supportsLocationTriggers() || !canAddTrigger()) return;
        startActivity(new Intent(this, LocationTriggerEditorActivity.class)
                .putExtra(LocationTriggerEditorActivity.EXTRA_ROUTINE_ID, routineId));
    }

    private boolean canAddTrigger() {
        if (RoutineTriggerStore.listForRoutine(this, routineId).size() >= RoutineTriggerStore.MAX_TRIGGERS_PER_ROUTINE) {
            Toast.makeText(this, getString(R.string.d_rtrg_max), Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private void refresh() {
        refreshPrecisionCard();
        refreshLocationAccessCard();
        if (timeTriggerList == null || locationTriggerList == null) return;
        timeTriggerList.removeAllViews();
        locationTriggerList.removeAllViews();
        List<RoutineTriggerStore.Trigger> times = new ArrayList<>();
        List<RoutineTriggerStore.Trigger> locations = new ArrayList<>();
        for (RoutineTriggerStore.Trigger trigger : RoutineTriggerStore.listForRoutine(this, routineId)) {
            if (RoutineTriggerStore.TYPE_LOCATION.equals(trigger.type)) locations.add(trigger);
            else times.add(trigger);
        }
        renderTriggerGroup(timeTriggerList, times, false);
        // In the Play edition an empty location section would invite something it cannot do.
        // Saved location triggers, such as ones brought over from a GitHub install, are still
        // listed so they can be seen and deleted, and nothing about them is changed.
        boolean showLocations = OrbitDistribution.supportsLocationTriggers() || !locations.isEmpty();
        if (locationSectionTitle != null) {
            locationSectionTitle.setVisibility(showLocations ? View.VISIBLE : View.GONE);
        }
        locationTriggerList.setVisibility(showLocations ? View.VISIBLE : View.GONE);
        if (showLocations) renderTriggerGroup(locationTriggerList, locations, true);
    }

    private void renderTriggerGroup(LinearLayout list, List<RoutineTriggerStore.Trigger> triggers, boolean location) {
        if (triggers.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(UiKit.text(this, getString(location ? R.string.d_rtrg_no_location : R.string.d_rtrg_no_time), 16, UiKit.TEXT, true));
            TextView note = UiKit.text(this, location
                            ? getString(R.string.d_rtrg_empty_location)
                            : getString(R.string.d_rtrg_empty_time),
                    13, UiKit.MUTED, false);
            note.setPadding(0, UiKit.dp(this, 6), 0, 0);
            empty.addView(note);
            list.addView(empty, cardLp());
            return;
        }
        for (RoutineTriggerStore.Trigger trigger : triggers) list.addView(triggerCard(trigger), cardLp());
    }

    private void refreshPrecisionCard() {
        if (precisionCard == null) return;
        precisionCard.removeAllViews();
        boolean exact = RoutineTriggerScheduler.canScheduleExact(this);
        precisionCard.addView(UiKit.text(this, getString(exact ? R.string.d_rtrg_precise_ready : R.string.d_rtrg_approx), 14,
                exact ? UiKit.SUCCESS : UiKit.TEXT, true));
        TextView detail = UiKit.text(this, exact
                        ? getString(R.string.d_rtrg_precise_detail)
                        : getString(R.string.d_rtrg_approx_detail),
                12, UiKit.MUTED, false);
        detail.setPadding(0, UiKit.dp(this, 5), 0, 0);
        precisionCard.addView(detail);
        if (!exact) {
            Button allow = secondaryButton(getString(R.string.d_rtrg_allow_precise));
            allow.setOnClickListener(v -> RoutineTriggerScheduler.openExactAlarmAccess(this));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 42));
            lp.topMargin = UiKit.dp(this, 10);
            precisionCard.addView(allow, lp);
        }
        boolean notifications = RoutineTriggerNotifier.notificationsAllowed(this);
        boolean runtimeNotifications = RoutineTriggerNotifier.runtimePermissionGranted(this);
        TextView notifyState = UiKit.text(this, notifications
                        ? getString(R.string.d_rtrg_alerts_ready)
                        : getString(R.string.d_rtrg_alerts_off),
                11, UiKit.MUTED, false);
        notifyState.setPadding(0, UiKit.dp(this, 9), 0, 0);
        precisionCard.addView(notifyState);
        if (!notifications) {
            Button alertSettings;
            if (!runtimeNotifications && Build.VERSION.SDK_INT >= 33) {
                alertSettings = secondaryButton(getString(R.string.d_rtrg_allow_alerts));
                alertSettings.setOnClickListener(v -> requestPermissions(
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_TRIGGER_ALERTS));
            } else {
                alertSettings = secondaryButton(getString(R.string.d_rtrg_open_alert_settings));
                alertSettings.setOnClickListener(v -> RoutineTriggerNotifier.openAlertSettings(this));
            }
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 42));
            nlp.topMargin = UiKit.dp(this, 9);
            precisionCard.addView(alertSettings, nlp);
        }
    }

    private void refreshLocationAccessCard() {
        if (locationAccessCard == null) return;
        locationAccessCard.removeAllViews();
        if (!OrbitDistribution.supportsLocationTriggers()) {
            locationAccessCard.addView(UiKit.text(this, getString(R.string.d_rtrg_location_triggers), 14, UiKit.TEXT, true));
            TextView unavailable = UiKit.text(this, OrbitDistribution.LOCATION_TRIGGERS_UNAVAILABLE,
                    12, UiKit.MUTED, false);
            unavailable.setPadding(0, UiKit.dp(this, 6), 0, 0);
            locationAccessCard.addView(unavailable);
            return;
        }
        boolean fine = RoutineLocationTriggerScheduler.hasFineLocation(this);
        boolean background = RoutineLocationTriggerScheduler.hasBackgroundLocation(this);
        boolean locationOn = RoutineLocationTriggerScheduler.isLocationEnabled(this);
        boolean ready = fine && background && locationOn;
        locationAccessCard.addView(UiKit.text(this, getString(ready ? R.string.d_rtrg_location_ready : R.string.d_rtrg_location_setup), 14,
                ready ? UiKit.SUCCESS : UiKit.TEXT, true));
        String detail;
        String button;
        if (!fine) {
            detail = getString(R.string.d_rtrg_need_precise);
            button = getString(R.string.d_rtrg_allow_precise_location);
        } else if (!background) {
            String option = RoutineLocationTriggerScheduler.backgroundPermissionLabel(this);
            detail = getString(R.string.d_rtrg_need_background, option);
            button = getString(R.string.d_rtrg_allow_background);
        } else if (!locationOn) {
            detail = getString(R.string.d_rtrg_location_off);
            button = getString(R.string.d_rtrg_turn_on_location);
        } else {
            detail = getString(R.string.d_rtrg_all_available);
            button = getString(R.string.d_rtrg_manage_access);
        }
        TextView note = UiKit.text(this, detail, 12, UiKit.MUTED, false);
        note.setPadding(0, UiKit.dp(this, 5), 0, UiKit.dp(this, 10));
        locationAccessCard.addView(note);
        Button manage = secondaryButton(button);
        manage.setOnClickListener(v -> setupLocationAccess());
        locationAccessCard.addView(manage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 42)));
    }

    private void setupLocationAccess() {
        if (!RoutineLocationTriggerScheduler.hasFineLocation(this)) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION}, REQ_FINE_LOCATION);
            return;
        }
        if (!RoutineLocationTriggerScheduler.hasBackgroundLocation(this)) {
            if (Build.VERSION.SDK_INT == 29) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, REQ_BACKGROUND_LOCATION);
            } else {
                showBackgroundLocationExplanation();
            }
            return;
        }
        if (!RoutineLocationTriggerScheduler.isLocationEnabled(this)) {
            RoutineLocationTriggerScheduler.openLocationServices(this);
            return;
        }
        RoutineLocationTriggerScheduler.openAppLocationSettings(this);
    }

    private void showBackgroundLocationExplanation() {
        String option = RoutineLocationTriggerScheduler.backgroundPermissionLabel(this);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.d_rtrg_allow_background))
                .setMessage(getString(R.string.d_rtrg_background_explain, option))
                .setNegativeButton(getString(R.string.d_rtrg_not_now), null)
                .setPositiveButton(getString(R.string.d_rtrg_open_settings), (d, w) -> RoutineLocationTriggerScheduler.openAppLocationSettings(this))
                .create();
        styleOrbitDialog(dialog, false);
        dialog.show();
    }

    private View triggerCard(RoutineTriggerStore.Trigger trigger) {
        LinearLayout card = card();
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView schedule = UiKit.text(this, RoutineTriggerSchedule.summary(this, trigger), 15, UiKit.TEXT, true);
        text.addView(schedule);
        TextView next = UiKit.text(this, triggerStateLabel(trigger), 12, UiKit.MUTED, false);
        next.setPadding(0, UiKit.dp(this, 4), 0, 0);
        text.addView(next);
        top.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        if (RoutineTriggerStore.TYPE_LOCATION.equals(trigger.type)
                && !OrbitDistribution.supportsLocationTriggers()) {
            // A saved location trigger in the Play edition: no switch, because nothing it could
            // switch on would ever run, and its stored on/off state is left for a GitHub install.
            // Deleting it is the one thing that still means something.
            ImageButton more = iconButton(R.drawable.ic_more, getString(R.string.d_rtrg_options));
            more.setOnClickListener(v -> UiKit.showOrbitMenu(this, more, new String[]{getString(R.string.ui_delete_e2d0a549)}, -1,
                    (index, label) -> confirmDelete(trigger)));
            top.addView(more, new LinearLayout.LayoutParams(UiKit.dp(this, 44), UiKit.dp(this, 44)));
            card.addView(top);
            return card;
        }

        OrbitSwitch enabled = new OrbitSwitch(this);
        enabled.setChecked(trigger.enabled, false);
        enabled.setContentDescription(getString(trigger.enabled ? R.string.d_rtrg_disable : R.string.d_rtrg_enable));
        enabled.setOnCheckedChangeListener((button, checked) -> {
            RoutineTriggerStore.Trigger latest = RoutineTriggerStore.findById(this, trigger.id);
            if (latest == null || latest.enabled == checked) return;
            RoutineTriggerStore.Trigger changed = latest.withEnabled(checked);
            if (checked && RoutineTriggerStore.hasEnabledScheduleConflict(this, changed)) {
                Toast.makeText(this, getString(R.string.d_rtrg_conflict), Toast.LENGTH_LONG).show();
                refresh();
                return;
            }
            if (RoutineTriggerStore.upsert(this, changed)) {
                if (checked) {
                    if (RoutineTriggerStore.TYPE_LOCATION.equals(changed.type)) {
                        boolean scheduled = RoutineLocationTriggerScheduler.schedule(this, changed);
                        if (!scheduled && !RoutineLocationTriggerScheduler.ready(this)) {
                            Toast.makeText(this, getString(R.string.d_rtrg_enabled_needs_setup), Toast.LENGTH_LONG).show();
                        }
                    } else {
                        long scheduled = RoutineTriggerScheduler.schedule(this, changed);
                        if (scheduled <= 0L && RoutineTriggerStore.MODE_ONCE.equals(changed.mode)) {
                            Toast.makeText(this, getString(R.string.d_rtrg_passed), Toast.LENGTH_LONG).show();
                        }
                    }
                } else {
                    RoutineTriggerScheduler.cancel(this, changed.id);
                }
                refresh();
            }
        });
        // No press-scale here: the thumb and track already carry the interaction, and stacking a
        // second animation on top of them reads as busy.
        top.addView(enabled, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ImageButton more = iconButton(R.drawable.ic_more, getString(R.string.d_rtrg_options));
        more.setOnClickListener(v -> showTriggerMenu(more, trigger));
        top.addView(more, new LinearLayout.LayoutParams(UiKit.dp(this, 44), UiKit.dp(this, 44)));
        card.addView(top);

        if (!trigger.lastResult.isEmpty()) {
            TextView status = UiKit.text(this, getString(R.string.d_rtrg_last, trigger.lastResult), 11,
                    trigger.lastResult.toLowerCase(Locale.US).contains("completed") ? UiKit.SUCCESS : UiKit.MUTED, false);
            status.setPadding(0, UiKit.dp(this, 8), 0, 0);
            card.addView(status);
        }
        return card;
    }

    private String triggerStateLabel(RoutineTriggerStore.Trigger trigger) {
        if (RoutineTriggerStore.TYPE_LOCATION.equals(trigger.type)) {
            if (!OrbitDistribution.supportsLocationTriggers()) {
                return OrbitDistribution.LOCATION_TRIGGER_UNAVAILABLE_STATE;
            }
            if (!trigger.enabled) return getString(R.string.d_rtrg_off);
            if (!RoutineLocationTriggerScheduler.hasFineLocation(this)) return getString(R.string.d_rtrg_state_precise);
            if (!RoutineLocationTriggerScheduler.hasBackgroundLocation(this)) return getString(R.string.d_rtrg_state_background);
            if (!RoutineLocationTriggerScheduler.isLocationEnabled(this)) return getString(R.string.d_rtrg_state_phone_off);
            return getString(R.string.d_rtrg_state_ready);
        }
        return nextTimeLabel(trigger);
    }

    private String nextTimeLabel(RoutineTriggerStore.Trigger t) {
        if (!t.enabled) {
            if (RoutineTriggerStore.MODE_ONCE.equals(t.mode) && t.lastRunAt > 0L) return getString(R.string.d_rtrg_finished);
            return getString(R.string.d_rtrg_off);
        }
        if (t.nextRunAt <= 0) return getString(RoutineTriggerStore.MODE_ONCE.equals(t.mode) ? R.string.d_rtrg_finished : R.string.d_rtrg_not_scheduled);
        ZonedDateTime z = Instant.ofEpochMilli(t.nextRunAt).atZone(ZoneId.systemDefault());
        LocalDate d = z.toLocalDate();
        LocalDate today = LocalDate.now();
        String day;
        if (d.equals(today)) day = getString(R.string.d_rtrg_today);
        else if (d.equals(today.plusDays(1))) day = getString(R.string.d_rtrg_tomorrow);
        else day = z.format(DateTimeFormatter.ofPattern(
                android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEMMMd"),
                Locale.getDefault()));
        return getString(R.string.d_rtrg_next, day,
                RoutineActionCatalog.timeLabel(this, z.getHour(), z.getMinute()));
    }

    private void showTriggerMenu(View anchor, RoutineTriggerStore.Trigger trigger) {
        String[] labels = {getString(R.string.d_rtrg_edit), getString(R.string.d_rtrg_duplicate), getString(R.string.ui_delete_e2d0a549)};
        UiKit.showOrbitMenu(this, anchor, labels, -1, (index, label) -> {
            if (index == 0) openEditor(trigger);
            else if (index == 1) duplicate(trigger);
            else confirmDelete(trigger);
        });
    }

    private void duplicate(RoutineTriggerStore.Trigger source) {
        if (!canAddTrigger()) return;
        RoutineTriggerStore.Trigger copy;
        if (RoutineTriggerStore.TYPE_LOCATION.equals(source.type)) {
            copy = RoutineTriggerStore.createLocation(source.routineId, source.locationName,
                    source.latitude, source.longitude, source.radiusMeters,
                    source.locationTransition).withEnabled(false);
        } else {
            copy = RoutineTriggerStore.createTime(source.routineId, source.mode,
                    source.hour, source.minute, source.startYear, source.startMonth, source.startDay,
                    source.weekdayMask, source.intervalCount, source.intervalUnit).withEnabled(false);
        }
        if (RoutineTriggerStore.upsert(this, copy)) {
            Toast.makeText(this, getString(R.string.d_rtrg_duplicated), Toast.LENGTH_SHORT).show();
            refresh();
        }
    }

    private void confirmDelete(RoutineTriggerStore.Trigger trigger) {
        int title = RoutineTriggerStore.TYPE_LOCATION.equals(trigger.type)
                ? R.string.d_rtrg_delete_location_title : R.string.d_rtrg_delete_time_title;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(title))
                .setMessage(RoutineTriggerSchedule.summary(this, trigger))
                .setNegativeButton(getString(R.string.ui_cancel_19766ed6), null)
                .setPositiveButton(getString(R.string.ui_delete_e2d0a549), (d, w) -> {
                    RoutineTriggerScheduler.cancel(this, trigger.id);
                    RoutineTriggerStore.delete(this, trigger.id);
                    refresh();
                }).create();
        styleOrbitDialog(dialog, true);
        dialog.show();
    }

    private void openEditor(RoutineTriggerStore.Trigger trigger) {
        if (RoutineTriggerStore.TYPE_LOCATION.equals(trigger.type)) {
            startActivity(new Intent(this, LocationTriggerEditorActivity.class)
                    .putExtra(LocationTriggerEditorActivity.EXTRA_ROUTINE_ID, routineId)
                    .putExtra(LocationTriggerEditorActivity.EXTRA_TRIGGER_ID, trigger.id));
        } else {
            startActivity(new Intent(this, TimeTriggerEditorActivity.class)
                    .putExtra(TimeTriggerEditorActivity.EXTRA_ROUTINE_ID, routineId)
                    .putExtra(TimeTriggerEditorActivity.EXTRA_TRIGGER_ID, trigger.id));
        }
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(UiKit.dp(this, 17), UiKit.dp(this, 15), UiKit.dp(this, 17), UiKit.dp(this, 15));
        c.setBackground(UiKit.outlined(UiKit.SURFACE, UiKit.withAlpha(UiKit.accent(this), 40), 20, this));
        c.setElevation(UiKit.dp(this, 2));
        return c;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, UiKit.dp(this, 10));
        return lp;
    }

    private TextView sectionTitle(String s) {
        TextView t = UiKit.text(this, s, 12, UiKit.MUTED, true);
        t.setLetterSpacing(0.16f);
        t.setPadding(UiKit.dp(this, 5), UiKit.dp(this, 14), 0, UiKit.dp(this, 10));
        return t;
    }

    private ImageButton iconButton(int res, String desc) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(res);
        b.setImageTintList(ColorStateList.valueOf(UiKit.accent(this)));
        b.setBackground(UiKit.ripple(UiKit.SURFACE_2, UiKit.accent(this), 18, this));
        b.setContentDescription(desc);
        b.setPadding(UiKit.dp(this, 11), UiKit.dp(this, 11), UiKit.dp(this, 11), UiKit.dp(this, 11));
        UiKit.pressScale(b);
        return b;
    }

    private Button primaryButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(UiKit.onAccent(this));
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setBackground(UiKit.ripple(UiKit.accent(this), UiKit.onAccent(this), 15, this));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        UiKit.pressScale(b);
        return b;
    }

    private Button secondaryButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(UiKit.TEXT);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2, Color.rgb(53,58,72), UiKit.accent(this), 15, this));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        UiKit.pressScale(b);
        return b;
    }

    private void styleOrbitDialog(AlertDialog dialog, boolean destructive) {
        UiKit.styleOrbitDialog(dialog, this, destructive);
    }

    private void tint(View v) {
        if (v == null) return;
        if (v instanceof TextView && !(v instanceof Button)) ((TextView)v).setTextColor(UiKit.TEXT);
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)v).getChildCount(); i++) tint(((ViewGroup)v).getChildAt(i));
    }
}
