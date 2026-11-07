package io.github.zakuro.android;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Explicit SAF export: small launch references only, with no ROM/code/save copies. */
final class FrontendExport {
    static final String COMPONENT="io.github.zakuro.android/.FrontendLaunch";
    static final String HELP="Zakuro frontend library\n\n"
        +"Scan the Games folder. Each .zkr file launches a game already installed in Zakuroishi, using its settings and saves. These files are not ROMs. Keep Zakuroishi installed. Re-export to add new games.\n\n"
        +"Cocoon: configure a custom player using config/Cocoon-Zakuro.json (a platform definition following Cocoon's published JSON format). Accept .zkr, .3ds, .cci and .cxi. The launch arguments are:\n"
        +"-n "+COMPONENT+" -a android.intent.action.VIEW -d {file.uri} --grant-read-uri-permission\n\n"
        +"iiSU: select Nintendo 3DS, configure a custom launch command:\n"
        +COMPONENT+" -a android.intent.action.VIEW -d %ROM_URI% --grant-read-uri-permission\n"
        +"Alternatively merge config/iiSU-Zakuro-entry.json into the Nintendo 3DS console's emulators array in a COPY of your own emuladores.json, then import that complete edited copy through iiSU Settings > Updates. The exported entry is a snippet, not a replacement catalog. Back up your catalog first.\n"
        +"If your iiSU build cannot scan .zkr, scan your original decrypted .3ds/.cci/.cxi ROM folder instead. Zakuroishi reads its title ID and launches the installed game without importing it again. Custom-launch syntax and extension controls depend on the frontend version; Zakuroishi is not yet in their built-in player catalogs.\n\n"
        +"Other frontends: use ACTION_VIEW, component "+COMPONENT+", and a readable content:// URI. A per-game URI also works: zakuro://game/TITLE_ID.\n"
        +"Never point another emulator at these .zkr references. They contain no ROM data and are not suitable for ROM hashing/RetroAchievements.\n";

