/*
 * Copyright (C) 2016 Jacob Klinker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package xyz.klinker.messenger.shared.service;

import android.app.AlarmManager;
import android.app.IntentService;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.support.annotation.VisibleForTesting;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.app.RemoteInput;
import android.text.Html;
import android.text.Spanned;
import android.util.LongSparseArray;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import xyz.klinker.messenger.shared.MessengerActivityExtras;
import xyz.klinker.messenger.shared.R;
import xyz.klinker.messenger.api.implementation.Account;
import xyz.klinker.messenger.shared.data.DataSource;
import xyz.klinker.messenger.shared.data.MimeType;
import xyz.klinker.messenger.shared.data.Settings;
import xyz.klinker.messenger.shared.data.model.Conversation;
import xyz.klinker.messenger.shared.data.model.Message;
import xyz.klinker.messenger.shared.data.pojo.NotificationAction;
import xyz.klinker.messenger.shared.data.pojo.NotificationConversation;
import xyz.klinker.messenger.shared.data.pojo.NotificationMessage;
import xyz.klinker.messenger.shared.data.pojo.VibratePattern;
import xyz.klinker.messenger.shared.receiver.CarReplyReceiver;
import xyz.klinker.messenger.shared.receiver.NotificationDismissedReceiver;
import xyz.klinker.messenger.shared.util.ActivityUtils;
import xyz.klinker.messenger.shared.util.ImageUtils;
import xyz.klinker.messenger.shared.util.NotificationWindowManager;
import xyz.klinker.messenger.shared.util.TimeUtils;
import xyz.klinker.messenger.shared.util.TvUtils;
import xyz.klinker.messenger.shared.view.NotificationView;
import xyz.klinker.messenger.shared.widget.MessengerAppWidgetProvider;

/**
 * Service for displaying notifications to the user based on which conversations have not been
 * seen yet.
 */
public class NotificationService extends IntentService {

    protected static final boolean DEBUG_QUICK_REPLY = false;
    protected static final boolean AUTO_CANCEL = true;
    
    public static Long CONVERSATION_ID_OPEN = 0L;

    private static final String GROUP_KEY_MESSAGES = "messenger_notification_group";
    public static final int SUMMARY_ID = 0;

    private NotificationWindowManager notificationWindowManager;

    private boolean skipSummaryNotification = false;

    public NotificationService() {
        super("NotificationService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        long snoozeTil = Settings.get(this).snooze;
        if (snoozeTil > System.currentTimeMillis()) {
            return;
        }

        skipSummaryNotification = false;

        LongSparseArray<NotificationConversation> conversations = getUnseenConversations();
        List<String> rows = new ArrayList<>();

        if (conversations.size() > 0) {
            NotificationManagerCompat.from(this).cancelAll();

            for (int i = 0; i < conversations.size(); i++) {
                NotificationConversation conversation = conversations.get(conversations.keyAt(i));
                rows.add(giveConversationNotification(conversation, i, conversations.size()));
            }

            if (conversations.size() > 1) {
                giveSummaryNotification(conversations, rows);
            }

            Settings settings = Settings.get(this);
            if (settings.repeatNotifications != -1) {
                NotificationService.scheduleNextRun(this, System.currentTimeMillis() + settings.repeatNotifications);
            }

            if (Settings.get(this).wakeScreen) {
                new Thread(() -> {
                    try {
                        Thread.sleep(600);
                    } catch (Exception e) { }

                    PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                    PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "NEW_NOTIFICATION");
                    wl.acquire(5000);
                }).start();
            }
        }

        MessengerAppWidgetProvider.refreshWidget(this);
    }

