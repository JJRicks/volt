package com.jjricks.volt;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Back up & restore: choose categories, review, then a fresh process. Terminal maintenance
 * screen; no scooter models are resumed after replacing their storage, even after cancel. */
public final class BackupActivity extends Activity {
    private static final int PICK_BACKUP=401, SAVE_BACKUP=402;
    private static final CharSequence NO_SPACE=M.backupEngineNotEnoughFreeStorage(), CHANGED=M.backupEngineSavedDataChangedReview();
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor();
    private static final AtomicInteger OPERATIONS=new AtomicInteger();
    private static boolean sessionStarted;
    private final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
    private Ui ui;
    private float textScale=1f;
    private ScrollView scroll;
    private LinearLayout content;
    private TextView status, action, closeAction;
    private EditText password, repeat;
    private ProgressBar progress;
    private boolean busy, terminal, restore, waitingForPicker, hasData, exportedComplete;
    private String preselect;
    private final LinkedHashMap<String,CheckBox> boxes=new LinkedHashMap<>();
    private Map<String,long[]> inventory;
    private Uri picked;
    private AppDataBackup.PreparedRestore staged;
    private Switch replaceAll;
    private File exported;
    private AlertDialog confirmation;
    private android.window.OnBackInvokedCallback backCallback;
    /** What each heading, button, checkbox and paragraph (status included) was given: a Msg renders in the language
     * current when shown, so a language change shows it again (relabel). The views stay plain Ui ones (TextView,
     * EditText): the emulator census keys each view by its class, so a subclass would change every Backup page. */
    private final WeakHashMap<TextView,CharSequence> words=new WeakHashMap<>();
    /** Each password field's hint, kept the same way. */
    private final WeakHashMap<EditText,CharSequence> hints=new WeakHashMap<>();
    /** The password errors as the shown language renders them, each with its Msg: a field keeps only the rendered
     * error (EditText.getError), so relabel finds the Msg by that text and shows it again in the new language. */
    private Map<String,CharSequence> shownErrors=Collections.emptyMap();
    private android.os.LocaleList shownLocales;
    /** The Back up list's scope and size line, and the Scooters page, which relabel builds again; null on other pages. */
    private Runnable refreshScope, destinations;
    /** The categories the Restore list shows, with their counts; null on other pages. */
    private Map<String,Integer> counts;

