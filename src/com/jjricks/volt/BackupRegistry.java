package com.jjricks.volt;

import java.io.IOException;
import java.util.*;

/**
 * The single classification of every preference key and storage root Volt writes. A backup
 * category restore applies a key only through this table; a new key, namespace or file
 * folder must be added here (and to docs/app-storage-inventory.md) in the same change.
 * BackupRegistryTest scans the source and fails on anything unclassified.
 */
final class BackupRegistry {
    /** Written to every v2 manifest; newer registries are refused by older readers. */
    static final int VERSION = 1;
    static final String SCOOTERS = "scooters", DASHBOARDS = "dashboards", PREFERENCES = "preferences", RIDES = "rides",
            CHARGES = "charges", HISTORY = "history", SCOOTER_PREFERENCES = "scooter-preferences";
    /** Manifest and UI order. IDs are stable file format values; labels are UI copy. */
    static final String[] CATEGORIES = {SCOOTERS, DASHBOARDS, PREFERENCES, RIDES, CHARGES, HISTORY, SCOOTER_PREFERENCES};
    static final String[] LABELS = {"Scooters & setup keys", "Dashboards", "App preferences", "Rides", "Charges", "Readings history", "Scooter preferences"};
    /** Namespaces a backup may read or replace, in the legacy full-backup order. */
    static final String[] NAMESPACES = AppDataArchive.PREFERENCES;
    /** Namespaces owned by this installation. Backups never read, clear or replace them. */
    static final String[] PRIVATE_NAMESPACES = {"pending_documents", "owner_capture_cleanup"};

    static final class Rule {
        final String namespace, key, category, reason;
        final boolean prefix, perProfile, slot, replaceOnly;
        final Class<?> type;
        private Rule(String namespace, String key, boolean prefix, Class<?> type, String category, String reason, boolean perProfile, boolean slot, boolean replaceOnly) {
            this.namespace=namespace;this.key=key;this.prefix=prefix;this.type=type;this.category=category;this.reason=reason;this.perProfile=perProfile;this.slot=slot;this.replaceOnly=replaceOnly;
        }
        boolean excluded(){return category==null;}
        boolean matches(String name){return prefix?name.startsWith(key)&&name.length()>key.length():name.equals(key);}
        /** Profile ID carried in a per-scooter key, or null when the key has none. */
        String profile(String name) {
            if(!perProfile||!matches(name))return null;String rest=name.substring(key.length());
            if(slot){int split=rest.lastIndexOf('_');if(split<=0||!rest.substring(split+1).matches("[0-9]"))return null;rest=rest.substring(0,split);}
            return rest.isEmpty()?null:rest;
        }
        /** The same key for another scooter: the profile part is replaced, a slot suffix kept. */
        String rename(String name, String profile) {
            String source=profile(name);if(source==null)throw new IllegalArgumentException(name);
            return key+profile+name.substring(key.length()+source.length());
        }
    }