    @VisibleForTesting
    LongSparseArray<NotificationConversation> getUnseenConversations() {
        DataSource source = getDataSource();
        source.open();

        Cursor unseenMessages = source.getUnseenMessages();
        LongSparseArray<NotificationConversation> conversations = new LongSparseArray<>();

        if (unseenMessages != null && unseenMessages.moveToFirst()) {
            do {
                long conversationId = unseenMessages
                        .getLong(unseenMessages.getColumnIndex(Message.COLUMN_CONVERSATION_ID));
                long id = unseenMessages
                        .getLong(unseenMessages.getColumnIndex(Message.COLUMN_ID));
                String data = unseenMessages
                        .getString(unseenMessages.getColumnIndex(Message.COLUMN_DATA));
                String mimeType = unseenMessages
                        .getString(unseenMessages.getColumnIndex(Message.COLUMN_MIME_TYPE));
                long timestamp = unseenMessages
                        .getLong(unseenMessages.getColumnIndex(Message.COLUMN_TIMESTAMP));
                String from = unseenMessages
                        .getString(unseenMessages.getColumnIndex(Message.COLUMN_FROM));

                if (!MimeType.isExpandedMedia(mimeType)) {
                    NotificationConversation conversation = conversations.get(conversationId);

                    if (conversation == null) {
                        Conversation c = source.getConversation(conversationId);
                        if (c != null && !c.mute) {
                            conversation = new NotificationConversation();
                            conversation.id = c.id;
                            conversation.unseenMessageId = id;
                            conversation.title = c.title;
                            conversation.imageUri = c.imageUri;
                            conversation.color = c.colors.color;
                            conversation.ringtoneUri = c.ringtoneUri;
                            conversation.ledColor = c.ledColor;
                            conversation.timestamp = c.timestamp;
                            conversation.mute = c.mute;
                            conversation.phoneNumbers = c.phoneNumbers;
                            conversation.groupConversation = c.phoneNumbers.contains(",");

                            if (c.privateNotifications) {
                                conversation.title = getString(R.string.new_message);
                                conversation.imageUri = null;
                                conversation.ringtoneUri = null;
                                conversation.color = Settings.get(this).globalColorSet.color;
                                conversation.privateNotification = true;
                                conversation.ledColor = Color.WHITE;
                            } else {
                                conversation.privateNotification = false;
                            }

                            conversations.put(conversationId, conversation);
                        }
                    }

                    if (conversation != null) {
                        conversation.messages.add(new NotificationMessage(id, data, mimeType, timestamp, from));
                    }
                }
            } while (unseenMessages.moveToNext());
        }

        try {
            unseenMessages.close();
        } catch (Exception e) { }

        source.close();

        return conversations;
    }

    /**
     * Displays a notification for a single conversation.
     */
    private String giveConversationNotification(NotificationConversation conversation, int conversationIndex, int numConversations) {
        Bitmap contactImage = ImageUtils.clipToCircle(
                ImageUtils.getBitmap(this, conversation.imageUri));

        try {
            float height = getResources().getDimension(android.R.dimen.notification_large_icon_height);
            float width = getResources().getDimension(android.R.dimen.notification_large_icon_width);
            contactImage = Bitmap.createScaledBitmap(contactImage, (int) width, (int) height, true);
        } catch (Exception e) { }

        VibratePattern vibratePattern = Settings.get(this).vibrate;
        int defaults = 0;
        if (vibratePattern == VibratePattern.DEFAULT) {
            defaults = Notification.DEFAULT_VIBRATE;
        }

        if (conversation.ledColor == Color.WHITE) {
            defaults = defaults | Notification.DEFAULT_LIGHTS;
        }

        Settings settings = Settings.get(this);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this)
                .setSmallIcon(!conversation.groupConversation ? R.drawable.ic_stat_notify : R.drawable.ic_stat_notify_group)
                .setContentTitle(conversation.title)
                .setAutoCancel(AUTO_CANCEL)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setColor(settings.useGlobalThemeColor ? settings.globalColorSet.color : conversation.color)
                .setDefaults(defaults)
                .setGroup(GROUP_KEY_MESSAGES)
                .setLargeIcon(contactImage)
                .setOnlyAlertOnce(this instanceof RepeatNotificationService ?
                        false : shouldAlertOnce(conversation.messages))
                .setPriority(Notification.PRIORITY_HIGH)
                .setShowWhen(true)
                .setTicker(getString(R.string.notification_ticker, conversation.title))
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setWhen(conversation.timestamp);

