package dev.softkeys.ios;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Paint;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Unicode catalog with device glyph filtering, named categories and tone variants. */
final class EmojiCatalog {
    static final String[] NAMES={"Recent","Smileys","People","Animals","Food","Travel","Activities","Objects","Symbols","Flags"};
    static final String[] ICONS={"◷","😀","👋","🐻","🍎","🚗","⚽","💡","❤️","🏁"};
    static final class Item {
        final String emoji,name;final int category;final List<String> variants=new ArrayList<>();
        Item(String emoji,String name,int category){this.emoji=emoji;this.name=name;this.category=category;}
    }
    final List<Item> items=new ArrayList<>();final Map<String,Item> byEmoji=new HashMap<>();
    EmojiCatalog(Context context) throws IOException {
        Paint glyph=new Paint();glyph.setTextSize(32);int category=1;
        Map<String,Item> bases=new HashMap<>();
        try(BufferedReader in=new BufferedReader(new InputStreamReader(context.getAssets().open("emoji-test.txt"),StandardCharsets.UTF_8))) {
            String line;
            while((line=in.readLine())!=null) {
                if(line.startsWith("# group:")) {String group=line.substring(8).trim();category=switch(group){case "Smileys & Emotion"->1;case "People & Body"->2;case "Animals & Nature"->3;case "Food & Drink"->4;case "Travel & Places"->5;case "Activities"->6;case "Objects"->7;case "Symbols"->8;case "Flags"->9;default->-1;};continue;}
                if(category<0||!line.contains("; fully-qualified"))continue;
                String[] parts=line.split("#",2);String hex=parts[0].substring(0,parts[0].indexOf(';')).trim();StringBuilder emoji=new StringBuilder(),base=new StringBuilder();boolean tone=false;
                for(String cp:hex.split("\\s+")){int point=Integer.parseInt(cp,16);emoji.appendCodePoint(point);if(point>=0x1f3fb&&point<=0x1f3ff)tone=true;else base.appendCodePoint(point);}
                String value=emoji.toString();if(!glyph.hasGlyph(value))continue;
                String name=parts[1].trim().replaceFirst("^\\S+ E[0-9.]+ ","");
                if(tone){Item parent=bases.get(base.toString());if(parent==null)parent=bases.get(base+"\uFE0F");if(parent!=null)parent.variants.add(value);continue;}
                Item item=new Item(value,name,category);items.add(item);byEmoji.put(value,item);bases.put(value,item);
            }
        }
        for(Item item:items)for(String variant:item.variants)byEmoji.put(variant,item);
    }
    List<Item> select(int category,String query,SharedPreferences prefs) {
        List<Item> result=new ArrayList<>();String search=query.toLowerCase(Locale.US).trim();
        if(!search.isEmpty()){for(Item item:items)if(item.name.contains(search))result.add(item);return result;}
        if(category==0){for(String value:prefs.getString("recentEmoji","😂\n❤️\n👍\n😊\n😭\n🙏\n🔥\n🎉").split("\n")){Item item=byEmoji.get(value);if(item!=null)result.add(new Item(value,item.name,item.category));}return result;}
        for(Item item:items)if(item.category==category)result.add(item);return result;
    }
    void used(String value,SharedPreferences prefs){LinkedHashSet<String> recent=new LinkedHashSet<>();recent.add(value);recent.addAll(Arrays.asList(prefs.getString("recentEmoji","").split("\n")));recent.remove("");List<String> ordered=new ArrayList<>(recent);prefs.edit().putString("recentEmoji",String.join("\n",ordered.subList(0,Math.min(40,ordered.size())))).apply();}
}