    private static final List<Rule> RULES = new ArrayList<>();
    private static final String DEVICE = "Device, window or boot-session state", PENDING = "Pending operation of this installation",
            PERMISSION = "Tied to this installation's Android permission prompts", WIZARD = "Setup wizard in progress on this installation";
    static {
        String app = "app_settings";
        // phone_placement is retired (no longer read or written). It stays registered so older backups that hold it restore it.
        for (String key : "theme units temperature_unit time_format stats_density phone_placement map_tile_url map_tile_attribution map_tile_attribution_url stats_favourite_order ride_chart_key last_ride_label".split(" ")) add(app, key, String.class, PREFERENCES);
        for (String key : "haptics_enabled auto_connect background_connection keep_screen_awake history_enabled phone_gps phone_motion map_basemap_enabled map_basemap_explained developer_options range_learned_intro_seen ride_auto_stop report_diagnostics".split(" ")) add(app, key, Boolean.class, PREFERENCES);
        add(app, "text_scale", Float.class, PREFERENCES);
        // Automatic ride copies (RideAutoExport): each type's choice travels; the folder grant, the waiting rides and
        // the last problem belong to this phone.
        add(app, "ride_auto_export_route_csv", Boolean.class, PREFERENCES);
        add(app, "ride_auto_export_json", Boolean.class, PREFERENCES);
        exclude(app, "ride_auto_export_folder", String.class, PERMISSION);
        exclude(app, "ride_auto_export_pending", String.class, PENDING);
        exclude(app, "ride_auto_export_problem", String.class, PENDING);
        // Ride details' Export ride data sheet (RideExportFiles): the remembered formats travel; the last folder is only
        // the picker's starting place on this phone.
        add(app, "ride_export_choice", String.class, PREFERENCES);
        exclude(app, "ride_export_folder_hint", String.class, PERMISSION);
        add(app, "update_check_consented", Boolean.class, PREFERENCES);
        // Live charging (ChargeLiveHold): the switch and the lock screen choice travel; a Stop for one charge doesn't.
        add(app, "charge_live_enabled", Boolean.class, PREFERENCES);
        add(app, "charge_live_lock_detail", String.class, PREFERENCES);
        // A Stop on the live charging card belongs to one charge on this phone: never restored elsewhere.
        exclude(app, "charge_live_stopped", String.class, DEVICE);
        // The last live card's Live Update verdict (Developer options) describes this phone's Android only.
        exclude(app, "charge_live_update_last", String.class, DEVICE);
        add(app, "poll_interval_ms", Integer.class, PREFERENCES);add(app, "stats_chart_window", Integer.class, PREFERENCES);
        for (String key : "stats_favourites stats_chart_favourites ride_chart_series charge_chart_series".split(" ")) add(app, key, Set.class, PREFERENCES);
        prefix(app, "stats_group_", Boolean.class, PREFERENCES, false);prefix(app, "stats_chart_group_", Boolean.class, PREFERENCES, false);
        add(app, "dashboard_columns", Integer.class, DASHBOARDS);
        prefix(app, "report_invite_", String.class, PREFERENCES, false);
        // Per scooter, so a merge onto another scooter carries the owner's answer with it.
        prefix(app, "connect_report_", String.class, SCOOTER_PREFERENCES, true);
        prefix(app, "dashboard_v1_", String.class, DASHBOARDS, true);
        RULES.add(new Rule(app, "dashboard_slot_v1_", true, String.class, DASHBOARDS, null, true, true, false));
        prefix(app, "dashboard_empty_example_v1_", Boolean.class, DASHBOARDS, true);
        // Riding mode's layout and its power bar's observed scale go with the dashboards (RidingConfig).
        prefix(app, "riding_layout_v1_", String.class, DASHBOARDS, true);
        prefix(app, "riding_power_scale_v1_", String.class, DASHBOARDS, true);
        add(app, "ride_comparison", Set.class, RIDES);add(app, "ride_selected", String.class, RIDES);
        prefix(app, "range_source_", String.class, SCOOTER_PREFERENCES, true);
        prefix(app, "range_follow_mode_", Boolean.class, SCOOTER_PREFERENCES, true);
        prefix(app, "charge_charger_", String.class, SCOOTER_PREFERENCES, true);
        // Displayed one-shot intentions only; restoration never sends charging commands.
        prefix(app, "charge_schedule_owned_", String.class, SCOOTER_PREFERENCES, true);
        // Battery alert choices travel with the scooter; the current charge's progress stays on this phone.
        prefix(app, "charge_alerts_", String.class, SCOOTER_PREFERENCES, true);
        // The Scooter tab's menu layout (ScooterMenuLayout) is a scooter preference, not a dashboard: Dashboards feeds the layout export and counts.
        prefix(app, "scooter_menu_v1_", String.class, SCOOTER_PREFERENCES, true);
        RULES.add(new Rule(app, "charge_alert_state_", true, String.class, null, "Charge in progress on this installation; a restored copy could silence the next alert", true, false, false));
        // Maintenance reminders travel with the scooter (MaintenanceStore); a merge onto another scooter drops the raw
        // odometer baseline (BackupArchive.perScooter). Which due reminders this phone already notified stays here:
        // a restored copy could repeat or silence the next reminder.
        prefix(app, "maintenance_v1_", String.class, SCOOTER_PREFERENCES, true);
        RULES.add(new Rule(app, "maintenance_seen_", true, String.class, null, DEVICE, true, false, false));
        RULES.add(new Rule(app, "dashboard_restore_undo_v1_", true, String.class, null, "Short-lived undo; removed when that scooter's dashboard is restored", true, false, false));
        exclude(app, "notification_prompted", Boolean.class, PERMISSION);
        exclude(app, "owner_import_applied", String.class, PENDING);
        for (String key : "last_page more_section scooter_section stats_section more_wide_section_scroll stats_query stats_chart_query".split(" ")) exclude(app, key, String.class, DEVICE);
        for (String key : "more_wide_menu_scroll more_away_boot stats_away_boot".split(" ")) exclude(app, key, Integer.class, DEVICE);
        exclude(app, "more_away_elapsed", Long.class, DEVICE);exclude(app, "stats_away_elapsed", Long.class, DEVICE);
        exclude(app, "dashboard_editing", Boolean.class, DEVICE);
        exclude(app, ScooterMenuLayout.EDITING, Boolean.class, DEVICE);
        // The language choice belongs to this phone; on Android 13 and later the system owns it (AppLanguage).
        exclude(app, "app_language", String.class, DEVICE);exclude(app, "app_language_migrated", Boolean.class, DEVICE);
        add("profiles", "items", String.class, SCOOTERS);add("profiles", "active", String.class, SCOOTERS);add("profiles", "legacy_migrated", Boolean.class, SCOOTERS);
        // The pre-profile setup is read only by a one-time migration: exact restores keep it, merges never do.
        for (String key : "identity mac serial last_connected_mac".split(" ")) RULES.add(new Rule("scooter", key, false, String.class, SCOOTERS, null, false, false, true));
        RULES.add(new Rule("scooter", "role", false, Integer.class, SCOOTERS, null, false, false, true));
        // Dormant since 1.1.0 and data only: controls always need fresh authenticated state.
        RULES.add(new Rule("control_evidence", "", true, String.class, SCOOTER_PREFERENCES, null, true, false, false));
        add("onboarding", OnboardingKeys.DONE, Boolean.class, SCOOTERS);
        for (String key : "rerun import_failed connect_requested connect_tried".split(" ")) exclude("onboarding", key, Boolean.class, WIZARD);
        exclude("onboarding", "nearby_asked", Boolean.class, PERMISSION);
        exclude("onboarding", "step", Integer.class, WIZARD);
        for (String key : "path profile_id created_profile_id pending_name".split(" ")) exclude("onboarding", key, String.class, WIZARD);
        RULES.add(new Rule("onboarding", "tick_", true, Boolean.class, null, WIZARD, false, false, false));
    }
    /** Avoid an Android dependency on OnboardingScreen for this pure table. */
    static final class OnboardingKeys { static final String DONE = "onboarding_done"; private OnboardingKeys() { } }
    private static void add(String namespace, String key, Class<?> type, String category){RULES.add(new Rule(namespace,key,false,type,category,null,false,false,false));}
    private static void prefix(String namespace, String key, Class<?> type, String category, boolean perProfile){RULES.add(new Rule(namespace,key,true,type,category,null,perProfile,false,false));}
    private static void exclude(String namespace, String key, Class<?> type, String reason){RULES.add(new Rule(namespace,key,false,type,null,reason,false,false,false));}
    private BackupRegistry() { }

