package com.theveloper.pixelplay.presentation.model

import com.theveloper.pixelplay.data.backup.model.BackupSection

/**
 * Backup sections only a tinkerer would recognise: transition rules, engagement stats, QuickFill
 * genres, artist images and AI activity logs. The export picker and the restore list show them
 * only in Developer mode; whether they are selected stays as it is (everything is included by
 * default), so hiding them never drops data from a backup or a restore.
 */
val BackupSection.isTechnical: Boolean
    get() = when (this) {
        BackupSection.TRANSITIONS,
        BackupSection.ENGAGEMENT_STATS,
        BackupSection.QUICK_FILL,
        BackupSection.ARTIST_IMAGES,
        BackupSection.AI_USAGE_LOGS -> true
        else -> false
    }

/** The sections the backup pickers list: all of them in Developer mode, the plain ones otherwise. */
fun visibleBackupSections(sections: Collection<BackupSection>, developerMode: Boolean): List<BackupSection> =
    if (developerMode) sections.toList() else sections.filterNot { it.isTechnical }