        if (numConversations == 1) {
            builder.setGroupSummary(true);
        }

        if (conversation.ledColor != Color.WHITE) {
            builder.setLights(conversation.ledColor, 1000, 500);
        }

        Uri sound = getRingtone(this, conversation.ringtoneUri);
        if (conversationIndex == 0) {
            if (sound != null) {
                builder.setSound(sound);
            }

            if (vibratePattern.pattern != null) {
                builder.setVibrate(vibratePattern.pattern);
            } else if (vibratePattern == VibratePattern.OFF) {
                builder.setVibrate(new long[0]);
            }
        }

        try {
            if (!conversation.groupConversation) {
                builder.addPerson("tel:" + conversation.phoneNumbers);
            }
        } catch (Exception e) { }

        NotificationCompat.BigPictureStyle pictureStyle = null;
        NotificationCompat.InboxStyle inboxStyle = null;
        NotificationCompat.Style messagingStyle = null;

        StringBuilder text = new StringBuilder();
        if (conversation.messages.size() > 1 && conversation.messages.get(0).from != null) {
            inboxStyle = new NotificationCompat.InboxStyle();

            for (NotificationMessage message : conversation.messages) {
                if (message.mimeType.equals(MimeType.TEXT_PLAIN)) {
                    String line = "<b>" + message.from + ":</b>  " + message.data;
                    text.append(line);
                    text.append("\n");
                    inboxStyle.addLine(Html.fromHtml(line));
                } else {
                    pictureStyle = new NotificationCompat.BigPictureStyle()
                            .bigPicture(ImageUtils.getBitmap(this, message.data));
                }
            }
        } else {
            for (int i = 0; i < conversation.messages.size(); i++) {
                NotificationMessage message = conversation.messages.get(i);

                if (message.mimeType.equals(MimeType.TEXT_PLAIN)) {
                    if (message.from != null) {
                        text.append("<b>");
                        text.append(message.from);
                        text.append(":</b>  ");
                        text.append(conversation.messages.get(i).data);
                        text.append("\n");
                    } else {
                        text.append(conversation.messages.get(i).data);
                        text.append(" | ");
                    }
                } else if (MimeType.isStaticImage(message.mimeType)) {
                    pictureStyle = new NotificationCompat.BigPictureStyle()
                            .bigPicture(ImageUtils.getBitmap(this, message.data));
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // build a messaging style notifation for Android Nougat
            messagingStyle = new NotificationCompat.MessagingStyle(getString(R.string.you));

            if (conversation.groupConversation) {
                ((NotificationCompat.MessagingStyle) messagingStyle)
                        .setConversationTitle(conversation.title);
            }

            DataSource source = getDataSource();
            source.open();
            List<Message> messages = source.getMessages(conversation.id, 10);
            source.close();

            for (int i = messages.size() - 1; i >= 0; i--) {
                Message message = messages.get(i);

                String from = null;
                if (message.type == Message.TYPE_RECEIVED) {
                    // we split it so that we only get the first name,
                    // if there is more than one

                    if (message.from != null) {
                        // it is most likely a group message.
                        from = message.from;
                    } else {
                        from = conversation.title;
                    }
                }

                String messageText = "";
                if (MimeType.isAudio(message.mimeType)) {
                    messageText += "<i>" + getString(R.string.audio_message) + "</i>";
                } else if (MimeType.isVideo(message.mimeType)) {
                    messageText += "<i>" + getString(R.string.video_message) + "</i>";
                } else if (MimeType.isVcard(message.mimeType)) {
                    messageText += "<i>" + getString(R.string.contact_card) + "</i>";
                } else if (MimeType.isStaticImage(message.mimeType)) {
                    messageText += "<i>" + getString(R.string.picture_message) + "</i>";
                } else if (message.mimeType.equals(MimeType.IMAGE_GIF)) {
                    messageText += "<i>" + getString(R.string.gif_message) + "</i>";
                } else if (MimeType.isExpandedMedia(message.mimeType)) {
                    messageText += "<i>" + getString(R.string.media) + "</i>";
                } else {
                    messageText += message.data;
                }

                ((NotificationCompat.MessagingStyle) messagingStyle)
                        .addMessage(Html.fromHtml(messageText), message.timestamp, from);
            }
        }

        String content = text.toString().trim();
        if (content.endsWith(" |")) {
            content = content.substring(0, content.length() - 2);
        }

        if (!conversation.privateNotification) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                builder.setContentText(Html.fromHtml(content, 0));
            } else {
                builder.setContentText(Html.fromHtml(content));
            }

            if (pictureStyle != null) {
                builder.setStyle(pictureStyle);
                builder.setContentText(getString(R.string.picture_message));
            } else if (messagingStyle != null) {
                builder.setStyle(messagingStyle);
            } else if (inboxStyle != null) {
                builder.setStyle(inboxStyle);
            } else {
                builder.setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(Html.fromHtml(content)));
            }
        }

        NotificationCompat.Builder publicVersion = new NotificationCompat.Builder(this)
                .setSmallIcon(!conversation.groupConversation ? R.drawable.ic_stat_notify : R.drawable.ic_stat_notify_group)
                .setContentTitle(getResources().getQuantityString(R.plurals.new_conversations, 1, 1))
                .setContentText(getResources().getQuantityString(R.plurals.new_messages,
                        conversation.messages.size(), conversation.messages.size()))
                .setLargeIcon(null)
                .setColor(settings.useGlobalThemeColor ? settings.globalColorSet.color : conversation.color)
                .setAutoCancel(AUTO_CANCEL)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setDefaults(defaults)
                .setGroup(GROUP_KEY_MESSAGES)
                .setVisibility(Notification.VISIBILITY_PUBLIC);

        if (conversation.ledColor != Color.WHITE) {
            builder.setLights(conversation.ledColor, 1000, 500);
        }

        if (conversationIndex == 0) {
            if (sound != null) {
                builder.setSound(sound);
            }

            if (vibratePattern.pattern != null) {
                builder.setVibrate(vibratePattern.pattern);
            } else if (vibratePattern == VibratePattern.OFF) {
                builder.setVibrate(new long[0]);
            }
        }

        if (numConversations == 1) {
            publicVersion.setGroupSummary(true);
        }

        try {
            if (!conversation.groupConversation) {
                publicVersion.addPerson("tel:" + conversation.phoneNumbers);
            }
        } catch (Exception e) { }

        builder.setPublicVersion(publicVersion.build());


        // one thing to keep in mind here... my adding only a wearable extender to the notification,
        // will the action be shown on phones or only on wear devices? If it is shown on phones, is
        // it only shown on Nougat+ where these actions can be accepted?
        RemoteInput remoteInput = new RemoteInput.Builder(ReplyService.EXTRA_REPLY)
                .setLabel(getString(R.string.reply_to, conversation.title))
                .setChoices(getResources().getStringArray(R.array.reply_choices))
                .setAllowFreeFormInput(true)
                .build();


        // Android wear extender (add a second page with message history
        NotificationCompat.BigTextStyle secondPageStyle = new NotificationCompat.BigTextStyle();
        secondPageStyle.setBigContentTitle(conversation.title)
                .bigText(getWearableSecondPageConversation(conversation));
        NotificationCompat.Builder wear =
                new NotificationCompat.Builder(this)
                        .setStyle(secondPageStyle);

        NotificationCompat.WearableExtender wearableExtender =
                new NotificationCompat.WearableExtender().addPage(wear.build());

        NotificationCompat.Action.WearableExtender actionExtender =
                new NotificationCompat.Action.WearableExtender()
                        .setHintLaunchesActivity(true)
                        .setHintDisplayActionInline(true);

        PendingIntent pendingReply;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !DEBUG_QUICK_REPLY) {
            // with Android N, we only need to show the the reply service intent through the wearable extender
            Intent reply = new Intent(this, ReplyService.class);
            reply.putExtra(ReplyService.EXTRA_CONVERSATION_ID, conversation.id);
            pendingReply = PendingIntent.getService(this,
                    (int) conversation.id, reply, PendingIntent.FLAG_UPDATE_CURRENT);

            NotificationCompat.Action action = new NotificationCompat.Action.Builder(R.drawable.ic_reply_white,
                    getString(R.string.reply), pendingReply)
                    .addRemoteInput(remoteInput)
                    .setAllowGeneratedReplies(true)
                    .extend(actionExtender)
                    .build();

            if (!conversation.privateNotification && settings.notificationActions.contains(NotificationAction.REPLY)) {
                builder.addAction(action);
            }

            wearableExtender.addAction(action);
        } else {
            // on older versions, we have to show the reply activity button as an action and add the remote input to it
            // this will allow it to be used on android wear (we will have to handle this from the activity)
            // as well as have a reply quick action button.
            Intent reply = ActivityUtils.buildForComponent(ActivityUtils.NOTIFICATION_REPLY);
            reply.putExtra(ReplyService.EXTRA_CONVERSATION_ID, conversation.id);
            reply.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            pendingReply = PendingIntent.getActivity(this,
                    (int) conversation.id, reply, PendingIntent.FLAG_UPDATE_CURRENT);

            if (DEBUG_QUICK_REPLY) {
                // if we are debugging, the assumption is that we are on android N, we have to be stop showing
                // the remote input or else it will keep using the direct reply
                NotificationCompat.Action action = new NotificationCompat.Action.Builder(R.drawable.ic_reply_dark,
                        getString(R.string.reply), pendingReply)
                        .extend(actionExtender)
                        .setAllowGeneratedReplies(true)
                        .build();

                if (!conversation.privateNotification && settings.notificationActions.contains(NotificationAction.REPLY)) {
                    builder.addAction(action);
                }

                action.icon = R.drawable.ic_reply_white;
                wearableExtender.addAction(action);
            } else {
                NotificationCompat.Action action = new NotificationCompat.Action.Builder(R.drawable.ic_reply_dark,
                        getString(R.string.reply), pendingReply)
                        .build();

                if (!conversation.privateNotification && settings.notificationActions.contains(NotificationAction.REPLY)) {
                    builder.addAction(action);
                }

                Intent wearReply = new Intent(this, ReplyService.class);
                Bundle extras = new Bundle();
                extras.putLong(ReplyService.EXTRA_CONVERSATION_ID, conversation.id);
                wearReply.putExtras(extras);
                PendingIntent wearPendingReply = PendingIntent.getService(this,
                        (int) conversation.id + 1, wearReply, PendingIntent.FLAG_UPDATE_CURRENT);

                NotificationCompat.Action wearAction = new NotificationCompat.Action.Builder(R.drawable.ic_reply_white,
                        getString(R.string.reply), wearPendingReply)
                        .addRemoteInput(remoteInput)
                        .extend(actionExtender)
                        .build();

                wearableExtender.addAction(wearAction);
            }
        }

        if (!conversation.groupConversation && settings.notificationActions.contains(NotificationAction.CALL)
                && (!Account.get(this).exists() || Account.get(this).primary)) {
            Intent call = new Intent(this, NotificationCallService.class);
            call.putExtra(NotificationMarkReadService.EXTRA_CONVERSATION_ID, conversation.id);
            call.putExtra(NotificationCallService.EXTRA_PHONE_NUMBER, conversation.phoneNumbers);
            PendingIntent callPending = PendingIntent.getService(this, (int) conversation.id,
                    call, PendingIntent.FLAG_UPDATE_CURRENT);

            builder.addAction(new NotificationCompat.Action(R.drawable.ic_call_dark, getString(R.string.call), callPending));
        }

        Intent deleteMessage = new Intent(this, NotificationDeleteService.class);
        deleteMessage.putExtra(NotificationDeleteService.EXTRA_CONVERSATION_ID, conversation.id);
        deleteMessage.putExtra(NotificationDeleteService.EXTRA_MESSAGE_ID, conversation.unseenMessageId);
        PendingIntent pendingDeleteMessage = PendingIntent.getService(this, (int) conversation.id,
                deleteMessage, PendingIntent.FLAG_UPDATE_CURRENT);

        wearableExtender.addAction(new NotificationCompat.Action(R.drawable.ic_delete_white, getString(R.string.delete), pendingDeleteMessage));

        if (settings.notificationActions.contains(NotificationAction.READ)) {
            builder.addAction(new NotificationCompat.Action(R.drawable.ic_delete_dark, getString(R.string.delete), pendingDeleteMessage));
        }

        Intent read = new Intent(this, NotificationMarkReadService.class);
        read.putExtra(NotificationMarkReadService.EXTRA_CONVERSATION_ID, conversation.id);
        PendingIntent pendingRead = PendingIntent.getService(this, (int) conversation.id,
                read, PendingIntent.FLAG_UPDATE_CURRENT);

        wearableExtender.addAction(new NotificationCompat.Action(R.drawable.ic_done_white, getString(R.string.read), pendingRead));

        if (settings.notificationActions.contains(NotificationAction.READ)) {
            builder.addAction(new NotificationCompat.Action(R.drawable.ic_done_dark, getString(R.string.read), pendingRead));
        }

        Intent delete = new Intent(this, NotificationDismissedReceiver.class);
        delete.putExtra(NotificationDismissedService.EXTRA_CONVERSATION_ID, conversation.id);
        PendingIntent pendingDelete = PendingIntent.getBroadcast(this, (int) conversation.id,
                delete, PendingIntent.FLAG_UPDATE_CURRENT);

        Intent open = ActivityUtils.buildForComponent(ActivityUtils.MESSENGER_ACTIVITY);
        open.putExtra(MessengerActivityExtras.EXTRA_CONVERSATION_ID, conversation.id);
        open.putExtra(MessengerActivityExtras.EXTRA_FROM_NOTIFICATION, true);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pendingOpen = PendingIntent.getActivity(this,
                (int) conversation.id, open, PendingIntent.FLAG_UPDATE_CURRENT);

        builder.setDeleteIntent(pendingDelete);
        builder.setContentIntent(pendingOpen);

        Intent carReply = new Intent(this, CarReplyReceiver.class);
        carReply.putExtra(ReplyService.EXTRA_CONVERSATION_ID, conversation.id);
        PendingIntent pendingCarReply = PendingIntent.getBroadcast(this, (int) conversation.id,
                carReply, PendingIntent.FLAG_UPDATE_CURRENT);

        // Android Auto extender
        NotificationCompat.CarExtender.UnreadConversation.Builder car = new
                NotificationCompat.CarExtender.UnreadConversation.Builder(conversation.title)
                .setReadPendingIntent(pendingDelete)
                .setReplyAction(pendingCarReply, remoteInput)
                .setLatestTimestamp(conversation.timestamp);

        for (NotificationMessage message : conversation.messages) {
            if (message.mimeType.equals(MimeType.TEXT_PLAIN)) {
                car.addMessage(message.data);
            } else {
                car.addMessage(getString(R.string.new_mms_message));
            }
        }

        // apply the extenders to the notification
        builder.extend(new NotificationCompat.CarExtender().setUnreadConversation(car.build()));
        builder.extend(wearableExtender);

        if (CONVERSATION_ID_OPEN == conversation.id) {
            // skip this notification since we are already on the conversation.
            skipSummaryNotification = true;
        } else {
            NotificationManagerCompat.from(this).notify((int) conversation.id, builder.build());
        }

        try {
            if (!TvUtils.hasTouchscreen(this)) {
                if (notificationWindowManager == null) {
                    notificationWindowManager = new NotificationWindowManager(this);
                }

                NotificationView.newInstance(notificationWindowManager)
                        .setImage(null)
                        .setTitle(conversation.title)
                        .setDescription(content)
                        .show();
            }
        } catch (WindowManager.BadTokenException e) {
            e.printStackTrace();
        }

        return "<b>" + conversation.title + "</b>  " + content;
    }

    /**
     * If the user is getting spammed by the same person over and over again, we don't want to immediately
     * give a vibrate or ringtone again.
     *
     * The 'onlyAlertOnce" flag on the notification builder means that it will not give a vibrate or sound
     * if the notification is already active.
     *
     * @param messages the messages in this conversation
     * @return true if the latest two messages are less than 1 min apart, or there is only one message. False
     *          if the latest messages are more than 1 min apart, so that it will ring again.
     */
    @VisibleForTesting
    protected boolean shouldAlertOnce(List<NotificationMessage> messages) {
        if (messages.size() > 1) {
            NotificationMessage one = messages.get(messages.size() - 2);
            NotificationMessage two = messages.get(messages.size() - 1);

            if (Math.abs(one.timestamp - two.timestamp) > TimeUtils.SECOND * 30) {
                return false;
            }
        }

        // default to true
        return true;
    }

    /**
     * Displays a summary notification for all conversations using the rows returned by each
     * individual notification.
     */
    private void giveSummaryNotification(LongSparseArray<NotificationConversation> conversations,
                                         List<String> rows) {
        StringBuilder summaryBuilder = new StringBuilder();
        for (int i = 0; i < conversations.size(); i++) {
            if (conversations.get(conversations.keyAt(i)).privateNotification) {
                summaryBuilder.append(getString(R.string.new_message));
            } else {
                summaryBuilder.append(conversations.get(conversations.keyAt(i)).title);
            }
            
            summaryBuilder.append(", ");
        }

        String summary = summaryBuilder.toString();
        if (summary.endsWith(", ")) {
            summary = summary.substring(0, summary.length() - 2);
        }

        String title = getResources().getQuantityString(R.plurals.new_conversations,
                conversations.size(), conversations.size());

        NotificationCompat.InboxStyle style = new NotificationCompat.InboxStyle()
                .setSummaryText(summary)
                .setBigContentTitle(title);

        for (String row : rows) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    style.addLine(Html.fromHtml(row, 0));
                } else {
                    style.addLine(Html.fromHtml(row));
                }
            } catch (Throwable t) {
                // there was a motorola device running api 24, but was on 6.0.1? WTF?
                // so catch the throwable instead of checking the api version
                style.addLine(Html.fromHtml(row));
            }
        }

        Notification publicVersion = new NotificationCompat.Builder(this)
                .setSmallIcon(R.drawable.ic_stat_notify_group)
                .setContentTitle(title)
                .setContentText(summary)
                .setGroup(GROUP_KEY_MESSAGES)
                .setGroupSummary(true)
                .setAutoCancel(AUTO_CANCEL)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setColor(Settings.get(this).globalColorSet.color)
                .setPriority(Notification.PRIORITY_HIGH)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build();

        Intent delete = new Intent(this, NotificationDismissedService.class);
        PendingIntent pendingDelete = PendingIntent.getService(this, 0,
                delete, PendingIntent.FLAG_UPDATE_CURRENT);

        Intent open = ActivityUtils.buildForComponent(ActivityUtils.MESSENGER_ACTIVITY);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pendingOpen = PendingIntent.getActivity(this, 0,
                open, PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new NotificationCompat.Builder(this)
                .setSmallIcon(R.drawable.ic_stat_notify_group)
                .setContentTitle(title)
                .setContentText(summary)
                .setGroup(GROUP_KEY_MESSAGES)
                .setGroupSummary(true)
                .setAutoCancel(AUTO_CANCEL)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setColor(Settings.get(this).globalColorSet.color)
                .setPriority(Notification.PRIORITY_HIGH)
                .setShowWhen(true)
                .setTicker(title)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setWhen(conversations.get(conversations.keyAt(conversations.size() - 1)).timestamp)
                .setStyle(style)
                .setPublicVersion(publicVersion)
                .setDeleteIntent(pendingDelete)
                .setContentIntent(pendingOpen)
                .build();

        if (!skipSummaryNotification) {
            NotificationManagerCompat.from(this).notify(SUMMARY_ID, notification);
        }
    }

    private Spanned getWearableSecondPageConversation(NotificationConversation conversation) {
        DataSource source = getDataSource();
        source.open();
        List<Message> messages = source.getMessages(conversation.id, 10);
        source.close();

        String you = getString(R.string.you);

        StringBuilder builder = new StringBuilder();

        for (Message message : messages) {
            String messageText = "";
            if (MimeType.isAudio(message.mimeType)) {
                messageText += "<i>" + getString(R.string.audio_message) + "</i>";
            } else if (MimeType.isVideo(message.mimeType)) {
                messageText += "<i>" + getString(R.string.video_message) + "</i>";
            } else if (MimeType.isVcard(message.mimeType)) {
                messageText += "<i>" + getString(R.string.contact_card) + "</i>";
            } else if (MimeType.isStaticImage(message.mimeType)) {
                messageText += "<i>" + getString(R.string.picture_message) + "</i>";
            } else if (message.mimeType.equals(MimeType.IMAGE_GIF)) {
                messageText += "<i>" + getString(R.string.gif_message) + "</i>";
            } else if (MimeType.isExpandedMedia(message.mimeType)) {
                messageText += "<i>" + getString(R.string.media) + "</i>";
            } else {
                messageText += message.data;
            }

            if (message.type == Message.TYPE_RECEIVED) {
                if (message.from != null) {
                    builder.append("<b>" + message.from + "</b>  " + messageText + "<br>");
                } else {
                    builder.append("<b>" + conversation.title + "</b>  " + messageText + "<br>");
                }
            } else {
                builder.append("<b>" + you + "</b>  " + messageText + "<br>");
            }

        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return Html.fromHtml(builder.toString(), 0);
        } else {
            return Html.fromHtml(builder.toString());
        }
    }

    public static Uri getRingtone(Context context, String conversationRingtone) {
        try {
            String globalUri = Settings.get(context).ringtone;

            if (conversationRingtone == null || conversationRingtone.contains("default") ||
                    conversationRingtone.equals("content://settings/system/notification_sound")) {
                // there is no conversation specific ringtone defined

                if (globalUri == null || globalUri.isEmpty()) {
                    return null;
                } if (ringtoneExists(context, globalUri)) {
                    // the global ringtone is available to use
                    return Uri.parse(globalUri);
                } else {
                    // there is no global ringtone defined, or it doesn't exist on the system
                    return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                }
            } else {
                if (conversationRingtone.isEmpty()) {
                    return null;
                } else if (ringtoneExists(context, conversationRingtone)) {
                    // conversation ringtone exists and can be played
                    return Uri.parse(conversationRingtone);
                } else {
                    // the global ringtone is available to use
                    return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                }
            }
        } catch (Exception e) {
            return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        }
    }

    private static boolean ringtoneExists(Context context, String uri) {
        if (uri.contains("file://")) {
            return false;
        }
        
        return RingtoneManager.getRingtone(context, Uri.parse(uri)) != null;
    }

    @VisibleForTesting
    DataSource getDataSource() {
        return DataSource.getInstance(this);
    }

    public static void cancelRepeats(Context context) {
        scheduleNextRun(context, 0);
    }

    public static void scheduleNextRun(Context context, long nextRun) {
        if (!Account.get(context).exists() || (Account.get(context).exists() && Account.get(context).primary)) {
            Intent intent = new Intent(context, RepeatNotificationService.class);
            PendingIntent pIntent = PendingIntent.getService(context, RepeatNotificationService.REQUEST_CODE,
                    intent, PendingIntent.FLAG_UPDATE_CURRENT);

            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            alarmManager.cancel(pIntent);

            long currentTime = new Date().getTime();
            if (currentTime < nextRun) {
                alarmManager.set(AlarmManager.RTC_WAKEUP, nextRun, pIntent);
            }
        }
    }
}