    static List<Rule> rules(){return Collections.unmodifiableList(RULES);}
    /** The rule for a stored key, or null for an unassigned key (exported only by complete backups). */
    static Rule rule(String namespace, String key) {
        Rule found=null;
        for(Rule rule:RULES)if(rule.namespace.equals(namespace)&&rule.matches(key)){if(found!=null)throw new IllegalStateException("Two backup rules match "+namespace+"/"+key);found=rule;}
        return found;
    }
    static String category(String namespace, String key){Rule rule=rule(namespace,key);return rule==null?null:rule.category;}
    static boolean known(String category){return category!=null&&Arrays.asList(CATEGORIES).contains(category);}
    static String label(String category){int i=Arrays.asList(CATEGORIES).indexOf(category);return i<0?category:LABELS[i];}
    /** LABELS as the screen shows them, in the current language (the English is LABELS'). An unknown id is shown as it is. */
    static Msg labelMsg(String category){int i=Arrays.asList(CATEGORIES).indexOf(category);return i<0?Msg.verbatim(category):Msg.of(LABEL_IDS[i]);}
    private static final int[] LABEL_IDS = {T.BACKUP_ENGINE_CATEGORY_SCOOTERS, T.BACKUP_ENGINE_CATEGORY_DASHBOARDS, T.BACKUP_ENGINE_CATEGORY_PREFERENCES, T.BACKUP_ENGINE_CATEGORY_RIDES,
            T.BACKUP_ENGINE_CATEGORY_CHARGES, T.BACKUP_ENGINE_CATEGORY_HISTORY, T.BACKUP_ENGINE_CATEGORY_SCOOTER_PREFERENCES};
    static boolean namespace(String name){return Arrays.asList(NAMESPACES).contains(name);}

