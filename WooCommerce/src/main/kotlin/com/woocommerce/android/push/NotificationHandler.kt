package com.woocommerce.android.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.NotificationManager.IMPORTANCE_DEFAULT
import android.app.PendingIntent
import android.content.ContentResolver.SCHEME_ANDROID_RESOURCE
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.support.v4.app.NotificationCompat
import android.support.v4.app.NotificationManagerCompat
import android.support.v4.content.ContextCompat
import com.woocommerce.android.AppPrefs
import com.woocommerce.android.R
import com.woocommerce.android.analytics.AnalyticsTracker
import com.woocommerce.android.analytics.AnalyticsTracker.Stat
import com.woocommerce.android.push.NotificationHandler.NotificationChannelType.NEW_ORDER
import com.woocommerce.android.push.NotificationHandler.NotificationChannelType.OTHER
import com.woocommerce.android.push.NotificationHandler.NotificationChannelType.REVIEW
import com.woocommerce.android.util.NotificationsUtils
import com.woocommerce.android.util.WooLog
import com.woocommerce.android.util.WooLog.T
import org.apache.commons.text.StringEscapeUtils
import org.wordpress.android.fluxc.model.AccountModel
import org.wordpress.android.util.ImageUtils
import org.wordpress.android.util.PhotonUtils
import org.wordpress.android.util.StringUtils
import java.io.UnsupportedEncodingException
import java.net.URLDecoder

object NotificationHandler {
    private val ACTIVE_NOTIFICATIONS_MAP = mutableMapOf<Int, Bundle>()

    private const val NOTIFICATION_GROUP_KEY = "notification_group_key"
    private const val PUSH_NOTIFICATION_ID = 10000
    const val GROUP_NOTIFICATION_ID = 30000
    private const val MAX_INBOX_ITEMS = 5

    private const val PUSH_ARG_USER = "user"
    private const val PUSH_ARG_TYPE = "type"
    private const val PUSH_ARG_TITLE = "title"
    private const val PUSH_ARG_MSG = "msg"
    private const val PUSH_ARG_NOTE_ID = "note_id"

    private const val PUSH_TYPE_COMMENT = "c"
    private const val PUSH_TYPE_NEW_ORDER = "store_order"

    /**
     * Note that we have separate notification channels for orders with and without the cha-ching sound - this is
     * necessary because once a channel is created we can't change it, and if we delete the channel and re-create
     * it then it will be re-created with the same settings it previously had (ie: we can't simply have a single
     * channel for orders and add/remove the sound from it)
     */
    private enum class NotificationChannelType {
        OTHER,
        REVIEW,
        NEW_ORDER
    }

    @Synchronized fun hasNotifications() = !ACTIVE_NOTIFICATIONS_MAP.isEmpty()

    @Synchronized fun clearNotifications() {
        ACTIVE_NOTIFICATIONS_MAP.clear()
    }

    @Synchronized fun removeNotification(localPushId: Int) {
        ACTIVE_NOTIFICATIONS_MAP.remove(localPushId)
    }

    // TODO: this is useful while we're developing notifications but can be removed when we're done
    fun testNotification(context: Context, title: String, message: String, account: AccountModel) {
        val data = Bundle()
        data.putString(PUSH_ARG_TYPE, PUSH_TYPE_NEW_ORDER)
        data.putString(PUSH_ARG_TITLE, title)
        data.putString(PUSH_ARG_MSG, message)
        data.putString(PUSH_ARG_USER, account.userId.toString())
        buildAndShowNotificationFromNoteData(context, data, account)
    }