    private static Uri child(Context context, Uri parent, String name, String mime) throws IOException {
        Uri query=DocumentsContract.buildChildDocumentsUriUsingTree(parent,DocumentsContract.getDocumentId(parent));
        Uri found=null;
        try(Cursor cursor=context.getContentResolver().query(query,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE},null,null,null)) {
            if(cursor==null) throw new IOException("Cannot list frontend folder");
            while(cursor.moveToNext()) if(name.equals(cursor.getString(1))) {
                boolean folder=DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                if(found!=null || folder!=DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))) throw new IOException("Conflicting frontend file: "+name);
                found=DocumentsContract.buildDocumentUriUsingTree(parent,cursor.getString(0));
            }
        }
        if(found!=null) return found;
        Uri created=DocumentsContract.createDocument(context.getContentResolver(),parent,mime,name);
        if(created==null) throw new IOException("Cannot create frontend file: "+name);
        return created;
    }
    private static void write(Context context,Uri parent,String name,byte[] bytes) throws IOException {
        Uri file=child(context,parent,name,"application/octet-stream");
        // Never overwrite an unrelated document, even in the user-selected export folder.
        try(InputStream in=context.getContentResolver().openInputStream(file)) {
            if(in==null) throw new IOException("Cannot check frontend file");
            byte[] current=in.readNBytes(65537);
            boolean owned=current.length==0 || Arrays.equals(current,bytes);
            if(!owned && current.length<=65536 && !name.endsWith(".zkr")) {
                String text=new String(current,StandardCharsets.UTF_8);
                try {
                    if(name.equals("README.txt")) owned=text.startsWith("Zakuro frontend library\n\n");
                    else if(name.equals("Cocoon-Zakuro.json")) owned=new JSONObject(text).getJSONObject("platform").optString("uniqueId").equals("zakuro.n3ds");
                    else if(name.equals("iiSU-Zakuro-entry.json")) owned=new JSONObject(text).optString("id").equals("ZAKURO_ANDROID");
                } catch(JSONException ignored) { }
            }
            if(!owned) throw new IOException("Existing frontend file differs: "+name+". Choose an empty export folder.");
        }
        try(OutputStream out=context.getContentResolver().openOutputStream(file,"wt")) {
            if(out==null) throw new IOException("Cannot write frontend file");
            out.write(bytes);
        }
    }
    static String filename(String title,String id) throws IOException {
        String safe=title.replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]"," ").replaceAll("\\s+"," ").trim();
        if(safe.length()>80) safe=safe.substring(0,80);
        if(safe.isEmpty()) safe="Game";
        return safe+" ["+FrontendReference.validId(id)+"].zkr";
    }
    static String cocoonConfig() throws JSONException {
        JSONObject platform=new JSONObject();
        platform.put("name","Zakuroishi (Nintendo 3DS)").put("uniqueId","zakuro.n3ds").put("shortname","n3ds")
            .put("description","Launch games already imported into Zakuroishi")
            .put("acceptedFilenameRegex","^(?!(?:\\._|\\.).*).*\\.(?:zkr|3ds|cci|cxi)$")
            .put("scraperSourceList",new JSONArray().put("LIBRETRO:Nintendo - Nintendo 3DS"))
            .put("boxArtAspectRatioId",0).put("useCustomBoxArtAspectRatio",true).put("customBoxArtAspectRatio","8:7")
            .put("screenAspectRatioId",0).put("useCustomScreenAspectRatio",false).put("customScreenAspectRatio",JSONObject.NULL)
            .put("retroAchievementsConsoleIdList",new JSONArray()).put("extra","");
        JSONObject player=new JSONObject();
        player.put("name","Zakuroishi").put("uniqueId","3ds.io.github.zakuro.android")
            .put("description","Launch an already imported Zakuroishi game from a .zkr reference or original decrypted ROM.")
            .put("acceptedFilenameRegex","(?i)^.*\\.(?:zkr|3ds|cci|cxi)$")
            .put("amStartArguments","-n "+COMPONENT+"\n-a android.intent.action.VIEW\n-d {file.uri}\n--grant-read-uri-permission\n")
            .put("killPackageProcesses",false).put("killPackageProcessesWarning",true).put("extra","");
        return new JSONObject().put("databaseVersion",14).put("revisionNumber",1).put("platform",platform)
            .put("playerList",new JSONArray().put(player)).toString(2);
    }
    static String iisuEntry() throws JSONException {
        JSONObject command=new JSONObject().put("description","Zakuroishi")
            .put("command",COMPONENT+" -a android.intent.action.VIEW -d %ROM_URI% --grant-read-uri-permission");
        return new JSONObject().put("id","ZAKURO_ANDROID").put("name","Zakuroishi").put("routeType","uri")
            .put("commands",new JSONArray().put(command)).put("packages",new JSONArray().put("io.github.zakuro.android")).toString(2);
    }
    static byte[] patchIisu(byte[] original) throws IOException,JSONException {
        if(original.length>1024*1024) throw new IOException("iiSU catalog exceeds 1 MB");
        JSONObject catalog=new JSONObject(new String(original,StandardCharsets.UTF_8));
        JSONArray consoles=catalog.getJSONArray("consoles");
        JSONObject target=null;
        for(int i=0;i<consoles.length();i++) {
            JSONObject console=consoles.getJSONObject(i);
            if(Arrays.asList("n3ds","3ds").contains(console.optString("shortName"))) {
                if(target!=null) throw new IOException("Ambiguous Nintendo 3DS console in iiSU catalog");
                target=console;
            }
        }
        if(target==null) throw new IOException("No Nintendo 3DS console in this iiSU catalog");
        JSONArray existing=target.getJSONArray("emulators"), merged=new JSONArray();
        for(int i=0;i<existing.length();i++) {
            JSONObject entry=existing.getJSONObject(i);
            if(!entry.optString("id").equals("ZAKURO_ANDROID")) merged.put(entry);
        }
        merged.put(new JSONObject(iisuEntry())); target.put("emulators",merged);
        JSONArray extensions=target.getJSONArray("romExtensions");
        boolean included=false;
        for(int i=0;i<extensions.length();i++) included|=".zkr".equalsIgnoreCase(extensions.getString(i));
        if(!included) extensions.put(".zkr");
        return catalog.toString(2).getBytes(StandardCharsets.UTF_8);
    }
    static int export(Context context,Uri tree) throws Exception {
        // Validate every installed game before creating anything in the destination.
        File[] pointers=new File(context.getFilesDir(),"imports").listFiles();
        Map<String,String> titles=new TreeMap<>();
        if(pointers!=null) for(File pointer:pointers) if(pointer.getName().matches("[0-9A-F]{16}\\.active(\\.bak)?")) {
            String id=pointer.getName().substring(0,16);
            File version=GameImport.active(context,id);
            String name=GameSettings.load(context,id).name;
            titles.put(id,name.isEmpty()?GameMetadata.name(version,id):name);
        }
        if(titles.isEmpty()) throw new IOException("Import a game before exporting your frontend library");
        Uri selected=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
        Uri root=child(context,selected,"Zakuro frontend",DocumentsContract.Document.MIME_TYPE_DIR);
        Uri games=child(context,root,"Games",DocumentsContract.Document.MIME_TYPE_DIR);
        Uri config=child(context,root,"config",DocumentsContract.Document.MIME_TYPE_DIR);
        for(Map.Entry<String,String> game:titles.entrySet()) write(context,games,filename(game.getValue(),game.getKey()),FrontendReference.encode(game.getKey()));
        write(context,config,"Cocoon-Zakuro.json",cocoonConfig().getBytes(StandardCharsets.UTF_8));
        write(context,config,"iiSU-Zakuro-entry.json",iisuEntry().getBytes(StandardCharsets.UTF_8));
        write(context,root,"README.txt",HELP.getBytes(StandardCharsets.UTF_8));
        return titles.size();
    }
}
