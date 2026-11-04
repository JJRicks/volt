package com.jjricks.volt;

import android.content.Intent;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * More › Developer options › Compatibility: the scooter's identity, the read-only check, its results, any running or
 * unfinished try or older guided test (review, Restore, Keep, Dismiss) and the report preview, which includes the
 * journalled tries and tests. Trying settings lives in More › Experimental controls (its Help verify group replaced
 * this panel's Try a setting). Nothing leaves the phone unless the owner sends the email that Email to Volt (under
 * Run again) drafts, or taps Share or Save in the preview; then the report's tries count as reported.
 */
final class CompatibilityPanel {
    private final ScreenHost host;
    private final Ui ui;
    private final LinearLayout root;
    private final boolean recoveryOnly;
    private String shown="";
    private TextView progressText;
    private View progressFill,progressRest;
    private final Set<String> expanded=new HashSet<>();
    private boolean preview,showJson;
    private CompatibilityReport report;
    /** The result groups' ids: the expanded set and the refresh key hold these; groupLabel gives each heading. */
    private static final String READS="Reads";
    private static final String FIELDS="Fields";
    private static final String CONTROLS="Controls";

    CompatibilityPanel(ScreenHost host,LinearLayout destination,boolean recoveryOnly){
        this.host=host;ui=host.ui();this.recoveryOnly=recoveryOnly;
        root=new LinearLayout(host.activity());root.setOrientation(LinearLayout.VERTICAL);
        destination.addView(root,new LinearLayout.LayoutParams(-1,-2));
        expanded.add(READS);expanded.add(CONTROLS);
        refresh();
    }

    private BleController ble(){ScooterService s=host.service();return s==null?null:s.ble;}

    void refresh(){
        BleController ble=ble();
        if(ble==null){rebuild("none",null);return;}
        DiscoveryCoordinator check=ble.discovery();
        boolean ready=ble.ready(),running=ble.discovering();
        if(running||check==null){preview=false;report=null;}
        // The preview shows the journal as it is: a restore or try finished meanwhile rebuilds it (acknowledging still checks).
        if(preview&&report!=null&&ble.reportOutdated(report)){CompatibilityReport fresh=ble.compatibilityReport(AppVersion.name(host.activity()));if(fresh!=null)report=fresh;}
        DeviceDescriptor d=ble.descriptor();
        String key=(ready?"ready":"offline")+"|"+running+"|"+(check==null?"":Integer.toHexString(System.identityHashCode(check)))+"|"+preview+"|"+showJson+"|"+expanded
            +"|"+ble.modelUnidentified()+"|"+ModelRegistry.modelLine(d)+"|"+d.pidSource()+"|"+d.region+"|"+d.firmware.meter+"|"+ble.resolution()+"|"+testKey(ble)
            +"|"+(report==null?"":Integer.toHexString(System.identityHashCode(report)));
        rebuild(key,ble);
        if(running&&check!=null)progress(check);
    }

    private void rebuild(String key,BleController ble){
        if(key.equals(shown))return;
        shown=key;root.removeAllViews();progressText=null;
        if(ble==null){root.addView(note(T.compatServiceStarting()));return;}
        DiscoveryCoordinator check=ble.discovery();boolean running=ble.discovering();
        // A pre-upgrade or interrupted test must remain recoverable even when developer options
        // are hidden. This route never offers a new check, test or report.
        if(recoveryOnly){
            if(!ble.ready())root.addView(ui.button(T.compatConnect(),host::connect));
            tests(ble);
            if(ble.guidedTest()==null&&ble.unresolvedTests().isEmpty()&&ble.unresolvedTestsElsewhere()==0
                    &&ble.orphanedTests().isEmpty()&&ble.testRecordsHealthy())root.addView(note(T.compatNoUnfinishedTests()));
            return;
        }
        identity(ble);
        if(running&&check!=null)running();
        else if(ble.ready()&&!ble.modelUnidentified()&&!ble.testing())root.addView(ui.button(check==null?T.compatRunCheck():T.compatRunAgain(),()->{ble.startDiscovery();toastIfIdle(ble);host.redraw();}));
        // A finished check is one tap from Volt's inbox: the offer's "Which scooter is this?" sheet, then an email draft.
        if(!running&&check!=null){TextView email=ui.secondaryButton(T.compatEmail(),()->emailNow(ble));email.setTag("compatibility-email");root.addView(email);}
        if(!running)tests(ble);
        if(!running&&check!=null){
            results(ble,check);
            if(preview)preview(ble);
            else root.addView(ui.secondaryButton(T.compatPreviewReport(),()->{report=ble.compatibilityReport(AppVersion.name(host.activity()));if(report==null){host.toast(T.compatNoFinishedCheck());return;}preview=true;showJson=false;refresh();}));
        }
        TextView foot=ui.caption(T.compatFooter());foot.setPadding(ui.dp(4),ui.dp(8),ui.dp(4),ui.dp(12));root.addView(foot);
    }
    private void toastIfIdle(BleController ble){if(!ble.discovering())host.toast(ble.displayText());}