    fun buildAndShowNotificationFromNoteData(context: Context, data: Bundle, account: AccountModel) {
        if (data.isEmpty) {
            WooLog.e(T.NOTIFS, "Push notification received without a valid Bundle!")
            return
        }

        val wpComNoteId = data.getString(PUSH_ARG_NOTE_ID, "")
        // TODO Temporarily disabled so it's easier to test spoofed notifications, restore
//        if (wpComNoteId.isNullOrEmpty()) {
//            // At this point 'note_id' is always available in the notification bundle.
//            WooLog.e(T.NOTIFS, "Push notification received without a valid note_id in the payload!")
//            return
//        }

        val pushUserId = data.getString(PUSH_ARG_USER)
        // pushUserId is always set server side, but better to double check it here.
        if (account.userId.toString() != pushUserId) {
            WooLog.e(T.NOTIFS, "WP.com userId found in the app doesn't match with the ID in the PN. Aborting.")
            return
        }

        // TODO: Store note object in database

        val noteTypeStr = StringUtils.notNullStr(data.getString(PUSH_ARG_TYPE))
        val noteType = when (noteTypeStr) {
            PUSH_TYPE_NEW_ORDER -> {
                NEW_ORDER
            }
            PUSH_TYPE_COMMENT -> {
                REVIEW
            }
            else -> {
                OTHER
            }
        }

        // skip if user chose to disable this type of notification
        if ((noteType == NEW_ORDER && !AppPrefs.isOrderNotificationsEnabled()) ||
                (noteType == REVIEW && !AppPrefs.isReviewNotificationsEnabled())) {
            WooLog.i(T.NOTIFS, "Skipped $noteTypeStr notification")
            return
        }

        val title = if (noteType == NEW_ORDER) {
            // New order notifications have title 'WordPress.com' - just show the app name instead
            // TODO Consider revising this, perhaps use the contents of the note as the title/body of the notification
            context.getString(R.string.app_name)
        } else {
            StringEscapeUtils.unescapeHtml4(data.getString(PUSH_ARG_TITLE))
                    ?: context.getString(R.string.app_name)
        }

        val message = StringEscapeUtils.unescapeHtml4(data.getString(PUSH_ARG_MSG))

        val localPushId = getLocalPushIdForWpComNoteId(wpComNoteId)
        ACTIVE_NOTIFICATIONS_MAP[localPushId] = data

        if (NotificationsUtils.isNotificationsEnabled(context)) {
            bumpPushNotificationsAnalytics(context, Stat.PUSH_NOTIFICATION_RECEIVED, data)
            AnalyticsTracker.flush()
        }

        // make sure we have a notification channel for this note type
        createNotificationChannel(context, noteType)

        // Build the new notification, add group to support wearable stacking
        val builder = getNotificationBuilder(context, noteType, title, message)
        val largeIconBitmap = getLargeIconBitmap(context, data.getString("icon"),
                shouldCircularizeNoteIcon(noteType))
        largeIconBitmap?.let { builder.setLargeIcon(it) }

        showSingleNotificationForBuilder(context, builder, noteType, wpComNoteId, localPushId)

        // Also add a group summary notification, which is required for non-wearable devices
        // Do not need to play the sound again. We've already played it in the individual builder.
        showGroupNotificationForBuilder(context, builder, noteType, wpComNoteId, message)
    }

    /**
     * For a given remote note ID, return a unique local ID to track that notification with, or return
     * the existing local ID if a notification matching the remote note ID is already being displayed.
     */
    private fun getLocalPushIdForWpComNoteId(wpComNoteId: String): Int {
        // Update notification content for the same noteId if it is already showing
        for (id in ACTIVE_NOTIFICATIONS_MAP.keys) {
            val noteBundle = ACTIVE_NOTIFICATIONS_MAP[id]
            if (noteBundle?.getString(PUSH_ARG_NOTE_ID, "") == wpComNoteId) {
                return id
            }
        }

        // Notification isn't already showing
        return PUSH_NOTIFICATION_ID + ACTIVE_NOTIFICATIONS_MAP.size
    }

    private fun getLargeIconBitmap(context: Context, iconUrl: String?, shouldCircularizeIcon: Boolean): Bitmap? {
        iconUrl?.let {
            try {
                val decodedIconUrl = URLDecoder.decode(iconUrl, "UTF-8")
                val largeIconSize = context.resources.getDimensionPixelSize(
                        android.R.dimen.notification_large_icon_height)
                val resizedUrl = PhotonUtils.getPhotonImageUrl(decodedIconUrl, largeIconSize, largeIconSize)

                val largeIconBitmap = ImageUtils.downloadBitmap(resizedUrl)
                if (largeIconBitmap != null && shouldCircularizeIcon) {
                    return ImageUtils.getCircularBitmap(largeIconBitmap)
                }

                return largeIconBitmap
            } catch (e: UnsupportedEncodingException) {
                WooLog.e(T.NOTIFS, e)
            }
        }
        return null
    }

