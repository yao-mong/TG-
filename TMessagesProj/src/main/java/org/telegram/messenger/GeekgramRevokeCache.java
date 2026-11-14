/*
 * Geekgram - 本地缓存防撤回
 *
 * 对方撤回消息时,Telegram 会直接把这条消息从本地库删掉,删完就再也看不到了。
 * 这里在消息真正被删除之前,把内容抄一份到 App 私有的 geekgram/revoked.json,
 * 之后可以在 设置 -> Geekgram -> 撤回记录 里翻看、复制。
 */
package org.telegram.messenger;

import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Locale;

public class GeekgramRevokeCache {

    private static final String PREF_ENABLED = "geekgram_revoke_enabled";
    private static final String DIR = "geekgram";
    private static final String FILE = "revoked.json";
    private static final int MAX_ENTRIES = 500;

    public static class Entry {
        public long dialogId;
        public int messageId;
        public int date;
        public long fromId;
        public String text;
        public String name;

        public String toJson() {
            try {
                JSONObject o = new JSONObject();
                o.put("did", dialogId);
                o.put("mid", messageId);
                o.put("date", date);
                o.put("from", fromId);
                o.put("text", text == null ? "" : text);
                return o.toString();
            } catch (Throwable e) {
                return null;
            }
        }

        public static Entry fromJson(JSONObject o) {
            Entry e = new Entry();
            e.dialogId = o.optLong("did", 0);
            e.messageId = o.optInt("mid", 0);
            e.date = o.optInt("date", 0);
            e.fromId = o.optLong("from", 0);
            e.text = o.optString("text", "");
            return e;
        }
    }

    public static boolean isEnabled() {
        return MessagesController.getGlobalMainSettings().getBoolean(PREF_ENABLED, true);
    }

    public static void setEnabled(boolean enabled) {
        MessagesController.getGlobalMainSettings().edit().putBoolean(PREF_ENABLED, enabled).apply();
    }

