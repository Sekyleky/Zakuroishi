package io.github.zakuro.android;

import java.nio.charset.StandardCharsets;
import org.json.*;

/** Run on Android's JSON implementation with app_process; no app data is touched. */
public final class IisuConfigCheck {
    private static void require(boolean ok) { if(!ok) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        String original="{\"keep\":true,\"consoles\":[{\"shortName\":\"n3ds\",\"romExtensions\":[\".3ds\"],\"emulators\":[{\"id\":\"AZAHAR\",\"keep\":7}]},{\"shortName\":\"gba\",\"keep\":9}]}";
        byte[] patched=FrontendExport.patchIisu(original.getBytes(StandardCharsets.UTF_8));
        JSONObject json=new JSONObject(new String(patched,StandardCharsets.UTF_8));
        require(json.getBoolean("keep"));
        JSONArray consoles=json.getJSONArray("consoles");
        require(consoles.getJSONObject(1).getInt("keep")==9);
        JSONObject n3ds=consoles.getJSONObject(0);
        require(n3ds.getJSONArray("romExtensions").toString().equals("[\".3ds\",\".zkr\"]"));
        JSONArray emulators=n3ds.getJSONArray("emulators");
        require(emulators.length()==2 && emulators.getJSONObject(0).getInt("keep")==7);
        require(emulators.getJSONObject(1).getString("id").equals("ZAKURO_ANDROID"));
        require(java.util.Arrays.equals(patched,FrontendExport.patchIisu(patched)));
        for(String bad:new String[]{"{}", "{\"consoles\":[]}",original.replace("\"gba\"","\"3ds\"")}) {
            try { FrontendExport.patchIisu(bad.getBytes(StandardCharsets.UTF_8)); throw new AssertionError("Accepted malformed/ambiguous catalog"); }
            catch(java.io.IOException | JSONException expected) { }
        }
        try { FrontendExport.patchIisu(new byte[1024*1024+1]); throw new AssertionError(); } catch(java.io.IOException expected) { }
        JSONObject cocoon=new JSONObject(FrontendExport.cocoonConfig());
        require(cocoon.getJSONObject("platform").getString("uniqueId").equals("zakuro.n3ds"));
        require(cocoon.getJSONArray("playerList").getJSONObject(0).getString("amStartArguments").contains("-d {file.uri}"));
        require(new JSONObject(FrontendExport.iisuEntry()).getJSONArray("commands").getJSONObject(0).getString("command").startsWith(FrontendExport.COMPONENT+" "));
        if(args.length==2) {
            byte[] real=java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(args[0]));
            java.nio.file.Files.write(java.nio.file.Paths.get(args[1]),FrontendExport.patchIisu(real));
        }
        System.out.println("iiSU/Cocoon checks passed: preserve other entries, repeat safely, add .zkr once, reject malformed/oversized/ambiguous catalogs, distinct launch templates.");
    }
}