    private fun getChannelIdForNoteType(context: Context, noteType: NotificationChannelType): String {
        return when (noteType) {
            NEW_ORDER -> context.getString(R.string.notification_channel_order_id)
            REVIEW -> context.getString(R.string.notification_channel_review_id)
            else -> context.getString(R.string.notification_channel_general_id)
        }
    }

    private fun getChannelTitleForNoteType(context: Context, noteType: NotificationChannelType): String {
        return when (noteType) {
            NEW_ORDER -> context.getString(R.string.notification_channel_order_title)
            REVIEW -> context.getString(R.string.notification_channel_review_title)
            else -> context.getString(R.string.notification_channel_general_title)
        }
    }

    /**
     * Ensures the desired notification channel is created when on API 26+, does nothing otherwise since notification
     * channels weren't added until API 26
     */
    private fun createNotificationChannel(context: Context, noteType: NotificationChannelType) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = getChannelIdForNoteType(context, noteType)

            // check for existing channel first
            manager.getNotificationChannel(channelId)?.let {
                return
            }

            // create the channel since it doesn't already exist
            val channelName = getChannelTitleForNoteType(context, noteType)
            val channel = NotificationChannel(channelId, channelName, IMPORTANCE_DEFAULT)

            // add cha-ching sound to new order notifications
            if (noteType == NEW_ORDER) {
                val attributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .build()
                channel.setSound(getChaChingUri(context), attributes)
            }

            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Called at startup to ensure we create the notification channels
     */
    fun createNotificationChannels(context: Context) {
        for (noteType in NotificationChannelType.values()) {
            createNotificationChannel(context, noteType)
        }
    }

    /**
     * Returns the URI to use for the cha-ching order notification sound
     */
    private fun getChaChingUri(context: Context): Uri {
        return Uri.parse(SCHEME_ANDROID_RESOURCE + "://" + context.packageName + "/" + R.raw.cha_ching)
    }

    /**
     * Returns true if the note type is known to have a Gravatar.
     */
    private fun shouldCircularizeNoteIcon(noteType: NotificationChannelType): Boolean {
        return when (noteType) {
            REVIEW -> true
            else -> false
        }
    }