    private static File getFile() {
        File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, FILE);
    }

    /**
     * 在消息被真正删除前抄一份。只在存储线程里调用。
     * 只缓存对方发来的消息(out = 0),自己删自己的不记录。
     */
    public static void capture(SQLiteDatabase database, int account, long dialogId, ArrayList<Integer> messages) {
        if (!isEnabled() || database == null || dialogId == 0 || messages == null || messages.isEmpty()) {
            return;
        }
        // 整段清空(清聊天记录、清空频道)这类不记
        if (messages.size() > 300) {
            return;
        }
        final String ids = TextUtils.join(",", messages);
        SQLiteCursor cursor = null;
        final ArrayList<Entry> found = new ArrayList<>();
        try {
            cursor = database.queryFinalized(String.format(Locale.US,
                    "SELECT mid, date, out, data FROM messages_v2 WHERE uid = %d AND mid IN(%s)", dialogId, ids));
            while (cursor.next()) {
                final int mid = (int) cursor.longValue(0);
                final int date = (int) cursor.longValue(1);
                final int out = (int) cursor.longValue(2);
                if (out != 0) {
                    continue;
                }
                NativeByteBuffer data = cursor.byteBufferValue(3);
                if (data == null) {
                    continue;
                }
                String text = null;
                long fromId = 0;
                try {
                    TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                    if (message != null) {
                        text = extractText(message);
                        fromId = MessageObject.getPeerId(message.from_id != null ? message.from_id : message.peer_id);
                    }
                } catch (Throwable ignore) {
                }
                data.reuse();
                if (TextUtils.isEmpty(text)) {
                    continue;
                }
                Entry entry = new Entry();
                entry.dialogId = dialogId;
                entry.messageId = mid;
                entry.date = date;
                entry.fromId = fromId;
                entry.text = text;
                found.add(entry);
            }
        } catch (Throwable ignore) {
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
        if (!found.isEmpty()) {
            append(found);
        }
    }

    private static String extractText(TLRPC.Message message) {
        if (!TextUtils.isEmpty(message.message)) {
            return message.message;
        }
        TLRPC.MessageMedia media = message.media;
        if (media == null) {
            if (message.action != null) {
                return "[服务消息]";
            }
            return null;
        }
        if (media instanceof TLRPC.TL_messageMediaPhoto) {
            return "[图片]";
        }
        if (media instanceof TLRPC.TL_messageMediaDocument) {
            String tag = "[文件]";
            if (media.document != null) {
                for (int a = 0; a < media.document.attributes.size(); a++) {
                    TLRPC.DocumentAttribute attr = media.document.attributes.get(a);
                    if (attr instanceof TLRPC.TL_documentAttributeSticker) {
                        tag = "[贴纸]";
                        break;
                    } else if (attr instanceof TLRPC.TL_documentAttributeVideo) {
                        tag = "[视频]";
                        break;
                    } else if (attr instanceof TLRPC.TL_documentAttributeAudio) {
                        tag = ((TLRPC.TL_documentAttributeAudio) attr).voice ? "[语音]" : "[音频]";
                        break;
                    } else if (attr instanceof TLRPC.TL_documentAttributeAnimated) {
                        tag = "[动图]";
                    }
                }
            }
            return tag;
        }
        if (media instanceof TLRPC.TL_messageMediaGeo || media instanceof TLRPC.TL_messageMediaVenue) {
            return "[位置]";
        }
        if (media instanceof TLRPC.TL_messageMediaContact) {
            return "[联系人]";
        }
        if (media instanceof TLRPC.TL_messageMediaPoll) {
            return "[投票]";
        }
        if (media instanceof TLRPC.TL_messageMediaWebPage) {
            return "[链接]";
        }
        return null;
    }

    private static synchronized void append(ArrayList<Entry> fresh) {
        try {
            ArrayList<Entry> all = readAll();
            // 新的放前面,同一聊天同一消息去重
            for (int i = 0; i < fresh.size(); i++) {
                Entry e = fresh.get(i);
                for (int a = all.size() - 1; a >= 0; a--) {
                    if (all.get(a).dialogId == e.dialogId && all.get(a).messageId == e.messageId) {
                        all.remove(a);
                        break;
                    }
                }
                all.add(0, e);
            }
            while (all.size() > MAX_ENTRIES) {
                all.remove(all.size() - 1);
            }
            writeAll(all);
        } catch (Throwable ignore) {
        }
    }

    private static ArrayList<Entry> readAll() {
        ArrayList<Entry> list = new ArrayList<>();
        File file = getFile();
        if (!file.exists()) {
            return list;
        }
        try {
            FileInputStream in = new FileInputStream(file);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) > 0) {
                bos.write(buf, 0, read);
            }
            in.close();
            String raw = bos.toString("UTF-8");
            if (TextUtils.isEmpty(raw)) {
                return list;
            }
            JSONArray arr = new JSONArray(raw);
            for (int a = 0; a < arr.length(); a++) {
                JSONObject o = arr.optJSONObject(a);
                if (o != null) {
                    list.add(Entry.fromJson(o));
                }
            }
        } catch (Throwable ignore) {
        }
        return list;
    }

    private static void writeAll(ArrayList<Entry> list) {
        try {
            JSONArray arr = new JSONArray();
            for (int a = 0; a < list.size(); a++) {
                String line = list.get(a).toJson();
                if (line != null) {
                    arr.put(new JSONObject(line));
                }
            }
            File file = getFile();
            FileOutputStream out = new FileOutputStream(file);
            out.write(arr.toString().getBytes("UTF-8"));
            out.close();
        } catch (Throwable ignore) {
        }
    }

    /** 供界面读取,会顺手把聊天名字补上 */
    public static ArrayList<Entry> load() {
        ArrayList<Entry> list = readAll();
        for (int a = 0; a < list.size(); a++) {
            list.get(a).name = resolveName(list.get(a));
        }
        return list;
    }

    public static int count() {
        return readAll().size();
    }

    public static void clear() {
        try {
            File file = getFile();
            if (file.exists()) {
                file.delete();
            }
        } catch (Throwable ignore) {
        }
    }

    private static String resolveName(Entry e) {
        try {
            MessagesController controller = MessagesController.getInstance(UserConfig.selectedAccount);
            if (e.dialogId > 0) {
                TLRPC.User user = controller.getUser(e.dialogId);
                if (user != null) {
                    String name = ContactsController.formatName(user.first_name, user.last_name);
                    if (!TextUtils.isEmpty(name)) {
                        return name;
                    }
                    if (!TextUtils.isEmpty(user.username)) {
                        return "@" + user.username;
                    }
                }
            } else {
                TLRPC.Chat chat = controller.getChat(-e.dialogId);
                if (chat != null && !TextUtils.isEmpty(chat.title)) {
                    return chat.title;
                }
            }
        } catch (Throwable ignore) {
        }
        return "聊天 " + e.dialogId;
    }
}