    /** Storage roots below the app data directory, as `files/rides`, `databases/x` and so on. */
    static final String[][] ROOTS = {
        {"files/rides", RIDES}, {"files/phone-rides", RIDES}, {"files/charge-recordings", CHARGES}, {"databases/telemetry-history.db", HISTORY},
        {"files/pending-owner-import.json", null, PENDING}, {"files/pending-owner-import.tmp", null, PENDING}, {"files/pending-export-", null, PENDING},
        {"databases/telemetry-history.db-journal", null, PENDING}, {"files/missing-export", null, "Placeholder for an absent pending export"}, {"no_backup/connection-events", null, "Device diagnostics"}, {"no_backup/connection-events.tmp", null, "Device diagnostics"}, {"no_backup/command-events", null, "Device diagnostics"}, {"no_backup/command-events.tmp", null, "Device diagnostics"},
        // Guided setting test records stay on this phone (decision D4): never exported, cleared or replaced.
        {"no_backup/compat-tests", null, "Guided-test records of this installation"}, {"no_backup/compat-tests.tmp", null, "Guided-test records of this installation"},
        {"no_backup/app-data-transfer", null, PENDING}, {"no_backup/ride-transfer", null, PENDING},
        // Saved ride summaries (RideSummaryStore): a rebuildable cache of this installation's journals, never exported or restored.
        {"no_backup/rides-index", null, DEVICE},
        {"cache/", null, "Disposable cache"}, {"shared_prefs/", null, "Preferences are classified key by key"},
    };
    /** Category of a storage root, null for an excluded root, or IllegalArgumentException if unclassified. */
    static String root(String path) {
        for(String[] root:ROOTS)if(path.equals(root[0])||path.startsWith(root[0]+(root[0].endsWith("/")||root[0].endsWith("-")?"":"/")))return root[1];
        throw new IllegalArgumentException("Unclassified storage root "+path);
    }

    /** Typed checks for every registered key, replacing the old hand-written lists. Unknown keys
     * stay portable for exact restores; only registered keys must have their reader's type. */
    static void validateTypes(Map<String,Map<String,Object>> all) throws IOException {
        for(Map.Entry<String,Map<String,Object>> namespace:all.entrySet()) {
            if(!namespace(namespace.getKey()))throw new BackupException(M.backupEngineBackupUnsupportedPreferenceGroup());
            for(Map.Entry<String,Object> entry:namespace.getValue().entrySet()) {
                Rule rule=rule(namespace.getKey(),entry.getKey());
                if(rule!=null&&!rule.type.isInstance(entry.getValue()))throw new BackupException(M.backupEngineBackupIncompatibleAppPreference());
            }
        }
        Map<String,Object> app=all.get("app_settings");
        if(app!=null&&app.containsKey("text_scale")){float scale=(Float)app.get("text_scale");if(!Float.isFinite(scale)||scale<.5f||scale>3f)throw new BackupException(M.backupEngineBackupInvalidDisplayText());}
    }
}