    @Override public void onCreate(Bundle state) {
        Intent active=DataMaintenance.preparedTransfer();
        if(DataMaintenance.active()&&(active==null||!getComponentName().equals(active.getComponent()))){
            super.onCreate(state);startActivity((active==null?new Intent(this,MainActivity.class):active).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));finish();return;
        }
        // Follow the rider's theme and text size; preferences are only read here.
        boolean dark=true;
        try{android.content.SharedPreferences settings=getSharedPreferences("app_settings",MODE_PRIVATE);String theme=settings.getString("theme","dark");
            dark=theme.equals("dark")||(theme.equals("system")&&(getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES);
            float scale=settings.getFloat("text_scale",1f);if(Float.isFinite(scale)&&scale>=.5f&&scale<=3f)textScale=scale;}catch(RuntimeException unreadable){/* Defaults during recovery. */}
        setTheme(dark?R.style.VoltDark:R.style.VoltLight);super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        shownLocales=locales();noteErrors();
        ui=new Ui(this,dark,textScale);
        if(Build.VERSION.SDK_INT>=30)getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(ui.bg);
        scroll=new ScrollView(this);scroll.setFillViewport(true);
        content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(ui.dp(24),ui.dp(32),ui.dp(24),ui.dp(32));scroll.addView(content);
        root.addView(scroll,new FrameLayout.LayoutParams(-1,-1));setContentView(root);
        // Insets shrink the actual viewport. Padding the ScrollView itself leaves its focus
        // scrolling calculations using the full height, so a password can sit behind the IME.
        root.setOnApplyWindowInsetsListener((v,insets)->{
            if(Build.VERSION.SDK_INT>=30){android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());v.setPadding(i.left,i.top,i.right,i.bottom);}
            else v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        scroll.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
            if(r-l!=or-ol||b-t!=ob-ot)revealPassword();
        });
        root.requestApplyInsets();
        if(Build.VERSION.SDK_INT>=33){backCallback=this::requestClose;getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,backCallback);}
        restore=getIntent().getBooleanExtra("restore",false);
        preselect=getIntent().getStringExtra("preselect");if(preselect!=null&&!AppDataBackup.CATEGORIES.contains(preselect))preselect=null;
        boolean priorSession=sessionStarted;sessionStarted=true;
        if(priorSession || state!=null){
            DataMaintenance.enter();DataMaintenance.preparedTransfer(new Intent(this,BackupActivity.class));heading(M.backupSessionInterruptedTitle());status=paragraph("");progress=new ProgressBar(this);content.addView(progress);
            terminal=true;closeAction=button(M.backupCloseVolt(),this::requestClose);
            // Passwords and staged approval are deliberately never carried through recreation.
            // Let an already approved operation finish, then require a fresh process/models.
            handler.post(new Runnable(){public void run(){if(isDestroyed())return;
                if(OPERATIONS.get()>0){busy=true;closeAction.setEnabled(false);status.setText(M.backupFinishingPrevious());handler.postDelayed(this,200);}
                else done(M.backupCloseAndReopen());}});
            return;
        }
        if(getIntent().getBooleanExtra("recovery",false)){
            DataMaintenance.enter();DataMaintenance.preparedTransfer(new Intent(this,BackupActivity.class));heading(M.backupRecoveringTitle());status=paragraph(M.backupRecoveringKeepOpen());
            progress=new ProgressBar(this);content.addView(progress);busy=true;
            submit(()->{try{AppDataBackup.recover(this);RideBackup.recover(this);show(()->done(M.backupRecovered()));}
                catch(Exception failure){show(()->done(M.backupRecoveryFailed()));}});
            return;
        }
        if(!DataMaintenance.active()){
            DataMaintenance.enter();DataMaintenance.preparedTransfer(new Intent(this,BackupActivity.class));heading(M.backupSessionEndedTitle());status=paragraph("");progress=new ProgressBar(this);content.addView(progress);
            done(M.backupReopenToContinue());return;
        }
        String preparationError=getIntent().getStringExtra("preparation_error");
        if(preparationError!=null){heading(M.backupPausedTitle());status=paragraph("");progress=new ProgressBar(this);content.addView(progress);done(preparationError);return;}
        if(restore)restoreForm();else backupForm();
    }

    // ---- Building blocks ---------------------------------------------------------------------

    private void reset(CharSequence title){content.removeAllViews();boxes.clear();words.clear();hints.clear();refreshScope=destinations=null;counts=null;replaceAll=null;password=repeat=null;action=closeAction=null;heading(title);}
    private void heading(CharSequence title){TextView text=ui.text(title,28,true);words.put(text,title);if(Build.VERSION.SDK_INT>=28)text.setAccessibilityHeading(true);content.addView(text);}
    private TextView paragraph(CharSequence message){return paragraph(content,message);}
    private TextView paragraph(LinearLayout parent,CharSequence message){TextView text=ui.caption(message==null?null:message.toString());words.put(text,message);text.setFilters(new InputFilter[]{keep(text)});text.setTextSize(TypedValue.COMPLEX_UNIT_SP,15*textScale);text.setPadding(0,ui.dp(12),0,ui.dp(12));parent.addView(text);return text;}
    private TextView button(CharSequence label,Runnable callback){TextView button=ui.button(String.valueOf(label),callback);button.setContentDescription(label);words.put(button,label);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=ui.dp(12);content.addView(button,p);return button;}
    private void footer(CharSequence label,String tag,Runnable callback){
        status=paragraph("");status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        progress=new ProgressBar(this);progress.setVisibility(View.GONE);content.addView(progress);
        action=button(label,callback);action.setTag(tag);
        closeAction=button(M.backupCloseVolt(),this::requestClose);closeAction.setTag("backup-close");
    }
    private void label(TextView button,CharSequence text){button.setText(text);button.setContentDescription(text);words.put(button,text);}
    private EditText passwordField(CharSequence hint){
        EditText field=new EditText(this);hints.put(field,hint);field.setHint(hint);field.setTextColor(ui.text);field.setHintTextColor(ui.muted);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP,16*textScale);
        field.setSingleLine(true);field.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setSaveEnabled(false);field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        field.setOnFocusChangeListener((v,focused)->{if(focused)revealPassword();});
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(128)});field.setMinHeight(ui.dp(56));content.addView(field);return field;
    }
    /** Run after focus/viewport layout so the whole editor, not only its cursor, is reachable. */
    private void revealPassword(){
        scroll.post(()->{
            View focused=scroll.findFocus();
            if((focused==password||focused==repeat)&&focused!=null&&focused.isAttachedToWindow())
                focused.requestRectangleOnScreen(new android.graphics.Rect(0,-ui.dp(12),focused.getWidth(),focused.getHeight()+ui.dp(12)),true);
        });
    }
    /** Disabled (empty) categories read as unavailable, not as unchecked choices. */
    private ColorStateList enabledColors(int color){return new ColorStateList(new int[][]{{-android.R.attr.state_enabled},{}},new int[]{ui.muted,color});}
    /** One 48 dp checkbox row; its count is spoken with the label. */
    private CheckBox checkRow(LinearLayout list,String id,CharSequence label,String detail,boolean enabled,boolean checked){
        if(list.getChildCount()>0)ui.divider(list);
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setMinimumHeight(ui.dp(48));
        CheckBox box=new CheckBox(this);box.setText(label);words.put(box,label);box.setTextColor(enabledColors(ui.text));box.setTextSize(TypedValue.COMPLEX_UNIT_SP,15*textScale);
        box.setButtonTintList(enabledColors(ui.accent));box.setMinHeight(ui.dp(48));box.setEnabled(enabled);box.setChecked(enabled&&checked);
        box.setContentDescription(detail==null||detail.isEmpty()?label:M.backupCategorySpoken(label,detail.replace(" · ",", ")));if(id!=null)box.setTag("backup-category-"+id);
        row.addView(box,new LinearLayout.LayoutParams(0,-2,1));
        if(detail!=null&&!detail.isEmpty()){TextView value=ui.caption(detail);value.setTextSize(TypedValue.COMPLEX_UNIT_SP,13*textScale);value.setGravity(Gravity.END);
            value.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams vp=new LinearLayout.LayoutParams(-2,-2);vp.leftMargin=ui.dp(8);row.addView(value,vp);}
        list.addView(row);return box;
    }
    private LinearLayout list(){
        LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);
        list.setBackground(ui.shape(ui.surface,18,0));list.setPadding(ui.dp(14),ui.dp(2),ui.dp(14),ui.dp(2));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=ui.dp(14);content.addView(list,lp);return list;
    }
    /** Select all mirrors the category boxes and checks or clears every enabled one; frozen by createBackup, it keeps what it shows (BackupSelectAll). */
    private void selectAll(LinearLayout list,Runnable changed){
        CheckBox all=checkRow(list,null,M.backupSelectAll(),null,true,true);all.setTag("backup-select-all");
        boolean[] syncing={false};
        Runnable sync=()->{boolean[] enabled=new boolean[boxes.size()],checked=new boolean[boxes.size()];int i=0;
            for(CheckBox b:boxes.values()){enabled[i]=b.isEnabled();checked[i++]=b.isChecked();}
            syncing[0]=true;all.setChecked(BackupSelectAll.checked(all.isChecked(),!all.isEnabled(),enabled,checked));syncing[0]=false;if(changed!=null)changed.run();};
        all.setOnCheckedChangeListener((v,on)->{if(syncing[0])return;for(CheckBox b:boxes.values())if(b.isEnabled())b.setChecked(on);sync.run();});
        for(CheckBox b:boxes.values())b.setOnCheckedChangeListener((v,on)->sync.run());
        sync.run();
        refreshScope=sync;
    }
    private String size(long bytes){return android.text.format.Formatter.formatShortFileSize(this,bytes);}

    // ---- Back up -----------------------------------------------------------------------------

    private void backupForm(){
        reset(M.backupBackUpTitle());status=paragraph(M.backupCounting());progress=new ProgressBar(this);content.addView(progress);busy=true;
        submit(()->{try{Map<String,long[]> counted=AppDataBackup.inventory(this);show(()->{busy=false;inventory=counted;backupCategories();});}
            catch(Exception failure){show(()->done(M.backupInventoryUnreadable()));}});
    }
    /** A destination's name in its spoken labels: the layout file's display name, else the scooter's name as the backup has it.
     * The engine keeps its English "Layout file" in d.name (data, BackupArchive); backup_file_layout shows it. A backup's scooter
     * may have that source id under another name (BackupProfiles takes any id), so the name must match too. */
    private static CharSequence destinationName(AppDataBackup.Destination d){return d.source.equals(BackupArchive.LAYOUT_SOURCE)&&d.name.equals(M.backupFileLayout().english())?M.backupFileLayout():d.name;}
    private boolean hasItems(String id){return inventory.get(id)[0]>0||inventory.get(id)[1]>0;}
    private void backupCategories(){
        reset(M.backupBackUpTitle());
        TextView scope=paragraph("");scope.setTag("backup-scope");
        scope.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout list=list();LinearLayout categories=new LinearLayout(this);categories.setOrientation(LinearLayout.VERTICAL);
        for(String id:AppDataBackup.CATEGORIES){
            long items=inventory.get(id)[0],bytes=inventory.get(id)[1];String detail;
            if(!hasItems(id))detail=T.backupDetailNone();
            else if(id.equals("scooters"))detail=T.backupDetailPrivate(items);
            else if(id.equals("dashboards")||id.equals("scooter-preferences"))detail=BackupArchive.plural((int)items,"scooter");
            else if(id.equals("preferences"))detail="";
            else if(id.equals("history"))detail=size(bytes);
            else detail=items+" · "+size(bytes);
            CheckBox box=checkRow(categories,id,AppDataBackup.labelMsg(id),detail,hasItems(id),preselect==null||preselect.equals(id));boxes.put(id,box);
            if(id.equals(BackupRegistry.SCOOTERS)&&hasItems(id))warn(box);
        }
        TextView estimate=ui.caption("");estimate.setTextSize(TypedValue.COMPLEX_UNIT_SP,13*textScale);estimate.setPadding(0,ui.dp(10),0,0);
        selectAll(list,()->{long total=0;int missing=0;for(Map.Entry<String,CheckBox> e:boxes.entrySet()){
                if(e.getValue().isChecked())total+=inventory.get(e.getKey())[1];else if(hasItems(e.getKey()))missing++;
            }
            scope.setText(missing==0?M.backupScopeFull()
                    :M.backupScopeSelected(missing,missing));
            estimate.setText(M.backupEstimate(size(total)));});
        list.addView(categories);content.addView(estimate);
        paragraph(M.backupNotIncludedPreferences());
        password=passwordField(M.backupNewPassword());repeat=passwordField(M.backupRepeatPassword());paragraph(M.backupPasswordUnrecoverable());
        footer(M.backupCreate(),"backup-create",this::createBackup);
    }
    /** Every category with data checked makes a complete backup, empty categories included. */
    private Set<String> selected(){
        Set<String> result=new LinkedHashSet<>();boolean every=true;
        for(Map.Entry<String,CheckBox> e:boxes.entrySet()){if(e.getValue().isChecked())result.add(e.getKey());else if(e.getValue().isEnabled())every=false;}
        if(every&&!result.isEmpty())result.addAll(boxes.keySet());
        return result;
    }
    private void createBackup(){
        if(busy||waitingForPicker||terminal)return;
        Set<String> categories=selected();
        if(categories.isEmpty()){status.setText(M.backupChooseWhat());return;}
        exportedComplete=categories.containsAll(AppDataBackup.CATEGORIES);
        char[] secret=new char[password.length()];password.getText().getChars(0,password.length(),secret,0);
        if(secret.length<8){Arrays.fill(secret,'\0');password.setError(M.backupPasswordShort());return;}
        char[] repeated=new char[repeat.length()];repeat.getText().getChars(0,repeat.length(),repeated,0);
        boolean same=Arrays.equals(secret,repeated);Arrays.fill(repeated,'\0');
        if(!same){Arrays.fill(secret,'\0');repeat.setError(M.backupPasswordsDiffer());return;}
        password.setText("");repeat.setText("");hideKeyboard();
        for(CheckBox box:boxes.values())box.setEnabled(false);
        View all=content.findViewWithTag("backup-select-all");if(all!=null)all.setEnabled(false);
        setBusy(M.backupEncrypting());
        submit(()->{
            File file=null;
            try{file=File.createTempFile("volt-encrypted-",".voltbackup",getCacheDir());
                try(OutputStream out=new FileOutputStream(file)){AppDataBackup.write(this,out,secret,categories);}
                File ready=file;runOnUiThread(()->{if(isDestroyed()||isFinishing()){ready.delete();return;}exported=ready;busy=false;progress.setVisibility(View.GONE);getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);launchSave();});
            }catch(Exception failure){if(file!=null)file.delete();show(()->{for(Map.Entry<String,CheckBox> e:boxes.entrySet())e.getValue().setEnabled(hasItems(e.getKey()));if(all!=null)all.setEnabled(true);retry(M.backupFailed());});}
            finally{Arrays.fill(secret,'\0');}
        });
    }
    private void launchSave(){
        if(busy||waitingForPicker||terminal||isDestroyed()||isFinishing())return;
        if(exported==null||!exported.isFile()){retry(M.backupUnavailable());return;}
        status.setText("");if(closeAction!=null)closeAction.setEnabled(true);
        label(action,M.backupSave());action.setTag("backup-save");action.setEnabled(true);action.setOnClickListener(v->launchSave());
        Intent picker=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(AppDataBackup.MIME_TYPE);
        picker.putExtra(Intent.EXTRA_TITLE,(exportedComplete?"volt-full-":"volt-selected-")+new java.text.SimpleDateFormat("yyyy-MM-dd-HHmm",Locale.ROOT).format(new Date())+AppDataBackup.FILE_EXTENSION);
        try{waitingForPicker=true;startActivityForResult(picker,SAVE_BACKUP);}catch(Exception missing){waitingForPicker=false;status.setText(M.backupPickerFailedSave(T.backupSaveShort()));}
    }

    // ---- Restore -----------------------------------------------------------------------------

    private void restoreForm(){
        reset(M.backupRestoreTitle());paragraph(M.backupRestoreIntro());paragraph(M.backupNotIncluded());
        footer(M.backupChooseFile(),"restore-choose",this::choose);
    }
    private void choose(){
        if(busy||waitingForPicker||terminal)return;
        Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try{waitingForPicker=true;startActivityForResult(picker,PICK_BACKUP);}catch(Exception missing){waitingForPicker=false;status.setText(M.backupPickerFailed());}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);waitingForPicker=false;
        if(terminal||isDestroyed()||isFinishing())return;
        if(result!=RESULT_OK||data==null||data.getData()==null){status.setText(M.backupNoFile());return;}
        Uri uri=data.getData();
        if(request==PICK_BACKUP){picked=uri;setBusy(M.backupCheckingFile());
            submit(()->{int kind;
                try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException();byte[] head=new byte[64];int n=0;for(int r;n<head.length&&(r=in.read(head,n,head.length-n))>0;)n+=r;kind=AppDataBackup.sniff(head,n);}
                catch(Exception failure){show(()->refuseFile(M.backupFileUnopenable()));return;}
                if(kind==AppDataBackup.UNSUPPORTED)show(()->refuseFile(M.backupChooseVoltFile()));
                else if(kind==AppDataBackup.ENCRYPTED)show(this::askPassword);
                else stageFile(null);});
        }else if(request==SAVE_BACKUP){setBusy(M.backupSaving());
            File ready=exported;
            submit(()->{try{saveExport(ready,getContentResolver().openOutputStream(uri,"wt"));}catch(Exception failure){show(()->retry(M.backupSaveFailed()));}});
        }
    }
    /** On the worker. Providers can fail during close(): the prepared copy and Save stay until every resource closed. */
    private void saveExport(File ready,OutputStream destination){
        try(OutputStream out=destination;InputStream in=new FileInputStream(ready)){
            if(out==null)throw new IOException();byte[] buffer=new byte[65536];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count);out.flush();
        }catch(Exception failure){show(()->retry(M.backupSaveFailed()));return;}
        ready.delete();show(()->{exported=null;done(M.backupSaved());});
    }
    /** A file refused before any password: a password step left from an earlier file would blame the password. */
    private void refuseFile(CharSequence message){if(password!=null)restoreForm();retry(message);}
    private void askPassword(){
        retry("");if(password==null){password=passwordField(M.backupPasswordHint());content.removeView(password);content.addView(password,content.indexOfChild(status));}
        password.requestFocus();label(action,M.backupOpen());action.setTag("restore-open");action.setOnClickListener(v->openEncrypted());
        // A wrong or damaged file must not strand the rider on it: another file can be chosen here.
        if(content.findViewWithTag("restore-choose-again")==null){TextView again=ui.secondaryButton(T.backupChooseAnother(),this::choose);again.setTag("restore-choose-again");again.setContentDescription(M.backupChooseAnother());
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=ui.dp(12);content.addView(again,content.indexOfChild(closeAction),p);}
    }
    private void openEncrypted(){
        if(busy||terminal||picked==null)return;
        char[] secret=new char[password.length()];password.getText().getChars(0,password.length(),secret,0);
        if(secret.length<8){Arrays.fill(secret,'\0');password.setError(M.backupPasswordShort());return;}
        password.setText("");hideKeyboard();setBusy(M.backupCheckingBackup());
        submit(()->stageFile(secret));
    }
    /** On the worker. */
    private void stageFile(char[] secret){
        try(InputStream in=getContentResolver().openInputStream(picked)){
            if(in==null)throw new IOException();
            AppDataBackup.PreparedRestore checked;
            // A file the engine refuses before any password shows the engine's reason (B10); with a password it is "wrong password".
            try{checked=AppDataBackup.stage(this,in,secret);}catch(BackupException refused){if(secret!=null)throw refused;show(()->refuseFile(refused.msg()));return;}
            boolean data=AppDataBackup.hasData(this);
            runOnUiThread(()->{if(isDestroyed()||isFinishing()){submit(()->discardQuietly(checked));return;}staged=checked;hasData=data;busy=false;getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);restoreCategories();});
        }catch(Exception failure){show(()->{if(secret!=null)retry(M.backupWrongPassword());else refuseFile(message(failure));});}
        finally{if(secret!=null)Arrays.fill(secret,'\0');}
    }
    private void restoreCategories(){
        reset(M.backupRestoreTitle());
        String date=staged.createdAtMs>0?DisplayTime.dateTime(this,getSharedPreferences("app_settings",MODE_PRIVATE),staged.createdAtMs):null;
        paragraph(staged.format.equals("owner")?M.backupFileOwner()
                :staged.format.equals("layout")?M.backupFileLayout():date==null?(staged.format.equals("rides")?M.backupFileRides():staged.complete?M.backupFileFull():M.backupFileSelected())
                :staged.format.equals("rides")?M.backupFileRidesFrom(date):staged.complete?M.backupFileFullFrom(date):M.backupFileSelectedFrom(date));
        if(staged.format.equals("owner")){
            TextView warning=paragraph(M.backupOwnerOnlyDetail());
            warning.setTag("restore-owner-only");
        }else if(!staged.complete)paragraph(M.backupPartialFile());
        LinearLayout list=list();LinearLayout categories=new LinearLayout(this);categories.setOrientation(LinearLayout.VERTICAL);
        boolean preselected=preselect!=null&&staged.categories.containsKey(preselect);
        counts=staged.categories;
        for(Map.Entry<String,Integer> category:staged.categories.entrySet()){
            String id=category.getKey();int count=category.getValue();String detail;
            if(count==0)detail=T.backupDetailNone();
            else if(id.equals("rides"))detail=BackupArchive.plural(count,"ride");
            else if(id.equals("charges"))detail=BackupArchive.plural(count,"charge");
            else if(id.equals("preferences"))detail=BackupArchive.plural(count,"setting");
            else detail=BackupArchive.plural(count,"scooter");
            boxes.put(id,checkRow(categories,id,AppDataBackup.labelMsg(id),detail,count>0,!preselected||preselect.equals(id)));
        }
        if(boxes.size()>1)selectAll(list,null);
        list.addView(categories);
        if(staged.complete){
            replaceAll=new Switch(this);replaceAll.setText(M.backupReplaceAll());replaceAll.setTextColor(ui.text);replaceAll.setTextSize(TypedValue.COMPLEX_UNIT_SP,15*textScale);
            replaceAll.setMinHeight(ui.dp(48));ui.styleSwitch(replaceAll);replaceAll.setTag("restore-replace-all");
            replaceAll.setOnCheckedChangeListener((v,on)->{for(Map.Entry<String,CheckBox> e:boxes.entrySet()){boolean has=staged.categories.get(e.getKey())>0;if(on||!has)e.getValue().setChecked(on);e.getValue().setEnabled(!on&&has);}});
            LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,-2);sp.topMargin=ui.dp(12);content.addView(replaceAll,sp);
            // A cleared or new phone gets an exact copy by default; a phone with data merges.
            replaceAll.setChecked(!hasData&&!preselected);
        }
        footer(M.backupNext(),"restore-next",this::next);
    }
    private boolean replacing(){return replaceAll!=null&&replaceAll.isChecked();}
    private void next(){
        if(busy||staged==null)return;
        Set<String> categories=new LinkedHashSet<>();
        if(replacing())categories.addAll(AppDataBackup.CATEGORIES);
        else for(Map.Entry<String,CheckBox> e:boxes.entrySet())if(e.getValue().isChecked())categories.add(e.getKey());
        if(categories.isEmpty()){status.setText(M.backupChooseWhatRestore());return;}
        if(replacing()){review(categories,true,Collections.<String,String>emptyMap());return;}
        AppDataBackup.PreparedRestore current=staged;setBusy(M.backupCheckingScooters());
        submit(()->{try{List<AppDataBackup.Destination> needed=AppDataBackup.destinations(this,current,categories);Map<String,String> local=AppDataBackup.localScooters(this);
                show(()->{if(needed.isEmpty())review(categories,false,Collections.<String,String>emptyMap());else scooters(categories,needed,local);});}
            catch(Exception failure){show(()->retry(refusal(failure)));}});
    }
    private void scooters(Set<String> categories,List<AppDataBackup.Destination> needed,Map<String,String> local){
        busy=false;getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        reset(M.backupScootersTitle());
        LinkedHashMap<String,Spinner> choices=new LinkedHashMap<>();LinkedHashMap<String,List<String>> targets=new LinkedHashMap<>();boolean onlySkip=true;
        destinations=()->scooters(categories,needed,local);
        Runnable updateReady=()->{
            boolean resolved=true;
            for(Map.Entry<String,Spinner> e:choices.entrySet())resolved&=targets.get(e.getKey()).get(e.getValue().getSelectedItemPosition())!=null;
            if(action!=null)action.setEnabled(resolved);
        };
        for(AppDataBackup.Destination d:needed){
            boolean accountChoice=!d.accountMatches.isEmpty();
            paragraph(accountChoice?M.backupDestinationSetup():d.source.equals(BackupArchive.LAYOUT_SOURCE)?M.backupDestinationLayout():M.backupDestinationData(d.name));
            if(accountChoice)paragraph(M.backupOwnerChoice()).setTag("restore-owner-choice");
            List<String> ids=new ArrayList<>(),names=new ArrayList<>();
            if(accountChoice){ids.add(null);names.add(T.backupChooseScooterItem());}
            for(Map.Entry<String,String> scooter:local.entrySet())if(!accountChoice||d.accountMatches.contains(scooter.getKey())){ids.add(scooter.getKey());names.add(scooter.getValue());}
            if(d.addable){ids.add(d.source);names.add(accountChoice?T.backupAddAnotherScooter():T.backupAddFromBackup());}
            ids.add(AppDataBackup.SKIP);names.add(T.backupDontRestore());
            onlySkip&=ids.size()==1;
            Spinner choice=new Spinner(this);ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,names);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);choice.setAdapter(adapter);choice.setMinimumHeight(ui.dp(56));
            choice.setContentDescription(M.backupDestinationFor(d.name));choice.setTag("restore-destination");
            choice.setSelection(accountChoice?0:d.suggested==null?ids.size()-1:Math.max(0,ids.indexOf(d.suggested)));
            // A description replaces the spoken value, so it carries the current choice too.
            final CharSequence source=destinationName(d);
            choice.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
                public void onItemSelected(AdapterView<?> parent,View view,int position,long id){parent.setContentDescription(M.backupDestinationForChoice(source,names.get(position)));updateReady.run();}
                public void onNothingSelected(AdapterView<?> parent){}});
            choice.setContentDescription(M.backupDestinationForChoice(source,names.get(choice.getSelectedItemPosition())));
            content.addView(choice,new LinearLayout.LayoutParams(-1,-2));choices.put(d.source,choice);targets.put(d.source,ids);
        }
        if(onlySkip)paragraph(M.backupScooterFirst());
        footer(M.backupReviewButton(),"restore-review",()->{
            Map<String,String> chosen=new LinkedHashMap<>();
            for(Map.Entry<String,Spinner> e:choices.entrySet()){
                String target=targets.get(e.getKey()).get(e.getValue().getSelectedItemPosition());
                if(target==null){status.setText(M.backupChooseScooterFirst());return;}chosen.put(e.getKey(),target);
            }
            review(categories,false,chosen);
        });
        updateReady.run();
    }
    private void review(Set<String> categories,boolean replace,Map<String,String> chosen){review(categories,replace,chosen,null);}
    /** A non-null note means saved data changed after the last review: the new one replaces it, or the choices start over. */
    private void review(Set<String> categories,boolean replace,Map<String,String> chosen,String note){
        AppDataBackup.PreparedRestore current=staged;setBusy(M.backupComparing());
        submit(()->{try{AppDataBackup.Review checked=AppDataBackup.review(this,current,categories,replace,chosen);show(()->showReview(checked,()->review(categories,replace,chosen,CHANGED),note));}
            catch(Exception failure){show(()->{if(note!=null)restoreCategories();retry(refusal(failure));});}});
    }
    private void showReview(AppDataBackup.Review review,Runnable again,CharSequence note){
        busy=false;getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        reset(M.backupReviewTitle());
        LinearLayout list=list();list.setTag("restore-preview");list.setPadding(ui.dp(14),ui.dp(6),ui.dp(14),ui.dp(6));
        for(CharSequence line:review.messages)paragraph(list,line).setTextColor(ui.text);
        if(staged!=null&&staged.format.equals("owner"))paragraph("Scooter access only. This file contains no rides or other app data.").setTag("restore-owner-only");
        paragraph(M.backupClosesWhenDone());
        footer(review.replace?M.backupReplaceEverything():M.backupRestoreButton(),"restore-apply",()->applyRestore(review,again));
        if(note!=null)status.setText(note);
    }
    private void applyRestore(AppDataBackup.Review review,Runnable again){
        if(staged==null||busy)return;AppDataBackup.PreparedRestore approved=staged;setBusy(M.backupRestoring());
        submit(()->{try{AppDataBackup.apply(this,review);show(()->{staged=null;done(M.backupRestored());});}
            catch(Exception failure){
                BackupException.Code code=failure instanceof BackupException?((BackupException)failure).code():BackupException.Code.FAILED;
                // Refused before any change: the reviewed file is still staged. A changed phone gets a fresh review, never the stale approval.
                if(code==BackupException.Code.CHANGED){show(again);return;}
                if(code==BackupException.Code.NO_SPACE){Msg text=((BackupException)failure).msg();show(()->retry(text));return;}
                discardQuietly(approved);
                show(()->{staged=null;done(M.commonRestoreFailed());});
            }});
    }

    // ---- Lifecycle ---------------------------------------------------------------------------

    private static String message(Exception failure){String text=failure.getMessage();return failure instanceof IOException&&text!=null&&text.length()<=80?text:T.backupFileUnreadable();}
    private void hideKeyboard(){View focus=getCurrentFocus();if(focus!=null)((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(focus.getWindowToken(),0);}
    private static void submit(Runnable work){OPERATIONS.incrementAndGet();WORKER.execute(()->{try{work.run();}finally{OPERATIONS.decrementAndGet();}});}
    private void show(Runnable update){runOnUiThread(()->{if(!isDestroyed()&&!isFinishing())update.run();});}
    private void discardQuietly(AppDataBackup.PreparedRestore value){try{AppDataBackup.discard(this,value);}catch(IOException ignored){/* Startup recovery removes abandoned staging; never remove a transaction here. */}}
    // RideBackupValidation reflects setBusy(String); keep this overload.
    private void setBusy(String message){setBusy((CharSequence)message);}
    private void setBusy(CharSequence message){busy=true;status.setText(message);progress.setVisibility(View.VISIBLE);if(action!=null)action.setEnabled(false);if(closeAction!=null)closeAction.setEnabled(false);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
    private void retry(String message){busy=false;status.setText(message);progress.setVisibility(View.GONE);if(action!=null)action.setEnabled(true);if(closeAction!=null)closeAction.setEnabled(true);getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
    private void done(String message){busy=false;terminal=true;status.setText(message);progress.setVisibility(View.GONE);if(action!=null)action.setVisibility(View.GONE);if(closeAction==null)closeAction=button(M.backupCloseVolt(),this::requestClose);closeAction.setEnabled(true);getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
    private void requestClose(){
        // Close Volt is disabled while busy; Back lands here too and simply waits.
        if(busy||OPERATIONS.get()>0)return;
        if(terminal){closeProcess();return;}
        confirmation=new AlertDialog.Builder(this).setTitle(M.backupCloseTitle()).setMessage(M.backupCloseNothingChanged())
                .setNegativeButton(M.backupStayHere(),null).setPositiveButton(M.commonClose(),(d,n)->closeProcess()).show();
    }
    private void closeProcess(){
        if(busy||OPERATIONS.get()>0)return;
        busy=true;
        if(exported!=null)exported.delete();
        AppDataBackup.PreparedRestore old=staged;staged=null;
        submit(()->{if(old!=null)discardQuietly(old);runOnUiThread(()->{finishAffinity();android.os.Process.killProcess(android.os.Process.myPid());});});
    }
    @Override public void onBackPressed(){requestClose();}
    @Override protected void onDestroy(){
        if(Build.VERSION.SDK_INT>=33&&backCallback!=null)getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        handler.removeCallbacksAndMessages(null);if(confirmation!=null)confirmation.dismiss();
        AppDataBackup.PreparedRestore old=staged;staged=null;File ready=exported;exported=null;
        // Queue cleanup behind any write already using these files.
        submit(()->{if(old!=null)discardQuietly(old);if(ready!=null)ready.delete();});super.onDestroy();
    }

    // ---- Language ----------------------------------------------------------------------------

    @Override protected void attachBaseContext(android.content.Context base){super.attachBaseContext(AppLanguage.wrap(base));}
    private android.os.LocaleList locales(){
        try{return AppLanguage.resources(this).getConfiguration().getLocales();}catch(RuntimeException unavailable){return null;}
    }
    /** The manifest keeps this Activity through a language change (configChanges locale and layoutDirection): recreating
     * it would end the session (onCreate's interrupted path) and drop a staged restore, a typed password or a running job.
     * A rotation, fold or font change keeps the locale list, so nothing is relabelled then. */
    @Override public void onConfigurationChanged(android.content.res.Configuration next){
        super.onConfigurationChanged(next);
        android.os.LocaleList now=locales();
        if(now==null||now.equals(shownLocales))return;
        shownLocales=now;
        // VoltApp installs the new catalog in its own onConfigurationChanged; relabel after every callback has run.
        handler.post(this::relabel);
    }
    /** Shows the current page again in the new language, in place: every view, its state and the session stay. */
    private void relabel(){
        if(isDestroyed()||isFinishing()||content==null)return;
        if(destinations!=null){
            // The Scooters page lists its choices as text: build it again with each choice kept. A running review
            // (Comparing...) ends on the Review page or back here, so wait for it.
            if(busy){handler.postDelayed(this::relabel,200);return;}
            List<Integer> chosen=new ArrayList<>();
            for(int i=0;i<content.getChildCount();i++)if(content.getChildAt(i) instanceof Spinner)chosen.add(((Spinner)content.getChildAt(i)).getSelectedItemPosition());
            CharSequence note=status==null?null:words.get(status);int y=scroll.getScrollY();
            destinations.run();
            for(int i=0,s=0;i<content.getChildCount();i++)if(content.getChildAt(i) instanceof Spinner&&s<chosen.size())((Spinner)content.getChildAt(i)).setSelection(chosen.get(s++));
            if(note!=null&&status!=null)status.setText(note);
            scroll.post(()->scroll.scrollTo(0,y));
        }
        for(Map.Entry<TextView,CharSequence> e:words.entrySet())e.setValue(fresh(e.getValue()));
        for(Map.Entry<TextView,CharSequence> e:new ArrayList<>(words.entrySet())){TextView view=e.getKey();if(view!=null)view.setText(e.getValue());}
        for(Map.Entry<EditText,CharSequence> e:new ArrayList<>(hints.entrySet())){EditText field=e.getKey();if(field!=null&&e.getValue()!=null)field.setHint(e.getValue());}
        for(EditText field:new ArrayList<>(hints.keySet())){if(field==null)continue;CharSequence shown=field.getError();CharSequence error=shown==null?null:shownErrors.get(shown.toString());if(error!=null)field.setError(error);}
        noteErrors();
        relabel(content);
        if(replaceAll!=null)replaceAll.setText(M.backupReplaceAll());
        relabelCounts();
        if(refreshScope!=null)refreshScope.run();
        if(confirmation!=null&&confirmation.isShowing()){confirmation.dismiss();confirmation=null;requestClose();}
    }
    private void relabel(View view){
        // Choose another file (askPassword) is found by its spoken label, a Msg compared with a Msg.
        if(view instanceof TextView&&M.backupChooseAnother().equals(view.getContentDescription()))((TextView)view).setText(M.backupChooseAnother());
        if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++)relabel(group.getChildAt(i));}
    }
    /** The errors a password field can show (createBackup, openEncrypted), by their text in the current language,
     * and message()'s unreadable-file text, which the status line is given rendered (a String). */
    private void noteErrors(){
        Map<String,CharSequence> errors=new HashMap<>();
        for(CharSequence error:new CharSequence[]{M.backupPasswordShort(),M.backupPasswordsDiffer(),M.backupFileUnreadable()})errors.put(error.toString(),error);
        shownErrors=errors;
    }
    /** Records each text a paragraph is given (status.setText included) in words, as given (a Msg stays a Msg): TextView
     * runs its filters on every setText with the text it was passed, before rendering it, and this one changes nothing. */
    private InputFilter keep(TextView view){return (source,start,end,dest,dstart,dend)->{words.put(view,source);return null;};}
    /** The category details backupCategories and restoreCategories render in the language of that moment: the
     * catalog's ("None", the private scooter count) and the Readings history size (Formatter, in this Activity's
     * locale). The counts from BackupArchive.plural are English in every language. The rides and charges rows'
     * "12 · 3.4 MB" keep the count and separator as shown (backupCategories wrote the count's digits, then " · ") and
     * take the size formatted again. A row built again by checkRow, off screen, gives the shown row its detail and
     * spoken label. */
    private void relabelCounts(){
        LinearLayout scratch=new LinearLayout(this);
        for(Map.Entry<String,CheckBox> e:boxes.entrySet()){
            String id=e.getKey(),detail=null;
            if(counts!=null){Integer count=counts.get(id);if(count!=null&&count==0)detail=T.backupDetailNone();}
            else if(inventory!=null&&inventory.containsKey(id)){
                if(!hasItems(id))detail=T.backupDetailNone();
                else if(id.equals(BackupRegistry.SCOOTERS))detail=T.backupDetailPrivate(inventory.get(id)[0]);
                else if(id.equals(BackupRegistry.HISTORY))detail=size(inventory.get(id)[1]);
                else if(id.equals(BackupRegistry.RIDES)||id.equals(BackupRegistry.CHARGES)){
                    View shown=((android.view.ViewGroup)e.getValue().getParent()).getChildAt(1);int cut=Long.toString(inventory.get(id)[0]).length()+3;
                    if(shown instanceof TextView&&((TextView)shown).length()>cut)detail=((TextView)shown).getText().toString().substring(0,cut)+size(inventory.get(id)[1]);
                }
            }
            if(detail==null)continue;
            scratch.removeAllViews();CheckBox fresh=checkRow(scratch,null,AppDataBackup.labelMsg(id),detail,true,false);words.remove(fresh);
            CheckBox box=e.getValue();View value=((android.view.ViewGroup)box.getParent()).getChildAt(1);
            View freshValue=((android.view.ViewGroup)fresh.getParent()).getChildAt(1);
            if(value instanceof TextView&&freshValue instanceof TextView)((TextView)value).setText(((TextView)freshValue).getText());
            box.setContentDescription(fresh.getContentDescription());
        }
    }
    /** A recorded text again for the language now installed. Most are a Msg, which renders again by itself; these hold
     * words rendered when given: message()'s String (T.backupFileUnreadable, found by its text in noteErrors), the
     * Save hint's button name (T.backupSaveShort) and the Restore page's file line, whose date restoreCategories
     * formats once, in the locale of that moment, and which takes it formatted again now. Anything else stays. */
    private CharSequence fresh(CharSequence given){
        if(given instanceof String){CharSequence known=shownErrors.get(given);return known==null?given:known;}
        if(!(given instanceof Msg))return given;
        int id=((Msg)given).id();
        Msg hint=M.backupPickerFailedSave(M.backupSaveShort());if(id==hint.id())return hint;
        if(staged==null||staged.createdAtMs<=0)return given;
        String date=DisplayTime.dateTime(this,getSharedPreferences("app_settings",MODE_PRIVATE),staged.createdAtMs);
        return id==M.backupFileRidesFrom(date).id()?M.backupFileRidesFrom(date):id==M.backupFileFullFrom(date).id()?M.backupFileFullFrom(date)
                :id==M.backupFileSelectedFrom(date).id()?M.backupFileSelectedFrom(date):given;
    }
    /** The Scooters row on Back up: its count says the file holds the owner keys. */
    private void warn(CheckBox box){View value=((android.view.ViewGroup)box.getParent()).getChildAt(1);if(value instanceof TextView)((TextView)value).setTextColor(ui.danger);}
    /** What a refused file or check shows: the backup engine's reason (B10's msg()), else the legacy text. */
    private static CharSequence refusal(Exception failure){return failure instanceof BackupException?((BackupException)failure).msg():message(failure);}
    private void done(CharSequence message){done((String)null);status.setText(message);}
    private void retry(CharSequence message){retry((String)null);status.setText(message);}
    /** A review again after saved data changed (BackupException CHANGED): note is what the new review shows. */
    private void review(Set<String> categories,boolean replace,Map<String,String> chosen,CharSequence note){
        AppDataBackup.PreparedRestore current=staged;setBusy(M.backupComparing());
        submit(()->{try{AppDataBackup.Review checked=AppDataBackup.review(this,current,categories,replace,chosen);show(()->showReview(checked,()->review(categories,replace,chosen,CHANGED),note));}
            catch(Exception failure){show(()->{if(note!=null)restoreCategories();retry(refusal(failure));});}});
    }
}