    // ---- Identity -------------------------------------------------------------------------

    private void identity(BleController ble){
        LinearLayout card=ui.card();root.addView(card);
        if(!ble.ready()){
            TextView t=ui.text(T.compatConnectToCheck(),15,false);card.addView(t);
            card.addView(ui.button(T.compatConnect(),host::connect));
            return;
        }
        DeviceDescriptor d=ble.descriptor();ModelRegistry.Resolution r=ble.resolution();
        String model=ModelRegistry.modelLineText(d).toString();
        TextView title=ui.text(model.isEmpty()?T.compatModelNotCaptured():model,17,true);card.addView(title);
        if(d.simulated)card.addView(muted(T.compatSimulated()));
        line(card,T.compatModelId(),d.pidSource()==DeviceDescriptor.PidSource.ADVERTISED?T.compatModelIdAdvertised():d.pidSource()==DeviceDescriptor.PidSource.SERIAL?T.compatModelIdSerial():T.compatModelIdNotCaptured());
        if(r.conflict)line(card,T.compatCheck(),T.compatModelIdMismatch());
        line(card,T.compatRegion(),d.region==null?T.compatRegionUnknown():T.compatRegionFromSerial(d.region));
        line(card,T.compatFirmware(),d.firmware.meter==null?T.compatFirmwareNotRead():d.firmware.meter);
        line(card,T.compatRules(),layer(r.layer));
        String said=ModelRegistry.ownerModel(d);if(!said.isEmpty())line(card,T.compatYourAnswer(),said);
        if(ble.modelUnidentified()){
            card.addView(muted(T.compatIdentifyNote()));
            card.addView(ui.button(T.compatIdentify(),()->{ble.identifyModel();host.toast(ble.displayText());host.redraw();}));
        }
    }
    static String layer(ModelRegistry.Layer layer){
        switch(layer){
            case UT5_MAX: return T.compatLayerUt5Max();
            case OEM_GROUP: return T.compatLayerOem();
            case SHARED: return T.compatLayerShared();
            default: return T.compatLayerNone();
        }
    }

    // ---- Running and unfinished tries and tests (recovery) -------------------------------------

