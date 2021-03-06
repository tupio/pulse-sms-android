package xyz.klinker.messenger.shared.util

import android.annotation.TargetApi
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

import java.util.ArrayList
import java.util.HashSet

import xyz.klinker.messenger.shared.data.model.Conversation

class DynamicShortcutUtils(private val context: Context) {

    fun buildDynamicShortcuts(conversations: List<Conversation>) {
        var conversations = conversations
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            if (conversations.size > 3) {
                conversations = conversations.subList(0, 3)
            }

            val infos = ArrayList<ShortcutInfoCompat>()

            for (conversation in conversations) {
                val messenger = ActivityUtils.buildForComponent(ActivityUtils.MESSENGER_ACTIVITY)
                messenger.action = Intent.ACTION_VIEW
                messenger.data = Uri.parse("https://messenger.klinkerapps.com/" + conversation.id)

                val category = HashSet<String>()
                category.add("android.shortcut.conversation")

                val icon = getIcon(conversation)
                val info = ShortcutInfoCompat.Builder(context, conversation.id.toString())
                        .setIntent(messenger)
                        .setShortLabel(conversation.title ?: "No title")
                        .setCategories(category)
                        .setLongLived()
                        .setIcon(icon)
                        .setPerson(Person.Builder()
                                .setName(conversation.title)
                                .setUri(messenger.dataString)
                                .setIcon(icon)
                                .build()
                        ).build()

                infos.add(info)
            }

            ShortcutManagerCompat.addDynamicShortcuts(context, infos)
        }
    }

    private fun getIcon(conversation: Conversation): IconCompat? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val image = ImageUtils.getBitmap(context, conversation.imageUri)

            if (image != null) {
                createIcon(image)
            } else {
                val color = ContactImageCreator.getLetterPicture(context, conversation)
                createIcon(color)
            }
        } else {
            null
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun createIcon(bitmap: Bitmap?): IconCompat {
        return if (AndroidVersionUtil.isAndroidO) {
            IconCompat.createWithAdaptiveBitmap(bitmap)
        } else {
            val circleBitmap = ImageUtils.clipToCircle(bitmap)
            IconCompat.createWithBitmap(circleBitmap)
        }
    }
}
