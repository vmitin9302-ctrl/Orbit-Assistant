package com.orbit.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** Orbit-themed editor for scheduled time triggers. */
public class TimeTriggerEditorActivity extends Activity {
    public static final String EXTRA_ROUTINE_ID = "routine_id";
    public static final String EXTRA_TRIGGER_ID = "trigger_id";

    private String routineId;
    private String triggerId;
    private RoutineStore.Routine routine;
    private RoutineTriggerStore.Trigger original;

    private int hour;
    private int minute;
    private LocalDate startDate;
    private String mode;
    private int weekdayMask;
    private int intervalCount;
    private String intervalUnit;
    private boolean enabled;
    private boolean dirty;
    /** Interactive Back for this page. Its classification lives in OrbitNavigation. */
    private OrbitPredictiveBack navigation;

    private Button timeButton;
    private Button repeatButton;
    private Button dateButton;
    private LinearLayout weeklyBox;
    private LinearLayout customBox;
    private EditText intervalField;
    private Button unitButton;
    private CheckBox enabledBox;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        UiKit.syncTheme(this);
        routineId = getIntent().getStringExtra(EXTRA_ROUTINE_ID);
        triggerId = getIntent().getStringExtra(EXTRA_TRIGGER_ID);
        routine = RoutineStore.findById(this, routineId);
        original = RoutineTriggerStore.findById(this, triggerId);
        if (routine == null || (triggerId != null && original == null)) { finish(); return; }
        loadState();
        Window w = getWindow();
        w.setStatusBarColor(UiKit.BG); w.setNavigationBarColor(UiKit.BG);
        View content = buildContent();
        setContentView(content);
        UiKit.applyActivityInsets(this, content, true);
        refreshFields();
        // Offered only while there is nothing to lose. The discard confirmation below is unchanged
        // and is still what Back reaches once there is, so the page never slides away on a decision
        // the user has not made yet.
        navigation = OrbitPredictiveBack.install(this, new OrbitPredictiveBack.Screen() {
            @Override public boolean canNavigate() { return !dirty; }
            @Override public void navigateBack() { onBackPressed(); }
            @Override public String screenName() {
                return OrbitNavigation.labelFor(TimeTriggerEditorActivity.class);
            }
        });
    }

    @Override protected void onResume() { super.onResume(); UiPresence.enter(this); }
    @Override protected void onPause() { UiPresence.leave(this); super.onPause(); }
    @Override public void onBackPressed() { if (dirty) confirmDiscard(); else super.onBackPressed(); }

    private void loadState() {
        if (original != null) {
            hour = original.hour; minute = original.minute;
            startDate = LocalDate.of(original.startYear, original.startMonth, original.startDay);
            mode = original.mode; weekdayMask = original.weekdayMask;
            intervalCount = original.intervalCount; intervalUnit = original.intervalUnit;
            enabled = original.enabled;
        } else {
            LocalDateTime soon = LocalDateTime.now().plusMinutes(5);
            hour = soon.getHour(); minute = soon.getMinute(); startDate = soon.toLocalDate();
            mode = RoutineTriggerStore.MODE_ONCE;
            weekdayMask = RoutineTriggerSchedule.bitFor(startDate.getDayOfWeek());
            intervalCount = 1; intervalUnit = RoutineTriggerStore.UNIT_DAYS; enabled = true;
        }
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); OrbitBackground.applyPage(scroll);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 20); page.setPadding(p, UiKit.dp(this, 26), p, UiKit.dp(this, 48));
        scroll.addView(page, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = iconButton(R.drawable.ic_back, getString(R.string.ui_back_76900f1b)); back.setOnClickListener(v -> onBackPressed());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(UiKit.dp(this,48),UiKit.dp(this,48)); blp.rightMargin=UiKit.dp(this,12); header.addView(back,blp);
        LinearLayout titles = new LinearLayout(this); titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(UiKit.text(this, getString(original == null ? R.string.d_tte_new : R.string.d_tte_edit), 25, UiKit.TEXT, true));
        titles.addView(UiKit.text(this, routine.name, 12, UiKit.MUTED, false));
        header.addView(titles,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1)); page.addView(header);

        TextView intro = UiKit.text(this,
                getString(R.string.d_tte_intro),
                14, UiKit.MUTED, false);
        intro.setLineSpacing(0,1.13f); LinearLayout.LayoutParams ilp=new LinearLayout.LayoutParams(-1,-2); ilp.setMargins(2,UiKit.dp(this,16),2,UiKit.dp(this,14)); page.addView(intro,ilp);

        LinearLayout schedule = card();
        schedule.addView(label(getString(R.string.d_tte_time)));
        timeButton = selectorButton(""); timeButton.setOnClickListener(v -> showTimeDialog()); schedule.addView(timeButton, selectorLp());
        schedule.addView(label(getString(R.string.d_tte_repeat)));
        repeatButton = selectorButton(""); repeatButton.setOnClickListener(v -> showRepeatMenu()); schedule.addView(repeatButton, selectorLp());
        schedule.addView(label(getString(R.string.d_tte_start_date)));
        dateButton = selectorButton(""); dateButton.setOnClickListener(v -> showDateDialog()); schedule.addView(dateButton, selectorLp());

        weeklyBox = new LinearLayout(this); weeklyBox.setOrientation(LinearLayout.VERTICAL); weeklyBox.setPadding(0,UiKit.dp(this,10),0,0);
        weeklyBox.addView(label(getString(R.string.d_tte_run_on))); weeklyBox.addView(buildWeekdayRows()); schedule.addView(weeklyBox);

        customBox = new LinearLayout(this); customBox.setOrientation(LinearLayout.VERTICAL); customBox.setPadding(0,UiKit.dp(this,10),0,0);
        customBox.addView(label(getString(R.string.d_tte_every)));
        LinearLayout intervalRow = new LinearLayout(this); intervalRow.setGravity(Gravity.CENTER_VERTICAL);
        intervalField = new EditText(this); intervalField.setTextColor(UiKit.TEXT); intervalField.setHintTextColor(UiKit.MUTED); intervalField.setTextSize(14); intervalField.setSingleLine(true);
        intervalField.setInputType(InputType.TYPE_CLASS_NUMBER); intervalField.setPadding(UiKit.dp(this,14),0,UiKit.dp(this,14),0);
        intervalField.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2,Color.rgb(53,58,72),UiKit.accent(this),15,this));
        intervalField.setOnFocusChangeListener((v,has)->{ if(has) dirty=true; });
        intervalRow.addView(intervalField,new LinearLayout.LayoutParams(0,UiKit.dp(this,50),0.36f));
        unitButton = selectorButton(""); unitButton.setOnClickListener(v -> showUnitMenu());
        LinearLayout.LayoutParams ulp=new LinearLayout.LayoutParams(0,UiKit.dp(this,50),0.64f); ulp.leftMargin=UiKit.dp(this,9); intervalRow.addView(unitButton,ulp); customBox.addView(intervalRow);
        TextView customHelp=UiKit.text(this,getString(R.string.d_tte_custom_help),11,UiKit.MUTED,false);
        customHelp.setPadding(0,UiKit.dp(this,7),0,0); customBox.addView(customHelp); schedule.addView(customBox);

        enabledBox = new CheckBox(this); enabledBox.setText(R.string.d_tte_enabled); enabledBox.setTextColor(UiKit.TEXT); enabledBox.setTextSize(14); enabledBox.setButtonTintList(ColorStateList.valueOf(UiKit.accent(this)));
        enabledBox.setPadding(0,UiKit.dp(this,10),0,0); enabledBox.setOnCheckedChangeListener((b,c)->{ if(enabled!=c){enabled=c;dirty=true;} }); UiKit.pressScale(enabledBox); schedule.addView(enabledBox);
        page.addView(schedule, cardLp());

        LinearLayout note = card();
        note.addView(UiKit.text(this,getString(R.string.d_tte_bg_title),14,UiKit.TEXT,true));
        TextView body=UiKit.text(this,getString(R.string.d_tte_bg_body),12,UiKit.MUTED,false);
        body.setPadding(0,UiKit.dp(this,6),0,0); note.addView(body); page.addView(note,cardLp());

        Button save=primaryButton(getString(original==null?R.string.d_tte_save_trigger:R.string.d_tte_save_changes)); save.setOnClickListener(v->save()); page.addView(save,new LinearLayout.LayoutParams(-1,UiKit.dp(this,50)));
        return scroll;
    }

    private View buildWeekdayRows() {
        LinearLayout wrap = new LinearLayout(this); wrap.setOrientation(LinearLayout.VERTICAL);
        String[] names={getString(R.string.d_tte_mon),getString(R.string.d_tte_tue),getString(R.string.d_tte_wed),getString(R.string.d_tte_thu),getString(R.string.d_tte_fri),getString(R.string.d_tte_sat),getString(R.string.d_tte_sun)};
        for(int row=0;row<2;row++){
            LinearLayout line=new LinearLayout(this); line.setGravity(Gravity.CENTER_VERTICAL);
            int start=row==0?0:4, end=row==0?4:7;
            for(int i=start;i<end;i++){
                final int bit=1<<i; Button b=dayButton(names[i],bit); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,UiKit.dp(this,42),1); if(i>start)lp.leftMargin=UiKit.dp(this,7); line.addView(b,lp);
            }
            LinearLayout.LayoutParams rlp=new LinearLayout.LayoutParams(-1,-2); if(row>0)rlp.topMargin=UiKit.dp(this,7); wrap.addView(line,rlp);
        }
        return wrap;
    }

    private Button dayButton(String name,int bit){
        Button b=new Button(this); b.setText(name); b.setAllCaps(false); b.setTextSize(12); b.setMinHeight(0); b.setMinimumHeight(0); b.setStateListAnimator(null); b.setTag(bit); UiKit.pressScale(b);
        b.setOnClickListener(v->{ int x=(Integer)v.getTag(); if((weekdayMask&x)!=0) weekdayMask&=~x; else weekdayMask|=x; dirty=true; refreshWeekdayButtons(weeklyBox); });
        return b;
    }

    private void refreshFields() {
        timeButton.setText(RoutineActionCatalog.timeLabel(hour,minute));
        repeatButton.setText(repeatText());
        dateButton.setText(startDate.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)));
        enabledBox.setChecked(enabled);
        boolean weekly = RoutineTriggerStore.MODE_WEEKLY.equals(mode) || (RoutineTriggerStore.MODE_CUSTOM.equals(mode) && RoutineTriggerStore.UNIT_WEEKS.equals(intervalUnit));
        weeklyBox.setVisibility(weekly?View.VISIBLE:View.GONE);
        boolean monthlyPreset = RoutineTriggerStore.MODE_CUSTOM.equals(mode) &&
                RoutineTriggerStore.UNIT_MONTHS.equals(intervalUnit) && intervalCount == 1;
        customBox.setVisibility(RoutineTriggerStore.MODE_CUSTOM.equals(mode) && !monthlyPreset ? View.VISIBLE : View.GONE);
        intervalField.setText(String.valueOf(intervalCount));
        unitButton.setText(unitLabel());
        refreshWeekdayButtons(weeklyBox);
    }

    private void refreshWeekdayButtons(ViewGroup root){
        if(root==null)return; for(int i=0;i<root.getChildCount();i++){ View v=root.getChildAt(i); if(v instanceof Button && v.getTag() instanceof Integer){ int bit=(Integer)v.getTag(); boolean on=(weekdayMask&bit)!=0; ((Button)v).setTextColor(on?UiKit.onAccent(this):UiKit.TEXT); v.setBackground(on?UiKit.ripple(UiKit.accent(this),UiKit.onAccent(this),13,this):UiKit.rippleOutlined(UiKit.SURFACE_2,Color.rgb(53,58,72),UiKit.accent(this),13,this)); } else if(v instanceof ViewGroup) refreshWeekdayButtons((ViewGroup)v); }
    }

    private String repeatText(){
        if(RoutineTriggerStore.MODE_ONCE.equals(mode))return getString(R.string.d_tte_once);
        if(RoutineTriggerStore.MODE_DAILY.equals(mode))return getString(R.string.d_tte_daily);
        if(RoutineTriggerStore.MODE_WEEKDAYS.equals(mode))return getString(R.string.d_tte_weekdays);
        if(RoutineTriggerStore.MODE_WEEKENDS.equals(mode))return getString(R.string.d_tte_weekends);
        if(RoutineTriggerStore.MODE_WEEKLY.equals(mode)) return getString(intervalCount==2?R.string.d_tte_every_2_weeks:R.string.d_tte_weekly);
        if(RoutineTriggerStore.MODE_CUSTOM.equals(mode) && RoutineTriggerStore.UNIT_MONTHS.equals(intervalUnit) && intervalCount==1) return getString(R.string.d_tte_monthly);
        return getString(R.string.d_tte_custom_interval);
    }
    private String unitLabel(){ if(RoutineTriggerStore.UNIT_WEEKS.equals(intervalUnit))return getString(R.string.d_tte_weeks); if(RoutineTriggerStore.UNIT_MONTHS.equals(intervalUnit))return getString(R.string.d_tte_months); return getString(R.string.d_tte_days); }

    private String unitPlural(){ if(RoutineTriggerStore.UNIT_WEEKS.equals(intervalUnit))return getString(R.string.d_tte_weeks_plural); if(RoutineTriggerStore.UNIT_MONTHS.equals(intervalUnit))return getString(R.string.d_tte_months_plural); return getString(R.string.d_tte_days_plural); }

    private void showRepeatMenu(){
        String[] labels={getString(R.string.d_tte_once),getString(R.string.d_tte_daily),getString(R.string.d_tte_weekdays),getString(R.string.d_tte_weekends),getString(R.string.d_tte_weekly),getString(R.string.d_tte_monthly),getString(R.string.d_tte_every_2_weeks),getString(R.string.d_tte_custom_interval)};
        int selected=repeatIndex();
        UiKit.showOrbitMenu(this,repeatButton,labels,selected,(i,l)->{
            if(i==0)mode=RoutineTriggerStore.MODE_ONCE;
            else if(i==1)mode=RoutineTriggerStore.MODE_DAILY;
            else if(i==2)mode=RoutineTriggerStore.MODE_WEEKDAYS;
            else if(i==3)mode=RoutineTriggerStore.MODE_WEEKENDS;
            else if(i==4){mode=RoutineTriggerStore.MODE_WEEKLY;intervalCount=1;intervalUnit=RoutineTriggerStore.UNIT_WEEKS;if(weekdayMask==0)weekdayMask=RoutineTriggerSchedule.bitFor(startDate.getDayOfWeek());}
            else if(i==5){mode=RoutineTriggerStore.MODE_CUSTOM;intervalCount=1;intervalUnit=RoutineTriggerStore.UNIT_MONTHS;}
            else if(i==6){mode=RoutineTriggerStore.MODE_WEEKLY;intervalCount=2;intervalUnit=RoutineTriggerStore.UNIT_WEEKS;if(weekdayMask==0)weekdayMask=RoutineTriggerSchedule.bitFor(startDate.getDayOfWeek());}
            else {mode=RoutineTriggerStore.MODE_CUSTOM;if(intervalCount<1 || (RoutineTriggerStore.UNIT_MONTHS.equals(intervalUnit)&&intervalCount==1))intervalCount=2;}
            dirty=true; refreshFields();
        });
    }
    private int repeatIndex(){ if(RoutineTriggerStore.MODE_ONCE.equals(mode))return 0;if(RoutineTriggerStore.MODE_DAILY.equals(mode))return 1;if(RoutineTriggerStore.MODE_WEEKDAYS.equals(mode))return 2;if(RoutineTriggerStore.MODE_WEEKENDS.equals(mode))return 3;if(RoutineTriggerStore.MODE_WEEKLY.equals(mode)&&intervalCount==2)return 6;if(RoutineTriggerStore.MODE_WEEKLY.equals(mode))return 4;if(RoutineTriggerStore.MODE_CUSTOM.equals(mode)&&RoutineTriggerStore.UNIT_MONTHS.equals(intervalUnit)&&intervalCount==1)return 5;return 7; }

    private void showUnitMenu(){ String[] labels={getString(R.string.d_tte_days),getString(R.string.d_tte_weeks),getString(R.string.d_tte_months)}; int selected=RoutineTriggerStore.UNIT_WEEKS.equals(intervalUnit)?1:RoutineTriggerStore.UNIT_MONTHS.equals(intervalUnit)?2:0;
        UiKit.showOrbitMenu(this,unitButton,labels,selected,(i,l)->{ intervalUnit=i==1?RoutineTriggerStore.UNIT_WEEKS:i==2?RoutineTriggerStore.UNIT_MONTHS:RoutineTriggerStore.UNIT_DAYS; if(RoutineTriggerStore.UNIT_WEEKS.equals(intervalUnit)&&weekdayMask==0)weekdayMask=RoutineTriggerSchedule.bitFor(startDate.getDayOfWeek()); dirty=true; refreshFields(); }); }

    private void showTimeDialog(){
        LinearLayout form=dialogForm(); form.addView(dialogLabel(getString(R.string.d_tte_hour))); EditText h=numeric(String.valueOf(displayHour(hour))); form.addView(h,fieldLp());
        form.addView(dialogLabel(getString(R.string.d_tte_minute))); EditText m=numeric(String.valueOf(minute)); form.addView(m,fieldLp());
        final int[] ap={hour>=12?1:0}; Button apButton=selectorButton(ap[0]==0?"AM":"PM"); apButton.setOnClickListener(v->UiKit.showOrbitMenu(this,apButton,new String[]{"AM","PM"},ap[0],(i,l)->{ap[0]=i;apButton.setText(l);})); form.addView(apButton,selectorLp());
        AlertDialog d=new AlertDialog.Builder(this).setTitle(getString(R.string.d_tte_choose_time)).setView(form).setNegativeButton(getString(R.string.ui_cancel_19766ed6),null).setPositiveButton(getString(R.string.d_tte_set),null).create();
        UiKit.styleOrbitDialog(d,this,false,()->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{Integer hv=parse(h.getText().toString()),mv=parse(m.getText().toString());if(hv==null||hv<1||hv>12||mv==null||mv<0||mv>59){Toast.makeText(this,getString(R.string.d_tte_invalid_time),Toast.LENGTH_SHORT).show();return;}hour=hv%12+(ap[0]==1?12:0);minute=mv;dirty=true;refreshFields();d.dismiss();})); d.show();
    }

    private void showDateDialog(){
        LinearLayout form=dialogForm(); form.addView(dialogLabel(getString(R.string.d_tte_month))); EditText mo=numeric(String.valueOf(startDate.getMonthValue())); form.addView(mo,fieldLp());
        form.addView(dialogLabel(getString(R.string.d_tte_day))); EditText da=numeric(String.valueOf(startDate.getDayOfMonth())); form.addView(da,fieldLp());
        form.addView(dialogLabel(getString(R.string.d_tte_year))); EditText yr=numeric(String.valueOf(startDate.getYear())); form.addView(yr,fieldLp());
        AlertDialog d=new AlertDialog.Builder(this).setTitle(getString(R.string.d_tte_choose_date)).setView(form).setNegativeButton(getString(R.string.ui_cancel_19766ed6),null).setPositiveButton(getString(R.string.d_tte_set),null).create();
        UiKit.styleOrbitDialog(d,this,false,()->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{Integer y=parse(yr.getText().toString()),m=parse(mo.getText().toString()),day=parse(da.getText().toString());try{LocalDate chosen=LocalDate.of(y==null?0:y,m==null?0:m,day==null?0:day);startDate=chosen;if(weekdayMask==0)weekdayMask=RoutineTriggerSchedule.bitFor(chosen.getDayOfWeek());dirty=true;refreshFields();d.dismiss();}catch(Exception e){Toast.makeText(this,getString(R.string.d_tte_invalid_date),Toast.LENGTH_SHORT).show();}})); d.show();
    }

    private void save(){
        if(RoutineTriggerStore.MODE_CUSTOM.equals(mode)){Integer n=parse(intervalField.getText().toString());int max=RoutineTriggerStore.UNIT_WEEKS.equals(intervalUnit)?52:RoutineTriggerStore.UNIT_MONTHS.equals(intervalUnit)?60:365;if(n==null||n<1||n>max){Toast.makeText(this,getString(R.string.d_tte_interval_range,max,unitPlural()),Toast.LENGTH_SHORT).show();return;}intervalCount=n;}
        boolean weeks=RoutineTriggerStore.MODE_WEEKLY.equals(mode)||(RoutineTriggerStore.MODE_CUSTOM.equals(mode)&&RoutineTriggerStore.UNIT_WEEKS.equals(intervalUnit));
        if(weeks&&weekdayMask==0){Toast.makeText(this,getString(R.string.d_tte_need_weekday),Toast.LENGTH_SHORT).show();return;}
        RoutineTriggerStore.Trigger trigger;
        if(original==null) trigger=RoutineTriggerStore.createTime(routineId,mode,hour,minute,startDate.getYear(),startDate.getMonthValue(),startDate.getDayOfMonth(),weekdayMask,intervalCount,intervalUnit).withEnabled(enabled);
        else trigger=original.withSchedule(enabled,mode,hour,minute,startDate.getYear(),startDate.getMonthValue(),startDate.getDayOfMonth(),weekdayMask,intervalCount,intervalUnit);
        long next=enabled?RoutineTriggerSchedule.nextRun(trigger,System.currentTimeMillis()+500L):0L;
        if(enabled&&next<=0){Toast.makeText(this,getString(R.string.d_tte_no_future_run),Toast.LENGTH_LONG).show();return;}
        if(RoutineTriggerStore.hasEnabledScheduleConflict(this,trigger)){Toast.makeText(this,getString(R.string.d_tte_conflict),Toast.LENGTH_LONG).show();return;}
        if(!RoutineTriggerStore.upsert(this,trigger)){Toast.makeText(this,getString(R.string.d_tte_save_failed),Toast.LENGTH_SHORT).show();return;}
        RoutineTriggerScheduler.schedule(this,trigger); dirty=false; finish();
    }

    private void confirmDiscard(){ AlertDialog d=new AlertDialog.Builder(this).setTitle(getString(R.string.d_tte_discard_title)).setMessage(getString(R.string.d_tte_discard_message)).setNegativeButton(getString(R.string.d_tte_keep_editing),null).setPositiveButton(getString(R.string.d_tte_discard),(x,w)->{dirty=false;finish();}).create();styleDialog(d,true);d.show(); }

    private int displayHour(int h){int x=h%12;return x==0?12:x;}
    private Integer parse(String s){try{return Integer.parseInt(s.trim());}catch(Exception e){return null;}}
    private LinearLayout dialogForm(){LinearLayout f=new LinearLayout(this);f.setOrientation(LinearLayout.VERTICAL);f.setPadding(UiKit.dp(this,22),UiKit.dp(this,4),UiKit.dp(this,22),UiKit.dp(this,4));return f;}
    private TextView dialogLabel(String s){TextView t=UiKit.text(this,s,11,UiKit.MUTED,true);t.setPadding(0,UiKit.dp(this,10),0,UiKit.dp(this,6));return t;}
    private EditText numeric(String text){EditText e=new EditText(this);e.setText(text);e.setTextColor(UiKit.TEXT);e.setTextSize(14);e.setSingleLine(true);e.setInputType(InputType.TYPE_CLASS_NUMBER);e.setPadding(UiKit.dp(this,14),0,UiKit.dp(this,14),0);e.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2,Color.rgb(53,58,72),UiKit.accent(this),15,this));return e;}
    private LinearLayout.LayoutParams fieldLp(){return new LinearLayout.LayoutParams(-1,UiKit.dp(this,50));}
    private TextView label(String s){TextView t=UiKit.text(this,s,11,UiKit.MUTED,true);t.setLetterSpacing(.12f);t.setPadding(2,UiKit.dp(this,10),0,UiKit.dp(this,6));return t;}
    private Button selectorButton(String s){Button b=secondaryButton(s);b.setGravity(Gravity.CENTER_VERTICAL|Gravity.LEFT);b.setPadding(UiKit.dp(this,14),0,UiKit.dp(this,14),0);return b;}
    private LinearLayout.LayoutParams selectorLp(){return new LinearLayout.LayoutParams(-1,UiKit.dp(this,50));}
    private LinearLayout card(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(UiKit.dp(this,17),UiKit.dp(this,15),UiKit.dp(this,17),UiKit.dp(this,15));c.setBackground(UiKit.outlined(UiKit.SURFACE,UiKit.withAlpha(UiKit.accent(this),40),20,this));c.setElevation(UiKit.dp(this,2));return c;}
    private LinearLayout.LayoutParams cardLp(){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,UiKit.dp(this,10));return lp;}
    private ImageButton iconButton(int res,String desc){ImageButton b=new ImageButton(this);b.setImageResource(res);b.setImageTintList(ColorStateList.valueOf(UiKit.accent(this)));b.setBackground(UiKit.ripple(UiKit.SURFACE_2,UiKit.accent(this),18,this));b.setContentDescription(desc);b.setPadding(UiKit.dp(this,11),UiKit.dp(this,11),UiKit.dp(this,11),UiKit.dp(this,11));UiKit.pressScale(b);return b;}
    private Button primaryButton(String s){Button b=new Button(this);b.setText(s);b.setTextColor(UiKit.onAccent(this));b.setTextSize(14);b.setAllCaps(false);b.setBackground(UiKit.ripple(UiKit.accent(this),UiKit.onAccent(this),15,this));b.setMinHeight(0);b.setMinimumHeight(0);b.setStateListAnimator(null);UiKit.pressScale(b);return b;}
    private Button secondaryButton(String s){Button b=new Button(this);b.setText(s);b.setTextColor(UiKit.TEXT);b.setTextSize(14);b.setAllCaps(false);b.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2,Color.rgb(53,58,72),UiKit.accent(this),15,this));b.setMinHeight(0);b.setMinimumHeight(0);b.setStateListAnimator(null);UiKit.pressScale(b);return b;}
    private void styleDialog(AlertDialog d,boolean destructive){UiKit.styleOrbitDialog(d,this,destructive);}
    private void styleShown(AlertDialog d,boolean destructive){UiKit.applyDialogTypography(d);tint(d.getWindow()==null?null:d.getWindow().getDecorView());Button p=d.getButton(AlertDialog.BUTTON_POSITIVE),n=d.getButton(AlertDialog.BUTTON_NEGATIVE);if(p!=null)p.setTextColor(destructive?Color.rgb(239,105,105):UiKit.accent(this));if(n!=null)n.setTextColor(UiKit.accent(this));}
    private void tint(View v){if(v==null)return;if(v instanceof TextView&&!(v instanceof Button))((TextView)v).setTextColor(UiKit.TEXT);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)tint(((ViewGroup)v).getChildAt(i));}
}