    /** Everything the test cards show, so the panel rebuilds only when one of them changes. */
    private String testKey(BleController ble){
        StringBuilder k=new StringBuilder();GuidedTest t=ble.guidedTest();
        if(t!=null)k.append(t.phase()).append(t.outcomes().size()).append(t.note()).append(t.baseline());
        for(TestJournal.Entry e:ble.unresolvedTests()){GuidedTest.Review r=ble.reviewTest(e.testId);k.append('|').append(e.testId).append(e.phase).append(r==null?"":r.state+"/"+r.current+"/"+r.restorable+"/"+decision(ble,e.isTry(),e.feature).reason);}
        for(TestJournal.Entry e:ble.orphanedTests())k.append("|orphan").append(e.testId);
        k.append('|').append(ble.unresolvedTestsElsewhere()).append(ble.testParked()).append(ble.testRecordsHealthy());
        return k.toString();
    }
    /** A try is reviewed and restored under the try rule; an older guided test under the guided-test table. */
    private static CapabilityResolver.Decision decision(BleController ble,boolean tried,String feature){return tried?ble.tryDecision(feature):ble.testDecision(feature);}
    private void tests(BleController ble){
        GuidedTest t=ble.guidedTest();
        if(t!=null)liveTest(ble,t);
        List<TestJournal.Entry> open=ble.unresolvedTests();
        for(TestJournal.Entry e:open)unfinished(ble,e);
        int elsewhere=ble.unresolvedTestsElsewhere();
        if(elsewhere>0)root.addView(note(elsewhere==1?T.compatTestElsewhereOne():T.compatTestsElsewhere(elsewhere,elsewhere)));
        for(TestJournal.Entry e:ble.orphanedTests())orphaned(ble,e);
        // An unreadable journal may hide an unfinished test, so say so whether or not a scooter is connected.
        if(!ble.testRecordsHealthy())root.addView(note(T.compatTestRecordsUnavailable()));
    }
    private void liveTest(BleController ble,GuidedTest t){
        LinearLayout card=ui.card();root.addView(card);
        String label=ScooterCommands.find(t.feature).label().toString();CapabilityResolver.Decision d=decision(ble,t.keeps(),t.feature);
        card.addView(ui.text(t.keeps()?T.compatTryTitle(label):T.compatTestTitle(label),16,true));
        Integer base=t.baseline();String from=base==null?"":d.label(base),to=d.label(t.target);
        TextView status;
        switch(t.phase()){
            // A try approves itself once its baseline matches; no new guided test starts any more.
            case PRECHECK: case BASELINE: card.addView(muted(T.compatReadingCurrent()));card.addView(ui.secondaryButton(T.commonCancel(),()->{ble.cancelTest();refresh();}));return;
            case TARGET_PENDING: case TARGET_AWAIT: status=muted(T.compatChangingTo(d.labelText(t.target)));status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);card.addView(status);return;
            case RESTORE_PENDING: case RESTORE_AWAIT: status=muted(t.verifying()?T.compatCheckingUnchanged():T.compatSettingBack(base==null?from:d.labelText(base)));
                status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);card.addView(status);return;
            case OBSERVE:
                // question() takes the English label id on purpose (B9, raw values): it compares it with "On"/"Off" and "mph".
                card.addView(ui.text(question(t.feature,to),15,false));
                card.addView(ui.button(T.compatYes(),()->{ble.testEffect(true);refresh();}));
                card.addView(ui.secondaryButton(T.compatNo(),()->{ble.testEffect(false);refresh();}));
                card.addView(ui.secondaryButton(T.compatSkip(),()->{ble.testEffect(null);refresh();}));
                return;
            default:
                if(!t.note().isEmpty())card.addView(muted(t.noteMsg()));
                if(!t.nothingWritten()){
                    card.addView(muted(join(t.outcomeLabels())));
                    String r=t.restorationStatus();boolean kept=t.keeps()&&r.equals(TestJournal.DECLINED);
                    // A try that left a value Volt doesn't offer here (kick-start below 3 km/h) has no Set back.
                    String keptText=ble.experimentBaseline(t.feature)!=null?T.compatKeptSetBack():T.compatKept();
                    TextView restored=ui.text(kept?keptText:r.equals("confirmed")?T.compatRestorationConfirmed():T.compatRestorationOutstanding(),13.5f,true);
                    restored.setTextColor(r.equals("confirmed")||kept?ui.ok:ui.text);restored.setPadding(0,ui.dp(6),0,0);card.addView(restored);
                }
                card.addView(ui.secondaryButton(T.commonClose(),()->{ble.closeTest();refresh();}));
        }
    }
    private void unfinished(BleController ble,TestJournal.Entry e){
        LinearLayout card=ui.card();root.addView(card);
        String label=ScooterCommands.find(e.feature).label().toString();CapabilityResolver.Decision d=decision(ble,e.isTry(),e.feature);
        card.addView(ui.text(e.isTry()?T.compatUnfinishedTryTitle(label):T.compatUnfinishedTestTitle(label),16,true));
        line(card,e.isTry()?T.compatBeforeTry():T.compatBeforeTest(),d.labelText(e.baseline).toString());line(card,e.isTry()?T.compatValueTried():T.compatTestValue(),d.labelText(e.target).toString());
        if(!e.outcomes.isEmpty()){List<String> texts=new java.util.ArrayList<>();for(String o:e.outcomes)texts.add(GuidedTest.Outcome.label(o));card.addView(muted(join(texts)));}
        GuidedTest.Review r=ble.reviewTest(e.testId);
        if(r==null)return;
        card.addView(ui.text(r.message,14,false));
        boolean restore=(r.state==GuidedTest.ReviewState.RESTORE_NEEDED||r.state==GuidedTest.ReviewState.CHANGED_OUTSIDE)&&r.restorable;
        if(restore&&d.reason==CapabilityResolver.Reason.PARKED_UNCONFIRMED)card.addView(ui.toggleRow(T.compatParked(),T.compatParkedNoMotion(),ble.testParked(),true,ble::setTestParked));
        else if(restore&&!d.available())card.addView(muted(d.reasonText()));
        switch(r.state){
            case FIRMWARE_CHANGED: card.addView(ui.secondaryButton(T.compatContinueAnyway(),()->{ble.confirmTestFirmware(e.testId);refresh();}));break;
            case ALREADY_RESTORED: card.addView(ui.button(T.compatMarkRestored(),()->{ble.markTestRestored(e.testId);refresh();}));break;
            case RESTORE_NEEDED:
                if(restore)addRestore(card,ble,e,d,T.compatRestoreTo(d.labelText(e.baseline)));
                // A try may have worked: the owner can keep the value tried, as it would have stayed.
                if(e.isTry())card.addView(ui.secondaryButton(unbroken(T.compatKeepValue(d.labelText(e.target))),()->{ble.keepTest(e.testId);refresh();}));
                break;
            case CHANGED_OUTSIDE:
                if(r.current!=null)card.addView(ui.secondaryButton(unbroken(T.compatKeepValue(d.labelText(r.current))),()->{ble.keepTest(e.testId);refresh();}));
                if(restore)addRestore(card,ble,e,d,T.compatSetValue(d.labelText(e.baseline)));
                break;
            default: break;
        }
        TextView dismiss=ui.secondaryButton(T.compatDismissLeave(),()->{ble.dismissTest(e.testId);refresh();});
        dismiss.setContentDescription(e.isTry()?T.compatDismissTryDescription(label):T.compatDismissTestDescription(label));card.addView(dismiss);
    }
    /** An unfinished test whose scooter profile was removed: nothing can be checked or set back, only dismissed. */
    private void orphaned(BleController ble,TestJournal.Entry e){
        LinearLayout card=ui.card();root.addView(card);
        String label=ScooterCommands.find(e.feature).label().toString();
        card.addView(ui.text(T.compatUnfinishedTestTitle(label),16,true));
        if(!e.outcomes.isEmpty()){List<String> texts=new java.util.ArrayList<>();for(String o:e.outcomes)texts.add(GuidedTest.Outcome.label(o));card.addView(muted(join(texts)));}
        card.addView(ui.text(T.compatOrphanBody(),14,false));
        TextView dismiss=ui.secondaryButton(T.compatDismiss(),()->{ble.dismissTest(e.testId);refresh();});
        dismiss.setContentDescription(T.compatDismissOrphanDescription(label));card.addView(dismiss);
    }
    private void addRestore(LinearLayout card,BleController ble,TestJournal.Entry e,CapabilityResolver.Decision d,String text){
        TextView b=ui.button(unbroken(text),()->{ble.restoreTest(e.testId);if(!ble.testing())host.toast(ble.displayText());refresh();});b.setEnabled(d.available());card.addView(b);
    }
    static String question(String feature,String value){
        switch(feature){
            case "display_units": return value.contains("mph")?T.compatQuestionUnitsMph():T.compatQuestionUnitsKmh();
            case "headlight": return "On".equals(value)?T.compatQuestionTailLightOn():"Off".equals(value)?T.compatQuestionTailLightOff():T.compatQuestionTailLight(value.toLowerCase(java.util.Locale.ROOT));
            case "turn_sound": return "On".equals(value)?T.compatQuestionTurnSoundOn():"Off".equals(value)?T.compatQuestionTurnSoundOff():T.compatQuestionTurnSound(value.toLowerCase(java.util.Locale.ROOT));
            case "auto_sensor": return "On".equals(value)?T.compatQuestionAutoLightsOn():"Off".equals(value)?T.compatQuestionAutoLightsOff():T.compatQuestionAutoLights(value.toLowerCase(java.util.Locale.ROOT));
            case "range_algorithm": return T.compatQuestionRange();
            default: return T.compatQuestionDefault();
        }
    }
    /** Button labels: a word joiner keeps "km/h" on one line when large text wraps the label. */
    static String unbroken(String text){return text.replace("km/h","km/\u2060h");}
    private static String join(List<String> parts){StringBuilder b=new StringBuilder();for(String p:parts)b.append(b.length()==0?"":" · ").append(p);return b.toString();}

    // ---- Running --------------------------------------------------------------------------

    private void running(){
        LinearLayout card=ui.card();root.addView(card);
        progressText=ui.text("",15,false);progressText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);card.addView(progressText);
        LinearLayout bar=new LinearLayout(host.activity());bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackground(ui.shape(ui.raised,100,0));bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        progressFill=new View(host.activity());progressFill.setBackground(ui.shape(ui.accent,100,0));
        progressRest=new View(host.activity());
        bar.addView(progressFill,new LinearLayout.LayoutParams(0,-1,0));bar.addView(progressRest,new LinearLayout.LayoutParams(0,-1,1));
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,ui.dp(6));bp.topMargin=ui.dp(12);bp.bottomMargin=ui.dp(4);card.addView(bar,bp);
        root.addView(ui.secondaryButton(T.compatStop(),()->{BleController ble=ble();if(ble!=null)ble.stopDiscovery();refresh();}));
    }
    private void progress(DiscoveryCoordinator check){
        if(progressText==null)return;
        int total=DiscoveryCoordinator.ORDER.length,done=check.resolved();
        String text=done>=total?T.compatListening():T.compatReadingProgress(Fmt.integer(Math.min(total,done+1)),Fmt.integer(total));
        if(!text.contentEquals(progressText.getText()))progressText.setText(text);
        float fraction=Math.min(1f,done/(float)total);
        // Steps in place; no animation, so reduced motion needs nothing extra.
        ((LinearLayout.LayoutParams)progressFill.getLayoutParams()).weight=fraction;((LinearLayout.LayoutParams)progressRest.getLayoutParams()).weight=1f-fraction;
        progressFill.requestLayout();
    }

    // ---- Results --------------------------------------------------------------------------

    private void results(BleController ble,DiscoveryCoordinator check){
        if(check.interrupted())root.addView(note(T.compatCheckInterrupted()));
        LinearLayout reads=group(READS);
        if(reads!=null){
            for(DiscoveryCoordinator.Read r:check.reads())row(reads,T.compatReadTitle(CompatibilityReport.hex(r.cmd),readName(r.cmd)),readValue(r),r.outcome==DiscoveryCoordinator.Outcome.READ_SUPPORTED?ui.ok:ui.muted);
            StringBuilder seen=new StringBuilder();
            for(DiscoveryCoordinator.Read p:check.passive())if(p.outcome==DiscoveryCoordinator.Outcome.READ_SUPPORTED)seen.append(seen.length()==0?"":", ").append(CompatibilityReport.hex(p.cmd));
            row(reads,T.compatReports(),seen.length()==0?T.compatReportsNone():seen.toString(),ui.muted);
        }
        LinearLayout fields=group(FIELDS);
        if(fields!=null){
            StatusFields s=ble.discoveryStatus();ModelRegistry.Layer layer=ModelRegistry.resolve(ble.descriptor()).layer;
            if(!s.accepted())row(fields,T.compatReadStatus(),T.compatStatusNotReceived(),ui.muted);
            else for(int o=0;o<=CompatibilityReport.LAST_OFFSET;o++){
                if(!CompatibilityReport.valueOffset(o)&&!CompatibilityReport.presenceOffset(o))continue;
                StatusFields.Field f=s.field(o);
                String value=!f.present?T.compatFieldsValueAbsent():CompatibilityReport.valueOffset(o)?Integer.toString(f.value):T.compatFieldsValuePresent();
                row(fields,CompatibilityLabels.field(o,layer),value,f.present?ui.text:ui.muted);
            }
            if(s.accepted())row(fields,T.compatStatusRead(),age(s),ui.muted);
        }
        LinearLayout controls=group(CONTROLS);
        if(controls!=null){
            List<CapabilityResolver.Decision> ds=ble.discoveryDecisions();
            for(CapabilityResolver.Decision d:ds){
                String label=d.feature.equals("clock_sync")?T.compatControlClock():ScooterCommands.find(d.feature).label;
                String value=d.available()?T.compatControlAvailable(evidence(d.evidence)):T.compatControlUnavailable(d.reasonText(),evidence(d.evidence));
                row(controls,label,value,d.available()?ui.ok:ui.muted);
            }
        }
    }
    private String age(StatusFields s){
        long ms=android.os.SystemClock.elapsedRealtime()-s.observedMs();
        if(ms<0)return T.compatAgeJustNow();long minutes=ms/60000;
        return minutes<1?T.compatAgeUnderMinute():minutes==1?T.compatAgeOneMinute():T.compatAgeMinutes(minutes,minutes);
    }
    static String readName(int cmd){
        switch(cmd){
            case 0x74: return T.compatReadSerial(); case 0x70: return T.compatReadStatus(); case 0x73: return T.compatReadFirmware(); case 0x72: return T.compatReadBattery();
            case 0x76: return T.compatReadRideHistory(); case 0x7b: return T.compatReadFaultHistory(); case 0x71: return T.compatReadDriving();
            case 0x75: return T.compatReadBatteryId(); case 0x79: return T.compatReadBatterySigns(); default: return T.compatReadOther();
        }
    }
    static String readValue(DiscoveryCoordinator.Read r){
        // Deferred (B9): the supported read stays English; the font (Saira) has no U+2713 for the catalog.
        String late=r.late?" (late)":"";
        switch(r.outcome){
            case READ_SUPPORTED: return "✓ "+r.length+" bytes"+late;
            case REJECTED: return T.compatReadRejected(r.status);
            case MALFORMED: return T.compatReadTooShort(r.length,r.length);
            case EMPTY_REPLY: return T.compatReadEmpty();
            case NO_RESPONSE: return T.compatReadNoResponse();
            case INTERRUPTED: return T.compatReadInterrupted();
            default: return T.compatReadWaiting();
        }
    }
    static String evidence(CapabilityResolver.Evidence e){
        switch(e){
            case VOLT_VERIFIED: return T.compatEvidenceVoltVerified();
            case VOLT_LAB: return T.compatEvidenceLab();
            case OEM_MAPPED: return T.compatEvidenceOem();
            case SHARED_CONTRACT: return T.compatEvidenceShared();
            case LOCAL_TEST: return T.compatEvidenceLocal();
            default: return T.compatEvidenceNone();
        }
    }

    // ---- Preview --------------------------------------------------------------------------

    private void preview(BleController ble){
        if(report==null){preview=false;return;}
        TextView heading=ui.sectionLabel(T.compatReportPreview());heading.setText(T.compatReportPreview());heading.setAllCaps(true);root.addView(heading);
        LinearLayout card=ui.card();root.addView(card);
        card.addView(muted(T.compatReportPrivacy()));
        TextView summary=ui.text(showJson?report.json():report.summary(),12,false);summary.setTypeface(Typeface.MONOSPACE);summary.setTextIsSelectable(true);
        // The report, and anything shared or emailed from it, stays English; a translated panel says so.
        if(!Fmt.legacy())card.addView(muted(T.compatReportInEnglish()));
        summary.setPadding(0,ui.dp(10),0,0);card.addView(summary);
        TextView toggle=ui.text(showJson?T.compatShowSummary():T.compatShowJson(),13.5f,false);toggle.setTypeface(ui.medium());toggle.setTextColor(ui.accent);
        toggle.setGravity(Gravity.CENTER);toggle.setMinHeight(ui.dp(48));toggle.setFocusable(true);toggle.setContentDescription(showJson?T.compatShowSummaryDescription():T.compatShowJsonDescription());
        toggle.setOnClickListener(v->{showJson=!showJson;refresh();});root.addView(toggle,new LinearLayout.LayoutParams(-1,-2));
        final CompatibilityReport shared=report;
        root.addView(ui.button(T.compatShare(),()->share(shared)));
        // Android confirms neither a share nor an email, so those mark the tries at the handoff; a saved file only once written.
        root.addView(ui.secondaryButton(T.compatSaveFile(),()->host.saveReport(shared)));
        root.addView(ui.secondaryButton(T.compatClosePreview(),()->{preview=false;report=null;refresh();}));
    }
    private void emailNow(BleController ble){
        CompatibilityReport r=ble.compatibilityReport(AppVersion.name(host.activity()));
        if(r==null){host.toast(T.compatNoFinishedCheck());return;}
        host.emailReport(r);
    }
    /** Android share sheet, only from the owner's tap in the preview. */
    private void share(CompatibilityReport r){
        Intent send=new Intent(Intent.ACTION_SEND);send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT,"Volt compatibility report");
        send.putExtra(Intent.EXTRA_TEXT,r.summary()+"\n"+r.json());
        try{host.activity().startActivity(Intent.createChooser(send,T.compatShareChooser()));BleController ble=ble();if(ble!=null)ble.markReported(r);}
        catch(Exception e){host.toast(T.compatShareNone());}
    }

    // ---- Small views ----------------------------------------------------------------------

    /** Collapsible group: a heading that toggles it; null when collapsed. */
    private LinearLayout group(String name){
        boolean open=expanded.contains(name);
        LinearLayout header=new LinearLayout(host.activity());header.setOrientation(LinearLayout.HORIZONTAL);header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(ui.dp(48));
        TextView label=ui.sectionLabel(groupLabel(name));label.setText(groupLabel(name));label.setAllCaps(true);
        header.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        TextView state=ui.text(open?T.compatGroupHide():T.compatGroupShow(),12.5f,false);state.setTextColor(ui.accent);state.setPadding(ui.dp(8),ui.dp(14),ui.dp(6),ui.dp(6));
        state.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);header.addView(state);
        header.setFocusable(true);header.setClickable(true);
        header.setContentDescription(open?T.compatGroupExpanded(groupLabel(name)):T.compatGroupCollapsed(groupLabel(name)));
        header.setOnClickListener(v->{if(!expanded.remove(name))expanded.add(name);refresh();});
        root.addView(header,new LinearLayout.LayoutParams(-1,-2));
        if(!open)return null;
        LinearLayout g=ui.groupCard();root.addView(g);return g;
    }
    /** A result group's heading, from its id. */
    private static String groupLabel(String id){
        switch(id){
            case READS: return T.compatGroupReads();
            case FIELDS: return T.compatGroupFields();
            case CONTROLS: return T.compatGroupControls();
            default: return id;
        }
    }
    private void row(LinearLayout group,String title,String value,int valueColor){
        if(group.getChildCount()>0)ui.divider(group);
        LinearLayout line=new LinearLayout(host.activity());line.setOrientation(LinearLayout.HORIZONTAL);line.setGravity(Gravity.CENTER_VERTICAL);
        line.setMinimumHeight(ui.dp(44));line.setPadding(ui.dp(2),ui.dp(8),0,ui.dp(8));
        TextView t=ui.text(title,14,false);line.addView(t,new LinearLayout.LayoutParams(0,-2,1));
        TextView v=ui.text(value,13,false);v.setTextColor(valueColor);v.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);v.setMaxWidth(ui.dp(170));
        LinearLayout.LayoutParams vp=new LinearLayout.LayoutParams(-2,-2);vp.leftMargin=ui.dp(10);line.addView(v,vp);
        line.setFocusable(true);line.setContentDescription(T.compatRowDescription(title,value));
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        group.addView(line,new LinearLayout.LayoutParams(-1,-2));
    }
    private void line(LinearLayout card,String title,String value){
        TextView t=ui.text(T.compatIdentityLine(title,value),13,false);t.setTextColor(ui.muted);t.setPadding(0,ui.dp(6),0,0);card.addView(t);
    }
    private TextView muted(CharSequence text){TextView t=ui.text(text,12.5f,false);t.setTextColor(ui.muted);t.setPadding(0,ui.dp(6),0,ui.dp(4));return t;}
    private TextView note(String text){TextView t=ui.caption(text);t.setPadding(ui.dp(4),ui.dp(6),ui.dp(4),ui.dp(6));return t;}
}