    private fun getNotificationBuilder(
        context: Context,
        noteType: NotificationChannelType,
        title: String,
        message: String?
    ): NotificationCompat.Builder {
        val channelId = getChannelIdForNoteType(context, noteType)
        return NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_woo_w_notification)
                .setColor(ContextCompat.getColor(context, R.color.wc_purple))
                .setContentTitle(title)
                .setContentText(message)
                .setTicker(message)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setGroup(NOTIFICATION_GROUP_KEY)
    }

    private fun showSingleNotificationForBuilder(
        context: Context,
        builder: NotificationCompat.Builder,
        noteType: NotificationChannelType,
        wpComNoteId: String,
        pushId: Int
    ) {
        when (noteType) {
            NEW_ORDER -> {
                if (AppPrefs.isOrderNotificationsChaChingEnabled()) {
                    builder.setDefaults(NotificationCompat.DEFAULT_LIGHTS or NotificationCompat.DEFAULT_VIBRATE)
                    builder.setSound(getChaChingUri(context))
                } else {
                    builder.setDefaults(NotificationCompat.DEFAULT_ALL)
                }
            }
            REVIEW -> {
                builder.setDefaults(NotificationCompat.DEFAULT_ALL)
                // TODO: Add quick actions for reviews
            }
            else -> {
                builder.setDefaults(NotificationCompat.DEFAULT_ALL)
            }
        }

        showWPComNotificationForBuilder(builder, context, wpComNoteId, pushId)
    }

    private fun showGroupNotificationForBuilder(
        context: Context,
        builder: NotificationCompat.Builder,
        noteType: NotificationChannelType,
        wpComNoteId: String,
        message: String?
    ) {
        // Using a copy of the map to avoid concurrency problems
        val notesMap = ACTIVE_NOTIFICATIONS_MAP.toMap()
        if (notesMap.size > 1) {
            val inboxStyle = NotificationCompat.InboxStyle()

            var noteCounter = 1
            for (pushBundle in notesMap.values) {
                // InboxStyle notification is limited to 5 lines
                if (noteCounter > MAX_INBOX_ITEMS) break

                // Skip notes with no content from the 5-line inbox
                if (pushBundle.getString(PUSH_ARG_MSG) == null) continue

                if (noteType == REVIEW) {
                    val pnTitle = StringEscapeUtils.unescapeHtml4(pushBundle.getString(PUSH_ARG_TITLE))
                    val pnMessage = StringEscapeUtils.unescapeHtml4(pushBundle.getString(PUSH_ARG_MSG))
                    inboxStyle.addLine("$pnTitle: $pnMessage")
                } else {
                    val pnMessage = StringEscapeUtils.unescapeHtml4(pushBundle.getString(PUSH_ARG_MSG))
                    inboxStyle.addLine(pnMessage)
                }

                noteCounter++
            }

            if (notesMap.size > MAX_INBOX_ITEMS) {
                inboxStyle.setSummaryText(
                        String.format(context.getString(R.string.more_notifications), notesMap.size - MAX_INBOX_ITEMS)
                )
            }

            val subject = String.format(context.getString(R.string.new_notifications), notesMap.size)
            val groupBuilder = NotificationCompat.Builder(context, getChannelIdForNoteType(context, noteType))
                    .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                    .setSmallIcon(R.drawable.ic_woo_w_notification)
                    .setColor(ContextCompat.getColor(context, R.color.wc_purple))
                    .setGroup(NOTIFICATION_GROUP_KEY)
                    .setGroupSummary(true)
                    .setAutoCancel(true)
                    .setTicker(message)
                    .setContentTitle(context.getString(R.string.app_name))
                    .setContentText(subject)
                    .setStyle(inboxStyle)
                    .setSound(null)
                    .setVibrate(null)

            showWPComNotificationForBuilder(groupBuilder, context, wpComNoteId, GROUP_NOTIFICATION_ID)
        } else {
            // Set the individual notification we've already built as the group summary
            builder.setGroupSummary(true)
                    .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            showWPComNotificationForBuilder(builder, context, wpComNoteId, GROUP_NOTIFICATION_ID)
        }
    }

    /**
     * Creates a notification for a WordPress.com note, attaching an intent for the note's tap action.
     */
    private fun showWPComNotificationForBuilder(
        builder: NotificationCompat.Builder,
        context: Context,
        wpComNoteId: String,
        pushId: Int
    ) {
        // TODO Create an Intent containing the wpComNoteId that launches the MainActivity to handle the tap action
        // (and open the notifications tab)
        val resultIntent = Intent() // placeholder
        showNotificationForBuilder(builder, context, resultIntent, pushId)
    }

    // Displays a notification to the user
    private fun showNotificationForBuilder(
        builder: NotificationCompat.Builder,
        context: Context,
        resultIntent: Intent,
        pushId: Int
    ) {
        // Call processing service when notification is dismissed
        val pendingDeleteIntent = NotificationsProcessingService.getPendingIntentForNotificationDismiss(context, pushId)
        builder.setDeleteIntent(pendingDeleteIntent)

        builder.setCategory(NotificationCompat.CATEGORY_SOCIAL)

        val pendingIntent = PendingIntent.getActivity(context, pushId, resultIntent,
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_UPDATE_CURRENT)
        builder.setContentIntent(pendingIntent)
        val notificationManager = NotificationManagerCompat.from(context)
        notificationManager.notify(pushId, builder.build())
    }

    /**
     * Attach default properties and track given analytics for the given notifications-related [stat].
     *
     * Will skip tracking if user has disabled notifications from being shown at the app system settings level.
     */
    private fun bumpPushNotificationsAnalytics(context: Context, stat: Stat, noteBundle: Bundle) {
        if (!NotificationsUtils.isNotificationsEnabled(context)) return

        val wpComNoteId = noteBundle.getString(PUSH_ARG_NOTE_ID, "")
        if (wpComNoteId.isNotEmpty()) {
            val properties = mutableMapOf<String, Any>()
            properties["notification_note_id"] = wpComNoteId

            noteBundle.getString(PUSH_ARG_TYPE)?.takeUnless { it.isEmpty() }?.let { noteType ->
                // 'comment' types are sent in PN as type = "c"
                properties["notification_type"] = if (noteType == PUSH_TYPE_COMMENT) "comment" else noteType
            }

            val preferences = PreferenceManager.getDefaultSharedPreferences(context)
            val latestGCMToken = preferences.getString(FCMRegistrationIntentService.WPCOM_PUSH_DEVICE_TOKEN, null)
            properties["push_notification_token"] = latestGCMToken ?: ""
            AnalyticsTracker.track(stat, properties)
        }
    }
}
